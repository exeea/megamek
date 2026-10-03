/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native bed mapping with the reported relief toggles disabled, and the same material under real water optics. */
@Tag("on-demand")
class GpuWaterBedSmokeTest {
    @Test
    void preservesBedDetailWithoutCurlingTheStonesIntoBands() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    // Optional diagnostic controls reproduce the old deformation and expose the untouched source.
                    List<String> variants = Boolean.getBoolean("megamek.gpu.repeatBaseline")
                          ? List.of("legacy", "identity", "bounded") : List.of("bounded");
                    for (String variant : variants) {
                        var shaders = new GpuShaderManager();
                        try {
                            shaders.run(() -> {
                                if (!variant.equals("bounded")) {
                                    assertTrue(shaders.apply(Map.of("terrain-repeat.glsl", comparison(variant))).success());
                                }
                                review(variant);
                            });
                        } finally { shaders.close(); }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Water-bed material mapping", failure.get()); }
    }

    private static void review(String variant) {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "water-bed");
        assertTrue(output.isDirectory() || output.mkdirs());
        var report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER)).append('\n');
        var settings = new BoardAtmosphere.Settings(9, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(settings);
        var camera = new BoardCamera();
        camera.resize(1440, 1000);
        terrain.setAtmosphere(BoardAtmosphere.lighting(settings));
        try {
            List<BoardScene.Surface> families = variant.equals("bounded")
                  ? List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DIRT, BoardScene.Surface.SAND)
                  : List.of(BoardScene.Surface.GRASS);
            for (var family : families) {
                BoardScene scene = lake(family);
                String name = variant + "-" + family.name().toLowerCase(Locale.ROOT);
                camera.setIsometric(false);
                camera.camera.zoom = .22f;
                camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                frame.prepare(terrain, camera, scene);
                terrain.update(scene);
                terrain.animate(.5f, List.of());
                terrain.setWaterEffects(false);
                terrain.setNormalMaps(false);
                terrain.setParallaxMapping(false);
                render(terrain, frame, camera, scene, false);
                GpuReviewFrame.save(new File(output, name + "-bed-color.png"));
                byte[] color = ScreenUtils.getFrameBufferPixels(true);
                terrain.setNormalMaps(true);
                terrain.setParallaxMapping(true);
                render(terrain, frame, camera, scene, false);
                GpuReviewFrame.save(new File(output, name + "-bed-relief.png"));
                byte[] relief = ScreenUtils.getFrameBufferPixels(true);
                int changed = 0;
                // This central rectangle is inside the flat, submerged bed, clear of land and shore blending.
                for (int y = 300; y < 700; y += 2) {
                    for (int x = 480; x < 960; x += 2) {
                        int i = 4 * (y * 1440 + x);
                        if (color[i] != relief[i] || color[i + 1] != relief[i + 1] || color[i + 2] != relief[i + 2]) { changed++; }
                    }
                }
                assertTrue(changed > 1000, "Real normals/POM must still affect submerged sediment: " + family);
                report.append(name).append(": changed relief samples=").append(changed).append('\n');
                terrain.setNormalMaps(false);
                terrain.setParallaxMapping(false);
                render(terrain, frame, camera, scene, false);
                assertArrayEquals(color, ScreenUtils.getFrameBufferPixels(true),
                      "Disabling detail restores the identical bed without changing its mapping");
                terrain.setNormalMaps(true);
                terrain.setParallaxMapping(true);
                terrain.setWaterEffects(true);
                render(terrain, frame, camera, scene, true);
                GpuReviewFrame.save(new File(output, name + "-water.png"));
                camera.setIsometric(true);
                camera.camera.zoom = .17f;
                camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                render(terrain, frame, camera, scene, true);
                GpuReviewFrame.save(new File(output, name + "-water-oblique.png"));
                // The same mapping also serves dry ground. Keep this control free of moving cover and lighting detail.
                BoardScene dry = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, family, 0, -1, 0));
                terrain.update(dry);
                terrain.setNormalMaps(false);
                terrain.setParallaxMapping(false);
                camera.setIsometric(false);
                camera.camera.zoom = .22f;
                camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                render(terrain, frame, camera, dry, false);
                GpuReviewFrame.save(new File(output, name + "-dry-color.png"));
            }
            new FileHandle(new File(output, variant + "-checks.txt")).writeString(report.toString(), false);
        } finally { terrain.dispose(); frame.dispose(); }
    }

    private static BoardScene lake(BoardScene.Surface family) {
        return BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, family, 0,
              c.getX() >= 2 && c.getX() <= 6 && c.getY() >= 1 && c.getY() <= 7 ? 1 : -1, 0));
    }

    private static void render(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene, boolean water) {
        for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene, water); }
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    private static String comparison(String variant) {
        String source = GpuShaderSource.readDisk("terrain-repeat.glsl")
              .replace("mat2 materialWarp(", "mat2 boundedMaterialWarp(");
        if (variant.equals("identity")) {
            return source + "\nmat2 materialWarp(inout vec2 uv, float layer, bool wall) { return mat2(1.0); }\n";
        }
        // Regression reference: strong shears changed pebble shapes even with both material detail toggles off.
        return source + """

              mat2 materialWarp(inout vec2 uv, float layer, bool wall) {
                  vec2 horizontal = materialNoise(uv.y * .55 + layer * 5.37 + .27);
                  uv.x += 1.7 * (horizontal.x - .5);
                  float a = 1.7 * .55 * horizontal.y;
                  float vertical = wall ? .28 : 1.7;
                  vec2 along = materialNoise(uv.x * .49 + layer * 7.13 + 17.3);
                  uv.y += vertical * (along.x - .5);
                  float b = vertical * .49 * along.y;
                  return mat2(1.0, b, a, 1.0 + a * b);
              }
              """;
    }
}
