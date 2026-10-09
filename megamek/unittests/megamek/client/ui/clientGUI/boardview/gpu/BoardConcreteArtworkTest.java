/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardConcreteArtworkTest {
    @Test
    void generatedQuayWallsReplaceThePaintedSpritesInBothViews() throws Exception {
        FutureTask<Void> capture = new FutureTask<>(() -> {
            Board board = Board.createEmptyBoard(1, 1);
            Coords at = new Coords(0, 0);
            try (var artwork = new BoardArtwork()) {
                Hex plain = new Hex(0, "water:2", "grass", at);
                board.setHex(at, plain);
                var water = BoardScene.captureTile(plain, artwork.capture(board, at, true), null, new BoardScene.PixelPool());
                for (int variant = 1; variant <= 8; variant++) {
                    Hex hex = new Hex(0, "water:2;fluff:100:" + variant, "grass", at);
                    board.setHex(at, hex);
                    artwork.invalidate(at);
                    var tile = BoardScene.captureTile(hex, artwork.capture(board, at, true), null, new BoardScene.PixelPool());
                    assertNull(tile.decals(), "Native quay geometry replaces the painted wall, variant " + variant);
                    assertNull(tile.tilesetDecals(), "Tactical View also replaces the painted wall, variant " + variant);
                    assertEquals(water.tileset(), tile.tileset(), "The seabed artwork contains no baked quay sprite");
                    assertTrue(tile.detailedGround(), "The replaced wall does not disable native water terrain");
                    assertEquals(variant, hex.getTerrain(Terrains.FLUFF).getExits(), "Rendering preserves map data");
                }
            }
            return null;
        });
        SwingUtilities.invokeAndWait(capture);
        capture.get();
    }

    @Test
    void ultraSublevelArtStaysInTheTacticalViewOnly() throws Exception {
        FutureTask<Void> capture = new FutureTask<>(() -> {
            Board board = Board.createEmptyBoard(1, 1);
            Coords at = new Coords(0, 0);
            try (var artwork = new BoardArtwork()) {
                Hex plain = new Hex(0, "", "grass", at);
                board.setHex(at, plain);
                var ground = BoardScene.captureTile(plain, artwork.capture(board, at, true), null, new BoardScene.PixelPool());
                Hex pit = new Hex(0, "ultra_sublevel:1", "grass", at);
                board.setHex(at, pit);
                artwork.invalidate(at);
                var tile = BoardScene.captureTile(pit, artwork.capture(board, at, true), null, new BoardScene.PixelPool());
                assertNull(tile.decals(), "The pit geometry replaces the painted hole in 3D");
                assertNotEquals(ground.tileset(), tile.tileset(), "Tactical View keeps the painted hole");
            }
            return null;
        });
        SwingUtilities.invokeAndWait(capture);
        capture.get();
    }
}
