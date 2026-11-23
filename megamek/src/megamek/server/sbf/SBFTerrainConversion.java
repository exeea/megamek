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

package megamek.server.sbf;

import megamek.common.Hex;
import megamek.common.board.BoardLocation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Terrain Factor and Conversion Table, IO:BF pp. 221–222. */
final class SBFTerrainConversion {
    private record Feature(int type, int factor, int replacement) { }
    private static final int NO_REPLACEMENT = 0;
    private static final int SUBLEVEL = -1;

    private static Feature exposedFeature(Hex hex) {
        if (hex.containsTerrain(Terrains.BUILDING)) {
            // Urban hexes have their own construction factors (p. 215). Terrain beneath one is protected.
            return null;
        }
        if (hex.containsTerrain(Terrains.SNOW)) {
            return new Feature(Terrains.SNOW, 840, Terrains.MUD);
        }
        if (hex.containsTerrain(Terrains.ICE)) {
            return new Feature(Terrains.ICE, 840, NO_REPLACEMENT);
        }
        if (hex.containsTerrain(Terrains.JUNGLE)) {
            return new Feature(Terrains.JUNGLE, 1750, Terrains.ROUGH);
        }
        if (hex.containsTerrain(Terrains.WOODS)) {
            return new Feature(Terrains.WOODS, 1400, Terrains.ROUGH);
        }
        if (hex.containsTerrain(Terrains.ROAD) && !hex.isStrategicRoadDamaged()) {
            int factor = switch (hex.terrainLevel(Terrains.ROAD)) {
                case Terrains.ROAD_LVL_DIRT -> 420;
                case Terrains.ROAD_LVL_GRAVEL -> 1050;
                default -> 3150;
            };
            return new Feature(Terrains.ROAD, factor, Terrains.ROUGH);
        }
        if (hex.containsTerrain(Terrains.PAVEMENT)) {
            return new Feature(Terrains.PAVEMENT, 4200, Terrains.ROUGH);
        }
        if (hex.containsTerrain(Terrains.TUNDRA)) {
            return new Feature(Terrains.TUNDRA, 1470, Terrains.ROUGH);
        }
        if (hex.containsTerrain(Terrains.ROUGH) || hex.isClear()) {
            return new Feature(Terrains.ROUGH, 1400, SUBLEVEL);
        }
        return null;
    }

    static boolean canDamage(Hex hex) {
        return hex != null && exposedFeature(hex) != null;
    }

    static boolean canClear(Hex hex, int passage) {
        var feature = hex == null ? null : exposedFeature(hex);
        return feature != null && (feature.type() == Terrains.WOODS || feature.type() == Terrains.JUNGLE)
              && (passage == 0 || !hex.hasStrategicPassage(passage));
    }

    /** IO:BF p. 221, project ruling. Full work uses the feature TF; a two-hexside passage requires 200 TF. */
    static boolean clear(SBFGame game, BoardLocation site, int passage, int rating) {
        Hex hex = game.getHex(site);
        if (rating <= 0 || !canClear(hex, passage)) {
            return false;
        }
        var feature = exposedFeature(hex);
        int factor = passage == 0 ? feature.factor() : 200;
        int work = 10 * 3 * rating;
        int total = passage == 0 ? game.artillery().damageTerrain(site, work)
              : game.artillery().addPassageWork(site, passage, work);
        // Round the number of turns normally, including an exact half rounding up.
        if (2L * (factor - total) >= work) {
            return false;
        }
        if (passage == 0) {
            convert(game, site, hex, feature);
        } else {
            var changed = hex.duplicate();
            changed.addStrategicPassage(passage);
            game.getBoard(site.boardId()).setHex(site.coords(), changed);
        }
        return true;
    }

    /** Returns true when clients need an updated board. */
    static boolean damage(SBFGame game, BoardLocation location, int damage) {
        Hex original = game.getHex(location);
        Feature feature = original == null ? null : exposedFeature(original);
        if ((damage <= 0) || (feature == null) || (game.artillery().damageTerrain(location, damage) < feature.factor())) {
            return false;
        }
        convert(game, location, original, feature);
        return true;
    }

    private static void convert(SBFGame game, BoardLocation location, Hex original, Feature feature) {
        Hex changed = original.duplicate();
        if (feature.type() == Terrains.WOODS || feature.type() == Terrains.JUNGLE) {
            changed.clearStrategicPassages();
        }
        if (feature.type() == Terrains.ROAD) {
            changed.damageStrategicRoad();
        } else {
            changed.removeTerrain(feature.type());
            if (!changed.containsAnyTerrainOf(Terrains.WOODS, Terrains.JUNGLE)) {
                changed.removeTerrain(Terrains.FOLIAGE_ELEV);
            }
        }
        if (feature.replacement() == SUBLEVEL) {
            changed.setLevel(changed.getLevel() - 1);
        } else if (feature.replacement() != NO_REPLACEMENT) {
            changed.addTerrain(new Terrain(feature.replacement(), 1));
        }
        game.getBoard(location.boardId()).setHex(location.coords(), changed);
        game.artillery().convertedTerrain(location);
    }
}
