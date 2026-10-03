/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual Grassland 2 board capture, including its cosmetic ground transitions, roads and river. */
@Tag("on-demand")
class GpuGrasslandSmokeTest {
    @Test
    void rendersNativeGrassAcrossTheShippedBoardWithBothReliefTogglesOffAndOn() throws Exception {
        var captured = new AtomicReference<BoardScene>();
        SwingUtilities.invokeAndWait(() -> {
            var game = new Game();
            game.setBoard(BoardGroundCaptureTest.grassland());
            try (var source = new GpuMapSource(game, null, null)) {
                captured.set(source.takeFrame().scene());
            }
        });
        BoardScene scene = captured.get();
        assertTrue(scene.tiles().stream().filter(tile -> !tile.liquid().present()).allMatch(BoardScene.Tile::detailedGround));
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "grassland");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1200);
        new Lwjgl3Application(new ApplicationAdapter() {
            private final BoardAtmosphere.Settings settings = new BoardAtmosphere.Settings(9, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
            private GpuTerrain terrain;
            private GpuReviewFrame frame;
            private BoardCamera camera;
            private long started;
            private long previousFrame;
            private double constructorMillis;
            private double loadMillis;
            private double maxUpdateMillis;
            private double maxRefineMillis;
            private double maxLoadingFrameMillis;
            private double firstVisibleFrameMillis;
            private double maxVisibleFrameMillis;
            private GL20 originalGl;
            private int loadingFrames;
            private int captureIndex;
            private int settledFrames;
            private boolean loaded;
            private final List<String> steps = new ArrayList<>(List.of("frame,elapsed_ms,update_ms,refine_ms,materials_pending"));
            private final List<String> glPauses = new ArrayList<>();

            @Override
            public void create() {
                try {
                    started = previousFrame = System.nanoTime();
                    originalGl = Gdx.gl20;
                    Gdx.gl20 = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { GL20.class },
                          (proxy, method, args) -> {
                              if (loaded && method.getName().equals("glShaderSource")) {
                                  assertTrue(!((String) args[1]).contains("vec3 groundToneFor("),
                                        "Sculpt/blend programs must be ready before the first visible frame");
                              }
                              long callStarted = System.nanoTime();
                              try { return method.invoke(originalGl, args); }
                              catch (InvocationTargetException error) { throw error.getCause(); }
                              finally {
                                  double elapsed = (System.nanoTime() - callStarted) / 1_000_000.0;
                                  if (!loaded && elapsed >= 20) {
                                      glPauses.add(String.format(Locale.ROOT, "frame %d: %s %.3f ms", loadingFrames + 1,
                                            method.getName(), elapsed));
                                  }
                              }
                          });
                    terrain = new GpuTerrain();
                    constructorMillis = (System.nanoTime() - started) / 1_000_000.0;
                    terrain.setAtmosphere(BoardAtmosphere.lighting(settings));
                    camera = new BoardCamera();
                    camera.resize(1600, 1200);
                    camera.setIsometric(false);
                    camera.fit(scene);
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            @Override
            public void render() {
                if (failure.get() != null) { return; }
                try {
                    assertTrue(System.nanoTime() - started < 300_000_000_000L, "The terrain pipeline must eventually finish");
                    if (!loaded) {
                        long now = System.nanoTime();
                        maxLoadingFrameMillis = Math.max(maxLoadingFrameMillis, (now - previousFrame) / 1_000_000.0);
                        previousFrame = now;
                        long step = System.nanoTime();
                        terrain.update(scene, camera.camera);
                        double update = (System.nanoTime() - step) / 1_000_000.0;
                        step = System.nanoTime();
                        terrain.refine(camera.camera);
                        double refine = (System.nanoTime() - step) / 1_000_000.0;
                        maxUpdateMillis = Math.max(maxUpdateMillis, update);
                        maxRefineMillis = Math.max(maxRefineMillis, refine);
                        boolean materials = terrain.buildDetails().stream()
                              .anyMatch(status -> status.step().task().equals("materials"));
                        steps.add(String.format(Locale.ROOT, "%d,%.3f,%.3f,%.3f,%s", ++loadingFrames,
                              (System.nanoTime() - started) / 1_000_000.0, update, refine, materials));
                        // A moving loading marker is submitted every frame while driver links and terrain work proceed.
                        Gdx.gl.glClearColor(.04f, .06f, .08f, 1);
                        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
                        Gdx.gl.glScissor(20 + loadingFrames * 7 % 1400, 40, 100, 16);
                        Gdx.gl.glClearColor(.15f, .8f, .55f, 1);
                        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Loading frame");
                        if (!terrain.ready(scene)) { return; }
                        loaded = true;
                        loadMillis = (System.nanoTime() - started) / 1_000_000.0;
                        frame = new GpuReviewFrame(settings);
                        terrain.animate(.5f, List.of());
                        configureCapture();
                        return;
                    }
                    long drawStarted = System.nanoTime();
                    terrain.refine(camera.camera);
                    frame.render(terrain, camera, scene);
                    double drawMillis = (System.nanoTime() - drawStarted) / 1_000_000.0;
                    if (firstVisibleFrameMillis == 0) { firstVisibleFrameMillis = drawMillis; }
                    maxVisibleFrameMillis = Math.max(maxVisibleFrameMillis, drawMillis);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Native grass capture");
                    settledFrames = terrain.busy() ? 0 : settledFrames + 1;
                    if (settledFrames < 8) { return; }
                    String name = (captureIndex % 2 == 0 ? "board-top-" : "ground-close-")
                          + (captureIndex < 2 ? "color-only" : "relief");
                    GpuReviewFrame.save(new File(output, name + ".png"));
                    if (++captureIndex < 4) { configureCapture(); return; }
                    writeReport();
                    Gdx.app.exit();
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            private void configureCapture() {
                settledFrames = 0;
                terrain.setParallaxMapping(captureIndex >= 2);
                terrain.setNormalMaps(captureIndex >= 2);
                camera.setIsometric(captureIndex % 2 != 0);
                if (captureIndex % 2 == 0) { camera.fit(scene); }
                else {
                    camera.camera.zoom = .25f;
                    camera.center(BoardGeometry.center(new Coords(5, 5), 1));
                }
            }

            private void writeReport() throws java.io.IOException {
                String report = String.format(Locale.ROOT, """
                      GPU: %s
                      GL: %s
                      GLSL: %s
                      ARB parallel compile: %s
                      KHR parallel compile: %s
                      Terrain constructor: %.3f ms
                      Load to ready: %.3f ms
                      Loading frames submitted: %d
                      Maximum terrain.update step: %.3f ms
                      Maximum terrain.refine step: %.3f ms
                      Maximum loading frame interval: %.3f ms
                      First visible frame: %.3f ms
                      Maximum visible frame: %.3f ms
                      Dry hexes with native ground: %d
                      """, Gdx.gl.glGetString(GL20.GL_RENDERER), Gdx.gl.glGetString(GL20.GL_VERSION),
                      Gdx.gl.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION),
                      Gdx.graphics.supportsExtension("GL_ARB_parallel_shader_compile"),
                      Gdx.graphics.supportsExtension("GL_KHR_parallel_shader_compile"), constructorMillis, loadMillis,
                      loadingFrames, maxUpdateMillis, maxRefineMillis, maxLoadingFrameMillis,
                      firstVisibleFrameMillis, maxVisibleFrameMillis,
                      scene.tiles().stream().filter(tile -> !tile.liquid().present() && tile.detailedGround()).count());
                Files.writeString(new File(output, "loading.txt").toPath(), report + String.join("\n", glPauses) + "\n");
                Files.write(new File(output, "loading-steps.csv").toPath(), steps);
            }

            @Override
            public void dispose() {
                if (frame != null) { frame.dispose(); }
                if (terrain != null) { terrain.dispose(); }
                if (originalGl != null) { Gdx.gl20 = originalGl; }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Grassland 2 native material review", failure.get()); }
    }
}
