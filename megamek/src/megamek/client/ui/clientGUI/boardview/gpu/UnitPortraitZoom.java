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
 * How close the camera is to a unit drawn on its own, as in the unit readout, and which part of the unit it is looking
 * at. Zooming happens towards the mouse pointer, as in a map or picture viewer: the part of the unit under the pointer
 * stays under it while it grows, so pointing at the head and rolling the wheel zooms in on the head.
 *
 * <p>Positions are given in "whole view" coordinates: the picture showing the whole unit (factor {@code 1}) spans
 * {@code -1} to {@code 1} across and from bottom to top. The zoomed picture shows a part of that, {@code 2 / factor}
 * wide and high, centred on ({@link #centerX()}, {@link #centerY()}). The centre is kept where the zoomed picture
 * stays inside the whole view, so zooming never drifts off the unit into empty space.</p>
 *
 * @param factor  How many times larger the unit appears than in the whole view; between {@link #MIN_FACTOR} and
 *                {@link #MAX_FACTOR}
 * @param centerX The middle of the zoomed picture across the whole view, {@code -1} at its left edge
 * @param centerY The middle of the zoomed picture up the whole view, {@code -1} at its bottom edge
 */
public record UnitPortraitZoom(float factor, float centerX, float centerY) {

    /** The whole unit fits the picture; zooming out any further would only shrink it. */
    public static final float MIN_FACTOR = 1;

    /**
     * Close enough to read a cockpit or a weapon. Any closer and the narrow lens shows only a flat stretch of armour,
     * and on a wide unit seen from the side the camera would come right up against it.
     */
    public static final float MAX_FACTOR = 3;

    /** How much one notch of the mouse wheel zooms, as a multiple. */
    public static final float STEP_PER_NOTCH = 1.15f;

    /** The starting view: the whole unit, centred. */
    public static final UnitPortraitZoom DEFAULT = new UnitPortraitZoom(MIN_FACTOR, 0, 0);

    /**
     * Keeps the factor inside its range and the zoomed picture inside the whole view, so a caller can never build one
     * the renderer would have to correct.
     */
    public UnitPortraitZoom {
        factor = Math.clamp(factor, MIN_FACTOR, MAX_FACTOR);
        float centerLimit = 1 - (1 / factor);
        centerX = Math.clamp(centerX, -centerLimit, centerLimit);
        centerY = Math.clamp(centerY, -centerLimit, centerLimit);
    }

    /**
     * @param wheelRotation The mouse wheel's movement in notches, as Swing reports it: negative when the wheel is
     *                      rolled away from the user, which zooms in. Touchpads report fractions of a notch.
     * @param pointerX      Where the mouse pointer is across the current picture, {@code -1} at its left edge and
     *                      {@code 1} at its right
     * @param pointerY      Where the mouse pointer is up the current picture, {@code -1} at its bottom edge and
     *                      {@code 1} at its top
     *
     * @return The zoom after the wheel moved, keeping the part of the unit under the pointer where it is, and
     *       stopping at the limits
     */
    public UnitPortraitZoom wheeled(double wheelRotation, float pointerX, float pointerY) {
        float nextFactor = Math.clamp((float) (factor * Math.pow(STEP_PER_NOTCH, -wheelRotation)), MIN_FACTOR,
              MAX_FACTOR);
        // The whole-view point under the pointer now, and the centre that puts it under the pointer again.
        float pointedX = centerX + (pointerX / factor);
        float pointedY = centerY + (pointerY / factor);
        return new UnitPortraitZoom(nextFactor, pointedX - (pointerX / nextFactor), pointedY - (pointerY / nextFactor));
    }
}
