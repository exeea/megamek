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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import megamek.common.actions.sbf.SBFAirGroundAttack;
import megamek.common.actions.sbf.SBFAirGroundAttack.Release;
import megamek.common.actions.sbf.SBFAirGroundAttack.Type;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.AirResponse;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath.RadarFlight;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Player controls for the atmospheric radar map (IO:BF pp. 175–179). */
class SBFAeroCommand extends ServerCommand {
    static final String HELP = "/sbfaero status | deploy <ID> [outer zone] [HE bombs] [cluster bombs] "
          + "| move <ID> [zones ...] [retreat] [path <board ID> <col,row> ...]. "
          + "Zones are CENTRAL, INNER, A–F and 1–12. An empty move holds position. Use withdraw <ID> for a homeward "
          + "route. Combat orders: strike|strafe <ID> <target IDs> [flights 1,2]; dive <ID> <target ID> <HE> <cluster>; "
          + "altitude <ID> <col,row,HE,cluster> ...; add flights 1,2 to select one bombing Flight per impact hex. "
          + "Load VTOL battle armor before deployment with load <ID> <HE> <cluster>. Air defense: return|reserve|pass <ID> <attacker ID>.";
    private final SBFGameManager manager;

    SBFAeroCommand(Server server, SBFGameManager manager) {
        super(server, "sbfaero", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int player, String[] args) {
        if ((args.length < 2) || args[1].equalsIgnoreCase("status")) {
            showRadar(player);
            return;
        }
        try {
            SBFGame game = manager.getGame();
            SBFFormation squadron = game.getFormation(Integer.parseInt(args[2])).orElse(null);
            SBFAerospace aerospace = new SBFAerospace(manager);
            boolean done = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "load" -> (args.length == 5) && aerospace.loadBattleArmor(player, squadron,
                      Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                case "deploy" -> (args.length <= 6) && aerospace.deploy(player, squadron,
                      args.length > 3 ? SBFRadarZone.parse(args[3]) : null,
                      args.length > 4 ? Integer.parseInt(args[4]) : 0,
                      args.length > 5 ? Integer.parseInt(args[5]) : 0);
                case "return", "reserve", "pass" -> (args.length == 4) && (squadron != null)
                      && new SBFEngagementControl(manager).airResponse(player, squadron.getId(), Integer.parseInt(args[3]),
                      AirResponse.valueOf(args[1].toUpperCase(Locale.ROOT)));
                case "strike", "strafe", "dive", "altitude" -> (squadron != null)
                      && SBFAirGroundRun.declare(manager, player, parseAttack(squadron, args));
                case "move" -> aerospace.move(player, squadron, parseFlight(args));
                case "withdraw" -> (args.length == 3) && (squadron != null) && squadron.isOnRadarMap()
                      && aerospace.move(player, squadron, SBFAerospace.withdrawalFlight(squadron));
                default -> false;
            };
            if (done) {
                manager.sendPendingPackets();
            } else {
                server.sendServerChat(player, "This aerospace order is unavailable. " + HELP);
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
            server.sendServerChat(player, HELP);
        }
    }

    private SBFAirGroundAttack parseAttack(SBFFormation squadron, String[] args) {
        Type type = Type.valueOf(args[1].toUpperCase(Locale.ROOT));
        List<Integer> targets = new ArrayList<>();
        List<Integer> flights = new ArrayList<>();
        List<Release> releases = new ArrayList<>();
        int end = args.length;
        if ((end >= 5) && args[end - 2].equalsIgnoreCase("flights")) {
            Arrays.stream(args[end - 1].split(",", -1)).mapToInt(Integer::parseInt)
                  .forEach(number -> flights.add(number - 1));
            end -= 2;
        }
        if (type == Type.DIVE) {
            if (end != 6) {
                throw new IllegalArgumentException("Expected a target and bomb load");
            }
            int id = Integer.parseInt(args[3]);
            targets.add(id);
            BoardLocation hex = manager.getGame().getFormation(id).orElseThrow(IllegalArgumentException::new).getPosition();
            releases.add(new Release(hex, Integer.parseInt(args[4]), Integer.parseInt(args[5])));
        } else if (type == Type.ALTITUDE) {
            if (squadron.getGroundFlightPath().isEmpty()) {
                throw new IllegalArgumentException("No ground flight path");
            }
            int board = squadron.getGroundFlightPath().getFirst().boardId();
            for (int i = 3; i < end; i++) {
                String[] parts = args[i].split(",", -1);
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Expected col,row,HE,cluster");
                }
                BoardLocation hex = BoardLocation.of(new Coords(Integer.parseInt(parts[0]) - 1,
                      Integer.parseInt(parts[1]) - 1), board);
                releases.add(new Release(hex, Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
            }
        } else {
            for (int i = 3; i < end; i++) {
                targets.add(Integer.parseInt(args[i]));
            }
        }
        return new SBFAirGroundAttack(squadron.getId(), type, targets, flights, releases);
    }

    private RadarFlight parseFlight(String[] args) {
        List<SBFRadarZone> zones = new ArrayList<>();
        List<BoardLocation> path = new ArrayList<>();
        boolean retreat = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("path")) {
                int board = Integer.parseInt(args[++i]);
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing flight path");
                }
                for (i++; i < args.length; i++) {
                    String[] xy = args[i].split(",", -1);
                    if (xy.length != 2) {
                        throw new IllegalArgumentException("Expected column,row");
                    }
                    path.add(BoardLocation.of(new Coords(Integer.parseInt(xy[0]) - 1,
                          Integer.parseInt(xy[1]) - 1), board));
                }
            } else if (args[i].equalsIgnoreCase("retreat") && (i == args.length - 1)) {
                retreat = true;
            } else {
                zones.add(SBFRadarZone.parse(args[i]));
            }
        }
        return new RadarFlight(zones, path, retreat);
    }

    private void showRadar(int player) {
        SBFGame game = manager.getGame();
        server.sendServerChat(player, HELP);
        for (SBFFormation squadron : game.getActiveFormations()) {
            if (squadron.isOnRadarMap() && game.getVisibility(game.getPlayer(player), squadron.getId()).isLocated()) {
                String name = game.isVisible(player, squadron.getId()) ? squadron.generalName() : "radar contact";
                String description = squadron.getId() + " " + name + " — " + squadron.getRadarZone().label();
                if (squadron.getOwnerId() == player) {
                    description += ", Thrust " + squadron.getCurrentThrust() + ", neighboring zones "
                          + squadron.getRadarZone().neighbors().stream().map(SBFRadarZone::label).toList()
                          + ", ground flight path " + squadron.getGroundFlightPath();
                }
                server.sendServerChat(player, description);
            }
        }
    }
}
