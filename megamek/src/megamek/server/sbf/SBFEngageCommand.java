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
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Order;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Private standing orders for standard Engagement Control. */
class SBFEngageCommand extends ServerCommand {
    private static final String HELP = "/sbfengage <your formation ID> engage|avoid|ask|force|overrun|evade [opponent ID] "
          + "sets your choice for contact, a winning Engagement Control Roll and the End Phase.";
    private final SBFGameManager manager;

    SBFEngageCommand(Server server, SBFGameManager manager) {
        super(server, "sbfengage", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int connId, String[] args) {
        if ((args.length == 5) && (args[2].equalsIgnoreCase("fire") || args[2].equalsIgnoreCase("hit"))) {
            try {
                new SBFEngagementControl(manager).selectUnit(connId, Integer.parseInt(args[1]),
                      Integer.parseInt(args[3]), args[2].equalsIgnoreCase("fire"), Integer.parseInt(args[4]));
            } catch (NumberFormatException ex) {
                server.sendServerChat(connId, HELP);
            }
            return;
        }
        if ((args.length < 3) || (args.length > 4)) {
            server.sendServerChat(connId, HELP);
            return;
        }
        try {
            int own = Integer.parseInt(args[1]);
            int opponent = args.length == 4 ? Integer.parseInt(args[3]) : Player.PLAYER_NONE;
            if (args[2].equalsIgnoreCase("next") && (args.length == 4)) {
                new SBFEngagementControl(manager).chooseEnd(connId, own, opponent);
                return;
            }
            Order order = Order.valueOf(args[2].toUpperCase(Locale.ROOT));
            new SBFEngagementControl(manager).submitOrder(connId, own, opponent, order);
        } catch (IllegalArgumentException ex) {
            server.sendServerChat(connId, HELP);
        }
    }
}
