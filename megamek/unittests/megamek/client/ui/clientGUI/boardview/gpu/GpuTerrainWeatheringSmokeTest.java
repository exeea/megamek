/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/** Native material-family gallery: continuous ambient intensity, cover erosion and reversible controls. */
@Tag("on-demand")
class GpuTerrainWeatheringSmokeTest {
    @Test
    void weatheringFollowsEveryGroundFamilyInBothGameplayViews() throws Exception {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "weathering");
        Files.createDirectories(output.toPath());
        Map<String, String> baseline = new HashMap<>();
        String directory = System.getProperty("megamek.gpu.weatheringBaseline", "");
        if (!directory.isBlank()) {
            for (String name : List.of("terrain-materials.glsl", "terrain-sculpt.frag")) {
                baseline.put(name, Files.readString(Path.of(directory, name)));
            }
        }
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        config.useVsync(false);
        config.setForegroundFPS(0);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var shaders = new GpuShaderManager();
                try {
                    shaders.run(() -> {
                        try { review(output, shaders, baseline); }
                        catch (Throwable error) { failure.set(error); }
                    });
                } finally { shaders.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain weathering review", failure.get()); }
    }

    private static void review(File output, GpuShaderManager shaders, Map<String, String> baseline) throws Exception {
        var report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; 1440x1080\n")
              .append("Terrain fixture without woods, units or buildings; automatic ground cover/scatter retained.\n")
              .append("Matched weathering at 0, .25, .5, .75 and 1; no combat history.\n");
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        try {
            String selected = System.getProperty("megamek.gpu.weatheringFamily", "");
            var families = selected.isBlank() ? List.of(BoardScene.Surface.values())
                  : List.of(BoardScene.Surface.valueOf(selected.toUpperCase(Locale.ROOT)));
            for (var family : families) {
                var terrain = new GpuTerrain();
                try {
                    var layout = GpuTerrainShowcaseSmokeTest.scene(family);
                    var tiles = layout.tiles().stream().map(tile -> new BoardScene.Tile(tile.coords(), tile.elevation(),
                          tile.waterDepth(), tile.frozen(), tile.roadExits(), tile.surface(), tile.ground(),
                          null, null, null, null, List.of(), List.of(), tile.liquid(), null, true)).toList();
                    var scene = new BoardScene(0, layout.width(), layout.height(), tiles, List.of(), List.of(), -1, "", List.of());
                    terrain.update(scene);
                    terrain.setCombatScars(false);
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (boolean iso : new boolean[] { false, true }) {
                        String name = family.name().toLowerCase(Locale.ROOT) + (iso ? "-iso" : "-top");
                        camera.setIsometric(iso);
                        camera.camera.zoom = BoardRelief.metres(.04f);
                        camera.center(BoardGeometry.center(new Coords(7, 3), 3));
                        frame.prepare(terrain, camera, scene);
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        terrain.setTerrainWear(0);
                        frame.render(terrain, camera, scene);
                        byte[] clean = ScreenUtils.getFrameBufferPixels(false);
                        GpuReviewFrame.save(new File(output, name + "-off.png"));
                        terrain.setTerrainWear(1);
                        frame.render(terrain, camera, scene);
                        byte[] worn = ScreenUtils.getFrameBufferPixels(false);
                        GpuReviewFrame.save(new File(output, name + "-on.png"));
                        // The unified slider retains the previous full-strength mix, except revised concrete grime.
                        if (!baseline.isEmpty() && family != BoardScene.Surface.CONCRETE) {
                            var result = shaders.apply(baseline);
                            assertTrue(result.success(), result.message());
                            frame.render(terrain, camera, scene);
                            assertArrayEquals(worn, ScreenUtils.getFrameBufferPixels(false), name + ": keep accepted weathering");
                            result = shaders.apply(Map.of());
                            assertTrue(result.success(), result.message());
                        }
                        double difference = meanDifference(clean, worn);
                        report.append(String.format(Locale.ROOT, "%s: mean RGB byte change %.4f%n", name, difference));
                        if (family == BoardScene.Surface.SAND) {
                            assertArrayEquals(clean, worn, "Sand is a terrain cover and has no ambient weathering");
                        } else {
                            assertTrue(difference > .03, name + ": each ground family needs visible weathering");
                        }
                        for (float intensity : new float[] { .25f, .5f, .75f }) {
                            terrain.setTerrainWear(intensity);
                            frame.render(terrain, camera, scene);
                            byte[] partial = ScreenUtils.getFrameBufferPixels(false);
                            GpuReviewFrame.save(new File(output, name + "-" + Math.round(intensity * 100) + ".png"));
                            double change = meanDifference(clean, partial);
                            report.append(String.format(Locale.ROOT, "%s at %.2f: mean RGB byte change %.4f%n",
                                  name, intensity, change));
                            if (family == BoardScene.Surface.SAND) {
                                assertArrayEquals(clean, partial, name + ": sand keeps its own cover");
                            } else {
                                assertTrue(change > .01, name + ": partial intensity must already be visible");
                                assertTrue(meanDifference(partial, worn) > .01, name + ": partial is not full intensity");
                            }
                            if (family == BoardScene.Surface.CONCRETE) {
                                assertEquals(intensity, change / difference, .08,
                                      name + ": concrete intensity scales continuously, including below .5");
                            }
                        }
                        terrain.setTerrainWear(0);
                        frame.render(terrain, camera, scene);
                        assertArrayEquals(clean, ScreenUtils.getFrameBufferPixels(false), name + ": reversible toggle");
                        if (Boolean.getBoolean("megamek.gpu.measureWeathering")
                              && (family == BoardScene.Surface.CONCRETE || family == BoardScene.Surface.DESERT)) {
                            if (!baseline.isEmpty()) {
                                var result = shaders.apply(baseline);
                                assertTrue(result.success(), result.message());
                                measure(frame, terrain, camera, scene, report, name + " previous", new float[] { 1, 1 });
                                result = shaders.apply(Map.of());
                                assertTrue(result.success(), result.message());
                            }
                            measure(frame, terrain, camera, scene, report, name, new float[] { 1, .5f, 0, 0, .5f, 1 });
                        }
                        // Fine pigment fades out, but metre-scale exposed-bedrock patches must remain legible.
                        camera.camera.zoom = BoardRelief.metres(2.5f);
                        camera.update();
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        terrain.setTerrainWear(0);
                        frame.render(terrain, camera, scene);
                        byte[] distant = ScreenUtils.getFrameBufferPixels(false);
                        terrain.setTerrainWear(1);
                        frame.render(terrain, camera, scene);
                        byte[] distantWorn = ScreenUtils.getFrameBufferPixels(false);
                        if (family == BoardScene.Surface.CONCRETE || family == BoardScene.Surface.SAND) {
                            assertArrayEquals(distant, distantWorn, name + ": distant fine-detail fade");
                        } else if (family == BoardScene.Surface.DESERT) {
                            assertTrue(meanDifference(distant, distantWorn) > .01, name + ": broad exposed-rock patches persist");
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), name);
                    }
                    assertEquals(0, terrain.groundDamage().marks(), "Natural wear is not combat history");
                    Files.writeString(new File(output, "report.txt").toPath(), report);
                } finally { terrain.dispose(); }
            }
        } finally { frame.dispose(); }
    }

    private static double meanDifference(byte[] a, byte[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i += 4) {
            for (int c = 0; c < 3; c++) { sum += Math.abs((a[i + c] & 255) - (b[i + c] & 255)); }
        }
        return sum / (a.length * .75);
    }

    /** Alternating order, GPU timestamps; profiler counts separately from timed draws. Not full-game FPS. */
    private static void measure(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene,
          StringBuilder report, String name, float[] intensities) {
        GL20 rawGl20 = Gdx.gl20;
        var profiler = new GLProfiler(Gdx.graphics);
        try (var timings = new GpuStageTimings()) {
            int draws = -1;
            double vertices = -1;
            for (float intensity : intensities) {
                terrain.setTerrainWear(intensity);
                profiler.reset();
                profiler.enable();
                frame.render(terrain, camera, scene);
                GpuStageTimings.stopCounting(profiler, rawGl20);
                if (draws >= 0) {
                    assertEquals(draws, profiler.getDrawCalls(), "Weathering must not add draws");
                    assertEquals(vertices, (double) profiler.getVertexCount().total, "Weathering must not add geometry");
                }
                draws = profiler.getDrawCalls();
                vertices = profiler.getVertexCount().total;
                for (int i = 0; i < 300; i++) {
                    timings.beginFrame(i >= 60);
                    timings.stage("terrain frame");
                    frame.render(terrain, camera, scene);
                    timings.stage(null);
                    Gdx.gl.glFinish();
                }
                timings.appendReport(report, name + " intensity " + intensity);
            }
            report.append(name).append(": draws=").append(draws).append("; vertices=").append(vertices).append('\n');
        } finally { GpuStageTimings.stopCounting(profiler, rawGl20); }
    }
}
