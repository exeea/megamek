/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Test;

/**
 * A fighter's attack run through the playback: it flies its path in the run's time and its shot starts two hexes from
 * its target, one before the hex the shot was made from: the shot from 0301 starts over 0201 on its way east along row 1.
 */
class AttackRunPlaybackTest {

    private static final int FIGHTER = 10;
    private static final int TARGET = 3;
    private static final Coords FIRING_HEX = new Coords(2, 0);
    private static final Coords LEAD_HEX = new Coords(1, 0);
    private static final double FRAME = 1 / 120.0;

    @Test
    void theShotLaunchesOverItsFiringHexAndTheRunTakesItsTime() {
        BoardScene.Unit fighter = UnitPlaybackTest.unit(FIGHTER, 0);
        BoardScene.Unit target = new BoardScene.Unit(TARGET, -1, "Target", new BoardScene.Waypoint(new Coords(3, 0), 0, 0),
              null, false, null, 2, false);
        List<BoardScene.Waypoint> path = new ArrayList<>();
        for (int x = 0; x <= 5; x++) {
            path.add(new BoardScene.Waypoint(new Coords(x, 0), 3, 2));
        }
        BoardScene.Movement flight = new BoardScene.Movement(FIGHTER, 0, path, EntityMovementType.MOVE_FLYING, 0, 6,
              fighter);
        ResolvedAttack result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(FIGHTER, FIRING_HEX, 2, 3, 0), new UnitLocation(TARGET, target.location().coords(), 0, 0, 0),
              Targetable.TYPE_ENTITY, 0, "ISMediumLaser", 4, true);
        BoardScene.Combat shot = new BoardScene.Combat(result, fighter, target, target.location());
        UnitPlayback playback = new UnitPlayback();
        playback.accept(List.of(new FiringBurst(true, Set.of(FIGHTER), 0), flight, shot,
              new FiringBurst(false, Set.of(), 0)), UnitPlaybackTest.scene(fighter, target), unit -> false);

        Vector3 overLeadHex = BoardGeometry.center(LEAD_HEX, 3);
        double launchedAt = -1;
        float launchDistance = Float.NaN;
        double flightEndedAt = -1;
        double real = 0;
        while (real < 10 && playback.busy()) {
            playback.advance(FRAME, UnitMotion.Speed.NORMAL);
            real += FRAME;
            UnitAttack attack = playback.attack();
            if (launchedAt < 0 && attack != null && attack.seconds >= UnitAttack.ANTICIPATION_SECONDS) {
                launchedAt = real;
                Vector3 position = playback.motions.get(FIGHTER).position();
                launchDistance = Vector3.len(position.x - overLeadHex.x, position.y - overLeadHex.y, 0);
            }
            if (flightEndedAt < 0 && launchedAt >= 0 && !playback.motions.get(FIGHTER).isMoving()) {
                flightEndedAt = real;
            }
        }

        assertTrue(launchedAt >= 0, "the shot launched");
        assertTrue(launchDistance < BoardGeometry.height() * .25f,
              "it launched one hex before its firing hex, " + launchDistance + " from that hex's centre");
        assertTrue(flightEndedAt > 0, "the flight ended");
        // 1.5 clock seconds at the 1x rate of .5 clock seconds per real second.
        assertEquals(AttackRunTimeline.RUN_SECONDS / UnitMotion.Speed.NORMAL.rate, flightEndedAt, .1,
              "the flight takes the run's time");
        assertFalse(playback.busy(), "the run finished");
    }

    private static List<BoardScene.Waypoint> row(int... columns) {
        List<BoardScene.Waypoint> path = new ArrayList<>();
        for (int column : columns) {
            path.add(new BoardScene.Waypoint(new Coords(column, 0), 3, 2));
        }
        return path;
    }

    @Test
    void theShotStartsOneHexBeforeItsFiringHex() {
        assertEquals(new Coords(1, 0), AttackRunPlayer.leadHex(row(0, 1, 2, 3), new Coords(2, 0)));
    }

    @Test
    void aShortPathStartsTheShotAtItsFirstHex() {
        assertEquals(new Coords(0, 0), AttackRunPlayer.leadHex(row(0, 1), new Coords(0, 0)));
    }

    @Test
    void aTurnInPlaceDoesNotCountAsAHex() {
        assertEquals(new Coords(1, 0), AttackRunPlayer.leadHex(row(0, 1, 2, 2, 3), new Coords(2, 0)));
    }

    @Test
    void aFiringHexOffThePathIsKept() {
        assertEquals(new Coords(9, 9), AttackRunPlayer.leadHex(row(0, 1, 2), new Coords(9, 9)));
    }
}
