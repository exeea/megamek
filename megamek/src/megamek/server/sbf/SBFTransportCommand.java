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

import java.util.List;

import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovePath.TransportOrder;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Enter a carrier's complete movement and its pickup/drop points, IO:BF p. 167. */
final class SBFTransportCommand extends ServerCommand {
    private static final String HELP = "/sbftransport <carrier ID> [mount:<infantry ID>] [column,row ...] "
          + "[drop:<infantry ID> ...]. For a non-infantry passenger use <passenger ID> [column,row ...] board:<carrier ID>, "
          + "or unload <passenger ID> <carrier ID> <column,row>. Coordinates start at 1. Setup: embark <passenger> <carrier>. "
          + "Airdrop: high <passenger> <carrier>, low <passenger> <carrier> <board> <column,row>, "
          + "or land <dropping formation> <board> <column,row>. Cargo: cargo <passenger> <Unit number> <DropShip>, "
          + "or uncargo <passenger> <DropShip> <column,row>.";
    private final SBFGameManager manager;

    SBFTransportCommand(Server server, SBFGameManager manager) {
        super(server, "sbftransport", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int connId, String[] args) {
        SBFGame game = manager.getGame();
        try {
            if (args.length == 5 && (args[1].equals("cargo") || args[1].equals("uncargo"))) {
                var passenger = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                boolean loading = args[1].equals("cargo");
                var ship = game.getFormation(Integer.parseInt(args[loading ? 4 : 3])).orElse(null);
                boolean accepted = loading ? SBFCargoTransport.load(manager, connId, passenger, Integer.parseInt(args[3]) - 1, ship)
                      : ship != null && ship.getPosition() != null && SBFCargoTransport.unload(manager, connId, passenger, ship,
                      hex(Integer.toString(ship.getPosition().boardId()), args[4]));
                if (!accepted) {
                    server.sendServerChat(connId, "Cargo handling requires a grounded cargo bay, capacity for 110% of the Unit's weight, and an available transfer. " + HELP);
                }
                manager.sendPendingPackets();
                return;
            }
            if (args.length >= 4 && List.of("embark", "high", "low", "land").contains(args[1])) {
                var drop = new SBFAirDrop(manager);
                var passenger = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                var carrier = args[1].equals("land") ? null : game.getFormation(Integer.parseInt(args[3])).orElse(null);
                boolean accepted = switch (args[1]) {
                    case "embark" -> args.length == 4 && drop.embarkForSetup(connId, passenger, carrier);
                    case "high" -> args.length == 4 && drop.fromRadar(connId, passenger, carrier, null, true);
                    case "low" -> args.length == 6 && drop.fromRadar(connId, passenger, carrier, hex(args[4], args[5]), false);
                    case "land" -> args.length == 5 && drop.aimHigh(connId, passenger, hex(args[3], args[4]));
                    default -> false;
                };
                if (!accepted) {
                    server.sendServerChat(connId, HELP);
                }
                manager.sendPendingPackets();
                return;
            }
            if ((args.length < 3) || !game.getPhase().isMovement() || (game.getTurn() == null)) {
                server.sendServerChat(connId, HELP);
                return;
            }
            if (args[1].equalsIgnoreCase("unload")) {
                if (args.length == 5) {
                    var passenger = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                    var carrier = game.getFormation(Integer.parseInt(args[3])).orElse(null);
                    String[] xy = args[4].split(",", -1);
                    if ((carrier != null) && (carrier.getPosition() != null) && (xy.length == 2)
                          && SBFBayTransport.unload(manager, connId, passenger, carrier, BoardLocation.of(new Coords(
                                Integer.parseInt(xy[0]) - 1, Integer.parseInt(xy[1]) - 1), carrier.getPosition().boardId()))) {
                        manager.sendPendingPackets();
                        return;
                    }
                }
                server.sendServerChat(connId, HELP);
                return;
            }
            SBFFormation carrier = game.getFormation(Integer.parseInt(args[1])).orElse(null);
            if ((carrier == null) || !game.getTurn().isValid(connId, carrier, game) || (carrier.getPosition() == null)
                  || ((long) args.length > 2L * SBFMovePath.maximumMovementPoints(carrier, game) + 3)) {
                server.sendServerChat(connId, "This carrier cannot take that movement turn.");
                return;
            }
            BoardLocation point = carrier.getPosition();
            SBFMovePath route = new SBFMovePath(carrier.getId(), point, game);
            for (int i = 2; i < args.length; i++) {
                String token = args[i];
                if (token.startsWith("board:") && (i == args.length - 1)) {
                    route.boardCarrierAtEnd(Integer.parseInt(token.substring(6)));
                } else if (token.startsWith("mount:") || token.startsWith("drop:")) {
                    int id = Integer.parseInt(token.substring(token.indexOf(':') + 1));
                    boolean mount = token.startsWith("mount:");
                    route.addTransportOrder(new TransportOrder(route.getSteps().size(), id, mount));
                } else {
                    String[] xy = token.split(",", -1);
                    if (xy.length != 2) {
                        server.sendServerChat(connId, HELP);
                        return;
                    }
                    BoardLocation next = BoardLocation.of(new Coords(Integer.parseInt(xy[0]) - 1,
                          Integer.parseInt(xy[1]) - 1), point.boardId());
                    if (!game.hasBoardLocation(next) || (point.coords().distance(next.coords()) != 1)) {
                        server.sendServerChat(connId, HELP);
                        return;
                    }
                    route.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, carrier.getId(), point, next));
                    point = next;
                }
            }
            if ((route.getTransportOrders().isEmpty() && (route.boardingCarrier() == null))
                  || !manager.movementProcessor.processMovement(route, carrier)) {
                server.sendServerChat(connId, "Invalid transport move. Check passenger, capacity, stacking and MP.");
                return;
            }
            manager.sendPendingPackets();
        } catch (NumberFormatException ex) {
            server.sendServerChat(connId, HELP);
        }
    }
    private static BoardLocation hex(String boardId, String coordinate) {
        String[] xy = coordinate.split(",", -1);
        if (xy.length != 2) {
            throw new NumberFormatException("Expected column,row");
        }
        return BoardLocation.of(new Coords(Integer.parseInt(xy[0]) - 1, Integer.parseInt(xy[1]) - 1), Integer.parseInt(boardId));
    }
}
