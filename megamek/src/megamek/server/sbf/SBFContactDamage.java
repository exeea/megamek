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
import java.util.stream.IntStream;

import megamek.common.Player;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.ContactHit;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Exchange;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Order;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;

/** Immediate Overrun damage and the defender's free attack on successful Evade (IO:BF p. 168). */
record SBFContactDamage(SBFGameManager manager, IntSupplier dice) {

    static boolean canAmbush(SBFGame game, SBFFormation attacker, ASRange range) {
        return attacker.canAttackAfterEngagement() && attacker.getUnits().stream().anyMatch(unit -> unit.getCurrentArmor() > 0 && unit.canFire()
              && SBFStandardUnitAttack.standardWeaponDamage(game, attacker, unit, range) > 0);
    }

    static int formationDamage(SBFFormation formation, ASRange range) {
        if (formation.evasiveModifier() > 0) {
            return 0;
        }
        return formation.getUnits().stream().filter(unit -> unit.getCurrentArmor() > 0 && !unit.isConstructing())
              .mapToInt(unit -> unit.getCurrentDamage().getDamage(range).damage).sum();
    }

    /** Returns true after all required owner choices and both sides' simultaneous Overrun damage. */
    boolean resolve(SBFFormation mover, SBFFormation other, BoardLocation contact, SBFMovePath route, int step) {
        SBFGame game = manager.getGame();
        Exchange exchange = game.engagementOrders().exchange();
        if (exchange.order() == Order.AMBUSH && exchange.shooter() == -2) {
            remind(other.getOwnerId(), exchange);
            return false;
        }
        if ((exchange.order() == Order.EVADE) && exchange.succeeded() && (exchange.shooter() == -2)) {
            if (other.getUnits().stream().anyMatch(unit -> (unit.getCurrentArmor() > 0) && unit.canFire()) && other.canAttackAfterEngagement()
                  && !other.hasSprintedThisTurn()) {
                remind(other.getOwnerId(), exchange);
                return false;
            }
            exchange = exchange.withShooter(-1);
        }
        if (exchange.hits() == null) {
            List<ContactHit> hits = new ArrayList<>();
            if (exchange.order() == Order.AMBUSH && exchange.shooter() >= 0) {
                SBFHiddenCommand.reveal(manager, other, mover);
                other.markAmbush(game.getCurrentRound());
                SBFTransport.restoreMovementTurns(manager, other.getOwnerId(), mover.getId());
                ASRange range = exchange.shooter() == 0 ? ASRange.SHORT : ASRange.MEDIUM;
                for (SBFUnit unit : other.getUnits()) {
                    int damage = SBFStandardUnitAttack.standardWeaponDamage(game, other, unit, range);
                    if (unit.canFire() && unit.getCurrentArmor() > 0 && damage > 0) {
                        var tn = new SBFToHitData(other.getSkill() + unit.getTargetingCrits(), "ambush");
                        SBFToHitData.processTargetTerrain(tn, game, game.getHex(contact), mover);
                        SBFToHitData.processDefensiveMovement(tn, mover);
                        int roll = dice.getAsInt();
                        String report = other.generalName() + " ambushed at " + range + " and rolled " + roll + " against " + tn.getValue() + ".";
                        manager.sendServerChat(other.getOwnerId(), report);
                        manager.sendServerChat(mover.getOwnerId(), report);
                        if (roll >= tn.getValue()) {
                            addHit(hits, other, mover, damage);
                        }
                    }
                }
            } else if (exchange.order() == Order.OVERRUN) {
                addHit(hits, mover, other, formationDamage(mover, ASRange.SHORT) / 4);
                addHit(hits, other, mover, formationDamage(other, ASRange.MEDIUM) / 2);
            } else if (exchange.succeeded() && (exchange.shooter() >= 0)) {
                addHit(hits, other, mover, freeAttack(other, mover, exchange.shooter(), contact));
            }
            exchange = exchange.withHits(hits);
        }
        game.engagementOrders().setExchange(exchange);
        boolean waiting = false;
        for (ContactHit hit : exchange.hits()) {
            if (hit.targetUnit() < 0) {
                remind(hit.chooser(), exchange);
                waiting = true;
            }
        }
        if (waiting) {
            return false;
        }
        if (!exchange.hits().isEmpty()) {
            SBFTransport.applyThrough(manager, mover, route, step);
        }
        BoardLocation start = mover.getPosition();
        mover.setPosition(contact);
        SBFCombatDamage.applyImmediateHits(manager, mover, other, exchange.hits());
        SBFCombatDamage.applyImmediateHits(manager, other, mover, exchange.hits());
        if (game.getFormation(mover.getId()).isPresent()) {
            mover.setPosition(start);
        }
        if (!exchange.hits().isEmpty()) {
            SBFTransport.restoreDismountTurns(manager, mover.getOwnerId(), mover.getId());
            SBFTransport.restoreDismountTurns(manager, other.getOwnerId(), mover.getId());
        }
        game.engagementOrders().setExchange(null);
        return true;
    }

    private void addHit(List<ContactHit> hits, SBFFormation attacker, SBFFormation target, int damage) {
        List<Integer> candidates = IntStream.range(0, target.getUnits().size())
              .filter(index -> target.getUnits().get(index).getCurrentArmor() > 0).boxed().toList();
        if ((damage <= 0) || candidates.isEmpty()) {
            return;
        }
        int chosen = -1;
        int chooser = Player.PLAYER_NONE;
        if (candidates.size() == 1) {
            chosen = candidates.getFirst();
        } else if (manager.getGame().getOptions().booleanOption(SBFRuleOptions.BASE_SIMPLIFIED_DAMAGE)) {
            int[] counts = assignedCounts(hits, target);
            candidates = candidates.stream().filter(i -> SBFCombatDamage.canAllocate(counts, i)).toList();
            chosen = candidates.get(Compute.randomInt(candidates.size()));
        } else {
            chooser = hits.stream().filter(hit -> hit.target() == target.getId()).findFirst().map(ContactHit::chooser)
                  .orElseGet(() -> SBFCombatDamage.tacticsWinner(manager, attacker, target));
        }
        hits.add(new ContactHit(attacker.getId(), target.getId(), damage, chooser, chosen));
    }

    private static int[] assignedCounts(List<ContactHit> hits, SBFFormation target) {
        int[] counts = new int[target.getUnits().size()];
        hits.stream().filter(hit -> hit.target() == target.getId() && hit.targetUnit() >= 0)
              .forEach(hit -> counts[hit.targetUnit()]++);
        return counts;
    }

    private int freeAttack(SBFFormation attacker, SBFFormation target, int unitIndex, BoardLocation contact) {
        SBFUnit unit = attacker.getUnits().get(unitIndex);
        SBFStandardUnitAttack attack = new SBFStandardUnitAttack(attacker.getId(), unitIndex, target.getId(), ASRange.LONG);
        BoardLocation start = target.getPosition();
        target.setPosition(contact);
        try {
            SBFToHitData toHit = SBFToHitData.compileToHit(manager.getGame(), attack, List.of());
            int modifier = target.getEngagementDefenseModifier() == 0 ? 1 : 0;
            if (toHit.cannotSucceed()) {
                return 0;
            }
            int roll = dice.getAsInt();
            int tn = toHit.getValue() + modifier;
            String report = "Evade free attack rolled " + roll + " against " + tn + ".";
            manager.sendServerChat(attacker.getOwnerId(), report);
            manager.sendServerChat(target.getOwnerId(), report);
            if (roll < tn) {
                return 0;
            }
            int damage = attack.weaponDamage(manager.getGame(), attacker, unit, toHit.attackRange().orElse(ASRange.LONG));
            damage = attacker.adjustEngagementDamageAgainst(target, damage);
            return target.getEngagementDefenseModifier() == 0 ? (3 * damage) / 4 : damage;
        } finally {
            target.setPosition(start);
        }
    }

    void remind(int player, Exchange exchange) {
        SBFGame game = manager.getGame();
        SBFFormation other = game.getFormation(exchange.opponent()).orElse(null);
        SBFFormation mover = game.getFormation(exchange.mover()).orElse(null);
        if ((mover == null) || (other == null)) {
            return;
        }
        if (exchange.order() == Order.AMBUSH && exchange.shooter() == -2 && other.getOwnerId() == player) {
            manager.sendServerChat(player, "An enemy has entered your hidden Formation's hex. Choose /sbfhide ambush "
                  + other.getId() + " short|medium|hold.");
        }
        if ((exchange.shooter() == -2) && (player == other.getOwnerId()) && (exchange.order() == Order.EVADE)
              && exchange.succeeded()) {
            manager.sendServerChat(player, "Choose your free attack Unit with /sbfengage " + other.getId()
                  + " fire " + mover.getId() + " <Unit number>, starting at 1. Use 0 to decline.");
        }
        if (exchange.hits() != null) {
            for (ContactHit hit : exchange.hits()) {
                if ((hit.chooser() == player) && (hit.targetUnit() < 0)) {
                    SBFFormation own = mover.getOwnerId() == player ? mover : other;
                    manager.sendServerChat(player, "Allocate " + hit.damage() + " immediate damage using /sbfengage "
                          + own.getId() + " hit " + hit.target() + " <target Unit number>, starting at 1.");
                }
            }
        }
    }

    boolean select(int player, int own, int target, boolean shoot, int unitNumber) {
        SBFGame game = manager.getGame();
        Exchange exchange = game.engagementOrders().exchange();
        if ((exchange == null) || ((own != exchange.mover()) && (own != exchange.opponent()))
              || game.getFormation(own).filter(f -> f.getOwnerId() == player).isEmpty()) {
            return false;
        }
        int index = unitNumber - 1;
        if (shoot) {
            if ((exchange.order() != Order.EVADE) || !exchange.succeeded() || (exchange.shooter() != -2)
                  || (own != exchange.opponent()) || (target != exchange.mover())) {
                return false;
            }
            List<SBFUnit> units = game.getFormation(own).orElseThrow().getUnits();
            if ((unitNumber < 0) || (unitNumber > units.size()) || ((unitNumber > 0)
                  && ((units.get(index).getCurrentArmor() <= 0) || !units.get(index).canFire()))) {
                return false;
            }
            game.engagementOrders().setExchange(exchange.withShooter(index));
            return true;
        }
        SBFFormation victim = game.getFormation(target).orElse(null);
        if ((exchange.hits() == null) || (victim == null) || (unitNumber < 1) || (unitNumber > victim.getUnits().size())
              || (victim.getUnits().get(index).getCurrentArmor() <= 0)
              || !SBFCombatDamage.canAllocate(assignedCounts(exchange.hits(), victim), index)) {
            return false;
        }
        List<ContactHit> hits = new ArrayList<>(exchange.hits());
        for (int i = 0; i < hits.size(); i++) {
            ContactHit hit = hits.get(i);
            if ((hit.chooser() == player) && (hit.target() == target) && (hit.targetUnit() < 0)) {
                hits.set(i, new ContactHit(hit.attacker(), target, hit.damage(), player, index));
                game.engagementOrders().setExchange(exchange.withHits(hits));
                return true;
            }
        }
        return false;
    }
}
