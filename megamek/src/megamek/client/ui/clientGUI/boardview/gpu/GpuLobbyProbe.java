/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Array;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.BotFactory;
import megamek.client.bot.ui.swing.BotGUI;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.force.Force;
import megamek.common.loaders.MULParser;
import megamek.common.options.BasicOption;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * A developer switch for looking at the native lobby without clicking through to it, read from the environment.
 * {@code MM_START_PAGE=lobby} makes the start screen host a game at once with the Host page's saved name, on the saved
 * port or the next free one ({@link StartScreenProbe}); once the lobby shows, this probe fills it:
 * <ul>
 *     <li>{@code MM_LOBBY_BOTS}: how many Princess bots to add, on team 2 with the player on team 1 (0 to 8).</li>
 *     <li>{@code MM_LOBBY_UNITS}: unit list files ({@code .mul}), separated by the path separator ({@code ;} on
 *     Windows), loaded for the player and for each bot.</li>
 *     <li>{@code lobby+ready}: readies the player alone, without their bots, a second before the shot (or once the
 *     bots have joined without one), so the shot shows the ready state while the table waits for the bots.</li>
 *     <li>{@code lobby+start}: presses Ready as the lobby does, for the player and their bots, once the bots have
 *     joined (and after the shot), to watch the game start.</li>
 *     <li>{@code lobby+blocked}: turns on the victory condition of killing the commander, so forces without one
 *     raise a red warning that blocks Ready.</li>
 *     <li>{@code lobby+overlap}: turns on double blind with exclusive deployment, so every player left on any edge
 *     overlaps and Ready is blocked.</li>
 *     <li>{@code lobby+blind}: turns on the real blind drop, so the other sides' Battle Value hides.</li>
 *     <li>{@code MM_START_SHOT}: a PNG file the lobby saves from its frame buffer, {@code MM_START_SHOT_AT} seconds
 *     after it shows (by default {@value #SHOT_AT}, once the bots and units have arrived).</li>
 *     <li>{@code MM_START_QUIT}: {@code true} to quit MegaMek once the shot is saved.</li>
 *     <li>{@code MM_LOBBY_SIZE}: the window's size as the lobby shows, such as {@code 1600x900}; {@code lobby+narrow}
 *     without it makes the window {@value #NARROW_WIDTH} by {@value #NARROW_HEIGHT}, which folds the battlefield into
 *     its rail. Either keeps the window's saved size as it was.</li>
 *     <li>{@code lobby+rail}: opens the folded battlefield from its rail.</li>
 *     <li>{@code lobby+menu}: opens the first bot's player menu (the first player's without bots) before the shot.</li>
 *     <li>{@code lobby+chat}: has the player and each bot say a line in the chat as they join.</li>
 *     <li>{@code lobby+formations}: puts the player's first units in a formation and a sub-formation, and shows the
 *     forces as formations.</li>
 *     <li>{@code lobby+compact}: shows the forces as compact rows.</li>
 *     <li>{@code lobby+unitmenu}: opens the menu of the player's first unit before the shot.</li>
 *     <li>{@code lobby+zones}: deploys the player at the west edge and the bots at the east, so the map shows
 *     zones.</li>
 *     <li>{@code lobby+autoresolve}: turns on the client setting that shows auto-resolve, and opens its settings in
 *     the battlefield.</li>
 *     <li>{@code lobby+requisition}: opens the Requisition view; with {@code +atlas} it searches for "Atlas", with
 *     {@code +filters} it opens the Filters drawer and with {@code +pick} it picks the first result, showing its
 *     dossier.</li>
 * </ul>
 */
record GpuLobbyProbe(Set<String> options, List<File> units, int bots, File shot, double shotAt, boolean quit,
      int width, int height) {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyProbe.class);

    /** The shot's default time: the bots have joined and every unit has arrived. */
    static final double SHOT_AT = 6;
    static final int MAX_BOTS = 8;
    private static final int POLL_MS = 250;
    /** How long after the shot, or after the bots have joined when there is none, Ready is pressed. */
    private static final double READY_AFTER = 1;
    private static final int PLAYER_TEAM = 1;
    private static final int BOT_TEAM = 2;
    static final int NARROW_WIDTH = 1100;
    static final int NARROW_HEIGHT = 800;
    /** The formation steps of {@code lobby+formations}: a lance, then a sub-formation inside it. */
    private static final int FORMED = 2;
    private static final double FORM_WAIT = 20;

    private static boolean taken;

    static GpuLobbyProbe from(Map<String, String> environment) {
        String page = value(environment, "MM_START_PAGE");
        if (page == null) {
            return null;
        }
        String[] parts = page.toLowerCase(Locale.ROOT).split("\\+");
        if (!parts[0].trim().equals("lobby")) {
            return null;
        }
        Set<String> options = new TreeSet<>();
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank()) {
                options.add(parts[i].trim());
            }
        }
        List<File> units = new ArrayList<>();
        String unitsValue = value(environment, "MM_LOBBY_UNITS");
        if (unitsValue != null) {
            for (String path : unitsValue.split(File.pathSeparator)) {
                if (!path.isBlank()) {
                    units.add(new File(path.trim()));
                }
            }
        }
        int bots = 0;
        String botsValue = value(environment, "MM_LOBBY_BOTS");
        if (botsValue != null) {
            try {
                bots = Math.clamp(Integer.parseInt(botsValue), 0, MAX_BOTS);
            } catch (NumberFormatException ignored) {
                bots = 0;
            }
        }
        String shotValue = value(environment, "MM_START_SHOT");
        double shotAt = SHOT_AT;
        String atValue = value(environment, "MM_START_SHOT_AT");
        if (atValue != null) {
            try {
                shotAt = Math.max(0, Double.parseDouble(atValue));
            } catch (NumberFormatException ignored) {
                shotAt = SHOT_AT;
            }
        }
        boolean quit = shotValue != null && Boolean.parseBoolean(value(environment, "MM_START_QUIT"));
        int[] size = size(value(environment, "MM_LOBBY_SIZE"));
        if (size == null && options.contains("narrow")) {
            size = new int[] { NARROW_WIDTH, NARROW_HEIGHT };
        }
        return new GpuLobbyProbe(Set.copyOf(options), List.copyOf(units), bots,
              shotValue == null ? null : new File(shotValue), shotAt, quit, size == null ? 0 : size[0],
              size == null ? 0 : size[1]);
    }

    /** A {@code WIDTHxHEIGHT} size, or null when {@code value} is missing or not one. */
    private static int[] size(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            return width > 0 && height > 0 ? new int[] { width, height } : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Whether the probe sizes the window. */
    boolean sized() {
        return width > 0 && height > 0;
    }

    private static String value(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** EDT: applies the environment's probe to the first lobby shown in this run, if it asks for one. */
    static GpuLobbyProbe applyOnce(ClientGUI gui) {
        if (taken) {
            return null;
        }
        taken = true;
        GpuLobbyProbe probe = from(System.getenv());
        if (probe != null) {
            probe.apply(gui);
        }
        return probe;
    }

    private void apply(ClientGUI gui) {
        long start = System.nanoTime();
        if (options.contains("autoresolve")) {
            PreferenceManager.getClientPreferences().setShowAutoResolvePanel(true);
        }
        Client client = gui.getClient();
        List<BotClient> waiting = new ArrayList<>();
        boolean[] joined = { false };
        boolean starts = options.contains("start");
        double readyAt = shot == null ? READY_AFTER : starts ? shotAt + READY_AFTER : Math.max(0, shotAt - READY_AFTER);
        boolean[] readied = { !starts && !options.contains("ready") };
        int[] formed = { options.contains("formations") ? 0 : FORMED };
        Timer timer = new Timer(POLL_MS, null);
        timer.addActionListener(event -> {
            // The bots join after the player, who gets the first player id as when they are added by hand
            Player local = client.getLocalPlayer();
            if (!joined[0] && local != null) {
                joined[0] = true;
                local.setTeam(PLAYER_TEAM);
                if (options.contains("zones")) {
                    local.setStartingPos(Board.START_W);
                }
                client.sendPlayerInfo();
                sendOptions(client);
                loadUnits(client, local);
                chat(client);
                addBots(gui, waiting);
            }
            waiting.removeIf(bot -> {
                Player player = bot.getLocalPlayer();
                if (player == null) {
                    return false;
                }
                player.setTeam(BOT_TEAM);
                if (options.contains("zones")) {
                    player.setStartingPos(Board.START_E);
                }
                bot.sendPlayerInfo();
                loadUnits(bot, player);
                chat(bot);
                return true;
            });
            double elapsed = (System.nanoTime() - start) / 1e9;
            if (joined[0] && formed[0] < FORMED) {
                formed[0] = elapsed > FORM_WAIT ? FORMED : form(client, local, formed[0]);
            }
            if (joined[0] && !readied[0] && waiting.isEmpty() && elapsed >= readyAt && gui.chatlounge != null) {
                readied[0] = true;
                if (starts) {
                    LOGGER.info("The lobby probe presses Ready");
                    gui.chatlounge.ready();
                } else {
                    LOGGER.info("The lobby probe readies the player");
                    client.sendDone(true);
                }
            }
            if (joined[0] && waiting.isEmpty() && readied[0] && formed[0] >= FORMED) {
                timer.stop();
            }
        });
        timer.start();
    }

    private void addBots(ClientGUI gui, List<BotClient> waiting) {
        Client client = gui.getClient();
        for (int i = 1; i <= bots; i++) {
            String name = Messages.getString("Lobby.probeBot", i);
            BotClient bot = BotFactory.createBot(AIType.PRINCESS, name, client.getHost(), client.getPort());
            bot.setClientGUI(gui);
            bot.getGame().addGameListener(new BotGUI(gui.getFrame(), bot));
            if (bot.connect()) {
                gui.getLocalBots().put(name, bot);
                waiting.add(bot);
            } else {
                LOGGER.error("The lobby probe's bot {} failed to connect", name);
                bot.die();
            }
        }
    }

    /** Sends the game options {@code lobby+blocked}, {@code lobby+overlap} and {@code lobby+blind} ask for. */
    private void sendOptions(Client client) {
        Vector<IBasicOption> changed = new Vector<>();
        if (options.contains("blocked")) {
            changed.add(new BasicOption(OptionsConstants.VICTORY_COMMANDER_KILLED, true));
        }
        if (options.contains("overlap")) {
            changed.add(new BasicOption(OptionsConstants.ADVANCED_DOUBLE_BLIND, true));
            changed.add(new BasicOption(OptionsConstants.BASE_EXCLUSIVE_DB_DEPLOYMENT, true));
        }
        if (options.contains("blind")) {
            changed.add(new BasicOption(OptionsConstants.BASE_REAL_BLIND_DROP, true));
        }
        if (!changed.isEmpty()) {
            client.sendGameOptions("", changed);
        }
    }

    /** With {@code lobby+chat}, has {@code client} say a line, so the chat shows players' lines. */
    private void chat(AbstractClient client) {
        if (options.contains("chat")) {
            client.sendChat(Messages.getString("Lobby.probeChat"));
        }
    }

    /**
     * With {@code lobby+formations}, puts the player's first unit in a formation and, once it exists, up to two more in
     * a sub-formation of it, so the nesting shows near the top even with three units; returns the step reached.
     */
    private static int form(Client client, Player local, int step) {
        List<Entity> mine = client.getGame().getEntitiesVector().stream()
              .filter(entity -> entity.getOwnerId() == local.getId()).toList();
        if (step == 0) {
            if (mine.isEmpty()) {
                return 0;
            }
            client.sendAddForce(Force.createToplevelForce(Messages.getString("Lobby.probeFormation"), local),
                  new ArrayList<>(mine.subList(0, 1)));
            return 1;
        }
        Force top = client.getGame().getForces().getTopLevelForces().stream()
              .filter(force -> force.getOwnerId() == local.getId()).findFirst().orElse(null);
        if (top == null) {
            return 1;
        }
        List<Entity> free = mine.stream().filter(entity -> entity.getForceId() == Force.NO_FORCE).toList();
        if (!free.isEmpty()) {
            client.sendAddForce(Force.createSubforce(Messages.getString("Lobby.probeSubFormation"), top),
                  new ArrayList<>(free.subList(0, Math.min(2, free.size()))));
        }
        return FORMED;
    }

    /** Loads every unit list for {@code player}, sent by its own client. */
    private void loadUnits(AbstractClient client, Player player) {
        for (File file : units) {
            try {
                Vector<Entity> entities = new MULParser(file, (GameOptions) client.getGame().getOptions())
                      .getEntities();
                for (Entity entity : entities) {
                    entity.setOwner(player);
                }
                if (!entities.isEmpty() && client instanceof Client totalWarfare) {
                    totalWarfare.sendAddEntity(entities);
                }
            } catch (Exception failure) {
                LOGGER.error(failure, "The lobby probe could not load {}", file);
            }
        }
    }

    /**
     * GPU thread: logs the native windows of this run's libGDX application and the showing Swing windows, so a probe
     * run proves the lobby shares the one window with nothing beside it.
     */
    static void reportWindows() {
        String nativeWindows;
        try {
            java.lang.reflect.Field field = Gdx.app.getClass().getDeclaredField("windows");
            field.setAccessible(true);
            nativeWindows = String.valueOf(((Array<?>) field.get(Gdx.app)).size);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            nativeWindows = "unknown (" + failure + ")";
        }
        long gpuThreads = Thread.getAllStackTraces().keySet().stream()
              .filter(thread -> thread.getName().startsWith("MegaMek-GPU")).count();
        String glfw = nativeWindows;
        SwingUtilities.invokeLater(() -> {
            List<String> showing = new ArrayList<>();
            for (Window window : Window.getWindows()) {
                if (window.isShowing()) {
                    String title = window instanceof Frame frame ? frame.getTitle()
                          : window instanceof Dialog dialog ? dialog.getTitle() : "";
                    showing.add(window.getClass().getSimpleName() + " '" + title + "'");
                }
            }
            LOGGER.info("Lobby probe windows: libGDX windows {}, GPU threads {}, showing Swing windows {} {}", glfw,
                  gpuThreads, showing.size(), showing);
        });
    }
}
