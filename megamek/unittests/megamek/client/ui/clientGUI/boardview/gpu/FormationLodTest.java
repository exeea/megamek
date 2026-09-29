/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FormationLodTest {
    @Test
    void aSuitSmallOnScreenSwitchesToItsFarDetailAndALargeOneBack() {
        assertEquals(1, FormationLod.level(20, 0));
        assertEquals(0, FormationLod.level(120, 1));
    }

    @Test
    void theSwitchNeedsAClearMarginSoASquadAtTheBoundaryDoesNotFlicker() {
        float boundary = FormationLod.LOD1_PIXELS;
        // Just under the boundary: a full suit stays full, a far suit stays far.
        assertEquals(0, FormationLod.level(boundary * .95f, 0));
        assertEquals(1, FormationLod.level(boundary * .95f, 1));
        // Just over it: likewise, each keeps what it shows until the margin is crossed.
        assertEquals(0, FormationLod.level(boundary * 1.05f, 0));
        assertEquals(1, FormationLod.level(boundary * 1.05f, 1));
        assertEquals(1, FormationLod.level(boundary * .85f, 0));
        assertEquals(0, FormationLod.level(boundary * 1.15f, 1));
    }

    @Test
    void aMekSwitchesAtItsOwnHeightNotASuitsHeight() {
        float boundary = FormationLod.MEK_LOD1_PIXELS;
        // Tall enough for a suit to show its full detail, but a Mek this small already draws its far body.
        assertEquals(1, FormationLod.level(FormationLod.LOD1_PIXELS * 1.5f, 0, boundary));
        assertEquals(0, FormationLod.level(boundary * 1.2f, 1, boundary));
        // The same margin keeps a Mek at its boundary from flickering.
        assertEquals(0, FormationLod.level(boundary * .95f, 0, boundary));
        assertEquals(1, FormationLod.level(boundary * 1.05f, 1, boundary));
    }

    @Test
    void distantLevelsKeepTheirMarginInBothDirectionsAndAllowLargeZoomChanges() {
        for (float[] thresholds : new float[][] { { 48, 16 }, { 96, 32 } }) {
            float middle = thresholds[0];
            float distant = thresholds[1];
            assertEquals(1, FormationLod.level(distant * .95f, 1, middle, distant));
            assertEquals(2, FormationLod.level(distant * .85f, 1, middle, distant));
            assertEquals(2, FormationLod.level(distant * 1.05f, 2, middle, distant));
            assertEquals(1, FormationLod.level(distant * 1.15f, 2, middle, distant));
            assertEquals(2, FormationLod.level(1, 0, middle, distant));
            assertEquals(0, FormationLod.level(200, 2, middle, distant));
            assertEquals(1, FormationLod.level(distant * .9f, 1, middle, distant));
            assertEquals(1, FormationLod.level(distant * 1.1f, 2, middle, distant));
        }
    }
}
