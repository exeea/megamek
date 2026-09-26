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

import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Test;

/**
 * The readout's model camera: turning wraps round the unit, raising and lowering stop at the limits, and the angles
 * point the camera where their names say.
 */
class UnitPortraitAngleTest {

    private static final float TOLERANCE = 1e-4f;

    @Test
    void walkingPastAFullCircleWrapsRound() {
        UnitPortraitAngle angle = new UnitPortraitAngle(350, 20).turned(30, 0);

        assertEquals(20, angle.yawDegrees(), TOLERANCE);
    }

    @Test
    void walkingBackPastTheFrontWrapsRound() {
        UnitPortraitAngle angle = new UnitPortraitAngle(10, 20).turned(-30, 0);

        assertEquals(340, angle.yawDegrees(), TOLERANCE);
    }

    @Test
    void raisingTheCameraStopsShortOfLookingStraightDown() {
        UnitPortraitAngle angle = UnitPortraitAngle.DEFAULT.turned(0, 500);

        assertEquals(UnitPortraitAngle.MAX_PITCH, angle.pitchDegrees(), TOLERANCE);
    }

    @Test
    void loweringTheCameraStopsAtLevel() {
        UnitPortraitAngle angle = UnitPortraitAngle.DEFAULT.turned(0, -500);

        assertEquals(UnitPortraitAngle.MIN_PITCH, angle.pitchDegrees(), TOLERANCE);
    }

    @Test
    void theStartingViewIsAlreadyInRange() {
        UnitPortraitAngle angle = UnitPortraitAngle.DEFAULT;

        assertEquals(angle, new UnitPortraitAngle(angle.yawDegrees(), angle.pitchDegrees()));
    }

    @Test
    void yawZeroLooksAtTheFront() {
        Vector3 front = new Vector3(0, 1, 0);

        Vector3 direction = GpuUnitPortraitRenderer.direction(front, 0, 0);

        assertVector(0, 1, 0, direction);
    }

    @Test
    void yawNinetyLooksAtTheUnitsLeftSide() {
        // Models face +Y with +X to their right, so the unit's left is -X.
        Vector3 front = new Vector3(0, 1, 0);

        Vector3 direction = GpuUnitPortraitRenderer.direction(front, 90, 0);

        assertVector(-1, 0, 0, direction);
    }

    @Test
    void pitchRaisesTheCameraAndKeepsTheDirectionAUnitVector() {
        Vector3 front = new Vector3(0, 1, 0);

        Vector3 direction = GpuUnitPortraitRenderer.direction(front, 0, 30);

        assertVector(0, (float) Math.cos(Math.toRadians(30)), 0.5f, direction);
        assertEquals(1, direction.len(), TOLERANCE);
    }

    private static void assertVector(float x, float y, float z, Vector3 actual) {
        assertEquals(x, actual.x, TOLERANCE, "x");
        assertEquals(y, actual.y, TOLERANCE, "y");
        assertEquals(z, actual.z, TOLERANCE, "z");
    }
}
