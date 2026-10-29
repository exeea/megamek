/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import jakarta.annotation.Nullable;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbySession;
import megamek.common.Player;
import megamek.common.icons.Camouflage;
import megamek.common.units.Entity;

/**
 * The native lobby's player actions, on the Swing thread where the game is, as the Swing lobby's player list and its
 * menu run them: player settings, camouflage, team and deployment area for the local player and their bots, the Game
 * Master role, and adding, setting up, replacing and removing bots. Every action takes a player id from the view and
 * checks that the local player may act on that player, so a stale view cannot act on a player who has changed. The
 * Swing dialogs, and making a bot, come from {@link Dialogs}; they need no OpenGL.
 */
final class LobbyPlayerController {

    /** The Swing dialogs the actions ask through, owned by the client's frame, and the making of bots. */
    interface Dialogs {
        /** Opens the player settings for the player {@code client} plays, as the Swing lobby does. */
        void playerSettings(Client client);

        /** Asks for a camouflage for a player now wearing {@code initial} and owning {@code units}; empty: cancelled. */
        Optional<Camouflage> camouflage(Camouflage initial, List<Entity> units);

        /**
         * Asks for a bot's setup: its name ({@code name} fixed if not null), its kind ({@code type} fixed if not null)
         * and its behaviour, starting from {@code behavior}; empty when cancelled.
         */
        Optional<BotSetup> botSetup(@Nullable String name, BehaviorSettings behavior, @Nullable AIType type);

        /** Makes and connects the bot {@code setup} describes; null, after telling the player, when it fails. */
        @Nullable AbstractClient connectBot(BotSetup setup);

        /** Runs {@code step} now and then on this thread until it returns true, for a while at most. */
        void poll(BooleanSupplier step);

        /** The behaviour a new bot starts from: the player's favourite. */
        BehaviorSettings favouriteBehavior();
    }

    /** A bot as its setup dialog left it. */
    record BotSetup(String name, AIType type, BehaviorSettings behavior) { }

    private final LobbySession session;
    private final LobbyCommands commands;
    private final Dialogs dialogs;

    LobbyPlayerController(LobbySession session, LobbyCommands commands, Dialogs dialogs) {
        this.session = session;
        this.commands = commands;
        this.dialogs = dialogs;
    }

    /** The player {@code id} when the local player may configure them: themselves or one of their bots; or null. */
    private @Nullable Player configurable(int id) {
        Player player = session.game().getPlayer(id);
        return player != null && commands.isSelfOrLocalBot(player) ? player : null;
    }

    /** Opens the player settings of player {@code id}, if they are the local player or one of their bots. */
    void playerSettings(int id) {
        Player player = configurable(id);
        if (player != null) {
            dialogs.playerSettings(commands.clientFor(player));
        }
    }

    /** Asks for a new camouflage for player {@code id}, if they are the local player or one of their bots. */
    void camouflage(int id) {
        Player player = configurable(id);
        if (player == null) {
            return;
        }
        Client client = commands.clientFor(player);
        dialogs.camouflage(player.getCamouflage(), session.game().getPlayerEntities(player, false))
              .ifPresent(camo -> commands.changePlayerCamo(client, camo));
    }

    /** Moves player {@code id} to {@code team}, if they are the local player or one of their bots. */
    void changeTeam(int id, int team) {
        Player player = configurable(id);
        if (player != null) {
            commands.changeTeam(List.of(player), team);
        }
    }

    /** Sets the deployment area of player {@code id} to {@code position}, as the Swing lobby's menu does. */
    void changeDeployment(int id, int position) {
        Player player = configurable(id);
        if (player != null) {
            commands.changeStartingPosition(List.of(player), position);
        }
    }

    /** Asks for the Game Master role, or gives it up. */
    void toggleGameMaster() {
        commands.toggleGameMaster();
    }

    /** Opens the setup of the local bot {@code id} and gives it the behaviour chosen there. */
    void botSettings(int id) {
        Player player = session.game().getPlayer(id);
        if (player == null || !(commands.localBotClient(player) instanceof BotClient bot)) {
            return;
        }
        dialogs.botSetup(bot.getLocalPlayer().getName(), bot.getBehaviorSettings(), bot.getAIType())
              .ifPresent(setup -> bot.setBehaviorSettings(setup.behavior()));
    }

    /** Removes the local bot {@code id}; any other player is refused with the Swing lobby's message. */
    void removeBot(int id) {
        Player player = session.game().getPlayer(id);
        if (player != null) {
            commands.removeBot(commands.localBotClient(player));
        }
    }

    /** Has a new bot take the place of the disconnected player {@code id}, with their saved bot settings if any. */
    void replaceWithBot(int id) {
        Player player = session.game().getPlayer(id);
        if (player == null || !player.isGhost()) {
            return;
        }
        BehaviorSettings behavior = session.game().getBotSettings()
              .getOrDefault(player.getName(), dialogs.favouriteBehavior());
        addBot(player.getName(), behavior, Player.TEAM_UNASSIGNED);
    }

    /**
     * Adds a bot through its setup dialog, as the Swing lobby's Add Bot does, then puts it on {@code team} once it has
     * joined; {@link Player#TEAM_UNASSIGNED} leaves it where the server puts it.
     */
    void addBot(int team) {
        addBot(null, dialogs.favouriteBehavior(), team);
    }

    private void addBot(@Nullable String name, BehaviorSettings behavior, int team) {
        Optional<BotSetup> setup = dialogs.botSetup(name, behavior, null);
        if (setup.isEmpty()) {
            return;
        }
        AbstractClient bot = dialogs.connectBot(setup.get());
        if (bot == null) {
            return;
        }
        session.localBots().put(setup.get().name(), bot);
        if (team != Player.TEAM_UNASSIGNED) {
            dialogs.poll(() -> commands.joinTeam(bot, team));
        }
    }
}
