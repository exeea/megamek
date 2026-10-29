/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReadyCheckTest {

    private Game game;
    private GameOptions options;
    private Player local;
    private Player bot;
    private Player other;

    @BeforeEach
    void setUp() {
        game = mock(Game.class);
        options = mock(GameOptions.class);
        when(game.getOptions()).thenReturn(options);
        local = new Player(1, "Local");
        bot = new Player(2, "Bot");
        other = new Player(3, "Other");
        when(game.getPlayersList()).thenReturn(List.of(local, bot, other));
    }

    @Test
    void noBlockersByDefault() {
        assertTrue(ReadyCheck.blockers(game, local, List.of(bot)).isEmpty());
    }

    @Test
    void overlappingExclusiveDeploymentBlocks() {
        when(options.booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)).thenReturn(true);
        when(options.booleanOption(OptionsConstants.BASE_EXCLUSIVE_DB_DEPLOYMENT)).thenReturn(true);
        local.setStartingPos(1);
        other.setStartingPos(1);
        bot.setStartingPos(5);

        List<ReadyCheck.Blocker> blockers = ReadyCheck.blockers(game, local, List.of(bot));

        assertEquals(1, blockers.size());
        assertEquals(ReadyCheck.Reason.OVERLAPPING_DEPLOYMENT, blockers.get(0).reason());
        assertEquals(Messages.getString("ChatLounge.OverlapDeploy.title"), blockers.get(0).title());
        assertEquals(Messages.getString("ChatLounge.OverlapDeploy.msg"), blockers.get(0).message());
    }

    @Test
    void separateExclusiveDeploymentDoesNotBlock() {
        when(options.booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)).thenReturn(true);
        when(options.booleanOption(OptionsConstants.BASE_EXCLUSIVE_DB_DEPLOYMENT)).thenReturn(true);
        local.setStartingPos(1);
        bot.setStartingPos(3);
        other.setStartingPos(5);

        assertTrue(ReadyCheck.blockers(game, local, List.of(bot)).isEmpty());
    }

    @Test
    void localPlayersWithoutCommanderBlock() {
        when(options.booleanOption(OptionsConstants.VICTORY_COMMANDER_KILLED)).thenReturn(true);
        when(game.getEntitiesOwnedBy(local)).thenReturn(2);
        when(game.getLiveCommandersOwnedBy(local)).thenReturn(0);
        when(game.getEntitiesOwnedBy(bot)).thenReturn(1);
        when(game.getLiveCommandersOwnedBy(bot)).thenReturn(0);
        // a player without units needs no commander, and other players are not checked
        when(game.getEntitiesOwnedBy(other)).thenReturn(1);

        List<ReadyCheck.Blocker> blockers = ReadyCheck.blockers(game, local, List.of(bot));

        assertEquals(1, blockers.size());
        ReadyCheck.Blocker blocker = blockers.get(0);
        assertEquals(ReadyCheck.Reason.NO_COMMANDER, blocker.reason());
        assertEquals(List.of("Local", "Bot"), blocker.playerNames());
        assertEquals(Messages.getString("ChatLounge.noCmdr.msg") + "Local\nBot\n", blocker.message());
    }

    @Test
    void playersWithCommanderDoNotBlock() {
        when(options.booleanOption(OptionsConstants.VICTORY_COMMANDER_KILLED)).thenReturn(true);
        when(game.getEntitiesOwnedBy(local)).thenReturn(2);
        when(game.getLiveCommandersOwnedBy(local)).thenReturn(1);

        assertTrue(ReadyCheck.blockers(game, local, List.of(bot)).isEmpty());
    }

    @Test
    void commandsTellThePlayerWhyTheyCannotBeReady() {
        when(options.booleanOption(OptionsConstants.VICTORY_COMMANDER_KILLED)).thenReturn(true);
        when(game.getEntitiesOwnedBy(local)).thenReturn(1);
        Client client = mock(Client.class);
        Client botClient = mock(Client.class);
        when(botClient.getLocalPlayer()).thenReturn(bot);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(client.getBots()).thenReturn(new HashMap<>(Map.<String, AbstractClient>of("Bot", botClient)));
        LobbyPrompts prompts = mock(LobbyPrompts.class);

        boolean ready = new LobbyCommands(() -> client, prompts).checkReady();

        ArgumentCaptor<ReadyCheck.Blocker> blocker = ArgumentCaptor.forClass(ReadyCheck.Blocker.class);
        verify(prompts).readyBlocked(blocker.capture());
        assertEquals(List.of("Local"), blocker.getValue().playerNames());
        assertFalse(ready);
        verify(client, never()).sendDone(anyBoolean());
    }
}
