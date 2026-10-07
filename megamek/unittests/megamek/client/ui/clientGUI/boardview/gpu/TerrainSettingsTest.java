/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class TerrainSettingsTest {
    @Test
    void shutdownCancelsGeometryInsideARunningTerrainTask() throws Exception {
        TerrainSettings settings = TerrainSettings.capture();
        var scene = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.SAND);
        var tile = scene.tile(new Coords(4, 4));
        CountDownLatch started = new CountDownLatch(1), resume = new CountDownLatch(1);
        try (var workers = TerrainSettings.workers(2)) {
            var result = CompletableFuture.runAsync(() -> settings.run(() -> {
                started.countDown();
                try { resume.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                // Already inside the hex's task: there is no outer per-hex cancellation check here.
                assertThrows(CancellationException.class, () -> new BoardSurface(scene, tile, TerrainLod.FULL));
                assertTrue(Thread.currentThread().isInterrupted(), "Geometry must preserve the shutdown interrupt");
            }), workers);
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                workers.shutdownNow();
                result.get(10, TimeUnit.SECONDS);
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                resume.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test
    void aRunningBuildKeepsItsGeometryWhenControlsChangeAndReleasesItsScope() throws Exception {
        GdxNativesLoader.load();
        TerrainSettings original = TerrainSettings.capture();
        var scene = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.SAND);
        var tile = scene.tile(new Coords(4, 4));
        var expected = new BoardSurface(scene, tile, TerrainLod.COARSE).faces;
        CountDownLatch entered = new CountDownLatch(1), changed = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var future = worker.submit(() -> original.call(() -> {
                entered.countDown();
                try { assertTrue(changed.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return new BoardSurface(scene, tile, TerrainLod.COARSE).faces;
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var g = original.geometry();
                BoardGeometry.tune(new BoardGeometry.Tuning(g.hexScale() * 1.5f, g.unitScale(), g.unitHeightScale(),
                      g.levelHeight() + 4, g.gridShade(), g.multiHexUnitScale(), g.transitions(), g.padding()));
                BoardConcrete.tune(BoardConcrete.Mode.OFF);
                changed.countDown();
                assertEquals(expected, future.get(30, TimeUnit.SECONDS));
                assertNotEquals(expected, new BoardSurface(scene, tile, TerrainLod.COARSE).faces);
                float liveWidth = BoardGeometry.width();
                assertEquals(liveWidth, worker.submit(BoardGeometry::width).get());
                assertThrows(IllegalStateException.class, () -> original.run(() -> { throw new IllegalStateException(); }));
                assertEquals(liveWidth, BoardGeometry.width(), "Exceptional work must also restore its caller's settings");
            } finally {
                changed.countDown();
                BoardGeometry.tune(original.geometry());
                BoardConcrete.tune(original.concrete());
            }
        }
    }
}
