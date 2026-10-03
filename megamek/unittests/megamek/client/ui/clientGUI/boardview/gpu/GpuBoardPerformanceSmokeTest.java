/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
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
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.ScreenUtils;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
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
            config.setWindowedMode(Integer.getInteger("megamek.gpu.performanceWidth", 1280),
                  Integer.getInteger("megamek.gpu.performanceHeight", 900));
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
        if (failure.get() != null) { throw new AssertionError("Board rendering benchmark", failure.get()); }
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
        boolean comparePages = Boolean.getBoolean("megamek.gpu.performanceComparePages");
        int variants = comparePages ? 2 : 1;
        long settleNanos = Integer.getInteger("megamek.gpu.performanceSettleSeconds", 180) * 1_000_000_000L;
        // The draw audit installs GL interceptors. Leave warmup after removing them before measuring frame pacing.
        int drawCountFrame = warmupFrames / 2;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean previousLod = TerrainLod.enabled();
        TerrainLod.setEnabled(Boolean.parseBoolean(System.getProperty("megamek.gpu.performanceTerrainLod", "true")));
        phase("capture");
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board(size))) {
            if (Boolean.getBoolean("megamek.gpu.performanceFrozenCapture")) { stopTimer(fixture); }
            var config = GpuBoardWindow.configuration(Boolean.getBoolean("megamek.gpu.performanceVisible"));
            config.setWindowedMode(Integer.getInteger("megamek.gpu.performanceWidth", 1280),
                  Integer.getInteger("megamek.gpu.performanceHeight", 900));
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                int frame;
                GpuStageTimings timings;
                GLProfiler profiler;
                GL20 rawGl20;
                Input rawInput;
                Input fixedInput;
                GpuDrawCallAudit drawAudit;
                String profiledStage;
                final StringBuilder draws = new StringBuilder();
                final double[] samples = new double[sampleFrames];
                final double[] submissions = new double[sampleFrames];
                // Only intervals between measured frames: the separate draw-count frame must not enter this sample.
                final double[] intervals = new double[sampleFrames - 1];
                long previousFrameStart;
                boolean measuring;
                boolean timingFrame;
                long detailStarted;
                int detailFrames;
                int detailQuietFrames;
                final List<Double> detailSubmissions = new ArrayList<>();
                double detailSubmissionMaximum;
                long stageStarted;
                String currentStage;
                @Override
                boolean preparePlaybackCamera(UnitPlayback state, BoardScene scene) { return true; }

                @Override
                public void create() {
                    phase("open");
                    super.create();
                    rawInput = Gdx.input;
                    // Preserve hover picking under a fixed cursor without warping the user's desktop pointer.
                    // Live Swing capture and frozen capture must exercise the same pointer path.
                    rawInput.setInputProcessor(null);
                    fixedInput = (Input) Proxy.newProxyInstance(Input.class.getClassLoader(), new Class<?>[] { Input.class },
                          (proxy, method, args) -> switch (method.getName()) {
                              case "getX" -> Gdx.graphics.getWidth() / 2;
                              case "getY" -> Gdx.graphics.getHeight() / 2;
                              case "isKeyPressed", "isKeyJustPressed", "isButtonPressed", "isButtonJustPressed",
                                    "isTouched", "justTouched" -> false;
                              default -> method.invoke(rawInput, args);
                          });
                    // Constructing the tuning UI restores defaults. Apply the benchmark choice after that reset,
                    // before the first frame builds any terrain.
                    TerrainLod.setEnabled(Boolean.parseBoolean(System.getProperty("megamek.gpu.performanceTerrainLod", "true")));
                    timings = new GpuStageTimings();
                    rawGl20 = Gdx.graphics.getGL20();
                    profiler = new GLProfiler(Gdx.graphics);
                    boardCamera.animateOnSelectionChange = false;
                    boardCamera.animateCombatPlayback = false;
                    boardCamera.animateOnMove = false;
                    BoardScene scene = fixture.source.takeFrame().scene();
                    System.out.printf("PERF full-view board=%dx%d framebuffer=%dx%d renderer=%s%n", scene.width(), scene.height(),
                          Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Gdx.gl.glGetString(GL20.GL_RENDERER));
                    System.out.printf("PERF full-view warmup=%d samples=%d synchronization=%s%n",
                          warmupFrames, sampleFrames, noFinish ? "none" : "glFinish");
                    System.out.printf("PERF terrainLoD=%s frozenCapture=%s%n", TerrainLod.enabled(),
                          Boolean.getBoolean("megamek.gpu.performanceFrozenCapture"));
                }

                @Override
                void updateCameraFocus(BoardScene scene, BoardFocus request, BoardScene.Animation action) {
                    if (frame == 0) { super.updateCameraFocus(scene, request, action); }
                }

                @Override
                void renderStage(String stage) {
                    long now = System.nanoTime();
                    if (currentStage != null && now - stageStarted > 8_000_000) {
                        SlowStage event = new SlowStage();
                        event.stage = currentStage;
                        event.milliseconds = (now - stageStarted) / 1e6;
                        event.commit();
                    }
                    currentStage = stage;
                    stageStarted = now;
                    if (timingFrame) { timings.stage(stage); }
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
                        Gdx.input = fixedInput;
                        int phase = frame % scenarioFrames;
                        int view = frame / (scenarioFrames * variants);
                        if (comparePages && phase == 0) {
                            setPages(this, frame / scenarioFrames % variants == 1);
                        }
                        if (frame == 1) {
                            boardCamera.setIsometric(true);
                            boardCamera.fit(fixture.source.takeFrame().scene());
                        }
                        if (frame == scenarioFrames * variants || comparePages && view == 2 && phase == 0) {
                            boardCamera.camera.zoom = Float.parseFloat(System.getProperty("megamek.gpu.performanceZoom", ".5"));
                            BoardScene scene = fixture.source.takeFrame().scene();
                            boardCamera.center(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
                        }
                        // Keep the original total pan distance when taking more samples, independent of frame time.
                        if (view == 2) {
                            boardCamera.pan(BoardGeometry.WIDTH * .25f * 45 / scenarioFrames, 0);
                        }
                        String scenario = view == 0 ? "overview" : view == 1 ? "close" : "moving";
                        if (comparePages) { scenario += frame / scenarioFrames % variants == 0 ? "-original" : "-pages"; }
                        if (phase == 0 && detailFrames == 0) {
                            phase(frame == 0 ? "terrain-build" : scenario + "-warmup");
                            detailStarted = System.nanoTime();
                            detailSubmissionMaximum = 0;
                            detailQuietFrames = 0;
                            detailSubmissions.clear();
                        }
                        if (phase == warmupFrames) { phase(scenario + "-sample"); }
                        measuring = phase >= warmupFrames;
                        timingFrame = phase != drawCountFrame;
                        if (phase > warmupFrames) {
                            intervals[phase - warmupFrames - 1] = (frameStart - previousFrameStart) / 1e6;
                        }
                        previousFrameStart = frameStart;
                        if (phase == drawCountFrame) {
                            draws.setLength(0);
                            if (Boolean.getBoolean("megamek.gpu.performanceDrawAudit") && view != 2) {
                                drawAudit = new GpuDrawCallAudit(this);
                            }
                            profiler.enable();
                        }
                        if (measuring) {
                            assertSame(rawGl20, Gdx.gl20, "Draw counting must not leave error-checking wrappers in timed frames");
                        }
                        if (timingFrame) { timings.beginFrame(measuring); }
                        long start = System.nanoTime();
                        super.render();
                        if (timingFrame) { timings.stage(null); }
                        long submitted = System.nanoTime();
                        if (!noFinish) { Gdx.gl.glFinish(); }
                        if (phase == drawCountFrame) {
                            renderStage(null);
                            GpuStageTimings.stopCounting(profiler, rawGl20);
                            System.out.printf("PERF full-%s draw counts%n stage,draws,vertices,shaderSwitches,textureBindings,glCalls%n%s",
                                  scenario, draws);
                            if (drawAudit != null) {
                                drawAudit.close();
                                drawAudit.report(scenario);
                                drawAudit = null;
                            }
                        }
                        if (phase < drawCountFrame) {
                            detailSubmissionMaximum = Math.max(detailSubmissionMaximum, (submitted - start) / 1e6);
                        }
                        // Camera changes may queue several meshes. Measure steady frames only after they settle;
                        // three idle frames avoid mistaking the gap between two chunk jobs for completion.
                        // The moving scenario intentionally includes refinement during panning.
                        if (phase < drawCountFrame && view != 2
                              && (phase == 0 || detailFrames > 0 || detailPending(this))) {
                            detailQuietFrames = detailPending(this) ? 0 : detailQuietFrames + 1;
                            detailFrames++;
                            detailSubmissions.add((submitted - start) / 1e6);
                            assertTrue(System.nanoTime() - detailStarted < settleNanos, "Visible detail must settle");
                            if (detailQuietFrames < 3) { return; }
                        }
                        if (detailFrames > 0) {
                            double[] buildSamples = detailSubmissions.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                            System.out.printf("PERF full-%s detail-settle=%.3f ms frames=%d maxSubmission=%.3f ms%n", scenario,
                                  (System.nanoTime() - detailStarted) / 1e6, detailFrames, detailSubmissionMaximum);
                            System.out.printf("PERF full-%s building-submission median=%.3f ms p95=%.3f ms p99=%.3f ms%n", scenario,
                                  percentile(buildSamples, .5), percentile(buildSamples, .95), percentile(buildSamples, .99));
                            detailFrames = 0;
                        }
                        if (frame == 0) {
                            report("full-view-open", detailStarted);
                            phase("overview-warmup");
                        }
                        if (measuring) {
                            samples[phase - warmupFrames] = (System.nanoTime() - start) / 1e6;
                            submissions[phase - warmupFrames] = (submitted - start) / 1e6;
                        }
                        if (phase == scenarioFrames - 1) {
                            phase(scenario + "-report");
                            Arrays.sort(samples);
                            Arrays.sort(submissions);
                            Arrays.sort(intervals);
                            if (!noFinish) {
                                System.out.printf("PERF full-%s median=%.3f ms p95=%.3f ms%n",
                                      scenario, percentile(samples, .5), percentile(samples, .95));
                            }
                            System.out.printf("PERF full-%s submission median=%.3f ms p95=%.3f ms%n",
                                  scenario, percentile(submissions, .5), percentile(submissions, .95));
                            System.out.printf("PERF full-%s frame-interval median=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms averageFPS=%.2f intervals=%d%n",
                                  scenario, percentile(intervals, .5), percentile(intervals, .95),
                                  percentile(intervals, .99), intervals[intervals.length - 1],
                                  1000 / Arrays.stream(intervals).average().orElseThrow(), intervals.length);
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            output.mkdirs();
                            // Readback completes this scenario's queries before appendReport drains them. Reuse the
                            // query objects across scenarios, avoiding another first-use driver stall at each zoom.
                            GpuBoardTestUi.capture(new File(output, "performance-full-" + scenario + ".png"));
                            StringBuilder report = new StringBuilder();
                            timings.appendReport(report, scenario);
                            System.out.print(report);
                            memory(this, scenario);
                            if (view == 2 && Boolean.getBoolean("megamek.gpu.performanceFarPan")) {
                                // Extra visual regression capture, after all performance samples are complete.
                                measuring = false;
                                timingFrame = false;
                                boardCamera.fit(fixture.source.takeFrame().scene());
                                boardCamera.camera.zoom = 40;
                                boardCamera.pan(0, 180);
                                super.render();
                                GpuBoardTestUi.capture(new File(output, "performance-full-far-pan.png"));
                            }
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (++frame == 3 * scenarioFrames * variants) { phase("done"); Gdx.app.exit(); }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    } finally {
                        Gdx.input = rawInput;
                    }
                }

                @Override
                public void dispose() {
                    if (drawAudit != null) { drawAudit.close(); }
                    if (profiler.isEnabled()) { GpuStageTimings.stopCounting(profiler, rawGl20); }
                    timings.close();
                    super.dispose();
                }

            }, config);
        } finally { TerrainLod.setEnabled(previousLod); }
        if (failure.get() != null) { throw new AssertionError("Full board rendering benchmark", failure.get()); }
    }

    @Name("megamek.GpuBenchmarkPhase")
    @StackTrace(false)
    private static final class Phase extends Event {
        String phase;
        boolean terrainLod;
    }

    @Name("megamek.GpuSlowStage")
    @StackTrace(false)
    private static final class SlowStage extends Event {
        String stage;
        double milliseconds;
    }

    private static void phase(String name) {
        Phase event = new Phase();
        event.phase = name;
        event.terrainLod = TerrainLod.enabled();
        event.commit();
        System.out.printf("PERF phase=%s lod=%s at=%s%n", name, TerrainLod.enabled(), Instant.now());
    }

    private static GpuTerrain terrain(GpuBattleView view) throws Exception {
        var field = GpuBattleView.class.getDeclaredField("terrain");
        field.setAccessible(true);
        return (GpuTerrain) field.get(view);
    }

    private static void setPages(GpuBattleView view, boolean enabled) throws Exception {
        var field = GpuTerrain.class.getDeclaredField("terrainPages");
        field.setAccessible(true);
        Object pages = field.get(terrain(view));
        var method = pages.getClass().getDeclaredMethod("setEnabled", boolean.class);
        method.setAccessible(true);
        method.invoke(pages, enabled);
    }

    private static boolean detailPending(GpuBattleView view) throws Exception {
        for (String name : List.of("detailJob", "requested", "rebuild")) {
            try {
                var field = GpuTerrain.class.getDeclaredField(name);
                field.setAccessible(true);
                if (field.get(terrain(view)) != null) { return true; }
            } catch (NoSuchFieldException ignored) {
                // Frozen synchronous baselines only have the LoD job.
            }
        }
        return false;
    }

    private static void memory(GpuBattleView view, String scenario) throws Exception {
        // Outside measured frames. Report installed tiers and heap after collecting temporary build objects.
        var field = GpuTerrain.class.getDeclaredField("chunks");
        field.setAccessible(true);
        Map<String, Integer> levels = new java.util.TreeMap<>();
        for (Object chunk : (List<?>) field.get(terrain(view))) {
            var level = chunk.getClass().getDeclaredField("lod");
            level.setAccessible(true);
            levels.merge(level.get(chunk).toString(), 1, Integer::sum);
        }
        if (!TerrainLod.enabled()) { assertEquals(Set.of("FULL"), levels.keySet()); }
        System.gc();
        var runtime = Runtime.getRuntime();
        System.out.printf("PERF full-%s heapAfterGc=%.1f MiB installed=%s%n", scenario,
              (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0, levels);
        for (String name : List.of("propBatch", "terrainPages")) {
            var cacheField = GpuTerrain.class.getDeclaredField(name);
            cacheField.setAccessible(true);
            Object cache = cacheField.get(terrain(view));
            var rebuilds = cache.getClass().getDeclaredMethod("rebuilds");
            rebuilds.setAccessible(true);
            System.out.printf("PERF full-%s %s builds=%s geometryMiB=%.3f (CPU and GPU each store a copy)%n",
                  scenario, name, rebuilds.invoke(cache), GpuDrawCallAudit.pageBytes(cache) / 1048576.0);
        }
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
                  new BoardTactical.HexBorder(border.anchor(), border.padding(), border.width(), border.scale()));
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
        GL20 rawGl20 = Gdx.gl20;
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        draw(terrain, camera);
        if (tactical != null) { tactical.render(camera.camera, 0); }
        GpuStageTimings.stopCounting(profiler, rawGl20);
        Arrays.sort(samples);
        System.out.printf("PERF %s median=%.3f ms p95=%.3f ms draws=%d vertices=%.0f%n",
              name, samples[10], samples[19], profiler.getDrawCalls(), profiler.getVertexCount().total);
    }
}
