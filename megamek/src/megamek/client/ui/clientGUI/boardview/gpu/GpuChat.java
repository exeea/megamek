/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.overlay.ChatterBoxOverlay;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.player.GamePlayerChatEvent;
import megamek.common.game.Game;
import megamek.server.Server;

/** EDT service for the chat panel: the recent chat lines of the client's game and a guarded send. */
final class GpuChat implements AutoCloseable {
    /** The panel keeps the newest lines, as the prototype does. */
    private static final int MAX_LINES = 80;

    record ChatLine(long id, String sender, String text, boolean system, int rgb) { }

    /** The last chat lines, oldest first. */
    record Snapshot(List<ChatLine> lines) {
        static final Snapshot EMPTY = new Snapshot(List.of());

        Snapshot {
            lines = List.copyOf(lines);
        }
    }

    private final GpuBoardSource source;
    /** The game this service listens to; every board view of one client shares it. */
    private final Game game;
    /** Records each chat line; chat packets are handled on the EDT, anything else is moved there. */
    private final GameListenerAdapter listener = new GameListenerAdapter() {
        @Override
        public void gamePlayerChat(GamePlayerChatEvent event) {
            String message = event.getMessage();
            if (SwingUtilities.isEventDispatchThread()) {
                receive(message);
            } else {
                SwingUtilities.invokeLater(() -> receive(message));
            }
        }
    };
    private Snapshot snapshot = Snapshot.EMPTY;
    private long lastId;
    /** The round and sides of the last initiative result seen; a Tactical Genius reroll replaces the sides in-round. */
    private int initiativeRound;
    private List<GpuBattleStatus.InitiativeSide> initiativeSides = List.of();
    private int resolvedRound;

    /** EDT: listens to the source's game until {@link #close()}. */
    GpuChat(GpuBoardSource source) {
        this.source = source;
        game = source.currentView().game;
        game.addGameListener(listener);
    }

    /** EDT: the recent lines; the same instance until a line arrives. */
    Snapshot capture() {
        GpuBoardSource.requireSwingThread();
        return snapshot;
    }

    /**
     * EDT: adds the two round lines from real data: the initiative result when its report phase shows rolled sides,
     * again when a Tactical Genius reroll replaces them within the round, and the number of weapon hits once when the
     * round report starts. The start-of-game deployment's initiative belongs to no round and has no line.
     */
    void roundLines(GpuBattleStatus.Snapshot status, GpuReportLog.Snapshot reports) {
        GpuBoardSource.requireSwingThread();
        int round = status.round();
        List<GpuBattleStatus.InitiativeSide> initiative = status.initiative();
        if (status.phase() == GamePhase.INITIATIVE_REPORT && round > 0 && initiative.size() > 1
              && (round != initiativeRound || !initiative.equals(initiativeSides))) {
            initiativeRound = round;
            initiativeSides = initiative;
            initiativeLine(round, initiative);
        }
        if (status.phase() == GamePhase.END_REPORT && round != resolvedRound) {
            resolvedRound = round;
            long hits = reports.combat().stream().filter(event -> event.round() == round
                  && event.kind() == ResolvedAttack.Kind.SHOT && event.hit()).count();
            append(system(), Messages.getString("GpuBoard.hud.chat.resolvedLine", round, hits), true, 0);
        }
    }

    /**
     * The initiative line with the own side's total first and "you win" when the own side wins. No line without a
     * winner (two sides share the best rolls), by the status's one winner rule.
     */
    private void initiativeLine(int round, List<GpuBattleStatus.InitiativeSide> initiative) {
        GpuBattleStatus.InitiativeSide winner = GpuBattleStatus.winner(initiative);
        if (winner == null) {
            return;
        }
        List<GpuBattleStatus.InitiativeSide> sides = initiative.stream()
              .sorted(Comparator.comparing(side -> side.side() != GpuBattleStatus.Side.OWN)).toList();
        String others = sides.stream().skip(1).map(side -> Integer.toString(side.total()))
              .reduce((left, right) -> Messages.getString("GpuBoard.hud.chat.initiativeOthers", left, right))
              .orElseThrow();
        String key = winner.side() == GpuBattleStatus.Side.OWN ? "GpuBoard.hud.chat.initiativeLineOwn"
              : "GpuBoard.hud.chat.initiativeLine";
        append(system(), Messages.getString(key, round, sides.getFirst().total(), others, winner.name()), true, 0);
    }

    /**
     * GL-safe: sends a line through the board chat's one send path, which also keeps it in the chat history. A chat
     * line is not board input, so unlike the other commands it is sent while a modal dialog is shown, not dropped.
     */
    void send(String text) {
        SwingUtilities.invokeLater(() -> {
            if (source.isClosed()) {
                return;
            }
            ChatterBoxOverlay chat = source.currentView().getOverlay(ChatterBoxOverlay.class);
            if (chat != null) {
                chat.send(text);
            }
        });
    }

    /**
     * EDT: one chat line as the server formats it ("origin: text"). A line from a current player keeps the player's
     * name and colour; server notices and every other line are system lines.
     */
    private void receive(String message) {
        String line = message.strip();
        if (line.isEmpty()) {
            return;
        }
        int colon = line.indexOf(": ");
        String origin = colon > 0 ? line.substring(0, colon) : "";
        Player player = game.getPlayersList().stream().filter(candidate -> candidate.getName().equals(origin))
              .findFirst().orElse(null);
        if (player != null) {
            append(origin, line.substring(colon + 2), false, player.getColour().getColour().getRGB());
        } else {
            append(system(), origin.equals(Server.ORIGIN) ? line.substring(colon + 2) : line, true, 0);
        }
    }

    private static String system() {
        return Messages.getString("GpuBoard.hud.chat.system");
    }

    private void append(String sender, String text, boolean system, int rgb) {
        List<ChatLine> lines = new ArrayList<>(snapshot.lines());
        lines.add(new ChatLine(++lastId, sender, text, system, rgb));
        snapshot = new Snapshot(lines.subList(Math.max(0, lines.size() - MAX_LINES), lines.size()));
    }

    /** EDT: removes the listener; called by {@link GpuBoardSource#close()}. */
    @Override
    public void close() {
        game.removeGameListener(listener);
    }
}
