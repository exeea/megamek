/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Same-scene comparison with the saved pre-rewrite shader, including actual HDR composition and GPU timings. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuIceBenchmarkSmokeTest {
    private static final int WIDTH = Integer.getInteger("megamek.gpu.iceWidth", 1200);
    private static final int HEIGHT = Integer.getInteger("megamek.gpu.iceHeight", 900);
    private static final int SAMPLES = Integer.getInteger("megamek.gpu.iceSamples", 40);
    private static final int WARMUP = 24;
    private record View(String name, Coords focus, float zoom, float tilt, boolean perspective,
                        boolean normals, float hour) { }
    private static final List<View> VIEWS = List.of(
          new View("lake-overhead", new Coords(4, 2), .16f, 0, false, true, 13),
          new View("lake-oblique", new Coords(4, 2), .16f, 68, false, true, 13),
          new View("lake-perspective", new Coords(4, 2), .16f, 68, true, true, 13),
          new View("lake-flat-normals", new Coords(4, 2), .16f, 68, false, false, 13),
          new View("lake-sunrise", new Coords(4, 2), .16f, 68, false, true, 6.5f),
          new View("land-border", new Coords(6, 2), .12f, 50, false, true, 13),
          new View("black-ice", new Coords(1, 2), .12f, 50, false, true, 13),
          new View("overview", new Coords(3, 2), .55f, 50, false, true, 13));

    @Test
    void comparesSavedIceShaderPixelsAndCostOnTheSameScene() throws Exception {
        assertTrue(SAMPLES >= 40, "iceSamples must provide at least 20 measured frames per ABBA block (40 total per variant)");
        Path baseline = Path.of(System.getProperty("megamek.gpu.iceBaseline",
              "../build/shader-audit/baseline/terrain-ice.glsl"));
        assertTrue(Files.isRegularFile(baseline), "Save the original ice shader before running the comparison: " + baseline);
        // The old Java composer appended this wrapper. The revised shader owns it so that both optical work and
        // whether a slab needs its base shading can be compared through one source replacement on the same scene.
        String original = Files.readString(baseline);
        if (!Boolean.getBoolean("megamek.gpu.iceBaselineIncludesMain")) {
            original += """

              void main() {
                  iceBaseSurface();
              #ifdef iceFlag
                  fragColor.rgb = iceFinish(fragColor.rgb);
              #endif
              }
              """;
        }
        String baselineSource = original;
        BoardScene scene = GpuIceSmokeTest.scene(2);
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(WIDTH, HEIGHT);
        configuration.useVsync(false);
        configuration.setForegroundFPS(0);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> check(manager, scene, baselineSource)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Ice shader comparison", failure.get()); }
    }

    private static void check(GpuShaderManager manager, BoardScene scene, String original) {
        boolean materialChanges = Boolean.getBoolean("megamek.gpu.iceMaterialChanges");
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(settings(13));
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "ice-benchmark");
        var report = new StringBuilder("One frozen renderer; saved original versus current ice shader.\n")
              .append("RGBA16F scene, production atmosphere/display boundary; 24 warm frames per shader block.\n")
              .append("Two alternating blocks per variant, synchronized frame medians; GPU stages reported separately.\n")
              .append(SAMPLES).append(" measured frames per variant; at least 20 per block, with query drain after each frame.\n")
              .append(materialChanges
                    ? "Material-change review: original/revised image differences are diagnostic; frozen repeats stay strict.\n"
                    : "Parity review: original/revised comparisons and frozen repeats are strict.\n")
              .append("Strict image limits: maximum channel difference 1, mean channel difference .001.\n")
              .append("glFinish and query collection are outside GPU stages; synchronized frame time includes glFinish.\n");
        var issues = new ArrayList<String>();
        try {
            assertTrue(output.isDirectory() || output.mkdirs());
            // Shader replacement must not change the depth order of otherwise identical scene geometry.
            var batchField = GpuTerrain.class.getDeclaredField("batch");
            batchField.setAccessible(true);
            var sorter = ModelBatch.class.getDeclaredField("sorter");
            sorter.setAccessible(true);
            sorter.set(batchField.get(terrain), new DefaultRenderableSorter());
            report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append('\n');
            terrain.setAtmosphere(frame.lighting());
            terrain.setWind(BoardAtmosphere.Effects.NONE);
            terrain.update(scene);
            terrain.animate(.37f, List.of());
            for (View view : VIEWS) {
                var camera = new BoardCamera();
                camera.resize(WIDTH, HEIGHT);
                camera.fit(scene);
                camera.setIsometric(false);
                camera.tilt(view.tilt());
                camera.setPerspective(view.perspective());
                camera.camera.zoom = view.zoom();
                camera.center(BoardGeometry.center(view.focus(), 0));
                camera.update();
                frame.configure(settings(view.hour()));
                terrain.setNormalMaps(view.normals());
                long prepareStart = System.nanoTime();
                frame.prepare(terrain, camera, scene);
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (terrain.refine(camera.camera) || terrain.busy()) {
                    assertTrue(System.nanoTime() < deadline, "Ice comparison must settle its scene before timing");
                    frame.render(terrain, camera, scene);
                }
                report.append(String.format(Locale.ROOT, "%s scene preparationMs=%.3f%n", view.name(),
                      (System.nanoTime() - prepareStart) / 1e6));
                byte[][] images = new byte[4][];
                double[][] times = new double[2][SAMPLES];
                // Reversing the second pair's order reduces bias from temperature/clock changes during a run.
                for (int block = 0; block < 4; block++) {
                    int variant = block == 0 || block == 3 ? 0 : 1;
                    long compileStart = System.nanoTime();
                    var result = manager.apply(variant == 0 ? Map.of("terrain-ice.glsl", original) : Map.of());
                    assertTrue(result.success(), result.message());
                    String name = view.name() + (variant == 0 ? "-original" : "-revised") + "-" + block;
                    report.append(String.format(Locale.ROOT, "%s shader rebuild programs=%d elapsedMs=%.3f%n", name,
                          result.updated(), (System.nanoTime() - compileStart) / 1e6));
                    try (var timings = new GpuStageTimings()) {
                        for (int i = 0; i < WARMUP; i++) {
                            timings.beginFrame(false);
                            frame.render(terrain, camera, scene, timings);
                            Gdx.gl.glFinish();
                            timings.poll();
                        }
                        int count = block < 2 ? SAMPLES / 2 : SAMPLES - SAMPLES / 2;
                        int offset = block < 2 ? 0 : SAMPLES / 2;
                        for (int i = 0; i < count; i++) {
                            timings.beginFrame();
                            long start = System.nanoTime();
                            frame.render(terrain, camera, scene, timings);
                            Gdx.gl.glFinish();
                            times[variant][offset + i] = (System.nanoTime() - start) / 1e6;
                            timings.poll();
                        }
                        images[block] = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
                              Gdx.graphics.getBackBufferHeight(), false);
                        GpuReviewFrame.save(new File(output, name + ".png"));
                        var blockReport = new StringBuilder();
                        timings.appendReport(blockReport, name);
                        report.append(blockReport);
                        assertTrue(blockReport.indexOf("; dropped samples: 0\n") >= 0, blockReport::toString);
                        assertTrue(blockReport.indexOf("opaque terrain and plants,") >= 0, blockReport::toString);
                        assertTrue(blockReport.indexOf("transparent,") >= 0, blockReport::toString);
                        blockReport.toString().lines().skip(2).forEach(line -> assertEquals(count,
                              Integer.parseInt(line.split(",")[1]), name + " must collect every stage sample: " + line));
                    }
                }
                compare(images[0], images[3], view.name() + " repeated original", report, issues, true);
                compare(images[1], images[2], view.name() + " repeated revised", report, issues, true);
                compare(images[0], images[1], view.name() + " original/revised", report, issues, !materialChanges);
                for (int variant = 0; variant < 2; variant++) {
                    Arrays.sort(times[variant]);
                    report.append(String.format(Locale.ROOT, "%s %s: medianMs=%.4f p95Ms=%.4f%n", view.name(),
                          variant == 0 ? "original" : "revised", times[variant][SAMPLES / 2],
                          times[variant][Math.min(SAMPLES - 1, (int) (SAMPLES * .95))]));
                }
                Files.writeString(output.toPath().resolve("timings.txt"), report);
            }
            var targetsField = GpuShaderManager.class.getDeclaredField("targets");
            targetsField.setAccessible(true);
            int icePrograms = 0;
            var unique = new HashSet<String>();
            var generic = new HashSet<String>();
            for (Object value : (Iterable<?>) targetsField.get(manager)) {
                for (var source : ((GpuShaderManager.Target) value).sources()) {
                    if (!source.fragment().contains("#define iceFlag")) { continue; }
                    icePrograms++;
                    String stages = source.vertex() + "\n" + source.fragment();
                    unique.add(stages);
                    generic.add(stages.replaceAll("(?m)^#define ICE_MODE [^\\r\\n]*\\R", ""));
                }
            }
            report.append("Ice programs=").append(icePrograms).append(", unique source pairs=").append(unique.size())
                  .append(", source pairs without mode specialization=").append(generic.size()).append('\n');
            report.append("Rebuild times include the current driver cache; preparation measures per-view refinement\n")
                  .append("after the initial terrain update/animation, not full asset/geometry startup.\n");
            Files.writeString(output.toPath().resolve("timings.txt"), report);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            assertTrue(issues.isEmpty(), String.join("\n", issues));
        } catch (Exception error) { throw new IllegalStateException(error); }
        finally { frame.dispose(); terrain.dispose(); }
        System.out.print(report);
    }

    private static void compare(byte[] a, byte[] b, String name, StringBuilder report, List<String> issues, boolean strict) {
        long difference = 0;
        int maximum = 0, changed = 0;
        for (int i = 0; i < a.length; i += 4) {
            int pixel = 0;
            for (int channel = 0; channel < 3; channel++) {
                int delta = Math.abs(Byte.toUnsignedInt(a[i + channel]) - Byte.toUnsignedInt(b[i + channel]));
                difference += delta;
                pixel = Math.max(pixel, delta);
            }
            maximum = Math.max(maximum, pixel);
            if (pixel > 0) { changed++; }
        }
        double average = difference / (a.length * .75);
        String result = String.format(Locale.ROOT, "%s: changed=%d max=%d meanChannelError=%.8f%n",
              name, changed, maximum, average);
        report.append(result);
        // Frozen repeats always remain strict; only an explicit material review permits cross-version visual changes.
        if (strict && (maximum > 1 || average > .001)) { issues.add(result); }
    }

    private static BoardAtmosphere.Settings settings(float hour) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }
}
