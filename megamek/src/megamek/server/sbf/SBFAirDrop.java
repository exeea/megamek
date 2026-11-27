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

import megamek.common.Player;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.FormationLosses;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;

/** Airborne troop transport and end-of-movement landing, IO:BF pp. 167, 209–210. */
record SBFAirDrop(SBFGameManager manager) {
    static boolean mayDrop(SBFFormation passenger) {
        if (passenger.packedCargoTons() > 0) {
            return false;
        }
        if (!passenger.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA)) {
            return true; // Non-infantry may use their dedicated bays (IO:BF p. 209).
        }
        return (passenger.getJumpMove() > 0) || passenger.hasSUA(BattleForceSUA.PAR)
              || (!passenger.getUnits().isEmpty() && passenger.getUnits().stream().allMatch(u -> (u.getJumpMove() > 0) || u.hasSUA(BattleForceSUA.PAR)
                    || (u.getMovementMode() == SBFMovementMode.BA_JUMP) || (u.getMovementMode() == SBFMovementMode.CI_JUMP)));
    }

    boolean embarkForSetup(int player, SBFFormation passenger, SBFFormation carrier) {
        var game = manager.getGame();
        if (!game.getPhase().isDeployment() || (passenger == null) || (carrier == null) || (carrier == passenger)
              || (game.getTurn() == null) || !game.getTurn().isValid(player, passenger, game)
              || (carrier.getOwnerId() != player) || carrier.isEmbarked() || passenger.isEmbarked()
              || passenger.isAerospace() || !game.passengersOf(passenger).isEmpty()) {
            return false;
        }
        boolean infantry = SBFBayTransport.infantryOnly(passenger);
        double used = game.passengersOf(carrier).stream().mapToDouble(SBFFormation::getCAR).sum();
        if (infantry ? ((passenger.getCAR() <= 0) || (used + passenger.getCAR() > carrier.getIT()))
              : !SBFBayTransport.fits(game, carrier, passenger)) {
            return false;
        }
        passenger.embark(carrier.getId(), false);
        passenger.setDeployed(true);
        manager.sendUnitUpdate(passenger);
        manager.endCurrentTurn(passenger);
        return true;
    }

    boolean fromRadar(int player, SBFFormation passenger, SBFFormation carrier, BoardLocation target, boolean high) {
        var game = manager.getGame();
        if (!game.getPhase().isMovement() || (passenger == null) || (carrier == null) || (carrier.getOwnerId() != player)
              || (passenger.getOwnerId() != player) || !passenger.isCarriedBy(carrier.getId()) || !mayDrop(passenger)
              || (game.getTurn() == null) || (game.engagementOrders().pendingMove() != null)) {
            return false;
        }
        if (high) {
            if (!game.getTurn().isValid(player, carrier, game)
                  || ((carrier.getRadarZone() != SBFRadarZone.CENTRAL) && (carrier.getRadarZone() != SBFRadarZone.INNER))) {
                return false;
            }
            passenger.beginHighDrop(game.getCurrentRound());
            new SBFAerospace(manager).beginDropContact(passenger);
        } else {
            if (!carrier.isOnRadarMap() || !game.getTurn().isValid(player, carrier, game) || !game.hasBoardLocation(target)
                  || !game.getBoard(target).isGround()) {
                return false;
            }
            // The flight path is validated as part of the carrier's following move. Contact may interrupt that route.
            passenger.planLowDrop(target);
        }
        manager.sendUnitUpdate(passenger);
        return true;
    }

    boolean aimHigh(int player, SBFFormation passenger, BoardLocation target) {
        var game = manager.getGame();
        if (!game.getPhase().isMovement() || (passenger == null) || !passenger.canLandHighDrop(game.getCurrentRound())
              || (game.getTurn() == null) || !game.getTurn().isValid(player, passenger, game)
              || !game.hasBoardLocation(target) || !game.getBoard(target).isGround()
              || !game.getActiveFormationsAt(target).isEmpty()) {
            return false;
        }
        passenger.aimDrop(target, true);
        manager.sendUnitUpdate(passenger);
        manager.endCurrentTurn(passenger);
        return true;
    }

    List<Integer> landAll() {
        return landAll(() -> Compute.d6(2), () -> Compute.d6() - 1);
    }

    List<Integer> landAll(IntSupplier roll, IntSupplier scatter) {
        SBFGame game = manager.getGame();
        List<Integer> remaining = new ArrayList<>();
        for (SBFFormation passenger : game.getActiveFormations()) {
            BoardLocation destination = passenger.getDropDestination();
            if (destination == null) {
                continue;
            }
            int dice = roll.getAsInt();
            if (dice == 2) {
                destination = BoardLocation.of(destination.coords().translated(scatter.getAsInt()), destination.boardId());
            }
            boolean prohibited = !game.hasBoardLocation(destination) || !SBFMovePath.canOccupyTerrain(game, passenger, destination);
            int damage = Math.max(0, 5 - dice) * (passenger.landingFromHighDrop() ? 3 : 2);
            for (int opponent : passenger.getEngagedOpponents()) {
                game.getFormation(opponent).ifPresent(f -> f.setEngagedWith(passenger.getId(), false));
                passenger.setEngagedWith(opponent, false);
            }
            passenger.finishDrop(destination, game.getCurrentRound());
            int dead = 0;
            boolean leader = false;
            boolean commander = false;
            boolean half = false;
            boolean wasCrippled = passenger.isCrippled();
            for (var unit : passenger.getUnits()) {
                int before = unit.getCurrentArmor();
                unit.setCurrentArmor(prohibited ? 0 : Math.max(0, before - damage));
                half |= before * 2 > unit.getArmor() && unit.getCurrentArmor() <= (unit.getArmor() + 1) / 2;
                if ((before > 0) && (unit.getCurrentArmor() == 0)) {
                    dead++;
                    leader |= unit.hasSUA(BattleForceSUA.LEAD);
                    commander |= unit.hasSUA(BattleForceSUA.COM);
                    game.scenarioProgress().recordLoss(game.getCurrentRound(), passenger, unit, Player.PLAYER_NONE, LossCause.TRANSPORT);
                }
            }
            boolean crippled = !wasCrippled && passenger.isCrippled();
            if (dead > 0) {
                SBFCombatDamage.removeDestroyedUnits(manager, passenger);
            }
            if ((dead > 0) || half || crippled) {
                game.getImmediateLosses().add(new FormationLosses(passenger, half, dead, leader, commander, crippled));
            }
            if (game.getFormation(passenger.getId()).isPresent()) {
                remaining.add(passenger.getOwnerId());
                manager.sendUnitUpdate(passenger);
            }
            manager.sendServerChat(passenger.getOwnerId(), "Drop " + passenger.getId() + " rolled " + dice
                  + " and landed at " + destination.coords().getBoardNum() + ".");
        }
        return remaining;
    }
}
