/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Specular anti-aliasing (light-model.glsl) on the terrain calibration board in rain and afternoon sun, from four
 * sides: wet rock and pavement with their cliffs, water and the other ground families. The off/on pairs go to
 * build/gpu-board-review/specular-aa.
 */
@Tag("on-demand")
class GpuSpecularAntialiasingSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "specular-aa");
    private static final File BOARD = new File("data/boards/Calibration/32x34 Terrain Material Calibration.board");
    /** Rain wets the ground; at 15:00 the sun stands about as high as the isometric camera, so one side sees glints. */
    private static final BoardAtmosphere.Settings RAIN = new BoardAtmosphere.Settings(15, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, new BoardAtmosphere.Effects(.5f, 0, 0, 0, 0, 0, 0));
    private static final int[] ANGLES = { 0, 90, 180, 270 };
    /** Sub-pixel steps of the one-pixel pan. */
    private static final int STEPS = 12;
    /** Wet rock at the foot of the rock cliff bay (board hex 1023). */
    private static final Coords FOOT = new Coords(9, 22);
    /** World units per pixel of the middle view, about 20 cm of ground: the normal maps are minified. */
    private static final float MIDDLE_ZOOM = .5f;
    /**
     * About half a centimetre of ground per pixel: the rock map's 1.6 cm texels are magnified, as in free flight near the
     * ground. The board camera's nearest zoom, .05, still minifies the sculpt maps.
     */
    private static final float MAGNIFIED_ZOOM = .0125f;

    /**
     * Fitted to the window and at a middle distance, a highlight narrower than a pixel sparkles as the view moves: over
     * a slow pan, the frame-to-frame luminance change must be smaller with the widened highlights. In a close-up where
     * the maps are magnified and nothing aliases, the brightest highlights must keep their strength. Only the setting
     * differs between each off/on pair.
     */
    @Test
    void flickersLessAndKeepsResolvedHighlights() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        BoardScene scene = scene();
        double[] flicker = new double[2];
        double[][] highlights = new double[ANGLES.length][2];
        StringBuilder report = new StringBuilder("angle,fittedOff,fittedOn,middleOff,middleOn,closeTopOff,closeTopOn\n");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(RAIN);
                var units = new GpuUnitModels();
                var batch = new ModelBatch(GpuUnitShader.provider());
                try {
                    load(terrain, frame, scene);
                    var unit = standing(units.modular("units/modular/bodies/atlas.json").model(), scene, FOOT, 6);
                    int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                    for (int index = 0; index < ANGLES.length; index++) {
                        int angle = ANGLES[index];
                        BoardCamera camera = view(terrain, frame, scene, angle);
                        double[] fitted = pans(terrain, frame, camera, scene, angle + "-fitted");
                        focus(terrain, frame, camera, scene, MIDDLE_ZOOM);
                        double[] middle = pans(terrain, frame, camera, scene, angle + "-middle");
                        for (int on = 0; on < 2; on++) { flicker[on] += fitted[on] + middle[on]; }
                        focus(terrain, frame, camera, scene, MAGNIFIED_ZOOM);
                        frame.render(terrain, camera, scene, List.of(unit), batch);
                        for (int on = 0; on < 2; on++) {
                            GpuEnvironmentLighting.specularAntialiasing = on == 1;
                            frame.render(terrain, camera, scene, List.of(unit), batch);
                            GpuReviewFrame.save(new File(OUTPUT, angle + "-close" + (on == 1 ? "-on.png" : "-off.png")));
                            highlights[index][on] = brightest(luminance(width, height), .01);
                        }
                        report.append(String.format(Locale.ROOT, "%d,%.4f,%.4f,%.4f,%.4f,%.2f,%.2f%n", angle, fitted[0],
                              fitted[1], middle[0], middle[1], highlights[index][0], highlights[index][1]));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuEnvironmentLighting.specularAntialiasing = true;
                    batch.dispose();
                    units.dispose();
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Specular anti-aliasing review", failure.get()); }
        System.out.print("Flicker: mean squared luminance change per pan step; close-up: mean of the brightest 1% "
              + "(8-bit luminance)\n" + report);
        Files.writeString(new File(OUTPUT, "specular-aa.csv").toPath(), report);
        assertTrue(flicker[1] < flicker[0], "Specular anti-aliasing must reduce the pans' flicker:\n" + report);
        for (double[] closeUp : highlights) {
            assertTrue(closeUp[1] >= .95 * closeUp[0], "Resolved highlights must keep their strength:\n" + report);
        }
    }

    /** GPU timestamps of every stage, fitted and at the middle distance, off and on alternated (ABBA rounds). */
    @Test
    @Tag("gpu-benchmark")
    void specularAntialiasingTimings() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        BoardScene scene = scene();
        StringBuilder report = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1920, 1080);
        config.useVsync(false);
        config.setForegroundFPS(0);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(RAIN);
                try {
                    load(terrain, frame, scene);
                    BoardCamera camera = view(terrain, frame, scene, 0);
                    report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; window ")
                          .append(Gdx.graphics.getBackBufferWidth()).append(" x ")
                          .append(Gdx.graphics.getBackBufferHeight()).append('\n');
                    time(terrain, frame, camera, scene, "fitted", report);
                    focus(terrain, frame, camera, scene, MIDDLE_ZOOM);
                    time(terrain, frame, camera, scene, "middle distance", report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuEnvironmentLighting.specularAntialiasing = true;
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Specular anti-aliasing timings", failure.get()); }
        System.out.println(report);
        Files.writeString(new File(OUTPUT, "timings.txt").toPath(), report);
    }

    /** The board captured as a game loads it, artwork included. */
    private static BoardScene scene() throws Exception {
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(BOARD, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            return fixture.source.takeFrame().scene();
        }
    }

    /**
     * Loads the board with the frame's shader flags already set, so each material program compiles once, and waits for
     * them: on an integrated GPU this board's programs take minutes. The clock stays still from here on.
     */
    private static void load(GpuTerrain terrain, GpuReviewFrame frame, BoardScene scene) throws InterruptedException {
        BoardCamera camera = camera(scene, 0);
        frame.prepare(terrain, camera, scene);
        terrain.update(scene);
        terrain.animate(.5f, List.of());
        settle(terrain, camera);
    }

    /** The fitted isometric board turned by the angle. */
    private static BoardCamera camera(BoardScene scene, int angle) {
        var camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.orbit(angle, 0);
        camera.fit(scene);
        return camera;
    }

    /** The fitted view from the angle, its detail settled and drawn once. */
    private static BoardCamera view(GpuTerrain terrain, GpuReviewFrame frame, BoardScene scene, int angle)
          throws InterruptedException {
        BoardCamera camera = camera(scene, angle);
        frame.prepare(terrain, camera, scene);
        settle(terrain, camera);
        frame.render(terrain, camera, scene);
        return camera;
    }

    /** The same view zoomed onto the cliff foot, its detail settled and drawn once. */
    private static void focus(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene, float zoom)
          throws InterruptedException {
        camera.camera.zoom = zoom;
        camera.center(BoardGeometry.center(FOOT, scene.tile(FOOT).elevation()));
        settle(terrain, camera);
        frame.render(terrain, camera, scene);
    }

    /** Waits until the terrain has installed its detail for the camera and compiled its material programs. */
    private static void settle(GpuTerrain terrain, BoardCamera camera) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (terrain.refine(camera.camera) | terrain.busy()) {
            assertTrue(System.nanoTime() < deadline, () -> "Terrain must settle: " + terrain.buildDetails());
            Thread.sleep(5);
        }
    }

    /** The model standing on the hex, its largest side spanning the given world units. */
    private static ModelInstance standing(Model model, BoardScene scene, Coords at, float span) {
        var unit = new ModelInstance(model);
        var bounds = unit.calculateBoundingBox(new BoundingBox());
        float scale = span / Math.max(bounds.getDepth(), Math.max(bounds.getWidth(), bounds.getHeight()));
        Vector3 middle = bounds.getCenter(new Vector3()), ground = BoardGeometry.center(at, scene.tile(at).elevation());
        unit.transform.setToTranslation(ground.x - middle.x * scale, ground.y - middle.y * scale,
              ground.z - bounds.min.z * scale).scale(scale, scale, scale);
        return unit;
    }

    /** The pan's flicker without and with the setting; each run's first frame is its capture. */
    private static double[] pans(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          String name) {
        double[] result = new double[2];
        for (int on = 0; on < 2; on++) {
            GpuEnvironmentLighting.specularAntialiasing = on == 1;
            result[on] = pan(terrain, frame, camera, scene, new File(OUTPUT, name + (on == 1 ? "-on.png" : "-off.png")));
        }
        return result;
    }

    /**
     * Pans the orthographic view by one pixel in sub-pixel steps and returns the mean squared luminance change between
     * consecutive frames, in 8-bit units. Its first frame is the capture.
     */
    private static double pan(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          File capture) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Vector3 start = new Vector3(camera.focus);
        Vector3 pixel = camera.camera.unproject(new Vector3(width / 2f + 1, height / 2f, 0), 0, 0, width, height)
              .sub(camera.camera.unproject(new Vector3(width / 2f, height / 2f, 0), 0, 0, width, height));
        float[] previous = null;
        double sum = 0;
        for (int step = 0; step <= STEPS; step++) {
            camera.center(new Vector3(start).mulAdd(pixel, step / (float) STEPS));
            frame.render(terrain, camera, scene);
            if (step == 0) { GpuReviewFrame.save(capture); }
            float[] next = luminance(width, height);
            if (previous != null) {
                for (int i = 0; i < next.length; i++) {
                    double change = next[i] - previous[i];
                    sum += change * change;
                }
            }
            previous = next;
        }
        camera.center(start);
        return sum / ((double) STEPS * width * height);
    }

    /** Off and on alternated in four ABBA rounds of 80 frames, the first 20 of each unmeasured. */
    private static void time(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          String view, StringBuilder report) {
        var off = new GpuStageTimings();
        var on = new GpuStageTimings();
        try {
            for (int round = 0; round < 4; round++) {
                for (int index = 0; index < 2; index++) {
                    boolean enabled = (round + index) % 2 == 1;
                    GpuEnvironmentLighting.specularAntialiasing = enabled;
                    GpuStageTimings timings = enabled ? on : off;
                    for (int frames = 0; frames < 80; frames++) {
                        timings.beginFrame(frames >= 20);
                        frame.render(terrain, camera, scene, timings);
                        Gdx.gl.glFinish();
                    }
                }
            }
            off.appendReport(report, "Specular anti-aliasing off, calibration board in rain, " + view);
            on.appendReport(report, "Specular anti-aliasing on, calibration board in rain, " + view);
        } finally {
            off.close();
            on.close();
        }
    }

    /** The displayed frame's luminance, from its encoded 8-bit RGB. */
    private static float[] luminance(int width, int height) {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            ByteBuffer pixels = image.getPixels();
            float[] result = new float[width * height];
            for (int i = 0; i < result.length; i++) {
                result[i] = .2126f * (pixels.get(4 * i) & 255) + .7152f * (pixels.get(4 * i + 1) & 255)
                      + .0722f * (pixels.get(4 * i + 2) & 255);
            }
            return result;
        } finally {
            image.dispose();
        }
    }

    /** The mean of the brightest share of the values. */
    private static double brightest(float[] values, double share) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        int count = Math.max(1, (int) (sorted.length * share));
        double sum = 0;
        for (int i = sorted.length - count; i < sorted.length; i++) { sum += sorted[i]; }
        return sum / count;
    }
}
