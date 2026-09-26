/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

import megamek.common.board.Coords;
import megamek.common.board.BoardLocation;
import megamek.common.Hex;
import org.junit.jupiter.api.Test;

class GpuCaptureInvalidationTest {
    @Test
    void singleHexEditsRetainDistantArtworkAndMatchAFreshCapture() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                BoardScene before = fixture.source.takeFrame().scene();
                Coords edited = new Coords(5, 5), distant = new Coords(14, 14);
                fixture.game.getBoard().setHex(edited, new Hex(4));
                fixture.source.refresh();
                BoardScene incremental = fixture.source.takeFrame().scene();
                assertSame(before.tile(distant), incremental.tile(distant), "An edit must retain untouched tile snapshots");
                assertEquals(4, incremental.tile(edited).elevation());
                try (GpuBoardSource fresh = new GpuBoardSource(fixture.view, () -> fixture.panel)) {
                    assertEquals(fresh.takeFrame().scene().tiles(), incremental.tiles(),
                          "Incremental artwork and neighbour captures must match a complete board capture");
                }
            });
        }
    }

    @Test
    void batchedEdgeEditsUndoAndWholeBoardChangesMatchCompleteCaptures() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                var board = fixture.game.getBoard();
                Coords first = new Coords(0, 0), last = new Coords(board.getWidth() - 1, board.getHeight() - 1);
                Hex firstBefore = board.getHex(first).duplicate(), lastBefore = board.getHex(last).duplicate();
                board.setHexes(Map.of(BoardLocation.of(first, board.getBoardId()), new Hex(3),
                      BoardLocation.of(last, board.getBoardId()), new Hex(-2)));
                fixture.source.refresh();
                assertEquals(3, fixture.source.takeFrame().scene().tile(first).elevation());
                assertEquals(-2, fixture.source.takeFrame().scene().tile(last).elevation());
                assertCompleteCapture(fixture);
                board.setHexes(Map.of(BoardLocation.of(first, board.getBoardId()), firstBefore,
                      BoardLocation.of(last, board.getBoardId()), lastBefore));
                fixture.source.refresh();
                assertCompleteCapture(fixture);
                board.getHex(first).setLevel(6);
                board.initializeAllAutomaticTerrain();
                fixture.source.refresh();
                assertEquals(6, fixture.source.takeFrame().scene().tile(first).elevation());
                assertCompleteCapture(fixture);
            });
        }
    }

    private static void assertCompleteCapture(GpuBoardFixture fixture) {
        try (GpuBoardSource fresh = new GpuBoardSource(fixture.view, () -> fixture.panel)) {
            assertEquals(fresh.takeFrame().scene().tiles(), fixture.source.takeFrame().scene().tiles(),
                  "Accumulated edits, undo and whole-board invalidation must match a complete capture");
        }
    }

    @Test
    void unchangedRefreshSkipsPaintersAndInvalidationRefreshesTheirPixels() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicInteger paints = new AtomicInteger();
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.addHexDrawPlugin((graphics, hex, game, coords, view) -> {
                    paints.incrementAndGet();
                    graphics.setColor(Color.RED);
                    graphics.fillRect(90, 90, 20, 20);
                });
                fixture.view.clearHexImageCache();
                fixture.source.refresh();
                int first = paints.get();
                assertTrue(first > 0);
                fixture.source.refresh();
                assertEquals(first, paints.get(), "The timer must not repaint an unchanged board");
                fixture.view.clearHexImageCache();
                fixture.source.refresh();
                assertTrue(paints.get() > first, "Shared painter invalidation must reach the GPU snapshot");
                fixture.source.setVisibleArea(new Rectangle(0, 16, 1, 1));
                fixture.source.refresh();
                BoardScene.Pixels pixels = fixture.source.takeFrame().scene().tile(new Coords(0, 16)).tactical();
                assertEquals(0xff0000ff, pixels.rgba(95 * pixels.width() + 95),
                      "Panning must capture the destination at full tactical resolution");
            });
        }
    }
}
