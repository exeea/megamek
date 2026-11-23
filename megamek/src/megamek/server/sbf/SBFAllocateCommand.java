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
import java.util.List;

import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.Allocation;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Lets the winning player of an opposed Tactics check allocate the exchange's successful hits (IO:BF p. 170). */
final class SBFAllocateCommand extends ServerCommand {
    private final SBFGameManager manager;

    SBFAllocateCommand(Server server, SBFGameManager manager) {
        super(server, "sbfallocate", "/sbfallocate lists your pending damage choices. "
              + "/sbfallocate <exchange number> <Unit number> ... assigns one Unit to each hit, in order.");
        this.manager = manager;
    }

    @Override
    public void run(int connId, String[] args) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isFiringReport()) {
            server.sendServerChat(connId, "Damage allocation takes place in the firing report.");
            return;
        }
        if (args.length == 1) {
            showChoices(connId, game);
            return;
        }
        if (args.length < 3) {
            server.sendServerChat(connId, getHelp());
            return;
        }
        try {
            int exchange = Integer.parseInt(args[1]) - 1;
            List<Allocation> queue = game.getDamageAllocations();
            if ((exchange < 0) || (exchange >= queue.size()) || (queue.get(exchange).chooserId() != connId)) {
                server.sendServerChat(connId, "Use /sbfallocate to list your current exchanges.");
                return;
            }
            Allocation first = queue.get(exchange);
            List<Integer> units = new ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                units.add(Integer.parseInt(args[i]) - 1);
            }
            int previousReports = manager.getPendingReports().size();
            if (SBFCombatDamage.allocate(manager, connId, first.attackerId(), first.targetId(), units)) {
                server.sendServerChat(connId, "Damage allocation accepted.");
                manager.finishDamageAllocation(previousReports);
            } else {
                server.sendServerChat(connId, "Allocation refused. Check the hit count and Unit numbers with "
                      + "/sbfallocate. Each Unit may take two hits before every other Unit has taken two.");
            }
        } catch (NumberFormatException ex) {
            server.sendServerChat(connId, getHelp());
        }
    }

    private void showChoices(int playerId, SBFGame game) {
        List<Allocation> pending = game.getDamageAllocations().stream()
              .filter(hit -> (hit.chooserId() == playerId) && (hit.unitIndex() < 0)).toList();
        if (pending.isEmpty()) {
            server.sendServerChat(playerId, "You have no damage to allocate.");
            return;
        }
        List<Allocation> remaining = new ArrayList<>(pending);
        while (!remaining.isEmpty()) {
            Allocation first = remaining.getFirst();
            List<Integer> damage = remaining.stream().filter(hit -> hit.isFor(first.attackerId(), first.targetId()))
                  .map(Allocation::damage).toList();
            var target = game.getFormation(first.targetId()).orElseThrow();
            List<String> units = new ArrayList<>();
            for (int index = 0; index < target.getUnits().size(); index++) {
                var unit = target.getUnits().get(index);
                units.add((index + 1) + " " + unit.getName() + " (" + unit.getCurrentArmor() + " armor)");
            }
            server.sendServerChat(playerId, "/sbfallocate " + (game.getDamageAllocations().indexOf(first) + 1)
                  + " <Unit number per hit>. Hit damage in order: " + damage + ". " + target.generalName()
                  + " Units: " + String.join(", ", units));
            remaining.removeIf(hit -> hit.isFor(first.attackerId(), first.targetId()));
        }
    }
}
