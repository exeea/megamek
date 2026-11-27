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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.options.SBFRuleOptions;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFCapitalSector;
import megamek.common.strategicBattleSystems.SBFCapitalSector.Ring;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Capital-scale placement and movement, IO:BF pp. 180–185. */
final class SBFSpaceCommand extends ServerCommand {
    private static final String HELP = "/sbfspace status | deploy <ID> <sector> | move <ID> [sectors...] | retreat <ID> | zone <ID> [zones...]. "
          + "For refueling, recover <fighter ID> <carrier ID> or launch <fighter ID> <carrier ID>. Use stay <fighter ID> to remain aboard. "
          + "Use fall <ID> <clock zone> to choose where gravity places a Squadron. An optional final @clock chooses an Engagement Map entry bearing. Choose a falling WarShip's impact with impact <ID> <board> <column> <row>. Sectors are CENTRAL, I1-I6, M1-M12, O1-O24 and P1-P6. An empty move holds position.";
    private final SBFGameManager manager;

    SBFSpaceCommand(Server server, SBFGameManager manager) {
        super(server, "sbfspace", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int player, String[] args) {
        SBFGame game = manager.getGame();
        if (!game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_AEROSPACE) || game.getPlayer(player) == null) {
            return;
        }
        if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("status"))) {
            server.sendServerChat(player, HELP);
            for (SBFFormation squadron : game.getActiveFormations()) {
                if (squadron.capitalSector() != null && game.getVisibility(game.getPlayer(player), squadron.getId()).isLocated()) {
                    String details = squadron.getOwnerId() == player ? ", Thrust " + squadron.capitalThrust()
                          + (squadron.usesFighterFuel() ? ", fuel " + squadron.spaceFuel() : "") : "";
                    server.sendServerChat(player, "Contact " + squadron.getId() + " at " + squadron.capitalSector() + (squadron.engagementZone() == null ? "" : "/" + squadron.engagementZone().label()) + details);
                }
            }
            return;
        }
        try {
            SBFFormation squadron = game.getFormation(Integer.parseInt(args[2])).orElse(null);
            boolean accepted = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "deploy" -> args.length == 4 && deploy(manager, player, squadron, SBFCapitalSector.parse(args[3]));
                case "move" -> {
                    boolean choseBearing = args.length > 3 && args[args.length - 1].startsWith("@");
                    int end = args.length - (choseBearing ? 1 : 0);
                    yield move(manager, player, squadron, Arrays.stream(args, 3, end).map(SBFCapitalSector::parse).toList(),
                          false, choseBearing ? SBFRadarZone.parse(args[end].substring(1)) : null);
                }
                case "zone" -> new SBFAerospace(manager).move(player, squadron, new megamek.common.strategicBattleSystems.SBFMovePath.RadarFlight(
                      Arrays.stream(args, 3, args.length).map(SBFRadarZone::parse).toList(), List.of(), false));
                case "recover", "launch" -> args.length == 4 && handleRecovery(manager, player, squadron,
                      game.getFormation(Integer.parseInt(args[3])).orElse(null), args[1].equalsIgnoreCase("launch"));
                case "impact" -> args.length == 6 && SBFSpaceGravity.chooseAim(game, player, squadron,
                      megamek.common.board.BoardLocation.of(new megamek.common.board.Coords(Integer.parseInt(args[4]) - 1,
                            Integer.parseInt(args[5]) - 1), Integer.parseInt(args[3])));
                case "stay" -> args.length == 3 && stayAboard(manager, player, squadron);
                case "fall" -> args.length == 4 && setFallZone(game, player, squadron, SBFRadarZone.parse(args[3]));
                case "retreat" -> args.length == 3 && move(manager, player, squadron, List.of(), true);
                default -> false;
            };
            if (accepted) {
                manager.sendPendingPackets();
                return;
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // Invalid orders leave the Squadron and its turn unchanged.
        }
        server.sendServerChat(player, HELP);
    }

    private static boolean authorized(SBFGame game, int player, SBFFormation squadron) {
        return squadron != null && game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_AEROSPACE)
              && squadron.isAerospace() && squadron.hasSUA(BattleForceSUA.SPC) && game.getTurn() != null
              && game.getTurn().isValid(player, squadron, game);
    }

    static boolean deploy(SBFGameManager manager, int player, SBFFormation squadron, SBFCapitalSector start) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isDeployment() || !authorized(game, player, squadron)
              || (start.ring() != Ring.OUTER && start.ring() != Ring.PERIPHERAL)) {
            return false;
        }
        var enemy = game.getActiveFormations().stream().filter(f -> f.capitalSector() != null
              && game.areHostile(f, game.getPlayer(player))).findFirst();
        if (enemy.isPresent() && !start.oppositeBearing(enemy.get().capitalSector())) {
            return false;
        }
        squadron.setCapitalSector(start);
        squadron.setDeployed(true);
        manager.sendUnitUpdate(squadron);
        manager.endCurrentTurn(squadron);
        return true;
    }

    static boolean move(SBFGameManager manager, int player, SBFFormation squadron, List<SBFCapitalSector> route, boolean retreat) {
        return move(manager, player, squadron, route, retreat, null);
    }

    private static boolean move(SBFGameManager manager, int player, SBFFormation squadron, List<SBFCapitalSector> route,
          boolean retreat, SBFRadarZone entryBearing) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isMovement() || !authorized(game, player, squadron) || squadron.capitalSector() == null
              || !squadron.getEngagedOpponents().isEmpty() || game.engagementOrders().pendingMove() != null) {
            return false;
        }
        var current = squadron.capitalSector();
        if (squadron.engagementZone() != null && !squadron.waitingForSpaceCombat(game.getCurrentRound())) {
            if (route.isEmpty() && !retreat) {
                return new SBFAerospace(manager).move(player, squadron, new megamek.common.strategicBattleSystems.SBFMovePath.RadarFlight(
                      List.of(), List.of(), false));
            }
            if (route.size() != 1 || !squadron.engagementZone().isOuter() || !current.adjacent(route.getFirst())
                  || !nearBearing(squadron.engagementZone(), current.directionTo(route.getFirst()))) {
                return false;
            }
        }
        int allowance = SBFAerospace.zoneAllowance(current.adjustedThrust(squadron.capitalThrust()));
        if (route.size() + (retreat ? 1 : 0) > allowance) {
            return false;
        }
        var previous = current;
        for (var next : route) {
            if (!current.adjacent(next)) {
                return false;
            }
            previous = current;
            current = next;
        }
        if (entryBearing != null && (route.isEmpty() || !nearBearing(current.directionTo(previous), entryBearing))) {
            return false;
        }
        var origin = squadron.capitalSector();
        if (retreat) {
            if (current.ring() != Ring.PERIPHERAL) {
                return false;
            }
            SBFWithdrawal.leaveMap(manager, squadron, squadron.mustWithdraw());
        } else {
            if (!route.isEmpty()) {
                squadron.setCapitalSector(current);
                squadron.markSpaceManeuver(game.getCurrentRound());
                if (!current.equals(origin)) {
                    removeEmptyMap(manager, origin);
                }
                if (current.ring() == Ring.CENTRAL && game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_GROUND_CENTER)) {
                    // I1 is due north and corresponds to atmospheric Outer 12; I4 corresponds to Outer 6.
                    int clock = (previous.number() - 1) * 2;
                    squadron.enterCapitalAtmosphere(SBFRadarZone.parse(Integer.toString(clock == 0 ? 12 : clock)));
                } else {
                    enterMap(manager, squadron, entryBearing == null ? current.directionTo(previous) : entryBearing);
                }
            }
            squadron.setMpSpentThisTurn(route.size());
            squadron.setDone(true);
            manager.sendUnitUpdate(squadron);
        }
        manager.endCurrentTurn(squadron);
        return true;
    }

    private static boolean nearBearing(SBFRadarZone first, SBFRadarZone second) {
        return first.isOuter() && second.isOuter() && (first == second || first.adjacent(second));
    }

    static void enterMap(SBFGameManager manager, SBFFormation arrival, SBFRadarZone entry) {
        var game = manager.getGame();
        var sector = arrival.capitalSector();
        if (sector == null || sector.ring() == Ring.PERIPHERAL || (sector.ring() == Ring.CENTRAL
              && game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_GROUND_CENTER))) {
            return;
        }
        var occupants = game.getActiveFormations().stream().filter(f -> sector.equals(f.capitalSector())).toList();
        if (occupants.stream().noneMatch(f -> game.areHostile(f, game.getPlayer(arrival.getOwnerId())))) {
            return;
        }
        for (var occupant : occupants) {
            if (occupant.engagementZone() == null) {
                occupant.enterEngagementMap(occupant == arrival ? entry : SBFRadarZone.CENTRAL, game.getCurrentRound());
                manager.sendUnitUpdate(occupant);
            }
        }
    }

    static void removeEmptyMap(SBFGameManager manager, SBFCapitalSector sector) {
        var game = manager.getGame();
        var occupants = game.getActiveFormations().stream().filter(f -> sector.equals(f.capitalSector())).toList();
        if (!occupants.isEmpty() && occupants.stream().noneMatch(f -> game.areHostile(f, game.getPlayer(occupants.getFirst().getOwnerId())))) {
            for (var occupant : occupants) {
                if (occupant.engagementZone() != null) {
                    occupant.setEngagementZone(null);
                    manager.sendUnitUpdate(occupant);
                }
            }
        }
    }

    static boolean stayAboard(SBFGameManager manager, int player, SBFFormation fighter) {
        var game = manager.getGame();
        if (!game.getPhase().isMovement() || fighter == null || !fighter.readyToLaunch(game.getCurrentRound())
              || game.getTurn() == null || !game.getTurn().isValid(player, fighter, game)) {
            return false;
        }
        fighter.setDone(true);
        manager.sendUnitUpdate(fighter);
        manager.endCurrentTurn(fighter);
        return true;
    }

    static boolean setFallZone(SBFGame game, int player, SBFFormation squadron, SBFRadarZone zone) {
        if (squadron == null || squadron.getOwnerId() != player || !game.getPhase().isMovement() || !zone.isOuter()
              || !game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_GROUND_CENTER)) {
            return false;
        }
        squadron.setGravityBearing(zone);
        return true;
    }

    static boolean handleRecovery(SBFGameManager manager, int player, SBFFormation fighter, SBFFormation carrier,
          boolean launch) {
        SBFGame game = manager.getGame();
        int round = game.getCurrentRound();
        if (!game.getPhase().isMovement() || !game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_AEROSPACE)
              || fighter == null || carrier == null || fighter == carrier || fighter.getOwnerId() != player
              || !fighter.usesFighterFuel() || carrier.capitalSector() == null || game.getTurn() == null
              || game.getTurn().playerId() != player || game.areHostile(carrier, game.getPlayer(player))) {
            return false;
        }
        if (launch) {
            int doors = rating(carrier, BattleForceSUA.ATxD) + rating(carrier, BattleForceSUA.STxD);
            if (!game.getTurn().isValid(player, fighter, game) || !fighter.isCarriedBy(carrier.getId()) || !fighter.readyToLaunch(round)
                  || carrier.bayDoorsUsed(round) >= 3 * doors) {
                return false;
            }
            carrier.useBayDoors(round, 1);
            fighter.launchRefueled(carrier, round);
        } else {
            if (!authorized(game, player, fighter) || !carrier.capitalSector().equals(fighter.capitalSector())
                  || carrier.combatZone() != fighter.combatZone() || !carrier.getEngagedOpponents().isEmpty()
                  || !fighter.getEngagedOpponents().isEmpty() || fighter.spaceFuel() >= fighter.fighterFuelCapacity()) {
                return false;
            }
            int used = game.passengersOf(carrier).stream().filter(SBFFormation::isAerospace)
                  .mapToInt(SBFFormation::fighterSlots).sum();
            if (used + fighter.fighterSlots() > rating(carrier, BattleForceSUA.AT) + rating(carrier, BattleForceSUA.ST)) {
                return false;
            }
            int tn = fighter.getSkill() + (fighter.capitalThrust() == 0 ? 6 : 0);
            if (Compute.d6(2) < tn) {
                game.getImmediateLosses().add(SBFAerospace.crash(game, fighter));
            } else {
                fighter.recoverForRefueling(carrier, round);
                fighter.setDone(true);
            }
        }
        manager.sendUnitUpdate(carrier);
        if (game.getFormation(fighter.getId()).isPresent()) {
            manager.sendUnitUpdate(fighter);
        } else {
            manager.entityAllUpdate();
        }
        manager.endCurrentTurn(fighter);
        return true;
    }

    private static int rating(SBFFormation carrier, BattleForceSUA special) {
        return carrier.getSUA(special) instanceof Number n ? n.intValue() : 0;
    }

    static void endTurn(SBFGameManager manager) {
        SBFGame game = manager.getGame();
        game.getActiveFormations().stream().filter(f -> f.engagementZone() != null).map(SBFFormation::capitalSector)
              .filter(Objects::nonNull).distinct().toList().forEach(s -> removeEmptyMap(manager, s));
        for (SBFFormation squadron : game.getActiveFormations()) {
            if (squadron.capitalSector() != null) {
                int before = squadron.spaceFuel();
                squadron.expendSpaceFuel(game.getCurrentRound());
                if (game.getOptions().booleanOption(SBFRuleOptions.CAPITAL_GROUND_CENTER)
                      && squadron.capitalSector().ring() == Ring.CENTRAL && squadron.capitalThrust() < 2) {
                    squadron.fallIntoAtmosphere(game.getCurrentRound());
                    manager.sendUnitUpdate(squadron);
                }
                if (squadron.spaceFuel() != before) {
                    manager.addReportFor(new SBFReportEntry(3630).add(squadron.generalName()).add(squadron.spaceFuel()), squadron);
                }
            }
        }
    }
}
