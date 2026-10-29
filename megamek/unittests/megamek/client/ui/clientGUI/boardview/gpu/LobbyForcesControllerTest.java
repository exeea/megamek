/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyPrompts;
import megamek.client.ui.clientGUI.boardview.gpu.LobbyForcesController.Target;
import megamek.common.Player;
import megamek.common.equipment.EquipmentType;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The native lobby's unit and formation actions, without OpenGL or Swing: what each drop sends, and what it refuses. */
class LobbyForcesControllerTest {
    private Game game;
    private Client client;
    private Player local;
    private Player enemy;
    private LobbyPrompts prompts;
    private LobbyForcesController controller;
    private final Map<String, AbstractClient> bots = new HashMap<>();

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        local = player(1, "Local", 1);
        enemy = player(2, "Enemy", 2);
        client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(client.getBots()).thenReturn(bots);
        prompts = mock(LobbyPrompts.class);
        controller = new LobbyForcesController(() -> client, new LobbyCommands(() -> client, prompts));
    }

    private Player player(int id, String name, int team) {
        Player player = new Player(id, name);
        player.setTeam(team);
        game.addPlayer(id, player);
        return player;
    }

    private Entity mek(Player owner) {
        BipedMek mek = new BipedMek();
        mek.setChassis("Mek");
        mek.setModel("X");
        mek.setWeight(50);
        mek.setOwner(owner);
        game.addEntity(mek);
        return mek;
    }

    private int formation(String name, Player owner) {
        return game.getForces().addTopLevelForce(Force.createToplevelForce(name, owner), owner);
    }

    @Test
    void acceptsEitherUnitsOrASingleFormation() {
        assertFalse(LobbyForcesController.accepts(List.of(), List.of(), Target.NONE));
        assertFalse(LobbyForcesController.accepts(List.of(1), List.of(2), Target.NONE));
        assertTrue(LobbyForcesController.accepts(List.of(1, 2), List.of(), Target.unit(3)));
        assertTrue(LobbyForcesController.accepts(List.of(), List.of(2), Target.formation(3)));
        assertFalse(LobbyForcesController.accepts(List.of(), List.of(2), Target.formation(2)));
        assertFalse(LobbyForcesController.accepts(List.of(), List.of(2, 3), Target.formation(4)));
        assertFalse(LobbyForcesController.accepts(List.of(), List.of(2), Target.unit(4)));
        assertTrue(LobbyForcesController.accepts(List.of(), List.of(2, 3), Target.NONE));
    }

    @Test
    void unitsDroppedOnAFormationJoinIt() {
        Entity unit = mek(local);
        int alpha = formation("Alpha", local);

        assertTrue(controller.drop(List.of(unit.getId()), List.of(), Target.formation(alpha)));

        verify(client).sendAddEntitiesToForce(argThat(units -> units.contains(unit)), eq(alpha));
    }

    @Test
    void unitsDroppedOnAUnitJoinItsFormation() {
        Entity unit = mek(local);
        Entity member = mek(local);
        int alpha = formation("Alpha", local);
        game.getForces().addEntity(member, alpha);

        assertTrue(controller.drop(List.of(unit.getId()), List.of(), Target.unit(member.getId())));

        verify(client).sendAddEntitiesToForce(argThat(units -> units.contains(unit)), eq(alpha));
    }

    @Test
    void unitsDroppedOnEmptySpaceLeaveTheirFormation() {
        Entity unit = mek(local);
        int alpha = formation("Alpha", local);
        game.getForces().addEntity(unit, alpha);

        assertTrue(controller.drop(List.of(unit.getId()), List.of(), Target.NONE));

        verify(client).sendAddEntitiesToForce(argThat(units -> units.contains(unit)), eq(Force.NO_FORCE));
    }

    @Test
    void unitsWithoutAFormationDroppedOnEmptySpaceSendNothing() {
        Entity unit = mek(local);

        assertFalse(controller.drop(List.of(unit.getId()), List.of(), Target.NONE));

        verify(client, never()).sendAddEntitiesToForce(anyCollection(), anyInt());
    }

    @Test
    void aFormationDroppedOnAnotherBecomesPartOfIt() {
        int alpha = formation("Alpha", local);
        int bravo = formation("Bravo", local);

        assertTrue(controller.drop(List.of(), List.of(bravo), Target.formation(alpha)));

        verify(client).sendForceParent(argThat(list -> list.size() == 1
              && list.iterator().next().getId() == bravo), eq(alpha));
    }

    @Test
    void aFormationDroppedOnEmptySpaceBecomesTopLevel() {
        int alpha = formation("Alpha", local);
        Force bravo = new Force("Bravo", -1, new megamek.common.icons.Camouflage(), local);
        int bravoId = game.getForces().addSubForce(bravo, game.getForces().getForce(alpha));

        assertTrue(controller.drop(List.of(), List.of(bravoId), Target.NONE));

        verify(client).sendForceParent(anyCollection(), eq(Force.NO_FORCE));
    }

    @Test
    void anEnemyFormationCannotBeAttached() {
        int red = formation("Red", enemy);
        int alpha = formation("Alpha", local);

        controller.drop(List.of(), List.of(red), Target.formation(alpha));

        verify(client, never()).sendForceParent(anyCollection(), anyInt());
        verify(prompts).error(any(LobbyError.class));
    }

    @Test
    void anEnemyUnitIsNotConfigured() {
        Entity theirs = mek(enemy);

        assertFalse(controller.configure(theirs.getId()));
        assertFalse(controller.configure(999));
    }

    @Test
    void movesTheSingleUnitWithinItsFormation() {
        Entity first = mek(local);
        Entity second = mek(local);
        int alpha = formation("Alpha", local);
        game.getForces().addEntity(first, alpha);
        game.getForces().addEntity(second, alpha);

        controller.move(List.of(second.getId()), List.of(), true);

        verify(client).sendUpdateForce(anyCollection());
    }
}
