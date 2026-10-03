/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Fixed-camera material, weather and silhouette captures through the complete board compositor. */
@Tag("on-demand")
class GpuReliefReviewSmokeTest {
    @Test
    void reviewsReliefInSunRainAndDarkness() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1440, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                if (!Boolean.getBoolean("megamek.gpu.repeatBaseline")) { review(); return; }
                // A test-only in-memory draft reproduces the previous periodic sampler, without editing live files.
                var shaders = new GpuShaderManager();
                try {
                    shaders.run(() -> {
                        assertTrue(shaders.apply(Map.of("terrain-repeat.glsl", """
                              mat2 materialWarp(inout vec2 uv, float layer, bool wall) { return mat2(1.0); }
                              vec3 materialWarpNormal(vec3 normal, mat2 basis) { return normal; }
                              """)).success());
                        review();
                    });
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                finally { shaders.close(); }
            }

            private void review() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(settings(9));
                var camera = new BoardCamera();
                camera.resize(1440, 1000);
                var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "relief");
                try (var timings = new GpuStageTimings()) {
                    assertTrue(output.isDirectory() || output.mkdirs());
                    var report = new StringBuilder("GPU: " + Gdx.gl.glGetString(GL20.GL_RENDERER) + "\n");
                    for (String name : List.of("rock", "cliff", "lunar", "lunar-cliff", "sand", "sandstone", "grass", "grass-cliff",
                          "grass-slope", "dirt-slope", "sand-slope", "dirt-cliff", "snow-cliff",
                          "snow", "dirt", "mud", "concrete", "magma")) {
                        String selected = System.getProperty("megamek.gpu.reliefScenes", "");
                        if (!selected.isEmpty() && !List.of(selected.split(",")).contains(name)) { continue; }
                        BoardScene scene = switch (name) {
                            case "cliff" -> cliffs(BoardScene.Surface.ROCK);
                            case "lunar-cliff" -> cliffs(BoardScene.Surface.LUNAR);
                            case "grass-cliff" -> cliffs(BoardScene.Surface.GRASS);
                            case "sandstone" -> cliffs(BoardScene.Surface.SAND);
                            case "dirt-cliff" -> cliffs(BoardScene.Surface.DIRT);
                            case "snow-cliff" -> cliffs(BoardScene.Surface.SNOW);
                            case "grass-slope" -> cliffs(BoardScene.Surface.GRASS, 2);
                            case "dirt-slope" -> cliffs(BoardScene.Surface.DIRT, 2);
                            case "sand-slope" -> cliffs(BoardScene.Surface.SAND, 2);
                            case "mud" -> BoardSurfaceBlendTest.scene(c -> BoardBiomeTest.tile(c, BoardScene.Biome.MUD, 0));
                            case "magma" -> GpuMagmaSmokeTest.scene(false);
                            default -> BoardTerrainDetailTest.rough(BoardScene.Surface.valueOf(name.toUpperCase(Locale.ROOT)));
                        };
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        camera.setIsometric(true);
                        camera.orbit(0, 12);
                        boolean cliff = name.equals("cliff") || name.endsWith("-cliff") || name.endsWith("-slope")
                              || name.equals("sandstone");
                        camera.camera.zoom = cliff ? .14f : .065f;
                        int elevation = name.equals("mud") ? 0 : name.equals("magma") ? 2 : 1;
                        camera.center(BoardGeometry.center(new Coords(2, 2), elevation));
                        for (int condition = 0; condition < 2; condition++) {
                            boolean rain = condition == 1 && !name.equals("magma");
                            // The atmosphere owns wetness and reapplies it each frame; configure the real rain input.
                            var atmosphere = settings(condition == 1 && name.equals("magma") ? 0 : 9, rain);
                            assertEquals(rain ? 1 : 0, BoardAtmosphere.wetness(atmosphere));
                            frame.configure(atmosphere);
                            for (boolean pom : new boolean[] { false, true }) {
                                terrain.setParallaxMapping(pom);
                                String label = name + (condition == 0 ? "-dry" : name.equals("magma") ? "-night" : "-rain")
                                      + (pom ? "-pom" : "-flat");
                                for (int i = 0; i < 20; i++) {
                                    timings.beginFrame(i >= 8);
                                    frame.render(terrain, camera, scene, timings);
                                    Gdx.gl.glFinish();
                                }
                                GpuReviewFrame.save(new File(output, label + ".png"));
                                timings.appendReport(report, label);
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), label);
                            }
                        }
                        if (name.equals("cliff") || name.endsWith("-cliff")) {
                            // A texture-free capture exposes the actual shelves and silhouette for shape review.
                            frame.configure(settings(9));
                            terrain.setClay(true);
                            for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                            GpuReviewFrame.save(new File(output, name + "-clay.png"));
                            terrain.setClay(false);
                        }
                        // The reported repetition survives both detail toggles: review that exact base-colour path.
                        if (!name.equals("magma") && !cliff) {
                            frame.configure(settings(9));
                            BoardScene plane = BoardSurfaceBlendTest.scene(c -> name.equals("mud")
                                  ? BoardBiomeTest.tile(c, BoardScene.Biome.MUD, 0)
                                  : BoardSurfaceBlendTest.tile(c, BoardScene.Surface.valueOf(name.toUpperCase(Locale.ROOT)), 1, -1, 0));
                            terrain.update(plane);
                            terrain.setParallaxMapping(false);
                            terrain.setNormalMaps(false);
                            camera.setIsometric(false);
                            camera.camera.zoom = .34f;
                            camera.center(BoardGeometry.center(new Coords(4, 4), name.equals("mud") ? 0 : 1));
                            for (int i = 0; i < 3; i++) { frame.render(terrain, camera, plane); }
                            GpuReviewFrame.save(new File(output, name + "-color-only.png"));
                            terrain.setNormalMaps(true);
                        }
                    }
                    Files.writeString(new File(output, "timings.txt").toPath(), report);
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Relief review", failure.get()); }
    }

    private static BoardAtmosphere.Settings settings(float hour) {
        return settings(hour, false);
    }

    private static BoardAtmosphere.Settings settings(float hour, boolean rain) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0,
              new BoardAtmosphere.Effects(rain ? 1 : 0, 0, 0, 0, 0, 0, 0));
    }

    static BoardScene cliffs(BoardScene.Surface family) {
        return cliffs(family, 4);
    }

    static BoardScene cliffs(BoardScene.Surface family, int height) {
        var tiles = new ArrayList<BoardScene.Tile>();
        var pixels = new BoardScene.Pixels(new java.awt.image.BufferedImage(84, 72, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), y < 2 ? height : 0, -1, false, 0, family,
                      pixels, null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
