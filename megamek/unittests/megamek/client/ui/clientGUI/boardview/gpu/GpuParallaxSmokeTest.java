/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.FloatFrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native intersection checks and matched image/GPU-cost comparisons using the production shaders. */
@Tag("on-demand")
class GpuParallaxSmokeTest {
    @TempDir
    Path temporary;

    @Test
    void tracesKnownSurfacesAndReviewsShippedMaterials() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    tuning();
                    intersections();
                    review();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Parallax integration", failure.get()); }
    }

    private void tuning() throws Exception {
        var preferences = GUIPreferences.getInstance();
        boolean previous = preferences.getGpuBoardParallaxMapping();
        var skin = new GpuBoardSkin();
        var stage = new Stage(new ScreenViewport());
        try {
            SwingUtilities.invokeAndWait(() -> preferences.setGpuBoardParallaxMapping(true));
            var tuning = new GpuBoardTuning(skin.skin);
            stage.addActor(tuning.panel());
            var dock = new GpuPanelDock(skin.skin, () -> { }, null, tuning.panel());
            dock.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0, 0, 0, 0);
            dock.show(tuning.panel());
            Gdx.input.setInputProcessor(new InputMultiplexer(stage));
            stage.act(0);
            stage.draw();
            assertTrue(tuning.parallaxMapping());
            GpuBoardTestUi.click("tuning-parallax-mapping");
            assertFalse(tuning.parallaxMapping(), "A pointer click disables POM");
            assertTrue(tuning.normalMaps(), "Disabling POM keeps normal maps enabled");
            SwingUtilities.invokeAndWait(() -> { });
            assertFalse(new GpuBoardTuning(skin.skin).parallaxMapping(), "A new panel restores the preference");
            GpuBoardTestUi.click("tuning-defaults");
            assertTrue(tuning.parallaxMapping(), "Defaults restores POM");
            GpuBoardTestUi.click("tuning-normal-maps");
            assertTrue(tuning.parallaxMapping(), "The two controls are independent");
            GpuBoardTestUi.click("tuning-normal-maps");
            ScreenUtils.clear(.04f, .04f, .04f, 1);
            stage.draw();
            var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(output.isDirectory() || output.mkdirs());
            GpuReviewFrame.save(new File(output, "pom-tuning.png"));
        } finally {
            Gdx.input.setInputProcessor(null);
            stage.dispose();
            skin.dispose();
            SwingUtilities.invokeAndWait(() -> preferences.setGpuBoardParallaxMapping(previous));
        }
    }

    private void intersections() {
        var pixels = new Pixmap(256, 8, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 256; x++) { pixels.drawPixel(x, y, x << 24 | 255 - x); }
        }
        var file = new FileHandle(temporary.resolve("height.png").toFile());
        PixmapIO.writePNG(file, pixels);
        var texture = new Texture(pixels);
        var array = new TextureArray(false, file);
        pixels.dispose();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        array.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        var target = new FloatFrameBuffer(1, 1, false);
        var triangle = new Mesh(true, 3, 0, new VertexAttribute(Usage.Position, 2, "a_position"));
        triangle.setVertices(new float[] { -1, -1, 3, -1, -1, 3 });
        // Explicit GLSL 330 compilation also exercises the lowest supported shader language.
        String vertexPrefix = ShaderProgram.prependVertexCode, fragmentPrefix = ShaderProgram.prependFragmentCode;
        ShaderProgram.prependVertexCode = "";
        ShaderProgram.prependFragmentCode = "";
        var program = new ShaderProgram("""
              #version 330 core
              in vec2 a_position;
              void main() { gl_Position = vec4(a_position, 0.0, 1.0); }
              """, "#version 330 core\n" + GpuTerrain.parallaxFunctions() + GpuShaderSource.read("terrain-repeat.glsl") + """
              uniform sampler2D u_map;
              uniform sampler2DArray u_array;
              uniform int u_mode;
              uniform int u_shadow;
              uniform vec2 u_uv, u_ray, u_dx, u_dy;
              uniform vec4 u_channel;
              out vec4 color;
              void main() {
                  if (u_mode >= 2) {
                      vec2 at = u_uv;
                      mat2 basis = materialWarp(at, 4.0, u_mode >= 5);
                      int outputMode = (u_mode - 2) % 3;
                      color = outputMode == 0 ? vec4(at, determinant(basis), 1.0)
                            : outputMode == 1 ? vec4(basis[0], basis[1])
                            : vec4(materialWarpNormal(normalize(vec3(-.3, .2, 1.0)), basis), 1.0);
                      return;
                  }
                  vec2 hit = u_mode == 0
                        ? parallaxUv(u_map, 0.0, u_channel, u_uv, u_dx, u_dy, u_ray)
                        : parallaxUv(u_array, 0.0, u_channel, u_uv, u_dx, u_dy, u_ray);
                  color = vec4(hit, parallaxPixels(u_ray, u_dx, u_dy), 1.0);
                  if (u_shadow != 0) {
                      float visibility = u_mode == 0
                            ? parallaxShadow(u_map, 0.0, u_channel, u_uv, u_dx, u_dy, u_ray, 1.0)
                            : parallaxShadow(u_array, 0.0, u_channel, u_uv, u_dx, u_dy, u_ray, 1.0);
                      color = vec4(visibility);
                  }
              }
              """);
        ShaderProgram.prependVertexCode = vertexPrefix;
        ShaderProgram.prependFragmentCode = fragmentPrefix;
        try {
            assertTrue(program.isCompiled(), program.getLog());
            target.begin();
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            program.bind();
            texture.bind(0);
            array.bind(1);
            program.setUniformi("u_map", 0);
            program.setUniformi("u_array", 1);
            program.setUniformf("u_parallaxMapping", 1);
            program.setUniformi("u_shadow", 0);
            for (int mode : new int[] { 0, 1 }) {
                program.setUniformi("u_mode", mode);
                for (boolean alpha : new boolean[] { false, true }) {
                    program.setUniformf("u_channel", alpha ? 0f : 1f, 0, 0, alpha ? 1f : 0f);
                    for (float ray : new float[] { -.3f, 0f, .3f }) {
                        float origin = .38f;
                        float slope = (alpha ? -1f : 1f) * 256 / 255;
                        float offset = alpha ? 1 + .5f / 255 : -.5f / 255;
                        float expected = (origin + ray * (offset - .5f)) / (1 - ray * slope);
                        float[] hit = hit(program, triangle, origin, ray, .001f);
                        assertEquals(expected, hit[0], .00005, "Analytic ramp, sampler=" + mode + ", alpha=" + alpha);
                        assertEquals(.5, hit[1], .00001);
                    }
                    assertEquals(.38, hit(program, triangle, .38f, .0004f, .001f)[0], .000001,
                          "Less than half a pixel must retain the original UV exactly");
                    assertEquals(.38, hit(program, triangle, .38f, .3f, 0)[0], .000001,
                          "Degenerate projections must be finite and undisplaced");
                    program.setUniformf("u_parallaxMapping", 0);
                    assertEquals(.38, hit(program, triangle, .38f, .3f, .001f)[0], .000001,
                          "The live toggle must return the original UV even for a visible ray");
                    program.setUniformf("u_parallaxMapping", 1);
                }
            }
            // Multiple intersections: a whole-ray binary search can land behind the front ridge.
            var ridges = new Pixmap(256, 8, Pixmap.Format.RGBA8888);
            int[] heights = new int[256];
            ridges.setBlending(Pixmap.Blending.None);
            for (int x = 0; x < 256; x++) {
                heights[x] = (int) Math.round(128 + 110 * Math.cos(x * Math.PI / 24));
                for (int y = 0; y < 8; y++) { ridges.drawPixel(x, y, heights[x] << 24 | 255); }
            }
            texture.draw(ridges, 0, 0);
            ridges.dispose();
            program.setUniformi("u_mode", 0);
            program.setUniformf("u_channel", 1, 0, 0, 0);
            texture.bind(0);
            for (float origin : new float[] { .35f, .45f, .55f, .65f }) {
                float expected = origin;
                for (int step = 0; step <= 100000; step++) {
                    float depth = step / 100000f;
                    float coordinate = origin + .3f * (.5f - depth);
                    float texel = coordinate * 256 - .5f;
                    int x = (int) Math.floor(texel);
                    float h = (heights[x] * (1 - (texel - x)) + heights[x + 1] * (texel - x)) / 255f;
                    if (1 - depth <= h) { expected = coordinate; break; }
                }
                assertEquals(expected, hit(program, triangle, origin, .3f, .001f)[0], .001,
                      "The first visible ridge must occlude later intersections");
            }
            // A raised shelf shadows its lower neighbour only when the light points toward that shelf.
            // This tests lighting at a fixed camera hit, separately from view-dependent UV displacement.
            var shelf = new Pixmap(256, 8, Pixmap.Format.RGBA8888);
            shelf.setBlending(Pixmap.Blending.None);
            for (int x = 0; x < 256; x++) {
                int height = x < 128 ? 26 : 230;
                for (int y = 0; y < 8; y++) { shelf.drawPixel(x, y, height << 24 | height); }
            }
            texture.draw(shelf, 0, 0);
            PixmapIO.writePNG(file, shelf);
            shelf.dispose();
            array.dispose();
            array = new TextureArray(false, file);
            array.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            array.bind(1);
            texture.bind(0);
            program.setUniformi("u_shadow", 1);
            for (int mode : new int[] { 0, 1 }) {
                program.setUniformi("u_mode", mode);
                assertTrue(hit(program, triangle, .42f, .3f, .001f)[0] < .1,
                      "The raised shelf blocks grazing light");
                assertEquals(1, hit(program, triangle, .42f, -.3f, .001f)[0], .00001,
                      "The opposite light direction leaves the lower surface lit");
                assertEquals(1, hit(program, triangle, .65f, .3f, .001f)[0], .00001,
                      "The shelf top cannot shadow itself");
                program.setUniformf("u_parallaxMapping", 0);
                assertEquals(1, hit(program, triangle, .42f, .3f, .001f)[0], .00001,
                      "The POM checkbox also disables its relief shadows");
                program.setUniformf("u_parallaxMapping", 1);
            }
            mapping(program, triangle);
            target.end();
        } finally {
            program.dispose(); triangle.dispose(); target.dispose(); array.dispose(); texture.dispose();
        }
    }

    private static float[] hit(ShaderProgram program, Mesh triangle, float origin, float ray, float footprint) {
        program.setUniformf("u_uv", origin, .5f);
        program.setUniformf("u_ray", ray, 0);
        program.setUniformf("u_dx", footprint, 0);
        program.setUniformf("u_dy", 0, footprint);
        return read(program, triangle);
    }

    /** Check the real GLSL's Jacobian and normal against finite differences, including negative world coordinates. */
    private static void mapping(ShaderProgram program, Mesh triangle) {
        float epsilon = .002f;
        int changed = 0;
        for (int mode : new int[] { 2, 5 }) {
            for (float x : new float[] { -7.21f, .37f, 4.15f, 9.42f }) {
                for (float y : new float[] { -2.44f, .39f, 5.62f }) {
                    float[] at = warp(program, triangle, mode, x, y);
                    float[] dx = warp(program, triangle, mode, x + epsilon, y);
                    float[] dy = warp(program, triangle, mode, x, y + epsilon);
                    float[] basis = warp(program, triangle, mode + 1, x, y);
                    assertEquals(1, at[2], .00001, "The coordinate mapping cannot fold");
                    double squared = 0;
                    for (float entry : basis) { squared += entry * entry; }
                    double determinant = basis[0] * basis[3] - basis[1] * basis[2];
                    double stretch = Math.sqrt((squared + Math.sqrt(Math.max(0,
                          squared * squared - 4 * determinant * determinant))) / 2);
                    assertTrue(stretch < 1.26, "Mapping must retain stone shapes, not stretch them into waves: " + stretch);
                    for (int channel = 0; channel < 2; channel++) {
                        assertEquals(basis[channel], (dx[channel] - at[channel]) / epsilon, .012,
                              "The POM ray and mip gradient follow the sampled colour's X derivative");
                        assertEquals(basis[2 + channel], (dy[channel] - at[channel]) / epsilon, .012,
                              "The POM ray and mip gradient follow the sampled colour's Y derivative");
                    }
                    float[] normal = warp(program, triangle, mode + 2, x, y);
                    var expected = new com.badlogic.gdx.math.Vector3(
                          -(.3f * (dx[0] - at[0]) - .2f * (dx[1] - at[1])) / epsilon,
                          -(.3f * (dy[0] - at[0]) - .2f * (dy[1] - at[1])) / epsilon, 1).nor();
                    assertEquals(expected.x, normal[0], .006, "Normal follows the transformed height field");
                    assertEquals(expected.y, normal[1], .006, "Normal follows the transformed height field");
                    float[] repeat = warp(program, triangle, mode, x + 4, y + 4);
                    if (Math.abs(repeat[0] - at[0] - 4) + Math.abs(repeat[1] - at[1] - 4) > .05f) { changed++; }
                }
            }
        }
        assertTrue(changed >= 18, "A patch spanning several source tiles must not repeat the same window");
    }

    private static float[] warp(ShaderProgram program, Mesh triangle, int mode, float x, float y) {
        program.setUniformi("u_mode", mode);
        program.setUniformf("u_uv", x, y);
        return read(program, triangle);
    }

    private static float[] read(ShaderProgram program, Mesh triangle) {
        triangle.render(program, GL20.GL_TRIANGLES);
        var readback = BufferUtils.newFloatBuffer(4);
        Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, readback);
        return new float[] { readback.get(0), readback.get(1), readback.get(2), readback.get(3) };
    }

    private void review() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "parallax");
        Files.createDirectories(output.toPath());
        var report = new StringBuilder("Renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER)
              + "\nVersion: " + Gdx.gl.glGetString(GL20.GL_VERSION) + "\n1440x1000; fixed scene/clock; normals enabled\n");
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        try (var timings = new GpuStageTimings()) {
            var camera = new BoardCamera();
            camera.resize(1440, 1000);
            for (String material : List.of("cliff", "soil-bank", "rock", "dirt", "concrete", "mud", "magma")) {
                BoardScene scene = switch (material) {
                    case "cliff" -> GpuReliefReviewSmokeTest.cliffs(BoardScene.Surface.ROCK);
                    case "soil-bank" -> GpuReliefReviewSmokeTest.cliffs(BoardScene.Surface.DIRT, 2);
                    case "mud" -> BoardSurfaceBlendTest.scene(c -> BoardBiomeTest.tile(c, BoardScene.Biome.MUD, 0));
                    case "magma" -> GpuMagmaSmokeTest.scene(false);
                    default -> BoardTerrainDetailTest.rough(BoardScene.Surface.valueOf(material.toUpperCase(java.util.Locale.ROOT)));
                };
                terrain.update(scene);
                terrain.animate(.5f, List.of());
                for (float zoom : new float[] { material.equals("cliff") || material.equals("soil-bank") ? .14f : .09f, .34f }) {
                    camera.setIsometric(true);
                    camera.camera.zoom = zoom;
                    camera.center(BoardGeometry.center(new Coords(2, 2), material.equals("mud") ? 0 : material.equals("magma") ? 2 : 1));
                    byte[] baseline = null;
                    // Warm both settings before taking timings. ABBA limits ordering/thermal bias.
                    for (int pass : new int[] { 0, 1, 1, 0 }) {
                        boolean enabled = pass == 1;
                        terrain.setParallaxMapping(enabled);
                        String label = material + "-" + zoom + (enabled ? "-pom" : "-normal");
                        for (int i = 0; i < 48; i++) {
                            timings.beginFrame(i >= 16);
                            frame.render(terrain, camera, scene, timings);
                            Gdx.gl.glFinish();
                        }
                        GpuReviewFrame.save(new File(output, label + ".png"));
                        byte[] rendered = ScreenUtils.getFrameBufferPixels(true);
                        if (!enabled) {
                            if (baseline != null) {
                                assertArrayEquals(baseline, rendered, "Disabling POM restores the same frame: " + label);
                            }
                            baseline = rendered;
                        } else if (zoom < .2f) {
                            assertFalse(Arrays.equals(baseline, rendered), "The live toggle must affect close relief: " + label);
                        }
                        timings.appendReport(report, label);
                        new FileHandle(new File(output, "timings.txt")).writeString(report.toString(), false);
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), label);
                    }
                }
                // A normal-disabled fallback is independently checked by the existing material smoke suites.
                camera.setIsometric(false);
                terrain.setParallaxMapping(true);
                frame.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(output, material + "-top.png"));
            }
        } finally { frame.dispose(); terrain.dispose(); }
        Files.writeString(output.toPath().resolve("timings.txt"), report);
        System.out.println(report);
    }
}
