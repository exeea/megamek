/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.client.Client;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbySession;
import megamek.common.loaders.MapSettings;

/**
 * The native lobby's battlefield actions, on the Swing thread: the map setup, the planetary conditions, the game
 * options, the victory conditions, the map's files and previews, and auto-resolve. Each runs through the window's Swing dialogs
 * ({@link Lounge}), with their checks; this controller refuses beforehand what may not run now, such as the planetary conditions in space. It needs neither OpenGL nor Swing components, so it can be
 * tested on its own.
 */
final class LobbyBattlefieldController {

    /** The battlefield column's actions. */
    enum Command {
        MAP_SETUP, CONDITIONS, GAME_OPTIONS, VICTORY, RANDOM_MAP, LOAD_MAP_SETUP, SAVE_MAP_SETUP, GENERATE,
        PREVIEW_3D, BOARD_EDITOR, RUN_AUTO_RESOLVE
    }

    /** The battlefield's Swing dialogs and services, which this controller runs. */
    interface Lounge {
        /** Runs {@code command}; never {@link Command#MAP_SETUP} or the editor. */
        void command(Command command);

        /** Shows the map setup dialog. */
        void mapSetup();

        /** Opens the board editor. */
        void boardEditor();

        /** Sets the lobby's auto-resolve settings; the values are within their bounds. */
        void autoResolve(boolean chances, int runs, int threads);

        /** The lobby's auto-resolve settings. */
        LobbyBattlefield.AutoResolve autoResolve();

        /** Runs auto-resolve. */
        void runAutoResolve();
    }

    private final LobbySession session;
    private final Lounge lounge;

    LobbyBattlefieldController(LobbySession session, Lounge lounge) {
        this.session = session;
        this.lounge = lounge;
    }

    /** Whether {@code command} may run now, as the Swing lobby enables its button. */
    boolean allows(Command command) {
        Client client = session.client();
        MapSettings settings = client.getMapSettings();
        boolean space = settings != null && settings.getMedium() == MapSettings.MEDIUM_SPACE;
        return switch (command) {
            case CONDITIONS, RANDOM_MAP, LOAD_MAP_SETUP, SAVE_MAP_SETUP -> !space;
            case GENERATE -> settings != null && LobbyBattlefield.generable(settings);
            case RUN_AUTO_RESOLVE -> session.localPlayer() != null && session.game() != null
                  && LobbyBattlefield.autoResolveShown(session.game().getOptions());
            default -> true;
        };
    }


    /** Runs {@code command} when the Swing lobby allows it. */
    void run(Command command) {
        if (!allows(command)) {
            return;
        }
        switch (command) {
            case MAP_SETUP -> lounge.mapSetup();
            case BOARD_EDITOR -> lounge.boardEditor();
            case RUN_AUTO_RESOLVE -> lounge.runAutoResolve();
            default -> lounge.command(command);
        }
    }

    /** Sets the auto-resolve settings, bringing the runs and the parallelism within their bounds. */
    void autoResolve(boolean chances, int runs, int threads) {
        LobbyBattlefield.AutoResolve current = lounge.autoResolve();
        lounge.autoResolve(chances, LobbyBattlefield.AutoResolve.clampRuns(runs), current.clampThreads(threads));
    }
}
