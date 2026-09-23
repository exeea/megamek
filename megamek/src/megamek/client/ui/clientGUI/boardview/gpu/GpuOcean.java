/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * Wind waves simulated on the GPU after Tessendorf's FFT ocean. A wind-driven spectrum is evolved every frame with
 * the deep-water dispersion relation, so every wave travels at its own speed and the surface keeps changing instead
 * of sliding, and an inverse FFT in fragment passes turns it into wave slopes and whitecaps. The result is one small
 * texture that tiles in both directions: red and green hold the slope along world x and y, blue how tightly the crest
 * there is compressed and alpha foam, which lingers after a crest folds. It is mipmapped, so distant water averages
 * its waves instead of aliasing them. Fragment passes need only OpenGL 3.3, so this also runs where compute shaders
 * do not, such as on macOS.
 */
final class GpuOcean implements Disposable {
    /** Texels along each side of the simulation. */
    static final int SIZE = 128;
    /** Metres over which the waves repeat: about two hexes. */
    static final float PATCH_METRES = 64;
    private static final int BITS = Integer.numberOfTrailingZeros(SIZE);
    private static final float GRAVITY = 9.81f;
    /** Every frequency is a multiple of 2π over this many seconds, so the clock can wrap without a jump. */
    private static final float LOOP_SECONDS = 1000;
    private static final String PATH = "megamek/client/ui/clientGUI/boardview/gpu/";

    private ShaderProgram spectrum;
    private ShaderProgram butterfly;
    private ShaderProgram finish;
    private Mesh quad;
    private int initial;
    /** Ping-pong targets of the transform, and of the result, whose foam carries over from the previous frame. */
    private final FrameBuffer[] work = new FrameBuffer[2];
    private final FrameBuffer[] result = new FrameBuffer[2];
    private int latest;
    private boolean failed;
    private float windX = Float.NaN;
    private float windY;
    private float windStrength;
    private float previousTime = Float.NaN;

    /** Whether this context can run the simulation: it needs OpenGL 3 for float targets and integer shaders. */
    static boolean supported() {
        return Gdx.gl30 != null;
    }

    /** The latest waves, or null where the context cannot simulate them. */
    Texture texture() {
        return result[latest] == null ? null : result[latest].getColorBufferTexture();
    }

    /** The latest result read back, four floats per texel row by row; empty where nothing was simulated. */
    FloatBuffer pixels() {
        FloatBuffer pixels = BufferUtils.newFloatBuffer(result[latest] == null ? 0 : SIZE * SIZE * 4);
        if (result[latest] == null) { return pixels; }
        IntBuffer state = saveState();
        result[latest].bind();
        Gdx.gl.glReadPixels(0, 0, SIZE, SIZE, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
        restoreState(state);
        return pixels;
    }

    /** World-space scale of the texture: multiply a world position by it to get texture coordinates. */
    static float scale() {
        return 1 / BoardRelief.metres(PATCH_METRES);
    }

    /**
     * Advances the waves to the given time for a wind (direction in x and y, strength from 0 to 1 in z). Runs outside
     * any model batch; it restores the framebuffer and viewport it found.
     */
    void update(float time, Vector3 wind) {
        if (failed || !supported()) { return; }
        try {
            if (quad == null) { create(); }
            float strength = MathUtils.clamp(wind.z, 0, 1);
            float x = wind.x, y = wind.y;
            float length = (float) Math.hypot(x, y);
            if (length < .01f) { x = .8f; y = .6f; } else { x /= length; y /= length; }
            if (Math.abs(x - windX) > .02f || Math.abs(y - windY) > .02f || Math.abs(strength - windStrength) > .02f) {
                windX = x;
                windY = y;
                windStrength = strength;
                upload(initialSpectrum(x, y, strength));
            }
            simulate(time);
        } catch (GdxRuntimeException error) {
            // A driver that cannot compile or attach these leaves the water on its static ripples.
            failed = true;
            Gdx.app.error("GpuOcean", "Wave simulation disabled", error);
            dispose();
        }
    }

    private void create() {
        String vertex = Gdx.files.classpath(PATH + "ocean.vert").readString();
        spectrum = program(vertex, "ocean-spectrum.frag");
        butterfly = program(vertex, "ocean-fft.frag");
        finish = program(vertex, "ocean-finish.frag");
        quad = new Mesh(true, 4, 0, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        for (int i = 0; i < 2; i++) {
            work[i] = new GLFrameBuffer.FrameBufferBuilder(SIZE, SIZE)
                  .addFloatAttachment(GL30.GL_RGBA32F, GL30.GL_RGBA, GL30.GL_FLOAT, true).build();
            result[i] = new GLFrameBuffer.FrameBufferBuilder(SIZE, SIZE)
                  .addFloatAttachment(GL30.GL_RGBA16F, GL30.GL_RGBA, GL30.GL_FLOAT, true).build();
            Texture waves = result[i].getColorBufferTexture();
            waves.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            waves.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            waves.setAnisotropicFilter(8);
        }
        initial = Gdx.gl.glGenTexture();
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, initial);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST);
        // The first frame reads a previous result; start both without foam.
        IntBuffer state = saveState();
        for (FrameBuffer target : result) {
            target.bind();
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            target.getColorBufferTexture().bind(0);
            Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
        }
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        restoreState(state);
    }

    private static ShaderProgram program(String vertex, String fragment) {
        ShaderProgram program = new ShaderProgram(vertex, Gdx.files.classpath(PATH + fragment).readString());
        if (!program.isCompiled()) {
            String log = program.getLog();
            program.dispose();
            throw new GdxRuntimeException(fragment + ": " + log);
        }
        return program;
    }

    /**
     * Phillips spectrum for the wind, as h0(k) and conj(h0(-k)) per texel. Waves against the wind are damped and a
     * little energy is spread in every direction, so crests never line up into ruled stripes. The amplitude is set by
     * the slope it produces, not by an absolute height, so the look holds at any scale: calm water ripples, a gale
     * steepens into breaking crests.
     */
    static float[] initialSpectrum(float windX, float windY, float strength) {
        // The spectrum's shape: its peak stays well inside the patch, so no single wave spans the whole tile.
        float speed = 2 + 5 * strength;
        float largest = speed * speed / GRAVITY;
        float smallest = PATCH_METRES / SIZE / 2;
        float[] real = new float[SIZE * SIZE], imaginary = new float[SIZE * SIZE];
        Random random = new Random(0x6f6365616eL);
        double variance = 0;
        for (int m = 0; m < SIZE; m++) {
            for (int n = 0; n < SIZE; n++) {
                float kx = MathUtils.PI2 * (n - SIZE / 2) / PATCH_METRES;
                float ky = MathUtils.PI2 * (m - SIZE / 2) / PATCH_METRES;
                float k = (float) Math.hypot(kx, ky);
                double gaussReal = random.nextGaussian(), gaussImaginary = random.nextGaussian();
                // The Nyquist rows have no conjugate partner; leaving them empty keeps the result real.
                if (k < 1e-6f || n == 0 || m == 0) { continue; }
                float along = (kx * windX + ky * windY) / k;
                float spread = along * along * (along < 0 ? .07f : 1) * .85f + .15f;
                double phillips = Math.exp(-1 / (k * largest * k * largest)) / (k * k * k * k) * spread
                      * Math.exp(-k * k * smallest * smallest);
                double amplitude = Math.sqrt(phillips / 2);
                real[m * SIZE + n] = (float) (gaussReal * amplitude);
                imaginary[m * SIZE + n] = (float) (gaussImaginary * amplitude);
                // Mean square slope this wave contributes, with its mirror image, before normalization.
                variance += k * k * phillips * (gaussReal * gaussReal + gaussImaginary * gaussImaginary);
            }
        }
        // Root-mean-square slope: glassy at a whisper of wind, steep and breaking in a gale.
        float slope = .07f + .2f * strength;
        float scale = (float) (slope / Math.sqrt(Math.max(variance, 1e-20)));
        float[] data = new float[SIZE * SIZE * 4];
        for (int m = 0; m < SIZE; m++) {
            for (int n = 0; n < SIZE; n++) {
                int index = m * SIZE + n, mirror = ((SIZE - m) % SIZE) * SIZE + (SIZE - n) % SIZE;
                data[index * 4] = real[index] * scale;
                data[index * 4 + 1] = imaginary[index] * scale;
                data[index * 4 + 2] = real[mirror] * scale;
                data[index * 4 + 3] = -imaginary[mirror] * scale;
            }
        }
        return data;
    }

    private void upload(float[] data) {
        FloatBuffer buffer = BufferUtils.newFloatBuffer(data.length);
        buffer.put(data).flip();
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, initial);
        Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, SIZE, SIZE, 0, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
    }

    private void simulate(float time) {
        float delta = Float.isNaN(previousTime) ? 0 : Math.clamp(time - previousTime, 0, .25f);
        previousTime = time;
        IntBuffer state = saveState();
        boolean depthTest = Gdx.gl.glIsEnabled(GL20.GL_DEPTH_TEST), blend = Gdx.gl.glIsEnabled(GL20.GL_BLEND);
        boolean cull = Gdx.gl.glIsEnabled(GL20.GL_CULL_FACE), scissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        // The wave spectrum at this instant.
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, initial);
        spectrum.bind();
        spectrum.setUniformi("u_initial", 0);
        spectrum.setUniformf("u_time", time % LOOP_SECONDS);
        spectrum.setUniformf("u_loop", MathUtils.PI2 / LOOP_SECONDS);
        spectrum.setUniformf("u_patch", PATCH_METRES);
        spectrum.setUniformi("u_size", SIZE);
        pass(spectrum, work[0]);
        // Inverse transform: first along x, then along y, one radix-2 stage per pass.
        int source = 0;
        butterfly.bind();
        butterfly.setUniformi("u_source", 0);
        butterfly.setUniformi("u_bits", BITS);
        for (int vertical = 0; vertical < 2; vertical++) {
            for (int stage = 0; stage < BITS; stage++) {
                work[source].getColorBufferTexture().bind(0);
                butterfly.setUniformi("u_stage", stage);
                butterfly.setUniformi("u_vertical", vertical);
                pass(butterfly, work[1 - source]);
                source = 1 - source;
            }
        }
        // Slopes, crest compression and foam, which fades over a few seconds after its crest has passed.
        int next = 1 - latest;
        work[source].getColorBufferTexture().bind(0);
        result[latest].getColorBufferTexture().bind(1);
        finish.bind();
        finish.setUniformi("u_source", 0);
        finish.setUniformi("u_previous", 1);
        finish.setUniformi("u_size", SIZE);
        finish.setUniformf("u_texel", PATCH_METRES / SIZE);
        finish.setUniformf("u_choppiness", .8f + 1.2f * windStrength);
        finish.setUniformf("u_fade", delta / 2.5f);
        pass(finish, result[next]);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        Texture waves = result[next].getColorBufferTexture();
        waves.bind(0);
        Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        latest = next;
        enable(GL20.GL_DEPTH_TEST, depthTest);
        enable(GL20.GL_BLEND, blend);
        enable(GL20.GL_CULL_FACE, cull);
        enable(GL20.GL_SCISSOR_TEST, scissor);
        restoreState(state);
    }

    private static void enable(int capability, boolean enabled) {
        if (enabled) { Gdx.gl.glEnable(capability); } else { Gdx.gl.glDisable(capability); }
    }

    private void pass(ShaderProgram program, FrameBuffer target) {
        target.bind();
        Gdx.gl.glViewport(0, 0, SIZE, SIZE);
        quad.render(program, GL20.GL_TRIANGLE_STRIP);
    }

    /** The bound framebuffer and viewport, as found. */
    private static IntBuffer saveState() {
        IntBuffer state = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, state);
        state.position(4);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, state);
        state.position(0);
        return state;
    }

    private static void restoreState(IntBuffer state) {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, state.get(0));
        Gdx.gl.glViewport(state.get(4), state.get(5), state.get(6), state.get(7));
    }

    @Override
    public void dispose() {
        for (ShaderProgram program : new ShaderProgram[] { spectrum, butterfly, finish }) {
            if (program != null) { program.dispose(); }
        }
        spectrum = butterfly = finish = null;
        if (quad != null) { quad.dispose(); }
        quad = null;
        for (int i = 0; i < 2; i++) {
            if (work[i] != null) { work[i].dispose(); }
            if (result[i] != null) { result[i].dispose(); }
            work[i] = result[i] = null;
        }
        if (initial != 0) { Gdx.gl.glDeleteTexture(initial); }
        initial = 0;
        windX = Float.NaN;
        previousTime = Float.NaN;
    }
}
