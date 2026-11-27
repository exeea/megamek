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

import java.math.BigDecimal;

import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.loaders.MekSummaryCache;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFUnit;

/** Civilian DropShip cargo uses actual tonnage and packs or unpacks one Unit per turn (IO:BF p. 199). */
final class SBFCargoTransport {
    private SBFCargoTransport() {
    }

    static boolean load(SBFGameManager manager, int player, SBFFormation passenger, int unitIndex, SBFFormation ship) {
        var game = manager.getGame();
        boolean setup = game.getPhase().isDeployment();
        if ((!setup && !game.getPhase().isMovement()) || passenger == null || ship == null || passenger == ship
              || game.getTurn() == null || !game.getTurn().isValid(player, passenger, game) || !isCargoShip(ship)
              || game.engagementOrders().pendingMove() != null || game.areHostile(ship, game.getPlayer(player)) || ship.isEmbarked()
              || passenger.isEmbarked() || passenger.mustWithdraw() || !game.passengersOf(passenger).isEmpty()
              || !passenger.getEngagedOpponents().isEmpty() || unitIndex < 0 || unitIndex >= passenger.getUnits().size()
              || !setup && (ship.handledCargo(game.getCurrentRound()) || !ship.occupiesGroundHex()
              || !passenger.occupiesGroundHex() || !alongside(ship.getPosition(), passenger.getPosition()))) {
            return false;
        }
        SBFUnit unit = passenger.getUnits().get(unitIndex);
        BigDecimal weight = packedWeight(unit);
        BigDecimal used = game.passengersOf(ship).stream().map(p -> BigDecimal.valueOf(p.packedCargoTons()))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weight.signum() <= 0 || used.add(weight).compareTo(capacity(ship)) > 0) {
            return false;
        }
        var cargo = SBFFormationCommand.separateUnit(game, passenger, unit);
        game.scenarioProgress().interruptOccupation(cargo);
        cargo.packAsCargo(ship.getId(), weight.doubleValue());
        cargo.setDeployed(true);
        cargo.setDone(true);
        if (!setup) {
            ship.recordCargoTransfer(game.getCurrentRound());
        }
        manager.sendUnitUpdate(ship);
        manager.sendUnitUpdate(cargo);
        if (cargo == passenger) {
            manager.endCurrentTurn(passenger);
        } else {
            manager.sendUnitUpdate(passenger);
        }
        return true;
    }

    static boolean unload(SBFGameManager manager, int player, SBFFormation passenger, SBFFormation ship, BoardLocation site) {
        var game = manager.getGame();
        if (!game.getPhase().isMovement() || game.getTurn() == null || game.getTurn().playerId() != player
              || game.engagementOrders().pendingMove() != null || passenger == null || ship == null
              || passenger.getOwnerId() != player || !ship.occupiesGroundHex() || !passenger.isCarriedBy(ship.getId())
              || passenger.packedCargoTons() <= 0 || ship.handledCargo(game.getCurrentRound()) || !alongside(ship.getPosition(), site)
              || !game.hasBoardLocation(site) || !SBFMovePath.canOccupyTerrain(game, passenger, site)
              || SBFMovePath.exceedsStackingLimit(game, passenger, site)) {
            return false;
        }
        passenger.dismount(site, game.getCurrentRound());
        passenger.setDone(true);
        ship.recordCargoTransfer(game.getCurrentRound());
        manager.sendUnitUpdate(ship);
        manager.sendUnitUpdate(passenger);
        return true;
    }

    private static boolean isCargoShip(SBFFormation ship) {
        var elements = ship.getUnits().stream().flatMap(u -> u.getElements().stream())
              .filter(e -> e.getASUnitType() != null).toList();
        return elements.isEmpty() ? ship.isType(SBFElementType.LA) && (ship.getMovementMode() == SBFMovementMode.AERODYNE
              || ship.getMovementMode() == SBFMovementMode.SPHEROID) : elements.stream().anyMatch(AlphaStrikeElement::isDropShip);
    }

    private static boolean alongside(BoardLocation ship, BoardLocation site) {
        return ship != null && site != null && ship.isSameBoardAs(site) && ship.coords().distance(site.coords()) <= 1;
    }

    private static BigDecimal capacity(SBFFormation ship) {
        return rating(ship, BattleForceSUA.CT).add(rating(ship, BattleForceSUA.CK).movePointLeft(3));
    }

    private static BigDecimal rating(SBFFormation ship, BattleForceSUA ability) {
        if (ship.getSUA(ability) instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return ship.getUnits().stream().map(u -> u.getSUA(ability) instanceof Number n ? BigDecimal.valueOf(n.doubleValue())
              : u.getElements().stream().filter(e -> e.getSUA(ability) instanceof Number)
              .map(e -> BigDecimal.valueOf(((Number) e.getSUA(ability)).doubleValue())).reduce(BigDecimal.ZERO, BigDecimal::add))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal packedWeight(SBFUnit unit) {
        BigDecimal tons = BigDecimal.ZERO;
        for (var element : unit.getElements()) {
            double mass = element.getTransportTonnage();
            if (mass <= 0 && element.getFullChassis() != null && !element.getFullChassis().isBlank()) {
                // Older AS rosters retain the source chassis and model, even when the weight was not serialized.
                var source = MekSummaryCache.getInstance().getMek((element.getFullChassis() + " " + element.getModel()).trim());
                if (source != null) {
                    mass = source.getTons();
                }
            }
            if (!(mass > 0) || !Double.isFinite(mass)) {
                return BigDecimal.ZERO;
            }
            tons = tons.add(BigDecimal.valueOf(mass));
        }
        return tons.multiply(BigDecimal.valueOf(1.1));
    }
}
