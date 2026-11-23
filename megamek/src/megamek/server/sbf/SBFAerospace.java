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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;

import megamek.common.Player;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.FormationLosses;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovePath.RadarFlight;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;

/** Strategic Aerospace setup and atmospheric movement (IO:BF pp. 175–176). */
record SBFAerospace(SBFGameManager manager) {
    static int zoneAllowance(int thrust) {
        return thrust <= 0 ? 0 : Math.min(3, 1 + (thrust - 1) / 5);
    }

    boolean deploy(int playerId, SBFFormation squadron, SBFRadarZone requested, int he, int cluster) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isDeployment() || !(game.getTurn() instanceof SBFFormationTurn turn)
              || (squadron == null) || !turn.isValid(playerId, squadron, game) || !squadron.isType(SBFElementType.AS)
              || squadron.getUnits().stream().anyMatch(u -> u.getType() == SBFElementType.LA
                    || u.getElements().stream().anyMatch(AlphaStrikeElement::isLargeAerospace))) {
            return false;
        }
        Player owner = game.getPlayer(playerId);
        SBFRadarZone home = homeZone(game, owner, requested);
        if (!home.isOuter() || ((requested != null) && (requested != home)) || !validBombLoad(squadron, he, cluster)) {
            return false;
        }
        squadron.setRadarHome(home);
        squadron.setBombLoad(he, cluster);
        setAtmosphericPosition(game, squadron, home, List.of());
        squadron.setDeployed(true);
        manager.sendUnitUpdate(squadron);
        manager.endCurrentTurn(squadron);
        return true;
    }

    static boolean validBombLoad(SBFFormation formation, int he, int cluster) {
        int capacity = formation.getSUA(BattleForceSUA.BOMB) instanceof Number n ? n.intValue() : 0;
        return (he >= 0) && (cluster >= 0) && ((long) he + cluster <= capacity);
    }

    /** Hovering VTOL battle armor may carry conventional ordnance for a dive attack (IO:BF p. 178). */
    boolean loadBattleArmor(int player, SBFFormation formation, int he, int cluster) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isDeployment() || (formation == null) || !(game.getTurn() instanceof SBFFormationTurn turn)
              || !turn.isValid(player, formation, game) || !SBFAirGroundRun.isBombingBattleArmor(formation)
              || !validBombLoad(formation, he, cluster)) {
            return false;
        }
        formation.setBombLoad(he, cluster);
        manager.sendUnitUpdate(formation);
        return true;
    }

    /** Ground deployment sets the first bearing. An existing friendly or opposing side fixes later bearings (p. 176). */
    private static SBFRadarZone homeZone(SBFGame game, Player owner, SBFRadarZone requested) {
        SBFRadarZone groundEdge = SBFRadarZone.home(owner.getStartingPos());
        return game.getActiveFormations().stream().filter(f -> f.isOnRadarMap() && (f.getRadarHome() != null))
              .sorted(Comparator.comparingInt(SBFFormation::getId))
              .map(f -> game.areHostile(f, owner) ? f.getRadarHome().oppositeOuter() : f.getRadarHome())
              .findFirst().orElse(groundEdge != null ? groundEdge : (requested == null ? SBFRadarZone.OUTER_12 : requested));
    }

    /** Broken grounded aerospace spends its next move lifting off (IO:BF p. 173). */
    boolean liftBrokenSquadron(SBFFormation squadron) {
        SBFGame game = manager.getGame();
        if (!squadron.isType(SBFElementType.AS) || squadron.isOnRadarMap() || !squadron.mustWithdraw()
              || (squadron.getPosition() == null)) {
            return false;
        }
        if ((squadron.getCurrentThrust() > 0) && game.canEnterCentral(squadron)) {
            squadron.setRadarHome(homeZone(game, game.getPlayer(squadron.getOwnerId()), null));
            game.enterCentral(squadron);
            game.visibilityHelper().enteredCentral(squadron.getId(), List.of(squadron.getPosition()));
            squadron.setRadarPosition(SBFRadarZone.CENTRAL, List.of(squadron.getPosition()));
        }
        squadron.setDone(true);
        manager.sendUnitUpdate(squadron);
        manager.endCurrentTurn(squadron);
        return true;
    }

    /** Distances include the final boundary crossing. When home is unspecified, any outer edge is an exit. */
    private static Map<SBFRadarZone, Integer> distanceToExit(SBFRadarZone home) {
        Map<SBFRadarZone, Integer> distance = new EnumMap<>(SBFRadarZone.class);
        ArrayDeque<SBFRadarZone> frontier = new ArrayDeque<>();
        for (SBFRadarZone zone : SBFRadarZone.values()) {
            if ((zone == home) || ((home == null) && zone.isOuter())) {
                distance.put(zone, 1);
                frontier.add(zone);
            }
        }
        while (!frontier.isEmpty()) {
            SBFRadarZone next = frontier.remove();
            for (SBFRadarZone neighbor : next.neighbors()) {
                if (distance.putIfAbsent(neighbor, distance.get(next) + 1) == null) {
                    frontier.add(neighbor);
                }
            }
        }
        return distance;
    }

    static RadarFlight withdrawalFlight(SBFFormation squadron) {
        Map<SBFRadarZone, Integer> distance = distanceToExit(squadron.getRadarHome());
        SBFRadarZone zone = squadron.getRadarZone();
        List<SBFRadarZone> route = new ArrayList<>();
        int allowance = zoneAllowance(squadron.getCurrentThrust());
        for (int i = 0; i < allowance; i++) {
            if (distance.get(zone) == 1) {
                return new RadarFlight(route, List.of(), true);
            }
            zone = zone.neighbors().stream().min(Comparator.comparingInt(distance::get)).orElseThrow();
            route.add(zone);
        }
        return new RadarFlight(route, List.of(), false);
    }

    boolean move(int player, SBFFormation squadron, RadarFlight flight) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isMovement() || (squadron == null) || squadron.inHighDrop() || squadron.combatZone() == null
              || (game.getTurn() == null) || !game.getTurn().isValid(player, squadron, game)
              || (game.engagementOrders().pendingMove() != null) || !validFlight(game, squadron, flight)) {
            return false;
        }
        SBFMovePath route = new SBFMovePath(squadron.getId(), squadron.getPosition(), game);
        route.setRadarFlight(flight);
        fly(squadron, route);
        return true;
    }

    static boolean validFlight(SBFGame game, SBFFormation squadron, RadarFlight flight) {
        if (squadron.engagementZone() != null) {
            if (flight == null || squadron.waitingForSpaceCombat(game.getCurrentRound()) || flight.retreat()
                  || !flight.groundPath().isEmpty() || flight.zones().size()
                  > zoneAllowance(squadron.capitalSector().adjustedThrust(squadron.capitalThrust()))) {
                return false;
            }
            SBFRadarZone previous = squadron.engagementZone();
            for (var next : flight.zones()) {
                if (!previous.adjacent(next)) {
                    return false;
                }
                previous = next;
            }
            return true;
        }
        if ((flight == null) || (flight.zones().size() + (flight.retreat() ? 1 : 0)
              > zoneAllowance(squadron.getCurrentThrust()))) {
            return false;
        }
        SBFRadarZone zone = squadron.getRadarZone();
        // Initialize this round's Central occupants before any Squadron leaves it.
        boolean mayEnterCentral = game.canEnterCentral(squadron);
        for (SBFRadarZone next : flight.zones()) {
            if (!zone.adjacent(next) || ((next == SBFRadarZone.CENTRAL) && !mayEnterCentral)
                  || ((squadron.capitalSector() != null || squadron.fallingFromOrbit()) && !next.isOuter()
                        && !squadron.canDescendIntoAtmosphere())) {
                return false;
            }
            zone = next;
        }
        if (squadron.mustWithdraw()) {
            Map<SBFRadarZone, Integer> distance = distanceToExit(squadron.getRadarHome());
            int best = Math.max(0, distance.get(squadron.getRadarZone()) - zoneAllowance(squadron.getCurrentThrust()));
            if ((flight.retreat() && (distance.get(zone) != 1)) || ((flight.retreat() ? 0 : distance.get(zone)) > best)) {
                return false;
            }
        }
        for (var rider : game.passengersOf(squadron)) {
            if (rider.plannedLowDrop() != null && ((zone != SBFRadarZone.CENTRAL)
                  || !(flight.groundPath().isEmpty() ? squadron.getGroundFlightPath() : flight.groundPath()).contains(rider.plannedLowDrop()))) {
                return false;
            }
        }
        if (flight.retreat() && !zone.isOuter()) {
            return false;
        }
        boolean crossesCentral = flight.zones().contains(SBFRadarZone.CENTRAL);
        if (flight.groundPath().isEmpty()) {
            // Entering Central needs a contingency flight path even when engagement interrupts a transit (p. 176).
            return !crossesCentral && ((zone != SBFRadarZone.CENTRAL) || !squadron.getGroundFlightPath().isEmpty());
        }
        if (!crossesCentral && (squadron.getRadarZone() != SBFRadarZone.CENTRAL)) {
            return false;
        }
        BoardLocation previous = null;
        for (BoardLocation hex : flight.groundPath()) {
            if (!game.hasBoardLocation(hex) || !game.getBoard(hex.boardId()).isGround()
                  || ((previous != null) && ((previous.boardId() != hex.boardId())
                  || (previous.coords().distance(hex.coords()) != 1)))) {
                return false;
            }
            previous = hex;
        }
        return true;
    }

    /** A released high drop appears in Central immediately, including after an enemy there took its move (p. 209). */
    void beginDropContact(SBFFormation passenger) {
        var route = new SBFMovePath(passenger.getId(), null, manager.getGame());
        route.setRadarFlight(new RadarFlight(List.of(), List.of(), false));
        fly(passenger, route);
    }

    void fly(SBFFormation squadron, SBFMovePath route) {
        SBFGame game = manager.getGame();
        game.engagementOrders().startRound(game.getCurrentRound());
        RadarFlight flight = route.radarFlight();
        List<SBFRadarZone> visited = new ArrayList<>();
        visited.add(squadron.combatZone());
        visited.addAll(flight.zones());
        SBFEngagementControl control = new SBFEngagementControl(manager);
        Player owner = game.getPlayer(squadron.getOwnerId());
        for (int i = 0; i < visited.size(); i++) {
            SBFRadarZone zone = visited.get(i);
            if ((zone == SBFRadarZone.CENTRAL) && squadron.engagementZone() == null && !squadron.inHighDrop()) {
                game.enterCentral(squadron);
                if (i > 0) {
                    game.visibilityHelper().enteredCentral(squadron.getId(), flight.groundPath());
                }
            }
            List<SBFFormation> opponents = game.getActiveFormations().stream()
                  .filter(f -> squadron.sharesAerospaceMap(f) && f.combatZone() == zone
                        && !f.waitingForSpaceCombat(game.getCurrentRound()) && game.areHostile(f, owner))
                  .sorted(Comparator.comparingInt(SBFFormation::getId)).toList();
            boolean stopped = false;
            for (SBFFormation opponent : opponents) {
                Boolean engaged = control.airContact(squadron, opponent, route);
                if (engaged == null) {
                    return;
                }
                stopped |= engaged;
            }
            if (stopped) {
                finish(squadron, zone, flight, i, false);
                return;
            }
        }
        finish(squadron, visited.getLast(), flight, flight.zones().size(), flight.retreat());
    }

    private void finish(SBFFormation squadron, SBFRadarZone zone, RadarFlight flight, int spent, boolean retreat) {
        SBFGame game = manager.getGame();
        game.engagementOrders().finishMovement();
        if (squadron.inHighDrop()) {
            manager.sendUnitUpdate(squadron);
            return; // Resolving the drop does not expend the carrier's own movement turn.
        }
        if (squadron.engagementZone() != null) {
            squadron.setEngagementZone(zone);
            if (spent > 0) {
                squadron.markSpaceManeuver(game.getCurrentRound());
            }
            squadron.setMpSpentThisTurn(spent);
            squadron.setDone(true);
            manager.sendUnitUpdate(squadron);
        } else if (retreat) {
            SBFWithdrawal.leaveMap(manager, squadron, squadron.mustWithdraw());
        } else {
            List<BoardLocation> path = List.of();
            if (zone == SBFRadarZone.CENTRAL) {
                path = flight.groundPath().isEmpty() ? squadron.getGroundFlightPath() : flight.groundPath();
            }
            setAtmosphericPosition(game, squadron, zone, path);
            for (var rider : game.passengersOf(squadron)) {
                BoardLocation landing = rider.plannedLowDrop();
                if (landing != null) {
                    rider.planLowDrop(null);
                    if ((zone == SBFRadarZone.CENTRAL) && path.contains(landing)) {
                        rider.aimDrop(landing, false);
                    }
                    manager.sendUnitUpdate(rider);
                }
            }
            squadron.setMpSpentThisTurn(spent);
            squadron.setDone(true);
            manager.sendUnitUpdate(squadron);
        }
        manager.endCurrentTurn(squadron);
    }

    private static void setAtmosphericPosition(SBFGame game, SBFFormation squadron, SBFRadarZone zone,
          List<BoardLocation> path) {
        if (zone.isOuter() && !squadron.fallingFromOrbit() && squadron.hasSUA(BattleForceSUA.SPC)
              && game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_AEROSPACE)
              && game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_GROUND_CENTER)) {
            squadron.enterCapitalAtmosphere(zone);
        } else {
            squadron.setRadarPosition(zone, path);
        }
    }

    /** Maintaining Position and Aerospace End Phase, IO:BF pp. 176, 179. */
    List<FormationLosses> removeCrashedSquadrons() {
        SBFGame game = manager.getGame();
        List<FormationLosses> losses = new ArrayList<>();
        for (SBFFormation squadron : game.getActiveFormations()) {
            if (squadron.fallingFromOrbit() && !squadron.fellThisRound(game.getCurrentRound())
                  && !squadron.canDescendIntoAtmosphere()) {
                losses.addAll(SBFSpaceGravity.fallInward(manager, squadron));
            } else if (squadron.isOnRadarMap() && squadron.capitalSector() == null && !squadron.fellThisRound(game.getCurrentRound())
                  && !squadron.inHighDrop() && (squadron.getCurrentThrust() == 0)) {
                manager.sendServerChat(squadron.getOwnerId(), "Squadron " + squadron.getId() + " crashed at zero Thrust.");
                for (SBFFormation passenger : game.passengersOf(squadron)) {
                    losses.add(crash(game, passenger));
                }
                losses.add(crash(game, squadron));
            }
        }
        return losses;
    }

    static FormationLosses crash(SBFGame game, SBFFormation formation) {
        boolean leader = formation.getUnits().stream().anyMatch(u -> u.hasSUA(BattleForceSUA.LEAD));
        boolean commander = formation.getUnits().stream().anyMatch(u -> u.hasSUA(BattleForceSUA.COM));
        formation.getUnits().forEach(unit -> {
            game.scenarioProgress().recordLoss(game.getCurrentRound(), formation, unit, Player.PLAYER_NONE, LossCause.CRASH);
            unit.setCurrentArmor(0);
        });
        game.removeFormationFromPlay(formation);
        return new FormationLosses(formation, true, formation.getUnits().size(), leader, commander, true);
    }
}
