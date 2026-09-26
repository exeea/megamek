/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.client.ui.clientGUI.boardview.BoardView;
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
                        if (size <= 32) {
                            start = System.nanoTime();
                            SwingUtilities.invokeAndWait(() -> {
                                fixture.player.setStartingPos(Board.START_ANY);
                                fixture.entity.setDeployed(false);
                                fixture.game.setPhase(GamePhase.DEPLOYMENT);
                                fixture.view.markDeploymentHexesFor(fixture.entity);
                                fixture.source.refresh();
                            });
                            report("deployment-capture", start);
                            BoardScene deployment = fixture.source.takeFrame().scene();
                            start = System.nanoTime();
                            terrain.update(deployment);
                            report("deployment-terrain", start);
                            start = System.nanoTime();
                            tactical.update(deployment);
                            report("deployment-overlay", start);
                            draw(terrain, camera);
                            tactical.render(camera.camera, 0);
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            output.mkdirs();
                            GpuBoardTestUi.capture(new File(output, "performance-deployment.png"));
                        }
                        start = System.nanoTime();
                        SwingUtilities.invokeAndWait(() -> {
                            board.setHex(new Coords(size / 2, size / 2), new Hex(1));
                            fixture.source.refresh();
                        });
                        report("edit-capture", start);
                        scene = fixture.source.takeFrame().scene();
                        start = System.nanoTime();
                        terrain.update(scene);
                        report("edit-terrain", start);
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

    private static Board board(int size) {
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
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board(size))) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 900);
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                int frame;
                GpuStageTimings timings;
                final double[] samples = new double[30];
                boolean measuring;
                @Override
                public void create() {
                    super.create();
                    timings = new GpuStageTimings();
                    System.out.printf("PERF full-view size=%d renderer=%s%n", size, Gdx.gl.glGetString(GL20.GL_RENDERER));
                }

                @Override
                void updateCameraFocus(BoardScene scene, BoardView.CenterRequest request, BoardScene.Animation action) {
                    if (frame == 0) { super.updateCameraFocus(scene, request, action); }
                }

                @Override
                void renderStage(String stage) {
                    if (measuring) { timings.stage(stage); }
                }

                @Override
                public void render() {
                    try {
                        if (frame == 45) {
                            boardCamera.camera.zoom = .5f;
                            boardCamera.center(BoardGeometry.center(new Coords(size / 2, size / 2), 0));
                        }
                        int phase = frame % 45;
                        measuring = phase >= 15;
                        if (measuring) { timings.beginFrame(); }
                        long start = System.nanoTime();
                        super.render();
                        if (measuring) { timings.stage(null); }
                        Gdx.gl.glFinish();
                        if (frame == 0) { report("full-view-open", start); }
                        if (measuring) { samples[phase - 15] = (System.nanoTime() - start) / 1e6; }
                        if (phase == 44) {
                            Arrays.sort(samples);
                            System.out.printf("PERF full-%s median=%.3f ms p95=%.3f ms%n",
                                  frame < 45 ? "overview" : "close", samples[15], samples[28]);
                            StringBuilder report = new StringBuilder();
                            timings.appendReport(report, frame < 45 ? "overview" : "close");
                            System.out.print(report);
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (++frame == 90) { Gdx.app.exit(); }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() { timings.close(); super.dispose(); }
            }, config);
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
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

    private static void draw(GpuTerrain terrain, BoardCamera camera) {
        terrain.animate(1 / 60f, List.of());
        terrain.renderShadows(camera.camera, List.of());
        ScreenUtils.clear(.08f, .08f, .08f, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
    }

    private static void measure(GpuTerrain terrain, BoardCamera camera, String name) {
        double[] samples = new double[20];
        for (int i = -10; i < samples.length; i++) {
            long start = System.nanoTime();
            draw(terrain, camera);
            Gdx.gl.glFinish();
            if (i >= 0) { samples[i] = (System.nanoTime() - start) / 1e6; }
        }
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        draw(terrain, camera);
        profiler.disable();
        Arrays.sort(samples);
        System.out.printf("PERF %s median=%.3f ms p95=%.3f ms draws=%d vertices=%.0f%n",
              name, samples[10], samples[19], profiler.getDrawCalls(), profiler.getVertexCount().total);
    }
}
