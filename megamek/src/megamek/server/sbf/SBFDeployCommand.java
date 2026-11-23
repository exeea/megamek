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

import java.util.Locale;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFPlayerTurn;
import megamek.common.strategicBattleSystems.SBFTurn;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Alternating setup within deployment zones, IO:BF “Deployment”, p. 163. */
final class SBFDeployCommand extends ServerCommand {
    static final String ORDER = "Choose setup order with /sbfdeploy first|second [north|south|east|west].";
    static final String PLACE = "For an airborne Squadron use /sbfaero deploy <formation ID>. "
          + "Place a ground formation with /sbfdeploy <formation ID> <column> <row> [board ID]. "
          + "For an unassigned zone, /sbfdeploy edge north|south|east|west selects a home edge.";
    private final SBFGameManager manager;

    SBFDeployCommand(Server server, SBFGameManager manager) {
        super(server, "sbfdeploy", ORDER + " " + PLACE + " Hex numbers start at 1.");
        this.manager = manager;
    }

    @Override
    public void run(int connId, String[] args) {
        SBFGame game = manager.getGame();
        SBFTurn turn = game.getTurn();
        if (!game.getPhase().isDeployment() || turn == null || turn.playerId() != connId) {
            server.sendServerChat(connId, "It is not your deployment turn.");
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("edge")) {
            if (game.getCurrentRound() == 0 && chooseSideEdge(game, connId, args[2])) {
                manager.transmitAllPlayerUpdates();
                server.sendServerChat(connId, "Deployment edge set. " + PLACE);
            } else {
                server.sendServerChat(connId, "A configured zone already exists or this edge is unavailable.");
            }
            return;
        }
        if (turn instanceof SBFPlayerTurn) {
            if (args.length < 2 || args.length > 3 || !(args[1].equalsIgnoreCase("first") || args[1].equalsIgnoreCase("second"))
                  || (args.length == 3 && !chooseSideEdge(game, connId, args[2]))) {
                server.sendServerChat(connId, ORDER);
                return;
            }
            if (args.length == 3) {
                manager.transmitAllPlayerUpdates();
            }
            game.setTurns(manager.initiativeHelper.formationTurns(GamePhase.DEPLOYMENT,
                  args[1].equalsIgnoreCase("first")));
            game.resetTurnIndex();
            manager.sendCurrentTurns();
            manager.endCurrentTurn(null);
        } else {
            if (args.length != 4 && args.length != 5) {
                server.sendServerChat(connId, PLACE);
                return;
            }
            try {
                BoardLocation hex = BoardLocation.of(new Coords(Integer.parseInt(args[2]) - 1,
                      Integer.parseInt(args[3]) - 1), args.length == 5 ? Integer.parseInt(args[4]) : 0);
                SBFFormation formation = game.getFormation(Integer.parseInt(args[1])).orElse(null);
                if (!place(game, connId, formation, hex)) {
                    server.sendServerChat(connId, "That formation or deployment hex is ineligible.");
                    return;
                }
                manager.unitUpdateHelper.sendUnitUpdate(formation);
                manager.endCurrentTurn(formation);
            } catch (NumberFormatException exception) {
                server.sendServerChat(connId, PLACE);
                return;
            }
        }
        manager.sendPendingPackets();
    }

    static boolean chooseSideEdge(SBFGame game, int owner, String name) {
        Player player = game.getPlayer(owner);
        if (!chooseEdge(player, name)) {
            return false;
        }
        game.getPlayersList().stream().filter(member -> !player.isEnemyOf(member) && member.getStartingPos() == Board.START_ANY)
              .forEach(member -> member.setStartingPos(player.getStartingPos()));
        return true;
    }

    private static boolean chooseEdge(Player player, String name) {
        int edge = switch (name.toLowerCase(Locale.ROOT)) {
            case "north" -> Board.START_N;
            case "south" -> Board.START_S;
            case "west" -> Board.START_W;
            case "east" -> Board.START_E;
            default -> Board.START_NONE;
        };
        if (edge == Board.START_NONE || player.getStartingPos() != Board.START_ANY) {
            return false;
        }
        player.setStartingPos(edge);
        return true;
    }

    static boolean place(SBFGame game, int player, SBFFormation formation, BoardLocation location) {
        SBFTurn turn = game.getTurn();
        Board board = game.getBoard(location);
        if (!game.getPhase().isDeployment() || formation == null || turn == null
              || !turn.isValid(player, formation, game) || board == null || !board.isGround()
              || !board.contains(location.coords()) || !board.isLegalDeployment(location.coords(), game.getPlayer(player))
              || !SBFMovePath.canOccupyTerrain(game, formation, location)
              || SBFMovePath.exceedsStackingLimit(game, formation, location)) {
            return false;
        }
        formation.setPosition(location);
        formation.setDeployed(true);
        return true;
    }
}
