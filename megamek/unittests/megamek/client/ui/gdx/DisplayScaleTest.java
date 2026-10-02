/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The native windows' logical-pixel scale. */
class DisplayScaleTest {
    @Test
    void followsMonitorDpiWithoutApplyingRetinaScalingTwice() {
        assertEquals(1, DisplayScale.calculate(1600, 1000, 1, 1, 1));
        assertEquals(1.5f, DisplayScale.calculate(2400, 1500, 1.5f, 1, 1));
        assertEquals(2, DisplayScale.calculate(3200, 2000, 2, 1, 1));
        assertEquals(2.5f, DisplayScale.calculate(3840, 2400, 2.5f, 1, 1));
        assertEquals(1, DisplayScale.calculate(1600, 1000, 2, 2, 1));
    }

    @Test
    void adaptsToResolutionAndHonorsManualScaleWithinAvailableSpace() {
        assertEquals(2, DisplayScale.calculate(3200, 2000, 1, 1, 1));
        assertEquals(1.25f, DisplayScale.calculate(1600, 1000, 1, 1, 1.25f));
        for (int[] size : new int[][] { { 900, 600 }, { 1280, 800 }, { 2048, 1200 }, { 3840, 2160 } }) {
            float scale = DisplayScale.calculate(size[0], size[1], 2.5f, 1, 1.5f);
            assertTrue(size[0] / scale >= 959.99f);
            assertTrue(size[1] / scale >= 639.99f);
        }
    }
}
