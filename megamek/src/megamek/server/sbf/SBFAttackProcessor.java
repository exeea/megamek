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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import megamek.common.Player;
import megamek.common.actions.EntityAction;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack.WeaponMode;
import megamek.common.strategicBattleSystems.SBFCombatOrders;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.logging.MMLogger;

record SBFAttackProcessor(SBFGameManager gameManager) implements SBFGameManagerHelper {
    private static final MMLogger logger = MMLogger.create(SBFAttackProcessor.class);

    /**
     * Executes a formation's submitted attacks. They are checked as a whole and rebuilt on the server with the range
     * that the formations' positions give; one invalid attack refuses the whole submission.
     *
     * @return true when the attacks were accepted, false when they were refused (nothing changed)
     */
    boolean processAttacks(List<EntityAction> submittedActions, SBFFormation formation) {
        Optional<List<SBFStandardUnitAttack>> attacks = validateAndRebuild(submittedActions, formation);
        if (attacks.isEmpty()) {
            return false;
        }

        attacks.get().forEach(game()::addAction);
        publishEstablishedSight(formation, attacks.get());
        formation.setDone(true);
        gameManager.sendUnitUpdate(formation);
        gameManager.sendPendingActions();
        gameManager.endCurrentTurn(formation);
        return true;
    }

    /** Supplementary chat declaration on the active player's turn; normal Done orders finish the Formation. */
    boolean declareWeapon(int playerId, SBFStandardUnitAttack action) {
        SBFFormation formation = game().getFormation(action.getEntityId()).orElse(null);
        if ((formation == null) || (game().getTurn() == null) || !game().getTurn().isValid(playerId, formation, game())) {
            return false;
        }
        var accepted = validateAndRebuild(List.of(action), formation);
        if (accepted.isEmpty()) {
            return false;
        }
        accepted.get().forEach(game()::addAction);
        publishEstablishedSight(formation, accepted.get());
        gameManager.sendPendingActions();
        gameManager.sendPendingPackets();
        return true;
    }

    private void publishEstablishedSight(SBFFormation formation, List<SBFStandardUnitAttack> attacks) {
        boolean sightChanged = false;
        for (SBFStandardUnitAttack shot : attacks) {
            if (shot.getWeaponMode() == WeaponMode.INDIRECT) {
                continue;
            }
            SBFFormation target = game().getFormation(shot.getTargetId()).orElseThrow();
            if (SBFCombatOrders.controlsSight(game(), formation, target)) {
                game().combatOrders().setSight(formation, target, true, true);
                sightChanged = true;
            }
            if (SBFCombatOrders.controlsSight(game(), target, formation)) {
                game().combatOrders().setSight(target, formation, true, true);
                sightChanged = true;
            }
        }
        if (sightChanged) {
            new SBFCombatControl(gameManager).publish();
        }
    }

    /**
     * Checks the attacks against IO:BF p.169 (one standard attack per Unit per turn, at most two enemy Formations as
     * targets) and p.170 (range). With reconnaissance, a located enemy has a counter in a known hex (p.196).
     */
    private Optional<List<SBFStandardUnitAttack>> validateAndRebuild(List<EntityAction> submittedActions,
          SBFFormation formation) {
        if (!game().getPhase().isFiring()) {
            logger.error("Server got attacks packet in wrong phase!");
            return Optional.empty();
        } else if (formation.hasSprintedThisTurn()) {
            logger.error("Sprinted formation cannot attack!");
            return Optional.empty();
        } else if (formation.isDone()) {
            logger.error("Formation already done!");
            return Optional.empty();
        }

        Player actingPlayer = game().getPlayer(formation.getOwnerId());
        if (actingPlayer == null) {
            logger.error("Attacking formation has no owner!");
            return Optional.empty();
        }

        Set<Integer> usedUnitNumbers = new HashSet<>();
        Set<Integer> torpedoUnits = new HashSet<>();
        List<EntityAction> declarations = new ArrayList<>(game().getActionsVector());
        declarations.addAll(submittedActions);
        for (EntityAction existing : game().getActionsVector()) {
            if ((existing instanceof SBFStandardUnitAttack attack) && (attack.getEntityId() == formation.getId())) {
                var used = attack.getWeaponMode() == WeaponMode.TORPEDO ? torpedoUnits : usedUnitNumbers;
                used.add(attack.getUnitNumber());
            }
        }

        Set<Integer> targetIds = new HashSet<>(SBFToHitData.targetsOfFormation(formation, game()));
        List<SBFStandardUnitAttack> attacks = new ArrayList<>(submittedActions.size());
        for (EntityAction submittedAction : submittedActions) {
            if (!(submittedAction instanceof SBFStandardUnitAttack attack)
                  || (attack.getEntityId() != formation.getId())
                  || !attack.isDataValid(game())
                  || !(attack.getWeaponMode() == WeaponMode.TORPEDO ? torpedoUnits : usedUnitNumbers)
                        .add(attack.getUnitNumber())) {
                logger.error("Invalid or duplicate SBF standard attack submission!");
                return Optional.empty();
            }

            SBFFormation target = game().getFormation(attack.getTargetId()).orElse(null);
            if ((target == null) || (game().getPlayer(target.getOwnerId()) == null)
                  || !game().areHostile(target, actingPlayer)
                  || !game().getVisibility(actingPlayer, target.getId()).isLocated()) {
                logger.error("Invalid, friendly, or hidden SBF attack target!");
                return Optional.empty();
            }

            if (target.isOnRadarMap() && !formation.isOnRadarMap() && game().usesDoubleBlind()
                  && (formation.getPosition() == null || target.getGroundFlightPath().stream()
                        .noneMatch(hex -> hex.isSameBoardAs(formation.getPosition())
                              && hex.coords().distance(formation.getPosition().coords()) <= SBFDetectionHelper.visualRange(game(), formation)))) {
                return Optional.empty();
            }
            if ((attack.getWeaponMode() == WeaponMode.TAG) && (!game().getOptions().booleanOption(
                  megamek.common.options.SBFRuleOptions.ADVANCED_ARTILLERY) || !formation.occupiesGroundHex()
                  || !target.occupiesGroundHex() || (formation.getPosition().coords().distance(target.getPosition().coords())
                  > SBFDetectionHelper.visualRange(game(), formation)))) {
                return Optional.empty();
            }
            SBFToHitData toHit = SBFToHitData.compileToHit(game(), attack, declarations);
            if (toHit.cannotSucceed()) {
                logger.error("SBF standard attack is impossible!");
                return Optional.empty();
            }

            attacks.add(new SBFStandardUnitAttack(formation.getId(), attack.getUnitNumber(), target.getId(),
                  toHit.attackRange().orElseThrow(), attack.getWeaponMode(), attack.getSpotterId()));
            targetIds.add(target.getId());
        }

        if (usedUnitNumbers.size() > game().combatOrders().standardAttackLimit(formation)) {
            logger.error("Outmaneuvered Formation declared too many attacks!");
            return Optional.empty();
        }
        if (targetIds.size() > 2) {
            logger.error("Formation targeting too many targets!");
            return Optional.empty();
        }
        return Optional.of(attacks);
    }
}
