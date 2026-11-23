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
import java.util.List;
import java.util.stream.IntStream;

import megamek.common.compute.Compute;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.BaseFormationConverter;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.Allocation;
import megamek.common.strategicBattleSystems.SBFGame.PendingHit;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFUnit;

/** Records ground combat damage during firing and applies it in the End Phase (IO:BF pp. 170-172). */
final class SBFCombatDamage {
    private SBFCombatDamage() { }

    record FormationLosses(SBFFormation formation, boolean armorAtHalf, int destroyedUnits, boolean leaderLost,
          boolean commanderLost, boolean newlyCrippled) { }

    static void recordStandardHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int damage) {
        SBFGame game = manager.getGame();
        if ((target.getUnits().size() == 1) || game.getOptions().booleanOption(SBFRuleOptions.BASE_SIMPLIFIED_DAMAGE)) {
            List<Integer> eligible = eligibleUnits(game, attacker, target);
            int index = eligible.size() == 1 ? eligible.getFirst() : eligible.get(Compute.randomInt(eligible.size()));
            if (game.getDamageAllocations().isEmpty()) {
                recordHit(manager, attacker, target, index, damage);
            } else {
                game.getDamageAllocations().add(new Allocation(attacker.getId(), target.getId(), -1, damage, index));
            }
            return;
        }

        // One opposed check per attacker/target exchange, even when their hits are interleaved (IO:BF p. 170).
        int chooser = game.getDamageAllocations().stream().filter(hit -> hit.isFor(attacker.getId(), target.getId()))
              .findFirst().map(Allocation::chooserId).orElseGet(() -> tacticsWinner(manager, attacker, target));
        game.getDamageAllocations().add(new Allocation(attacker.getId(), target.getId(), chooser, damage, -1));
        manager.addReportFor(new SBFReportEntry(3121).add(damage), attacker, target);
    }

    private static int tacticsWinner(SBFGameManager manager, SBFFormation attacker, SBFFormation target) {
        int attackerDie = Compute.d6();
        int defenderDie = Compute.d6();
        int attackerTotal = attackerDie + Math.max(0, target.getTactics() - attacker.getTactics());
        int defenderTotal = defenderDie + Math.max(0, attacker.getTactics() - target.getTactics());
        if (target.getUnits().stream().anyMatch(unit -> unit.hasSUA(LEAD) || unit.hasSUA(COM))) {
            defenderTotal += 2;
        }
        int winner = attackerTotal > defenderTotal ? attacker.getOwnerId() : target.getOwnerId();
        manager.addReportFor(new SBFReportEntry(3120).add(attackerDie).add(attackerTotal).add(defenderDie)
              .add(defenderTotal).add(manager.getGame().getPlayer(winner).getName()), attacker, target);
        manager.sendServerChat(winner, "You won a Tactics check. During the firing report, use /sbfallocate "
              + "to see the damage and assign each hit to a Unit.");
        return winner;
    }

    static boolean hasPendingChoices(SBFGame game) {
        return game.getDamageAllocations().stream().anyMatch(hit -> hit.unitIndex() < 0);
    }

    /** Accept all Unit choices for one exchange atomically (one zero-based Unit index per hit, in firing order). */
    static boolean allocate(SBFGameManager manager, int playerId, int attackerId, int targetId, List<Integer> indices) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isFiringReport()) {
            return false;
        }
        List<Allocation> queue = game.getDamageAllocations();
        List<Allocation> exchange = queue.stream().filter(hit -> hit.isFor(attackerId, targetId)).toList();
        SBFFormation target = game.getFormation(targetId).orElse(null);
        if ((target == null) || exchange.isEmpty() || (exchange.size() != indices.size())
              || exchange.stream().anyMatch(hit -> (hit.chooserId() != playerId) || (hit.unitIndex() >= 0))) {
            return false;
        }
        int[] counts = new int[target.getUnits().size()];
        for (int index : indices) {
            if ((index < 0) || (index >= counts.length) || !canAllocate(counts, index)) {
                return false;
            }
            counts[index]++;
        }

        int choice = 0;
        for (int i = 0; i < queue.size(); i++) {
            Allocation hit = queue.get(i);
            if (hit.isFor(attackerId, targetId)) {
                queue.set(i, new Allocation(attackerId, targetId, playerId, hit.damage(), indices.get(choice++)));
            }
        }
        if (!hasPendingChoices(game)) {
            // Different winners may answer in either order. Armor and critical checks always follow firing order.
            for (Allocation hit : queue) {
                recordHit(manager, game.getFormation(hit.attackerId()).orElseThrow(),
                      game.getFormation(hit.targetId()).orElseThrow(), hit.unitIndex(), hit.damage());
            }
            queue.clear();
        }
        return true;
    }

    private static boolean canAllocate(int[] counts, int index) {
        return (counts[index] < 2) || Arrays.stream(counts).allMatch(count -> count >= 2);
    }

    /** A Unit may absorb two hits in an exchange before every other Unit has absorbed two (IO:BF p. 170). */
    static List<Integer> eligibleUnits(SBFGame game, SBFFormation attacker, SBFFormation target) {
        int[] counts = new int[target.getUnits().size()];
        for (PendingHit hit : game.getPendingHits()) {
            if ((hit.attackerId() == attacker.getId()) && (hit.targetId() == target.getId())) {
                counts[hit.unitIndex()]++;
            }
        }
        for (Allocation hit : game.getDamageAllocations()) {
            if (hit.isFor(attacker.getId(), target.getId()) && (hit.unitIndex() >= 0)) {
                counts[hit.unitIndex()]++;
            }
        }
        return IntStream.range(0, counts.length).filter(index -> canAllocate(counts, index)).boxed().toList();
    }

    /** IO:BF pp. 170-171: record each hit individually. The table is rolled again on each further armor loss. */
    static void recordHit(SBFGameManager manager, SBFFormation attacker, SBFFormation target, int index, int damage) {
        SBFUnit unit = target.getUnits().get(index);
        List<PendingHit> hits = manager.getGame().getPendingHits();
        int previousDamage = hits.stream().filter(hit -> (hit.targetId() == target.getId())
              && (hit.unitIndex() == index)).mapToInt(PendingHit::damage).sum();
        int armorBefore = Math.max(0, unit.getCurrentArmor() - previousDamage);
        int armorAfter = Math.max(0, armorBefore - damage);
        int criticalRoll = 0;
        manager.addReportFor(new SBFReportEntry(3100).add(unit.getName()).add(damage).add(armorAfter), target);
        if ((armorAfter < armorBefore) && (armorAfter * 2 < unit.getArmor())) {
            criticalRoll = Compute.d6(2);
            manager.addReportFor(new SBFReportEntry(3110).add(criticalRoll)
                  .add(new SBFReportEntry(CriticalEffect.forRoll(criticalRoll).reportId).noNL().text()), target);
        }
        hits.add(new PendingHit(attacker.getId(), target.getId(), index, damage, criticalRoll));
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
        return losses;
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
            switch (CriticalEffect.forRoll(hit.criticalRoll())) {
                case TARGETING -> {
                    unit.addTargetingCrit();
                    leaderLost |= unit.hasSUA(LEAD) && (unit.getTargetingCrits() == 3);
                }
                case WEAPON -> unit.addDamageCrit();
                case MOVEMENT -> {
                    unit.addMovementCrit();
                    movementDamaged = true;
                }
                case MISSION_KILL -> unit.setCurrentArmor(0);
                case NONE -> { }
            }
            armorAtHalf |= (before > half) && (unit.getCurrentArmor() <= half);
            if ((before > 0) && (unit.getCurrentArmor() == 0)) {
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
