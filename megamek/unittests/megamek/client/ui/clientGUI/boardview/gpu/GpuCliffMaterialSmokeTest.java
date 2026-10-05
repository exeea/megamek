/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A constant horizontal field isolates the repeated vertical motifs that a short cliff cannot reveal. */
@Tag("on-demand")
class GpuCliffMaterialSmokeTest {
    private static final int PERIOD = 64;
    private static final List<String> MATERIALS = List.of("soil-contact", "granite-contact", "sandstone",
          "mars-bedrock", "volcano-basalt", "lunar-cliff", "fungus-cliff", "fungus-fibres", "dirt", "snow",
          "volcano-ground", "mars-hardpan");

    @Test
    void tallNaturalFacesDoNotRepeatAtTheSourceMapPeriod() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(512, 768);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkMaterials(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tall cliff material repetition", failure.get()); }
    }

    private static void checkMaterials() throws Exception {
        var assets = new GpuAssets();
        var quad = new Mesh(true, 4, 0, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        String materials = GpuShaderSource.read("terrain-materials.glsl");
        String sampling = materials.substring(materials.indexOf("vec4 materialTexel("),
              materials.indexOf("// Every role shares projection"));
        var shader = GpuGlsl.compile("cliff-material-repeat-test", """
              in vec2 a_position;
              void main() { gl_Position = vec4(a_position, 0.0, 1.0); }
              """, """
              out vec4 fragColor;
              uniform sampler2DArray u_terrainLayers;
              uniform float u_layer;
              uniform int u_mode;
              """ + sampling + """
              void main() {
                  vec2 uv = gl_FragCoord.xy / 64.0;
                  mat2 gradient = mat2(dFdx(uv), dFdy(uv));
                  vec4 color = u_mode == 0 ? translatedTexel(u_layer, uv, gradient, .37)
                        : u_mode == 2 ? fungusTexel(u_layer, uv, gradient)
                        : cliffTexel(u_layer, uv, gradient, .37);
                  fragColor = vec4(color.rgb, 1.0);
              }
              """);
        try {
            var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "cliff-materials");
            Files.createDirectories(output.toPath());
            var array = assets.sculptArray(MATERIALS);
            StringBuilder report = new StringBuilder("Material: vertical correlation at one source period, fixed column -> height-aware\n");
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            array.bind(0);
            shader.bind();
            shader.setUniformi("u_terrainLayers", 0);
            for (int layer = 0; layer < MATERIALS.size(); layer++) {
                String name = MATERIALS.get(layer);
                shader.setUniformf("u_layer", 2f * layer);
                double[] correlation = new double[2];
                for (int pass = 0; pass < 2; pass++) {
                    shader.setUniformi("u_mode", pass == 0 ? 0 : name.startsWith("fungus") ? 2 : 1);
                    quad.render(shader, GL20.GL_TRIANGLE_STRIP);
                    Pixmap pixels = ScreenUtils.getFrameBufferPixmap(0, 0,
                          Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                    try { correlation[pass] = correlation(pixels); }
                    finally { pixels.dispose(); }
                    GpuReviewFrame.save(new File(output, name + (pass == 0 ? "-fixed.png" : "-varied.png")));
                }
                report.append(name).append(": ").append(correlation[0]).append(" -> ").append(correlation[1]).append('\n');
                assertTrue(correlation[0] > .98, name + ": exercise the repeating source, not an empty frame");
                assertTrue(correlation[1] < .8, name + ": repeated cliff motif remains, correlation=" + correlation[1]);
            }
            Files.writeString(new File(output, "report.txt").toPath(), report);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            shader.dispose();
            quad.dispose();
            assets.dispose();
        }
    }

    private static double correlation(Pixmap pixels) {
        double a = 0, b = 0, aa = 0, bb = 0, ab = 0;
        int count = 0;
        for (int y = 0; y < pixels.getHeight() - PERIOD; y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                double first = luminance(pixels.getPixel(x, y));
                double second = luminance(pixels.getPixel(x, y + PERIOD));
                a += first; b += second; aa += first * first; bb += second * second; ab += first * second; count++;
            }
        }
        return (ab - a * b / count) / Math.sqrt((aa - a * a / count) * (bb - b * b / count));
    }

    private static double luminance(int rgba) {
        return .2126 * (rgba >>> 24) + .7152 * (rgba >>> 16 & 255) + .0722 * (rgba >>> 8 & 255);
    }
}
