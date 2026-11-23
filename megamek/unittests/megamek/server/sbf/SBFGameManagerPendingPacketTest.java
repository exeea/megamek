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

package megamek.server.sbf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.server.Server;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** Tests recipient filtering and empty-envelope suppression in SBF packet batching. */
class SBFGameManagerPendingPacketTest {

    @Test
    void unseenIdsAreNeverSentAndLosingAKnownContactRemovesItOnce() {
        SBFGameManager manager = mock(SBFGameManager.class);
        SBFGame game = new SBFGame();
        org.mockito.Mockito.when(manager.getGame()).thenReturn(game);
        Player viewer = new Player(0, "Viewer");
        game.addPlayer(0, viewer);
        game.addPlayer(1, new Player(1, "Enemy"));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        SBFFormation target = new SBFFormation();
        target.setId(37);
        target.setOwnerId(1);
        game.addUnit(target);
        var updates = new SBFUnitUpdateHelper(manager);
        updates.sendUnitUpdate(viewer, target);
        verify(manager, never()).send(eq(0), any());
        game.visibilityHelper().setVisible(0, 37);
        assertEquals(List.of(target), updates.snapshotFor(viewer));
        game.visibilityHelper().setInvisible(0, 37);
        updates.sendUnitUpdate(viewer, target);
        updates.sendUnitUpdate(viewer, target);
        var packet = ArgumentCaptor.forClass(Packet.class);
        verify(manager).send(eq(0), packet.capture());
        assertEquals(PacketCommand.UNIT_INVISIBLE, packet.getValue().command());
        assertEquals(37, packet.getValue().getObject(0));
    }

    @Test
    void reconnectDuringLastMovementTurnResendsItOnlyToReturningPlayer() {
        SBFGameManager manager = new SBFGameManager();
        SBFGame game = new SBFGame();
        manager.setGame(game);
        game.addPlayer(0, new Player(0, "Returning"));
        game.addPlayer(1, new Player(1, "Other"));
        game.setPhase(GamePhase.MOVEMENT);
        game.setTurns(List.of(new SBFFormationTurn(0)));
        game.changeToNextTurn();
        Server server = mock(Server.class);
        try (MockedStatic<Server> serverStatic = mockStatic(Server.class)) {
            serverStatic.when(Server::getServerInstance).thenReturn(server);
            manager.sendCurrentInfo(0);
            assertEquals(GamePhase.MOVEMENT, game.getPhase());
            assertEquals(0, game.getTurnIndex());
            ArgumentCaptor<Packet> envelopes = ArgumentCaptor.forClass(Packet.class);
            verify(server).send(eq(0), envelopes.capture());
            List<?> packets = (List<?>) envelopes.getValue().getObject(0);
            assertTrue(packets.stream().anyMatch(p -> p instanceof Packet packet
                  && packet.command() == PacketCommand.TURN));
            verify(server, never()).send(eq(1), any(Packet.class));
        }
    }

    @Test
    void graveyardReconnectShowsOwnedAndDetectedFormationsOnly() {
        SBFGameManager manager = new SBFGameManager();
        SBFGame game = new SBFGame();
        manager.setGame(game);
        Player player = new Player(0, "Viewer");
        game.addPlayer(0, player);
        game.addPlayer(1, new Player(1, "Enemy"));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        for (int id = 10; id <= 12; id++) {
            SBFFormation formation = new SBFFormation();
            formation.setId(id);
            formation.setOwnerId(id == 10 ? 0 : 1);
            game.getGraveyard().add(formation);
        }
        game.visibilityHelper().setVisible(0, 11);

        assertEquals(game.getGraveyard().subList(0, 2), manager.createGameStartUnitPacket(player).getObject(1));
    }

    @Test
    void formationReportsRequireAllSubjectsToHaveBeenVisibleAtTheTimeOfTheEvent() {
        SBFGameManager manager = new SBFGameManager();
        SBFGame game = new SBFGame();
        Player viewer = new Player(1, "Viewer");
        Player opponent = new Player(2, "Opponent");
        game.addPlayer(1, viewer);
        game.addPlayer(2, opponent);
        manager.setGame(game);
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        SBFFormation attacker = new SBFFormation();
        attacker.setId(7);
        attacker.setOwnerId(1);
        SBFFormation target = new SBFFormation();
        target.setId(13);
        target.setOwnerId(2);
        game.addUnit(attacker);
        game.addUnit(target);
        SBFReportEntry heading = new SBFReportEntry(1000);
        SBFReportEntry casualty = new SBFReportEntry(3092);
        SBFReportEntry hiddenAttack = new SBFReportEntry(2001);
        SBFReportEntry seenAttack = new SBFReportEntry(2001);
        manager.addReport(heading);
        manager.addReportFor(casualty, target);
        manager.addReportFor(hiddenAttack, attacker, target);
        game.visibilityHelper().setVisible(viewer.getId(), target.getId());
        manager.addReportFor(seenAttack, attacker, target);
        game.visibilityHelper().setInvisible(viewer.getId(), target.getId());
        game.getGameReport().add(3, manager.getPendingReports());

        assertEquals(List.of(heading, seenAttack), manager.createReportPacket(viewer).getObject(0));
        assertEquals(List.of(heading, casualty), manager.createReportPacket(opponent).getObject(0));
        assertEquals(List.of(heading, seenAttack), game.getGameReport().createFilteredReport(viewer).get(3));
        assertEquals(List.of(heading, casualty), game.getGameReport().createFilteredReport(opponent).get(3));
    }

    @Test
    void targetedPacketDoesNotSendEmptyEnvelopeToOtherPlayers() {
        SBFGameManager gameManager = new SBFGameManager();
        SBFGame game = new SBFGame();
        game.addPlayer(0, new Player(0, "Recipient"));
        game.addPlayer(1, new Player(1, "Other"));
        gameManager.setGame(game);
        Packet targetedPacket = new Packet(PacketCommand.ROUND_UPDATE, 3);
        gameManager.addPendingPacket(0, targetedPacket);
        Server server = mock(Server.class);

        try (MockedStatic<Server> serverStatic = mockStatic(Server.class)) {
            serverStatic.when(Server::getServerInstance).thenReturn(server);

            gameManager.handlePacket(0, new Packet(PacketCommand.LOCAL_PN));
            gameManager.handlePacket(0, new Packet(PacketCommand.LOCAL_PN));

            ArgumentCaptor<Packet> envelopeCaptor = ArgumentCaptor.forClass(Packet.class);
            verify(server, times(1)).send(eq(0), envelopeCaptor.capture());
            verify(server, never()).send(eq(1), any(Packet.class));
            Packet envelope = envelopeCaptor.getValue();
            assertEquals(PacketCommand.MULTI_PACKET, envelope.command());
            assertEquals(List.of(targetedPacket), envelope.getObject(0));
        }
    }
}
