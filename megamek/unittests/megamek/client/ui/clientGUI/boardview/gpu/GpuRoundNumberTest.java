/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.SwingUtilities;

import megamek.common.Player;
import megamek.common.Report;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.server.Server;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

/**
 * The round the HUD shows (user item 30) over the opening of a real game: MegaMek's TWGameManager plays it with and
 * without a start-of-game deployment, every packet it sends the local player reaches a client game as the client
 * applies it, and the HUD captures that game after each phase change, as its refresh does. MegaMek's round 0 is the
 * deployment, or already the first combat round when every unit starts on the map; the HUD calls the first combat
 * round 1 in both games, and shows no round before it.
 */
class GpuRoundNumberTest {
    /** The local player's id, which is also its connection. */
    private static final int LOCAL = 0;
    private static final int OPPONENT = 1;
    /** The phase changes the trace lists. */
    private static final Set<GamePhase> TRACED = Set.of(GamePhase.INITIATIVE, GamePhase.INITIATIVE_REPORT,
          GamePhase.DEPLOYMENT, GamePhase.MOVEMENT);
    private static final Set<Integer> INITIATIVE_MARKERS = Set.of(1000, 1005, 1010);

    /** What the HUD captured after a phase change. */
    record Shown(GamePhase phase, GpuBattleStatus.Snapshot status, GpuReportLog.Snapshot log) { }

    @Test
    void afterAStartOfGameDeploymentTheFirstCombatRoundIsOne() throws Exception {
        List<String> trace = new ArrayList<>();
        List<Shown> shown = play(true, trace);
        assertEquals(List.of("ROUND_UPDATE 0", "initiative 1005", "INITIATIVE_REPORT shows 0", "DEPLOYMENT shows 0",
                    "ROUND_UPDATE 1", "initiative 1000", "INITIATIVE_REPORT shows 1", "MOVEMENT shows 1"), trace,
              "MegaMek's round 0 is the deployment (Initiative Phase for Start of Game Deployment), which shows no "
                    + "round; its round 1 is the first combat round. Each round changes before its initiative report "
                    + "and that before its phase, and no initiative phase is ever sent");
        assertLogShowsTheStatusRound(shown);
        assertEquals(List.of(1), entryRounds(shown.getLast().log()),
              "The deployment's reports open round 1's report, as GameReports and the classic report keep them");
    }

    @Test
    void withoutADeploymentMegameksRoundZeroIsTheFirstCombatRound() throws Exception {
        List<String> trace = new ArrayList<>();
        List<Shown> shown = play(false, trace);
        assertEquals(List.of("ROUND_UPDATE 0", "initiative 1000", "INITIATIVE_REPORT shows 1", "MOVEMENT shows 1",
                    "ROUND_UPDATE 1", "initiative 1000", "INITIATIVE_REPORT shows 2", "MOVEMENT shows 2"), trace,
              "Nothing deploys, so round 0 opens with a combat round's initiative (Initiative Phase for Round #0) and "
                    + "its movement is the first round; the user's game showed ROUND 00 there");
        assertLogShowsTheStatusRound(shown);
        GpuReportLog.Snapshot log = shown.getLast().log();
        assertEquals(2, log.round(), "The second round's log");
        assertEquals(List.of(1, 2), entryRounds(log), "MegaMek keeps its rounds 0 and 1 in one report list "
              + "(GameReports); the log splits them at the second initiative, so the round select lists both");
    }

    @Test
    void noRoundShowsBeforeTheFirstOne() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardSource.Frame frame = fixture.source.takeFrame();
            assertEquals(-1, fixture.game.getRoundCount(), "A game before its first initiative");
            assertEquals(0, frame.status().round(), "No round, never -1");
            assertEquals(0, frame.reports().round());
        }
    }

    /** The log's title round is the status's round on every phase change; the log shows round 1 at the least. */
    private static void assertLogShowsTheStatusRound(List<Shown> shown) {
        for (Shown each : shown) {
            assertEquals(each.status().round(), each.log().round(), each.phase().name());
            assertTrue(each.log().entries().stream().allMatch(entry -> entry.round() >= 1 && entry.round()
                  <= Math.max(1, each.status().round())), each.phase() + ": entries of a round shown so far");
        }
    }

    /** The rounds of the log's entries, in report order: the round select's choices. */
    private static List<Integer> entryRounds(GpuReportLog.Snapshot log) {
        return log.entries().stream().map(GpuReportLog.Entry::round).distinct().toList();
    }

    /**
     * Plays the opening of a game on a real TWGameManager: the local player's Atlas against an Archer, both deploying
     * at the start or both on the map already. The manager runs MegaMek's own phase code from the first initiative,
     * the players answer as their clients do (done, deploy), and in the game without a deployment the second round
     * starts as the end of the first starts it, skipping the rest of the first. Every packet for the local player
     * reaches a client game on the Swing thread as AbstractClient and Client apply it (ROUND_UPDATE, SENDING_REPORTS,
     * PHASE_CHANGE; the round depends on nothing else), and after each phase change the HUD captures that game.
     * {@code trace} receives the round updates, the initiative reports and the traced phase changes with the round
     * the HUD shows.
     */
    static List<Shown> play(boolean deployAtStart, List<String> trace) throws Exception {
        TWGameManager manager = mock(TWGameManager.class, withSettings().useConstructor()
              .defaultAnswer(Answers.CALLS_REAL_METHODS));
        // No save files: the initiative report's autosave, and the rolling round saves
        doNothing().when(manager).autoSave();
        Game game = manager.getGame();
        game.getOptions().getOption(OptionsConstants.BASE_MAX_NUMBER_ROUND_SAVES).setValue(0);
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        game.setBoard(board);
        Game client = new Game();
        for (Player player : List.of(new Player(LOCAL, "GPU review"), new Player(OPPONENT, "Opponent"))) {
            player.setTeam(player.getId() + 1);
            game.addPlayer(player.getId(), player);
            client.addPlayer(player.getId(), player.copy());
        }
        add(game, LOCAL, "Atlas AS7-D.mtf", 1, deployAtStart ? null : new Coords(5, 5));
        add(game, OPPONENT, "Archer ARC-2R.mtf", 2, deployAtStart ? null : new Coords(10, 10));
        // What the exchange phase leaves for the first initiative (TWGameManager.executeCurrentPhase)
        game.setupTeams();
        game.setupDeployment();
        game.setPhase(GamePhase.SET_ARTILLERY_AUTO_HIT_HEXES);

        GpuBattleStatus status = new GpuBattleStatus();
        GpuReportLog reports = new GpuReportLog();
        List<Shown> shown = new ArrayList<>();
        Server server = mock(Server.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            if (invocation.getMethod().getName().equals("getGame")) {
                return game;
            }
            if (invocation.getMethod().getName().equals("send")
                  && (arguments.length == 1 || arguments[0].equals(LOCAL))) {
                Packet packet = (Packet) arguments[arguments.length - 1];
                SwingUtilities.invokeAndWait(() -> receive(packet, client, status, reports, shown, trace));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getServerInstance).thenReturn(server);
            // The end of the setup phases (TWPhaseEndManager)
            manager.changePhase(GamePhase.INITIATIVE);
            done(manager);
            if (deployAtStart) {
                for (int unit = 0; unit < 2 && game.getPhase().isDeployment(); unit++) {
                    int player = game.getTurn().playerId();
                    Entity entity = game.getPlayerEntities(game.getPlayer(player), false).getFirst();
                    Coords hex = player == LOCAL ? new Coords(5, 5) : new Coords(10, 10);
                    manager.handlePacket(player, new Packet(PacketCommand.ENTITY_DEPLOY, entity.getId(), hex, 0, 0, 0,
                          0, false));
                }
            } else {
                // The end of the round (TWPhaseEndManager, END_REPORT)
                manager.changePhase(GamePhase.INITIATIVE);
            }
            done(manager);
        }
        assertEquals(GamePhase.MOVEMENT, game.getPhase(), "The opening reached the movement of round 1");
        return shown;
    }

    /** One packet for the local player, applied to {@code client} as the client does. */
    @SuppressWarnings("unchecked")
    private static void receive(Packet packet, Game client, GpuBattleStatus status, GpuReportLog reports,
          List<Shown> shown, List<String> trace) {
        switch (packet.command()) {
            case ROUND_UPDATE -> {
                client.setCurrentRound((Integer) packet.getObject(0));
                trace.add("ROUND_UPDATE " + client.getRoundCount());
            }
            case SENDING_REPORTS -> {
                List<Report> received = (List<Report>) packet.getObject(0);
                client.addReports(received);
                received.stream().map(report -> report.messageId).filter(INITIATIVE_MARKERS::contains).findFirst()
                      .ifPresent(marker -> trace.add("initiative " + marker));
            }
            case PHASE_CHANGE -> {
                client.receivePhase((GamePhase) packet.getObject(0));
                Shown now = new Shown(client.getPhase(), status.capture(client, client.getPlayer(LOCAL), null,
                      Entity.NONE, entity -> true, entity -> true, entity -> null),
                      reports.capture(client.getAllReports(), client.getRoundCount(), client.getPhase()));
                shown.add(now);
                if (TRACED.contains(now.phase())) {
                    trace.add(now.phase() + " shows " + now.status().round());
                }
            }
            default -> { }
        }
    }

    /** Every player declares done, as in a report phase. */
    private static void done(TWGameManager manager) {
        for (int player : List.of(LOCAL, OPPONENT)) {
            manager.handlePacket(player, new Packet(PacketCommand.PLAYER_READY, true));
        }
    }

    private static void add(Game game, int owner, String file, int id, Coords position) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
        entity.setId(id);
        entity.setOwner(game.getPlayer(owner));
        if (position != null) {
            entity.setPosition(position);
            entity.setDeployed(true);
        }
        game.addEntity(entity, false);
    }
}
