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

import java.util.function.IntSupplier;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFEngagementOrders;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;
import megamek.common.strategicBattleSystems.SBFVisibilityStatus;
import megamek.common.units.Terrains;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** “Hidden Formations” and “Hiding on the Ground”, IO:BF p. 214. */
final class SBFHiddenCommand extends ServerCommand {
    static final String REVEAL = "Use /sbfhide reveal <formation ID> to act this phase, then /sbfhide done.";
    private static final String HELP = "/sbfhide place <formation ID> <column> <row> [board ID]. "
          + "When an enemy enters your hex, /sbfhide ambush <formation ID> short|medium|hold. " + REVEAL;
    private final SBFGameManager manager;

    SBFHiddenCommand(Server server, SBFGameManager manager) {
        super(server, "sbfhide", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int player, String[] args) {
        SBFGame game = manager.getGame();
        if (args.length == 4 && args[1].equalsIgnoreCase("ambush")) {
            try {
                var choice = SBFEngagementOrders.Ambush.valueOf(args[3].toUpperCase(java.util.Locale.ROOT));
                var control = new SBFEngagementControl(manager);
                if (control.chooseAmbush(player, Integer.parseInt(args[2]), choice)) {
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Show the command usage below.
            }
            server.sendServerChat(player, HELP);
            return;
        }
        if (game.getTurn() == null || game.getTurn().playerId() != player) {
            server.sendServerChat(player, HELP);
            return;
        }
        boolean preparing = game.getPhase() == GamePhase.PREMOVEMENT || game.getPhase() == GamePhase.PRE_FIRING;
        try {
            if (preparing && args.length == 2 && args[1].equalsIgnoreCase("done")) {
                manager.endCurrentTurn(null);
                manager.sendPendingPackets();
                return;
            }
            SBFFormation f = args.length >= 3 ? game.getFormation(Integer.parseInt(args[2])).orElse(null) : null;
            if (f != null && f.getOwnerId() == player) {
                if (preparing && args.length == 3 && args[1].equalsIgnoreCase("reveal") && f.isHidden()) {
                    reveal(manager, f, null);
                    manager.sendPendingPackets();
                    return;
                }
                if (game.getPhase().isDeployment() && game.getCurrentRound() == 0 && (args.length == 5 || args.length == 6)
                      && args[1].equalsIgnoreCase("place")) {
                    var location = BoardLocation.of(new Coords(Integer.parseInt(args[3]) - 1, Integer.parseInt(args[4]) - 1),
                          args.length == 6 ? Integer.parseInt(args[5]) : 0);
                    if (mayPlace(game, f, location) && SBFDeployCommand.place(game, player, f, location)) {
                        f.setHidden(true);
                        manager.sendUnitUpdate(f);
                        manager.endCurrentTurn(f);
                        manager.sendPendingPackets();
                        return;
                    }
                }
            }
        } catch (NumberFormatException ignored) {
            // Invalid coordinates and ineligible formations have the same response.
        }
        server.sendServerChat(player, HELP);
    }

    static boolean mayPlace(SBFGame game, SBFFormation f, BoardLocation location) {
        if (!canHideAt(game, f, location)) {
            return false;
        }
        if (game.getOptions().booleanOption(SBFRuleOptions.BASE_HIDDEN)) {
            return true;
        }
        Player owner = game.getPlayer(f.getOwnerId());
        int side = SBFScenarioProgress.sideId(game, owner);
        return game.getOptions().booleanOption(SBFRuleOptions.INIT_BATTLEFIELD_INT)
              && game.getActiveFormations().stream().filter(SBFFormation::isHidden).filter(other -> !game.areHostile(other, owner)).count()
              < game.scenarioProgress().areaKnowledgeAllowance(side);
    }

    static boolean canHideAt(SBFGame game, SBFFormation f, BoardLocation location) {
        if (location == null || !game.hasBoardLocation(location) || !game.getBoard(location).isGround()
              || f.isOnRadarMap() || f.isType(SBFElementType.MS) || f.isEmbarked()
              || f.getUnits().stream().anyMatch(u -> u.getType() == SBFElementType.MS
                    || u.getElements().stream().anyMatch(e -> e.getASUnitType() == ASUnitType.MS))) {
            return false;
        }
        Hex hex = game.getHex(location);
        if (hex == null || hex.isClear() || hex.hasPavementOrRoad() || hex.hasBridge()) {
            return false;
        }
        if (hex.terrainLevel(Terrains.WATER) > 0) {
            return !f.getUnits().isEmpty() && f.getUnits().stream().allMatch(u -> u.isUnderwater(game, f, location));
        }
        var elements = f.getUnits().stream().flatMap(u -> u.getElements().stream()).toList();
        boolean dropShip = elements.stream().anyMatch(e -> e.getASUnitType() != null && e.isDropShip());
        boolean largeSupport = elements.stream().anyMatch(e -> e.getASUnitType() == ASUnitType.SV
              && e.hasAnySUAOf(BattleForceSUA.LG, BattleForceSUA.VLG, BattleForceSUA.SLG))
              || f.getUnits().stream().anyMatch(u -> u.getElements().isEmpty() && u.getType() == SBFElementType.V
                    && u.hasAnySUAOf(BattleForceSUA.LG, BattleForceSUA.VLG, BattleForceSUA.SLG))
              || (elements.isEmpty() && f.isType(SBFElementType.V)
                    && f.hasAnySUAOf(BattleForceSUA.LG, BattleForceSUA.VLG, BattleForceSUA.SLG));
        if ((!dropShip && !largeSupport) || hex.containsTerrain(Terrains.BUILDING)) {
            return true;
        }
        int height = hex.getLevel() + (dropShip ? 6 : 2);
        return location.coords().allAdjacent().stream().map(game.getBoard(location)::getHex)
              .allMatch(neighbor -> neighbor != null && neighbor.ceiling() > height);
    }

    static void validateSetup(SBFGameManager manager) {
        SBFGame game = manager.getGame();
        for (var f : game.getActiveFormations()) {
            if (f.isHidden() && f.isDeployed() && !canHideAt(game, f, f.getPosition())) {
                f.setHidden(false);
                manager.sendServerChat(f.getOwnerId(), "Formation " + f.getId() + " cannot use hidden deployment in its scenario hex.");
            }
        }
    }

    static void detectOnEntry(SBFGameManager manager, SBFFormation mover, BoardLocation location, IntSupplier dice) {
        SBFGame game = manager.getGame();
        for (SBFFormation hidden : game.getActiveFormationsAt(location)) {
            if (hidden.isHidden() && game.areHostile(hidden, game.getPlayer(mover.getOwnerId()))
                  && (mover.hasSUA(BattleForceSUA.PRB) || dice.getAsInt() + (mover.hasSUA(BattleForceSUA.RCN) ? 1 : 0) >= mover.getSkill() + 3)) {
                reveal(manager, hidden, mover);
            }
        }
    }

    static void reveal(SBFGameManager manager, SBFFormation formation, SBFFormation detector) {
        SBFGame game = manager.getGame();
        formation.setHidden(false);
        if (game.usesDoubleBlind()) {
            for (Player player : game.getPlayersList()) {
                if (game.areHostile(formation, player) && (detector == null || detector.getOwnerId() == player.getId())) {
                    var previous = game.getVisibility(player, formation.getId());
                    game.visibilityHelper().setVisibility(player.getId(), formation.getId(),
                          previous.bestOf(detector == null ? SBFVisibilityStatus.BLIP : SBFVisibilityStatus.EYES_ON_TARGET));
                }
            }
        }
        manager.sendUnitUpdate(formation);
    }
}
