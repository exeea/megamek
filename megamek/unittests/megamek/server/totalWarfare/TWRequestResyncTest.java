/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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

package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.TimeUnit;

import megamek.common.GameBoardTestCase;
import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.Sensor;
import megamek.common.moves.MovePath;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.turns.SpecificEntityTurn;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.VTOL;
import megamek.server.Server;
import megamek.utils.ServerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

/**
 * A Client whose request the server refuses is put back in step: it gets its own unit as the server has it and, for a
 * turn submission, the phase and the turn. Another player's unit is never sent.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TWRequestResyncTest extends GameBoardTestCase {

    private static final int PLAYER_ID = 0;
    private static final int OTHER_PLAYER_ID = 1;
    private static final int OWN_UNIT_ID = 1;
    private static final int OTHER_UNIT_ID = 2;
    private static final int TURN_UNIT_ID = 3;

    private Server server;
    private TWGameManager gameManager;
    private VTOL ownUnit;

    static {
        initializeBoard("RESYNC_CLEAR", """
              size 1 1
              hex 0101 0 "" ""
              end""");
    }

    @BeforeEach
    void setUp() throws Exception {
        setBoard("RESYNC_CLEAR");
        gameManager = spy(new TWGameManager());
        gameManager.setGame(getGame());
        server = ServerFactory.createServer(gameManager);
        Player player = new Player(PLAYER_ID, "Test");
        Player otherPlayer = new Player(OTHER_PLAYER_ID, "Other");
        getGame().addPlayer(PLAYER_ID, player);
        getGame().addPlayer(OTHER_PLAYER_ID, otherPlayer);
        ownUnit = unit(OWN_UNIT_ID, player);
        unit(OTHER_UNIT_ID, otherPlayer);
        // The turn belongs to a different unit, so moves are refused
        getGame().setTurnVector(List.of(new SpecificEntityTurn(PLAYER_ID, TURN_UNIT_ID)));
        getGame().setTurnIndex(0, PLAYER_ID);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.die();
        }
    }

    @Test
    void aMoveInTheWrongPhaseGetsItsUnitThePhaseAndTheTurnBack() {
        getGame().setPhase(GamePhase.FIRING);

        gameManager.handlePacket(PLAYER_ID, move(ownUnit));

        InOrder order = inOrder(gameManager);
        order.verify(gameManager).send(eq(PLAYER_ID), argThat(packet -> isUnit(packet, OWN_UNIT_ID)));
        order.verify(gameManager).send(eq(PLAYER_ID), argThat(packet -> isCommand(packet, PacketCommand.PHASE_CHANGE)));
        order.verify(gameManager)
              .send(eq(PLAYER_ID), argThat(packet -> isCommand(packet, PacketCommand.SENDING_TURNS)));
        order.verify(gameManager).send(eq(PLAYER_ID), argThat(packet -> (packet.command() == PacketCommand.TURN)
              && Integer.valueOf(PLAYER_ID).equals(packet.getObject(1))));
    }

    @Test
    void aRefusedMoveOfAnotherPlayersUnitSendsNoUnit() {
        getGame().setPhase(GamePhase.MOVEMENT);

        gameManager.handlePacket(PLAYER_ID, move(getGame().getEntity(OTHER_UNIT_ID)));

        verify(gameManager, never())
              .send(eq(PLAYER_ID), argThat(packet -> isCommand(packet, PacketCommand.ENTITY_UPDATE)));
        verify(gameManager).send(eq(PLAYER_ID), argThat(packet -> isCommand(packet, PacketCommand.SENDING_TURNS)));
    }

    @Test
    void aRefusedSettingChangeSendsTheOwnerItsUnitUnchanged() {
        getGame().setPhase(GamePhase.MOVEMENT);

        Sensor before = ownUnit.getNextSensor();

        gameManager.handlePacket(PLAYER_ID, new Packet(PacketCommand.ENTITY_SENSOR_CHANGE, OWN_UNIT_ID, 5));

        assertEquals(before, ownUnit.getNextSensor());
        verify(gameManager).send(eq(PLAYER_ID), argThat(packet -> isUnit(packet, OWN_UNIT_ID)));
    }

    @Test
    void anAcceptedSettingChangeSendsTheOwnerItsChangedUnit() {
        getGame().setPhase(GamePhase.MOVEMENT);
        Sensor radar = new Sensor(Sensor.TYPE_VEE_RADAR);
        ownUnit.getSensors().add(radar);

        gameManager.handlePacket(PLAYER_ID, new Packet(PacketCommand.ENTITY_SENSOR_CHANGE, OWN_UNIT_ID, 0));

        assertEquals(radar, ownUnit.getNextSensor());
        verify(gameManager).send(eq(PLAYER_ID), argThat(packet -> isUnit(packet, OWN_UNIT_ID)));
    }

    @Test
    void aSettingChangeForAnotherPlayersUnitSendsNothing() {
        getGame().setPhase(GamePhase.MOVEMENT);

        gameManager.handlePacket(PLAYER_ID, new Packet(PacketCommand.ENTITY_SENSOR_CHANGE, OTHER_UNIT_ID, 0));

        verify(gameManager, never()).send(anyInt(), argThat(packet -> isCommand(packet, PacketCommand.ENTITY_UPDATE)));
    }

    private VTOL unit(int id, Player owner) {
        VTOL unit = new VTOL();
        unit.setChassis("Test");
        unit.setModel("Unit " + id);
        unit.setMovementMode(EntityMovementMode.VTOL);
        unit.setCrew(new Crew(CrewType.CREW));
        unit.setOwner(owner);
        unit.setId(id);
        getGame().addEntity(unit);
        return unit;
    }

    private Packet move(Entity unit) {
        return new Packet(PacketCommand.ENTITY_MOVE, unit.getId(), new MovePath(getGame(), unit));
    }

    private static boolean isUnit(Packet packet, int unitId) {
        return isCommand(packet, PacketCommand.ENTITY_UPDATE) && Integer.valueOf(unitId).equals(packet.getObject(0));
    }

    private static boolean isCommand(Packet packet, PacketCommand command) {
        return (packet != null) && (packet.command() == command);
    }
}
