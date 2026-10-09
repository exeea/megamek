/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissedSurfaceCollisionTest {
    @ParameterizedTest
    @ValueSource(strings = { "ISSmallLaser", "ISLargeLaser", "ISPPC", "ISAC2", "ISAC20", "ISPlasmaRifle",
          "Flamer", "ISLRM5", "ISSRM6", "ISThunderbolt10", "ISLongTom" })
    void everyWeaponStopsItsMissAtTheWallAndSharesThatContactWithItsScar(String weapon) {
        var sample = GroundDamagePlaybackTest.impact(weapon, null);
        var attack = sample.attack();
        float wall = -BoardGeometry.height();
        attack.landscape = ray -> wall(ray, wall);
        attack.seconds = attack.contactSeconds;
        var effects = new GpuAttackEffects();
        effects.update(List.of(attack), null, Map.of());
        var scars = new ArrayList<GpuGroundDamage.Impact>();
        effects.groundImpacts(List.of(), scars::add);
        assertFalse(scars.isEmpty(), weapon);
        for (var scar : scars) {
            assertEquals(wall, scar.point().y, .001f, weapon + " must not continue to the ground behind the wall");
            var contact = attack.landscapeContact(scar.point());
            assertNotNull(contact, weapon);
            assertTrue(contact.surface().hardSurface());
            assertEquals(contact.incoming(), scar.incoming(), "Angle uses direction at contact, including descending trajectories");
        }
        for (var launch : effects.missileLaunches(attack)) {
            for (int missile = 0; missile < launch.missiles(); missile++) {
                assertTrue(launch.endProgress(missile) < 1);
                assertEquals(launch.targets()[missile], GpuMissileEffects.position(launch, missile, 1, new Vector3()));
                assertTrue(GpuMissileEffects.position(launch, missile, launch.endProgress(missile) * .5f, new Vector3()).y > wall);
            }
        }
    }

    @Test
    void curvedFlightRetainsItsOriginalArcAndCannotCollideBeyondTheTestedSegment() {
        var attack = GroundDamagePlaybackTest.impact("ISLongTom", null).attack();
        var start = new Vector3(0, 0, 10);
        var end = new Vector3(0, -100, 0);
        attack.landscape = ray -> wall(ray, -40);
        var contact = attack.landscapeContact(t -> UnitAttack.projectile(start, end, (float) t, true, new Vector3()), 32);
        assertNotNull(contact);
        assertEquals(-40, contact.point().y, .001);
        assertEquals(.4, contact.progress(), .001);
        assertTrue(contact.point().z > 25, "The original arc must not be recalculated toward the shortened endpoint");
        var trace = new GpuAttackEffects.Trace(start, new Vector3(), contact.point(), true, end, contact.progress());
        assertEquals(UnitAttack.projectile(start, end, .2f, true, new Vector3()), trace.position(.5f, true, new Vector3()));
        attack.landscape = ray -> wall(ray, -140);
        assertNull(attack.landscapeContact(t -> start.cpy().lerp(end, (float) t), 1));
    }

    @Test
    void earlierContactsBecomeVisibleBeforeTheOriginalGroundArrival() {
        var attack = GroundDamagePlaybackTest.impact("ISAC20", null).attack();
        attack.landscape = ray -> wall(ray, -BoardGeometry.height());
        attack.seconds = UnitAttack.ANTICIPATION_SECONDS + attack.flightSeconds * .5f;
        var effects = new GpuAttackEffects();
        effects.update(List.of(attack), null, Map.of());
        var scars = new ArrayList<GpuGroundDamage.Impact>();
        effects.groundImpacts(List.of(), scars::add);
        assertEquals(1, scars.size(), "An earlier wall hit must not wait for the former ground arrival");
    }

    private static BoardGeometry.Hit wall(Ray ray, float y) {
        float distance = Math.abs(ray.direction.y) < .000001f ? Float.POSITIVE_INFINITY : (y - ray.origin.y) / ray.direction.y;
        if (distance >= 0 && Float.isFinite(distance)) {
            var face = new BoardSurface.Face(new Vector3(-10000, y, -10000), new Vector3(10000, y, -10000),
                  new Vector3(0, y, 10000), BoardSurface.Finish.WALL);
            return new BoardGeometry.Hit(new Coords(0, 1), distance * distance, true, "ground", face);
        }
        if (ray.direction.z >= 0) { return null; }
        distance = -ray.origin.z / ray.direction.z;
        return distance < 0 ? null : new BoardGeometry.Hit(new Coords(0, 4), distance * distance);
    }
}
