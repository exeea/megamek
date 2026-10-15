/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("on-demand")
class GpuLightingControlsSmokeTest {
    @TempDir Path directory;

    @Test void controlsResetAndBothEnvironmentStoragePathsRenderTheSelectedImage() throws Exception {
        var image = GpuLightingEnvironment.load(GpuLightingControlsTest.constantPng(directory.resolve("red.png"), 0x804020));
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { check(image); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void check(GpuLightingEnvironment image) {
        var skin = new GpuBoardSkin();
        var environment = new GpuEnvironmentLighting();
        var atmosphere = new GpuAtmosphere();
        var target = GpuHeatGlow.buffer(8, 6, false);
        var quad = GpuAtmosphere.screenQuad();
        var assets = new GpuAssets();
        try {
            var tuning = new GpuBoardTuning(skin.skin);
            Slider shadows = tuning.atmosphereRows().findActor("Shadows");
            Slider ambient = tuning.atmosphereRows().findActor("Ambient light");
            Slider sky = tuning.atmosphereRows().findActor("Sky contribution");
            assertEquals(100, shadows.getValue()); assertEquals(20, ambient.getValue()); assertEquals(100, sky.getValue());
            shadows.setValue(25); ambient.setValue(60); sky.setValue(50);
            tuning.setEnvironmentImage(image);
            assertEquals(.25f, tuning.atmosphereOptions().shadowStrength());
            assertEquals(.6f, tuning.atmosphereOptions().ambientShare());
            assertSame(image, tuning.atmosphereOptions().environmentImage());
            atmosphere.configure(BoardAtmosphere.DEFAULTS);
            float exposure = atmosphere.exposure();
            atmosphere.setOptions(tuning.atmosphereOptions());
            assertEquals(exposure, atmosphere.exposure(), "Manual lighting must not be cancelled by exposure adaptation");
            TextButton reset = tuning.defaults();
            reset.fire(new ChangeListener.ChangeEvent());
            assertNull(tuning.atmosphereOptions().environmentImage());
            assertEquals(100, shadows.getValue()); assertEquals(20, ambient.getValue()); assertEquals(100, sky.getValue());

            var lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS).tuned(1, .2f, 1, image);
            var array = assets.sculptArray(List.of());
            environment.terrainArray(array, 0);
            // Select after the array exists, exercising a live image replacement without rebuilding materials.
            environment.set(lighting);
            for (boolean arrayPath : new boolean[] { false, true }) {
                String prefix = "#define environmentLightingFlag\n" + (arrayPath ? "#define environmentArrayFlag\n" : "");
                var shader = GpuGlsl.compile("Lighting controls", prefix, GpuShaderSource.read("atmosphere.vert"),
                      "#version 330 core\n" + GpuShaderSource.read("light-model.glsl")
                            + GpuShaderSource.read("environment-lighting.glsl") + """
                      layout(location=0) out vec4 fragColor;
                      void main() {
                          float roughness = floor(gl_FragCoord.y) / 5.0;
                          vec3 direction = normalize(vec3(cos(gl_FragCoord.x), sin(gl_FragCoord.x), .3));
                          fragColor = vec4(environmentRadiance(direction, roughness, vec3(0), vec3(0)), shadowVisibility(0.0));
                      }
                      """);
                try {
                    target.begin();
                    Gdx.gl.glDisable(GL20.GL_BLEND); Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
                    shader.bind();
                    shader.setUniformf("u_environmentImage", 1, .5f);
                    shader.setUniformf("u_environmentSky", 0, 0, 0); shader.setUniformf("u_environmentHorizon", 0, 0, 0);
                    shader.setUniformf("u_shadowStrength", .25f);
                    if (arrayPath) {
                        array.bind(0);
                        shader.setUniformi("u_terrainLayers", 0); shader.setUniformf("u_environmentLayer", 0);
                        assertFalse(shader.hasUniform("u_environmentLut"));
                    } else { environment.texture().bind(0); shader.setUniformi("u_environmentLut", 0); }
                    quad.render(shader, GL20.GL_TRIANGLES);
                    var output = BufferUtils.newFloatBuffer(8 * 6 * 4);
                    Gdx.gl.glReadPixels(0, 0, 8, 6, GL20.GL_RGBA, GL20.GL_FLOAT, output);
                    for (int i = 0; i < 8 * 6; i++) {
                        assertEquals(GpuColor.linear(128 / 255f) * .5, output.get(i * 4), .0002);
                        assertEquals(GpuColor.linear(64 / 255f) * .5, output.get(i * 4 + 1), .0001);
                        assertEquals(GpuColor.linear(32 / 255f) * .5, output.get(i * 4 + 2), .0001);
                        assertEquals(.75, output.get(i * 4 + 3), .0001);
                    }
                    target.end();
                } finally { shader.dispose(); }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            assets.dispose();
            quad.dispose(); target.dispose(); atmosphere.dispose(); environment.dispose(); skin.dispose();
        }
    }
}
