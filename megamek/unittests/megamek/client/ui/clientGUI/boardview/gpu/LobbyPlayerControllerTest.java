/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Vector;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyPrompts;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The native lobby's player actions, without OpenGL or Swing: who may do what, and what each sends. */
class LobbyPlayerControllerTest {
    private Game game;
    private Client client;
    private BotClient botClient;
    private Player local;
    private Player bot;
    private Player enemy;
    private Map<String, AbstractClient> bots;
    private LobbyPrompts prompts;
    private FakeDialogs dialogs;
    private LobbyPlayerController controller;

    @BeforeEach
    void setUp() {
        game = new Game();
        local = player(1, "Local", 1);
        bot = player(2, "Bot", 2);
        bot.setBot(true);
        enemy = player(3, "Enemy", 3);
        client = mock(Client.class);
        botClient = mock(BotClient.class);
        bots = new HashMap<>(Map.of(bot.getName(), botClient));
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(client.getBots()).thenReturn(bots);
        when(client.isLocalBot(bot)).thenReturn(true);
        when(client.getBotClient(bot)).thenReturn(botClient);
        when(botClient.getLocalPlayer()).thenReturn(bot);
        when(botClient.getName()).thenReturn(bot.getName());
        prompts = mock(LobbyPrompts.class);
        dialogs = new FakeDialogs();
        controller = new LobbyPlayerController(() -> client, new LobbyCommands(() -> client, prompts), dialogs);
    }

    private Player player(int id, String name, int team) {
        Player player = new Player(id, name);
        player.setTeam(team);
        game.addPlayer(id, player);
        return player;
    }

    @Test
    void playerSettingsOpenForTheLocalPlayerAndTheirBotsOnly() {
        controller.playerSettings(local.getId());
        controller.playerSettings(bot.getId());
        controller.playerSettings(enemy.getId());
        controller.playerSettings(99);
        assertEquals(List.of(client, botClient), dialogs.settingsOpened);
    }

    @Test
    void aChosenCamouflageIsSentByThePlayersOwnClient() {
        Camouflage chosen = new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "Red");
        dialogs.camouflage = Optional.of(chosen);
        controller.camouflage(bot.getId());
        assertSame(chosen, bot.getCamouflage());
        verify(botClient).sendPlayerInfo();
        verify(client, never()).sendPlayerInfo();
    }

    @Test
    void aCancelledCamouflageSendsNothing() {
        controller.camouflage(local.getId());
        verify(client, never()).sendPlayerInfo();
    }

    @Test
    void aCamouflageChosenAfterThePlayerLeftIsNotSent() {
        LobbyPlayerController.Dialogs late = new FakeDialogs() {
            @Override
            public void camouflage(Camouflage initial, List<Entity> units, Consumer<Camouflage> chosen) {
                game.removePlayer(bot.getId());
                chosen.accept(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "Red"));
            }
        };
        new LobbyPlayerController(() -> client, new LobbyCommands(() -> client, prompts), late)
              .camouflage(bot.getId());
        verify(botClient, never()).sendPlayerInfo();
    }

    @Test
    void anotherPlayersCamouflageIsNotAsked() {
        dialogs.camouflage = Optional.of(new Camouflage());
        controller.camouflage(enemy.getId());
        assertEquals(0, dialogs.camouflageAsked);
    }

    @Test
    void theTeamChangesForOwnPlayersOnly() {
        controller.changeTeam(local.getId(), 4);
        verify(client).sendChangeTeam(eq(new Vector<>(List.of(local))), eq(4));
        controller.changeTeam(enemy.getId(), 4);
        verify(client, never()).sendChangeTeam(eq(new Vector<>(List.of(enemy))), anyInt());
    }

    @Test
    void theDeploymentAreaOfALocalBotIsSentByTheBot() {
        controller.changeDeployment(bot.getId(), 3);
        assertEquals(3, bot.getStartingPos());
        verify(botClient).sendPlayerInfo();
    }

    @Test
    void theDeploymentAreaOfAnotherPlayerStays() {
        int before = enemy.getStartingPos();
        controller.changeDeployment(enemy.getId(), 3);
        assertEquals(before, enemy.getStartingPos());
    }

    @Test
    void theGameMasterRoleIsAskedForWithItsCommand() {
        controller.toggleGameMaster();
        verify(client).sendChat("/gm");
    }

    @Test
    void removingALocalBotEndsIt() {
        controller.removeBot(bot.getId());
        verify(botClient).die();
        assertFalse(bots.containsKey(bot.getName()));
    }

    @Test
    void removingAnotherPlayerIsRefused() {
        controller.removeBot(enemy.getId());
        verify(prompts).error(LobbyError.ONLY_OWN_BOT);
    }

    @Test
    void botSettingsKeepTheBotsNameAndKind() {
        BehaviorSettings changed = new BehaviorSettings();
        when(botClient.getAIType()).thenReturn(AIType.PRINCESS);
        dialogs.setup = new LobbyPlayerController.BotSetup(bot.getName(), AIType.PRINCESS, changed);
        controller.botSettings(bot.getId());
        assertEquals(bot.getName(), dialogs.setupName);
        assertEquals(AIType.PRINCESS, dialogs.setupType);
        verify(botClient).setBehaviorSettings(changed);
    }

    @Test
    void botSettingsAreDroppedWhenTheBotLeftWhileTheDialogShowed() {
        when(botClient.getAIType()).thenReturn(AIType.PRINCESS);
        dialogs.deferSetup = true;
        controller.botSettings(bot.getId());
        bots.remove(bot.getName());
        BehaviorSettings changed = new BehaviorSettings();
        dialogs.pendingSetup.accept(new LobbyPlayerController.BotSetup(bot.getName(), AIType.PRINCESS, changed));
        verify(botClient, never()).setBehaviorSettings(changed);
    }

    @Test
    void anotherPlayerHasNoBotSettings() {
        controller.botSettings(enemy.getId());
        assertEquals(0, dialogs.setupAsked);
    }

    @Test
    void anAddedBotJoinsItsTeamOnceItHasItsPlayer() {
        AbstractClient added = mock(AbstractClient.class);
        Player joined = new Player(7, "New");
        dialogs.setup = new LobbyPlayerController.BotSetup("New", AIType.PRINCESS, new BehaviorSettings());
        dialogs.connected = added;
        controller.addBot(4);
        assertNull(dialogs.setupName);
        assertSame(added, bots.get("New"));
        BooleanSupplier step = dialogs.polled.get(0);
        assertFalse(step.getAsBoolean());
        when(added.getLocalPlayer()).thenReturn(joined);
        assertTrue(step.getAsBoolean());
        assertEquals(4, joined.getTeam());
        verify(added).sendPlayerInfo();
    }

    @Test
    void aCancelledBotIsNotMade() {
        controller.addBot(4);
        assertEquals(1, dialogs.setupAsked);
        assertEquals(0, dialogs.connectAsked);
    }

    @Test
    void aBotThatCannotConnectIsNotKept() {
        dialogs.setup = new LobbyPlayerController.BotSetup("New", AIType.PRINCESS, new BehaviorSettings());
        controller.addBot(4);
        assertEquals(1, dialogs.connectAsked);
        assertFalse(bots.containsKey("New"));
        assertTrue(dialogs.polled.isEmpty());
    }

    @Test
    void aDisconnectedPlayerIsReplacedUnderTheirNameAndSettings() {
        enemy.setGhost(true);
        BehaviorSettings saved = new BehaviorSettings();
        game.getBotSettings().put(enemy.getName(), saved);
        controller.replaceWithBot(enemy.getId());
        assertEquals(enemy.getName(), dialogs.setupName);
        assertSame(saved, dialogs.setupBehavior);
    }

    @Test
    void aConnectedPlayerIsNotReplaced() {
        controller.replaceWithBot(enemy.getId());
        assertEquals(0, dialogs.setupAsked);
    }

    /** Dialogs that answer as a test sets them up, and remember what they were asked. */
    private static class FakeDialogs implements LobbyPlayerController.Dialogs {
        final List<Client> settingsOpened = new ArrayList<>();
        final List<BooleanSupplier> polled = new ArrayList<>();
        Optional<Camouflage> camouflage = Optional.empty();
        LobbyPlayerController.BotSetup setup;
        boolean deferSetup;
        Consumer<LobbyPlayerController.BotSetup> pendingSetup;
        AbstractClient connected;
        int camouflageAsked;
        int setupAsked;
        int connectAsked;
        String setupName;
        AIType setupType;
        BehaviorSettings setupBehavior;

        @Override
        public void playerSettings(Client client) {
            settingsOpened.add(client);
        }

        @Override
        public void camouflage(Camouflage initial, List<Entity> units, Consumer<Camouflage> chosen) {
            camouflageAsked++;
            camouflage.ifPresent(chosen);
        }

        @Override
        public void botSetup(String name, BehaviorSettings behavior, AIType type,
              Consumer<LobbyPlayerController.BotSetup> chosen) {
            setupAsked++;
            setupName = name;
            setupType = type;
            setupBehavior = behavior;
            if (deferSetup) {
                pendingSetup = chosen;
            } else if (setup != null) {
                chosen.accept(setup);
            }
        }

        @Override
        public AbstractClient connectBot(LobbyPlayerController.BotSetup setup) {
            connectAsked++;
            return connected;
        }

        @Override
        public void poll(BooleanSupplier step) {
            polled.add(step);
        }

        @Override
        public BehaviorSettings favouriteBehavior() {
            return new BehaviorSettings();
        }
    }
}
