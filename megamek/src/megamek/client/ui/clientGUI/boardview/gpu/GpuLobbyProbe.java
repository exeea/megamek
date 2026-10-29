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
import megamek.common.loaders.MULParser;
import megamek.common.options.GameOptions;
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
 *     <li>{@code lobby+ready}: presses Ready once the bots have joined (and after the shot), to watch the game
 *     start.</li>
 *     <li>{@code MM_START_SHOT}: a PNG file the lobby saves from its frame buffer, {@code MM_START_SHOT_AT} seconds
 *     after it shows (by default {@value #SHOT_AT}, once the bots and units have arrived).</li>
 *     <li>{@code MM_START_QUIT}: {@code true} to quit MegaMek once the shot is saved.</li>
 * </ul>
 */
record GpuLobbyProbe(Set<String> options, List<File> units, int bots, File shot, double shotAt, boolean quit) {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyProbe.class);

    /** The shot's default time: the bots have joined and every unit has arrived. */
    static final double SHOT_AT = 6;
    static final int MAX_BOTS = 8;
    private static final int POLL_MS = 250;
    /** How long after the shot, or after the bots have joined when there is none, Ready is pressed. */
    private static final double READY_AFTER = 1;
    private static final int PLAYER_TEAM = 1;
    private static final int BOT_TEAM = 2;

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
        return new GpuLobbyProbe(Set.copyOf(options), List.copyOf(units), bots,
              shotValue == null ? null : new File(shotValue), shotAt, quit);
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
        Client client = gui.getClient();
        List<BotClient> waiting = new ArrayList<>();
        boolean[] joined = { false };
        double readyAt = (shot == null ? 0 : shotAt) + READY_AFTER;
        boolean[] readied = { !options.contains("ready") };
        Timer timer = new Timer(POLL_MS, null);
        timer.addActionListener(event -> {
            // The bots join after the player, who gets the first player id as when they are added by hand
            Player local = client.getLocalPlayer();
            if (!joined[0] && local != null) {
                joined[0] = true;
                local.setTeam(PLAYER_TEAM);
                client.sendPlayerInfo();
                loadUnits(client, local);
                addBots(gui, waiting);
            }
            waiting.removeIf(bot -> {
                Player player = bot.getLocalPlayer();
                if (player == null) {
                    return false;
                }
                player.setTeam(BOT_TEAM);
                bot.sendPlayerInfo();
                loadUnits(bot, player);
                return true;
            });
            double elapsed = (System.nanoTime() - start) / 1e9;
            if (joined[0] && !readied[0] && waiting.isEmpty() && elapsed >= readyAt && gui.chatlounge != null) {
                readied[0] = true;
                LOGGER.info("The lobby probe presses Ready");
                gui.chatlounge.ready();
            }
            if (joined[0] && waiting.isEmpty() && readied[0]) {
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
