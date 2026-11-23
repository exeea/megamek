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

import megamek.common.board.Board;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** A voluntary exit is eliminated for pickup victory and awards no withdrawal VP (IO:BF p. 174). */
final class SBFWithdrawCommand extends ServerCommand {
    private static final String HELP = "/sbfwithdraw <formation ID> withdraws your formation from an eligible map edge.";
    private final SBFGameManager manager;

    SBFWithdrawCommand(Server server, SBFGameManager manager) {
        super(server, "sbfwithdraw", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int connId, String[] args) {
        SBFGame game = manager.getGame();
        try {
            SBFFormation formation = args.length == 2 ? game.getFormation(Integer.parseInt(args[1])).orElse(null) : null;
            if ((formation == null) || !game.getPhase().isMovement() || (game.getTurn() == null)
                  || !game.getTurn().isValid(connId, formation, game) || (formation.getPosition() == null)
                  || (game.engagementOrders().pendingMove() != null)) {
                server.sendServerChat(connId, HELP);
                return;
            }
            var coords = formation.getPosition().coords();
            Board board = game.getBoard(formation.getPosition());
            if ((board == null) || ((coords.getX() > 0) && (coords.getY() > 0)
                  && (coords.getX() < board.getWidth() - 1) && (coords.getY() < board.getHeight() - 1))
                  || !game.getPlayer(connId).getFleeZone().containsCoords(coords, board)
                  || (formation.mustWithdraw() && !SBFWithdrawal.atHomeEdge(game, formation))) {
                server.sendServerChat(connId, "The formation has not reached an eligible exit.");
                return;
            }
            SBFWithdrawal.leaveMap(manager, formation, formation.mustWithdraw());
            manager.endCurrentTurn(formation);
            manager.sendPendingPackets();
        } catch (NumberFormatException ex) {
            server.sendServerChat(connId, HELP);
        }
    }
}
