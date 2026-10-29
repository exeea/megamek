/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import org.junit.jupiter.api.Test;

/** The battlefield column's map: its size, and the deployment zones it draws and leaves out. */
class LobbyMapThumbnailTest {

    private static Board board(int width, int height) {
        Hex[] hexes = new Hex[width * height];
        Arrays.setAll(hexes, i -> new Hex());
        return new Board(width, height, hexes);
    }

    private static Player player(int id, int team, int start) {
        Player player = new Player(id, "P" + id);
        player.setTeam(team);
        player.setStartingPos(start);
        return player;
    }

    @Test
    void aZoneThatIsEverywhereOrNowhereIsLeftOut() {
        Board board = board(16, 17);

        assertNull(LobbyMapThumbnail.zone(board, player(1, 1, Board.START_ANY)));
        assertNull(LobbyMapThumbnail.zone(board, player(1, 1, Board.START_NONE)));
    }

    @Test
    void aSideZoneCoversOnlyItsEdge() {
        boolean[][] zone = LobbyMapThumbnail.zone(board(16, 17), player(1, 1, Board.START_W));

        assertNotNull(zone);
        assertTrue(zone[0][8]);
        assertFalse(zone[15][8]);
    }

    @Test
    void theImageKeepsTheBoardsShapeAlongItsLongerSide() {
        BufferedImage image = LobbyMapThumbnail.bake(board(32, 17), List.of(), null, LobbyMapThumbnail.LONG_SIDE);

        assertEquals(LobbyMapThumbnail.LONG_SIDE, image.getWidth());
        assertTrue(image.getHeight() < image.getWidth());
    }

    @Test
    void friendsAndEnemiesDifferAndTheLocalZoneIsEdged() {
        Player local = player(1, 1, Board.START_W);
        Player ally = player(2, 1, Board.START_W);
        Player enemy = player(3, 2, Board.START_E);

        LobbyMapThumbnail.ZoneStyle own = LobbyMapThumbnail.style(local, local);
        LobbyMapThumbnail.ZoneStyle friend = LobbyMapThumbnail.style(ally, local);
        LobbyMapThumbnail.ZoneStyle foe = LobbyMapThumbnail.style(enemy, local);

        assertEquals(friend.fill(), own.fill());
        assertNotEquals(friend.fill(), foe.fill());
        assertNotEquals(own.edge(), friend.edge());
    }

    @Test
    void zonesTintTheBoard() {
        Board board = board(16, 17);
        Player local = player(1, 1, Board.START_W);
        BufferedImage plain = LobbyMapThumbnail.bake(board, List.of(), local, 200);
        BufferedImage zoned = LobbyMapThumbnail.bake(board, List.of(local), local, 200);

        int x = 6;
        int y = zoned.getHeight() / 2;
        assertNotEquals(plain.getRGB(x, y), zoned.getRGB(x, y));
        int far = zoned.getWidth() - 7;
        assertEquals(plain.getRGB(far, y), zoned.getRGB(far, y));
    }
}
