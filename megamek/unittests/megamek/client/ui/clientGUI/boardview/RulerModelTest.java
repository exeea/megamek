/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.InputEvent;
import java.io.File;

import megamek.common.Player;
import megamek.common.LosEffects;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class RulerModelTest {
    private static final Coords START = new Coords(0, 0), END = new Coords(0, 6);

    @Test
    void flipReversesBlockerAndKeepsHeightsAndLockAttachedToTheirEndpoints() {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        game.getBoard().getHex(new Coords(0, 2)).setLevel(3);
        game.getBoard().getHex(new Coords(0, 4)).setLevel(3);
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.addPoint(START, 2);
        assertEquals(InputEvent.ALT_DOWN_MASK, ruler.capture().pending());
        ruler.addPoint(END, 2);
        ruler.lock(true, true);
        assertEquals(new Coords(0, 2), ruler.capture().ruler().blockedAt());
        ruler.flip();
        var flipped = ruler.capture();
        assertEquals(END, flipped.start().coords());
        assertEquals(new Coords(0, 4), flipped.ruler().blockedAt());
        assertTrue(flipped.end().locked());
        ruler.addPoint(new Coords(0, 5), 4);
        assertEquals(START, ruler.capture().end().coords());
        assertEquals(4, ruler.capture().start().height());
        ruler.height(true, 5); ruler.height(false, 5);
        assertTrue(ruler.capture().clear());
        assertNull(ruler.capture().ruler().blockedAt());
        ruler.clear();
        assertSame(RulerModel.Snapshot.NONE, ruler.capture());
    }

    @Test
    void measurementAndRuleComparisonUseTheSelectedBoardAndRestoreGameOptions() {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        Board other = Board.createEmptyBoard(1, 7);
        other.getHex(new Coords(0, 3)).setLevel(8);
        game.setBoard(4, other);
        game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1).setValue(true);
        RulerModel ruler = new RulerModel(game, 4, null);
        ruler.measure(START, END, Entity.NONE, null);
        ruler.compare(true);
        var snapshot = ruler.capture();
        assertFalse(snapshot.clear());
        assertEquals(new Coords(0, 3), snapshot.ruler().blockedAt());
        assertEquals(3, snapshot.comparison().size());
        assertTrue(snapshot.comparison().stream().allMatch(row -> row.forward().contains("blocked")));
        assertTrue(game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1));
        assertFalse(game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES));
        assertEquals("Diagrammed LOS", snapshot.mode());
    }

    @Test
    void losingVisualContactDropsIdentityHeightAndStateFromTheNativeCard() throws Exception {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        Player local = new Player(0, "Local"), enemy = new Player(1, "Enemy");
        local.setTeam(1); enemy.setTeam(2);
        game.addPlayer(0, local); game.addPlayer(1, enemy);
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
        game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
        Entity target = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        target.setId(1); target.setOwner(enemy); target.setPosition(END); target.setDeployed(true);
        target.setProne(true); game.addEntity(target, false);
        target.addBeenSeenBy(local); target.addBeenDetectedBy(local);
        RulerModel ruler = new RulerModel(game, 0, local);
        ruler.measure(START, END, Entity.NONE, null);
        ruler.height(false, 7);
        assertEquals(target.getDisplayName(), ruler.capture().end().name());
        assertTrue(ruler.capture().end().detail().contains("Prone"));
        target.clearSeenBy();
        var sensor = ruler.capture();
        assertEquals(megamek.client.ui.Messages.getString("BoardView1.sensorReturn"), sensor.end().name());
        assertEquals(0, sensor.end().height());
        assertFalse(sensor.end().detail().contains("Prone"));
        assertFalse(sensor.forward().contains("prone"));
        game.getOptions().getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(true);
        target.setHidden(true);
        var hidden = ruler.capture();
        assertEquals(Entity.NONE, hidden.end().entityId());
        assertEquals(1, hidden.end().choices().size());
        assertFalse(hidden.end().name().contains("Atlas"));
    }

    @Test
    void surfaceEndpointsDoNotBecomeUndergroundInTheCalculation() {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.addPoint(START, 0);
        ruler.addPoint(END, 0);
        var result = ruler.capture();
        assertTrue(result.clear(), "Level-0 ground must not block two level-0 endpoints on a clear board");
        assertEquals(0, result.start().absoluteHeight());
        assertEquals(0, result.ruler().startHeight());
        game.getBoard().getHex(new Coords(0, 1)).setLevel(1);
        assertFalse(ruler.capture().clear(), "An adjacent level-1 hill does block the ground-level sightline");
    }

    @Test
    void boxCanyonSurfaceMeasurementMatchesTheEngineAndFindsARealRaisedBlocker() {
        Game game = new Game();
        Board board = new Board();
        board.load(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"));
        game.setBoard(board);
        Coords from = new Coords(8, 12), to = new Coords(9, 10), middle = new Coords(9, 11);
        assertEquals(8, board.getHex(from).getLevel());
        assertEquals(0, board.getHex(to).getLevel());
        assertEquals(0, board.getHex(middle).getLevel());
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.addPoint(from, 0); ruler.addPoint(to, 0); ruler.compare(true);
        var result = ruler.capture();
        assertEquals(2, result.distance());
        assertEquals(8, result.start().absoluteHeight());
        assertEquals(0, result.end().absoluteHeight());
        assertTrue(result.clear(), "The screenshot's level-0 intervening hex is not a hill above the target");
        assertNull(result.ruler().blockedAt());
        assertEquals(result.forward(), result.comparison().getFirst().forward());
        var attack = LosEffects.prepLosAttackInfo(game, null, null, from, to, 0, false, false);
        assertEquals(8, attack.attackAbsHeight);
        assertEquals(0, attack.targetAbsHeight);
        assertEquals(LosEffects.calculateLos(game, attack, true).canSee(), result.clear());

        board.getHex(middle).setLevel(8);
        assertFalse(ruler.capture().clear());
        assertEquals(middle, ruler.capture().ruler().blockedAt());
    }

    @Test
    void compactModifiersFollowTheDirectionHeightAndBlockingState() {
        Game game = new Game(); game.setBoard(Board.createEmptyBoard(1, 7));
        game.getBoard().getHex(END).addTerrain(new Terrain(Terrains.WOODS, 2));
        game.getBoard().getHex(END).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.measure(START, END, Entity.NONE, null);
        assertEquals("+2 · target in heavy woods", ruler.capture().modifierSummary());
        ruler.flip();
        assertTrue(ruler.capture().modifierSummary().isEmpty(), "The woods are now at the start, not the target");
        ruler.flip(); ruler.height(false, 3);
        assertTrue(ruler.capture().modifierSummary().isEmpty(), "An endpoint above the woods loses their modifier");
        ruler.height(false, 0);
        game.getBoard().getHex(new Coords(0, 3)).setLevel(4);
        assertFalse(ruler.capture().clear());
        assertTrue(ruler.capture().modifierSummary().isEmpty(), "Blocked LOS must not retain a previous penalty");
    }

    @Test
    void actualUnitsAndComparisonsUseTheGameLosPathAtTheirActualSightHeights() throws Exception {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        Player owner = new Player(0, "Local"); game.addPlayer(owner.getId(), owner);
        Entity attacker = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        Entity target = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        attacker.setId(1); attacker.setOwner(owner); attacker.setPosition(START); attacker.setDeployed(true);
        target.setId(2); target.setOwner(owner); target.setPosition(END); target.setDeployed(true);
        game.addEntity(attacker, false); game.addEntity(target, false);
        RulerModel ruler = new RulerModel(game, 0, owner);
        ruler.measure(START, END, attacker.getId(), null); ruler.compare(true);
        for (boolean prone : new boolean[] { false, true }) {
            target.setProne(prone);
            var result = ruler.capture();
            assertTrue(result.entityBased());
            assertEquals(attacker.relHeight(), result.start().height());
            assertEquals(target.relHeight(), result.end().height());
            assertEquals(target.relHeight(), result.ruler().endHeight());
            assertEquals(LOSModifierCalculator.computeEntityBasedModifiers(game, attacker, target), result.forward());
            assertEquals(result.forward(), result.comparison().getFirst().forward());
        }
        ruler.height(false, 4);
        assertFalse(ruler.capture().entityBased());
        assertEquals(4, ruler.capture().ruler().endHeight());
    }
}
