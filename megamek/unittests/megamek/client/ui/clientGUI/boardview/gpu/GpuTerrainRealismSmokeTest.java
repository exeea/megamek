/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Repeatable shipped-landscape review through the real scene compositor; does not load or alter unit models. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuTerrainRealismSmokeTest {
    private record Landscape(String name, String path) { }
    private record Conditions(String name, float hour, float clouds, float fog) {
        BoardAtmosphere.Settings settings() {
            return new BoardAtmosphere.Settings(hour, clouds, fog, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
        }
    }
    private record Scene(Landscape landscape, BoardScene board) { }
    private record View(Scene scene, boolean top, Conditions conditions) {
        String name() { return scene.landscape.name + (top ? "-top-" : "-iso-") + conditions.name; }
    }

    private static final List<Landscape> LANDSCAPES = List.of(
          new Landscape("forest", "Map Set 4/16x17 Heavy Forest 1.board"),
          new Landscape("canyon", "Map Pack Savannahs/16x17 Box Canyon (Savannah).board"),
          new Landscape("lake", "Map Pack Savannahs/16x17 Mountain Lake (Savannah).board"),
          new Landscape("lava", "Map Pack Volcanic/16x17 Lava Tubes 1.board"),
          new Landscape("fungus", "Alien Worlds/32x17 Fungal Crevasse.board"));
    private static final List<Conditions> CONDITIONS = List.of(new Conditions("morning", 9, 0, 0),
          new Conditions("midday", 13, 0, 0), new Conditions("overcast", 13, .9f, .04f));

    @Test
    void reviewsShippedLandscapesAndMeasuresCompleteTerrainFrames() throws Exception {
        var selected = Arrays.asList(System.getProperty("megamek.gpu.realismBoards", "forest,canyon,lake,lava,fungus").split(","));
        var views = new ArrayList<View>();
        for (Landscape landscape : LANDSCAPES) {
            if (!selected.contains(landscape.name)) { continue; }
            var board = new Board();
            board.load(new File("data/boards", landscape.path));
            var captured = new AtomicReference<BoardScene>();
            try (var fixture = GpuBoardFixture.create(board)) {
                SwingUtilities.invokeAndWait(() -> {
                    fixture.source.refresh();
                    captured.set(fixture.source.takeFrame().scene());
                });
            }
            var scene = new Scene(landscape, captured.get());
            for (boolean top : new boolean[] { true, false }) {
                for (Conditions conditions : CONDITIONS) { views.add(new View(scene, top, conditions)); }
                if (landscape.name.equals("lava") || landscape.name.equals("fungus")) {
                    views.add(new View(scene, top, new Conditions("night", 0, 0, 0)));
                }
            }
        }
        assertTrue(!views.isEmpty(), "Select at least one known landscape");
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "landscapes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 900);
        config.useVsync(false);
        config.setForegroundFPS(0);
        int warmup = 120;
        int samples = 360;
        new Lwjgl3Application(new ApplicationAdapter() {
            private GpuTerrain terrain;
            private GpuReviewFrame compositor;
            private GpuStageTimings stages;
            private GpuStageTimings total;
            private BoardCamera camera;
            private Scene installed;
            private Ray pick;
            private Coords picked;
            private int viewIndex;
            private int frame;
            private long previousFrame;
            private final double[] cpu = new double[samples];
            private final double[] intervals = new double[samples];
            private final StringBuilder report = new StringBuilder();

            @Override
            public void create() {
                try {
                    stages = new GpuStageTimings();
                    total = new GpuStageTimings();
                    compositor = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                    report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; ")
                          .append(Gdx.graphics.getBackBufferWidth()).append('x')
                          .append(Gdx.graphics.getBackBufferHeight())
                          .append("; 120 warmup / 360 measured frames; terrain scene, no HUD or unit models\n");
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            @Override
            public void render() {
                if (failure.get() != null) { return; }
                try {
                    View view = views.get(viewIndex);
                    if (frame == 0) { prepare(view); }
                    long now = System.nanoTime();
                    if (frame >= warmup) { intervals[frame - warmup] = (now - previousFrame) / 1e6; }
                    previousFrame = now;
                    total.beginFrame(frame >= warmup);
                    stages.beginFrame(frame >= warmup);
                    total.stage("complete terrain scene");
                    compositor.render(terrain, camera, installed.board, stages);
                    total.stage(null);
                    if (frame >= warmup) { cpu[frame - warmup] = (System.nanoTime() - now) / 1e6; }
                    if (++frame == warmup + samples) {
                        GpuReviewFrame.save(new File(output, view.name() + ".png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), view.name());
                        var hit = terrain.hit(installed.board, pick);
                        assertNotNull(hit, view.name() + ": retain board picking");
                        assertEquals(picked, hit.coords(), view.name() + ": shared picking in every light and view");
                        total.appendReport(report, view.name());
                        stages.appendReport(report, view.name() + " stages");
                        Arrays.sort(cpu);
                        Arrays.sort(intervals);
                        report.append(String.format(Locale.ROOT,
                              "CPU frame median/p95 %.4f/%.4f ms; frame interval median/p95 %.4f/%.4f ms; plant buffers %d bytes%n",
                              percentile(cpu, .5), percentile(cpu, .95), percentile(intervals, .5),
                              percentile(intervals, .95), terrain.treeGeometryBytes()));
                        Files.writeString(new File(output, "timings.txt").toPath(), report);
                        frame = 0;
                        if (++viewIndex == views.size()) { Gdx.app.exit(); }
                    }
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            private void prepare(View view) throws Exception {
                if (installed != view.scene) {
                    if (terrain != null) { terrain.dispose(); }
                    terrain = new GpuTerrain();
                    installed = view.scene;
                    terrain.update(installed.board);
                    var coords = new Coords(installed.board.width() / 2, installed.board.height() / 2);
                    var center = BoardGeometry.center(coords, 0);
                    pick = new Ray(center.cpy().add(0, 0, 10000), new Vector3(0, 0, -1));
                    var hit = terrain.hit(installed.board, pick);
                    assertNotNull(hit, installed.landscape.name);
                    picked = hit.coords();
                }
                compositor.configure(view.conditions.settings());
                camera = new BoardCamera();
                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                camera.setIsometric(!view.top);
                camera.fit(installed.board);
                compositor.prepare(terrain, camera, installed.board);
                GpuTerrainLodSmokeTest.settle(terrain, null, installed.board, camera);
                terrain.animate(.5f, List.of());
                compositor.render(terrain, camera, installed.board);
                if (view.conditions.name.equals("midday")) {
                    GpuReviewFrame.save(new File(output, view.name() + "-overview.png"));
                }
                camera.camera.zoom = Math.min(.55f, camera.camera.zoom);
                camera.update();
                GpuTerrainLodSmokeTest.settle(terrain, null, installed.board, camera);
                compositor.prepare(terrain, camera, installed.board);
                // Compile each newly reached program before starting warmup or timestamp collection.
                compositor.render(terrain, camera, installed.board);
            }

            @Override
            public void dispose() {
                if (terrain != null) { terrain.dispose(); }
                if (compositor != null) { compositor.dispose(); }
                if (stages != null) { stages.close(); }
                if (total != null) { total.close(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain realism review", failure.get()); }
    }

    private static double percentile(double[] values, double fraction) {
        return values[Math.min(values.length - 1, (int) (values.length * fraction))];
    }
}
