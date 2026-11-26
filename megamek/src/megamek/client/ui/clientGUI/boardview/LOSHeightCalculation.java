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
package megamek.client.ui.clientGUI.boardview;

/**
 * Converts TW (Total Warfare) heights to absolute heights for the ruler's line of sight ({@link RulerModel}).
 *
 * <p>TW height is 1-indexed: a standard Mek occupies 2 levels, a vehicle occupies 1 level.</p>
 */
final class LOSHeightCalculation {

    private LOSHeightCalculation() {
        // utility class
    }

    /**
     * Converts a TW height and hex ground level to an absolute height value for LOS calculations.
     *
     * <p>For ground units, subtracts 1 from TW height (converting from 1-indexed TW to 0-indexed
     * code units) and adds the hex ground level.</p>
     *
     * <p>For altitude units (airborne aero), the spinner value IS the altitude, which maps directly
     * to the elevation used by LOS calculations on ground maps. The hex level is not added because altitude is already
     * an absolute value independent of hex terrain.</p>
     *
     * @param twHeight       the TW height in levels (1-indexed) or altitude for aero units
     * @param hexLevel       the hex ground elevation level
     * @param isAltitudeUnit true if the unit uses altitude (airborne aero)
     *
     * @return the absolute height for LOS calculations
     */
    static int toAbsoluteHeight(int twHeight, int hexLevel, boolean isAltitudeUnit) {
        if (isAltitudeUnit) {
            // Altitude IS the elevation for airborne aero on ground maps
            return twHeight;
        }
        return (twHeight - 1) + hexLevel;
    }

    /**
     * Converts a TW height and hex ground level to an absolute height value for LOS calculations. Assumes a ground unit
     * (not altitude-based).
     *
     * @param twHeight the TW height in levels (1-indexed)
     * @param hexLevel the hex ground elevation level
     *
     * @return the absolute height for LOS calculations
     */
    static int toAbsoluteHeight(int twHeight, int hexLevel) {
        return toAbsoluteHeight(twHeight, hexLevel, false);
    }
}
