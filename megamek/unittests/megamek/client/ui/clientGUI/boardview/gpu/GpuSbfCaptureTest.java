/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.InGameObject;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFReportHeader;
import megamek.common.strategicBattleSystems.SBFSomethingOutThereUnitPlaceHolder;
import megamek.common.strategicBattleSystems.SBFTurn;
import megamek.common.strategicBattleSystems.SBFUnit;
import org.junit.jupiter.api.Test;

/** An SBF game's formations, placeholders and reports as the 3D board's units, status and log (no OpenGL). */
class GpuSbfCaptureTest {
    private static final int LOCAL = 1;
    private static final int ENEMY = 2;

    private final SBFGame game = new SBFGame();
    private final GpuSbfCapture capture = new GpuSbfCapture(game, () -> LOCAL);

    private SBFFormation formation(int id, int owner, int x, int y, int board) {
        var formation = new SBFFormation();
        formation.setId(id);
        formation.setOwnerId(owner);
        formation.setName("Formation " + id);
        formation.setType(SBFElementType.BM);
        formation.setSize(2);
        formation.setMovement(4);
        formation.setTmm(1);
        formation.setDeployed(true);
        formation.setPosition(BoardLocation.of(new Coords(x, y), board));
        var unit = new SBFUnit();
        unit.setArmor(8);
        unit.setCurrentArmor(4);
        formation.setUnits(List.of(unit));
        return formation;
    }

    private void setUp(InGameObject... objects) {
        for (int id : new int[] { LOCAL, ENEMY }) {
            var player = new Player(id, "Player " + id);
            player.setTeam(id);
            game.addPlayer(id, player);
        }
        game.setBoard(0, new Board(16, 17));
        game.setCurrentRound(1);
        game.setUnitList(List.of(objects));
        game.setPhase(GamePhase.MOVEMENT);
        game.setTurns(List.<SBFTurn>of(new SBFFormationTurn(LOCAL), new SBFFormationTurn(ENEMY)));
        game.setTurnIndex(0, Player.PLAYER_NONE);
    }

    @Test
    void formationsAreTokensAndPlaceholdersAreContacts() {
        var own = formation(10, LOCAL, 3, 4, 0);
        var elsewhere = formation(11, LOCAL, 1, 1, 1);
        var placeholder = new SBFSomethingOutThereUnitPlaceHolder(formation(20, ENEMY, 5, 5, 0));
        setUp(own, elsewhere, placeholder);

        List<BoardScene.Unit> units = capture.units(0);

        assertEquals(2, units.size(), "the formation on another board is not on this one");
        BoardScene.Unit token = units.stream().filter(unit -> unit.id() == 10).findFirst().orElseThrow();
        assertFalse(token.sensorContact());
        assertEquals("Formation 10", token.name());
        assertEquals(new Coords(3, 4), token.location().coords());
        assertNotNull(token.image(), "a formation shows its counter");
        BoardScene.Unit contact = units.stream().filter(unit -> unit.id() == 20).findFirst().orElseThrow();
        assertTrue(contact.sensorContact());
        assertEquals(SBFElementType.BM.toString(), contact.name(), "a typed placeholder names its type");
        assertSame(token.image(), capture.units(0).stream().filter(unit -> unit.id() == 10).findFirst()
              .orElseThrow().image(), "one counter picture per look");
    }

    @Test
    void statusListsOwnFormationsFirstWithWhoCanAct() {
        var enemy = formation(30, ENEMY, 8, 8, 0);
        var ready = formation(31, LOCAL, 3, 4, 0);
        var done = formation(32, LOCAL, 3, 5, 0);
        done.setDone(true);
        setUp(enemy, ready, done);

        GpuBattleStatus.Snapshot status = capture.status(31);

        assertTrue(status.myTurn());
        assertEquals(31, status.actorId());
        assertEquals(2, status.turns().size());
        assertEquals(GpuBattleStatus.Side.OWN, status.turns().getFirst().side());
        assertEquals(List.of(31, 32, 30), status.units().stream().map(GpuBattleStatus.UnitStatus::id).toList());
        var readyStatus = status.units().getFirst();
        assertTrue(readyStatus.canActNow());
        assertEquals(ready.getName(), readyStatus.chassis());
        assertEquals(0.5, readyStatus.armor(), 1e-9);
        assertFalse(status.units().get(1).canActNow(), "a done formation cannot act");
        assertTrue(status.units().get(1).done());
        assertEquals(GpuBattleStatus.Side.ENEMY, status.units().get(2).side());
        assertSame(status, capture.status(31), "an unchanged status is the same snapshot");
    }

    @Test
    void reportsSplitAtHeaders() {
        setUp();
        game.addReports(List.of(text("Before the header"), new SBFReportHeader(1000) {
            @Override
            protected String reportText() {
                return "Initiative Phase";
            }
        }, text("Player 1 wins")));

        List<GpuReportLog.Entry> entries = capture.reports().entries();

        assertEquals(2, entries.size());
        assertTrue(entries.getFirst().heading().endsWith(" " + game.getCurrentRound()), "the round heads its start");
        assertEquals("Before the header", entries.getFirst().text());
        assertEquals("Initiative Phase", entries.get(1).heading());
        assertEquals("Player 1 wins", entries.get(1).text());
    }

    private static SBFReportEntry text(String text) {
        return new SBFReportEntry(0) {
            @Override
            protected String reportText() {
                return text;
            }
        };
    }
}
