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

import static megamek.common.alphaStrike.BattleForceSUA.COM;
import static megamek.common.alphaStrike.BattleForceSUA.LEAD;
import static megamek.common.alphaStrike.BattleForceSUA.SCOM;

import java.util.List;

import megamek.common.Player;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Setup choices for “Force Commander and Formation Leaders”, IO:BF p. 163. */
final class SBFCommandDesignation extends ServerCommand {
    private static final String HELP = "/sbfcommand com|lead|scom <formation ID> <unit number>, numbered from 1.";
    private final SBFGameManager manager;

    SBFCommandDesignation(Server server, SBFGameManager manager) {
        super(server, "sbfcommand", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int connId, String[] args) {
        SBFGame game = manager.getGame();
        boolean setup = game.getPhase().isLounge() || (game.getCurrentRound() == 0 && game.getPhase().isDeployment())
              || (game.getCurrentRound() == 1 && game.getPhase().isInitiativeReport());
        if (setup && args.length == 4) {
            try {
                SBFFormation formation = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                BattleForceSUA role = args[1].equalsIgnoreCase("com") ? COM
                      : args[1].equalsIgnoreCase("lead") ? LEAD : args[1].equalsIgnoreCase("scom") ? SCOM : null;
                if (designate(game, connId, formation, Integer.parseInt(args[3]) - 1, role)) {
                    manager.entityAllUpdate();
                    if (game.getPhase().isInitiativeReport()) {
                        manager.checkSetupReady();
                    }
                    manager.sendPendingPackets();
                    return;
                }
            } catch (NumberFormatException ignored) {
                // Report the command usage below.
            }
        }
        server.sendServerChat(connId, HELP);
    }

    static boolean designate(SBFGame game, int player, SBFFormation formation, int unitIndex, BattleForceSUA role) {
        if (formation == null || formation.getOwnerId() != player || unitIndex < 0 || unitIndex >= formation.getUnits().size()
              || (role != COM && role != LEAD && role != SCOM)) {
            return false;
        }
        SBFUnit selected = formation.getUnits().get(unitIndex);
        if (role == SCOM) {
            List<SBFFormation> friendly = force(game, player);
            long count = friendly.stream().flatMap(f -> f.getUnits().stream()).filter(u -> u.hasSUA(SCOM) && u != selected).count();
            if (!selected.hasSUA(LEAD) || selected.hasSUA(COM) || count >= Math.max(0, friendly.size() / 3 - 1)) {
                return false;
            }
            selected.getSpecialAbilities().setSUA(SCOM);
            return true;
        }
        List<SBFFormation> affected = role == LEAD ? List.of(formation) : force(game, player);
        for (SBFFormation part : affected) {
            for (SBFUnit unit : part.getUnits()) {
                unit.getSpecialAbilities().removeSUA(role);
                if ((role == LEAD && unit != selected) || (role == COM && unit == selected)) {
                    unit.getSpecialAbilities().removeSUA(SCOM);
                }
            }
            if (role == COM) {
                part.getSpecialAbilities().removeSUA(COM);
            }
        }
        formation.getUnits().get(unitIndex).getSpecialAbilities().setSUA(role);
        if (role == COM) {
            formation.getSpecialAbilities().setSUA(COM);
        }
        return true;
    }

    static boolean ready(SBFGameManager manager) {
        SBFGame game = manager.getGame();
        boolean ready = true;
        for (Player player : SBFScenarioResolution.sides(game)) {
            List<SBFFormation> force = force(game, player.getId());
            List<SBFUnit> units = force.stream().flatMap(formation -> formation.getUnits().stream()).toList();
            if (units.size() == 1 && !units.getFirst().hasSUA(COM)) {
                SBFFormation only = force.stream().filter(formation -> !formation.getUnits().isEmpty()).findFirst().orElseThrow();
                designate(game, only.getOwnerId(), only, 0, COM);
                manager.sendUnitUpdate(only);
            } else if (!units.isEmpty() && units.stream().filter(unit -> unit.hasSUA(COM)).count() != 1) {
                force.stream().map(SBFFormation::getOwnerId).distinct().forEach(owner ->
                      manager.sendServerChat(owner, "Your side must designate one Force Commander. " + HELP));
                ready = false;
            }
            for (SBFFormation formation : force) {
                if (formation.getUnits().size() > 1 && formation.getUnits().stream().filter(unit -> unit.hasSUA(LEAD)).count() != 1) {
                    manager.sendServerChat(formation.getOwnerId(), "Designate one leader for Formation "
                          + formation.getId() + ". " + HELP);
                    ready = false;
                }
            }
        }
        return ready;
    }

    private static List<SBFFormation> force(SBFGame game, int player) {
        Player owner = game.getPlayer(player);
        return game.getActiveFormations().stream().filter(formation -> !game.areHostile(formation, owner)).toList();
    }
}
