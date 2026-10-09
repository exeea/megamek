/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Test;

class GpuGeysersTest {
    @Test
    void followsInstalledAssetStateAndItsGroundTransform() {
        Matrix4 transform = new Matrix4().setToTranslation(120, -90, 18).scale(2, 2, 2);
        var active = GpuGeysers.emitter("scenery/geysers/water-erupting", transform);
        assertNotNull(active);
        assertTrue(active.active());
        assertFalse(active.magma());
        assertEquals(new Vector3(120, -90, 19.68f), active.origin());
        assertEquals(2, active.scale());
        var dormant = GpuGeysers.emitter("scenery/geysers/water-dormant", transform);
        assertFalse(dormant.active());
        assertEquals(active.origin(), dormant.origin());
        var magma = GpuGeysers.emitter("scenery/geysers/magma", transform);
        assertTrue(magma.magma());
        assertFalse(magma.active());
        assertNull(GpuGeysers.emitter("scenery/fluff/pool", transform));
    }

    @Test
    void dropletsAdvanceOnTheSharedClockAndStayWithinTheCullingEnvelope() {
        var source = GpuGeysers.emitter("scenery/geysers/water-erupting", new Matrix4());
        Vector3 first = GpuGeysers.droplet(source, 4, .5f, 9.8f, Vector3.Zero, new Vector3());
        assertEquals(first, GpuGeysers.droplet(source, 4, .5f, 9.8f, Vector3.Zero, new Vector3()));
        assertNotEquals(first, GpuGeysers.droplet(source, 4, .9f, 9.8f, Vector3.Zero, new Vector3()));
        Vector3 sample = new Vector3();
        for (float gravity : new float[] { .16f, 1, 4 }) {
            for (int drop = 0; drop < 40; drop++) for (int frame = 0; frame < 120; frame++) {
                GpuGeysers.droplet(source, drop, frame * .071f, gravity * BoardAtmosphere.STANDARD_GRAVITY,
                      new Vector3(1, 0, 1), sample);
                assertTrue(sample.z >= source.origin().z && sample.z <= source.origin().z + 34.001f);
                assertTrue(Math.abs(sample.x) < 20 && Math.abs(sample.y) < 20);
            }
        }
    }
}
