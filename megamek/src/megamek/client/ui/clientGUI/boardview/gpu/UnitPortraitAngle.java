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

/**
 * Where the camera stands when a single unit is drawn on its own, for example in the unit readout. The camera
 * always looks at the middle of the unit; this only says from which side and how high up.
 *
 * @param yawDegrees   How far the camera has walked around the unit. {@code 0} looks straight at the unit's front,
 *                     {@code 90} at its left side, {@code 180} at its back. Always in {@code [0, 360)}.
 * @param pitchDegrees How far above the unit's middle the camera looks down from, {@code 0} being level. Always
 *                     between {@link #MIN_PITCH} and {@link #MAX_PITCH}.
 */
public record UnitPortraitAngle(float yawDegrees, float pitchDegrees) {

    /** The lowest the camera goes: level with the unit's middle, never looking up from below the ground. */
    public static final float MIN_PITCH = 0;

    /**
     * The highest the camera goes. Any higher and a unit that has been turned lies diagonally across the picture,
     * which reads as a strange angle rather than a view from above.
     */
    public static final float MAX_PITCH = 60;

    /** A three-quarter view of the unit's front left, a little from above, as a miniature is usually photographed. */
    public static final UnitPortraitAngle DEFAULT = new UnitPortraitAngle(35, 20);

    /**
     * Keeps every angle inside its range, so a caller can never build one the renderer would have to correct.
     */
    public UnitPortraitAngle {
        yawDegrees = wrapYaw(yawDegrees);
        pitchDegrees = Math.clamp(pitchDegrees, MIN_PITCH, MAX_PITCH);
    }

    /**
     * @param yawChange   Degrees to walk further around the unit; negative walks the other way
     * @param pitchChange Degrees to raise the camera; negative lowers it
     *
     * @return The angle after the change. Walking around wraps past a full circle; raising and lowering stop at the
     *       limits.
     */
    public UnitPortraitAngle turned(float yawChange, float pitchChange) {
        return new UnitPortraitAngle(yawDegrees + yawChange, pitchDegrees + pitchChange);
    }

    private static float wrapYaw(float degrees) {
        float wrapped = degrees % 360;
        return (wrapped < 0) ? wrapped + 360 : wrapped;
    }
}
