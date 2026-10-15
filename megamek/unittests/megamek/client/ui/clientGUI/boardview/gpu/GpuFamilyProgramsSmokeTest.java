/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
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
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.Atmosphere;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Terrain family programs (GpuFamilyPrograms) against their generic programs, within one 8-bit step in colour and
 * alpha and one depth step: on the terrain calibration board with its 32 most-drawn family programs, and on a small
 * board where grass, dirt and cooled lava meet, which builds the plain, ordinary-blend and mixed volcanic kinds. A
 * second calibration load takes every program from the program cache. On the small board a newly painted combination
 * draws at once with its generic program, never waits and links nothing: family programs build only behind the
 * loading stage. A lighting change while a family link is pending, and back, draws no stale program, leaks no link and
 * keeps the provider's family programs within the capacity. The tests share one program cache; in
 * this order the small board loads most programs the calibration board compiled. Reports go to
 * build/gpu-board-review/family-programs.
 */
@Tag("on-demand")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GpuFamilyProgramsSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "family-programs");
    private static final File CALIBRATION =
          new File("data/boards/Calibration/32x34 Terrain Material Calibration.board");
    /** One step of the 24-bit depth buffer. */
    private static final double DEPTH_STEP = 1.0 / ((1 << 24) - 1);
    /** A frame that compiled a program on the render thread would take seconds on the integrated GPUs we target. */
    private static final double LONGEST_EDIT_FRAME_MS = 250;
    /** Frames slower than this while family programs build are listed in the report. */
    private static final double SLOW_FRAME_MS = 200;
    /** Pixel comparisons that failed in the running test; run() fails the test after its other checks. */
    private static final List<String> PIXEL_FAILURES = new java.util.ArrayList<>();
    /** A moonless night: no direct light, so the terrain draws without a shadow map, a lighting change. */
    private static final BoardAtmosphere.Settings NIGHT = new BoardAtmosphere.Settings(0, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25, false);
    @TempDir static Path directory;

    @Test
    @Order(1)
    void calibrationFamilyProgramsDrawTheGenericFrameAndComeFromTheCache() throws Exception {
        BoardScene scene;
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(CALIBRATION, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        StringBuilder report = new StringBuilder();
        run("calibration", report, () -> {
            var cache = GpuProgramCache.open(directory.resolve("cache"));
            GpuGlsl.programCache = cache;
            report.append(String.format(Locale.ROOT, "%s, %s%n", Gdx.gl.glGetString(GL20.GL_RENDERER),
                  Gdx.gl.glGetString(GL20.GL_VERSION)));
            System.setProperty(GpuFamilyPrograms.PROPERTY, "false");
            Frame generic = load(scene, "calibration-generic", report);
            // Control: a second generic build must match the first, or the comparison below cannot be judged.
            compare("Generic twice", generic, load(scene, "calibration-control", report), report);
            System.setProperty(GpuFamilyPrograms.PROPERTY, "true");
            Frame family = load(scene, "calibration-family", report);
            assertTrue(family.ready() > 0, "The calibration board builds family programs");
            compare("Generic against family", generic, family, report);
            if (cache == null) {
                report.append("No program binary format: the warm load is skipped.\n");
                return;
            }
            int stored = cache.stored.get(), loaded = cache.loaded.get();
            Frame warm = load(scene, "calibration-warm", report);
            report.append(String.format(Locale.ROOT, "Warm load: %d programs loaded, %d compiled%n",
                  cache.loaded.get() - loaded, cache.stored.get() - stored));
            assertEquals(stored, cache.stored.get(), "A second load compiles no program");
            assertEquals(family.ready(), warm.ready(), "and builds the same family programs");
            assertTrue(difference(family.pixels(), warm.pixels()) <= 1, "Cached family programs draw the same");
        });
    }

    @Test
    @Order(2)
    void smallBoardMatchesAndAPaintedCombinationNeverWaits() throws Exception {
        Board board = Board.createEmptyBoard(16, 17);
        // Grass, dirt and cooled lava meet: plain, ordinary-blend and mixed volcanic family programs, under the cap.
        board.setHex(new Coords(4, 4), new Hex(0, "", "dirt"));
        board.setHex(new Coords(5, 4), new Hex(0, "magma:1", ""));
        Coords painted = new Coords(10, 10);
        StringBuilder report = new StringBuilder();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene before = fixture.source.takeFrame().scene();
            run("small-board", report, () -> {
                var cache = GpuProgramCache.open(directory.resolve("cache"));
                GpuGlsl.programCache = cache;
                System.setProperty(GpuFamilyPrograms.PROPERTY, "false");
                Frame generic = load(before, "small-generic", report);
                System.setProperty(GpuFamilyPrograms.PROPERTY, "true");
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    BoardCamera camera = camera(before);
                    settle(terrain, frame, camera, before);
                    finish(terrain, frame, camera, before, "small-family", report);
                    compare("Generic against family", generic, capture(terrain, frame, camera, before, "small-family"),
                          report);
                    List<float[]> built = terrain.familyPrograms().ready();
                    report.append("Small board family programs: ")
                          .append(built.stream().map(Arrays::toString).toList()).append('\n');
                    assertTrue(built.stream().anyMatch(GpuFamilyProgramsSmokeTest::mixedVolcanic),
                          "Grass, dirt and cooled lava build a mixed volcanic family program");

                    // Paint a combination the board has not had: its chunk installs with the generic program, which
                    // it keeps until the next load; nothing links while editing.
                    int ready = built.size(), stored = cache == null ? 0 : cache.stored.get();
                    SwingUtilities.invokeAndWait(() -> {
                        fixture.game.getBoard().setHex(painted, new Hex(0, "", "lunar"));
                        fixture.source.refresh();
                    });
                    BoardScene after = fixture.source.takeFrame().scene();
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                    long start = System.nanoTime(), installed = 0;
                    int waiting = -1, frames = 0;
                    double longest = 0;
                    terrain.update(after, camera.camera);
                    do {
                        long frameStart = System.nanoTime();
                        terrain.refine(camera.camera, true);
                        frame.render(terrain, camera, after);
                        longest = Math.max(longest, (System.nanoTime() - frameStart) / 1e6);
                        frames++;
                        if (installed == 0 && !terrain.busy()
                              && terrain.presentation(after).tile(painted).equals(after.tile(painted))) {
                            installed = System.nanoTime();
                            waiting = terrain.familyPrograms().pending();
                        }
                        assertTrue(System.nanoTime() < deadline, "The painted combination must install");
                    } while (installed == 0 || frames < 120);
                    report.append(String.format(Locale.ROOT, "Paint: installed after %d ms with %d family programs"
                                + " waiting; %d -> %d ready after %d ms and %d frames, %d compiled; longest frame"
                                + " %.1f ms%n", (installed - start) / 1_000_000, waiting, ready,
                          terrain.familyPrograms().ready().size(), (System.nanoTime() - start) / 1_000_000, frames,
                          cache == null ? -1 : cache.stored.get() - stored, longest));
                    assertTrue(waiting > 0, "The painted combination draws with its generic program");
                    assertEquals(ready, terrain.familyPrograms().ready().size(), "and keeps it while editing");
                    if (cache != null) { assertEquals(stored, cache.stored.get(), "Nothing links while editing"); }
                    assertTrue(longest < LONGEST_EDIT_FRAME_MS, "No frame waits for a family program");
                } finally {
                    frame.dispose();
                    terrain.dispose();
                }
            });
        }
    }

    @Test
    @Order(3)
    void lightingChangesWhileAFamilyLinkIsPendingLeaveNoStaleProgramNoLeakAndTheCapacity() throws Exception {
        Board board = Board.createEmptyBoard(16, 17);
        board.setHex(new Coords(4, 4), new Hex(0, "", "dirt"));
        board.setHex(new Coords(5, 4), new Hex(0, "magma:1", ""));
        StringBuilder report = new StringBuilder();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            run("lifecycle", report, () -> {
                GpuGlsl.programCache = GpuProgramCache.open(directory.resolve("cache"));
                System.setProperty(GpuFamilyPrograms.PROPERTY, "false");
                Frame generic = load(scene, "lifecycle-generic", report);
                System.setProperty(GpuFamilyPrograms.PROPERTY, "true");
                // A capacity the small board exceeds, so the toggles below reach it.
                int capacity = GpuFamilyPrograms.capacity;
                GpuFamilyPrograms.capacity = 3;
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    BoardCamera camera = camera(scene);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene, camera.camera);
                    terrain.animate(.5f, List.of());
                    // Day, then night as soon as a family link is pending, then day again.
                    boolean night = false;
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                    while (terrain.refine(camera.camera) | terrain.busy() || terrain.familyPrograms().pending() > 0) {
                        if (!night && terrain.familyPrograms().building()) {
                            frame.configure(NIGHT);
                            frame.prepare(terrain, camera, scene);
                            night = true;
                        }
                        assertTrue(System.nanoTime() < deadline, "The night must settle");
                        Thread.sleep(5);
                    }
                    assertTrue(night, "A family link was pending when the lighting changed");
                    assertTrue(terrain.environment().shadowMap == null, "The night has no shadow map");
                    int afterNight = terrain.familyPrograms().programs();
                    frame.configure(BoardAtmosphere.DEFAULTS);
                    frame.prepare(terrain, camera, scene);
                    while (terrain.refine(camera.camera) | terrain.busy() || terrain.familyPrograms().pending() > 0) {
                        assertTrue(System.nanoTime() < deadline, "The day must settle");
                        Thread.sleep(5);
                    }
                    report.append(String.format(Locale.ROOT, "Lifecycle: %d family programs after the night, %d after"
                                + " the day again, %d in the provider; %d ready%n", afterNight,
                          terrain.familyPrograms().programs(), familyProgramsIn(terrain),
                          terrain.familyPrograms().ready().size()));
                    compare("Day again against generic", generic, capture(terrain, frame, camera, scene,
                          "lifecycle-day"), report);
                    assertTrue(terrain.familyPrograms().programs() <= 3, "The capacity bounds the programs built");
                    assertEquals(terrain.familyPrograms().programs(), familyProgramsIn(terrain),
                          "and the provider holds exactly those");
                    assertEquals(0, unfinishedLinks(terrain), "No link is left behind");

                    // Edits and more changes: the provider's family programs stay within the capacity.
                    for (int round = 0; round < 2; round++) {
                        int hex = round;
                        SwingUtilities.invokeAndWait(() -> {
                            fixture.game.getBoard().setHex(new Coords(10 + hex, 10), new Hex(0, "", "lunar"));
                            fixture.source.refresh();
                        });
                        terrain.update(fixture.source.takeFrame().scene(), camera.camera);
                        for (var settings : List.of(NIGHT, BoardAtmosphere.DEFAULTS)) {
                            frame.configure(settings);
                            frame.prepare(terrain, camera, scene);
                            while (terrain.refine(camera.camera) | terrain.busy()
                                  || terrain.familyPrograms().pending() > 0 && terrain.familyPrograms().building()) {
                                assertTrue(System.nanoTime() < deadline, "Edits and changes must settle");
                                Thread.sleep(5);
                            }
                        }
                    }
                    report.append(String.format(Locale.ROOT, "After edits and changes: %d family programs, %d in the"
                          + " provider%n", terrain.familyPrograms().programs(), familyProgramsIn(terrain)));
                    assertTrue(familyProgramsIn(terrain) <= 3, "Edits and changes keep the capacity");
                    assertEquals(0, unfinishedLinks(terrain), "and leave no link behind");
                } finally {
                    GpuFamilyPrograms.capacity = capacity;
                    frame.dispose();
                    terrain.dispose();
                }
            });
        }
    }

    /** The family programs the terrain's shader provider holds, counted from their sources. */
    private static int familyProgramsIn(GpuTerrain terrain) throws ReflectiveOperationException {
        var batch = GpuTerrain.class.getDeclaredField("batch");
        batch.setAccessible(true);
        var provider = ((com.badlogic.gdx.graphics.g3d.ModelBatch) batch.get(terrain)).getShaderProvider();
        var shaders = com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider.class.getDeclaredField("shaders");
        shaders.setAccessible(true);
        int count = 0;
        for (var shader : (com.badlogic.gdx.utils.Array<?>) shaders.get(provider)) {
            if (shader instanceof com.badlogic.gdx.graphics.g3d.shaders.BaseShader base
                  && base.program.getFragmentShaderSource().contains("#define SCULPT_FAMILY")) { count++; }
        }
        return count;
    }

    /** Links the terrain's compiler queue still holds: started and never adopted. */
    private static int unfinishedLinks(GpuTerrain terrain) throws ReflectiveOperationException {
        var field = GpuTerrain.class.getDeclaredField("terrainMaterialPreparation");
        field.setAccessible(true);
        Object preparation = field.get(terrain);
        int count = 0;
        for (String name : List.of("pending", "working")) {
            var queue = preparation.getClass().getDeclaredField(name);
            queue.setAccessible(true);
            count += ((java.util.Map<?, ?>) queue.get(preparation)).size();
        }
        return count;
    }

    private interface Body { void run() throws Exception; }

    /** Runs the body on a window's GL thread, restoring the program cache and family property; reports to name.txt. */
    private static void run(String name, StringBuilder report, Body body) throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuProgramCache previous = GpuGlsl.programCache;
                String property = System.getProperty(GpuFamilyPrograms.PROPERTY);
                try {
                    PIXEL_FAILURES.clear();
                    body.run();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    assertTrue(PIXEL_FAILURES.isEmpty(), "Pixels: " + PIXEL_FAILURES);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuGlsl.programCache = previous;
                    if (property == null) { System.clearProperty(GpuFamilyPrograms.PROPERTY); }
                    else { System.setProperty(GpuFamilyPrograms.PROPERTY, property); }
                    Gdx.app.exit();
                }
            }
        }, config);
        System.out.print(report);
        Files.writeString(new File(OUTPUT, name + ".txt").toPath(), report);
        if (failure.get() != null) { throw new AssertionError("Family programs", failure.get()); }
    }

    private record Frame(byte[] pixels, float[] depth, int ready) { }

    /** A fresh terrain settled on the board, drawn until no family program is waiting, then captured. */
    private static Frame load(BoardScene scene, String name, StringBuilder report) throws InterruptedException {
        long start = System.nanoTime();
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        try {
            BoardCamera camera = camera(scene);
            settle(terrain, frame, camera, scene);
            long settled = System.nanoTime();
            double longest = finish(terrain, frame, camera, scene, name, report);
            report.append(String.format(Locale.ROOT, "%s: settled in %d ms; %d of %d variants built (cap %d) %d ms"
                        + " later; longest frame meanwhile %.1f ms%n", name, (settled - start) / 1_000_000,
                  terrain.familyPrograms().ready().size(), terrain.familyPrograms().requested(),
                  GpuFamilyPrograms.CAPACITY, (System.nanoTime() - settled) / 1_000_000, longest));
            return capture(terrain, frame, camera, scene, name);
        } finally {
            frame.dispose();
            terrain.dispose();
        }
    }

    /** One more frame: colour and alpha, and the depth the composite wrote. */
    private static Frame capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          String name) {
        frame.render(terrain, camera, scene);
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
        byte[] pixels;
        try {
            ByteBuffer buffer = image.getPixels();
            pixels = new byte[buffer.remaining()];
            buffer.get(pixels);
        } finally {
            image.dispose();
        }
        FloatBuffer depths = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depths);
        float[] depth = new float[width * height];
        depths.get(depth);
        GpuReviewFrame.save(new File(OUTPUT, name + ".png"));
        return new Frame(pixels, depth, terrain.familyPrograms().ready().size());
    }

    private static void compare(String label, Frame generic, Frame family, StringBuilder report) {
        report.append(String.format(Locale.ROOT, "%s: colour/alpha largest difference %d, %d values over 1;"
                    + " depth largest difference %.1f steps%n", label,
              difference(generic.pixels(), family.pixels()), over(generic.pixels(), family.pixels()),
              difference(generic.depth(), family.depth()) / DEPTH_STEP));
        // The first differing pixels, top-down like the saved PNGs: where to look, and whether depth tied.
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight(), listed = 0;
        for (int i = 0; i < generic.pixels().length && listed < 10; i += 4) {
            int largest = 0;
            for (int c = 0; c < 4; c++) {
                largest = Math.max(largest, Math.abs((generic.pixels()[i + c] & 255) - (family.pixels()[i + c] & 255)));
            }
            if (largest <= 1) { continue; }
            int pixel = i / 4;
            report.append(String.format(Locale.ROOT, "    pixel %d,%d: %s -> %s, depth %.9f -> %.9f%n", pixel % width,
                  height - 1 - pixel / width, rgba(generic.pixels(), i), rgba(family.pixels(), i),
                  generic.depth()[pixel], family.depth()[pixel]));
            listed++;
        }
        // Recorded, not thrown: the rest of the test still runs, and run() fails it at the end.
        if (difference(generic.pixels(), family.pixels()) > 1) {
            PIXEL_FAILURES.add(label + ": colour/alpha differ by " + difference(generic.pixels(), family.pixels()));
        }
        if (difference(generic.depth(), family.depth()) > DEPTH_STEP * 1.5) { PIXEL_FAILURES.add(label + ": depth"); }
    }

    private static String rgba(byte[] pixels, int i) {
        return String.format(Locale.ROOT, "(%d,%d,%d,%d)", pixels[i] & 255, pixels[i + 1] & 255, pixels[i + 2] & 255,
              pixels[i + 3] & 255);
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
              .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    /** A blend program with cooled lava and at least two ordinary families (GpuSurfaceBlend.ordinaryFamily -1). */
    private static boolean mixedVolcanic(float[] families) {
        if (families.length != 5) { return false; }
        float[] slots = Arrays.copyOfRange(families, 1, 5);
        boolean lava = false;
        float ordinary = -1;
        boolean mixed = false;
        for (float family : slots) {
            if (family >= BoardSurfaceBlend.CRUST) { lava = true; }
            else if (ordinary < 0) { ordinary = family; }
            else if (family != ordinary) { mixed = true; }
        }
        return lava && mixed;
    }

    private static BoardCamera camera(BoardScene scene) {
        var camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.fit(scene);
        return camera;
    }

    private static void settle(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene)
          throws InterruptedException {
        frame.prepare(terrain, camera, scene);
        terrain.update(scene);
        terrain.animate(.5f, List.of());
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        // The loading stage, not busy(), waits for a load's family programs: so does this.
        while (terrain.refine(camera.camera) | terrain.busy() || terrain.familyPrograms().pending() > 0) {
            assertTrue(System.nanoTime() < deadline, () -> "Terrain must settle: " + terrain.buildDetails());
            Thread.sleep(5);
        }
    }

    /**
     * Draws frames until no family program is waiting; returns the longest frame. Reports the frame count and each
     * frame over SLOW_FRAME_MS with its wall-clock start (to match the GPU_SHADER log), the family programs ready
     * before and after it, and the garbage collection inside it.
     */
    private static double finish(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          String name, StringBuilder report) {
        long start = System.nanoTime(), deadline = start + TimeUnit.MINUTES.toNanos(20);
        double longest = 0;
        int frames = 0;
        StringBuilder slow = new StringBuilder();
        do {
            int ready = terrain.familyPrograms().ready().size();
            long collected = gcMillis();
            LocalTime at = LocalTime.now();
            long before = System.nanoTime();
            frame.render(terrain, camera, scene);
            double millis = (System.nanoTime() - before) / 1e6;
            longest = Math.max(longest, millis);
            frames++;
            if (millis > SLOW_FRAME_MS) {
                slow.append(String.format(Locale.ROOT, "    slow frame at %s: %.1f ms; family programs ready %d -> %d;"
                            + " garbage collection %d ms%n", at, millis, ready, terrain.familyPrograms().ready().size(),
                      gcMillis() - collected));
            }
            assertTrue(System.nanoTime() < deadline, "Family programs must finish");
        } while (terrain.familyPrograms().pending() > 0);
        report.append(String.format(Locale.ROOT, "%s: %d frames in %.1f s while family programs built%n", name, frames,
              (System.nanoTime() - start) / 1e9)).append(slow);
        return longest;
    }

    private static int difference(byte[] first, byte[] second) {
        assertEquals(first.length, second.length);
        int largest = 0;
        for (int i = 0; i < first.length; i++) {
            largest = Math.max(largest, Math.abs((first[i] & 255) - (second[i] & 255)));
        }
        return largest;
    }

    private static double difference(float[] first, float[] second) {
        assertEquals(first.length, second.length);
        double largest = 0;
        for (int i = 0; i < first.length; i++) { largest = Math.max(largest, Math.abs(first[i] - second[i])); }
        return largest;
    }

    private static int over(byte[] first, byte[] second) {
        int count = 0;
        for (int i = 0; i < first.length; i++) {
            if (Math.abs((first[i] & 255) - (second[i] & 255)) > 1) { count++; }
        }
        return count;
    }
}
