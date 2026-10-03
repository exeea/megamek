/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.tileset.HexTileset;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Real artwork capture must not let cosmetic terrain transitions replace native ground materials. */
class BoardGroundCaptureTest {
    @Test
    void grasslandTwoKeepsNativeGroundAcrossItsAuthoredColorVariations() throws Exception {
        onEdt(() -> {
            var board = grassland();
            var game = new Game();
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) {
                BoardScene scene = source.takeFrame().scene();
                int transitions = 0, roads = 0, water = 0;
                for (var tile : scene.tiles()) {
                    var hex = board.getHex(tile.coords());
                    assertEquals(BoardScene.Surface.GRASS, tile.surface(), tile.coords().toString());
                    if (hex.containsTerrain(Terrains.WATER)) { water++; }
                    assertEquals(hex.containsTerrain(Terrains.WATER), tile.liquid().present());
                    if (!tile.liquid().present()) {
                        assertTrue(tile.detailedGround(), "Native grass at " + tile.coords());
                    }
                    if (hex.containsTerrain(Terrains.GROUND_FLUFF)) {
                        transitions++;
                        assertNull(tile.decals(), "No old ground transition may cover the native surface at " + tile.coords());
                    }
                    if (hex.containsTerrain(Terrains.ROAD)) {
                        roads++;
                        assertEquals(hex.getTerrain(Terrains.ROAD).getExits(), tile.roadExits());
                        assertEquals(BoardRoad.Kind.PAVED, tile.road());
                    }
                }
                assertTrue(transitions > 100, "Exercise the actual map's widespread decorative transitions");
                assertTrue(roads > 0 && water > 0, "The real road and river remain in the captured scene");
                assertEquals(transitions, board.getHexes(scene.tiles().stream().map(BoardScene.Tile::coords).toList()).stream()
                      .filter(hex -> hex.containsTerrain(Terrains.GROUND_FLUFF)).count(), "Capture never edits the board");
            }
            return null;
        });
    }

    @Test
    void cosmeticTransitionsUseNativeGroundWhileClassicAndUnmodeledArtworkRemainAvailable() throws Exception {
        onEdt(() -> {
            var board = Board.createEmptyBoard(1, 1);
            var coords = new Coords(0, 0);
            try (var artwork = new BoardArtwork(); var classic = new HexTileset(Configuration.hexesDir())) {
                classic.loadFromFile("saxarba.tileset");
                for (int family = 1; family <= 5; family++) {
                    for (int strength = 1; strength <= 5; strength++) {
                        Hex hex = new Hex(0, "ground_fluff:" + family + ":" + strength, "grass", coords);
                        board.setHex(coords, hex);
                        artwork.invalidate(coords);
                        var pixels = artwork.capture(board, coords, true);
                        assertTrue(pixels.blankTerrains().contains(Terrains.GROUND_FLUFF));
                        assertTrue(BoardFeatures.detailedGround(hex, pixels.structureModels(), pixels.blankTerrains()));
                        assertTrue(BoardEditorTerrain.types(hex, pixels.blankTerrains()).isEmpty(),
                              "Cosmetic blends must not fill the editor with hidden-terrain badges");
                        var tile = BoardScene.captureTile(hex, pixels, null, new BoardScene.PixelPool());
                        assertNull(tile.decals(), "A hidden transition must not survive in the decal pass");
                        assertFalse(classic.blankTerrainTypes(hex).contains(Terrains.GROUND_FLUFF),
                              "The ordinary board keeps its authored transition artwork");
                        assertEquals(strength, hex.getTerrain(Terrains.GROUND_FLUFF).getExits());
                    }
                }
                Hex gravel = new Hex(0, "ground_fluff:2000", "grass", coords);
                board.setHex(coords, gravel);
                artwork.invalidate(coords);
                var pixels = artwork.capture(board, coords, true);
                assertFalse(pixels.blankTerrains().contains(Terrains.GROUND_FLUFF));
                assertFalse(BoardFeatures.detailedGround(gravel, pixels.structureModels(), pixels.blankTerrains()),
                      "Unmodeled gravel artwork must not be discarded by a blanket fluff allow-list");
                assertTrue(BoardEditorTerrain.types(new Hex(0, "ground_fluff:99:4", "grass"),
                      Set.of(Terrains.GROUND_FLUFF)).contains(Terrains.GROUND_FLUFF),
                      "Unknown hidden artwork keeps its editor marker");
            }
            return null;
        });
    }

    static Board grassland() {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        return board;
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
