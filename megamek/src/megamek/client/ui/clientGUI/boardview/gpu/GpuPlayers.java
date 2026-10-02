/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Comparator;
import java.util.List;

import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.dialogs.BotCommands.BotCommandsPanel;
import megamek.client.ui.dialogs.BotCommands.HexTargetPicker;
import megamek.common.Player;

/**
 * EDT service for the players panel: the game's players with the states the Swing player list shows, the bot
 * commands panel's commands for each bot while the panel is open, and the hex pick of a bot order while it runs.
 */
final class GpuPlayers {
    /**
     * A bot order's hex pick (HexTargetPicker) while it runs: its instructions, and its status once a hex is picked
     * ("" before). The board's left clicks pick hexes; the Done key ends it with the order, Esc without.
     */
    record Pick(String instructions, String status) { }

    /**
     * One player. {@code team} is the player's team number, {@link Player#TEAM_NONE} (the Swing list's "Lone Wolf")
     * or {@link Player#TEAM_UNASSIGNED}. {@code seeAll}, {@code singleBlind} and {@code ignoresDoubleBlind} are the
     * visibility modes the Swing list marks. {@code botCommands} are the bot commands panel's popup buttons for this
     * bot as groups of their items (GpuBoardActions.botCommands), captured only while the players panel is open; they
     * are empty otherwise and for every player the local player does not command.
     */
    record PlayerRow(int id, String name, int team, int rgb, boolean local, boolean done, boolean bot,
          boolean observer, boolean ghost, boolean gameMaster, boolean seeAll, boolean singleBlind,
          boolean ignoresDoubleBlind, List<BoardScene.Command> botCommands) {
        PlayerRow {
            botCommands = List.copyOf(botCommands);
        }
    }

    /**
     * The players in id order, the bot commands panel's Pause/Continue button as a plain command while the players
     * panel is open and a bot plays (null otherwise), and the hex pick that runs (null for none).
     */
    record Snapshot(List<PlayerRow> players, BoardScene.Command pause, Pick pick) {
        static final Snapshot EMPTY = new Snapshot(List.of());

        Snapshot {
            players = List.copyOf(players);
        }

        Snapshot(List<PlayerRow> players) {
            this(players, null);
        }

        Snapshot(List<PlayerRow> players, BoardScene.Command pause) {
            this(players, pause, null);
        }
    }

    private final GpuBoardSource source;
    // GL thread writes, EDT reads at the next capture, as GpuUnitRecord's sheet flag.
    private volatile boolean panelOpen;
    private Snapshot snapshot = Snapshot.EMPTY;

    GpuPlayers(GpuBoardSource source) {
        this.source = source;
    }

    /**
     * GL thread: whether the players panel is open. Only then does the capture build the bot commands, whose popups
     * list every enemy unit and player; the next capture follows the change.
     */
    void setPanelOpen(boolean open) {
        panelOpen = open;
    }

    /**
     * EDT: the players of the source's game in id order, as the Swing player list shows them, with each bot's commands
     * while the panel is open; the previous instance while nothing changed. Each capture builds new command actions,
     * so a bot's commands keep their previous instances while they show the same.
     */
    Snapshot capture() {
        GpuBoardSource.requireSwingThread();
        var view = source.currentView();
        Player local = view.getLocalPlayer();
        // The bot commands do not depend on the shown board; a command waits only for the source to accept input.
        GpuBoardActions actions = panelOpen
              ? new GpuBoardActions(view, () -> null, () -> !source.acceptsInput(), source::refresh) : null;
        List<PlayerRow> rows = view.game.getPlayersList().stream()
              .sorted(Comparator.comparingInt(Player::getId))
              .map(player -> new PlayerRow(player.getId(), player.getName(), player.getTeam(),
                    player.getColour().getColour().getRGB(), local != null && local.getId() == player.getId(),
                    player.isDone(), player.isBot(), player.isObserver(), player.isGhost(), player.isGameMaster(),
                    player.getSeeAll(), player.getSingleBlind(), player.canIgnoreDoubleBlind(),
                    actions == null || !player.isBot() ? List.of() : unchanged(actions.botCommands(player),
                          snapshot.players().stream().filter(row -> row.id() == player.getId())
                                .map(PlayerRow::botCommands).findFirst().orElse(List.of()))))
              .toList();
        BoardScene.Command pause = actions == null || rows.stream().noneMatch(PlayerRow::bot) ? null
              : actions.pauseCommand();
        if (pause != null && snapshot.pause() != null) {
            pause = unchanged(List.of(pause), List.of(snapshot.pause())).getFirst();
        }
        HexTargetPicker picker = picker();
        Pick pick = picker == null ? null
              : new Pick(picker.instructions(), picker.hasHexes() ? picker.status() : "");
        Snapshot next = new Snapshot(rows, pause, pick);
        if (!next.equals(snapshot)) {
            snapshot = next;
        }
        return snapshot;
    }

    /** GL thread: ends the hex pick that runs, as its Done ({@code send}: the order goes out) or Cancel does. */
    void endPick(boolean send) {
        source.command(() -> {
            HexTargetPicker picker = picker();
            if (picker != null && send) {
                picker.done();
            } else if (picker != null) {
                picker.cancel();
            }
        });
    }

    /** EDT: the closing window cancels the hex pick, whose Done and Cancel it showed. */
    void close() {
        HexTargetPicker picker = picker();
        if (picker != null) {
            picker.cancel();
        }
    }

    /** EDT: the bot order's hex pick while it runs, else null. */
    private HexTargetPicker picker() {
        ClientGUI gui = source.currentView().getClientgui();
        BotCommandsPanel bots = gui == null ? null : gui.getBotCommandsPanel();
        return bots == null ? null : bots.hexPicker();
    }

    /** {@code previous} while it shows what {@code next} shows, else {@code next}. */
    private static List<BoardScene.Command> unchanged(List<BoardScene.Command> next,
          List<BoardScene.Command> previous) {
        return shown(next).equals(shown(previous)) ? previous : next;
    }

    /** The commands without their actions, which are new on every capture: what the panel shows of them. */
    private static List<BoardScene.Command> shown(List<BoardScene.Command> commands) {
        return commands.stream().map(command -> new BoardScene.Command(command.id(),
              command.label(), command.detail(), command.enabled(), command.commit(), command.boardTool(),
              shown(command.children()), null, command.shortcut(), command.selected())).toList();
    }
}
