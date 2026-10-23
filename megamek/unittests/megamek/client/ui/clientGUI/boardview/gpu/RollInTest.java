/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Test;

/**
 * A run rolls in like an aircraft on a ground target. A fighter at altitude 5 flies east along row 1, 0101 to 0801; its
 * shot is made from 0501 at a target in 0601, so the shot starts over 0401.
 */
class RollInTest {

    private static final int FIGHTER = 10;
    private static final float CRUISE = 5;

    private final BoardScene.Unit fighter = UnitPlaybackTest.unit(FIGHTER, 0);

    private BoardScene.Movement flight(int... columns) {
        List<BoardScene.Waypoint> path = new ArrayList<>();
        for (int column : columns) {
            path.add(new BoardScene.Waypoint(new Coords(column, 0), CRUISE, 2));
        }
        return new BoardScene.Movement(FIGHTER, 0, path, EntityMovementType.MOVE_FLYING, 0, 6, fighter);
    }

    private BoardScene.Movement eastward() {
        return flight(0, 1, 2, 3, 4, 5, 6, 7);
    }

    private BoardScene.Combat shotFrom(int column, boolean airborneTarget) {
        BoardScene.Unit target = new BoardScene.Unit(3, -1, "Target", new BoardScene.Waypoint(new Coords(column + 1, 0), 0, 0),
              null, false, null, 2, airborneTarget);
        ResolvedAttack result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(FIGHTER, new Coords(column, 0), 2, 0, 0), new UnitLocation(3, target.location().coords(),
              0, 0, 0), Targetable.TYPE_ENTITY, 0, "ISMediumLaser", 4, true);
        return new BoardScene.Combat(result, fighter, target, target.location());
    }

    /** How many levels below its cruise each path point is. */
    private static List<Float> depths(BoardScene.Movement shaped) {
        List<Float> depths = new ArrayList<>();
        for (BoardScene.Waypoint point : shaped.path()) {
            depths.add(CRUISE - point.elevation());
        }
        return depths;
    }

    @Test
    void aStrafingRunDipsOneLevelAndClimbsBack() {
        List<Float> depths = depths(RollIn.shape(eastward(), List.of(shotFrom(4, false)), 0));

        assertEquals(0, depths.get(0), 1e-6, "cruising at the start");
        assertEquals(0, depths.get(1), 1e-6, "rolls in two hexes before the shot starts");
        assertTrue(depths.get(2) > 0 && depths.get(2) < 1, "nosing down");
        for (int index = 3; index <= 5; index++) {
            assertEquals(RollIn.STRAFE_DIP, depths.get(index), 1e-6, "low through the firing pass at " + index);
        }
        assertEquals(0, depths.get(7), 1e-6, "climbed back to its altitude: a strafe costs none");
    }

    @Test
    void aStrikeEndsOneLevelLower() {
        List<Float> depths = depths(RollIn.shape(eastward(), List.of(shotFrom(4, false)), 1));

        assertEquals(1, depths.get(5), 1e-6, "low over the target");
        assertEquals(1, depths.getLast(), 1e-6, "stays one level lower, as the game has it");
    }

    @Test
    void aDiveBombEndsTwoLevelsLower() {
        List<Float> depths = depths(RollIn.shape(eastward(), List.of(shotFrom(4, false)), 2));

        assertEquals(2, depths.get(4), 1e-6);
        assertEquals(2, depths.getLast(), 1e-6);
    }

    @Test
    void anAirToAirRunDoesNotDip() {
        BoardScene.Movement flight = eastward();

        assertSame(flight, RollIn.shape(flight, List.of(shotFrom(4, true)), 0));
    }

    @Test
    void aTurnInPlaceKeepsOneHeight() {
        List<Float> depths = depths(RollIn.shape(flight(0, 1, 2, 2, 3, 4, 5, 6, 7), List.of(shotFrom(4, false)), 0));

        assertEquals(depths.get(2), depths.get(3), 1e-6, "the repeated hex keeps the height it was reached at");
    }

    @Test
    void anAircraftWithoutShotsOnItsPathStillEndsAtItsNewAltitude() {
        List<Float> depths = depths(RollIn.shape(eastward(), List.of(), 1));

        assertEquals(0, depths.getFirst(), 1e-6);
        assertEquals(1, depths.getLast(), 1e-6);
    }
}
