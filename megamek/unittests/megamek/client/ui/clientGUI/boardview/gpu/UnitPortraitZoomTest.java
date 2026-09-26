/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The readout model's mouse-wheel zoom. Rolling the wheel away zooms in towards the mouse pointer, so the part of the
 * unit under the pointer stays under it; rolling it back zooms out. Both stop at their limits, and the zoomed picture
 * never leaves the whole-unit view.
 */
class UnitPortraitZoomTest {

    private static final float TOLERANCE = 1e-4f;

    @Test
    void theStartingViewShowsTheWholeUnitCentred() {
        UnitPortraitZoom zoom = UnitPortraitZoom.DEFAULT;

        assertEquals(UnitPortraitZoom.MIN_FACTOR, zoom.factor(), TOLERANCE);
        assertEquals(0, zoom.centerX(), TOLERANCE);
        assertEquals(0, zoom.centerY(), TOLERANCE);
    }

    @Test
    void rollingTheWheelAwayZoomsInOneStepPerNotch() {
        // Swing reports a wheel rolled away from the user as a negative rotation.
        UnitPortraitZoom zoom = UnitPortraitZoom.DEFAULT.wheeled(-2, 0, 0);

        assertEquals(UnitPortraitZoom.STEP_PER_NOTCH * UnitPortraitZoom.STEP_PER_NOTCH, zoom.factor(), TOLERANCE);
    }

    @Test
    void zoomingWithThePointerInTheMiddleStaysCentred() {
        UnitPortraitZoom zoom = UnitPortraitZoom.DEFAULT.wheeled(-5, 0, 0);

        assertEquals(0, zoom.centerX(), TOLERANCE);
        assertEquals(0, zoom.centerY(), TOLERANCE);
    }

    @Test
    void thePartUnderThePointerStaysUnderIt() {
        // Pointing at the head: halfway up the picture and a little to the left.
        float pointerX = -0.2f;
        float pointerY = 0.5f;
        UnitPortraitZoom before = new UnitPortraitZoom(1.5f, 0.1f, 0.2f);

        UnitPortraitZoom after = before.wheeled(-1, pointerX, pointerY);

        assertTrue(after.factor() > before.factor());
        assertEquals(before.centerX() + (pointerX / before.factor()), after.centerX() + (pointerX / after.factor()),
              TOLERANCE);
        assertEquals(before.centerY() + (pointerY / before.factor()), after.centerY() + (pointerY / after.factor()),
              TOLERANCE);
    }

    @Test
    void zoomingAllTheWayOutReturnsToTheWholeUnitCentred() {
        UnitPortraitZoom zoom = UnitPortraitZoom.DEFAULT.wheeled(-6, 0.8f, 0.9f).wheeled(20, -0.5f, -0.5f);

        assertEquals(UnitPortraitZoom.MIN_FACTOR, zoom.factor(), TOLERANCE);
        assertEquals(0, zoom.centerX(), TOLERANCE);
        assertEquals(0, zoom.centerY(), TOLERANCE);
    }

    @Test
    void zoomingInStopsAtTheLimit() {
        UnitPortraitZoom zoom = UnitPortraitZoom.DEFAULT.wheeled(-100, 0, 0);

        assertEquals(UnitPortraitZoom.MAX_FACTOR, zoom.factor(), TOLERANCE);
    }

    @Test
    void theZoomedPictureNeverLeavesTheWholeView() {
        // Pointing at a corner and zooming in hard would otherwise drift the view off the unit.
        UnitPortraitZoom zoom = UnitPortraitZoom.DEFAULT.wheeled(-100, 1, 1);
        float limit = 1 - (1 / zoom.factor());

        assertTrue(zoom.centerX() <= limit + TOLERANCE);
        assertTrue(zoom.centerY() <= limit + TOLERANCE);
    }

    @Test
    void aTouchpadsFractionOfANotchZoomsALittle() {
        UnitPortraitZoom zoom = UnitPortraitZoom.DEFAULT.wheeled(-0.25, 0, 0);

        assertTrue(zoom.factor() > UnitPortraitZoom.MIN_FACTOR);
        assertTrue(zoom.factor() < UnitPortraitZoom.STEP_PER_NOTCH);
    }
}
