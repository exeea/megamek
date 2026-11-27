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

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.compute.Compute;
import megamek.common.board.BoardLocation;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame.PendingHit;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.units.IBuilding;
import megamek.common.units.Terrains;

/** Checks the real starting and entered hexes, with a saved cursor for interrupted movement (IO:BF pp. 200–202). */
final class SBFMovementHazards {
    private SBFMovementHazards() {
    }

    static boolean stops(SBFGameManager manager, SBFFormation mover, BoardLocation location, SBFMovePath path, int step, IntSupplier dice) {
        var game = manager.getGame();
        if (!path.needsTerrainCheck(step)) {
            return path.stoppedByTerrainAt(step);
        }
        Hex hex = game.getHex(location);
        boolean stuck = false;
        if (hex != null && game.usesAdvancedTerrain()) {
            if (step > 0 && hex.hasBridge() && usesBridge(mover)) {
                collapseOverloadedBridge(manager, mover, location, path, step);
                if (game.getFormation(mover.getId()).isEmpty()) {
                    path.completeTerrainCheck(step, true);
                    return true;
                }
            }
            markDepth(manager, mover, location);
            if (mover.boggedIn(game.getCurrentRound())) {
                stuck = true;
            } else if (mover.isBoggedDown() || (bogTerrain(hex) && subjectToBogging(mover, step))) {
                int roll = dice.getAsInt();
                int jump = mover.getUnits().stream().mapToInt(SBFUnit::getJumpMove).min().orElse(mover.getJumpMove());
                boolean difficult = hex.containsAnyTerrainOf(Terrains.TUNDRA, Terrains.MUD) || hex.terrainLevel(Terrains.SNOW) >= 2;
                int tn = mover.getSkill() + (difficult ? 2 : 1);
                stuck = roll == 2 || roll + jump < tn;
                mover.resolveBogCheck(stuck, game.getCurrentRound());
                manager.sendServerChat(mover.getOwnerId(), mover.generalName() + " rolled " + roll + " + " + jump
                      + " against " + tn + (stuck ? " and bogged down." : " and kept moving."));
            }
        }
        path.completeTerrainCheck(step, stuck);
        if (step > 0 && hex != null && (game.minefields().fields().containsKey(location) || hex.hasStrategicInferno())) {
            SBFTransport.applyThrough(manager, mover, path, step);
            var start = mover.getPosition();
            int entrance = mover.passageEntrance();
            mover.setPosition(location);
            SBFMinefieldControl.enter(manager, mover, location);
            if (game.getFormation(mover.getId()).isPresent()) {
                mover.setPosition(start);
                mover.setPassageEntrance(entrance);
            }
        }
        return path.stoppedByTerrainAt(step);
    }

    /** A miss can ignite a Heavy Industrial hex; nearby attackers suffer equal damage (IO:BF p. 200). */
    static void industrialMiss(SBFGameManager manager, SBFFormation attacker, SBFFormation target,
          ASRange range, int missedDamage, IntSupplier check, IntSupplier severity) {
        var game = manager.getGame();
        if (!game.usesAdvancedTerrain() || !target.occupiesGroundHex() || !game.getHex(target.getPosition()).containsTerrain(Terrains.INDUSTRIAL)
              || missedDamage <= 0 || check.getAsInt() < 7) {
            return;
        }
        int result = severity.getAsInt();
        if (result <= 1) {
            return;
        }
        int damage = result == 6 ? missedDamage : target.getUnits().size();
        distributeExplosion(manager, attacker, target, damage);
        if (range == ASRange.SHORT) {
            distributeExplosion(manager, target, attacker, damage);
        }
    }

    private static void distributeExplosion(SBFGameManager manager, SBFFormation source, SBFFormation target, int total) {
        int count = target.getUnits().size();
        for (int i = 0; i < count; i++) {
            int damage = total / count + (i < total % count ? 1 : 0);
            if (damage > 0) {
                SBFCombatDamage.recordHit(manager, source, target, i, damage, LossCause.TERRAIN);
            }
        }
    }

    private static boolean usesBridge(SBFFormation mover) {
        return !mover.isAerospace() && !mover.isAirVehicle() && mover.getMovementMode() != SBFMovementMode.NAVAL
              && mover.getMovementMode() != SBFMovementMode.SUBMARINE;
    }

    /** Bridges below their current CF limit collapse on entry; only one Unit falls (IO:BF p. 199). */
    private static void collapseOverloadedBridge(SBFGameManager manager, SBFFormation mover, BoardLocation site,
          SBFMovePath path, int step) {
        var game = manager.getGame();
        var board = game.getBoard(site.boardId());
        var hex = game.getHex(site);
        var structure = board.getBuildingsAt(site.coords()).stream().filter(b -> b.getBldgClass() == IBuilding.BRIDGE).findFirst();
        int cf = structure.map(b -> b.getCurrentCF(site.coords())).orElse(hex.terrainLevel(Terrains.BRIDGE_CF));
        int capacity = cf > 20 ? 4 : cf > 10 ? 3 : cf > 5 ? 2 : 1;
        if (mover.getSize() <= capacity || cf < 0) {
            return;
        }
        int elevation = Math.max(0, hex.terrainLevel(Terrains.BRIDGE_ELEV));
        structure.ifPresent(b -> board.collapseBuilding(b, site.coords()));
        hex.removeBridge();
        board.setHex(site.coords(), hex);
        manager.send(manager.getPacketHelper().createBoardsPacket());
        if (mover.getUnits().isEmpty()) {
            return;
        }
        var units = mover.getUnits();
        var lead = units.stream().filter(u -> u.hasSUA(BattleForceSUA.LEAD)).findFirst().orElse(null);
        var others = units.stream().filter(u -> u != lead).toList();
        var falling = (lead != null && (others.isEmpty() || Compute.d6() <= 2)) ? lead : others.get(Compute.randomInt(others.size()));
        int damage = SBFMovePath.canOccupyTerrain(game, mover, falling, site)
              ? elevation + (falling.isUnderwater(game, mover, site) ? hex.depth() : 0) : falling.getCurrentArmor();
        SBFTransport.applyThrough(manager, mover, path, step);
        var start = mover.getPosition();
        int entrance = mover.passageEntrance();
        mover.setPosition(site);
        var hit = new PendingHit(Player.PLAYER_NONE, mover.getId(), units.indexOf(falling), damage, 0,
              List.of(), LossCause.TERRAIN, Player.PLAYER_NONE);
        SBFCombatDamage.applyTerrainEffects(manager, mover, List.of(hit));
        if (game.getFormation(mover.getId()).isPresent()) {
            mover.setPosition(start);
            mover.setPassageEntrance(entrance);
        }
    }

    /** Keep transit exposure as well as the final position; pressure checks resolve before Combat (IO:BF p. 202). */
    private static void markDepth(SBFGameManager manager, SBFFormation mover, BoardLocation site) {
        var game = manager.getGame();
        var hex = game.getHex(site);
        if (hex != null && hex.terrainLevel(Terrains.WATER) >= 15) {
            mover.getUnits().stream().filter(u -> u.getMovementMode() != SBFMovementMode.SUBMARINE
                  && (u.getMovementMode() != SBFMovementMode.UNKNOWN || mover.getMovementMode() != SBFMovementMode.SUBMARINE)
                  && u.isUnderwater(game, mover, site)).forEach(SBFUnit::markExtremeDepth);
        }
    }

    static void extremeDepthAtMovementEnd(SBFGameManager manager, IntSupplier critical) {
        var game = manager.getGame();
        if (!game.usesAdvancedTerrain()) {
            return;
        }
        for (var mover : game.getActiveFormations()) {
            if (mover.occupiesGroundHex()) {
                markDepth(manager, mover, mover.getPosition());
            }
            List<PendingHit> effects = new ArrayList<>();
            for (int index = 0; index < mover.getUnits().size(); index++) {
                var unit = mover.getUnits().get(index);
                if (unit.consumeExtremeDepth()) {
                    boolean infantry = unit.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA);
                    int roll = infantry || unit.absorbCriticalChance() ? 0 : critical.getAsInt();
                    effects.add(new PendingHit(Player.PLAYER_NONE, mover.getId(), index, infantry ? 1 : 0, roll,
                          List.of(), LossCause.TERRAIN, Player.PLAYER_NONE));
                }
            }
            SBFCombatDamage.applyTerrainEffects(manager, mover, effects);
        }
    }

    private static boolean bogTerrain(Hex hex) {
        return hex.containsAnyTerrainOf(Terrains.MUD, Terrains.SAND, Terrains.SWAMP, Terrains.TUNDRA)
              || hex.terrainLevel(Terrains.SNOW) >= 2;
    }

    private static boolean subjectToBogging(SBFFormation mover, int step) {
        if (mover.isAerospace()) {
            return true; // Grounded Squadrons, including landed DropShips.
        }
        if (mover.isAirVehicleLanded() && step == 0) {
            return true;
        }
        if (mover.getUnits().isEmpty()) {
            return !ignoresBogging(mover.getMovementMode());
        }
        return mover.getUnits().stream().anyMatch(unit -> (unit.getArmor() == 0 || unit.getCurrentArmor() > 0)
              && (unit.getElements().isEmpty() ? !ignoresBogging(unit.getMovementMode())
              : unit.getElements().stream().anyMatch(e -> !ignoresBogging(e.getASUnitType() == null
              ? unit.getMovementMode() : SBFMovementMode.modeForElement(unit, e)))));
    }

    private static boolean ignoresBogging(SBFMovementMode mode) {
        return mode == SBFMovementMode.HOVER || mode == SBFMovementMode.WIGE || mode == SBFMovementMode.VTOL || mode == SBFMovementMode.AIRSHIP;
    }
}
