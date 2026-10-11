/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The one capture ({@link BoardTiles}): it recaptures an edited hex together with the neighbours the edit changed, and
 * draws no legacy art the conversion takes, never decoding it itself.
 */
class BoardTilesTest {
    @Test
    void aRoadEditThatSilentlyChangesANeighboursExitsRecapturesThatNeighbourInTheGame() throws Exception {
        Coords road = new Coords(3, 3), extension = road.translated(3), distant = new Coords(8, 8);
        Hex[] hexes = new Hex[10 * 10];
        Arrays.setAll(hexes, ignored -> new Hex(0));
        Board board = new Board(10, 10, hexes);
        board.setNativeFormat(true);
        board.setHex(road, new Hex(0, "road:1", ""));
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                var before = fixture.source.takeFrame().scene();
                int exits = board.getHex(road).getTerrain(Terrains.ROAD).getExits();
                // The board re-derives the old road's automatic exits and announces only the new hex.
                board.setHex(extension, new Hex(0, "road:1", ""));
                assertNotEquals(exits, board.getHex(road).getTerrain(Terrains.ROAD).getExits(), "The neighbour's exits change");
                fixture.source.refresh();
                var after = fixture.source.takeFrame().scene();
                var fresh = BoardTiles.captureAll(board).get(road.getX() * board.getHeight() + road.getY());
                assertEquals(board.getHex(road).getTerrain(Terrains.ROAD).getExits() & 63, after.tile(road).roadExits());
                assertEquals(fresh.ground(), after.tile(road).ground(), "The neighbour's road art is recaptured, not cached");
                assertNotEquals(before.tile(road).ground(), after.tile(road).ground());
                assertSame(before.tile(distant), after.tile(distant), "A distant tile keeps its snapshot");
            });
        }
    }

    @Test
    void anUnconvertedBoardDrawsNothingForTheArtTheConversionTakesAndDecodesNothing() {
        // A load site that skipped the conversion: the tripwire logs it, and the skylight is neither art nor an object.
        Board board = Board.createEmptyBoard(1, 1);
        board.setHex(new Coords(0, 0), new Hex(0, "pavement:1;fluff:6:0", ""));
        var tile = BoardTiles.captureAll(board).getFirst();
        assertNull(tile.tilesetScenery());
        assertNull(tile.decals());
        assertTrue(tile.features().stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY || f.decoration() != null));
        assertTrue(board.getHex(0, 0).containsTerrain(Terrains.FLUFF), "The renderer never changes the board");
        // The tripwire also meets the legacy road-with-trees art, which has no token to strip (road level 2).
        for (String terrain : List.of("pavement:1;fluff:6:0", "road:2:09")) {
            Hex hex = new Hex(0, terrain, "");
            assertTrue(BoardTiles.unconverted(hex, megamek.common.board.BoardSceneryLayouts.withoutTakenTokens(hex)), terrain);
        }
        Hex road = new Hex(0, "road:1:09", "");
        assertFalse(BoardTiles.unconverted(road, road), "A plain road is no legacy art");
    }
}
