/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;

import megamek.client.ui.boardeditor.BoardEditorPanel;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.tileset.HexTileset;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardIceTest {
    @Test
    void hiddenBlackIceDoesNotReplaceClassicGrassWithPavement() throws Exception {
        onEdt(() -> {
            try (var tiles = new HexTileset(new File("data/images/hexes"))) {
                tiles.loadFromFile("saxarba.tileset");
                var dry = new Hex(0, "road:1:9", "grass");
                var hidden = new Hex(0, "road:1:9;black_ice:1", "grass");
                assertEquals(tiles.getBase(dry), tiles.getBase(hidden));
                assertEquals(tiles.getSupers(dry), tiles.getSupers(hidden));
                assertTrue(hidden.containsTerrain(Terrains.BLACK_ICE), "Artwork matching cannot mutate the rules hex");
            }
            return null;
        });
    }

    @Test
    void discoveryChangesOnlyTheMaterialSnapshotAndPreviewNeverShowsAuthoringMarkers() throws Exception {
        onEdt(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(3, 3);
            var coords = new Coords(1, 1);
            var hex = new Hex(0, "road:1:9;black_ice:1;bldg_base_collapsed:1", "grass");
            board.setHex(coords, hex);
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) {
                var hidden = source.takeFrame().scene();
                assertFalse(hidden.tile(coords).blackIce());
                assertFalse(hidden.tile(coords).frozen());
                assertTrue(hidden.tile(coords).detailedGround());
                assertTrue(BoardRoad.rendered(hidden.tile(coords)));
                assertEquals(BoardTactical.EMPTY, hidden.tactical());
                hex.getTerrain(Terrains.BLACK_ICE).detectBlackIce();
                board.setHex(coords, hex);
                source.refresh();
                var discovered = source.takeFrame().scene().tile(coords);
                assertTrue(discovered.blackIce());
                assertTrue(discovered.sameGeometry(hidden.tile(coords)), "Discovery adds no geometry");
                assertFalse(discovered.frozen(), "Do not turn thin black ice into load-bearing lake ice");
            }
            return null;
        });
    }

    @Test
    void editorUsesTheExistingMarkerPlaneAndRemovesMarkersAfterTerrainEdits() throws Exception {
        onEdt(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(2, 2);
            var coords = new Coords(0, 0);
            board.setHex(coords, new Hex(0, "black_ice:1;bldg_base_collapsed:1", ""));
            game.setBoard(board);
            var editor = mock(BoardEditorPanel.class);
            var window = mock(JFrame.class);
            when(window.getTitle()).thenReturn("Ice editor test");
            when(editor.getFrame()).thenReturn(window);
            when(editor.getMenuBar()).thenReturn(new JMenuBar());
            when(editor.elevationBrush(null)).thenReturn(List.of());
            try (var source = new GpuMapSource(game, null, editor)) {
                var markers = source.takeFrame().scene().tactical();
                assertFalse(markers.fills().isEmpty());
                markers.fills().forEach(fill -> assertNotNull(fill.planeAnchor()));
                board.setHex(coords, new Hex(0));
                source.refresh();
                assertEquals(BoardTactical.EMPTY, source.takeFrame().scene().tactical());
            }
            return null;
        });
    }

    @Test
    void blankFlagsAreIdentifiedFromTheSelectedArtworkWithoutHidingVisibleGradients() throws Exception {
        onEdt(() -> {
            var board = Board.createEmptyBoard(1, 1);
            var coords = new Coords(0, 0);
            try (var artwork = new BoardArtwork()) {
                board.setHex(coords, new Hex(0, "ground_fluff:4", ""));
                var blank = artwork.capture(board, coords, true);
                assertTrue(blank.blankTerrains().contains(Terrains.GROUND_FLUFF));
                assertTrue(BoardFeatures.detailedGround(board.getHex(coords), blank.structureModels(), blank.blankTerrains()));
                board.setHex(coords, new Hex(0, "ground_fluff:4:4", ""));
                artwork.invalidate(coords);
                var visible = artwork.capture(board, coords, true);
                assertFalse(visible.blankTerrains().contains(Terrains.GROUND_FLUFF));
                assertTrue(BoardEditorTerrain.types(board.getHex(coords), visible.blankTerrains()).isEmpty());
            }
            var flags = new Hex(0, "black_ice:1;bldg_base_collapsed:1;metal_deposit:1;fluff:0;road_fluff:99", "");
            var types = BoardEditorTerrain.types(flags, Set.of(Terrains.FLUFF, Terrains.ROAD_FLUFF));
            assertTrue(types.containsAll(List.of(Terrains.BLACK_ICE, Terrains.BLDG_BASE_COLLAPSED,
                  Terrains.FLUFF, Terrains.ROAD_FLUFF)));
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
