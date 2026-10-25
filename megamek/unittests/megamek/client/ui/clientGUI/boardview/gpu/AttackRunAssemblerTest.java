/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.Test;

/**
 * The firing results' burst becomes one attack run per released aircraft, placed first; every other event, the scene
 * checkpoints included, keeps its order.
 */
class AttackRunAssemblerTest {

    private static final int FIGHTER = 10;
    private static final int MEK = 2;
    private static final int TARGET = 3;

    private final BoardScene.Unit fighter = UnitPlaybackTest.unit(FIGHTER, 0);
    private final BoardScene.Unit mek = UnitPlaybackTest.unit(MEK, 2);
    private final BoardScene.Unit target = UnitPlaybackTest.unit(TARGET, 4);

    private BoardScene.Movement flight() {
        List<BoardScene.Waypoint> path = new ArrayList<>();
        for (int x = 0; x <= 5; x++) {
            path.add(new BoardScene.Waypoint(new Coords(x, 0), 3, 2));
        }
        return new BoardScene.Movement(FIGHTER, 0, path, EntityMovementType.MOVE_FLYING, 0, 6, fighter);
    }

    private static FiringBurst open(Integer... aircraft) {
        return new FiringBurst(true, Set.of(aircraft), 0);
    }

    private static FiringBurst close() {
        return new FiringBurst(false, Set.of(), 0);
    }

    @Test
    void anAircraftsMoveAndShotsBecomeOneRunAheadOfTheGroundAttacks() {
        BoardScene.Movement flight = flight();
        BoardScene.Combat groundShot = UnitPlaybackTest.attack(mek, target, ResolvedAttack.Kind.SHOT, true);
        BoardScene.SceneUpdate checkpoint = new BoardScene.SceneUpdate(UnitPlaybackTest.scene(fighter, mek, target));
        BoardScene.Combat airShot = UnitPlaybackTest.attack(fighter, target, ResolvedAttack.Kind.SHOT, true);
        BoardScene.Combat death = UnitPlaybackTest.attack(target, target, ResolvedAttack.Kind.DEATH, true);
        ArrayDeque<BoardScene.Animation> pending = new ArrayDeque<>(
              List.of(open(FIGHTER), flight, groundShot, checkpoint, airShot, death, close()));

        assertTrue(AttackRunAssembler.assemble(pending));

        List<BoardScene.Animation> played = new ArrayList<>(pending);
        AttackRun run = assertInstanceOf(AttackRun.class, played.getFirst(), "the run plays first");
        // The run flies the fighter's own path, its heights shaped for the roll-in (RollInTest).
        assertEquals(FIGHTER, run.flight().entityId());
        assertEquals(hexes(flight), hexes(run.flight()));
        assertEquals(List.of(airShot), run.shots(), "the fighter's own shot is in its run");
        assertEquals(List.of(groundShot, checkpoint, death), played.subList(1, played.size()),
              "everything else keeps its order; the markers are gone");
    }

    private static List<Coords> hexes(BoardScene.Movement movement) {
        List<Coords> hexes = new ArrayList<>();
        for (BoardScene.Waypoint point : movement.path()) {
            hexes.add(point.coords());
        }
        return hexes;
    }

    @Test
    void nothingChangesUntilTheBurstCloses() {
        BoardScene.Movement flight = flight();
        BoardScene.Combat airShot = UnitPlaybackTest.attack(fighter, target, ResolvedAttack.Kind.SHOT, true);
        List<BoardScene.Animation> queued = List.of(open(FIGHTER), flight, airShot);
        ArrayDeque<BoardScene.Animation> pending = new ArrayDeque<>(queued);

        assertFalse(AttackRunAssembler.assemble(pending));
        assertEquals(queued, new ArrayList<>(pending));
    }

    @Test
    void aMoveSplitByAChangeOfFormPlaysAsItCame() {
        BoardScene.Movement first = flight();
        BoardScene.Movement second = flight();
        BoardScene.Combat airShot = UnitPlaybackTest.attack(fighter, target, ResolvedAttack.Kind.SHOT, true);
        ArrayDeque<BoardScene.Animation> pending = new ArrayDeque<>(
              List.of(open(FIGHTER), first, second, airShot, close()));

        assertTrue(AttackRunAssembler.assemble(pending));

        assertEquals(List.of(first, second, airShot), new ArrayList<>(pending), "no run; only the markers go");
    }

    @Test
    void aMoveTheBurstDidNotReleaseIsNotARun() {
        BoardScene.Movement flight = flight();
        BoardScene.Combat airShot = UnitPlaybackTest.attack(fighter, target, ResolvedAttack.Kind.SHOT, true);
        ArrayDeque<BoardScene.Animation> pending = new ArrayDeque<>(List.of(open(99), flight, airShot, close()));

        assertTrue(AttackRunAssembler.assemble(pending));

        assertEquals(List.of(flight, airShot), new ArrayList<>(pending));
    }
}
