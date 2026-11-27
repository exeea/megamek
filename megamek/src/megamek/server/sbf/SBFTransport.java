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
import static megamek.common.alphaStrike.BattleForceSUA.MEC;
import static megamek.common.alphaStrike.BattleForceSUA.SBF_OMNI;
import static megamek.common.alphaStrike.BattleForceSUA.XMEC;
import static megamek.common.strategicBattleSystems.SBFElementType.BA;
import static megamek.common.strategicBattleSystems.SBFElementType.BM;
import static megamek.common.strategicBattleSystems.SBFElementType.CI;
import static megamek.common.strategicBattleSystems.SBFElementType.V;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.FormationLosses;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovePath.TransportOrder;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFTurn;

/** Ground infantry transport, IO:BF pp. 167, 267-268. Transport orders belong to the carrier's movement. */
final class SBFTransport {
    private SBFTransport() {
    }

    record Operation(int step, SBFFormation passenger, boolean mount, boolean external, BoardLocation location) { }

    /** Validate orders against earlier loads and drops without changing the live formations. */
    static Optional<List<Operation>> plan(SBFGame game, SBFFormation carrier, SBFMovePath route) {
        return plan(game, carrier, route, false);
    }

    static Optional<List<Operation>> plan(SBFGame game, SBFFormation carrier, SBFMovePath route,
          boolean validatedBeforeDamage) {
        List<TransportOrder> orders = route.getTransportOrders();
        if (orders.isEmpty()) {
            return Optional.of(List.of());
        }
        if (carrier.isOnRadarMap() || carrier.isEmbarked()
              || (carrier.isAerospace() && !route.getSteps().isEmpty())
              || (!validatedBeforeDamage && (orders.size() > SBFMovePath.maximumMovementPoints(carrier, game)))) {
            return Optional.empty();
        }
        List<Operation> result = new ArrayList<>();
        Map<SBFFormation, Boolean> aboard = new HashMap<>();
        game.passengersOf(carrier).forEach(f -> aboard.put(f, f.isExternallyMounted()));
        boolean hadXmec = carriesXmec(aboard);
        Map<SBFFormation, BoardLocation> dropped = new HashMap<>();
        int previousStep = 0;
        for (int i = validatedBeforeDamage ? route.processedTransportOrders() : 0; i < orders.size(); i++) {
            TransportOrder order = orders.get(i);
            if (validatedBeforeDamage && (order != null) && (order.afterSteps() > route.getSteps().size())) {
                continue;
            }
            if ((order == null) || (order.afterSteps() < previousStep)
                  || (order.afterSteps() > route.getSteps().size())) {
                return Optional.empty();
            }
            previousStep = order.afterSteps();
            BoardLocation location = previousStep == 0 ? carrier.getPosition()
                  : route.getSteps().get(previousStep - 1).getDestination();
            SBFFormation passenger = game.getFormation(order.passengerId()).orElse(null);
            if (validatedBeforeDamage && !order.mount() && ((passenger == null) || !aboard.containsKey(passenger))) {
                continue; // Emergency dismount or casualty before this previously approved drop.
            }
            if ((passenger == null) || passenger.packedCargoTons() > 0 || (passenger == carrier) || !passenger.isDeployed()
                  || !passenger.isAnyTypeOf(CI, BA) || (passenger.getOwnerId() != carrier.getOwnerId())) {
                return Optional.empty();
            }
            boolean external;
            if (order.mount()) {
                if ((previousStep != 0) || (carrier.isAirVehicle() && !carrier.isAirVehicleLanded()) || aboard.containsKey(passenger) || dropped.containsKey(passenger)
                      || passenger.isEmbarked() || !location.equals(passenger.getPosition())
                      || !passenger.getEngagedOpponents().isEmpty() || passenger.mustWithdraw()) {
                    return Optional.empty();
                }
                double used = aboard.entrySet().stream().filter(e -> !e.getValue() && e.getKey().packedCargoTons() == 0)
                      .mapToDouble(e -> e.getKey().getCAR()).sum();
                external = (passenger.getCAR() == 0) || (passenger.getCAR() + used > carrier.getIT());
                if (external && !canMountExternally(carrier, passenger, aboard)) {
                    return Optional.empty();
                }
                aboard.put(passenger, external);
            } else {
                Boolean mounted = aboard.remove(passenger);
                boolean airDrop = carrier.isAirVehicle() && (!carrier.isAirVehicleLanded() || (previousStep > 0));
                if ((mounted == null) || (airDrop ? !SBFAirDrop.mayDrop(passenger) : !SBFMovePath.canOccupyTerrain(game, passenger, location))) {
                    return Optional.empty();
                }
                external = mounted;
                List<SBFFormation> others = friendlyOccupants(game, carrier, location, aboard, dropped);
                others.remove(passenger);
                others.add(carrier);
                if (!airDrop && SBFMovePath.exceedsStackingLimit(passenger, others)) {
                    return Optional.empty();
                }
                if (!airDrop) {
                    dropped.put(passenger, location);
                }
            }
            result.add(new Operation(previousStep, passenger, order.mount(), external, location));
        }
        int movement = SBFMovePath.normalMovementPoints(carrier, game);
        if (!hadXmec && carriesXmec(aboard)) {
            movement = Math.max(0, movement - 1);
        }
        int budget = game.usesSprintingMove() ? movement + (movement + 1) / 2 : movement;
        if ((!validatedBeforeDamage && (route.getMpUsed() + orders.size() > budget))
              || SBFMovePath.exceedsStackingLimit(carrier, friendlyOccupants(game, carrier, route.getLastPosition(),
                    aboard, dropped))) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private static List<SBFFormation> friendlyOccupants(SBFGame game, SBFFormation carrier, BoardLocation location,
          Map<SBFFormation, Boolean> aboard, Map<SBFFormation, BoardLocation> dropped) {
        Player owner = game.getPlayer(carrier.getOwnerId());
        List<SBFFormation> result = new ArrayList<>(game.getActiveFormationsAt(location).stream()
              .filter(f -> (f != carrier) && !aboard.containsKey(f) && !dropped.containsKey(f))
              .filter(f -> !game.areHostile(f, owner))
              .toList());
        dropped.forEach((f, hex) -> {
            if (hex.equals(location)) {
                result.add(f);
            }
        });
        return result;
    }

    private static boolean carriesXmec(Map<SBFFormation, Boolean> aboard) {
        return aboard.entrySet().stream().anyMatch(e -> e.getValue() && e.getKey().hasSUA(XMEC));
    }

    private static boolean canMountExternally(SBFFormation carrier, SBFFormation rider,
          Map<SBFFormation, Boolean> aboard) {
        if (!rider.isType(BA) || !rider.hasAnySUAOf(MEC, XMEC) || !carrier.isAnyTypeOf(BM, V)) {
            return false;
        }
        int squads = elementCount(rider);
        if (squads == 0) {
            return false; // CAR measures transport space, not the number of externally mounted squads.
        }
        int omni = carrier.hasSUA(SBF_OMNI) ? ((Number) carrier.getSUA(SBF_OMNI)).intValue() : 0;
        int external = aboard.entrySet().stream().filter(Map.Entry::getValue)
              .mapToInt(e -> elementCount(e.getKey())).sum();
        int omniRequired = aboard.entrySet().stream().filter(e -> e.getValue() && !e.getKey().hasSUA(XMEC))
              .mapToInt(e -> elementCount(e.getKey())).sum();
        return (rider.hasSUA(XMEC) || (squads + omniRequired <= omni))
              && (squads + external <= Math.max(omni, elementCount(carrier)));
    }

    private static int elementCount(SBFFormation formation) {
        if (formation.getUnits().stream().anyMatch(u -> u.getElements().isEmpty())) {
            return 0;
        }
        return formation.getUnits().stream().mapToInt(u -> u.getElements().size()).sum();
    }

    /** Resolve the route prefix actually traveled before engagement, retaining orders on a suspended move. */
    static int applyThrough(SBFGameManager manager, SBFFormation carrier, SBFMovePath route, int traveledSteps) {
        if (route.processedTransportOrders() >= route.getTransportOrders().size()) {
            return route.spentTransportMP();
        }
        List<Operation> remaining = plan(manager.getGame(), carrier, route, true).orElse(List.of());
        int spent = apply(manager, carrier, remaining, traveledSteps);
        int processed = (int) route.getTransportOrders().stream().filter(o -> o.afterSteps() <= traveledSteps).count();
        route.recordTransportProgress(processed, spent);
        return route.spentTransportMP();
    }

    private static int apply(SBFGameManager manager, SBFFormation carrier, List<Operation> orders, int traveledSteps) {
        int spent = 0;
        for (Operation op : orders) {
            if (op.step() > traveledSteps) {
                break;
            }
            if (op.mount()) {
                manager.addReportFor(new SBFReportEntry(3420).add(carrier.generalName())
                      .add(op.passenger().generalName()), carrier, op.passenger());
                manager.getGame().scenarioProgress().interruptOccupation(op.passenger());
                op.passenger().embark(carrier.getId(), op.external());
            } else {
                if (carrier.isAirVehicle() && (!carrier.isAirVehicleLanded() || (op.step() > 0))) {
                    op.passenger().aimDrop(op.location(), false);
                } else {
                    op.passenger().dismount(op.location(), manager.getGame().getCurrentRound());
                }
                manager.addReportFor(new SBFReportEntry(3421).add(carrier.generalName())
                      .add(op.passenger().generalName()), carrier, op.passenger());
            }
            manager.sendUnitUpdate(op.passenger());
            spent++;
        }
        return spent;
    }

    /** Previously embarked riders may need additional movement turns on dismount. */
    static void restoreDismountTurns(SBFGameManager manager, int ownerId) {
        restoreDismountTurns(manager, ownerId, Player.PLAYER_NONE);
    }

    /** During an immediate exchange the mover has already consumed the current turn. */
    static void restoreDismountTurns(SBFGameManager manager, int ownerId, int moverId) {
        SBFGame game = manager.getGame();
        if (game.getActiveFormations().stream().noneMatch(f -> f.getOwnerId() == ownerId
              && f.dismountedIn(game.getCurrentRound()))) {
            return;
        }
        restoreMovementTurns(manager, ownerId, moverId);
    }

    /** Restore turns for newly eligible formations after movement has already begun. */
    static void restoreMovementTurns(SBFGameManager manager, int ownerId, int moverId) {
        SBFGame game = manager.getGame();
        long eligible = game.getActiveFormations().stream().filter(f -> f.getId() != moverId)
              .filter(f -> SBFFormationTurn.isEligible(f, game.getPhase(), game.getCurrentRound()) && f.getOwnerId() == ownerId)
              .map(SBFFormation::movementGroup).distinct().count();
        List<SBFTurn> turns = new ArrayList<>(game.getTurnsList());
        long remaining = turns.stream().skip(game.getTurnIndex() + 1L).filter(t -> t.playerId() == ownerId).count();
        if (eligible > remaining) {
            for (long i = remaining; i < eligible; i++) {
                turns.add(new SBFFormationTurn(ownerId));
            }
            game.setTurns(turns);
            manager.sendCurrentTurns();
        }
    }

    static void unloadAfterCasualty(SBFGameManager manager, SBFFormation carrier) {
        SBFGame game = manager.getGame();
        if (carrier.getPosition() == null) {
            return;
        }
        for (SBFFormation passenger : game.passengersOf(carrier).stream()
              .sorted(Comparator.comparingInt(SBFFormation::getId)).toList()) {
            BoardLocation landing = nearestDismount(game, carrier, passenger);
            if ((landing == null) || !SBFMovePath.canOccupyTerrain(game, passenger, landing)) {
                manager.addReportFor(new SBFReportEntry(3422).add(passenger.generalName()), passenger);
                boolean leader = passenger.getUnits().stream().anyMatch(u -> u.hasSUA(LEAD));
                boolean commander = passenger.getUnits().stream().anyMatch(u -> u.hasSUA(COM));
                game.getImmediateLosses().add(new FormationLosses(passenger, true, passenger.getUnits().size(),
                      leader, commander, true));
                passenger.getUnits().forEach(u -> {
                    game.scenarioProgress().recordLoss(game.getCurrentRound(), passenger, u, Player.PLAYER_NONE, LossCause.TRANSPORT);
                    u.setCurrentArmor(0);
                });
                game.removeFormationFromPlay(passenger);
            } else {
                passenger.dismount(landing, game.getCurrentRound());
                manager.addReportFor(new SBFReportEntry(3423).add(passenger.generalName()), passenger);
            }
        }
    }

    private static BoardLocation nearestDismount(SBFGame game, SBFFormation carrier, SBFFormation passenger) {
        BoardLocation start = carrier.getPosition();
        Board board = game.getBoard(start);
        boolean wiped = carrier.getUnits().stream().noneMatch(u -> u.getCurrentArmor() > 0);
        Player owner = game.getPlayer(passenger.getOwnerId());
        for (int radius = 0; radius <= board.getWidth() + board.getHeight(); radius++) {
            for (Coords coords : start.coords().allAtDistance(radius)) {
                if (!board.contains(coords)) {
                    continue;
                }
                BoardLocation candidate = BoardLocation.of(coords, start.boardId());
                List<SBFFormation> others = game.getActiveFormationsAt(candidate).stream()
                      .filter(f -> (!wiped || (f != carrier)) && !game.areHostile(f, owner)).toList();
                if (!SBFMovePath.exceedsStackingLimit(passenger, others)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
