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

import static megamek.common.alphaStrike.BattleForceSUA.IT;
import static megamek.common.alphaStrike.BattleForceSUA.LG;
import static megamek.common.alphaStrike.BattleForceSUA.MT;
import static megamek.common.alphaStrike.BattleForceSUA.PT;
import static megamek.common.alphaStrike.BattleForceSUA.SLG;
import static megamek.common.alphaStrike.BattleForceSUA.VLG;
import static megamek.common.alphaStrike.BattleForceSUA.VTH;
import static megamek.common.alphaStrike.BattleForceSUA.VTM;
import static megamek.common.alphaStrike.BattleForceSUA.VTS;
import static megamek.common.strategicBattleSystems.SBFElementType.BA;
import static megamek.common.strategicBattleSystems.SBFElementType.CI;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;

/** Ground combat-ready bays and doors, IO:BF pp. 199, 268; vehicle size classes, p. 152. */
final class SBFBayTransport {
    private static final List<BattleForceSUA> BAYS = List.of(MT, PT, VTM, VTH, VTS);

    private SBFBayTransport() {
    }

    static boolean canBoard(SBFGame game, SBFFormation passenger, SBFMovePath route) {
        var carrier = game.getFormation(route.boardingCarrier()).orElse(null);
        int move = SBFMovePath.normalMovementPoints(passenger, game);
        if ((carrier == null) || (carrier == passenger) || !carrier.occupiesGroundHex()
              || (carrier.isAirVehicle() && !carrier.isAirVehicleLanded())
              || game.areHostile(carrier, game.getPlayer(passenger.getOwnerId()))
              || !passenger.occupiesGroundHex() || infantryOnly(passenger) || passenger.isAerospace()
              || passenger.dismountedIn(game.getCurrentRound()) || passenger.mustWithdraw()
              || !game.passengersOf(passenger).isEmpty() || !route.getTransportOrders().isEmpty()
              || !passenger.getEngagedOpponents().isEmpty() || !adjacent(route.getLastPosition(), carrier.getPosition())
              || (move <= 0) || (route.getMpUsed() + Math.min(4, move) > move)) {
            return false;
        }
        double doors = BAYS.stream().mapToDouble(bay -> rating(carrier, bay.getDoor())).sum();
        return (carrier.bayDoorsUsed(game.getCurrentRound()) + passenger.getUnits().size() <= doors)
              && fits(game, carrier, passenger);
    }

    static int board(SBFGameManager manager, SBFFormation passenger, SBFMovePath planned, SBFMovePath traveled) {
        if ((planned.boardingCarrier() == null) || !planned.getLastPosition().equals(traveled.getLastPosition())
              || !canBoard(manager.getGame(), passenger, planned)) {
            return 0;
        }
        var game = manager.getGame();
        var carrier = game.getFormation(planned.boardingCarrier()).orElseThrow();
        int cost = Math.min(4, SBFMovePath.normalMovementPoints(passenger, game));
        carrier.useBayDoors(game.getCurrentRound(), passenger.getUnits().size());
        game.scenarioProgress().interruptOccupation(passenger);
        passenger.embark(carrier.getId(), false);
        manager.sendUnitUpdate(carrier);
        return cost;
    }

    static boolean unload(SBFGameManager manager, int owner, SBFFormation passenger, SBFFormation carrier, BoardLocation hex) {
        var game = manager.getGame();
        if (!game.getPhase().isMovement() || (game.getTurn() == null) || (game.getTurn().playerId() != owner)
              || (game.engagementOrders().pendingMove() != null) || (passenger == null) || (carrier == null)
              || (passenger.getOwnerId() != owner) || !carrier.occupiesGroundHex() || !passenger.isCarriedBy(carrier.getId())
              || (carrier.isAirVehicle() && !carrier.isAirVehicleLanded())
              || passenger.packedCargoTons() > 0 || infantryOnly(passenger) || passenger.isDone() || !adjacent(carrier.getPosition(), hex)
              || !game.hasBoardLocation(hex) || !SBFMovePath.canOccupyTerrain(game, passenger, hex)
              || SBFMovePath.exceedsStackingLimit(game, passenger, hex)) {
            return false;
        }
        passenger.dismountFromBay(hex, game.getCurrentRound());
        manager.sendUnitUpdate(passenger);
        // This passenger can use the current turn. Keep the remaining turns of its owner's other formations.
        SBFTransport.restoreDismountTurns(manager, owner, passenger.getId());
        return true;
    }

    private static boolean adjacent(BoardLocation first, BoardLocation second) {
        return (first != null) && first.isSameBoardAs(second) && (first.coords().distance(second.coords()) == 1);
    }

    static boolean fits(SBFGame game, SBFFormation carrier, SBFFormation candidate) {
        Map<BattleForceSUA, Integer> demand = new EnumMap<>(BattleForceSUA.class);
        for (var passenger : Stream.concat(game.passengersOf(carrier).stream(), Stream.of(candidate)).toList()) {
            if (passenger.packedCargoTons() > 0) {
                continue;
            }
            if (infantryOnly(passenger)) {
                if (!passenger.isExternallyMounted()) {
                    demand.merge(IT, passenger.getCAR(), Integer::sum);
                }
                continue;
            }
            if (passenger.getUnits().isEmpty() || passenger.getUnits().stream().anyMatch(u -> u.getElements().isEmpty())) {
                return false;
            }
            for (var unit : passenger.getUnits()) {
                for (var element : unit.getElements()) {
                    BattleForceSUA bay = requiredBay(element);
                    if (bay == null) {
                        return false;
                    }
                    int slots = bay == IT ? element.getCAR() : 1;
                    if (slots <= 0) {
                        return false;
                    }
                    demand.merge(bay, slots, Integer::sum);
                }
            }
        }
        int medium = demand.getOrDefault(VTM, 0);
        int heavy = demand.getOrDefault(VTH, 0);
        int superHeavy = demand.getOrDefault(VTS, 0);
        return (demand.getOrDefault(IT, 0) <= carrier.getIT()) && (demand.getOrDefault(MT, 0) <= rating(carrier, MT)) && (demand.getOrDefault(PT, 0) <= rating(carrier, PT))
              && (superHeavy <= rating(carrier, VTS)) && (heavy + superHeavy <= rating(carrier, VTH) + rating(carrier, VTS))
              && (medium + heavy + superHeavy <= rating(carrier, VTM) + rating(carrier, VTH) + rating(carrier, VTS));
    }

    static boolean infantryOnly(SBFFormation passenger) {
        return passenger.isAnyTypeOf(BA, CI) && passenger.getUnits().stream().flatMap(u -> u.getElements().stream())
              .filter(e -> e.getASUnitType() != null).allMatch(e -> SBFElementType.getUnitType(e).isAnyOf(BA, CI));
    }

    private static BattleForceSUA requiredBay(AlphaStrikeElement element) {
        if (element.getASUnitType() == null) {
            return null;
        }
        return switch (SBFElementType.getUnitType(element)) {
            case BA, CI -> IT;
            case BM -> MT;
            case PM -> PT;
            case V -> element.hasAnySUAOf(VLG, SLG) || (element.getSize() > 4) ? null
                  : element.hasSUA(LG) ? VTS : element.getSize() > 2 ? VTH : VTM;
            default -> null;
        };
    }

    private static double rating(SBFFormation carrier, BattleForceSUA ability) {
        if (carrier.getSUA(ability) instanceof Number total) {
            return total.doubleValue();
        }
        // Old Formation summaries omitted doors. The component Units or Elements still retain their printed ratings.
        return carrier.getUnits().stream().mapToDouble(unit -> unit.getSUA(ability) instanceof Number n ? n.doubleValue()
              : unit.getElements().stream().mapToDouble(e -> e.getSUA(ability) instanceof Number v ? v.doubleValue() : 0).sum()).sum();
    }
}
