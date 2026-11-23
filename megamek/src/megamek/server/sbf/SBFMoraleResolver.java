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

import static megamek.common.alphaStrike.BattleForceSUA.COM;
import static megamek.common.alphaStrike.BattleForceSUA.DRO;
import static megamek.common.alphaStrike.BattleForceSUA.LG;
import static megamek.common.alphaStrike.BattleForceSUA.RBT;
import static megamek.common.alphaStrike.BattleForceSUA.SCOM;
import static megamek.common.alphaStrike.BattleForceSUA.SDCS;
import static megamek.common.alphaStrike.BattleForceSUA.SLG;
import static megamek.common.alphaStrike.BattleForceSUA.VLG;
import static megamek.common.strategicBattleSystems.SBFElementType.BA;
import static megamek.common.strategicBattleSystems.SBFElementType.BM;
import static megamek.common.strategicBattleSystems.SBFElementType.CI;
import static megamek.common.strategicBattleSystems.SBFElementType.LA;
import static megamek.common.strategicBattleSystems.SBFElementType.MS;
import static megamek.common.strategicBattleSystems.SBFElementType.PM;
import static megamek.common.strategicBattleSystems.SBFElementType.V;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.compute.Compute;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormation.MoraleStatus;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.FormationLosses;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.units.Terrains;

/** Resolves the ground End Phase morale check and recovery tables, IO:BF pp. 172-174. */
record SBFMoraleResolver(SBFGameManager manager, IntSupplier roll2d6) {
    static void resolveEndPhase(SBFGameManager manager, List<FormationLosses> losses) {
        new SBFMoraleResolver(manager, () -> Compute.d6(2)).resolve(losses);
    }

    void resolve(List<FormationLosses> losses) {
        SBFGame game = manager.getGame();
        Map<Integer, FormationLosses> byFormation = new HashMap<>();
        Map<Integer, SBFFormation> commanderLosses = new HashMap<>();
        for (FormationLosses loss : losses) {
            byFormation.put(loss.formation().getId(), loss);
            if (loss.commanderLost()) {
                commanderLosses.put(sideOf(loss.formation()), loss.formation());
            }
        }
        List<SBFFormation> formations = game.getActiveFormations().stream().filter(SBFFormation::isDeployed)
              .filter(f -> f.isOnRadarMap() || (f.getPosition() != null))
              .sorted(Comparator.comparingInt(SBFFormation::getId)).toList();
        Map<Integer, Boolean> enraged = new HashMap<>();
        for (int owner : commanderLosses.keySet().stream().sorted().toList()) {
            enraged.put(owner, reactToCommanderLoss(commanderLosses.get(owner)));
        }
        // All rally target numbers use the same board state rather than earlier formations' rally results.
        List<SBFFormation> rally = new ArrayList<>();
        Map<SBFFormation, Integer> rallyTargets = new HashMap<>();
        for (SBFFormation formation : formations) {
            FormationLosses loss = byFormation.get(formation.getId());
            boolean newlyCrippled = (loss != null) && loss.newlyCrippled();
            if (game.getOptions().booleanOption(SBFRuleOptions.BASE_FORCED_WITHDRAWAL)
                  && (newlyCrippled || formation.isCrippled())) {
                formation.startForcedWithdrawal();
            }
            int quiet = formation.updateQuietTurns();
            if (!isExempt(formation)) {
                boolean commanderLost = enraged.containsKey(sideOf(formation));
                boolean newlyEnraged = enraged.getOrDefault(sideOf(formation), false);
                boolean forced = commanderLost || formation.mustCheckArtilleryMorale(game.getCurrentRound()) || ((loss != null)
                      && (newlyCrippled || loss.armorAtHalf() || (loss.destroyedUnits() > 0) || loss.leaderLost()));
                if (forced && !newlyEnraged && !formation.isRouted()) {
                    check(formation, checkTarget(formation, loss, commanderLost), false);
                    if (commanderLost && (loss != null) && loss.leaderLost() && !formation.isRouted()) {
                        check(formation, checkTarget(formation, loss, true), false);
                    }
                } else if (!forced && formation.isMoraleRestricted()) {
                    rally.add(formation);
                    rallyTargets.put(formation, quiet);
                }
            }
        }
        // A routed Unit below the armor limit is lost even if its Formation rallies this turn.
        Map<Integer, SBFFormation> routedCommanderLosses = new HashMap<>();
        for (SBFFormation formation : formations) {
            if (resolveWithdrawal(formation)) {
                routedCommanderLosses.put(sideOf(formation), formation);
            }
        }
        for (FormationLosses cargo : game.getImmediateLosses()) {
            if (cargo.commanderLost()) {
                routedCommanderLosses.put(sideOf(cargo.formation()), cargo.formation());
            }
        }
        game.getImmediateLosses().clear();
        for (int owner : routedCommanderLosses.keySet().stream().sorted().toList()) {
            if (!commanderLosses.containsKey(owner) && !reactToCommanderLoss(routedCommanderLosses.get(owner))) {
                for (SBFFormation formation : formations) {
                    if ((sideOf(formation) == owner) && game.getFormation(formation.getId()).isPresent()
                          && !isExempt(formation) && !formation.isRouted()) {
                        check(formation, checkTarget(formation, byFormation.get(formation.getId()), true), false);
                        resolveWithdrawal(formation);
                    }
                }
            }
        }
        rally.removeIf(f -> game.getFormation(f.getId()).isEmpty() || routedCommanderLosses.containsKey(sideOf(f))
              || !canRally(f));
        for (SBFFormation formation : rally) {
            int quiet = rallyTargets.get(formation);
            rallyTargets.put(formation, checkTarget(formation, byFormation.get(formation.getId()), false)
                  + recoveryModifier(formation, quiet));
        }
        for (SBFFormation formation : rally) {
            check(formation, rallyTargets.get(formation), true);
        }
        formations.forEach(SBFFormation::clearMoraleEvents);
    }

    private int sideOf(SBFFormation formation) {
        SBFGame game = manager.getGame();
        return SBFScenarioProgress.sideId(game, game.getPlayer(formation.getOwnerId()));
    }

    private boolean reactToCommanderLoss(SBFFormation commandFormation) {
        int owner = commandFormation.getOwnerId();
        SBFGame game = manager.getGame();
        int roll = roll2d6.getAsInt();
        boolean enraged = roll >= 11;
        manager.addReportFor(new SBFReportEntry(enraged ? 3410 : 3411).add(game.getPlayer(owner).getName()).add(roll),
              commandFormation);
        if (enraged) {
            game.getActiveFormations().stream().filter(f -> sideOf(f) == sideOf(commandFormation))
                  .forEach(f -> f.becomeEnraged(game.getCurrentRound()));
        }
        return enraged;
    }

    private boolean resolveWithdrawal(SBFFormation formation) {
        SBFGame game = manager.getGame();
        boolean lostCommander = false;
        boolean scatter = !formation.isOnRadarMap() && formation.mustWithdraw() && (formation.getMovement() == 0)
              && !SBFWithdrawal.atHomeEdge(game, formation);
        if (formation.isRouted() || scatter) {
            List<SBFUnit> casualties = formation.getUnits().stream()
                  .filter(u -> scatter || (u.getCurrentArmor() <= u.getArmor() / 2)).toList();
            for (SBFUnit casualty : casualties) {
                lostCommander |= casualty.hasSUA(COM);
                game.scenarioProgress().recordLoss(game.getCurrentRound(), formation, casualty, Player.PLAYER_NONE,
                      scatter ? LossCause.SCATTER : LossCause.ROUT);
                casualty.setCurrentArmor(0);
                manager.addReportFor(new SBFReportEntry(scatter ? 3406 : 3403)
                      .add(formation.generalName()).add(casualty.getName()), formation);
            }
            if (!casualties.isEmpty()) {
                SBFCombatDamage.removeDestroyedUnits(manager, formation);
            }
        }
        if (!formation.isAerospace() && formation.mustWithdraw() && game.getFormation(formation.getId()).isPresent()) {
            if (SBFWithdrawal.homeEdge(game, formation).isEmpty()) {
                manager.addReportFor(new SBFReportEntry(3405).add(formation.generalName()), formation);
            } else {
                SBFWithdrawal.withdrawIfAtHomeEdge(manager, formation);
            }
        }
        return lostCommander;
    }

    static boolean isExempt(SBFFormation formation) {
        return formation.isAnyTypeOf(MS, LA) || (formation.getStartingMovement() == 0)
              || formation.hasAnySUAOf(LG, VLG, SLG, DRO, RBT, SDCS)
              || (!formation.getUnits().isEmpty() && formation.getUnits().stream()
                    .allMatch(u -> u.isAnyTypeOf(MS, LA) || u.hasAnySUAOf(LG, VLG, SLG, DRO, RBT, SDCS)));
    }

    private int checkTarget(SBFFormation formation, FormationLosses loss, boolean commanderLost) {
        SBFGame game = manager.getGame();
        int target = formation.getMorale() + (seesEnemy(formation) ? 1 : -1);
        target += formation.isShaken() ? 1 : (formation.isBroken() ? 3 : 0);
        target += formation.isEnraged() ? -2 : 0;
        target += formation.getMovement() == 0 ? 2 : 0;
        target += commanderLost ? 2 : 0;
        if (game.scenarioProgress().losses().stream().anyMatch(l -> l.round() == game.getCurrentRound()
              && !l.withdrawn() && l.unit().hasSUA(SCOM)
              && !game.getPlayer(formation.getOwnerId()).isEnemyOf(game.getPlayer(l.ownerId())))) {
            target += 2; // IO:BF p. 173, Sub-Commander lost this turn.
        }
        target += formation.recentCruiseMissileAttack(game.getCurrentRound()) ? 1 : 0;
        if (loss != null) {
            target += loss.destroyedUnits() > 0 ? 3 : 0;
            target += loss.leaderLost() ? 1 : 0;
        }
        target += (int) formation.getUnits().stream()
              .filter(u -> u.getCurrentArmor() <= (u.getArmor() + 1) / 2).count();
        if (primarily(formation, u -> u.isAnyTypeOf(BM, PM))) {
            target -= 2;
        } else if (primarily(formation, u -> u.getType() == BA)) {
            target--;
        } else if (primarily(formation, u -> u.getType() == CI)) {
            target++;
        } else if (primarily(formation, u -> !u.getElements().isEmpty()
              && u.getElements().stream().allMatch(AlphaStrikeElement::isSupportVehicle))) {
            target += 2;
        }
        if (primarily(formation, u -> u.isAnyTypeOf(BA, CI))) {
            target += formation.wasAttackedByMek() ? 1 : 0;
            Hex hex = game.getBoard(formation.getPosition().boardId()).getHex(formation.getPosition().coords());
            if ((hex != null) && hex.containsTerrain(Terrains.BUILDING)) {
                target -= hex.terrainLevel(Terrains.BUILDING) >= 4 ? 3 : 2;
            }
        }
        return target;
    }

    private boolean seesEnemy(SBFFormation formation) {
        SBFGame game = manager.getGame();
        if (formation.isOnRadarMap()) {
            return game.getActiveFormations().stream().anyMatch(f -> (f.getRadarZone() == formation.getRadarZone())
                  && game.areHostile(f, game.getPlayer(formation.getOwnerId())));
        }
        var enemies = SBFWithdrawal.enemiesOnBoard(game, formation);
        return SBFWithdrawal.exposure(game, formation.getPosition(), enemies) > 0;
    }

    private boolean canRally(SBFFormation formation) {
        if (formation.isRouted()) {
            return manager.getGame().getActiveFormations().stream()
                  .anyMatch(f -> (sideOf(f) == sideOf(formation)) && near(formation, f)
                        && f.getUnits().stream().anyMatch(u -> u.hasSUA(COM)));
        }
        return formation.isBroken() || (formation.isShaken() && !seesEnemy(formation));
    }

    private int recoveryModifier(SBFFormation formation, int quietTurns) {
        SBFGame game = manager.getGame();
        int modifier = (formation.isBroken() ? -3 : 0) - quietTurns;
        modifier += seesEnemy(formation) ? 0 : -1;
        modifier += formation.getMovement() == 0 ? 2 : 0;
        List<SBFFormation> friendly = game.getActiveFormations().stream().filter(SBFFormation::isDeployed)
              .filter(f -> formation.isOnRadarMap() ? f.getRadarZone() == formation.getRadarZone()
                    : (!f.isOnRadarMap() && game.onSameBoard(formation, f)))
              .filter(f -> !game.areHostile(f, game.getPlayer(formation.getOwnerId()))).toList();
        if (friendly.stream().anyMatch(f -> (sideOf(f) == sideOf(formation))
              && f.getUnits().stream().anyMatch(u -> u.hasSUA(COM))
              && (formation.isOnRadarMap() || (SBFWithdrawal.visualExposure(game, formation, f.getPosition()) > 0)))) {
            modifier -= 2;
        }
        List<SBFFormation> nearby = friendly.stream().filter(f -> (f != formation) && near(formation, f)).toList();
        List<SBFFormation> steady = nearby.stream().filter(f -> !f.isBroken() && !f.isRouted()).toList();
        if (steady.stream().anyMatch(f -> primarily(f, u -> u.getType() == BM))) {
            modifier -= 2;
        }
        if (steady.stream().anyMatch(f -> primarily(f, u -> u.getType() == V))) {
            modifier--;
        }
        if (primarily(formation, u -> u.isAnyTypeOf(CI, BA))
              && nearby.stream().anyMatch(f -> f.isBroken() || f.isRouted())) {
            modifier++;
        }
        return modifier;
    }

    private static boolean near(SBFFormation a, SBFFormation b) {
        if (a.isOnRadarMap() || b.isOnRadarMap()) {
            return b.isDeployed() && (a.getRadarZone() == b.getRadarZone());
        }
        return b.isDeployed() && (a.getPosition() != null) && (b.getPosition() != null)
              && a.getPosition().isSameBoardAs(b.getPosition())
              && (a.getPosition().coords().distance(b.getPosition().coords()) <= 2);
    }

    private static boolean primarily(SBFFormation formation, Predicate<SBFUnit> type) {
        int count = formation.getUnits().size();
        return (count > 0) && (3 * formation.getUnits().stream().filter(type).count() >= 2 * count);
    }

    private void check(SBFFormation formation, int target, boolean rally) {
        int roll = roll2d6.getAsInt();
        MoraleStatus current = formation.moraleStatus();
        if (rally && (roll >= target)) {
            formation.setMoraleStatus(switch (current) {
                case ROUTED -> MoraleStatus.BROKEN;
                case BROKEN -> MoraleStatus.SHAKEN;
                default -> MoraleStatus.NORMAL;
            });
        } else if (!rally && (roll < target)) {
            formation.setMoraleStatus(switch (current) {
                case NORMAL -> MoraleStatus.SHAKEN;
                case SHAKEN, UNSTEADY -> MoraleStatus.BROKEN;
                case BROKEN, ROUTED -> MoraleStatus.ROUTED;
            });
        }
        manager.addReportFor(new SBFReportEntry(rally ? 3401 : 3400).add(formation.generalName())
              .add(target).add(roll).add(formation.moraleStatus().name()), formation);
    }
}
