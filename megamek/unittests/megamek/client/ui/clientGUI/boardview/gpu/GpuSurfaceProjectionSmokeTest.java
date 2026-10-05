/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GPU/CPU boundary checks: normals match physical height derivatives; grass roots share the sand's openings. */
@Tag("on-demand")
class GpuSurfaceProjectionSmokeTest {
    private static final int SIZE = 64;

    @Test
    void projectedReliefAndSandOpeningsMatchTheirPhysicalFields() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(SIZE, SIZE);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkProjection(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Surface projection", failure.get()); }
    }

    private static void checkProjection() {
        var quad = new Mesh(true, 4, 0, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        var shader = GpuGlsl.compile("surface-projection-test", """
              in vec2 a_position;
              void main() { gl_Position = vec4(a_position, 0.0, 1.0); }
              """, """
              out vec4 fragColor;
              uniform int u_test;
              uniform sampler2DArray u_terrainLayers;
              uniform vec4 u_sculptTiles, u_sculptLayers;
              const mat2 TURN = mat2(.8253, .5646, -.5646, .8253);
              """ + GpuShaderSource.read("terrain-projection.glsl") + GpuShaderSource.read("terrain-hexes.glsl") + """
              void main() {
                  vec2 p = (gl_FragCoord.xy - 32.0) * .73;
                  if (u_test == 0) {
                      fragColor = vec4(vec3(sandExposure(p)), 1.0);
                  } else {
                      vec2 q = TURN * p / 2.37 + .31;
                      vec3 normal = normalize(vec3(-.7 * cos(q.x * 6.2831853), -.4 * sin(q.y * 6.2831853), 1.0));
                      farDetail = 0.0;
                      vec4 texel = vec4(normal * .5 + .5, 1.0);
                      fragColor = vec4(planarNormal(texel, texel, 1.0).rgb * .5 + .5, 1.0);
                  }
              }
              """);
        try {
            Gdx.gl.glViewport(0, 0, SIZE, SIZE);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            shader.bind();
            for (int test = 0; test < 2; test++) {
                shader.setUniformi("u_test", test);
                quad.render(shader, GL20.GL_TRIANGLE_STRIP);
                var image = ScreenUtils.getFrameBufferPixmap(0, 0, SIZE, SIZE);
                try {
                    for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                        float px = (x + .5f - 32) * .73f, py = (y + .5f - 32) * .73f;
                        int rgba = image.getPixel(x, y);
                        if (test == 0) {
                            assertEquals(BoardSurfaceBlend.sandExposure(px, py), (rgba >>> 24) / 255f, .006,
                                  "Sand and grass disagree at " + px + ", " + py);
                        } else {
                            double delta = .001;
                            var normal = new Vector3((float) ((height(px - delta, py) - height(px + delta, py)) / (2 * delta)),
                                  (float) ((height(px, py - delta) - height(px, py + delta)) / (2 * delta)), 1).nor();
                            assertEquals(normal.x, (rgba >>> 24) / 127.5f - 1, .01, "Relief U slope");
                            assertEquals(normal.y, (rgba >>> 16 & 255) / 127.5f - 1, .01, "Relief V slope");
                            assertEquals(normal.z, (rgba >>> 8 & 255) / 127.5f - 1, .01, "Relief vertical component");
                        }
                    }
                } finally { image.dispose(); }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            shader.dispose();
            quad.dispose();
        }
    }

    /** Metre-space height, differentiated independently of the shader's normal transformation. */
    private static double height(double x, double y) {
        double u = (.8253 * x - .5646 * y) / 2.37 + .31;
        double v = (.5646 * x + .8253 * y) / 2.37 + .31;
        return (.7 * Math.sin(2 * Math.PI * u) - .4 * Math.cos(2 * Math.PI * v)) / (2 * Math.PI);
    }
}
