/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
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

package megamek.common.strategicBattleSystems;

import static megamek.common.alphaStrike.BattleForceSUA.AMP;
import static megamek.common.alphaStrike.BattleForceSUA.SEAL;
import static megamek.common.alphaStrike.BattleForceSUA.UMU;

import java.util.stream.Stream;

import megamek.common.Hex;
import megamek.common.alphaStrike.ASSpecialAbilityCollector;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.units.Terrains;

/** Applies the SBF Movement Cost Table to each constituent's actual movement mode (IO:BF pp. 164–166). */
record SBFTerrainMovement(SBFElementType type, SBFMovementMode mode, ASSpecialAbilityCollector abilities,
      boolean industrialMek, boolean submarineSurfaced) {
    static final int PROHIBITED = -1;

    /** Element-backed formations retain restrictions that a single converted movement code cannot express. */
    static Stream<SBFTerrainMovement> of(SBFFormation formation) {
        if (formation.getUnits().isEmpty()) {
            return Stream.of(new SBFTerrainMovement(formation.getType(), formation.getMovementMode(), formation, false, formation.isSubmarineSurfaced()));
        }
        return formation.getUnits().stream()
              .filter(unit -> (unit.getArmor() == 0) || (unit.getCurrentArmor() > 0))
              .flatMap(unit -> of(formation, unit));
    }

    static Stream<SBFTerrainMovement> of(SBFFormation formation, SBFUnit unit) {
        var fallback = new SBFTerrainMovement(unit.getType() == SBFElementType.UNKNOWN ? formation.getType() : unit.getType(),
              unit.getMovementMode() == SBFMovementMode.UNKNOWN ? formation.getMovementMode() : unit.getMovementMode(),
              unit, false, formation.isSubmarineSurfaced());
        if (unit.getElements().isEmpty()) {
            return Stream.of(fallback);
        }
        return unit.getElements().stream().map(element -> element.getASUnitType() == null ? fallback
              : new SBFTerrainMovement(SBFElementType.getUnitType(element), SBFMovementMode.modeForElement(unit, element),
              element, element.isType(ASUnitType.IM), formation.isSubmarineSurfaced()));
    }

    /** A bridge over otherwise open water does not make it a shore. */
    static boolean isFullWater(Hex hex) {
        return (hex != null) && (hex.terrainLevel(Terrains.WATER) > 0)
              && !hex.containsAnyTerrainOf(Terrains.WOODS, Terrains.JUNGLE, Terrains.ROUGH, Terrains.RUBBLE,
                    Terrains.BUILDING, Terrains.ROAD, Terrains.PAVEMENT, Terrains.SAND, Terrains.SWAMP, Terrains.FIELDS);
    }

    boolean earnsRoadBonus() {
        return (mode == SBFMovementMode.WHEELED) || (mode == SBFMovementMode.TRACKED)
              || (mode == SBFMovementMode.QUAD_TRACKED) || (mode == SBFMovementMode.QUAD_WHEELED);
    }

    static boolean isPaved(Hex hex) {
        return hex.hasPavementOrRoad() || hex.containsTerrain(Terrains.BUILDING);
    }

    boolean canOccupy(Hex hex, SBFGame game) {
        if (mode == SBFMovementMode.RAIL) {
            return (game.usesAdvancedTerrain() || railTerrainAllowed(hex)) && (hex.containsTerrain(Terrains.RAIL) || hex.containsTerrain(Terrains.BRIDGE_RAIL));
        }
        return terrainCost(hex, hex, 0, game) != PROHIBITED;
    }

    private boolean isInfantry() {
        return type == SBFElementType.CI || type == SBFElementType.BA;
    }

    private boolean fliesOverTerrain(SBFGame game) {
        return mode == SBFMovementMode.VTOL || (game.usesAdvancedTerrain() && mode == SBFMovementMode.AIRSHIP);
    }

    int elevationCost(int levels, SBFGame game) {
        if (levels == 0 || fliesOverTerrain(game)) {
            return 0;
        }
        if (mode == SBFMovementMode.SUBMARINE) {
            return levels;
        }
        if (levels > 2 || (levels > 1 && type != SBFElementType.BM)) {
            return PROHIBITED;
        }
        return levels * (isInfantry() || type == SBFElementType.V ? 2 : 1);
    }

    int terrainCost(Hex from, Hex to, int direction, SBFGame game) {
        boolean advanced = game.usesAdvancedTerrain();
        boolean expandedWoods = game.usesExpandedWoods();
        if (fliesOverTerrain(game)) {
            // Airborne VTOLs ignore the underlying terrain and maintain one level of clearance (IO:BF p. 166).
            return 1;
        }
        if (mode == SBFMovementMode.RAIL) {
            if ((!advanced && !railTerrainAllowed(to)) || !hasRailExit(from, direction) || !hasRailExit(to, (direction + 3) % 6)) {
                return PROHIBITED;
            }
        }
        boolean submarine = mode == SBFMovementMode.SUBMARINE;
        boolean naval = (mode == SBFMovementMode.NAVAL) || submarine;
        boolean infantry = isInfantry();
        boolean mek = type == SBFElementType.BM;
        int cost = 1;
        int depth = to.terrainLevel(Terrains.WATER);
        if (advanced && naval && to.containsTerrain(Terrains.ICE)) {
            return PROHIBITED;
        }
        if (naval) {
            boolean needsOpenWater = (submarine && !submarineSurfaced)
                  || abilities.hasAnySUAOf(BattleForceSUA.VLG, BattleForceSUA.SLG);
            return (depth > 0) && (!needsOpenWater || isFullWater(to)) ? cost + (advanced && to.containsTerrain(Terrains.RAPIDS) ? 1 : 0) : PROHIBITED;
        }
        if (mode == SBFMovementMode.WIGE) {
            return cost;
        }
        if (advanced && mode == SBFMovementMode.RAIL) {
            return cost;
        }
        if (advanced && to.containsTerrain(Terrains.INDUSTRIAL)) {
            return cost + (mek ? 1 : 0);
        }
        if (to.containsTerrain(Terrains.BUILDING)) {
            int buildingCost = Math.clamp(to.terrainLevel(Terrains.BUILDING), 1, 4);
            if (advanced) {
                return cost + (infantry ? 0 : type == SBFElementType.PM ? 1 : Math.max(0, buildingCost - largeMekBonus()));
            }
            return cost + (infantry ? Math.max(0, buildingCost - 2) : buildingCost);
        }
        if (to.hasPavementOrRoad() || (mode == SBFMovementMode.RAIL)) {
            return cost + ((mode != SBFMovementMode.RAIL && to.isStrategicRoadDamaged()) ? 1 : 0);
        }
        if (advanced && to.containsTerrain(Terrains.ICE)) {
            return cost + 1;
        }
        if (depth > 0) {
            if (advanced && to.containsTerrain(Terrains.RAPIDS)) {
                cost++;
            }
            if (mode == SBFMovementMode.HOVER) {
                return cost;
            }
            if (abilities.hasSUA(AMP)) {
                return cost + (earnsRoadBonus() ? 1 : 0);
            }
            if ((type == SBFElementType.V) || (industrialMek && (!advanced || depth >= 2) && !abilities.hasSUA(SEAL))
                  || (infantry && !abilities.hasSUA(UMU) && (mode != SBFMovementMode.BA_UMU))) {
                return PROHIBITED;
            }
            // An explicitly mapped full water hex adds 2 MP for its width (Water, IO:BF p. 166).
            return cost + 2 + (depth == 1 ? 1 : advanced && depth >= 3 ? 8 : 6) + (!advanced && depth >= 4 ? 1 : 0);
        }
        boolean wheeledOrHover = (mode == SBFMovementMode.WHEELED) || (mode == SBFMovementMode.QUAD_WHEELED)
              || (mode == SBFMovementMode.HOVER);
        boolean jungle = to.containsTerrain(Terrains.JUNGLE);
        if (to.containsTerrain(Terrains.WOODS) || jungle) {
            int density = expandedWoods ? Math.max(to.terrainLevel(Terrains.WOODS), to.terrainLevel(Terrains.JUNGLE)) : 1;
            if ((density > 1 && type == SBFElementType.V) || (density >= 3 && !infantry)) {
                return PROHIBITED;
            }
            boolean expandedJungle = jungle && expandedWoods;
            int surcharge = density + (expandedJungle ? 1 : 0);
            if (infantry && !expandedJungle && density < 3) {
                surcharge--;
            }
            cost += Math.max(0, (wheeledOrHover ? 2 : surcharge) - (advanced ? largeMekBonus() : 0));
        }
        if (to.containsTerrain(Terrains.RUBBLE)) {
            cost += advanced ? Math.max(0, (wheeledOrHover || to.terrainLevel(Terrains.RUBBLE) >= 6 ? 2 : 1) - largeMekBonus()) : 1;
        }
        if (to.containsTerrain(Terrains.ROUGH)) {
            cost += Math.max(0, (wheeledOrHover || (advanced && to.terrainLevel(Terrains.ROUGH) >= 2) ? 2 : 1) - (advanced ? largeMekBonus() : 0));
        }
        if (advanced) {
            if (to.containsTerrain(Terrains.MUD)) {
                cost++;
            }
            if (to.containsTerrain(Terrains.SWAMP)) {
                cost += mek || type == SBFElementType.PM ? 1 : 2;
            }
            if (to.containsTerrain(Terrains.SAND) && (infantry || (mode.isWheeled() && !abilities.hasSUA(BattleForceSUA.DUN)))) {
                cost++;
            }
            if (to.containsTerrain(Terrains.RAIL)) {
                cost++;
            }
            if (to.terrainsPresent() == 0 && mode.isWheeled() && !abilities.hasSUA(BattleForceSUA.ORO)
                  && abilities instanceof AlphaStrikeElement element && element.isSupportVehicle()) {
                cost++;
            }
        }
        return cost;
    }

    private int largeMekBonus() {
        return type == SBFElementType.BM && !industrialMek && abilities.hasSUA(BattleForceSUA.LG) ? 1 : 0;
    }

    private static boolean railTerrainAllowed(Hex hex) {
        return !hex.containsTerrain(Terrains.WOODS) && !hex.containsTerrain(Terrains.JUNGLE)
              && !hex.containsTerrain(Terrains.RUBBLE);
    }

    private static boolean hasRailExit(Hex hex, int direction) {
        return hex.containsTerrainExit(Terrains.RAIL, direction) || hex.containsTerrainExit(Terrains.BRIDGE_RAIL, direction);
    }
}
