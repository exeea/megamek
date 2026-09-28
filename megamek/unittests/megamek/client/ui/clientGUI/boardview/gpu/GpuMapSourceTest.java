/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class GpuMapSourceTest {
    @Test
    void previewUsesOnlyTheModelAndReleasesItsListeners() throws Exception {
        onEdt(() -> {
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(17, 16));
            var listeners = List.copyOf(game.getGameListeners());
            try (var source = new GpuMapSource(game, null, null)) {
                assertEquals(272, source.takeFrame().scene().tiles().size());
                assertTrue(source.takeFrame().scene().units().isEmpty());
                assertTrue(game.getGameListeners().stream().noneMatch(listener ->
                      listener.getClass().getName().contains("BoardView")), "Preview must not construct a BoardView");
                long generation = source.takeFrame().boardGeneration();
                Board oldBoard = game.getBoard();
                game.setBoard(Board.createEmptyBoard(6, 8));
                assertNotEquals(generation, source.takeFrame().boardGeneration());
                assertEquals(48, source.takeFrame().scene().tiles().size());
                oldBoard.setHex(new Coords(4, 4), new Hex(7));
                source.refresh();
                assertEquals(0, source.takeFrame().scene().tile(new Coords(4, 4)).elevation());
            }
            assertEquals(listeners, game.getGameListeners(), "Closing preview must detach every owned game listener");
            return null;
        });
    }

    @Test
    void incrementalEditsRetainDistantTilesAndMatchFreshCapture() throws Exception {
        onEdt(() -> {
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(17, 16));
            Coords edited = new Coords(8, 8);
            try (var source = new GpuMapSource(game, null, null)) {
                for (Hex replacement : List.of(new Hex(3),
                      new Hex(0, new Terrain[] { new Terrain(Terrains.WATER, 2) }, null),
                      new Hex(-2, new Terrain[] { new Terrain(Terrains.FIELDS, 1) }, null), new Hex(1))) {
                    BoardScene before = source.takeFrame().scene();
                    game.getBoard().setHex(edited, replacement);
                    source.refresh();
                    BoardScene after = source.takeFrame().scene();
                    int retained = 0;
                    for (int i = 0; i < before.tiles().size(); i++) {
                        if (before.tiles().get(i) == after.tiles().get(i)) { retained++; }
                    }
                    assertTrue(retained >= 247, "A local edit must retain distant tile objects: " + retained);
                    try (var fresh = new GpuMapSource(game, null, null)) {
                        assertEquals(fresh.takeFrame().scene().tiles(), after.tiles());
                    }
                    source.refresh();
                    assertSame(after.tiles(), source.takeFrame().scene().tiles(), "Idle capture retains terrain snapshots");
                }
            }
            return null;
        });
    }

    @Test
    void nativeArtworkMatchesTheGameplayCaptureBoundary() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        try (var fixture = GpuBoardFixture.create(board)) {
            onEdt(() -> {
                try (var source = new GpuMapSource(fixture.game, null, null)) {
                    var expected = fixture.source.takeFrame().scene();
                    var actual = source.takeFrame().scene();
                    assertEquals(expected.width(), actual.width());
                    for (var tile : actual.tiles()) {
                        var previous = expected.tile(tile.coords());
                        assertEquals(previous.ground(), tile.ground());
                        assertEquals(previous.normals(), tile.normals());
                        assertEquals(previous.decals(), tile.decals());
                        assertEquals(previous.features(), tile.features());
                        assertEquals(previous.text(), tile.text());
                    }
                }
                return null;
            });
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
