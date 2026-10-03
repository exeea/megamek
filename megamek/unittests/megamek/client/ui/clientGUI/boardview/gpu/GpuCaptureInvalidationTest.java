/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuCaptureInvalidationTest {
    @Test
    void panningReusesOverlapAndMatchesFreshTacticalCaptures() throws Exception {
        // More than the painter's small image cache: a full viewport repaint would invoke old hex painters again.
        Hex[] hexes = new Hex[40 * 40];
        Arrays.setAll(hexes, ignored -> new Hex(0));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(40, 40, hexes))) {
            SwingUtilities.invokeAndWait(() -> {
                Set<Coords> painted = new HashSet<>();
                fixture.view.addHexDrawPlugin((graphics, hex, game, coords, view) -> {
                    painted.add(coords);
                    graphics.setColor(new Color(coords.getX() * 5, coords.getY() * 5, 50));
                    graphics.fillRect(90, 90, 20, 20);
                });
                fixture.view.clearArtwork();
                Rectangle previous = new Rectangle(1, 1, 20, 20);
                fixture.source.setVisibleArea(previous);
                fixture.source.refresh();
                // Horizontal/diagonal pans, shrinking, enlarging, disjoint moves, and revisiting old terrain.
                for (Rectangle next : new Rectangle[] { new Rectangle(2, 1, 20, 20), new Rectangle(3, 2, 20, 20),
                      new Rectangle(4, 3, 8, 8), new Rectangle(2, 1, 22, 22), new Rectangle(32, 32, 5, 5),
                      new Rectangle(1, 1, 20, 20) }) {
                    BoardScene before = fixture.source.takeFrame().scene();
                    painted.clear();
                    fixture.source.setVisibleArea(next);
                    fixture.source.refresh();
                    BoardScene after = fixture.source.takeFrame().scene();
                    Rectangle overlap = previous.intersection(next);
                    Set<Coords> exposed = new HashSet<>();
                    for (int x = next.x; x < next.x + next.width; x++) {
                        for (int y = next.y; y < next.y + next.height; y++) {
                            Coords at = new Coords(x, y);
                            if (overlap.contains(x, y)) {
                                assertSame(before.tile(at), after.tile(at), "Panning retains the unchanged overlap");
                            } else { exposed.add(at); }
                        }
                    }
                    assertTrue(exposed.containsAll(painted), "Only exposed hexes may need painting; cached images can be reused");
                    fixture.view.capturePlanarTactical(next, hex -> {
                        BoardScene.Pixels expected = BoardScene.Pixels.capture(hex.tactical(), null);
                        assertEquals(expected, after.tile(hex.coords()).tactical(), "Incremental pixels match fresh capture");
                    });
                    for (var tile : after.tiles()) {
                        assertTrue(next.contains(tile.coords().getX(), tile.coords().getY()) || tile.tactical() == null,
                              "Offscreen tactical images are released");
                    }
                    previous = next;
                }
                painted.clear();
                fixture.view.clearArtwork();
                fixture.source.refresh();
                assertEquals(previous.width * previous.height, painted.size(), "Invalidation repaints the entire view");
            });
        }
    }

    @Test
    void localHeightEditOnlyInvokesLocalTacticalPainters() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Set<Coords> painted = new HashSet<>();
                fixture.view.addHexDrawPlugin((graphics, hex, game, coords, view) -> painted.add(coords));
                fixture.source.refresh();
                painted.clear();
                Coords at = new Coords(5, 5);
                fixture.game.getBoard().setHex(at, new Hex(4));
                fixture.source.refresh();
                assertTrue(painted.size() <= 9, "Local edit repainted " + painted.size() + " hexes");
                assertTrue(painted.contains(at));
                assertCompleteCapture(fixture);
            });
        }
    }

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
                fixture.view.clearArtwork();
                fixture.source.refresh();
                int first = paints.get();
                assertTrue(first > 0);
                fixture.source.refresh();
                assertEquals(first, paints.get(), "The timer must not repaint an unchanged board");
                fixture.view.clearArtwork();
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
