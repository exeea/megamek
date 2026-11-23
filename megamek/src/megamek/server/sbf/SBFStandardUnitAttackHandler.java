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

import static megamek.client.ui.clientGUI.tooltip.SBFInGameObjectTooltip.ownerColor;

import java.util.ArrayList;
import java.util.List;

import megamek.common.actions.EntityAction;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack.WeaponMode;
import megamek.common.compute.Compute;
import megamek.common.rolls.Roll;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationReportEntry;
import megamek.common.strategicBattleSystems.SBFPlayerNameReportEntry;
import megamek.common.strategicBattleSystems.SBFPublicReportEntry;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFRollReportEntry;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SBFUnitReportEntry;

public class SBFStandardUnitAttackHandler extends AbstractSBFActionHandler {

    private final List<SBFUnit> declaredRoster;

    public SBFStandardUnitAttackHandler(SBFStandardUnitAttack action, SBFGameManager gameManager) {
        super(action, gameManager);
        declaredRoster = gameManager.getGame().getFormation(action.getEntityId())
              .map(f -> List.copyOf(f.getUnits())).orElse(List.of());
    }

    /** An immediate artillery hit may remove or shift Flights before their declared attacks resolve. */
    private SBFStandardUnitAttack currentAttack(SBFStandardUnitAttack declared, SBFFormation attacker) {
        if (declaredRoster == null) {
            return declared;
        }
        int original = declared.getUnitNumber();
        if (original < 0 || original >= declaredRoster.size()) {
            return null;
        }
        int current = attacker.getUnits().indexOf(declaredRoster.get(original));
        if (current < 0) {
            return null;
        }
        return current == original ? declared : new SBFStandardUnitAttack(declared.getEntityId(), current, declared.getTargetId(),
              declared.getRange(), declared.getWeaponMode(), declared.getSpotterId());
    }

    @Override
    public boolean cares() {
        return game().getPhase().isFiring();
    }

    @Override
    public void handle() {
        var declared = (SBFStandardUnitAttack) getAction();
        var attacker = game().getFormation(declared.getEntityId()).orElse(null);
        var attack = attacker == null ? null : currentAttack(declared, attacker);
        if (attack != null && attack.isDataValid(game())) {
            //noinspection OptionalGetWithoutIsPresent
            SBFFormation target = game().getFormation(attack.getTargetId()).get();
            SBFUnit attackingUnit = attacker.getUnits().get(attack.getUnitNumber());

            List<EntityAction> declarations = new ArrayList<>();
            for (var order : game().getActionsVector()) {
                if (order instanceof SBFStandardUnitAttack other && other.getEntityId() == attacker.getId()) {
                    var surviving = currentAttack(other, attacker);
                    if (surviving != null) {
                        declarations.add(surviving);
                    }
                } else {
                    declarations.add(order);
                }
            }
            SBFToHitData toHit = SBFToHitData.compileToHit(game(), attack, declarations);
            SBFReportEntry report = new SBFReportEntry(2001).noNL();
            report.add(new SBFUnitReportEntry(attacker, attack.getUnitNumber(), ownerColor(attacker, game())).text());
            report.add(new SBFFormationReportEntry(target, game()).text());
            gameManager().addReportFor(report, attacker, target);

            target.recordAttack(attacker);
            if (toHit.cannotSucceed()) {
                gameManager().addReportFor(new SBFReportEntry(2010).add(toHit.getDesc()), attacker, target);
            } else {
                gameManager().addReportFor(new SBFReportEntry(2003).add(toHit.getValue()).noNL(), attacker, target);
                Roll roll = Compute.rollD6(2);
                report = new SBFReportEntry(2020).noNL();
                report.add(new SBFPlayerNameReportEntry(game().getPlayer(attacker.getOwnerId())).text());
                report.add(new SBFRollReportEntry(roll).noNL().text());
                gameManager().addReportFor(report, attacker, target);

                if (roll.getIntValue() < toHit.getValue()) {
                    gameManager().addReportFor(new SBFPublicReportEntry(2012), attacker, target);
                    if (attack.getWeaponMode() != WeaponMode.TAG && target.occupiesGroundHex()) {
                        var range = toHit.attackRange().orElseThrow();
                        SBFMovementHazards.industrialMiss(gameManager(), attacker, target, range,
                              attack.weaponDamage(game(), attacker, attackingUnit, range), () -> Compute.d6(2), Compute::d6);
                    }
                } else {
                    gameManager().addReportFor(new SBFPublicReportEntry(2013), attacker, target);
                    if (attack.getWeaponMode() == WeaponMode.TAG) {
                        game().artillery().paint(game().getCurrentRound(), attacker.getOwnerId(), target.getId());
                        setFinished();
                        return;
                    }
                    int damage = attack.weaponDamage(game(), attacker, attackingUnit, toHit.attackRange().orElseThrow());
                    if (game().combatOrders().isBehind(attacker, target)) {
                        damage++;
                    }
                    damage = attacker.adjustEngagementDamageAgainst(target, damage);
                    if (damage > 0) {
                        if (attack.getWeaponMode() == WeaponMode.INDIRECT) {
                            SBFCombatDamage.recordRandomHit(gameManager(), attacker, target, damage);
                        } else {
                            SBFCombatDamage.recordStandardHit(gameManager(), attacker, target, damage);
                        }
                    } else {
                        gameManager().addReportFor(new SBFPublicReportEntry(3068), attacker, target);
                    }
                }
            }
        }
        setFinished();
    }

}
