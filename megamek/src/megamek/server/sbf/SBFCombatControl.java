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

import megamek.common.Player;
import megamek.common.alphaStrike.ASRange;
import megamek.common.compute.Compute;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.strategicBattleSystems.SBFCombatOrders;
import megamek.common.strategicBattleSystems.SBFCombatOrders.Maneuver;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;

/** Resolves and publishes Maneuver and optional occupant-controlled LOS decisions (IO:BF p. 169). */
record SBFCombatControl(SBFGameManager manager, IntSupplier roll2d6) {
    SBFCombatControl(SBFGameManager manager) {
        this(manager, () -> Compute.d6(2));
    }

    void startFiring() {
        SBFGame game = manager.getGame();
        for (SBFFormation first : game.getActiveFormations()) {
            for (int id : first.getEngagedOpponents()) {
                SBFFormation second = game.getFormation(id).orElse(null);
                if ((second != null) && (first.getId() < id) && second.isEngagedWith(first.getId())
                      && SBFCombatOrders.sameEngagementArea(game, first, second)
                      && game.combatOrders().maneuver(first.getId(), id).isEmpty()) {
                    game.combatOrders().setManeuver(rollManeuver(first, second));
                }
            }
        }
        for (Player player : game.getPlayersList()) {
            remind(player);
            sendOrders(player);
        }
    }

    Maneuver rollManeuver(SBFFormation first, SBFFormation second) {
        boolean inAir = first.sharesAerospaceMap(second);
        int firstTN = first.getTactics() - (!inAir && (first.getMpSpentThisTurn() < first.getMovement()) ? 1 : 0);
        int secondTN = second.getTactics() - (!inAir && (second.getMpSpentThisTurn() < second.getMovement()) ? 1 : 0);
        while (true) {
            int a = roll2d6.getAsInt();
            int b = roll2d6.getAsInt();
            int firstMargin = a - firstTN;
            int secondMargin = b - secondTN;
            boolean firstSucceeded = (a != 2) && (firstMargin >= 0);
            boolean secondSucceeded = (b != 2) && (secondMargin >= 0);
            String rolls = "Maneuver " + first.getId() + " rolled " + a + " against " + firstTN + ", "
                  + second.getId() + " rolled " + b + " against " + secondTN + ". ";
            if (!firstSucceeded && !secondSucceeded) {
                ASRange range = SBFCombatOrders.limitTerrainRange(manager.getGame(), first, second, ASRange.LONG);
                tellPair(first, second, rolls + "Both failed; range is " + range + ".");
                return new Maneuver(first.getId(), second.getId(), -1, -1, range);
            }
            int comparison = Boolean.compare(firstSucceeded, secondSucceeded);
            if (comparison == 0) {
                comparison = Integer.compare(firstMargin, secondMargin);
                if (comparison == 0) {
                    comparison = inAir ? Integer.compare(first.getCurrentThrust(), second.getCurrentThrust())
                          : Integer.compare(first.getMovement(), second.getMovement());
                }
            }
            if (comparison == 0) {
                tellPair(first, second, rolls + "Tied margin and MP. Rerolling.");
                continue;
            }
            int winner = comparison > 0 ? first.getId() : second.getId();
            int outmaneuvered = firstSucceeded == secondSucceeded ? -1 : (firstSucceeded ? second.getId() : first.getId());
            tellPair(first, second, rolls + "Formation " + winner + " chooses range."
                  + (outmaneuvered < 0 ? "" : " Formation " + outmaneuvered + " was outmaneuvered."));
            return new Maneuver(first.getId(), second.getId(), winner, outmaneuvered, null);
        }
    }

    private void tellPair(SBFFormation first, SBFFormation second, String message) {
        SBFGame game = manager.getGame();
        if (game.isVisible(first.getOwnerId(), second.getId())) {
            manager.sendServerChat(first.getOwnerId(), message);
        }
        if (game.isVisible(second.getOwnerId(), first.getId())) {
            manager.sendServerChat(second.getOwnerId(), message);
        }
    }

    void listContacts(Player player) {
        SBFGame game = manager.getGame();
        for (SBFFormation formation : game.getActiveFormations()) {
            if (game.areHostile(formation, player) && game.getVisibility(player, formation.getId()).isLocated()) {
                String location = formation.isOnRadarMap() ? String.valueOf(formation.getRadarZone())
                      : formation.getPosition() == null ? null : formation.getPosition().coords().getBoardNum();
                if (location != null) {
                    manager.sendServerChat(player.getId(), "Contact " + formation.getId() + " at " + location + ".");
                }
            }
        }
    }

    void remind(Player player) {
        SBFGame game = manager.getGame();
        for (SBFFormation own : game.getActiveFormations()) {
            if ((own.getOwnerId() != player.getId()) || ((own.getPosition() == null) && own.combatZone() == null)) {
                continue;
            }
            for (SBFFormation other : game.getActiveFormations()) {
                if (!game.getVisibility(player, other.getId()).isLocated() || !game.areHostile(other, player)) {
                    continue;
                }
                var maneuver = game.combatOrders().maneuver(own.getId(), other.getId());
                if (maneuver.isPresent() && (maneuver.get().winner() == own.getId()) && (maneuver.get().range() == null)) {
                    manager.sendServerChat(player.getId(), "Choose the engagement range with /sbfcombat range "
                          + own.getId() + " " + other.getId() + (own.combatZone() != null ? " short|medium|long|extreme."
                                : " short|medium|long."));
                }
                if (SBFCombatOrders.controlsSight(game, own, other) && !game.combatOrders().hasSightDecision(own, other)) {
                    manager.sendServerChat(player.getId(), "You control line of sight. Use /sbfcombat sight "
                          + own.getId() + " " + other.getId() + " open|block. Firing establishes line of sight.");
                }
            }
        }
    }

    void sendOrders(Player player) {
        SBFGame game = manager.getGame();
        manager.send(player.getId(), new Packet(PacketCommand.SBF_COMBAT_ORDERS,
              game.combatOrders().visibleTo(id -> game.getVisibility(player, id).isLocated(),
                    id -> game.isVisible(player.getId(), id))));
    }

    void publish() {
        manager.getGame().getPlayersList().forEach(this::sendOrders);
    }

    /** Spot one target in the ground spotting hex or within aerial reconnaissance range (IO:BF pp. 169, 196). */
    boolean spot(int playerId, int spotterId, int targetId) {
        SBFGame game = manager.getGame();
        var spotter = game.getFormation(spotterId).orElse(null);
        var target = game.getFormation(targetId).orElse(null);
        if (!game.getPhase().isFiring() || (spotter == null) || (target == null) || (spotter.getOwnerId() != playerId)
              || spotter.unpackingCargo(game.getCurrentRound()) || !spotter.isDeployed() || !target.isDeployed() || spotter.isEmbarked() || target.isEmbarked()
              || target.isOnRadarMap() || spotter.hasSprintedThisTurn()
              || !game.getVisibility(game.getPlayer(playerId), targetId).isLocated() || !game.areHostile(target, game.getPlayer(playerId))
              || game.combatOrders().spottedTarget(spotterId).filter(id -> id != targetId).isPresent()) {
            return false;
        }
        if (spotter.isOnRadarMap()) {
            if (!spotter.getEngagedOpponents().isEmpty() || !SBFDetectionHelper.inAerialVisualRange(game, spotter, target)
                  || game.getActionsVector().stream().anyMatch(action -> action.getEntityId() == spotterId)) {
                return false;
            }
        } else if ((spotter.getPosition() == null) || !spotter.getPosition().equals(target.getPosition())) {
            return false;
        }
        game.combatOrders().setSpotter(spotterId, targetId);
        publish();
        manager.sendPendingPackets();
        return true;
    }

    boolean choose(int player, int firstId, int otherId, String action, String value) {
        SBFGame game = manager.getGame();
        SBFFormation own = game.getFormation(firstId).orElse(null);
        SBFFormation other = game.getFormation(otherId).orElse(null);
        if (!game.getPhase().isFiring() || (own == null) || (other == null) || (own.getOwnerId() != player)
              || !game.getVisibility(game.getPlayer(player), otherId).isLocated() || !game.areHostile(other, game.getPlayer(player))) {
            return false;
        }
        if (action.equals("range")) {
            var result = game.combatOrders().maneuver(firstId, otherId).orElse(null);
            ASRange range = switch (value) {
                case "short" -> ASRange.SHORT;
                case "medium" -> ASRange.MEDIUM;
                case "long" -> ASRange.LONG;
                case "extreme" -> own.combatZone() != null ? ASRange.EXTREME : null;
                default -> null;
            };
            if ((result == null) || (result.winner() != firstId) || (result.range() != null) || (range == null)
                  || range != SBFCombatOrders.limitTerrainRange(game, own, other, range)) {
                return false;
            }
            game.combatOrders().setManeuver(new Maneuver(result.first(), result.second(), firstId,
                  result.outmaneuvered(), range));
        } else if (action.equals("sight") && SBFCombatOrders.controlsSight(game, own, other)
              && (value.equals("open") || value.equals("block"))) {
            if (!game.combatOrders().setSight(own, other, value.equals("open"), false)) {
                return false;
            }
        } else {
            return false;
        }
        for (SBFFormation participant : new SBFFormation[] {own, other}) {
            var recipient = game.getPlayer(participant.getOwnerId());
            int opponent = participant == own ? otherId : firstId;
            if (game.getVisibility(recipient, opponent).isLocated()) {
                manager.sendServerChat(recipient.getId(), "Formation " + firstId + " chose " + action + " " + value
                      + " against contact " + otherId + ".");
            }
        }
        publish();
        manager.sendPendingPackets();
        return true;
    }
}
