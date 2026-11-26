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

package megamek.server.sbf;

import megamek.common.Hex;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.units.Terrains;

/** Urban Hexes (Buildings), IO:BF pp. 215–218: where a Formation stands in an urban hex. */
final class SBFUrbanCombat {
    /**
     * p. 217: "The second value in the MP Cost per hex column represents the MP cost for a Formation to enter buildings
     * in an urban hex" (Light +0, Medium +1, Heavy +2, Hardened +2), indexed by building class.
     */
    private static final int[] BUILDING_ENTRY_COST = { 0, 0, 1, 2, 2 };

    private SBFUrbanCombat() {
    }

    /** p. 217: infantry "do not apply additional Move costs when moving through an urban hex". */
    static int entryCost(SBFFormation formation, Hex hex) {
        if (formation.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA)) {
            return 0;
        }
        return BUILDING_ENTRY_COST[Math.clamp(hex.terrainLevel(Terrains.BUILDING), 1, 4)];
    }

    /**
     * p. 217: a Formation may enter the buildings "when first moving into the hex ... or a Formation already in an urban
     * hex may enter a building by paying the cost after the slash". Large Elements and aircraft collapse the buildings
     * instead (p. 217) and cannot be inside them.
     */
    static boolean canEnterBuildings(SBFGame game, SBFFormation formation, SBFMovePath route) {
        Hex hex = game.getHex(route.getLastPosition());
        return game.usesUrbanCombat() && (hex != null) && hex.containsTerrain(Terrains.BUILDING)
              && formation.occupiesGroundHex() && !formation.isAerospace() && !formation.isAirVehicle()
              && !SBFFieldFortification.hasLargeElement(formation)
              && !(route.getSteps().isEmpty() && formation.isInsideBuildings())
              && (route.getMpUsed() + entryCost(formation, hex) <= SBFMovePath.maximumMovementPoints(formation, game));
    }
}
