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
        float boundary = FormationLod.FAR_PIXELS;
        // Just under the boundary: a full suit stays full, a far suit stays far.
        assertEquals(0, FormationLod.level(boundary * .95f, 0));
        assertEquals(1, FormationLod.level(boundary * .95f, 1));
        // Just over it: likewise, each keeps what it shows until the margin is crossed.
        assertEquals(0, FormationLod.level(boundary * 1.05f, 0));
        assertEquals(1, FormationLod.level(boundary * 1.05f, 1));
        assertEquals(1, FormationLod.level(boundary * .85f, 0));
        assertEquals(0, FormationLod.level(boundary * 1.15f, 1));
    }
}
