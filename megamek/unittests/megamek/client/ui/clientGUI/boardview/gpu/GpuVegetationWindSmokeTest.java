/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Measure the actual vertex outputs, including the shared gust clock and every vegetation mesh LOD. */
@Tag("on-demand")
class GpuVegetationWindSmokeTest {
    @Test
    void strongerWindMovesFasterAndBendsMoreWithoutMovingRoots() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkClock(); checkPlants(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Vegetation wind response", failure.get()); }
    }

    private static void checkClock() throws Exception {
        var terrain = new GpuTerrain();
        var phase = GpuTerrain.class.getDeclaredField("vegetationPhase");
        phase.setAccessible(true);
        try {
            float previous = 0, breeze = 0;
            for (float strength : new float[] { 0, .25f, .5f, .75f, 1 }) {
                float before = phase.getFloat(terrain);
                terrain.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, strength, 115));
                terrain.animate(0, List.of());
                assertEquals(before, phase.getFloat(terrain), "Changing strength must not jump gust phase");
                terrain.animate(.1f, List.of());
                float step = phase.getFloat(terrain) - before;
                assertTrue(step > previous, "Stronger wind must advance the gust faster");
                if (strength == .25f) { breeze = step; }
                if (strength == 1) { assertTrue(step > breeze * 2, "Maximum wind must be visibly faster than a breeze"); }
                previous = step;
            }
            terrain.animate(10000, List.of());
            assertTrue(phase.getFloat(terrain) >= 0 && phase.getFloat(terrain) < MathUtils.PI2,
                  "Long sessions must retain a bounded animation phase");
        } finally { terrain.dispose(); }
    }

    private static void checkPlants() {
        int grass = program(true), plants = program(false);
        int vao = GL30.glGenVertexArrays(), buffer = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, buffer);
        GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 6L * Float.BYTES, GL15.GL_STREAM_READ);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, buffer);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        try {
            Vector3 root = new Vector3(.17f, .23f, .1f);
            for (int kind = 0; kind <= 2; kind++) {
                int shader = kind == 0 ? grass : plants;
                GL20.glUseProgram(shader);
                GL20.glUniform1f(GL20.glGetUniformLocation(shader, "u_biomeKind"), kind);
                float previousSpan = -1;
                for (float strength : new float[] { 0, .25f, .5f, .75f, 1 }) {
                    float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                    Vector3 calm = null;
                    for (int phase = 0; phase < 64; phase++) {
                        float gustPhase = phase * MathUtils.PI2 / 64;
                        for (int lod = 0; lod < (kind == 0 ? 1 : 3); lod++) {
                            GL20.glUniform1f(GL20.glGetUniformLocation(shader, "u_biomeLod"), lod);
                            // Keep this tier visible without changing the tested root or wind.
                            GL20.glUniform1f(GL20.glGetUniformLocation(shader, "u_coverPixels"),
                                  kind == 0 || lod == 0 ? 600 : lod == 1 ? 160 : 48);
                            assertTrue(sample(shader, root, strength, gustPhase, 0).len() < .000001f,
                                  "A gust cannot move the root at any LOD");
                            Vector3 tip = sample(shader, root, strength, gustPhase, kind == 0 ? 1 : kind == 1 ? 1.04f : 1.1f);
                            assertTrue(tip.z > 0, "The centreline must stay above its support");
                            if (lod != 0) { continue; }
                            if (strength == 0) {
                                if (calm == null) { calm = tip; }
                                assertEquals(calm, tip, "Calm plants must stay still as time advances");
                            }
                            float angle = (float) Math.atan2(tip.x * .8f + tip.y * .6f, tip.z);
                            low = Math.min(low, angle); high = Math.max(high, angle);
                        }
                    }
                    float span = high - low;
                    assertTrue(span > previousSpan + .005f, "Angular sway must grow throughout the slider for kind " + kind);
                    previousSpan = span;
                    if (kind == 0 && strength == 1) {
                        assertTrue(high > 1.47f && high < 1.57f, "Maximum grass retains its nearly horizontal bend");
                    }
                }
            }
            GL20.glUseProgram(plants);
            GL20.glUniform1f(GL20.glGetUniformLocation(plants, "u_biomeKind"), 1);
            GL20.glUniform1f(GL20.glGetUniformLocation(plants, "u_coverPixels"), 600);
            GL20.glUniform1f(GL20.glGetUniformLocation(plants, "u_biomeLod"), 0);
            checkRowFaces(plants);
            GL20.glVertexAttrib4f(15, 0, 0, 0, 1);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL20.glUseProgram(0);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
            GL15.glDeleteBuffers(buffer);
            GL30.glBindVertexArray(0);
            GL30.glDeleteVertexArrays(vao);
            GL20.glDeleteProgram(grass); GL20.glDeleteProgram(plants);
        }
    }

    private static void checkRowFaces(int shader) {
        float length = GpuBiomeVegetation.ROW_LENGTH, rise = .42f;
        // Keep the whole strip inside the test view when the configurable plant spacing grows.
        float clipScale = 1 / (length + 2);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(shader, "u_projViewTrans"), false,
              new Matrix4().setToScaling(clipScale, clipScale, clipScale).val);
        Vector3 along = new Vector3(-BoardBiome.ROW_Y, BoardBiome.ROW_X, 0);
        Vector3 root = new Vector3(0, 0, .1f);
        Vector3 side = new Vector3(0, -1, 0), front = new Vector3(1, 0, 0), top = new Vector3(0, 0, 1);
        for (float strength : new float[] { 0, .5f, 1 }) {
            for (float phase : new float[] { 0, 1, 2, 3 }) {
                for (int i = 0; i < GpuBiomeVegetation.PLANTS_PER_ROW; i++) {
                    float centre = (i + .5f) / GpuBiomeVegetation.PLANTS_PER_ROW;
                    for (float height : new float[] { 0, GpuBiomeVegetation.CANOPY_HEIGHT, 1.04f }) {
                        Vector3 point = new Vector3(centre - .5f, 0, height);
                        GL20.glVertexAttrib4f(15, length, rise, 0, 1);
                        Vector3 whole = sample(shader, root, strength, phase, point, side).add(root);
                        assertTrue(whole.dst(sample(shader, root, strength, phase, point, front).add(root)) < .00001f,
                              "Side and front must share each variant's stem throughout a gust");
                        if (height == GpuBiomeVegetation.CANOPY_HEIGHT) {
                            assertTrue(whole.dst(sample(shader, root, strength, phase, point, top).add(root)) < .00001f,
                                  "The overhead crown must meet both upright planes throughout a gust");
                        }
                        if (height == 0) {
                            assertTrue(whole.dst(new Vector3(root).mulAdd(along, (centre - .5f) * length)
                                  .add(0, 0, (centre - .5f) * rise)) < .00001f,
                                  "Every plant's stem must stay on the supported slope");
                        }
                        // A hex boundary or bend can split a plant's supporting strip on either side of its centre.
                        for (float[] interval : new float[][] { { 0, centre + .02f }, { centre - .02f, 1 } }) {
                            float middle = (interval[0] + interval[1]) / 2 - .5f, span = interval[1] - interval[0];
                            Vector3 fragment = new Vector3(root).mulAdd(along, middle * length).add(0, 0, middle * rise);
                            GL20.glVertexAttrib4f(15, length * span, rise * span, interval[0], interval[1]);
                            for (Vector3 normal : List.of(side, front, top)) {
                                Vector3 split = sample(shader, fragment, strength, phase, point, normal).add(fragment);
                                assertTrue(whole.dst(split) < .00002f,
                                      "Clipping must preserve plant " + i + " at height " + height + " with plane " + normal
                                            + ": " + whole + " versus " + split);
                            }
                        }
                    }
                }
            }
        }
    }

    private static int program(boolean grass) {
        String source = """
              #version 330 core
              uniform mat4 u_projViewTrans;
              uniform mat4 u_worldTrans;
              uniform mat3 u_normalMatrix;
              layout(location = 0) in vec3 a_position;
              layout(location = 1) in vec3 a_normal;
              layout(location = 2) in vec4 a_color;
              out vec3 probePosition;
              out vec3 probeNormal;
              out vec4 v_color;
              void main() {
                  vec4 pos = u_worldTrans * vec4(a_position, 1.0);
                  vec3 normal = normalize(u_normalMatrix * a_normal);
                  v_color = a_color;
                  probePosition = pos.xyz;
                  probeNormal = normal;
                  gl_Position = pos;
              }
              """;
        int shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(shader, grass ? GpuGroundCover.vertex(source) : GpuBiomeVegetation.vertex(source));
        GL20.glCompileShader(shader);
        assertEquals(GL11.GL_TRUE, GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS), GL20.glGetShaderInfoLog(shader));
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL30.glTransformFeedbackVaryings(program, new String[] { "probePosition", "probeNormal" }, GL30.GL_INTERLEAVED_ATTRIBS);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);
        assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS), GL20.glGetProgramInfoLog(program));
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "u_projViewTrans"), false, new Matrix4().val);
        for (String uniform : List.of("u_worldMetre", "u_metre", "u_levelHeight", "u_coverHexWidth")) {
            GL20.glUniform1f(GL20.glGetUniformLocation(program, uniform), 1);
        }
        return program;
    }

    private static Vector3 sample(int program, Vector3 root, float strength, float phase, float height) {
        return sample(program, root, strength, phase,
              GL20.glGetUniformLocation(program, "u_biomeKind") >= 0 ? new Vector3(0, 0, height) : new Vector3(0, height, 0));
    }

    private static Vector3 sample(int program, Vector3 root, float strength, float phase, Vector3 point) {
        return sample(program, root, strength, phase, point, new Vector3(0, -1, 0));
    }

    private static Vector3 sample(int program, Vector3 root, float strength, float phase, Vector3 point, Vector3 normal) {
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "u_wind"), .8f, .6f, strength);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_vegetationPhase"), phase);
        GL20.glVertexAttrib4f(14, root.x, root.y, root.z, .33f);
        GL20.glVertexAttrib3f(0, point.x, point.y, point.z);
        GL20.glVertexAttrib3f(1, normal.x, normal.y, normal.z);
        GL20.glVertexAttrib4f(2, 1, 1, 1, 1);
        GL30.glBeginTransformFeedback(GL11.GL_POINTS);
        GL11.glDrawArrays(GL11.GL_POINTS, 0, 1);
        GL30.glEndTransformFeedback();
        float[] data = new float[6];
        GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, data);
        for (float value : data) { assertTrue(Float.isFinite(value), "Non-finite vegetation shader output"); }
        return new Vector3(data[0], data[1], data[2]).sub(root);
    }
}
