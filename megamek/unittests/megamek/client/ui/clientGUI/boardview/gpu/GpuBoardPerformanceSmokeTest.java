/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MapSettings;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Separated-water benchmark including edits and deployment using the actual client painters. */
@Tag("on-demand")
class GpuBoardPerformanceSmokeTest {
    @Test
    void measuresTerrainUpdatesAndSeparatedWater() throws Exception {
        assumeFalse(Boolean.getBoolean("megamek.gpu.performanceFullView"));
        int size = Integer.getInteger("megamek.gpu.performanceSize", 24);
        Board board = board(size);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long opened = System.nanoTime();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            report("capture", opened);
            stopTimer(fixture);
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 900);
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    GpuTactical tactical = new GpuTactical(terrain::tacticalSurface);
                    BoardSurface.Tuning original = BoardSurface.tuning();
                    try {
                        System.out.printf("PERF size=%d renderer=%s%n", size, Gdx.gl.glGetString(GL20.GL_RENDERER));
                        BoardScene scene = fixture.source.takeFrame().scene();
                        long start = System.nanoTime();
                        terrain.update(scene);
                        report("terrain-open", start);
                        BoardCamera camera = new BoardCamera();
                        camera.resize(1280, 900);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        measure(terrain, camera, "overview");
                        camera.camera.zoom = .5f;
                        camera.center(BoardGeometry.center(new Coords(size / 2, size / 2), 0));
                        measure(terrain, camera, "close");
                        if (size <= 32 || Boolean.getBoolean("megamek.gpu.performanceDeployment")) {
                            start = System.nanoTime();
                            SwingUtilities.invokeAndWait(() -> {
                                fixture.player.setStartingPos(Integer.getInteger("megamek.gpu.performanceDeploymentStart",
                                      Board.START_ANY));
                                fixture.entity.setDeployed(false);
                                fixture.game.setPhase(GamePhase.DEPLOYMENT);
                                fixture.view.markDeploymentHexesFor(fixture.entity);
                                fixture.source.refresh();
                            });
                            report("deployment-capture", start);
                            BoardScene deployment = overlayPresentation(fixture.source.takeFrame().scene());
                            start = System.nanoTime();
                            terrain.update(deployment);
                            report("deployment-terrain", start);
                            start = System.nanoTime();
                            tactical.update(deployment);
                            report("deployment-overlay", start);
                            reportOverlayCache(tactical);
                            draw(terrain, camera);
                            start = System.nanoTime();
                            tactical.render(camera.camera, 0);
                            Gdx.gl.glFinish();
                            report("deployment-first-render", start);
                            reportMaskCache(tactical);
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            output.mkdirs();
                            GpuBoardTestUi.capture(new File(output, "performance-deployment.png"));
                            measure(terrain, camera, "deployment-close", tactical);
                            if (Boolean.getBoolean("megamek.gpu.performanceDeploymentOverview")) {
                                float closeZoom = camera.camera.zoom;
                                camera.fit(deployment);
                                draw(terrain, camera);
                                GpuBoardTestUi.capture(new File(output, "performance-overview-without-deployment.png"));
                                start = System.nanoTime();
                                tactical.render(camera.camera, 0);
                                Gdx.gl.glFinish();
                                report("deployment-overview-first-render", start);
                                reportMaskCache(tactical);
                                GpuBoardTestUi.capture(new File(output, "performance-deployment-overview.png"));
                                measure(terrain, camera, "deployment-overview", tactical);
                                if (Boolean.getBoolean("megamek.gpu.performanceDeploymentTopView")) {
                                    camera.setIsometric(false);
                                    camera.fit(deployment);
                                    draw(terrain, camera);
                                    tactical.render(camera.camera, 0);
                                    GpuBoardTestUi.capture(new File(output, "performance-deployment-top.png"));
                                    camera.setIsometric(true);
                                }
                                camera.camera.zoom = closeZoom;
                                camera.center(BoardGeometry.center(new Coords(size / 2, size / 2), 0));
                            }
                            start = System.nanoTime();
                            SwingUtilities.invokeAndWait(() -> {
                                fixture.entity.setDeployed(true);
                                fixture.view.redrawEntity(fixture.entity);
                                fixture.source.refresh();
                            });
                            report("deployed-unit-capture", start);
                            BoardScene placed = overlayPresentation(fixture.source.takeFrame().scene());
                            start = System.nanoTime();
                            terrain.update(placed);
                            report("deployed-unit-terrain", start);
                            start = System.nanoTime();
                            tactical.update(placed);
                            report("deployed-unit-overlay", start);
                        }
                        start = System.nanoTime();
                        SwingUtilities.invokeAndWait(() -> {
                            board.setHex(new Coords(size / 2, size / 2), new Hex(1));
                            fixture.source.refresh();
                        });
                        report("edit-capture", start);
                        scene = overlayPresentation(fixture.source.takeFrame().scene());
                        start = System.nanoTime();
                        terrain.update(scene);
                        report("edit-terrain", start);
                        start = System.nanoTime();
                        tactical.update(scene);
                        report("edit-overlay", start);
                        if (size <= 32) {
                            start = System.nanoTime();
                            BoardSurface.tune(new BoardSurface.Tuning(original.fallsOffBoard(), original.bottomlessLevels(),
                                  original.hug() + .1f, original.beach(), original.plungePool(), original.shoreBank(),
                                  original.shoreRound(), original.mouthOpening(), original.plungeOpening(), original.lipJut(),
                                  original.fallLipWidth(), original.fallLipDrop(), original.plateau(), original.lipDepth(), original.valley()));
                            terrain.update(scene);
                            report("terrain-tuning", start);
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        BoardSurface.tune(original);
                        tactical.dispose();
                        terrain.dispose();
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static Board board(int size) throws Exception {
        String saved = System.getProperty("megamek.gpu.performanceBoard", "");
        if (!saved.isEmpty() && new File(saved).isFile()) {
            Board board = new Board();
            board.load(new File(saved));
            return board;
        }
        if (Boolean.getBoolean("megamek.gpu.performanceMedium")) {
            // The Random Map dialog's medium water, hills, mountains, cliffs, woods and rough-ground settings.
            MapSettings settings = MapSettings.getInstance();
            settings.setBoardSize(size, size);
            settings.setWaterParams(2, 5, 6, 10, 30);
            settings.setElevationParams(50, 5, 0);
            settings.setMountainParams(2, 7, 10, 6, 8, 0);
            settings.setCliffParam(50);
            settings.setForestParams(4, 8, 3, 10, 30, 0);
            settings.setRoughParams(3, 8, 2, 5, 0);
            Board board = BoardUtilities.generateRandom(settings);
            if (!saved.isEmpty()) {
                try (var output = Files.newOutputStream(new File(saved).toPath())) { board.save(output); }
            }
            return board;
        }
        Random random = new Random(0x706f6e64);
        Hex[] hexes = new Hex[size * size];
        for (int i = 0; i < hexes.length; i++) {
            Hex hex = new Hex(0);
            if (random.nextFloat() < .22f) { hex.addTerrain(new Terrain(Terrains.WATER, 1)); }
            hexes[i] = hex;
        }
        return new Board(size, size, hexes);
    }

    @Test
    void measuresTheCompleteBoardViewWithLiveCapture() throws Exception {
        assumeTrue(Boolean.getBoolean("megamek.gpu.performanceFullView"));
        int size = Integer.getInteger("megamek.gpu.performanceSize", 200);
        int warmupFrames = Integer.getInteger("megamek.gpu.performanceWarmupFrames", 15);
        int sampleFrames = Integer.getInteger("megamek.gpu.performanceSampleFrames", 30);
        boolean noFinish = Boolean.getBoolean("megamek.gpu.performanceNoFinish");
        if (warmupFrames < 2 || sampleFrames < 2) {
            throw new IllegalArgumentException("Performance scenarios need at least two warmup and sample frames");
        }
        int scenarioFrames = warmupFrames + sampleFrames;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board(size))) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 900);
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                int frame;
                GpuStageTimings timings;
                GLProfiler profiler;
                GpuDrawCallAudit drawAudit;
                String profiledStage;
                final StringBuilder draws = new StringBuilder();
                final double[] samples = new double[sampleFrames];
                final double[] submissions = new double[sampleFrames];
                // Only intervals between measured frames: the separate draw-count frame must not enter this sample.
                final double[] intervals = new double[sampleFrames - 1];
                long previousFrameStart;
                boolean measuring;
                @Override
                boolean preparePlaybackCamera(UnitPlayback state, BoardScene scene) { return true; }

                @Override
                public void create() {
                    super.create();
                    timings = new GpuStageTimings();
                    profiler = new GLProfiler(Gdx.graphics);
                    boardCamera.animateOnSelectionChange = false;
                    boardCamera.animateCombatPlayback = false;
                    boardCamera.animateOnMove = false;
                    System.out.printf("PERF full-view size=%d renderer=%s%n", size, Gdx.gl.glGetString(GL20.GL_RENDERER));
                    System.out.printf("PERF full-view warmup=%d samples=%d synchronization=%s%n",
                          warmupFrames, sampleFrames, noFinish ? "none" : "glFinish");
                }

                @Override
                void updateCameraFocus(BoardScene scene, BoardView.CenterRequest request, BoardScene.Animation action) {
                    if (frame == 0) { super.updateCameraFocus(scene, request, action); }
                }

                @Override
                void renderStage(String stage) {
                    if (measuring) { timings.stage(stage); }
                    if (drawAudit != null) { drawAudit.stage(stage); }
                    if (profiler.isEnabled()) {
                        if (profiledStage != null) {
                            draws.append(String.format(java.util.Locale.ROOT, "%s,%d,%.0f,%d,%d,%d%n", profiledStage,
                                  profiler.getDrawCalls(), profiler.getVertexCount().total, profiler.getShaderSwitches(),
                                  profiler.getTextureBindings(), profiler.getCalls()));
                        }
                        profiler.reset();
                        profiledStage = stage;
                    }
                }

                @Override
                public void render() {
                    long frameStart = System.nanoTime();
                    try {
                        int phase = frame % scenarioFrames;
                        if (phase == 0 && frame > 0) {
                            // Pending timestamp queries belong only to the scenario that issued them.
                            timings.close();
                            timings = new GpuStageTimings();
                        }
                        if (frame == 1) {
                            boardCamera.setIsometric(true);
                            boardCamera.fit(fixture.source.takeFrame().scene());
                        }
                        if (frame == scenarioFrames) {
                            boardCamera.camera.zoom = .5f;
                            boardCamera.center(BoardGeometry.center(new Coords(size / 2, size / 2), 0));
                        }
                        // Keep the original total pan distance when taking more samples, independent of frame time.
                        if (frame >= 2 * scenarioFrames) {
                            boardCamera.pan(BoardGeometry.WIDTH * .25f * 45 / scenarioFrames, 0);
                        }
                        String scenario = frame < scenarioFrames ? "overview"
                              : frame < 2 * scenarioFrames ? "close" : "moving";
                        measuring = phase >= warmupFrames;
                        if (phase > warmupFrames) {
                            intervals[phase - warmupFrames - 1] = (frameStart - previousFrameStart) / 1e6;
                        }
                        previousFrameStart = frameStart;
                        if (phase == warmupFrames - 1) {
                            draws.setLength(0);
                            if (Boolean.getBoolean("megamek.gpu.performanceDrawAudit") && !scenario.equals("moving")) {
                                drawAudit = new GpuDrawCallAudit(this);
                            }
                            profiler.enable();
                        }
                        if (measuring) { timings.beginFrame(); }
                        long start = System.nanoTime();
                        super.render();
                        if (measuring) { timings.stage(null); }
                        long submitted = System.nanoTime();
                        if (!noFinish) { Gdx.gl.glFinish(); }
                        if (phase == warmupFrames - 1) {
                            renderStage(null);
                            profiler.disable();
                            System.out.printf("PERF full-%s draw counts%n stage,draws,vertices,shaderSwitches,textureBindings,glCalls%n%s",
                                  scenario, draws);
                            if (drawAudit != null) {
                                drawAudit.close();
                                drawAudit.report(scenario);
                                drawAudit = null;
                            }
                        }
                        if (frame == 0) { report("full-view-open", start); }
                        if (measuring) {
                            samples[phase - warmupFrames] = (System.nanoTime() - start) / 1e6;
                            submissions[phase - warmupFrames] = (submitted - start) / 1e6;
                        }
                        if (phase == scenarioFrames - 1) {
                            Arrays.sort(samples);
                            Arrays.sort(submissions);
                            Arrays.sort(intervals);
                            if (!noFinish) {
                                System.out.printf("PERF full-%s median=%.3f ms p95=%.3f ms%n",
                                      scenario, percentile(samples, .5), percentile(samples, .95));
                            }
                            System.out.printf("PERF full-%s submission median=%.3f ms p95=%.3f ms%n",
                                  scenario, percentile(submissions, .5), percentile(submissions, .95));
                            System.out.printf("PERF full-%s frame-interval median=%.3f ms p95=%.3f ms averageFPS=%.2f intervals=%d%n",
                                  scenario, percentile(intervals, .5), percentile(intervals, .95),
                                  1000 / Arrays.stream(intervals).average().orElseThrow(), intervals.length);
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            output.mkdirs();
                            // Readback is outside the sample and completes the scenario's pending GPU queries.
                            GpuBoardTestUi.capture(new File(output, "performance-full-" + scenario + ".png"));
                            StringBuilder report = new StringBuilder();
                            timings.appendReport(report, scenario);
                            System.out.print(report);
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (++frame == 3 * scenarioFrames) { Gdx.app.exit(); }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    if (drawAudit != null) { drawAudit.close(); }
                    if (profiler.isEnabled()) { profiler.disable(); }
                    timings.close();
                    super.dispose();
                }
            }, config);
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static double percentile(double[] sorted, double fraction) {
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * fraction))];
    }

    private static void stopTimer(GpuBoardFixture fixture) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var field = GpuBoardSource.class.getDeclaredField("timer");
                field.setAccessible(true);
                ((Timer) field.get(fixture.source)).stop();
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
    }

    private static void report(String name, long start) {
        System.out.printf("PERF %s %.3f ms%n", name, (System.nanoTime() - start) / 1e6);
    }

    /** Exercise the draped mask path with exactly the deployment painter's captured borders and legal state. */
    private static BoardScene overlayPresentation(BoardScene scene) {
        if (!Boolean.getBoolean("megamek.gpu.performanceHexMasks")) { return scene; }
        var captured = scene.tactical();
        List<BoardTactical.Fill> fills = captured.fills().stream().map(fill -> {
            BoardTactical.HexBorder border = fill.border();
            return border == null ? fill : new BoardTactical.Fill(fill.contours(), fill.winding(), fill.argb(), fill.playback(),
                  new BoardTactical.HexBorder(border.anchor(), border.padding(), border.width(), border.scale(), false));
        }).toList();
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(), scene.plannedPath(),
              scene.selectedId(), scene.phase(), scene.commands(), scene.light(), scene.firingLines(), scene.rangeBorders(),
              scene.markers(), new BoardTactical(fills, captured.labels(), captured.walls(), captured.flatWalls()),
              scene.rangeLabels(), scene.fieldOfView());
    }

    private static void reportMaskCache(GpuTactical tactical) throws ReflectiveOperationException {
        for (String name : List.of("hexMasks", "deploymentTint")) {
            var field = GpuTactical.class.getDeclaredField(name);
            field.setAccessible(true);
            var masks = (GpuHexMasks) field.get(tactical);
            System.out.printf("PERF %s active=%s carrier-payload=%.3fMiB%n", name, masks.active(), masks.bytes() / 1048576.0);
        }
    }

    /** Exact retained vertex payload, excluding map/object overhead and mesh/GPU buffers; old variants have no cache. */
    private static void reportOverlayCache(GpuTactical tactical) throws ReflectiveOperationException {
        Set<float[]> arrays = Collections.newSetFromMap(new IdentityHashMap<>());
        long dependencies = 0;
        for (String name : List.of("fills", "walls")) {
            java.lang.reflect.Field field;
            try { field = GpuTactical.class.getDeclaredField(name); }
            catch (NoSuchFieldException oldVariant) { return; }
            field.setAccessible(true);
            for (Object geometry : ((Map<?, ?>) field.get(tactical)).values()) {
                for (var component : geometry.getClass().getRecordComponents()) {
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    if (component.getType() == float[].class) { arrays.add((float[]) accessor.invoke(geometry)); }
                    if (component.getName().equals("surfaces")) {
                        dependencies += ((Map<?, ?>) accessor.invoke(geometry)).size();
                    }
                }
            }
        }
        long bytes = arrays.stream().mapToLong(array -> (long) array.length * Float.BYTES).sum();
        System.out.printf("PERF overlay-cache vertex-payload=%.3fMiB arrays=%d surface-references=%d%n",
              bytes / 1048576.0, arrays.size(), dependencies);
    }

    private static void draw(GpuTerrain terrain, BoardCamera camera) {
        terrain.animate(1 / 60f, List.of());
        terrain.renderShadows(camera.camera, List.of());
        ScreenUtils.clear(.08f, .08f, .08f, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
    }

    private static void measure(GpuTerrain terrain, BoardCamera camera, String name) {
        measure(terrain, camera, name, null);
    }

    private static void measure(GpuTerrain terrain, BoardCamera camera, String name, GpuTactical tactical) {
        double[] samples = new double[20];
        for (int i = -10; i < samples.length; i++) {
            long start = System.nanoTime();
            draw(terrain, camera);
            if (tactical != null) { tactical.render(camera.camera, 0); }
            Gdx.gl.glFinish();
            if (i >= 0) { samples[i] = (System.nanoTime() - start) / 1e6; }
        }
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        draw(terrain, camera);
        if (tactical != null) { tactical.render(camera.camera, 0); }
        profiler.disable();
        Arrays.sort(samples);
        System.out.printf("PERF %s median=%.3f ms p95=%.3f ms draws=%d vertices=%.0f%n",
              name, samples[10], samples[19], profiler.getDrawCalls(), profiler.getVertexCount().total);
    }
}
