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

import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import megamek.common.units.Terrains;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Supplementary ground movement orders (IO:BF p. 166). */
class SBFMovementCommand extends ServerCommand {
    static final String HELP = "/sbfmove <formation ID> surface|submerge|land|lift|evasive, "
          + "hull [column,row ...], buildings [column,row ...], rooftop [column,row ...], or fortify <Unit number>";
    private final SBFGameManager manager;

    SBFMovementCommand(Server server, SBFGameManager manager) {
        super(server, "sbfmove", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int player, String[] args) {
        try {
            var game = manager.getGame();
            SBFFormation formation = args.length >= 3 ? game.getFormation(Integer.parseInt(args[1])).orElse(null) : null;
            if ((formation == null) || !game.getPhase().isMovement() || (game.getTurn() == null)
                  || !game.getTurn().isValid(player, formation, game) || (game.engagementOrders().pendingMove() != null)
                  || (!formation.occupiesGroundHex() && !formation.isOnRadarMap() && formation.capitalSector() == null)) {
                server.sendServerChat(player, HELP);
                return;
            }
            boolean buildings = args[2].equalsIgnoreCase("buildings");
            boolean rooftop = args[2].equalsIgnoreCase("rooftop");
            if ((buildings || rooftop || args[2].equalsIgnoreCase("hull")) && formation.occupiesGroundHex()) {
                var point = formation.getPosition();
                var route = new SBFMovePath(formation.getId(), point, game);
                if ((long) args.length > (long) SBFMovePath.maximumMovementPoints(formation, game) + 4) {
                    server.sendServerChat(player, HELP);
                    return;
                }
                for (int i = 3; i < args.length; i++) {
                    String[] xy = args[i].split(",", -1);
                    if (xy.length != 2) {
                        server.sendServerChat(player, HELP);
                        return;
                    }
                    var next = BoardLocation.of(new Coords(Integer.parseInt(xy[0]) - 1, Integer.parseInt(xy[1]) - 1), point.boardId());
                    if (!game.hasBoardLocation(next) || point.coords().distance(next.coords()) != 1) {
                        server.sendServerChat(player, HELP);
                        return;
                    }
                    route.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), point, next));
                    point = next;
                }
                if (buildings) {
                    route.enterBuildingsAtEnd();
                } else if (rooftop) {
                    route.climbBuildingsAtEnd();
                } else {
                    route.enterHullDownAtEnd();
                }
                if (!manager.movementProcessor.processMovement(route, formation)) {
                    server.sendServerChat(player, buildings ? "This Formation cannot enter the buildings on that route."
                          : rooftop ? "This Formation cannot climb onto the roofs on that route."
                          : "This Formation cannot enter a fortified position on that route.");
                }
                manager.sendPendingPackets();
                return;
            }
            if (args[2].equalsIgnoreCase("fortify") && args.length == 4) {
                if (!SBFFieldFortification.construct(manager, formation, Integer.parseInt(args[3]) - 1)) {
                    server.sendServerChat(player, "This Unit cannot construct a field fortification here.");
                }
                manager.sendPendingPackets();
                return;
            }
            if (args.length != 3) {
                server.sendServerChat(player, HELP);
                return;
            }
            if (args[2].equalsIgnoreCase("evasive") && game.getOptions().booleanOption(SBFRuleOptions.MOVE_EVASIVE)
                  && !formation.ambushedIn(game.getCurrentRound())) {
                formation.moveEvasively();
                manager.sendUnitUpdate(formation);
                manager.sendPendingPackets();
                return;
            }
            if (!formation.occupiesGroundHex()) {
                server.sendServerChat(player, HELP);
                return;
            }
            if (formation.isAirVehicle() && (args[2].equalsIgnoreCase("land") || args[2].equalsIgnoreCase("lift"))) {
                boolean land = args[2].equalsIgnoreCase("land");
                if (!land && formation.isBoggedDown()) {
                    server.sendServerChat(player, "The Formation must escape the terrain before lifting off.");
                    return;
                }
                if (land && !formation.isAirVehicleLanded() && game.usesAdvancedTerrain()
                      && game.getHex(formation.getPosition()).containsTerrain(Terrains.SWAMP)) {
                    formation.resolveBogCheck(true, game.getCurrentRound());
                }
                formation.setAirVehicleLanded(land);
                manager.sendUnitUpdate(formation);
                manager.sendPendingPackets();
                return;
            }
            boolean surface = args[2].equalsIgnoreCase("surface");
            if ((!surface && !args[2].equalsIgnoreCase("submerge"))
                  || !formation.canChangeSubmarineDepth(game.getHex(formation.getPosition()), surface)) {
                server.sendServerChat(player, "This depth is unavailable in the current hex. " + HELP);
                return;
            }
            formation.setSubmarineSurfaced(surface);
            manager.sendUnitUpdate(formation);
            manager.sendPendingPackets();
        } catch (NumberFormatException ex) {
            server.sendServerChat(player, HELP);
        }
    }
}
