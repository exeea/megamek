/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs in a separate, constrained JVM: a large default test heap would conceal the original regression. */
@Tag("on-demand")
class GpuLargeMapTest {
    static Board largeBoard() {
        Board sample = new Board();
        sample.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        Hex[] hexes = new Hex[200 * 200];
        for (int x = 0; x < 200; x++) {
            for (int y = 0; y < 200; y++) {
                hexes[y * 200 + x] = sample.getHex(x % sample.getWidth(), y % sample.getHeight()).duplicate();
            }
        }
        return new Board(200, 200, hexes);
    }

    @Test
    void capturesOverviewPansAndEditsAtFullResolutionWithALimitedHeap() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean mapSheets = preferences.getShowMapSheets();
        long start = System.nanoTime();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(largeBoard())) {
            BoardScene initial = fixture.source.takeFrame().scene();
            assertEquals(40_000, initial.tiles().size());
            Set<BoardScene.Pixels> ground = new HashSet<>();
            initial.tiles().forEach(tile -> ground.add(tile.ground()));
            assertTrue(ground.size() < 1_000, "Repeated artwork must be shared across all 40,000 hexes");
            ground.forEach(pixels -> {
                assertEquals(84, pixels.width());
                assertEquals(72, pixels.height());
            });
            long opened = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                preferences.setShowMapSheets(true);
                fixture.source.setVisibleArea(new Rectangle(0, 0, 200, 200));
                fixture.source.refresh();
            });
            BoardScene overview = fixture.source.takeFrame().scene();
            assertTrue(GpuHexOverlayTest.covers(overview.tactical(), preferences.getMapsheetColor(),
                  new Coords(192, 190), GpuHexOverlayTest.NORTH_WEST_X, GpuHexOverlayTest.NORTH_WEST_Y),
                  "Far sheet borders must survive full-board capture as native geometry");
            assertNull(overview.tile(new Coords(192, 190)).tactical(), "Native sheet borders need no per-hex raster");
            Set<BoardScene.Pixels> markings = new HashSet<>();
            overview.tiles().forEach(tile -> {
                if (tile.tactical() != null) {
                    markings.add(tile.tactical());
                    assertEquals(252, tile.tactical().width());
                    assertEquals(216, tile.tactical().height());
                }
            });
            assertTrue(markings.size() < 1_000, "Sheet borders must share atlas artwork even in full overview");
            long captured = System.nanoTime();
            Coords far = new Coords(190, 190);
            // Hex plugins are the raster pass's one user: a marking far out exercises its capture and eviction.
            boolean[] marked = { true };
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.addHexDrawPlugin((graphics, hex, game, coords, view) -> {
                    if (marked[0] && coords.equals(far)) { graphics.fillRect(90, 90, 20, 20); }
                });
                fixture.source.setVisibleArea(new Rectangle(185, 185, 15, 15));
                fixture.source.refresh();
            });
            assertNotNull(fixture.source.takeFrame().scene().tile(far).tactical());
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.setVisibleArea(new Rectangle(0, 0, 16, 16));
                fixture.source.refresh();
            });
            assertNull(fixture.source.takeFrame().scene().tile(far).tactical(), "Panning must release offscreen markings");
            SwingUtilities.invokeAndWait(() -> {
                marked[0] = false;
                fixture.game.getBoard().setHex(far, new Hex(4));
                fixture.source.setVisibleArea(new Rectangle(185, 185, 15, 15));
                fixture.source.refresh();
            });
            assertEquals(4, fixture.source.takeFrame().scene().tile(far).elevation());
            BoardScene last = fixture.source.takeFrame().scene();
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            assertSame(last.tiles(), fixture.source.takeFrame().scene().tiles());
            System.out.printf("200x200: open %.2fs, overview %.2fs, unique ground %d, markings %d, max heap %d MiB%n",
                  (opened - start) / 1e9, (captured - opened) / 1e9, ground.size(), markings.size(),
                  Runtime.getRuntime().maxMemory() / (1024 * 1024));
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setShowMapSheets(mapSheets));
        }
    }
}
