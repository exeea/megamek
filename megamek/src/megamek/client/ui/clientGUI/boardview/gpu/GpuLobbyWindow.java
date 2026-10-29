/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationListener;
import jakarta.annotation.Nullable;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.BotFactory;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.BehaviorSettingsFactory;
import megamek.client.bot.ui.swing.BotGUI;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyMenuEntry;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbyUnit;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ChatterBox;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.dialogs.buttonDialogs.BotConfigDialog;
import megamek.client.ui.dialogs.iconChooser.CamoChooserDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.panels.phaseDisplay.lobby.ChatLounge;
import megamek.client.ui.panels.phaseDisplay.lobby.PlayerSettingsDialog;
import megamek.client.ui.tileset.EntityImage;
import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.entity.GameEntityNewEvent;
import megamek.common.event.entity.GameEntityRemoveEvent;
import megamek.common.event.player.GamePlayerChangeEvent;
import megamek.common.event.player.GamePlayerChatEvent;
import megamek.common.event.player.GamePlayerConnectedEvent;
import megamek.common.event.player.GamePlayerDisconnectedEvent;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.loaders.MapSettings;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;
import megamek.server.ServerBoardHelper;

/**
 * The game lobby as a scene of MegaMek's one native window ({@link GpuHost}), for games MegaMek hosts or joins and
 * games MekHQ starts alike. It shows while the client's game is in the lounge; the client's Swing frame stays hidden.
 * This controller lives on the Swing thread: it listens to the client's game, sends each change to the screen
 * ({@link GpuLobbyScreen}) as a {@link LobbySnapshot}, and runs the screen's actions on the Swing thread, where the
 * game is.
 *
 * <p>Ready runs the Swing lobby's own ready step ({@code ChatLounge.ready()}), whose questions ask through
 * {@code SwingLobbyPrompts}: Swing dialogs owned by the hidden client frame. While a red warning blocks readying, the
 * top bar lights its warnings instead of that step's blocked dialog. This scene owns the dialogs natively, as the board
 * and the start screen own theirs, so they stay in front of the window, and a modal one blocks the screen's input.
 * Leave and the window's close box run the client's own exit ({@link ClientGUI#handleExit}), with its save question.
 * </p>
 *
 * <p>When the scene fails, for example because OpenGL can't start, the client's Swing frame shows with the Swing lobby
 * instead.</p>
 */
public final class GpuLobbyWindow {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyWindow.class);
    /** How often, and how long at most, a new bot is checked for having joined, to put it on its team. */
    private static final int BOT_JOIN_POLL_MS = 250;
    private static final int BOT_JOIN_WAIT_MS = 10_000;
    /** How long the map waits for changes to settle before it is drawn again. */
    private static final int MAP_DELAY_MS = 150;
    /** How long a sent ready state shows before the server's echo, at most. */
    private static final long PENDING_READY_NANOS = 3_000_000_000L;

    /** EDT: the lobby holding the window or about to. */
    private static GpuLobbyWindow active;
    /** EDT: the client whose lobby a scene it opened, such as the 3D preview or the board editor, replaced. */
    private static ClientGUI displaced;
    /** The client whose lobby an open from another thread is about to show. */
    private static volatile ClientGUI pending;

    private final ClientGUI gui;
    private final Set<Dialog> modalDialogs = new LinkedHashSet<>();
    private final Map<Dialog, Boolean> dialogOnTop = new HashMap<>();
    private final AWTEventListener dialogListener = this::dialogEvent;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final GameListener gameListener = new GameListenerAdapter() {
        @Override
        public void gamePlayerConnected(GamePlayerConnectedEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerDisconnected(GamePlayerDisconnectedEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerChange(GamePlayerChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerChat(GamePlayerChatEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePhaseChange(GamePhaseChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gameEntityNew(GameEntityNewEvent event) {
            queueRefresh();
        }

        @Override
        public void gameEntityRemove(GameEntityRemoveEvent event) {
            queueRefresh();
        }

        @Override
        public void gameEntityChange(GameEntityChangeEvent event) {
            queueRefresh();
        }
    };
    private final GpuHost.Scene scene = new GpuHost.Scene() {
        @Override
        public ApplicationListener listener() {
            GpuLobbyScreen created = new GpuLobbyScreen(GpuLobbyWindow.this, GUIPreferences.getInstance());
            screen = created;
            spritesStale = true;
            queueRefresh();
            return created;
        }

        @Override
        public String title() {
            return Messages.getString("Lobby.windowTitle");
        }

        @Override
        public void started(long windowHandle) {
            nativeOwner = GpuNativeDialogOwner.forGlfwWindow(windowHandle);
            SwingUtilities.invokeLater(GpuLobbyWindow.this::presented);
        }

        @Override
        public void closeRequested() {
            if (modalOpen) {
                SwingUtilities.invokeLater(GpuLobbyWindow.this::frontModal);
            } else {
                leave();
            }
        }

        @Override
        public void focusGained() {
            if (modalOpen) {
                SwingUtilities.invokeLater(GpuLobbyWindow.this::frontModal);
            }
        }

        @Override
        public void ended(Throwable failure) {
            SwingUtilities.invokeLater(() -> finished(failure));
        }
    };

    /** Whether a modal dialog is open; the GPU thread reads it to send clicks on the window to that dialog. */
    private volatile boolean modalOpen;
    private volatile boolean closing;
    private volatile GpuNativeDialogOwner nativeOwner;
    /** The screen while its listener exists; written on the GPU thread. */
    private volatile GpuLobbyScreen screen;
    /** The newest snapshot, which a screen shows as it is created. */
    private volatile LobbySnapshot snapshot = LobbySnapshot.EMPTY;
    /** EDT: the ready state just sent, shown until the server echoes it or {@link #PENDING_READY_NANOS} pass. */
    private Boolean pendingReady;
    private long pendingSince;
    private volatile GpuLobbyProbe probe;
    private boolean exitRequested;
    /** EDT: the map settings whose custom deployment zones {@link #customZones} holds. */
    private MapSettings zonesOf;
    private Collection<Integer> customZones = List.of();
    private PlayerSettingsDialog playerSettings;
    /** EDT: the Swing unit menu whose items the screen shows, until one is chosen. */
    private JPopupMenu unitMenu;
    /** EDT: the sprite keys whose images went to the screen, and the keys of the units it last got. */
    private final Set<String> spriteKeys = new HashSet<>();
    private Map<Integer, String> sentSprites = Map.of();
    /** Set when a new screen needs every sprite again. */
    private volatile boolean spritesStale;
    /** EDT: what the map picture last drawn, or being drawn, shows; the newest drawing's number, and its delay. */
    private MapKey mapKey;
    private int mapGeneration;
    private Timer mapTimer;
    private volatile BufferedImage lastMap;

    private GpuLobbyWindow(ClientGUI gui) {
        this.gui = gui;
    }

    /**
     * Any thread: shows {@code gui}'s lobby in the native window, replacing the scene there, or brings it to the front
     * when it already shows. Nothing for a bot's client, which has no screen.
     */
    public static void open(ClientGUI gui) {
        if (gui.getClient() instanceof BotClient) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            pending = gui;
            SwingUtilities.invokeLater(() -> open(gui));
            return;
        }
        if (pending == gui) {
            pending = null;
        }
        displaced = null;
        if (active != null) {
            if (active.gui == gui && !active.closing) {
                GpuHost.focus();
                return;
            }
            active.close();
        }
        GpuLobbyWindow window = new GpuLobbyWindow(gui);
        active = window;
        gui.getClient().getGame().addGameListener(window.gameListener);
        Toolkit.getDefaultToolkit().addAWTEventListener(window.dialogListener,
              AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
        window.refresh();
        GpuHost.show(window.scene);
        window.probe = GpuLobbyProbe.applyOnce(gui);
    }

    /** EDT: ends {@code gui}'s lobby scene, if it shows; the scene that replaces it, if any, decides what shows next. */
    public static void closeFor(ClientGUI gui) {
        if (pending == gui) {
            pending = null;
        }
        if (displaced == gui) {
            displaced = null;
        }
        if (active != null && active.gui == gui) {
            active.close();
        }
    }

    /**
     * EDT: when a scene that replaced a lobby closes, for example the 3D preview or the board editor it opened, shows
     * that lobby again if {@code owner} is its client's frame or a window of it and the game is still in the lounge.
     * Returns whether it did.
     */
    static boolean reopenFor(@Nullable Window owner) {
        ClientGUI gui = displaced;
        if (gui == null) {
            return false;
        }
        for (Window current = owner; current != null; current = current.getOwner()) {
            if (current == gui.getFrame()) {
                displaced = null;
                if (!gui.getClient().getGame().getPhase().isLounge()) {
                    return false;
                }
                open(gui);
                return true;
            }
        }
        return false;
    }

    /** Whether {@code gui}'s lobby holds the native window or is about to, so its Swing frame stays hidden. */
    public static boolean isActiveFor(ClientGUI gui) {
        GpuLobbyWindow window = active;
        return pending == gui || window != null && window.gui == gui && !window.closing;
    }

    /** EDT: whether a lobby holds the window and is not closing. */
    static boolean isShowing() {
        return active != null && !active.closing;
    }

    private void close() {
        closing = true;
        GpuHost.end(scene);
    }

    /** The client whose lobby this is. */
    ClientGUI gui() {
        return gui;
    }

    /** The developer switch's shot and quit, or null; see {@link GpuLobbyProbe}. */
    GpuLobbyProbe probe() {
        return probe;
    }

    /** The newest snapshot of the lobby. */
    LobbySnapshot snapshot() {
        return snapshot;
    }

    /** Whether a modal Swing dialog over the lobby is open. */
    boolean modalOpen() {
        return modalOpen;
    }

    private void queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            // Later, so the chat box has taken the same event first
            SwingUtilities.invokeLater(this::refresh);
        }
    }

    /** EDT: reads the lobby and hands it to the screen. */
    private void refresh() {
        refreshQueued.set(false);
        if (closing) {
            return;
        }
        LobbySnapshot read;
        try {
            ChatterBox chat = gui.getChatterBox();
            read = LobbySnapshot.of(gui::getClient, chat == null ? List.of() : chat.lines(LobbySnapshot.CHAT_LINES),
                  customZones(), unitOrder(), gui.chatlounge == null ? LobbyBattlefield.AutoResolve.DEFAULT
                        : gui.chatlounge.autoResolveSettings());
            bakeMap();
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the game");
            return;
        }
        if (pendingReady != null) {
            if (read.localReady() == pendingReady || System.nanoTime() - pendingSince > PENDING_READY_NANOS) {
                pendingReady = null;
            } else {
                read = read.withLocalReady(pendingReady);
            }
        }
        snapshot = read;
        LobbySnapshot shownRead = read;
        sendSprites(shownRead);
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.show(shownRead);
            }
        });
    }

    // ---- The screen's actions, from the GPU thread ----

    /** Any thread: toggles the local player's ready state through the lobby's ready checks. */
    void ready() {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            if (gui.chatlounge == null) {
                return;
            }
            Player local = gui.getClient().getLocalPlayer();
            boolean readying = local != null && !local.isDone();
            // A red warning blocks readying: the bar lights them, and their tooltips say why, instead of a dialog
            if (readying && !gui.chatlounge.lobbyCommands().readyBlockers().isEmpty()) {
                GpuHost.post(() -> {
                    GpuLobbyScreen shown = screen;
                    if (shown != null) {
                        shown.pulseWarnings();
                    }
                });
                return;
            }
            gui.chatlounge.ready();
            if (local != null && local.isDone() != readying) {
                // The server echoes the state a moment later; until then the bar and the card show it at once
                pendingReady = readying;
                pendingSince = System.nanoTime();
                // Read again once the server has had its time, in case its echo never comes
                Timer expiry = new Timer((int) (PENDING_READY_NANOS / 1_000_000L) + 1, event -> queueRefresh());
                expiry.setRepeats(false);
                expiry.start();
            }
            queueRefresh();
        });
    }

    /** Any thread: leaves the game the way the client's exit does, after its save question. */
    void leave() {
        SwingUtilities.invokeLater(() -> {
            if (closing || exitRequested) {
                return;
            }
            exitRequested = true;
            try {
                gui.handleExit();
            } finally {
                exitRequested = false;
            }
        });
    }

    /** Any thread: sends a chat line, or runs a client command such as /who, as the chat box's input line does. */
    void chat(String text) {
        if (text.isBlank()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ChatterBox chat = gui.getChatterBox();
            if (!closing && chat != null) {
                chat.submit(text);
                queueRefresh();
            }
        });
    }

    /**
     * EDT: the custom deployment zones of the board the map settings make, as the Swing lobby's deployment menu lists
     * them; read again only when the map settings change, as reading them builds the board.
     */
    private Collection<Integer> customZones() {
        MapSettings settings = gui.getClient().getMapSettings();
        if (settings != zonesOf) {
            zonesOf = settings;
            try {
                Board board = settings == null ? null : ServerBoardHelper.getPossibleGameBoard(settings, true);
                customZones = board == null ? List.of() : List.copyOf(board.getCustomDeploymentZones());
            } catch (RuntimeException failure) {
                LOGGER.warn(failure, "The lobby could not read the custom deployment zones");
                customZones = List.of();
            }
        }
        return customZones;
    }

    /** Any thread: runs {@code action} with the player actions on the Swing thread, then shows what changed. */
    private void players(Consumer<LobbyPlayerController> action) {
        SwingUtilities.invokeLater(() -> {
            if (closing || gui.chatlounge == null) {
                return;
            }
            action.accept(new LobbyPlayerController(gui::getClient, gui.chatlounge.lobbyCommands(), dialogs));
            queueRefresh();
        });
    }

    /** Any thread: opens the player settings of the local player or their bot {@code id}. */
    void playerSettings(int id) {
        players(controller -> controller.playerSettings(id));
    }

    /** Any thread: asks for a camouflage for the local player or their bot {@code id}. */
    void camouflage(int id) {
        players(controller -> controller.camouflage(id));
    }

    /** Any thread: moves the local player or their bot {@code id} to {@code team}. */
    void changeTeam(int id, int team) {
        players(controller -> controller.changeTeam(id, team));
    }

    /** Any thread: sets the deployment area of the local player or their bot {@code id}. */
    void changeDeployment(int id, int position) {
        players(controller -> controller.changeDeployment(id, position));
    }

    /** Any thread: asks for the Game Master role, or gives it up. */
    void toggleGameMaster() {
        players(LobbyPlayerController::toggleGameMaster);
    }

    /** Any thread: opens the setup of the local bot {@code id}. */
    void botSettings(int id) {
        players(controller -> controller.botSettings(id));
    }

    /** Any thread: removes the local bot {@code id}. */
    void removeBot(int id) {
        players(controller -> controller.removeBot(id));
    }

    /** Any thread: has a new bot take the place of the disconnected player {@code id}. */
    void replaceWithBot(int id) {
        players(controller -> controller.replaceWithBot(id));
    }

    /** Any thread: adds a bot, through its setup dialog, on {@code team}. */
    void addBot(int team) {
        players(controller -> controller.addBot(team));
    }

    // ---- The forces column ----

    /** Any thread: runs {@code action} with the unit and formation actions on the Swing thread, then shows the result. */
    private void forces(Consumer<LobbyForcesController> action) {
        SwingUtilities.invokeLater(() -> {
            if (closing || gui.chatlounge == null) {
                return;
            }
            action.accept(new LobbyForcesController(gui::getClient, gui.chatlounge.lobbyCommands()));
            refreshSoon();
        });
    }

    /** Any thread: runs one of the forces column's commands through the Swing lobby, as its buttons do. */
    void forcesCommand(LobbyForcesPanel.Command command) {
        SwingUtilities.invokeLater(() -> {
            if (closing || gui.chatlounge == null) {
                return;
            }
            switch (command) {
                case ADD_UNIT -> gui.chatlounge.forcesCommand(ChatLounge.ForcesCommand.ADD_UNIT, null);
                case RANDOM_ARMY -> gui.chatlounge.forcesCommand(ChatLounge.ForcesCommand.RANDOM_ARMY, null);
                case SKILLS -> gui.chatlounge.forcesCommand(ChatLounge.ForcesCommand.SKILLS, null);
                case NAMES -> gui.chatlounge.forcesCommand(ChatLounge.ForcesCommand.NAMES, null);
                case C3 -> gui.chatlounge.forcesCommand(ChatLounge.ForcesCommand.C3, null);
                case LOAD_LIST, SAVE_LIST, PRINT_LIST -> gui.chatlounge.forcesCommand(
                      ChatLounge.ForcesCommand.valueOf(command.name()), gui.getClient().getLocalPlayer());
                case NEW_FORMATION -> gui.chatlounge.lobbyCommands().forceCreateEmpty();
            }
            refreshSoon();
        });
    }

    // ---- The battlefield column ----

    /** Any thread: runs {@code action} with the battlefield actions on the Swing thread, then shows the result. */
    private void battlefield(Consumer<LobbyBattlefieldController> action) {
        SwingUtilities.invokeLater(() -> {
            if (closing || gui.chatlounge == null) {
                return;
            }
            action.accept(new LobbyBattlefieldController(gui::getClient, lounge(gui.chatlounge)));
            queueRefresh();
        });
    }

    /** The Swing lobby's battlefield controls, with the board editor as the start screen opens it. */
    private LobbyBattlefieldController.Lounge lounge(ChatLounge lounge) {
        return new LobbyBattlefieldController.Lounge() {
            @Override
            public void command(LobbyBattlefieldController.Command command) {
                lounge.battlefieldCommand(ChatLounge.BattlefieldCommand.valueOf(command.name()));
            }

            @Override
            public void mapSetup() {
                lounge.openMapSetup();
            }

            @Override
            public void boardEditor() {
                GpuBoardWindow.openEditor(gui.getFrame(), null, false);
            }

            @Override
            public void autoResolve(boolean chances, int runs, int threads) {
                lounge.setAutoResolveSettings(chances, runs, threads);
            }

            @Override
            public LobbyBattlefield.AutoResolve autoResolve() {
                return lounge.autoResolveSettings();
            }

            @Override
            public void runAutoResolve() {
                lounge.runAutoResolve();
            }
        };
    }

    /** Any thread: runs one of the battlefield column's actions through the Swing lobby, as its map tab does. */
    void battlefieldCommand(LobbyBattlefieldController.Command command) {
        battlefield(controller -> controller.run(command));
    }

    /** Any thread: sets the auto-resolve settings of the Swing lobby. */
    void autoResolve(boolean chances, int runs, int threads) {
        battlefield(controller -> controller.autoResolve(chances, runs, threads));
    }

    /** The newest map picture, which a screen shows as it is created; null while none is drawn. */
    @Nullable
    BufferedImage lastMap() {
        return lastMap;
    }

    /**
     * EDT: draws the map again, shortly, when the board or a deployment zone changed: the map the server made, or else
     * the one the map settings make of their fixed boards, as the Swing lobby previews it.
     */
    private void bakeMap() {
        Client client = gui.getClient();
        MapSettings settings = client.getMapSettings();
        Board serverBoard = client.getGame().getBoard();
        if (serverBoard != null && (serverBoard.getWidth() <= 0 || serverBoard.getHeight() <= 0)) {
            serverBoard = null;
        }
        List<Player> players = new ArrayList<>();
        List<List<Integer>> zones = new ArrayList<>();
        for (Player player : client.getGame().getPlayersList()) {
            players.add(player.copy());
            zones.add(List.of(player.getId(), player.getTeam(), player.getStartingPos(), player.getStartWidth(),
                  player.getStartOffset(), player.getStartingAnyNWx(), player.getStartingAnyNWy(),
                  player.getStartingAnySEx(), player.getStartingAnySEy(), player.isObserver() ? 1 : 0));
        }
        Player local = client.getLocalPlayer();
        MapKey key = new MapKey(settings, serverBoard, zones, local == null ? Player.PLAYER_NONE : local.getId());
        if (key.equals(mapKey)) {
            return;
        }
        mapKey = key;
        Board board = serverBoard;
        MapSettings copy = settings == null ? null : MapSettings.getInstance(settings);
        Player localCopy = local == null ? null : local.copy();
        int ticket = ++mapGeneration;
        Runnable bake = () -> new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                Board drawn = board != null ? board
                      : copy == null ? null : ServerBoardHelper.getPossibleGameBoard(copy, true);
                if (drawn == null || drawn.getWidth() <= 0 || drawn.getHeight() <= 0) {
                    return null;
                }
                return LobbyMapThumbnail.bake(drawn, players, localCopy, LobbyMapThumbnail.LONG_SIDE);
            }

            @Override
            protected void done() {
                if (ticket != mapGeneration || closing) {
                    return;
                }
                BufferedImage image = null;
                try {
                    image = get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    LOGGER.warn(e.getCause(), "The lobby could not draw the map");
                }
                sendMap(image, false);
            }
        }.execute();
        if (mapTimer != null) {
            mapTimer.stop();
        }
        mapTimer = new Timer(MAP_DELAY_MS, event -> bake.run());
        mapTimer.setRepeats(false);
        mapTimer.start();
    }

    private void sendMap(@Nullable BufferedImage image, boolean drawing) {
        lastMap = image;
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.map(image, drawing);
            }
        });
    }

    /** What the map picture shows: the map settings, the server's board, each player's zone, and who is local. */
    private record MapKey(@Nullable MapSettings settings, @Nullable Board board, List<List<Integer>> zones,
          int local) {
        @Override
        public boolean equals(Object other) {
            return other instanceof MapKey key && key.settings == settings && key.board == board
                  && key.zones.equals(zones) && key.local == local;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(settings), System.identityHashCode(board), zones, local);
        }
    }

    /** Any thread: orders the units by the Swing unit table's order {@code index}. */
    void sortUnits(int index) {
        SwingUtilities.invokeLater(() -> {
            if (!closing && gui.chatlounge != null) {
                gui.chatlounge.sortUnits(index);
                queueRefresh();
            }
        });
    }

    /** Any thread: opens the configuration of the editable unit {@code id}. */
    void configureUnit(int id) {
        forces(controller -> controller.configure(id));
    }

    /** Any thread: moves dragged units or formations onto {@code target}. */
    void dropForces(List<Integer> unitIds, List<Integer> formationIds, LobbyForcesController.Target target) {
        forces(controller -> controller.drop(unitIds, formationIds, target));
    }

    /**
     * Any thread: reads the Swing lobby's unit menu for these units and formations and sends its items to the screen;
     * the menu stays here until one is chosen.
     */
    void unitMenu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView) {
        SwingUtilities.invokeLater(() -> {
            if (closing || gui.chatlounge == null) {
                return;
            }
            Game game = gui.getClient().getGame();
            List<Entity> entities = unitIds.stream().map(game::getEntity).filter(Objects::nonNull).toList();
            List<Force> formations = formationIds.stream().map(game.getForces()::getForce).filter(Objects::nonNull)
                  .toList();
            List<LobbyMenuEntry> entries;
            try {
                unitMenu = gui.chatlounge.unitMenu(entities, formations, formationsView);
                entries = LobbySwingMenus.read(unitMenu);
            } catch (RuntimeException failure) {
                LOGGER.error(failure, "The lobby could not read the unit menu");
                unitMenu = null;
                entries = List.of();
            }
            List<LobbyMenuEntry> read = entries;
            GpuHost.post(() -> {
                GpuLobbyScreen shown = screen;
                if (shown != null) {
                    shown.unitMenu(read);
                }
            });
        });
    }

    /** Any thread: chooses the item at {@code path} of the unit menu last read, as clicking it in Swing would. */
    void chooseMenu(List<Integer> path) {
        SwingUtilities.invokeLater(() -> {
            JPopupMenu menu = unitMenu;
            unitMenu = null;
            if (!closing && menu != null) {
                LobbySwingMenus.choose(menu, path);
                refreshSoon();
            }
        });
    }

    /** Reads the lobby now and twice more, as the server's answer to a unit change takes a moment to come back. */
    private void refreshSoon() {
        queueRefresh();
        for (int delay : new int[] { 300, 1000 }) {
            Timer timer = new Timer(delay, event -> queueRefresh());
            timer.setRepeats(false);
            timer.start();
        }
    }

    /** EDT: the order of the units as the Swing unit table sorts them, and the orders it offers. */
    private LobbyForces.Order unitOrder() {
        if (gui.chatlounge == null) {
            return LobbyForces.Order.NONE;
        }
        try {
            List<Integer> ids = gui.chatlounge.sortedUnits().stream().map(Entity::getId).toList();
            return new LobbyForces.Order(ids, gui.chatlounge.unitSortNames(), gui.chatlounge.unitSortIndex());
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The lobby could not read the unit order");
            return LobbyForces.Order.NONE;
        }
    }

    /**
     * EDT: the lobby sprite of each shown unit as a key, and the images of keys not sent before; a unit a blind drop
     * hides has none.
     */
    private void sendSprites(LobbySnapshot read) {
        if (spritesStale) {
            spritesStale = false;
            spriteKeys.clear();
            sentSprites = Map.of();
        }
        Game game = gui.getClient().getGame();
        Map<Integer, String> keys = new HashMap<>();
        Map<String, BufferedImage> fresh = new HashMap<>();
        for (LobbyUnit unit : read.forces().units()) {
            Entity entity = unit.hidden() ? null : game.getEntity(unit.id());
            if (entity == null) {
                continue;
            }
            Camouflage camouflage = entity.getCamouflageOrElseOwners();
            String key = entity.getChassis() + '|' + entity.getModel() + '|' + camouflage.getCategory() + '|'
                  + camouflage.getFilename();
            keys.put(unit.id(), key);
            if (spriteKeys.add(key)) {
                BufferedImage image = sprite(entity, camouflage);
                if (image != null) {
                    fresh.put(key, image);
                }
            }
        }
        if (keys.equals(sentSprites) && fresh.isEmpty()) {
            return;
        }
        sentSprites = keys;
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.sprites(keys, fresh);
            }
        });
    }

    private static @Nullable BufferedImage sprite(Entity entity, Camouflage camouflage) {
        try {
            Image base = MMStaticDirectoryManager.getMekTileset().imageFor(entity);
            Image icon = base == null ? null
                  : EntityImage.createLobbyIcon(base, camouflage, entity).loadPreviewImage(false);
            if (icon == null || icon.getWidth(null) <= 0 || icon.getHeight(null) <= 0) {
                return null;
            }
            BufferedImage copy = new BufferedImage(icon.getWidth(null), icon.getHeight(null),
                  BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = copy.createGraphics();
            graphics.drawImage(icon, 0, 0, null);
            graphics.dispose();
            return trimmed(copy);
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The lobby could not draw the sprite of {}", entity.getShortName());
            return null;
        }
    }

    /**
     * The sprite cut to its visible pixels and a pixel of margin: the hex tile leaves wide clear edges round the unit,
     * which would shrink it in the card's box.
     */
    static BufferedImage trimmed(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < 0) {
            return image;
        }
        left = Math.max(0, left - 1);
        top = Math.max(0, top - 1);
        right = Math.min(width - 1, right + 1);
        bottom = Math.min(height - 1, bottom + 1);
        BufferedImage cut = new BufferedImage(right - left + 1, bottom - top + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = cut.createGraphics();
        graphics.drawImage(image, -left, -top, null);
        graphics.dispose();
        return cut;
    }

    /** The Swing dialogs of the player actions, owned by the client's hidden frame, which this scene adopts. */
    private final LobbyPlayerController.Dialogs dialogs = new LobbyPlayerController.Dialogs() {
        @Override
        public void playerSettings(Client client) {
            if (playerSettings != null) {
                playerSettings.dispose();
            }
            playerSettings = new PlayerSettingsDialog(gui, client, null);
            playerSettings.setModal(false);
            playerSettings.showDialog();
        }

        @Override
        public Optional<Camouflage> camouflage(Camouflage initial, List<Entity> units) {
            CamoChooserDialog chooser = new CamoChooserDialog(gui.getFrame(), initial);
            try {
                chooser.setDisplayedEntities(units);
                return chooser.showDialog().isConfirmed() ? Optional.ofNullable(chooser.getSelectedItem())
                      : Optional.empty();
            } finally {
                chooser.dispose();
            }
        }

        @Override
        public Optional<LobbyPlayerController.BotSetup> botSetup(@Nullable String name, BehaviorSettings behavior,
              @Nullable AIType type) {
            BotConfigDialog dialog = type == null
                  ? new BotConfigDialog(gui.getFrame(), name, behavior, gui)
                  : new BotConfigDialog(gui.getFrame(), name, behavior, gui, type);
            dialog.setVisible(true);
            if (dialog.getResult() != DialogResult.CONFIRMED) {
                return Optional.empty();
            }
            return Optional.of(new LobbyPlayerController.BotSetup(dialog.getBotName(),
                  type == null ? dialog.getSelectedAIType() : type, dialog.getBehaviorSettings()));
        }

        @Override
        public @Nullable AbstractClient connectBot(LobbyPlayerController.BotSetup setup) {
            Client client = gui.getClient();
            BotClient bot = BotFactory.createBot(setup.type(), setup.name(), client.getHost(), client.getPort(),
                  setup.behavior());
            bot.setClientGUI(gui);
            bot.getGame().addGameListener(new BotGUI(gui.getFrame(), bot));
            boolean connected;
            try {
                connected = bot.connect();
            } catch (Exception failure) {
                LOGGER.error(failure, "The bot {} could not connect", setup.name());
                connected = false;
            }
            if (!connected) {
                gui.doAlertDialog(Messages.getString("ChatLounge.AlertBot.title"),
                      Messages.getString("ChatLounge.AlertBot.message"));
                bot.die();
                return null;
            }
            return bot;
        }

        @Override
        public void poll(BooleanSupplier step) {
            long until = System.currentTimeMillis() + BOT_JOIN_WAIT_MS;
            Timer timer = new Timer(BOT_JOIN_POLL_MS, null);
            timer.addActionListener(event -> {
                if (closing || step.getAsBoolean() || System.currentTimeMillis() > until) {
                    timer.stop();
                    queueRefresh();
                }
            });
            timer.start();
        }

        @Override
        public BehaviorSettings favouriteBehavior() {
            BehaviorSettingsFactory factory = BehaviorSettingsFactory.getInstance();
            return factory.getBehaviorOrDefault(
                  PreferenceManager.getClientPreferences().getFavoritePrincessBehaviorSetting(),
                  factory.DEFAULT_BEHAVIOR);
        }
    };

    /** GPU thread, as the screen's listener is disposed: returns every adopted dialog to its Swing owner. */
    void disposing() {
        screen = null;
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null) {
            owner.releaseAll();
        }
    }

    // ---- The scene's life ----

    /** EDT, once the scene has started: the frame stays hidden, and dialogs that floated meanwhile are owned. */
    private void presented() {
        if (closing || active != this) {
            return;
        }
        JFrame frame = gui.getFrame();
        if (frame != null && frame.isVisible()) {
            frame.setVisible(false);
        }
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window instanceof Dialog dialog && belongsToClient(dialog)) {
                centre(dialog);
                raise(dialog);
            }
        }
        refresh();
    }

    private void finished(Throwable failure) {
        Toolkit.getDefaultToolkit().removeAWTEventListener(dialogListener);
        gui.getClient().getGame().removeGameListener(gameListener);
        modalDialogs.clear();
        modalOpen = false;
        dialogOnTop.forEach(Dialog::setAlwaysOnTop);
        dialogOnTop.clear();
        boolean replaced = closing;
        closing = true;
        if (mapTimer != null) {
            mapTimer.stop();
        }
        if (active == this) {
            active = null;
        }
        if (failure == null && !replaced && gui.getClient().getGame().getPhase().isLounge()) {
            // Another scene took the window, such as the 3D preview; the lobby comes back when it closes
            displaced = gui;
        } else if (gui.chatlounge != null) {
            gui.chatlounge.closeMapSetup();
        }
        if (failure != null && !replaced) {
            LOGGER.error(failure, "The native lobby failed; the Swing lobby shows instead");
            // The Swing lobby replaces the native window rather than standing beside the start screen
            GpuHost.closeWhenIdle();
            JFrame frame = gui.getFrame();
            if (frame != null && frame.isDisplayable()) {
                frame.setVisible(true);
                frame.toFront();
            }
        }
    }

    // ---- Dialogs over the lobby ----

    private void dialogEvent(AWTEvent event) {
        if (!(event.getSource() instanceof Dialog dialog)) {
            return;
        }
        int id = event.getID();
        if (id == ComponentEvent.COMPONENT_HIDDEN || id == WindowEvent.WINDOW_CLOSED) {
            endModal(dialog);
            GpuNativeDialogOwner owner = nativeOwner;
            if (owner != null) {
                if (id == WindowEvent.WINDOW_CLOSED) {
                    owner.forget(dialog);
                } else {
                    owner.hidden(dialog);
                }
            }
            if (id == WindowEvent.WINDOW_CLOSED) {
                Boolean wasOnTop = dialogOnTop.remove(dialog);
                if (wasOnTop != null) {
                    dialog.setAlwaysOnTop(wasOnTop);
                }
            }
        } else if (id == ComponentEvent.COMPONENT_SHOWN && !closing && belongsToClient(dialog)) {
            centre(dialog);
            raise(dialog);
        }
    }

    /**
     * A dialog of the client's hidden frame, or a modal one without an owner of its own, which Swing gives its hidden
     * shared frame: both would otherwise hide behind the native window.
     */
    private boolean belongsToClient(Dialog dialog) {
        Frame shared;
        try {
            shared = JOptionPane.getRootFrame();
        } catch (HeadlessException headless) {
            shared = null;
        }
        for (Window current = dialog.getOwner(); current != null; current = current.getOwner()) {
            if (current == gui.getFrame() || current == shared && dialog.isModal()) {
                return true;
            }
        }
        return false;
    }

    /** Centres a dialog of the hidden frame over the window; the hidden frame would centre it on the screen. */
    private static void centre(Dialog dialog) {
        Rectangle bounds = GpuHost.clientBounds();
        if (bounds == null || dialog.getOwner() instanceof Dialog) {
            return;
        }
        Rectangle swing = GpuStartWindow.toSwing(bounds);
        dialog.setLocation(swing.x + (swing.width - dialog.getWidth()) / 2,
              swing.y + (swing.height - dialog.getHeight()) / 2);
    }

    /**
     * Keeps a dialog above the window until either closes: owned natively where the platform allows, otherwise
     * floating on top. A modal dialog blocks the screen's input as it would its Swing owner's.
     */
    private void raise(Dialog dialog) {
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null && (dialog.getOwner() instanceof Dialog parent && parent.isShowing() && owner.owns(parent)
              || owner.adopt(dialog))) {
            Boolean wasOnTop = dialogOnTop.remove(dialog);
            if (wasOnTop != null) {
                dialog.setAlwaysOnTop(wasOnTop);
            }
            if (dialog.isModal() && modalDialogs.add(dialog) && modalDialogs.size() == 1) {
                setInputBlocked(true);
            }
            dialog.toFront();
            return;
        }
        dialogOnTop.putIfAbsent(dialog, dialog.isAlwaysOnTop());
        dialog.setAlwaysOnTop(true);
        dialog.toFront();
    }

    private void endModal(Dialog dialog) {
        if (modalDialogs.remove(dialog) && modalDialogs.isEmpty()) {
            setInputBlocked(false);
        }
    }

    /** EDT: brings the newest open modal dialog to the front when the window is clicked or asked to close. */
    private void frontModal() {
        List<Dialog> showing = new ArrayList<>();
        for (Dialog dialog : modalDialogs) {
            if (dialog.isShowing()) {
                showing.add(dialog);
            }
        }
        if (!showing.isEmpty()) {
            showing.get(showing.size() - 1).toFront();
        }
    }

    /**
     * Blocks or restores the screen's input. The native window itself stays enabled, as the start screen's does, so
     * Windows doesn't hand the activation to another application as a modal dialog closes.
     */
    private void setInputBlocked(boolean blocked) {
        modalOpen = blocked;
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.setInputBlocked(blocked);
            }
        });
    }
}
