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

package megamek.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Server commands ("/reset" ...) change the game, so they run on the packet pump in order with the game packets and
 * are not held by a pause; plain chat is handled at once.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServerCommandOrderTest {

    private static final int CONNECTION_ID = 0;

    private Server server;
    private TWGameManager gameManager;
    private final List<String> handled = new CopyOnWriteArrayList<>();
    private final CountDownLatch commandRan = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        gameManager = spy(new TWGameManager());
        Game game = new Game();
        game.addPlayer(CONNECTION_ID, new Player(CONNECTION_ID, "Test Player"));
        gameManager.setGame(game);
        // The command /reset ends in gameManager.resetGame(); record where and when it ran instead of resetting
        doAnswer(invocation -> {
            handled.add("command on " + Thread.currentThread().getName());
            commandRan.countDown();
            return null;
        }).when(gameManager).resetGame();
        // Port 0 binds an ephemeral port, so the test never collides with a running server
        server = new Server(null, 0, gameManager);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.die();
        }
    }

    @Test
    void aCommandRunsAfterTheGamePacketsReceivedBeforeIt() throws InterruptedException {
        doAnswer(invocation -> {
            Thread.sleep(200);
            handled.add("game packet");
            return null;
        }).when(gameManager)
              .handlePacket(anyInt(), argThat(packet -> isCommand(packet, PacketCommand.LOBBY_GENERATE_BOARD)));

        server.receivePacket(CONNECTION_ID, new Packet(PacketCommand.LOBBY_GENERATE_BOARD));
        server.receivePacket(CONNECTION_ID, new Packet(PacketCommand.CHAT, "/reset"));

        assertTrue(commandRan.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("game packet", "command on Packet Pump"), handled);
    }

    @Test
    void aCommandWhilePausedRunsAndHeldGamePacketsStayHeld() throws InterruptedException {
        server.receivePacket(CONNECTION_ID, new Packet(PacketCommand.PAUSE));
        server.receivePacket(CONNECTION_ID, new Packet(PacketCommand.LOBBY_GENERATE_BOARD));
        server.receivePacket(CONNECTION_ID, new Packet(PacketCommand.CHAT, "/reset"));

        assertTrue(commandRan.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("command on Packet Pump"), handled);
        verify(gameManager, never())
              .handlePacket(anyInt(), argThat(packet -> isCommand(packet, PacketCommand.LOBBY_GENERATE_BOARD)));
    }

    @Test
    void plainChatWhilePausedIsHandledAtOnce() {
        Server receivingServer = spy(server);
        Packet chat = new Packet(PacketCommand.CHAT, "hello");

        receivingServer.receivePacket(CONNECTION_ID, new Packet(PacketCommand.PAUSE));
        receivingServer.receivePacket(CONNECTION_ID, chat);

        verify(receivingServer).handle(eq(CONNECTION_ID), eq(chat));
    }

    private static boolean isCommand(Packet packet, PacketCommand command) {
        return (packet != null) && (packet.command() == command);
    }
}
