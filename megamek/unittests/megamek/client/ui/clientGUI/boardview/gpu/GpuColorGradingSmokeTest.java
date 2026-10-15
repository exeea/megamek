/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Live display controls through the real scene composite, including its sky and transparent-export boundary. */
@Tag("on-demand")
class GpuColorGradingSmokeTest {
    @Test
    void controlsGradeTheWholeSceneWithoutChangingLightingOrCoverage() {
        nativeCheck(() -> {
            try (var probe = new Probe()) {
                var neutral = options(0, 0, 1, 1);
                Color gray = new Color(.18f, .18f, .18f, 1);
                Color reference = probe.draw(neutral, gray);
                assertEquals(.461f, reference.r, .005f, "Neutral controls retain the sRGB encoding of 18% gray");
                var depth = probe.atmosphere.depthTexture();
                var light = probe.atmosphere.lighting();
                Color warm = probe.draw(options(1, 0, 1, 1), gray);
                Color cool = probe.draw(options(-1, 0, 1, 1), gray);
                assertTrue(warm.r > warm.b + .1f, "Warm balance increases red relative to blue");
                assertTrue(cool.b > cool.r + .1f, "Cool balance increases blue relative to red");
                assertEquals(.18f, luminance(warm), .006f, "White balance preserves neutral-gray luminance");
                assertEquals(.18f, luminance(cool), .006f);
                Color magenta = probe.draw(options(0, 1, 1, 1), gray);
                Color green = probe.draw(options(0, -1, 1, 1), gray);
                assertTrue(magenta.r > magenta.g + .05f && magenta.b > magenta.g + .05f);
                assertTrue(green.g > green.r + .05f && green.g > green.b + .05f);

                Color pigment = new Color(.35f, .13f, .04f, 1);
                Color original = probe.draw(neutral, pigment);
                Color saturated = probe.draw(options(0, 0, 1.5f, 1), pigment);
                assertTrue(saturated.r - saturated.b > original.r - original.b + .05f);
                assertGray(probe.draw(options(.8f, -.5f, 0, 1), pigment));
                assertGray(probe.draw(options(.8f, -.5f, 0, 1), null)); // The uncovered sky, too.
                assertGray(probe.draw(options(.8f, -.5f, 0, 1), new Color(4, .5f, .1f, 1))); // HDR emission.

                Color shadows = new Color(.03f, .03f, .03f, 1);
                Color highlights = new Color(.4f, .4f, .4f, 1);
                float low = probe.draw(neutral, shadows).r, high = probe.draw(neutral, highlights).r;
                float contrastLow = probe.draw(options(0, 0, 1, 1.5f), shadows).r;
                float contrastHigh = probe.draw(options(0, 0, 1, 1.5f), highlights).r;
                assertTrue(contrastLow < low && contrastHigh > high, "Contrast expands both sides of mid-gray");
                Color translucent = probe.draw(options(1, -.5f, 1.5f, 1.2f), new Color(.2f, .1f, .03f, .4f));
                assertEquals(.4f, translucent.a, .005f, "Color grading preserves transparent-export coverage");
                for (float balance : new float[] { -1, 1 }) {
                    for (float tint : new float[] { -1, 1 }) {
                        Color extreme = probe.draw(options(balance, tint, 2, 1.5f), new Color(4, 0, .05f, 1));
                        for (float channel : new float[] { extreme.r, extreme.g, extreme.b }) {
                            assertTrue(Float.isFinite(channel) && channel >= 0 && channel <= 1);
                        }
                    }
                }
                assertEquals(reference, probe.draw(neutral, gray), "Returning to neutral restores the original pixels");
                assertSame(depth, probe.atmosphere.depthTexture(), "Color edits retain the scene framebuffer");
                assertSame(light, probe.atmosphere.lighting(), "Color edits do not rebuild the world lighting");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            }
        });
    }

    private static void assertGray(Color color) {
        assertEquals(color.r, color.g, .005f, "Zero saturation makes every output channel equal");
        assertEquals(color.g, color.b, .005f);
    }

    private static float luminance(Color display) {
        return .2126f * GpuColor.linear(display.r) + .7152f * GpuColor.linear(display.g) + .0722f * GpuColor.linear(display.b);
    }

    private static GpuAtmosphere.Options options(float balance, float tint, float saturation, float contrast) {
        return new GpuAtmosphere.Options(0, false, 0, 0, 0, 0, 0, 0, 0, 0, balance, tint, saturation, contrast);
    }

    private static final class Probe implements AutoCloseable {
        final GpuAtmosphere atmosphere = new GpuAtmosphere();
        final GpuTerrain terrain = new GpuTerrain();
        final BoardScene scene = new BoardScene(0, 1, 1, List.of(new BoardScene.Tile(new Coords(0, 0), 0, -1,
              false, 0, BoardScene.Surface.GRASS, null, null, null, null, List.of(), List.of())),
              List.of(), List.of(), -1, "", List.of());
        final OrthographicCamera camera = new OrthographicCamera(2, 2);
        final FrameBuffer target = GpuAtmosphere.buffer(3, 3, true);

        Probe() {
            camera.position.set(0, 0, 2);
            camera.lookAt(0, 0, 0);
            camera.near = .1f;
            camera.far = 10;
            camera.update();
            atmosphere.setOptions(options(0, 0, 1, 1));
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0));
            atmosphere.configureClouds(terrain, scene);
        }

        Color draw(GpuAtmosphere.Options options, Color radiance) {
            atmosphere.setOptions(options);
            atmosphere.setTransparentBackground(radiance != null && radiance.a < 1);
            atmosphere.beginPixels(3, 3, 0);
            if (radiance != null) {
                Color sky = atmosphere.lighting().sky();
                float a = radiance.a, scale = GpuSceneRadiance.SCALE;
                ScreenUtils.clear((radiance.r * a + sky.r * (1 - a)) * scale,
                      (radiance.g * a + sky.g * (1 - a)) * scale,
                      (radiance.b * a + sky.b * (1 - a)) * scale, a, true);
            }
            atmosphere.end(camera, terrain, scene, 0, null, false, target);
            target.begin();
            try {
                var pixel = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(1, 1, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
                return new Color(pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3));
            } finally { target.end(); }
        }

        @Override public void close() { target.dispose(); atmosphere.dispose(); terrain.dispose(); }
    }

    /** Optional old atmosphere-grade.glsl lets a local run compare the added arithmetic with the original pass. */
    @Test
    @Tag("gpu-benchmark")
    void compositeColorAdjustmentTimings() {
        nativeCheck(() -> {
            String baselinePath = System.getProperty("megamek.gpu.colorBaseline");
            ShaderProgram baseline = null;
            var shader = GpuAtmosphere.shader("atmosphere-composite.frag");
            var quad = GpuAtmosphere.screenQuad();
            var pixels = BufferUtils.newFloatBuffer(64 * 64 * 4);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    pixels.put((x + 1) / 128f * GpuSceneRadiance.SCALE);
                    pixels.put((y + 1) / 128f * GpuSceneRadiance.SCALE);
                    pixels.put(.1f * GpuSceneRadiance.SCALE).put(1);
                }
            }
            pixels.flip();
            var scene = GpuColorTexture.linear(pixels, 64, 64, false);
            var report = new StringBuilder("Color composite; ").append(Gdx.gl.glGetString(GL20.GL_RENDERER))
                  .append("\nEach sample is 8 composite draws; divide stage times by 8 for milliseconds per draw.\n");
            try {
                if (baselinePath != null) {
                    String grade = Files.readString(Path.of(baselinePath))
                          .replace("// COLOR_SPACE", GpuShaderSource.read("color-space.glsl"));
                    String visibility = GpuShaderSource.read("sun-visibility.glsl");
                    String fragment = GpuAtmosphere.fragment("atmosphere-composite.frag")
                          .replace(GpuShaderSource.read("atmosphere-grade.glsl"), grade)
                          .replace("// SUN_VISIBILITY", visibility);
                    String vertex = GpuShaderSource.read("atmosphere.vert").replace("// SUN_VISIBILITY", visibility);
                    baseline = GpuGlsl.compile("Original color composite", "#define VERTEX_SUN_VISIBILITY\n", vertex, fragment);
                    assertFalse(baseline.hasUniform("u_colorAdjust"), "The baseline must omit the new color arithmetic");
                }
                for (int[] size : new int[][] { { 1920, 1080 }, { 3840, 2160 } }) {
                    var target = GpuAtmosphere.buffer(size[0], size[1], true);
                    try (var timings = new GpuStageTimings()) {
                        target.begin();
                        GpuAtmosphere.screenState();
                        scene.bind(0);
                        for (int frame = 0; frame < 224; frame++) {
                            // Alternate the order to reduce a systematic warmup/clock bias.
                            for (int index = 0; index < (baseline == null ? 2 : 3); index++) {
                                int mode = (frame + index) % (baseline == null ? 2 : 3);
                                ShaderProgram program = mode == 2 ? baseline : shader;
                                timings.beginFrame(frame >= 64);
                                timings.stage(mode == 2 ? "original" : mode == 0 ? "neutral" : "adjusted");
                                program.bind();
                                program.setUniformi("u_scene", 0);
                                program.setUniformi("u_depth", 0);
                                program.setUniformi("u_waterDepth", 0);
                                program.setUniformf("u_exposure", 1);
                                program.setUniformf("u_tint", 1, 1, 1);
                                program.setUniformf("u_saturation", 1);
                                program.setUniformf("u_projectionDepth", -1, 0, 0, 1);
                                if (mode != 2) {
                                    program.setUniformf("u_whiteBalanceShift", mode == 0 ? 0 : .2f, 0, mode == 0 ? 0 : -.2f);
                                    program.setUniformf("u_colorAdjust", mode == 0 ? 0 : .5f, mode == 0 ? 0 : .2f);
                                }
                                for (int draw = 0; draw < 8; draw++) { quad.render(program, GL20.GL_TRIANGLES); }
                                timings.stage(null);
                                // Isolate the variants so adjacent GPU work cannot overlap their timestamp intervals.
                                Gdx.gl.glFinish();
                            }
                        }
                        timings.appendReport(report, size[0] + "x" + size[1]);
                    } finally { target.end(); target.dispose(); }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                Path output = Path.of(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                Files.createDirectories(output);
                Files.writeString(output.resolve("color-grading-timing.txt"), report);
                System.out.println(report);
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                if (baseline != null) { baseline.dispose(); }
                shader.dispose(); quad.dispose(); scene.dispose();
            }
        });
    }

    private static void nativeCheck(Runnable check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(320, 240);
        config.setInitialVisible(false);
        config.useVsync(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { check.run(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Scene color grading", failure.get()); }
    }
}
