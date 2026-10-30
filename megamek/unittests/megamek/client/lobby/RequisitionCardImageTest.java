/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RequisitionCardImageTest {

    @Test
    void theCardIsDrawnAtThePixelsItTakesOnScreen() {
        assertEquals(875, RequisitionCardImage.pixels(500, 1.75f));
        assertEquals(501, RequisitionCardImage.pixels(500.6f, 1));
    }

    @Test
    void anUnlaidOrHugeCardIsKeptInBounds() {
        assertEquals(0, RequisitionCardImage.pixels(0, 2));
        assertEquals(0, RequisitionCardImage.pixels(Float.NaN, 2));
        assertEquals(0, RequisitionCardImage.pixels(400, 0));
        assertEquals(1, RequisitionCardImage.pixels(.1f, 1));
        assertEquals(RequisitionCardImage.MAX_PIXELS, RequisitionCardImage.pixels(4000, 2));
    }

    @Test
    void theCardIsDrawnAgainOnlyWhenItsSizeChanges() {
        assertTrue(RequisitionCardImage.redraw(0, 0, 875));
        assertFalse(RequisitionCardImage.redraw(875, 0, 875));
        assertFalse(RequisitionCardImage.redraw(0, 875, 875));
        assertTrue(RequisitionCardImage.redraw(875, 0, 700));
        assertTrue(RequisitionCardImage.redraw(875, 700, 650));
        assertFalse(RequisitionCardImage.redraw(0, 0, 0));
    }
}
