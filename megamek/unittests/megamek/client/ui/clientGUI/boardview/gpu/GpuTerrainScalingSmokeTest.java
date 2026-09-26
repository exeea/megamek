/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Interleaved submission comparison on exactly the same installed terrain and camera, with actual GL counters. */
@Tag("on-demand")
class GpuTerrainScalingSmokeTest {
    @Test
    void measuresProjectedTerrainAndMaterialStateReuse() throws Exception {
        String path = System.getProperty("megamek.gpu.performanceBoard", "");
        assumeTrue(!path.isEmpty());
        Board board = new Board();
        board.load(new File(path));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                try { ((Timer) field(fixture.source, "timer")).stop(); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 900);
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    FrameBuffer target = new FrameBuffer(Pixmap.Format.RGBA8888, 1280, 900, true);
                    try {
                        BoardScene scene = fixture.source.takeFrame().scene();
                        BoardCamera camera = new BoardCamera();
                        camera.resize(1280, 900);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        long start = System.nanoTime();
                        terrain.update(scene, camera.camera);
                        System.out.printf("TERRAIN size=%dx%d renderer=%s open=%.3f ms%n", scene.width(), scene.height(),
                              Gdx.gl.glGetString(GL20.GL_RENDERER), (System.nanoTime() - start) / 1e6);
                        ModelBatch batch = (ModelBatch) field(terrain, "batch");
                        GpuTerrainBatch grouping = (GpuTerrainBatch) batch.getRenderableSorter();
                        for (String view : new String[] { "overview", "close" }) {
                            if (view.equals("close")) {
                                camera.camera.zoom = .5f;
                                camera.center(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
                                long deadline = System.nanoTime() + 120_000_000_000L;
                                double maximum = 0;
                                while (true) {
                                    start = System.nanoTime();
                                    boolean changed = terrain.refine(camera.camera);
                                    maximum = Math.max(maximum, (System.nanoTime() - start) / 1e6);
                                    if (!changed && field(terrain, "detailJob") == null) { break; }
                                    assertTrue(System.nanoTime() < deadline, "Visible detail must settle");
                                    Thread.sleep(5);
                                }
                                System.out.printf("TERRAIN close maximum render-thread detail work=%.3f ms%n", maximum);
                            }
                            terrain.animate(0, List.of());
                            terrain.renderShadows(camera.camera, List.of());
                            target.begin();
                            for (int round = 0; round < 2; round++) {
                                for (boolean enabled : new boolean[] { false, true }) {
                                    grouping.setEnabled(enabled);
                                    for (int i = 0; i < 90; i++) { draw(terrain, camera); }
                                    Gdx.gl.glFinish();
                                    double[] times = new double[300];
                                    long began = System.nanoTime();
                                    for (int i = 0; i < times.length; i++) {
                                        start = System.nanoTime();
                                        draw(terrain, camera);
                                        times[i] = (System.nanoTime() - start) / 1e6;
                                    }
                                    Gdx.gl.glFinish();
                                    double mean = (System.nanoTime() - began) / 1e6 / times.length;
                                    Arrays.sort(times);
                                    GL20 rawGl20 = Gdx.gl20;
                                    GLProfiler profiler = new GLProfiler(Gdx.graphics);
                                    profiler.enable();
                                    draw(terrain, camera);
                                    System.out.printf("TERRAIN view=%s round=%d batching=%s mean=%.3f cpuMedian=%.3f p95=%.3f draws=%d glCalls=%d indices=%.0f%n",
                                          view, round, enabled, mean, times[150], times[285], profiler.getDrawCalls(),
                                          profiler.getCalls(), profiler.getVertexCount().total);
                                    GpuStageTimings.stopCounting(profiler, rawGl20);
                                }
                            }
                            target.end();
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally { target.dispose(); terrain.dispose(); Gdx.app.exit(); }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Terrain scaling benchmark", failure.get()); }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void draw(GpuTerrain terrain, BoardCamera camera) {
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(.2f, .26f, .31f, 1, true);
        terrain.render(camera.camera, false);
    }
}
