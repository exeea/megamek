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
import static megamek.common.alphaStrike.BattleForceSUA.LEAD;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import megamek.common.Player;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.BaseFormationConverter;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.ContactHit;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.Allocation;
import megamek.common.strategicBattleSystems.SBFGame.FormationLosses;
import megamek.common.strategicBattleSystems.SBFGame.PendingHit;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFUnit;

/** Records ground combat damage during firing and applies it in the End Phase (IO:BF pp. 170-172). */
final class SBFCombatDamage {
    private SBFCombatDamage() { }

    static void recordStandardHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int damage) {
        recordStandardHit(manager, attacker, target, damage, LossCause.COMBAT);
    }

    static void recordStandardHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int damage,
          LossCause cause) {
        SBFGame game = manager.getGame();
        if ((target.getUnits().size() == 1) || game.getOptions().booleanOption(SBFRuleOptions.BASE_SIMPLIFIED_DAMAGE)) {
            recordRandomHit(manager, attacker, target, damage, cause);
            return;
        }

        // One opposed check per attacker/target exchange, even when their hits are interleaved (IO:BF p. 170).
        int chooser = game.getDamageAllocations().stream().filter(hit -> (hit.chooserId() >= 0) && hit.isFor(attacker.getId(), target.getId()))
              .findFirst().map(Allocation::chooserId).orElseGet(() -> tacticsWinner(manager, attacker, target));
        game.getDamageAllocations().add(new Allocation(attacker.getId(), target.getId(), chooser, damage, -1, List.of(), cause, 0));
        manager.addReportFor(new SBFReportEntry(3121).add(damage), attacker, target);
    }

    /** Advanced artillery uses a separate 1d6 allocation decision for each hit, IO:BF p. 204. */
    static void recordArtilleryHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int damage) {
        if (target.getUnits().size() == 1 || manager.getGame().getOptions().booleanOption(SBFRuleOptions.BASE_SIMPLIFIED_DAMAGE)) {
            recordRandomHit(manager, attacker, target, damage, LossCause.ARTILLERY_OR_BOMB);
            return;
        }
        int chooser = Compute.d6() <= 3 ? target.getOwnerId() : attacker.getOwnerId();
        List<Allocation> queue = manager.getGame().getDamageAllocations();
        int exchange = 1 + queue.stream().mapToInt(Allocation::artilleryExchange).max().orElse(0);
        queue.add(new Allocation(attacker.getId(), target.getId(), chooser, damage, -1, List.of(),
              LossCause.ARTILLERY_OR_BOMB, exchange));
        manager.sendServerChat(chooser, "You choose the Unit hit by artillery. Use /sbfallocate during the firing report.");
    }

    /** Indirect fire bypasses Tactics and uses the random Unit allocation (IO:BF pp. 170-171). */
    static void recordRandomHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int damage) {
        recordRandomHit(manager, attacker, target, damage, LossCause.COMBAT);
    }

    static void recordRandomHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int damage,
          LossCause cause) {
        SBFGame game = manager.getGame();
        if (game.getDamageAllocations().isEmpty()) {
            List<Integer> eligible = eligibleUnits(game, attacker, target);
            int index = eligible.size() == 1 ? eligible.getFirst() : eligible.get(Compute.randomInt(eligible.size()));
            recordHit(manager, attacker, target, index, damage, cause);
            return;
        }
        // Earlier player choices can change which Units are eligible. Save a random order now so retries and
        // differently timed replies select the same first eligible Unit, without rerolling the hit.
        List<Integer> order = new ArrayList<>(IntStream.range(0, target.getUnits().size()).boxed().toList());
        for (int last = order.size() - 1; last > 0; last--) {
            Collections.swap(order, last, Compute.randomInt(last + 1));
        }
        game.getDamageAllocations().add(new Allocation(attacker.getId(), target.getId(), -1, damage, -1, order, cause, 0));
    }

    static int tacticsWinner(SBFGameManager manager, SBFFormation attacker, SBFFormation target) {
        return tacticsWinner(manager, attacker, target, manager.getGame().getPhase().isFiring());
    }

    static int tacticsWinner(SBFGameManager manager, SBFFormation attacker, SBFFormation target, boolean reportPhaseChoice) {
        int attackerDie = Compute.d6();
        int defenderDie = Compute.d6();
        int attackerTotal = attackerDie + Math.max(0, target.getTactics() - attacker.getTactics());
        int defenderTotal = defenderDie + Math.max(0, attacker.getTactics() - target.getTactics());
        if (target.getUnits().stream().anyMatch(unit -> unit.hasSUA(LEAD) || unit.hasSUA(COM))) {
            defenderTotal += 2;
        }
        int winner = attackerTotal > defenderTotal ? attacker.getOwnerId() : target.getOwnerId();
        manager.addReportFor(new SBFReportEntry(manager.getGame().getPhase().isMovement() ? 3122 : 3120).add(attackerDie).add(attackerTotal).add(defenderDie)
              .add(defenderTotal).add(manager.getGame().getPlayer(winner).getName()), attacker, target);
        if (reportPhaseChoice) {
            manager.sendServerChat(winner, "You won a Tactics check. During the firing report, use /sbfallocate "
                  + "to see the damage and assign each hit to a Unit.");
        }
        return winner;
    }

    static boolean hasPendingChoices(SBFGame game) {
        return game.getDamageAllocations().stream().anyMatch(hit -> (hit.chooserId() >= 0) && (hit.unitIndex() < 0));
    }

    /** Accept all Unit choices for one exchange atomically (one zero-based Unit index per hit, in firing order). */
    static boolean allocate(SBFGameManager manager, int playerId, int attackerId, int targetId, List<Integer> indices) {
        return allocate(manager, playerId, attackerId, targetId, 0, indices);
    }

    static boolean allocate(SBFGameManager manager, int playerId, int attackerId, int targetId, int artilleryExchange,
          List<Integer> indices) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isFiringReport()) {
            return false;
        }
        List<Allocation> queue = game.getDamageAllocations();
        List<Allocation> exchange = queue.stream().filter(hit -> (hit.chooserId() >= 0) && hit.isFor(attackerId, targetId, artilleryExchange)).toList();
        SBFFormation target = game.getFormation(targetId).orElse(null);
        if ((target == null) || exchange.isEmpty() || (exchange.size() != indices.size())
              || exchange.stream().anyMatch(hit -> (hit.chooserId() != playerId) || (hit.unitIndex() >= 0))) {
            return false;
        }
        int[] counts = artilleryExchange == 0 ? recordedCounts(game, attackerId, target) : new int[target.getUnits().size()];
        List<Allocation> updated = new ArrayList<>(queue);
        int choice = 0;
        for (int i = 0; i < queue.size(); i++) {
            Allocation hit = queue.get(i);
            if (!hit.isFor(attackerId, targetId, artilleryExchange)) {
                continue;
            }
            int index = hit.chooserId() >= 0 ? indices.get(choice++) : randomTarget(hit, counts);
            if ((index < 0) || (index >= counts.length) || !canAllocate(counts, index)) {
                return false;
            }
            counts[index]++;
            updated.set(i, hit.assignedTo(index));
        }
        queue.clear();
        queue.addAll(updated);
        if (!hasPendingChoices(game)) {
            // Different winners may answer in either order. Armor and critical checks always follow firing order.
            for (Allocation hit : queue) {
                SBFFormation recipient = game.getFormation(hit.targetId()).orElseThrow();
                int index = hit.unitIndex() >= 0 ? hit.unitIndex() : randomTarget(hit,
                      recordedCounts(game, hit.attackerId(), recipient));
                SBFFormation attacker = SBFScenarioResolution.roster(game).stream().filter(f -> f.getId() == hit.attackerId())
                      .findFirst().orElseThrow();
                recordHit(manager, attacker, recipient, index, hit.damage(), hit.cause());
            }
            queue.clear();
        }
        return true;
    }

    static boolean canAllocate(int[] counts, int index) {
        return (counts[index] < 2) || Arrays.stream(counts).allMatch(count -> count >= 2);
    }

    /** A Unit may absorb two hits in an exchange before every other Unit has absorbed two (IO:BF p. 170). */
    static List<Integer> eligibleUnits(SBFGame game, SBFFormation attacker, SBFFormation target) {
        int[] counts = recordedCounts(game, attacker.getId(), target);
        return IntStream.range(0, counts.length).filter(index -> canAllocate(counts, index)).boxed().toList();
    }

    private static int[] recordedCounts(SBFGame game, int attackerId, SBFFormation target) {
        int[] counts = new int[target.getUnits().size()];
        for (PendingHit hit : game.getPendingHits()) {
            if ((hit.attackerId() == attackerId) && (hit.targetId() == target.getId())) {
                counts[hit.unitIndex()]++;
            }
        }
        return counts;
    }

    private static int randomTarget(Allocation hit, int[] counts) {
        if (hit.unitIndex() >= 0) {
            return hit.unitIndex();
        }
        return hit.randomOrder().stream().filter(index -> canAllocate(counts, index)).findFirst().orElseThrow();
    }

    /** IO:BF pp. 170-171: record each hit individually. The table is rolled again on each further armor loss. */
    static void recordHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int index, int damage) {
        recordHit(manager, attacker, target, index, damage, LossCause.COMBAT);
    }

    static void recordHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int index,
          int damage, LossCause cause) {
        List<PendingHit> hits = manager.getGame().getPendingHits();
        int previousDamage = hits.stream().filter(hit -> (hit.targetId() == target.getId())
              && (hit.unitIndex() == index)).mapToInt(PendingHit::damage).sum();
        hits.add(rollHit(manager, attacker, target, index, damage, previousDamage, cause));
    }

    /** Resolve every Unit's hit before removing casualties or resuming the mover (IO:BF pp. 168, 214). */
    static void applyImmediateHits(SBFGameManager manager, SBFFormation attacker, SBFFormation target,
          List<ContactHit> exchange) {
        applyImmediateHits(manager, attacker, target, exchange, LossCause.COMBAT);
    }

    static void applyImmediateHits(SBFGameManager manager, SBFFormation attacker, SBFFormation target,
          List<ContactHit> exchange, LossCause cause) {
        List<PendingHit> hits = new ArrayList<>();
        int[] applied = new int[target.getUnits().size()];
        for (var shot : exchange) {
            if (shot.target() == target.getId()) {
                int index = shot.targetUnit();
                var hit = rollHit(manager, attacker, target, index, shot.damage(), applied[index], cause);
                applied[index] += hit.damage();
                hits.add(hit);
            }
        }
        if (!hits.isEmpty()) {
            target.recordAttack(attacker);
            manager.getGame().getImmediateLosses().add(applyHits(manager, target, hits));
            manager.entityAllUpdate();
        }
    }

    private static PendingHit rollHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int index,
          int damage, int previousDamage, LossCause cause) {
        SBFUnit unit = target.getUnits().get(index);
        int armorBefore = Math.max(0, unit.getCurrentArmor() - previousDamage);
        if (target.isHullDown()) {
            damage /= 2; // IO:BF, Hull Down, p. 199.
        }
        int applied = unit.hasSUA(BattleForceSUA.SHLD) ? Math.max(0, damage - 1) : damage;
        int armorAfter = Math.max(0, armorBefore - applied);
        manager.addReportFor(new SBFReportEntry(3100).add(unit.getName()).add(applied).add(armorAfter), target);
        List<Integer> rolls = new ArrayList<>();
        if (armorAfter < armorBefore) {
            int chances = armorAfter * 2 < unit.getArmor() ? 1 : 0;
            if (unit.hasSUA(BattleForceSUA.BAR)) {
                chances = (armorAfter * 2 <= unit.getArmor()) ? 2 : 1;
            }
            if (unit.isUnderwater(manager.getGame(), target) && !unit.hasSUA(BattleForceSUA.BHJ)) {
                chances *= 2;
            }
            for (int i = 0; i < chances; i++) {
                if (unit.absorbCriticalChance()) {
                    manager.addReportFor(new SBFReportEntry(3116).add(unit.getName()), target);
                    continue;
                }
                int roll = Compute.d6(2);
                rolls.add(roll);
                manager.addReportFor(new SBFReportEntry(3110).add(roll)
                      .add(new SBFReportEntry(CriticalEffect.forRoll(roll).reportId).noNL().text()), target);
            }
        }
        int firstRoll = rolls.isEmpty() ? 0 : rolls.removeFirst();
        return new PendingHit(attacker.getId(), target.getId(), index, applied, firstRoll, rolls, cause);
    }

    /** IO:BF p. 171 Critical Hit Table. A roll of zero means that the attack did not generate a critical check. */
    private enum CriticalEffect {
        NONE(3111), TARGETING(3112), WEAPON(3113), MOVEMENT(3114), MISSION_KILL(3115);

        final int reportId;

        CriticalEffect(int reportId) {
            this.reportId = reportId;
        }

        static CriticalEffect forRoll(int roll) {
            return switch (roll) {
                case 5, 6, 7 -> TARGETING;
                case 8, 9 -> WEAPON;
                case 10, 11 -> MOVEMENT;
                case 12 -> MISSION_KILL;
                default -> NONE;
            };
        }
    }

    static List<FormationLosses> applyEndPhase(SBFGameManager manager) {
        SBFGame game = manager.getGame();
        List<PendingHit> hits = game.getPendingHits();
        List<FormationLosses> losses = new ArrayList<>();
        for (int targetId : hits.stream().map(PendingHit::targetId).distinct().toList()) {
            game.getFormation(targetId).ifPresent(target -> losses.add(applyHits(manager, target,
                  hits.stream().filter(hit -> hit.targetId() == targetId).toList())));
        }
        hits.clear();
        losses.addAll(game.getImmediateLosses()); // Includes emergency dismounts during End Phase damage.
        game.getImmediateLosses().clear();
        Map<Integer, FormationLosses> byFormation = new LinkedHashMap<>();
        losses.forEach(loss -> byFormation.merge(loss.formation().getId(), loss, FormationLosses::combine));
        for (SBFFormation formation : game.getActiveFormations()) {
            for (SBFUnit unit : formation.getUnits()) {
                int restored = unit.recoverHarJelArmor(game.getCurrentRound());
                if (restored > 0) {
                    manager.addReportFor(new SBFReportEntry(3117).add(unit.getName()).add(restored), formation);
                }
            }
        }
        return List.copyOf(byFormation.values());
    }

    private static FormationLosses applyHits(SBFGameManager manager, SBFFormation formation, List<PendingHit> hits) {
        boolean wasCrippled = formation.isCrippled();
        boolean armorAtHalf = false;
        boolean leaderLost = false;
        boolean commanderLost = false;
        boolean movementDamaged = false;
        int destroyed = 0;
        for (PendingHit hit : hits) {
            SBFUnit unit = formation.getUnits().get(hit.unitIndex());
            int before = unit.getCurrentArmor();
            int half = (unit.getArmor() + 1) / 2;
            unit.setCurrentArmor(Math.max(0, before - hit.damage()));
            for (int roll : hit.criticalRolls()) {
                switch (CriticalEffect.forRoll(roll)) {
                    case TARGETING -> {
                        unit.addTargetingCrit();
                        leaderLost |= unit.hasSUA(LEAD) && (unit.getTargetingCrits() == 3);
                    }
                    case WEAPON -> unit.addDamageCrit();
                    case MOVEMENT -> {
                        unit.addMovementCrit();
                        if ((unit.getMovementMode() == SBFMovementMode.WIGE) && (unit.getCurrentMovement() == 0)) {
                            unit.setCurrentArmor(0); // A WiGE at zero MP is removed from play (IO:BF p. 166).
                        }
                        movementDamaged = true;
                    }
                    case MISSION_KILL -> unit.setCurrentArmor(0);
                    case NONE -> { }
                }
            }
            armorAtHalf |= (before > half) && (unit.getCurrentArmor() <= half);
            boolean crossedHalf = before * 2 >= unit.getArmor() && unit.getCurrentArmor() * 2 < unit.getArmor();
            boolean killed = before > 0 && unit.getCurrentArmor() == 0;
            if (crossedHalf || killed) {
                int attackerOwner = SBFScenarioResolution.roster(manager.getGame()).stream()
                      .filter(f -> f.getId() == hit.attackerId()).mapToInt(SBFFormation::getOwnerId)
                      .findFirst().orElse(Player.PLAYER_NONE);
                if (crossedHalf) {
                    manager.getGame().scenarioProgress().crossedHalfArmor(manager.getGame().getCurrentRound(), unit,
                          formation.getOwnerId(), attackerOwner);
                }
                if (!killed) {
                    continue;
                }
                LossCause cause = hit.cause() == LossCause.COMBAT && hit.criticalRolls().contains(12)
                      ? LossCause.MISSION_KILL : hit.cause();
                manager.getGame().scenarioProgress().recordLoss(manager.getGame().getCurrentRound(), formation, unit, attackerOwner, cause);
                destroyed++;
                leaderLost |= unit.hasSUA(LEAD);
                commanderLost |= unit.hasSUA(COM);
                manager.addReportFor(new SBFReportEntry(3092).add(formation.generalName()).add(unit.getName()),
                      formation);
            }
        }
        if (movementDamaged || (destroyed > 0)) {
            updateMovement(formation);
        }
        boolean newlyCrippled = !wasCrippled && formation.isCrippled();
        if (destroyed > 0) {
            removeCasualties(manager, formation);
        }
        return new FormationLosses(formation, armorAtHalf, destroyed, leaderLost, commanderLost, newlyCrippled);
    }

    /** Used for casualties outside normal Combat Phase attacks, such as routed Units. */
    static void removeDestroyedUnits(SBFGameManager manager, SBFFormation formation) {
        updateMovement(formation);
        removeCasualties(manager, formation);
    }

    /** IO:BF p. 171: only MP and movement mode are recalculated after a Unit is lost. */
    private static void updateMovement(SBFFormation formation) {
        List<SBFUnit> survivors = formation.getUnits().stream().filter(unit -> unit.getCurrentArmor() > 0).toList();
        if (!survivors.isEmpty()) {
            formation.setCurrentMovement(BaseFormationConverter.formationMovement(formation, survivors));
            formation.setMovementMode(BaseFormationConverter.formationMovementMode(survivors));
        }
    }

    private static void removeCasualties(SBFGameManager manager, SBFFormation formation) {
        SBFGame game = manager.getGame();
        SBFTransport.unloadAfterCasualty(manager, formation);
        List<SBFUnit> destroyed = formation.getUnits().stream().filter(unit -> unit.getCurrentArmor() == 0).toList();
        if (destroyed.size() == formation.getUnits().size()) {
            // Keep the wiped Formation's Units on its graveyard record for end-of-game accounting.
            game.removeFormationFromPlay(formation);
        } else {
            destroyed.forEach(formation::removeUnit);
        }
    }
}
