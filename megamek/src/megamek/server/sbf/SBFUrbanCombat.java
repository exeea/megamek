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
import megamek.common.board.BoardLocation;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFUnit;
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
        int cost = BUILDING_ENTRY_COST[Math.clamp(hex.terrainLevel(Terrains.BUILDING), 1, 4)];
        // Urban table note *, p. 216: exclusively wheeled Formations "may reduce all movement costs by 1 MP"
        return formation.isExclusivelyWheeled() ? Math.max(0, cost - 1) : cost;
    }

    /**
     * p. 217 Top of Building Line of Sight: "For every 4 levels of building height, Visual Range (see p. 195) is
     * increased by 1 hex to a maximum of 2 hexes."
     */
    static int roofVisualBonus(Hex hex) {
        return (hex == null) ? 0 : Math.min(2, Math.max(0, hex.terrainLevel(Terrains.BLDG_ELEV)) / 4);
    }

    /**
     * p. 217: "When a non-infantry Formation enters or exits buildings in an urban hex, it does damage to the hex equal
     * to the number of non-infantry Elements in the Formation"; infantry "inflict no damage to urban hexes by moving
     * through them". The damage counts against the hex's CF in the same terrain-damage record as other terrain.
     */
    static void damageByPassage(SBFGame game, SBFFormation formation, BoardLocation site) {
        int elements = formation.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA) ? 0 : formation.getUnits().isEmpty() ? 1
              : formation.getUnits().stream().mapToInt(SBFUrbanCombat::nonInfantryElements).sum();
        damageHex(game, site, elements);
    }

    /**
     * p. 216: "Urban hexes may be attacked in the same manner as any other Formation. Attacks against urban hexes are
     * resolved as against an immobile target. Damage to an urban hex is applied to the hex's CF value."
     */
    static boolean canAttackHex(SBFGame game, Hex hex) {
        return game.usesUrbanCombat() && (hex != null) && hex.containsTerrain(Terrains.BUILDING);
    }

    /** The urban hex's CF is its table CF less the damage recorded here. */
    static void damageHex(SBFGame game, BoardLocation site, int damage) {
        if (damage > 0) {
            game.artillery().damageTerrain(site, damage);
        }
    }

    /** A Unit without Element data counts as one Element (as SBFFormationCommand counts them). */
    private static int nonInfantryElements(SBFUnit unit) {
        if (unit.getElements().isEmpty()) {
            return unit.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA) ? 0 : 1;
        }
        return (int) unit.getElements().stream()
              .filter(element -> (element.getASUnitType() == null) || !element.getASUnitType().isInfantry()).count();
    }

    /** p. 217 Building Weight Capacity: the largest Size the roofs carry (Light 1, Medium 2, Heavy 3, Hardened 4). */
    static int weightCapacity(Hex hex) {
        return Math.clamp(hex.terrainLevel(Terrains.BUILDING), 1, 4);
    }

    /**
     * p. 217 "Climbing and Standing on Buildings": the MP to reach the roofs where the route ends, or -1 when this
     * Formation cannot. Infantry and ProtoMechs climb inside the buildings at 1 MP per level of building height; 'Mechs
     * climb the outside at 2 MP per level and "may not change levels once they are inside a building". A Formation
     * heavier than the weight capacity would collapse the buildings; until collapses are played it may not climb.
     */
    static int climbCost(SBFGame game, SBFFormation formation, SBFMovePath route) {
        Hex hex = game.getHex(route.getLastPosition());
        boolean stays = route.getSteps().isEmpty();
        if (!game.usesUrbanCombat() || (hex == null) || !hex.containsTerrain(Terrains.BUILDING)
              || !formation.occupiesGroundHex() || (stays && formation.isOnTopOfBuildings())) {
            return -1;
        }
        // "All non-battle armor infantry units are treated as if they have a Size of 0."
        int size = formation.isType(SBFElementType.CI) ? 0 : formation.getSize();
        if (size > weightCapacity(hex)) {
            return -1;
        }
        int height = Math.max(0, hex.terrainLevel(Terrains.BLDG_ELEV));
        boolean inside = stays && formation.isInsideBuildings();
        if (formation.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA, SBFElementType.PM)) {
            return (inside ? 0 : entryCost(formation, hex)) + height;
        }
        return (formation.isType(SBFElementType.BM) && !inside) ? 2 * height : -1;
    }

    static boolean canClimbBuildings(SBFGame game, SBFFormation formation, SBFMovePath route) {
        int cost = climbCost(game, formation, route);
        return (cost >= 0) && (route.getMpUsed() + cost <= SBFMovePath.maximumMovementPoints(formation, game));
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
