/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Vector;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.common.Player;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.util.C3Util;
import megamek.common.util.C3Util.C3CapacityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class LobbyCommandsTest {

    private static final int TEAM_LOCAL = 1;
    private static final int TEAM_BOT = 2;
    private static final int TEAM_ENEMY = 3;

    private Game game;
    private Client client;
    private Client botClient;
    private Player local;
    private Player bot;
    private Player ally;
    private Player enemy;
    private LobbyPrompts prompts;
    private LobbyCommands commands;
    private int nextEntityId = 1;

    @BeforeEach
    void setUp() {
        game = new Game();
        local = player(1, "Local", TEAM_LOCAL);
        bot = player(2, "Bot", TEAM_BOT);
        ally = player(3, "Ally", TEAM_LOCAL);
        enemy = player(4, "Enemy", TEAM_ENEMY);
        bot.setBot(true);

        client = mock(Client.class);
        botClient = mock(Client.class);
        Map<String, AbstractClient> bots = new HashMap<>(Map.of(bot.getName(), botClient));
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(client.getBots()).thenReturn(bots);
        when(botClient.getLocalPlayer()).thenReturn(bot);

        prompts = mock(LobbyPrompts.class);
        commands = new LobbyCommands(() -> client, prompts);
    }

    private Player player(int id, String name, int team) {
        Player player = new Player(id, name);
        player.setTeam(team);
        game.addPlayer(id, player);
        return player;
    }

    private Entity unitOf(Player owner) {
        Entity entity = mock(Entity.class);
        when(entity.getId()).thenReturn(nextEntityId++);
        when(entity.getOwner()).thenReturn(owner);
        when(entity.getOwnerId()).thenReturn(owner.getId());
        when(entity.getForceId()).thenReturn(Force.NO_FORCE);
        return entity;
    }

    @Test
    void ownUnitIsSentByTheLocalClient() {
        assertSame(client, commands.correctSender(unitOf(local)));
    }

    @Test
    void localBotUnitIsSentByTheBotClient() {
        assertSame(botClient, commands.correctSender(unitOf(bot)));
    }

    @Test
    void allyUnitIsSentByTheLocalClient() {
        assertSame(client, commands.correctSender(unitOf(ally)));
    }

    @Test
    void enemyUnitHasNoSenderWithoutGameMaster() {
        assertNull(commands.correctSender(unitOf(enemy)));
    }

    @Test
    void enemyUnitIsSentByTheBotOnItsTeam() {
        Player botTeammate = player(5, "Teammate", TEAM_BOT);
        assertSame(botClient, commands.correctSender(unitOf(botTeammate)));
    }

    @Test
    void enemyUnitIsSentByTheLocalClientForGameMaster() {
        local.setGameMaster(true);
        assertSame(client, commands.correctSender(unitOf(enemy)));
    }

    @Test
    void updatesAreSplitBetweenLocalClientAndBot() {
        Entity own = unitOf(local);
        Entity botUnit = unitOf(bot);
        Entity enemyUnit = unitOf(enemy);

        commands.sendUpdates(List.of(own, botUnit, enemyUnit));

        verify(client).sendUpdateEntity(List.<Entity>of(own));
        verify(botClient).sendUpdateEntity(List.<Entity>of(botUnit));
    }

    @Test
    void configuringAnEnemyUnitIsRefused() {
        Entity enemyUnit = unitOf(enemy);

        commands.applyDeployment(List.of(enemyUnit), 3);

        verify(prompts).error(LobbyError.CANNOT_CONFIG_ENEMIES);
        verify(enemyUnit, never()).setDeployRound(anyInt());
        verify(client, never()).sendUpdateEntity(anyCollection());
    }

    @Test
    void exceedingC3CapacityIsRefused() {
        Entity own = unitOf(local);
        try (MockedStatic<C3Util> c3Util = mockStatic(C3Util.class)) {
            c3Util.when(() -> C3Util.connect(any(), anyCollection(), anyInt(), anyBoolean()))
                  .thenThrow(new C3CapacityException());

            commands.c3Connect(List.of(own), 99, false);
        }

        verify(prompts).error(LobbyError.EXCEED_C3_CAPACITY);
        verify(client, never()).sendUpdateEntity(anyCollection());
    }

    @Test
    void deleteDoesNotSendWhenRefused() {
        Entity own = unitOf(local);
        when(prompts.confirm(anyString(), anyString())).thenReturn(false);

        commands.delete(new ArrayList<>(), new ArrayList<>(List.of(own)), true);

        verify(prompts).confirm("Delete Units...", "Really delete one unit?");
        verify(client, never()).sendDeleteEntities(anyList());
    }

    @Test
    void deleteSendsWhenConfirmed() {
        Entity own = unitOf(local);
        int ownId = own.getId();
        when(prompts.confirm(anyString(), anyString())).thenReturn(true);

        commands.delete(new ArrayList<>(), new ArrayList<>(List.of(own)), true);

        verify(client).sendDeleteEntities(List.of(ownId));
    }

    @Test
    void forceIsRenamedThroughThePrompt() {
        Force force = Force.createToplevelForce("Alpha", local);
        int forceId = game.getForces().addTopLevelForce(force, local);
        when(prompts.chooseForceName(any())).thenReturn(Optional.of("Bravo Lance"));

        commands.forceRename(forceId);

        ArgumentCaptor<ForceNameRequest> request = ArgumentCaptor.forClass(ForceNameRequest.class);
        verify(prompts).chooseForceName(request.capture());
        assertEquals("Alpha", request.getValue().currentName());
        assertEquals(local, request.getValue().owner());
        assertEquals("Bravo Lance", game.getForces().getForce(forceId).getName());
        verify(client).sendUpdateForce(List.of(game.getForces().getForce(forceId)));
    }

    @Test
    void cancelledForceRenameSendsNothing() {
        Force force = Force.createToplevelForce("Alpha", local);
        int forceId = game.getForces().addTopLevelForce(force, local);
        when(prompts.chooseForceName(any())).thenReturn(Optional.empty());

        commands.forceRename(forceId);

        assertEquals("Alpha", game.getForces().getForce(forceId).getName());
        verify(client, never()).sendUpdateForce(anyCollection());
    }

    @Test
    void renamingAnEnemyForceIsRefused() {
        Force force = Force.createToplevelForce("Alpha", enemy);
        int forceId = game.getForces().addTopLevelForce(force, enemy);

        commands.forceRename(forceId);

        verify(prompts).error(LobbyError.CANNOT_CONFIG_ENEMIES);
        verify(prompts, never()).chooseForceName(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void changeTeamSendsOnlyLocalPlayersThatChange() {
        commands.changeTeam(List.of(local, bot, enemy), TEAM_BOT);

        ArgumentCaptor<Vector<Player>> sent = ArgumentCaptor.forClass(Vector.class);
        verify(client).sendChangeTeam(sent.capture(), eq(TEAM_BOT));
        assertEquals(List.of(local), sent.getValue());
    }

    @Test
    void removingAPlayerThatIsNoLocalBotIsRefused() {
        commands.removeBot(client);

        verify(prompts).error(LobbyError.ONLY_OWN_BOT);
        verify(client, never()).die();
    }

    @Test
    void removingALocalBotStopsIt() {
        when(botClient.getName()).thenReturn(bot.getName());
        commands.removeBot(botClient);

        verify(botClient).die();
        assertFalse(client.getBots().containsKey(bot.getName()));
    }

    @Test
    void readyIsAllowedWithoutBlockers() {
        assertTrue(commands.checkReady());
        verify(prompts, never()).readyBlocked(any());
    }

    @Test
    void doneIsSentForLocalPlayerAndBots() {
        commands.sendDone(true);

        verify(client).sendDone(true);
        verify(botClient).sendDone(true);
    }

    @Test
    void scanTargetLogsAndSendsThroughTheBot() {
        Entity botUnit = unitOf(bot);

        commands.applyScanTarget(List.of(botUnit), true);

        verify(botUnit).setDesignatedScanTarget(true);
        verify(botClient).sendUpdateEntity(List.<Entity>of(botUnit));
    }

    @Test
    void directEditingExcludesAlliesUnlessGameMaster() {
        Entity allyUnit = unitOf(ally);
        assertFalse(commands.isDirectlyEditable(allyUnit));
        assertTrue(commands.isDirectlyEditable(unitOf(bot)));
        local.setGameMaster(true);
        assertTrue(commands.isDirectlyEditable(allyUnit));
    }

    @Test
    void proxyUpdatesUseTheBotClientForBots() {
        Collection<Entity> units = List.of(unitOf(bot));

        commands.sendProxyUpdates(units, bot);

        verify(botClient).sendUpdateEntity(units);
    }

    @Test
    void aNewBotJoinsItsTeamOnceItHasItsPlayer() {
        Client newBot = mock(Client.class);

        assertFalse(commands.joinTeam(newBot, TEAM_ENEMY));
        verify(newBot, never()).sendPlayerInfo();

        Player joined = new Player(6, "New Bot");
        joined.setTeam(TEAM_LOCAL);
        when(newBot.getLocalPlayer()).thenReturn(joined);

        assertTrue(commands.joinTeam(newBot, TEAM_ENEMY));
        assertEquals(TEAM_ENEMY, joined.getTeam());
        verify(newBot).sendPlayerInfo();
    }

    @Test
    void aNewBotAlreadyOnItsTeamSendsNothing() {
        Client newBot = mock(Client.class);
        Player joined = new Player(6, "New Bot");
        joined.setTeam(TEAM_ENEMY);
        when(newBot.getLocalPlayer()).thenReturn(joined);

        assertTrue(commands.joinTeam(newBot, TEAM_ENEMY));
        assertTrue(commands.joinTeam(newBot, Player.TEAM_UNASSIGNED));
        verify(newBot, never()).sendPlayerInfo();
    }

    @Test
    void findsTheClientsOfLocalBotsOnly() {
        assertSame(botClient, commands.localBotClient(bot));
        assertNull(commands.localBotClient(local));
        assertNull(commands.localBotClient(enemy));
    }
}
