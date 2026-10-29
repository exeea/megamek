/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;

/** The checks the lobby makes before the local player and their bots may declare themselves ready. */
public final class ReadyCheck {

    /** What keeps the players from being ready. */
    public enum Reason {
        /** Double blind with exclusive deployment, and two deployment zones overlap. */
        OVERLAPPING_DEPLOYMENT,
        /** Commander killed victory is on, and some of the local players have units but no commander. */
        NO_COMMANDER
    }

    /**
     * Something that keeps the players from being ready.
     *
     * @param reason      what is wrong
     * @param playerNames the players it concerns: those without a commander, none for overlapping deployment
     */
    public record Blocker(Reason reason, List<String> playerNames) {

        /** @return the title to show the blocker under */
        public String title() {
            return Messages.getString((reason == Reason.OVERLAPPING_DEPLOYMENT)
                  ? "ChatLounge.OverlapDeploy.title" : "ChatLounge.noCmdr.title");
        }

        /** @return the message explaining the blocker */
        public String message() {
            if (reason == Reason.OVERLAPPING_DEPLOYMENT) {
                return Messages.getString("ChatLounge.OverlapDeploy.msg");
            }
            StringBuilder message = new StringBuilder(Messages.getString("ChatLounge.noCmdr.msg"));
            for (String player : playerNames) {
                message.append(player).append("\n");
            }
            return message.toString();
        }
    }

    private ReadyCheck() {}

    /**
     * @param game        the game
     * @param localPlayer the local player
     * @param localBots   the players of the local bots
     *
     * @return what keeps the local player and their bots from being ready, most important first; empty when they may
     *       be ready
     */
    public static List<Blocker> blockers(Game game, Player localPlayer, Collection<Player> localBots) {
        List<Blocker> blockers = new ArrayList<>();
        for (Player player : game.getPlayersList()) {
            if (!LobbyRules.isValidStartPos(game, player)) {
                blockers.add(new Blocker(Reason.OVERLAPPING_DEPLOYMENT, List.of()));
                break;
            }
        }

        if (game.getOptions().booleanOption(OptionsConstants.VICTORY_COMMANDER_KILLED)) {
            List<String> players = new ArrayList<>();
            if (lacksCommander(game, localPlayer)) {
                players.add(localPlayer.getName());
            }
            for (Player bot : localBots) {
                if (lacksCommander(game, bot)) {
                    players.add(bot.getName());
                }
            }
            if (!players.isEmpty()) {
                blockers.add(new Blocker(Reason.NO_COMMANDER, players));
            }
        }
        return blockers;
    }

    private static boolean lacksCommander(Game game, Player player) {
        return (game.getLiveCommandersOwnedBy(player) < 1) && (game.getEntitiesOwnedBy(player) > 0);
    }
}
