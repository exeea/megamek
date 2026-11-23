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
import megamek.common.units.Terrains;

/** Applies the SBF Movement Cost Table to each constituent's actual movement mode (IO:BF pp. 164–166). */
record SBFTerrainMovement(SBFElementType type, SBFMovementMode mode, ASSpecialAbilityCollector abilities,
      boolean industrialMek) {
    static final int PROHIBITED = -1;

    /** Element-backed formations retain restrictions that a single converted movement code cannot express. */
    static Stream<SBFTerrainMovement> of(SBFFormation formation) {
        if (formation.getUnits().isEmpty()) {
            return Stream.of(new SBFTerrainMovement(formation.getType(), formation.getMovementMode(), formation, false));
        }
        return formation.getUnits().stream()
              .filter(unit -> (unit.getArmor() == 0) || (unit.getCurrentArmor() > 0))
              .flatMap(unit -> {
                  if (unit.getElements().isEmpty()) {
                      return Stream.of(new SBFTerrainMovement(unit.getType(), unit.getMovementMode(), unit, false));
                  }
                  return unit.getElements().stream().map(element -> new SBFTerrainMovement(
                        SBFElementType.getUnitType(element), SBFMovementMode.modeForElement(unit, element),
                        element, element.isType(ASUnitType.IM)));
              });
    }

    boolean earnsRoadBonus() {
        return (mode == SBFMovementMode.WHEELED) || (mode == SBFMovementMode.TRACKED)
              || (mode == SBFMovementMode.QUAD_TRACKED) || (mode == SBFMovementMode.QUAD_WHEELED);
    }

    static boolean isPaved(Hex hex) {
        return hex.hasPavementOrRoad() || hex.containsTerrain(Terrains.BUILDING);
    }

    boolean canOccupy(Hex hex, boolean expandedWoods) {
        if (mode == SBFMovementMode.RAIL) {
            return railTerrainAllowed(hex) && (hex.containsTerrain(Terrains.RAIL) || hex.containsTerrain(Terrains.BRIDGE_RAIL));
        }
        return cost(hex, hex, 0, expandedWoods) != PROHIBITED;
    }

    int cost(Hex from, Hex to, int direction, boolean expandedWoods) {
        int levels = Math.abs(to.getLevel() - from.getLevel());
        if (mode == SBFMovementMode.VTOL) {
            // Airborne VTOLs ignore the underlying terrain and maintain one level of clearance (IO:BF p. 166).
            return 1 + levels;
        }
        if (mode == SBFMovementMode.RAIL) {
            if (!railTerrainAllowed(to) || !hasRailExit(from, direction) || !hasRailExit(to, (direction + 3) % 6)) {
                return PROHIBITED;
            }
        }
        boolean submarine = mode == SBFMovementMode.SUBMARINE;
        boolean naval = (mode == SBFMovementMode.NAVAL) || submarine;
        boolean infantry = (type == SBFElementType.CI) || (type == SBFElementType.BA);
        boolean mek = type == SBFElementType.BM;
        if (!submarine && ((levels > 2) || ((levels > 1) && !mek))) {
            return PROHIBITED;
        }
        int cost = 1 + levels * ((infantry || (type == SBFElementType.V)) && !submarine ? 2 : 1);
        int depth = to.terrainLevel(Terrains.WATER);
        if (naval) {
            return (depth > 0) ? cost : PROHIBITED;
        }
        if (mode == SBFMovementMode.WIGE) {
            return cost;
        }
        if (to.containsTerrain(Terrains.BUILDING)) {
            int buildingCost = Math.clamp(to.terrainLevel(Terrains.BUILDING), 1, 4);
            return cost + (infantry ? Math.max(0, buildingCost - 2) : buildingCost);
        }
        if (to.hasPavementOrRoad() || (mode == SBFMovementMode.RAIL)) {
            return cost;
        }
        if (depth > 0) {
            if (mode == SBFMovementMode.HOVER) {
                return cost;
            }
            if (abilities.hasSUA(AMP)) {
                return cost + (earnsRoadBonus() ? 1 : 0);
            }
            if ((type == SBFElementType.V) || (industrialMek && !abilities.hasSUA(SEAL))
                  || (infantry && !abilities.hasSUA(UMU) && (mode != SBFMovementMode.BA_UMU))) {
                return PROHIBITED;
            }
            // An explicitly mapped full water hex adds 2 MP for its width (Water, IO:BF p. 166).
            return cost + 2 + (depth == 1 ? 1 : 6) + (depth >= 4 ? 1 : 0);
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
            cost += wheeledOrHover ? 2 : surcharge;
        }
        if (to.containsTerrain(Terrains.RUBBLE)) {
            cost++;
        }
        if (to.containsTerrain(Terrains.ROUGH)) {
            cost += wheeledOrHover ? 2 : 1;
        }
        return cost;
    }

    private static boolean railTerrainAllowed(Hex hex) {
        return !hex.containsTerrain(Terrains.WOODS) && !hex.containsTerrain(Terrains.JUNGLE)
              && !hex.containsTerrain(Terrains.RUBBLE);
    }

    private static boolean hasRailExit(Hex hex, int direction) {
        return hex.containsTerrainExit(Terrains.RAIL, direction) || hex.containsTerrainExit(Terrains.BRIDGE_RAIL, direction);
    }
}
