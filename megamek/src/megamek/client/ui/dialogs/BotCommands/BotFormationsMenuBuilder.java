/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.BotCommands;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;

import jakarta.annotation.Nullable;
import megamek.client.AbstractClient;
import megamek.client.ui.enums.DialogResult;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.orders.FormationOrder;
import megamek.common.orders.FormationShape;
import megamek.common.orders.LanceRole;
import megamek.common.orders.LanceRoles;
import megamek.common.orders.UnitOrderAction;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;
import megamek.server.commands.UnitOrderCommand;

/**
 * Sets a bot lance's formation and role from the lobby's unit menu, so the lance starts the game in formation. In game, formations
 * are set in the Move Order editor.
 *
 * <p>Every member carries the formation in its own orders, with its slot: the leader is slot 0 and the others follow
 * in the lance's order.</p>
 */
public final class BotFormationsMenuBuilder {

    private static final MMLogger LOGGER = MMLogger.create(BotFormationsMenuBuilder.class);

    private BotFormationsMenuBuilder() {
    }

    /**
     * Puts a bot lance in formation, as the lobby's Formation menu does: spacing
     * {@link FormationOrder#DEFAULT_SPACING}, the lance's first unit leading and keeping together.
     *
     * @param client  the client that sends the orders
     * @param lance   the lance
     * @param unitIds the lance's units, in lance order
     * @param shape   the formation's shape
     */
    public static void sendFormation(AbstractClient client, Force lance, List<Integer> unitIds,
          FormationShape shape) {
        for (int slot = 0; slot < unitIds.size(); slot++) {
            client.sendChat(UnitOrderCommand.commandText(unitIds.get(slot), UnitOrderAction.FORMATION,
                  UnitOrderCommand.SHAPE + '=' + shape.name(),
                  UnitOrderCommand.LEADER + '=' + unitIds.get(0),
                  UnitOrderCommand.SPACING + '=' + FormationOrder.DEFAULT_SPACING,
                  UnitOrderCommand.SLOT + '=' + slot,
                  UnitOrderCommand.TOGETHER + "=true"));
        }
        LOGGER.info("[BotOrders] lobby formation {} for {} ({} units)", shape, lance.getName(), unitIds.size());
    }

    /** Takes a bot lance's units out of formation, as the lobby's Formation off does. */
    public static void sendFormationOff(AbstractClient client, List<Integer> unitIds) {
        for (int unitId : unitIds) {
            client.sendChat(UnitOrderCommand.commandText(unitId, UnitOrderAction.FORMATION_OFF));
        }
    }

    /**
     * Sets a bot lance's role as the lobby's Role menu does: {@code null} clears it, and a convoy or escort role opens
     * the Role dialog on that choice first.
     *
     * @param convoy {@code null} for None, else whether the lance is to be a convoy ({@code true}) or an escort
     */
    public static void chooseLobbyRole(AbstractClient client, JFrame frame, Game game, Player owner, Force lance,
          List<Integer> unitIds, @Nullable Boolean convoy) {
        List<Entity> units = new ArrayList<>();
        for (int unitId : unitIds) {
            Entity unit = game.getEntity(unitId);
            if (unit != null) {
                units.add(unit);
            }
        }
        if (convoy == null) {
            sendRole(client, lance, units, null);
            return;
        }
        LanceRole current = LanceRoles.roleOf(units);
        OffBoardDirection defaultExitEdge = LanceRoles.defaultExitEdge(units.isEmpty() ? null : units.get(0));
        if (convoy) {
            chooseRole(client, frame, game, lance, units, List.of(), defaultExitEdge,
                  ((current != null) && current.isConvoy()) ? current : LanceRole.convoy(defaultExitEdge));
            return;
        }
        List<LanceRoles.ConvoyChoice> convoys = LanceRoles.convoyChoices(game, owner, lance.getId());
        if (!convoys.isEmpty()) {
            chooseRole(client, frame, game, lance, units, convoys, defaultExitEdge,
                  ((current != null) && current.isEscort()) ? current
                        : LanceRole.defaultEscort(convoys.get(0).forceId()));
        }
    }

    private static void chooseRole(AbstractClient client, JFrame frame, Game game, Force lance, List<Entity> units,
          List<LanceRoles.ConvoyChoice> convoys, OffBoardDirection defaultExitEdge, LanceRole shown) {
        LanceRoleDialog dialog = new LanceRoleDialog(frame, lance.getName(), convoys, defaultExitEdge, shown);
        if (dialog.showDialog() != DialogResult.CONFIRMED) {
            return;
        }
        if (dialog.lanceToMakeConvoy().isPresent()) {
            // the lance given an escort that was not a convoy yet becomes one
            LanceRoles.ConvoyChoice convoy = dialog.lanceToMakeConvoy().get();
            for (String command : MoveOrderCommands.makeConvoyCommands(LanceRoles.unitsOf(game, convoy.forceId()),
                  convoy.newExitEdge())) {
                client.sendChat(command);
            }
            LOGGER.info("[BotOrders] lobby: {} made a convoy, leaving by the {} edge, to be escorted by {}",
                  convoy.name(), convoy.newExitEdge(), lance.getName());
        }
        sendRole(client, lance, units, dialog.getRole());
    }

    private static void sendRole(AbstractClient client, Force lance, List<Entity> units, @Nullable LanceRole role) {
        for (String command : MoveOrderCommands.roleCommands(units, role)) {
            client.sendChat(command);
        }
        List<String> columnCommands = MoveOrderCommands.convoyColumnCommands(units, role);
        for (String command : columnCommands) {
            client.sendChat(command);
        }
        LOGGER.info("[BotOrders] lobby role {} for {} ({} units){}", (role == null) ? LanceRole.NONE_TEXT : role,
              lance.getName(), units.size(), columnCommands.isEmpty() ? "" : ", put in a Column to travel");
    }
}
