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
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.Declaration;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.IntelBonus;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SBFUnit.InitiativeAbility;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Place BI tokens and declare special command abilities before the next turn's initiative roll. */
final class SBFInitiativeCommand extends ServerCommand {
    private static final String HELP = "/sbfinit [normal|yield|spend|spend_two|force]; "
          + "/sbfinit hq|recon|network <formation ID> <unit number> places a BI token. "
          + "/sbfinit plot|unplot <x> <y> [board ID] designates an artillery hex. "
          + "During setup, /sbfinit banking|forcing <formation ID> <unit number> designates its commander.";
    private final SBFGameManager manager;

    SBFInitiativeCommand(Server server, SBFGameManager manager) {
        super(server, "sbfinit", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int connId, String[] args) {
        SBFGame game = manager.getGame();
        Player owner = game.getPlayer(connId);
        if (owner == null || owner.isObserver()) {
            return;
        }
        var force = new SBFInitiativeModifiers(game, owner);
        String reply = HELP;
        if (args.length == 1) {
            reply = "Banked points: " + force.orders.bankedPoints() + ". Next declaration: " + force.orders.declaration()
                  + ". " + HELP;
        } else {
            try {
                String operation = args[1].toUpperCase(Locale.ROOT);
                if (args.length == 2 && declare(game, owner, force, Declaration.valueOf(operation))) {
                    reply = "Next initiative declaration: " + operation;
                } else if ((args.length == 4 || args.length == 5) && (operation.equals("PLOT") || operation.equals("UNPLOT"))) {
                    BoardLocation hex = BoardLocation.of(new Coords(Integer.parseInt(args[2]) - 1, Integer.parseInt(args[3]) - 1),
                          args.length == 5 ? Integer.parseInt(args[4]) : 0);
                    if (preplot(game, owner, hex, operation.equals("UNPLOT"))) {
                        reply = "Pre-plotted hexes for your side: " + force.orders.preplots();
                    }
                } else if (args.length == 4) {
                    var formation = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                    int index = Integer.parseInt(args[3]) - 1;
                    if (select(game, owner, force, operation, formation, index)) {
                        reply = "Recorded " + operation + " on " + formation.generalName() + ", Unit " + (index + 1) + ".";
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid command or Unit index; display help.
            }
        }
        server.sendServerChat(connId, reply);
    }

    static boolean preplot(SBFGame game, Player owner, BoardLocation hex, boolean remove) {
        if (!setup(game) || !game.getOptions().booleanOption(SBFRuleOptions.INIT_BATTLEFIELD_INT)
              || !game.getOptions().booleanOption(SBFRuleOptions.ADVANCED_ARTILLERY) || game.getHex(hex) == null) {
            return false;
        }
        int side = SBFScenarioProgress.sideId(game, owner);
        int allowance = SBFScenarioProgress.preplotAllowance(SBFInitiativeModifiers.initialSurvey(game), side);
        return game.scenarioProgress().initiativeOrders(side).selectPreplot(hex, allowance, remove);
    }

    private static boolean setup(SBFGame game) {
        return game.getPhase().isLounge() || game.getPhase() == GamePhase.STARTING_SCENARIO || (game.getCurrentRound() == 0 && game.getPhase().isDeployment());
    }

    static boolean declare(SBFGame game, Player player, SBFInitiativeModifiers force, Declaration declaration) {
        // The side's senior commander submits the common order. If lost, surviving designated LEADs can command.
        var commanders = game.getActiveFormations().stream().filter(f -> !game.areHostile(f, player))
              .filter(f -> f.getUnits().stream().anyMatch(u -> u.getCurrentArmor() > 0 && u.hasSUA(BattleForceSUA.COM))).toList();
        boolean controls = commanders.isEmpty() ? game.getActiveFormations().stream().anyMatch(f -> f.getOwnerId() == player.getId()
              && f.getUnits().stream().anyMatch(u -> u.getCurrentArmor() > 0 && u.hasSUA(BattleForceSUA.SCOM))) : commanders.stream().anyMatch(f -> f.getOwnerId() == player.getId());
        if (!controls || game.getPhase().isVictory()) {
            return false;
        }
        boolean allowed = switch (declaration) {
            case NORMAL -> true;
            case FORCE -> game.getCurrentRound() >= 1 && force.hasAbility(InitiativeAbility.FORCING);
            case YIELD, SPEND, SPEND_TWO -> force.hasAbility(InitiativeAbility.BANKING)
                  && game.getTeams().stream().filter(t -> !t.isObserverTeam()).count() == 2
                  && (declaration == Declaration.YIELD || force.orders.bankedPoints() >= (declaration == Declaration.SPEND_TWO ? 2 : 1));
        };
        if (allowed) {
            force.orders.declare(declaration);
        }
        return allowed;
    }

    static boolean select(SBFGame game, Player owner, SBFInitiativeModifiers force, String operation,
          SBFFormation formation, int index) {
        if (formation == null || formation.getOwnerId() != owner.getId() || index < 0 || index >= formation.getUnits().size()
              || game.getPhase().isVictory()) {
            return false;
        }
        SBFUnit unit = formation.getUnits().get(index);
        if (unit.getCurrentArmor() <= 0) {
            return false;
        }
        if (operation.equals("BANKING") || operation.equals("FORCING")) {
            if (!setup(game) || !unit.hasAnySUAOf(BattleForceSUA.COM, BattleForceSUA.SCOM)) {
                return false;
            }
            InitiativeAbility ability = InitiativeAbility.valueOf(operation);
            if (!game.getOptions().booleanOption(ability == InitiativeAbility.BANKING ? SBFRuleOptions.INIT_BANKING
                  : SBFRuleOptions.INIT_FORCING)) {
                return false;
            }
            unit.getInitiativeAbilities().add(ability);
            return true;
        }
        // Placement is fixed once the battle begins, including if its carrier subsequently dies or is jammed.
        if (setup(game) && game.getOptions().booleanOption(SBFRuleOptions.INIT_BATTLEFIELD_INT)) {
            IntelBonus token = IntelBonus.valueOf(operation);
            if (SBFInitiativeModifiers.canCarry(game, token, unit)) {
                force.orders.assign(token, formation.getId(), unit);
                return true;
            }
        }
        return false;
    }
}
