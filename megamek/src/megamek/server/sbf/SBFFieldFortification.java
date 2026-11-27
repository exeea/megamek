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

import static megamek.common.alphaStrike.BattleForceSUA.ENG;
import static megamek.common.alphaStrike.BattleForceSUA.LG;
import static megamek.common.alphaStrike.BattleForceSUA.SLG;
import static megamek.common.alphaStrike.BattleForceSUA.TRN;
import static megamek.common.alphaStrike.BattleForceSUA.VLG;

import megamek.common.Hex;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Hull Down (IO:BF p. 199) and construction of Infantry/advanced field fortifications (p. 220). */
final class SBFFieldFortification {
    private SBFFieldFortification() {
    }

    static int level(Hex hex) {
        return hex != null && hex.containsTerrain(Terrains.FORTIFIED) ? hex.terrainLevel(Terrains.FORTIFIED) : 0;
    }

    static int hullCost(SBFFormation formation) {
        return (formation.getMovement() + 1) / 2;
    }

    static boolean canEnter(SBFGame game, SBFFormation formation, SBFMovePath route) {
        int level = level(game.getHex(route.getLastPosition()));
        boolean large = hasLargeElement(formation);
        return game.getOptions().booleanOption(SBFRuleOptions.MOVE_HULL_DOWN) && level > 0 && !large
              && !formation.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA) && formation.occupiesGroundHex()
              && (!formation.isAirVehicle() || (formation.isAirVehicleLanded() && route.getSteps().isEmpty()))
              && (level >= 2 || formation.isAnyTypeOf(SBFElementType.PM, SBFElementType.V))
              && (!formation.isHullDown() || !route.getSteps().isEmpty()) && formation.getMovement() > 0
              && route.boardingCarrier() == null
              && (route.getMpUsed() + hullCost(formation) + route.getTransportOrders().size()
              <= SBFMovePath.maximumMovementPoints(formation, game));
    }

    static boolean hasLargeElement(SBFFormation formation) {
        return formation.hasAnySUAOf(LG, VLG, SLG) || formation.getUnits().stream()
              .anyMatch(u -> u.hasAnySUAOf(LG, VLG, SLG) || u.getElements().stream().anyMatch(e -> e.hasAnySUAOf(LG, VLG, SLG)));
    }

    static boolean construct(SBFGameManager manager, SBFFormation formation, int index) {
        var game = manager.getGame();
        if (!game.getPhase().isMovement() || formation.isDone() || !formation.occupiesGroundHex() || formation.mustWithdraw()
              || formation.ambushedIn(game.getCurrentRound()) || (index < 0) || (index >= formation.getUnits().size())) {
            return false;
        }
        Hex hex = game.getHex(formation.getPosition());
        var builder = formation.getUnits().get(index);
        int current = level(hex);
        if (hex == null || hex.containsAnyTerrainOf(Terrains.WATER, Terrains.ICE, Terrains.MUD, Terrains.SWAMP)
              || current >= 2 || builder.getCurrentArmor() <= 0 || !(current == 0 ? builder.hasAnySUAOf(TRN, ENG) : builder.hasSUA(ENG))) {
            return false;
        }
        formation.beginFieldConstruction(builder, current + 1);
        if (!new SBFMovementProcessor(manager).processMovement(new SBFMovePath(formation.getId(), formation.getPosition(), game), formation)) {
            formation.finishFieldConstruction();
            return false;
        }
        return true;
    }

    static void finish(SBFGameManager manager) {
        var game = manager.getGame();
        boolean changed = false;
        for (var formation : game.getActiveFormations()) {
            var builder = formation.constructionUnit();
            if (builder == null) {
                continue;
            }
            var hex = game.getHex(formation.constructionSite());
            if (formation.occupiesGroundHex() && formation.constructionSite().equals(formation.getPosition())
                  && formation.getUnits().contains(builder) && builder.getCurrentArmor() > 0 && hex != null
                  && level(hex) == formation.constructionLevel() - 1) {
                hex.addTerrain(new Terrain(Terrains.FORTIFIED, formation.constructionLevel()));
                changed = true;
            }
            formation.finishFieldConstruction();
        }
        if (changed) {
            manager.send(manager.getPacketHelper().createBoardsPacket());
        }
    }
}
