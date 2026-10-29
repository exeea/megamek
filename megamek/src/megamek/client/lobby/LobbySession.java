/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Map;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.common.Player;
import megamek.common.game.Game;

/**
 * What the lobby's commands act through: the local client, its game and player, and the bots running on this machine.
 * The client is asked for each time, so a session can be created before its client exists.
 */
@FunctionalInterface
public interface LobbySession {

    /** @return the local player's client */
    Client client();

    /** @return the game as the local client sees it */
    default Game game() {
        return client().getGame();
    }

    /** @return the local player */
    default Player localPlayer() {
        return client().getLocalPlayer();
    }

    /** @return the bots running on this machine by player name; the map is the client's own, not a copy */
    default Map<String, AbstractClient> localBots() {
        return client().getBots();
    }
}
