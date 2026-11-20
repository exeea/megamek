/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.graphics.Color;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuRoadMasksTest {
    @Test
    void distantRoadMasksKeepTheirWorldBoundsAndCoverage() {
        int reduced = 0;
        for (int exits : new int[] { 1, 3, 5, 9, 21, 63 }) {
            var tile = BoardScenes.tile(BoardScenes.CENTER, BoardRoad.Kind.PAVED, exits, 0, BoardScene.Surface.GRASS);
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
            for (var patch : GpuRoads.patches(tile, road)) {
                if (patch.shape().isEmpty()) { continue; }
                var full = GpuRoads.mask(road, patch);
                int density = GpuRoads.maskDensity(patch, TerrainLod.COARSE);
                if (density < 4) { reduced++; }
                var distant = GpuRoads.mask(road, patch, density);
                assertEquals(full.x(), distant.x());
                assertEquals(full.y(), distant.y());
                assertEquals(full.width(), distant.width());
                assertEquals(full.height(), distant.height());
                assertEquals(full.pixels().width(), distant.pixels().width() * (4 / density));
                assertEquals(full.pixels().height(), distant.pixels().height() * (4 / density));
                // Full and medium detail alike: 4 texels a unit, but 2 for the field the distant levels reduce.
                assertEquals(GpuRoads.maskDensity(patch, TerrainLod.FULL), GpuRoads.maskDensity(patch, TerrainLod.MEDIUM));
                assertEquals(density < 4 ? 2 : 4, GpuRoads.maskDensity(patch, TerrainLod.FULL));
                assertEquals(coverage(full), coverage(distant), .005,
                      "Distant masks retain the covered road area, including curves and junction markings");
            }
        }
        assertTrue(reduced > 0, "The fixtures exercise distant wear fields as well as preserved markings");
    }

    @Test
    void equalRoadsAnywhereShareOneMaskUnlessItReadsTheirPlace() {
        var far = new Coords(BoardScenes.CENTER.getX() + 7, BoardScenes.CENTER.getY() + 5);
        for (int exits : new int[] { 9, 21, 63 }) {
            var here = BoardRoad.clearance(BoardScenes.CENTER, exits);
            var there = BoardRoad.clearance(far, exits);
            var patches = GpuRoads.patches(BoardScenes.tile(BoardScenes.CENTER, BoardRoad.Kind.PAVED, exits, 0, BoardScene.Surface.GRASS), here);
            var others = GpuRoads.patches(BoardScenes.tile(far, BoardRoad.Kind.PAVED, exits, 0, BoardScene.Surface.GRASS), there);
            assertEquals(patches.size(), others.size());
            for (int i = 0; i < patches.size(); i++) {
                if (patches.get(i).shape().isEmpty()) { continue; }
                assertEquals(GpuRoads.maskKey(here, patches.get(i), 4), GpuRoads.maskKey(there, others.get(i), 4));
                assertEquals(GpuRoads.mask(here, patches.get(i), 4), GpuRoads.mask(there, others.get(i), 4),
                      "One key means one mask, wherever its road lies");
            }
        }
        // A dead end's wear reads the world's noise (BoardRoad#endCoverage): it stays its own where it lies.
        var here = BoardRoad.layout(BoardScenes.CENTER, 1, BoardRoad.Kind.DIRT, direction -> BoardRoad.Kind.DIRT);
        var there = BoardRoad.layout(far, 1, BoardRoad.Kind.DIRT, direction -> BoardRoad.Kind.DIRT);
        var worn = GpuRoads.patches(BoardScenes.tile(BoardScenes.CENTER, BoardRoad.Kind.DIRT, 1, 0, BoardScene.Surface.GRASS), here).getFirst();
        var other = GpuRoads.patches(BoardScenes.tile(far, BoardRoad.Kind.DIRT, 1, 0, BoardScene.Surface.GRASS), there).getFirst();
        assertTrue(worn.fade().end() > 0);
        assertNotEquals(GpuRoads.maskKey(here, worn, 4), GpuRoads.maskKey(there, other, 4));
        assertNotEquals(GpuRoads.mask(here, worn, 4), GpuRoads.mask(there, other, 4));
    }

    private static double coverage(GpuRoads.MaskData mask) {
        var pixels = mask.pixels();
        long alpha = 0;
        int size = pixels.width() * pixels.height();
        for (int i = 0; i < size; i++) { alpha += pixels.rgba(i) & 255; }
        return alpha / (255.0 * size);
    }

    @Test
    void parallelRowsMatchSerialPixelsAndKeepTheCapturedTerrainSettings() throws Exception {
        var settings = TerrainSettings.capture();
        var patch = new GpuRoads.Patch(new Area(new Rectangle2D.Float(-60, -50, 120, 100)),
              "roads/asphalt", Color.WHITE, 1, 0, null);
        GpuRoads.MaskColor colour = (x, y, scratch) -> {
            assertSame(settings, TerrainSettings.current());
            return scratch.set((x + 61) / 122, (y + 51) / 102, .3f, .8f);
        };
        var serial = settings.call(() -> GpuRoads.mask(patch, colour));
        try (var workers = TerrainSettings.workers(4)) {
            var parallel = workers.submit(() -> settings.call(() -> GpuRoads.mask(patch, colour)));
            assertEquals(serial, parallel.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void simultaneousTerrainWorkersBuildOneSharedMask() throws Exception {
        var masks = new GpuRoads.Masks();
        var result = mask(0xff778899);
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var builds = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<GpuRoads.MaskData>>();
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> masks.mask("road", () -> {
                    builds.incrementAndGet();
                    await(release);
                    return result;
                }, entered::countDown)));
            }
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            for (var future : results) { assertSame(result, future.get(5, TimeUnit.SECONDS)); }
            assertEquals(1, builds.get(), "Adjacent chunks must not rasterize the same road mask in parallel");
        }
    }

    @Test
    void forgettingWhileAMaskBuildsDoesNotOverwriteTheNewGeneration() throws Exception {
        var masks = new GpuRoads.Masks();
        var old = mask(0xff112233);
        var current = mask(0xff445566);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var oldBuild = workers.submit(() -> masks.mask("road", () -> {
                entered.countDown();
                await(release);
                return old;
            }, () -> { }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                masks.forget();
                assertSame(current, masks.mask("road", () -> current, () -> { }));
            } finally { release.countDown(); }
            assertSame(old, oldBuild.get(5, TimeUnit.SECONDS));
            assertSame(current, masks.mask("road", () -> { throw new AssertionError("Already built"); }, () -> { }));
        }
    }

    @Test
    void failuresCanRetryAndAStaleRequesterDoesNotPoisonReusablePixels() {
        var masks = new GpuRoads.Masks();
        assertThrows(IllegalStateException.class, () -> masks.mask("road", () -> {
            throw new IllegalStateException("Rasterization failed");
        }, () -> { }));
        var result = mask(0xffaabbcc);
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> masks.mask("road", () -> result, () -> {
            if (checks.incrementAndGet() > 1) { throw new CancellationException(); }
        }));
        assertSame(result, masks.mask("road", () -> { throw new AssertionError("Reusable after cancellation"); }, () -> { }));
    }

    @Test
    void encodedMasksKeepATintOrTheirFourChannelsWhereTheRoadShaderReadsThem() {
        int tinted = 0, data = 0;
        for (int exits : new int[] { 9, 21 }) {
            var tile = BoardScenes.tile(BoardScenes.CENTER, BoardRoad.Kind.PAVED, exits, 0, BoardScene.Surface.GRASS);
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
            for (var patch : GpuRoads.patches(tile, road)) {
                if (patch.shape().isEmpty()) { continue; }
                var pixels = GpuRoads.mask(road, patch).pixels();
                var cell = GpuRoadMaskAtlas.encode(pixels);
                int width = pixels.width(), height = pixels.height();
                // A cell: the mask, its edge doubled, to whole 4-texel blocks (terrain-road.frag's step between cells).
                int cw = (width + 2 + 3) / 4 * 4, ch = (height + 2 + 3) / 4 * 4;
                int[] decoded = decode(cell);
                if (cell.tint() >= 0) {
                    tinted++;
                    assertEquals(cw, cell.width());
                    assertEquals(ch, cell.height());
                    assertEquals(pixels.rgba(0) >>> 8, cell.tint());
                } else {
                    data++;
                    assertEquals(2 * cw, cell.width());
                    assertEquals(2 * ch, cell.height());
                }
                // Coverage at the mask's texels, then red right of it, green under it, blue right of and under it.
                int[][] cells = { { 0, 0, 0 }, { cw, 0, 24 }, { 0, ch, 16 }, { cw, ch, 8 } };
                for (int c = 0; c < (cell.tint() >= 0 ? 1 : 4); c++) {
                    long error = 0;
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int expected = c == 0 ? pixels.rgba(y * width + x) & 255 : pixels.rgba(y * width + x) >>> cells[c][2] & 255;
                            int got = decoded[(cells[c][1] + y + 1) * cell.width() + cells[c][0] + x + 1];
                            assertTrue(Math.abs(got - expected) <= 32, "A BC4 block keeps each texel within an eighth of the range");
                            error += Math.abs(got - expected);
                        }
                    }
                    assertTrue(error < width * height, "On average a channel stays within one step of 255");
                }
            }
        }
        assertTrue(tinted > 0 && data > 0, "The fixtures exercise plain coats and paint as well as wear");
    }

    /** The cell's BC4 blocks decoded as a GPU decodes them (RGTC1, rounded to bytes): its texels row by row. */
    private static int[] decode(GpuRoadMaskAtlas.Cell cell) {
        int[] texels = new int[cell.width() * cell.height()];
        var blocks = cell.blocks().duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] palette = new int[8];
        for (int by = 0; by < cell.height(); by += 4) {
            for (int bx = 0; bx < cell.width(); bx += 4) {
                long code = blocks.getLong();
                int e0 = (int) (code & 255), e1 = (int) (code >>> 8 & 255);
                palette[0] = e0;
                palette[1] = e1;
                for (int i = 2; i < 8; i++) {
                    palette[i] = e0 > e1 ? Math.round(((8 - i) * e0 + (i - 1) * e1) / 7f)
                          : i < 6 ? Math.round(((6 - i) * e0 + (i - 1) * e1) / 5f) : i == 6 ? 0 : 255;
                }
                for (int i = 0; i < 16; i++) {
                    texels[(by + (i >> 2)) * cell.width() + bx + (i & 3)] = palette[(int) (code >>> (16 + 3 * i) & 7)];
                }
            }
        }
        return texels;
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static GpuRoads.MaskData mask(int argb) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        return new GpuRoads.MaskData(new BoardScene.Pixels(image), 0, 0, 1, 1);
    }
}
