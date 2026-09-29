/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Repeatable water-only measurements and lit captures of basins, shelves, an island and a connecting narrows. */
@Tag("on-demand")
class GpuWaterQualitySmokeTest {
    @Test
    void capturesAndMeasuresWaterAcrossZoomAndWind() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var shaders = new GpuShaderManager();
                var snapshot = new HashMap<String, String>();
                String path = System.getProperty("megamek.gpu.shaderSnapshot");
                try {
                    if (path != null) {
                        try (var files = Files.list(Path.of(path))) {
                            for (Path file : files.toList()) {
                                if (Files.isRegularFile(file)) { snapshot.put(file.getFileName().toString(), Files.readString(file)); }
                            }
                        }
                    }
                    shaders.run(() -> {
                        if (!snapshot.isEmpty()) { assertTrue(shaders.apply(snapshot).success()); }
                        review();
                    });
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                finally { shaders.close(); }
            }

            private void review() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    var scene = scene(Integer.getInteger("megamek.gpu.waterSize", 32));
                    var initial = new BoardCamera();
                    initial.resize(1280, 800); initial.setIsometric(true); initial.fit(scene); initial.update();
                    long start = System.nanoTime();
                    terrain.update(scene, initial.camera);
                    settle(terrain, initial);
                    System.out.printf("WATER build %.1f ms; GPU %s%n", (System.nanoTime() - start) / 1e6,
                          Gdx.gl.glGetString(GL20.GL_RENDERER));
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "quality");
                    if (!output.isDirectory() && !output.mkdirs()) { throw new IllegalStateException("Capture directory"); }
                    for (int view = 0; view < Integer.getInteger("megamek.gpu.waterViews", 4); view++) {
                        var camera = new BoardCamera();
                        camera.resize(1280, 800);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        if (view > 0) {
                            camera.camera.zoom *= view == 1 ? .22f : .1f;
                            camera.orbit(0, view == 3 ? 25 : view == 1 ? -15 : -35);
                            if (view == 3) { camera.setPerspective(true); }
                            camera.center(BoardGeometry.center(new Coords(scene.width() / 3, scene.height() / 2), 0));
                        }
                        camera.update();
                        settle(terrain, camera);
                        // Populate spatial pages before measuring; steady camera motion must not rebuild them.
                        for (int i = 0; i < 64; i++) { frame.render(terrain, camera, scene); }
                        for (float wind : new float[] { 0, .6f, 1 }) {
                            terrain.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, wind, 35));
                            for (int i = 0; i < 90; i++) { terrain.animate(1 / 30f, List.of()); }
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "view-" + view + "-wind-" + wind + ".png"));
                        }
                        terrain.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, .6f, 35));
                        terrain.animate(1 / 60f, List.of());
                        frame.render(terrain, camera, scene);
                        if (System.getProperty("megamek.gpu.shaderSnapshot") == null) {
                            var field = GpuTerrain.class.getDeclaredField("waterPages"); field.setAccessible(true);
                            var pages = (GpuWaterPages) field.get(terrain);
                            System.out.printf("WATER pages=%d atlasUploads=%d atlasMiB=%.2f maxPageBuildMs=%.3f%n",
                                  pages.rebuilds(), pages.atlasUploads(), pages.atlasBytes() / 1048576.0, pages.maxBuildNanos() / 1e6);
                            byte[] batched = ScreenUtils.getFrameBufferPixels(false);
                            pages.setEnabled(false);
                            frame.render(terrain, camera, scene);
                            byte[] original = ScreenUtils.getFrameBufferPixels(false);
                            pages.setEnabled(true);
                            frame.render(terrain, camera, scene);
                            double difference = 0; int changed = 0;
                            for (int i = 0; i < batched.length; i++) {
                                int delta = Math.abs(Byte.toUnsignedInt(batched[i]) - Byte.toUnsignedInt(original[i]));
                                difference += delta;
                                if (delta > 8) { changed++; }
                            }
                            System.out.printf("WATER atlas parity view=%d mean=%.5f changed=%d%n", view, difference / batched.length, changed);
                            assertTrue(difference / batched.length < .1, "Atlas must preserve water shading");
                            long builds = pages.rebuilds();
                            var oldFocus = camera.focus.cpy();
                            camera.center(BoardGeometry.center(new Coords(scene.width() / 3 + 1, scene.height() / 2), 0));
                            frame.render(terrain, camera, scene);
                            assertEquals(builds, pages.rebuilds(), "A camera pan must only select cached index ranges");
                            camera.center(oldFocus);
                            frame.render(terrain, camera, scene);
                        }
                        var raw = Gdx.gl;
                        var profiler = new GLProfiler(Gdx.graphics);
                        profiler.enable();
                        try (var timing = new GpuStageTimings()) {
                            for (int i = 0; i < 150; i++) {
                                Gdx.gl.glFinish();
                                Gdx.gl.glDepthMask(true);
                                Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
                                profiler.reset();
                                timing.beginFrame(i >= 30);
                                timing.stage("fft");
                                terrain.animate(1 / 60f, List.of());
                                timing.stage("water");
                                int simulationDraws = profiler.getDrawCalls();
                                terrain.renderTransparent(camera.camera);
                                timing.stage(null);
                                if (i == 149) {
                                    System.out.printf("WATER view=%d draws=%d fftDraws=%d indices=%.0f%n", view,
                                          profiler.getDrawCalls() - simulationDraws, simulationDraws, profiler.getVertexCount().total);
                                }
                            }
                            Gdx.gl.glFinish();
                            timing.poll();
                            var report = new StringBuilder();
                            timing.appendReport(report, "water-view-" + view);
                            System.out.println(report);
                        } finally { GpuStageTimings.stopCounting(profiler, raw); }
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "view-" + view + "-later.png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Water quality review", failure.get()); }
    }

    private static void settle(GpuTerrain terrain, BoardCamera camera) throws Exception {
        long deadline = System.nanoTime() + 180_000_000_000L;
        while (true) {
            boolean changed = terrain.refine(camera.camera);
            if (!changed && !terrain.busy()) { return; }
            assertTrue(System.nanoTime() < deadline, "Water LOD must settle");
            Thread.sleep(2);
        }
    }

    static BoardScene scene(int size) {
        var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff888888); }
        }
        var pixels = new BoardScene.Pixels(image);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                float u = x / (float) size, v = y / (float) size;
                float left = (float) Math.hypot((u - .30f) / .26f, (v - .5f) / .40f);
                float right = (float) Math.hypot((u - .75f) / .21f, (v - .5f) / .33f);
                boolean island = Math.hypot(u - .30f, v - .42f) < .046f;
                boolean wet = !island && (Math.min(left, right) < 1 || u > .45f && u < .62f && Math.abs(v - .5f) < .055f);
                int depth = !wet ? -1 : Math.min(left, right) > .78f ? 1 : u < .23f ? 2 : 3;
                tiles.add(new BoardScene.Tile(new Coords(x, y), !wet && island ? 2 : 0, depth, false, 0,
                      BoardScene.Surface.SAND, pixels, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, size, size, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
