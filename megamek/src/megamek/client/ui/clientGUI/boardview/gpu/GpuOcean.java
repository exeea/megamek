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
 * Wind waves simulated on the GPU after Tessendorf's FFT ocean. Water sums three cascades whose patches are about
 * 4.5 times apart, each owning one band of a fetch-limited JONSWAP sea, so no single tile repeats visibly and long
 * swells, chop and ripples all travel at their own deep-water speed. The cascades sit side by side in one texture:
 * every transform stage is one draw for all of them, 16 draws a frame in total. One finish pass writes, per cascade,
 * slope/height/persistent foam for shading, and for the longest cascade the choppy displacement that moves the
 * mesh. Lava runs a single cascade with its own viscous spectrum and output. The passes need OpenGL 3.3, no compute.
 */
final class GpuOcean implements Disposable {
    /** Texels along each side of one cascade. */
    static final int SIZE = 128;
    /** Metres over which each water cascade repeats, longest first. Their ratios are deliberately not integers. */
    static final float[] PATCHES = { 250, 55, 12.5f };
    /** Lava's single patch. */
    static final float LAVA_PATCH = 64;
    /** A cascade hands over to the next at this many of the next one's own harmonics, where its sampling is fine. */
    private static final float HANDOVER = 4;
    private static final int BITS = Integer.numberOfTrailingZeros(SIZE);
    private static final float GRAVITY = 9.81f;
    /** Every frequency is a multiple of 2π over this many seconds, so the clock can wrap without a jump. */
    private static final float LOOP_SECONDS = 1000;
    /** The finish pass's previous result of each water cascade, for foam that outlives its crest. */
    private static final String[] PREVIOUS = { "u_previous0", "u_previous1", "u_previous2" };
    /** Effective open-sea fetch. Enclosed water carries less of it through the exposure map, never more. */
    private static final float FETCH_METRES = 60_000;
    /** The same FFT with a slowly evolving, short-wave-damped spectrum and displacement output for lava. */
    private final boolean lava;
    private final int cascades;

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

    GpuOcean() { this(false); }

    GpuOcean(boolean lava) {
        this.lava = lava;
        cascades = lava ? 1 : PATCHES.length;
    }

    /** Whether this context can run the simulation: it needs OpenGL 3 for float targets and integer shaders. */
    static boolean supported() {
        return Gdx.gl30 != null;
    }

    /**
     * The latest waves of one cascade, or null where the context cannot simulate them. Water: RG slope, B height in
     * metres, A persistent breaking foam. Lava: RG slope, BA horizontal displacement.
     */
    Texture waves(int cascade) {
        return result[latest] == null ? null : result[latest].getTextureAttachments().get(cascade);
    }

    /** The first cascade's waves: lava's only output, and water's longest swell. */
    Texture texture() { return waves(0); }

    /** The longest water cascade's choppy XY displacement, height in metres and crest compression (1 - Jacobian). */
    Texture displacement() {
        return lava || result[latest] == null ? null : result[latest].getTextureAttachments().get(cascades);
    }

    /** World-space scale of a water cascade: multiply a world position by it to get texture coordinates. */
    static float scale(int cascade) {
        return 1 / BoardRelief.metres(PATCHES[cascade]);
    }

    /** World-space scale of lava's waves. */
    static float lavaScale() {
        return 1 / BoardRelief.metres(LAVA_PATCH);
    }

    /**
     * Advances the waves to the given time for a wind (direction in x and y, strength from 0 to 1 in z). Runs outside
     * any model batch; it restores the framebuffer and viewport it found.
     */
    void update(float time, Vector3 wind) {
        if (failed || !supported()) { return; }
        try {
            if (quad == null) { create(); }
            // Lava's convection is internal. Weather must not start or stop its motion.
            float strength = lava ? .35f : MathUtils.clamp(wind.z, 0, 1);
            float x = lava ? .8f : wind.x, y = lava ? .6f : wind.y;
            float length = (float) Math.hypot(x, y);
            if (length < .01f) { x = .8f; y = .6f; } else { x /= length; y /= length; }
            // A new or recreated simulation has no spectrum yet (windX is NaN, and every comparison with NaN is false).
            if (Float.isNaN(windX) || Math.abs(x - windX) > .02f || Math.abs(y - windY) > .02f
                  || Math.abs(strength - windStrength) > .02f) {
                windX = x;
                windY = y;
                windStrength = strength;
                upload(lava ? lavaSpectrum() : initialSpectrum(x, y, strength));
            }
            simulate(lava ? time * .12f : time);
        } catch (GdxRuntimeException error) {
            // A driver that cannot compile or attach these leaves the water on its static ripples.
            failed = true;
            Gdx.app.error("GpuOcean", "Wave simulation disabled", error);
            dispose();
        }
    }

    private void create() {
        spectrum = GpuShaderManager.program(() -> program("ocean-spectrum.frag"), next -> spectrum = next);
        butterfly = GpuShaderManager.program(() -> program("ocean-fft.frag"), next -> butterfly = next);
        finish = GpuShaderManager.program(() -> program(lava ? "ocean-lava-finish.frag" : "ocean-water-finish.frag"),
              next -> finish = next);
        quad = new Mesh(true, 4, 0, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        for (int i = 0; i < 2; i++) {
            work[i] = new GLFrameBuffer.FrameBufferBuilder(SIZE * cascades, SIZE)
                  .addFloatAttachment(GL30.GL_RGBA32F, GL30.GL_RGBA, GL30.GL_FLOAT, true).build();
            var output = new GLFrameBuffer.FrameBufferBuilder(SIZE, SIZE);
            // Water: one shading target per cascade, then the longest cascade's displacement.
            for (int target = 0; target < (lava ? 1 : cascades + 1); target++) {
                output.addFloatAttachment(GL30.GL_RGBA16F, GL30.GL_RGBA, GL30.GL_FLOAT, true);
            }
            result[i] = output.build();
            for (Texture waves : result[i].getTextureAttachments()) {
                waves.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
                waves.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                waves.setAnisotropicFilter(8);
            }
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
            for (Texture waves : target.getTextureAttachments()) {
                waves.bind(0);
                Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
            }
        }
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        restoreState(state);
    }

    static ShaderProgram program(String file) {
        return GpuGlsl.compile(file, GpuShaderSource.read("ocean.vert"), fragment(file));
    }

    static String fragment(String file) {
        String source = GpuShaderSource.read(file);
        return source.contains("// OCEAN_FINISH")
              ? source.replace("// OCEAN_FINISH", GpuShaderSource.read("ocean-finish.glsl")) : source;
    }

    /**
     * Water's three cascades side by side, as h0(k) and conj(h0(-k)) per texel. A fetch-limited JONSWAP sea for
     * the wind (Hasselmann et al. 1973), spread about the wind by Donelan-Banner's sech², plus a low, narrow swell
     * from a steady quarter to the wind so calm open water keeps breathing. Each cascade owns one band of wave
     * numbers; neighbouring cascades cross-fade in power over an octave, so the sum is the full spectrum once.
     * Amplitudes carry the wave-number cell area: the inverse transform yields heights in metres directly.
     */
    static float[] initialSpectrum(float windX, float windY, float strength) {
        // Calm air still ripples; a full gale (Beaufort 8) raises a steep, breaking sea with ~70 m peak waves.
        float speed = 1.5f + 18.5f * strength;
        double peak = Math.max(22 * Math.cbrt(GRAVITY * GRAVITY / (speed * FETCH_METRES)), .855 * GRAVITY / speed);
        double alpha = Math.max(.076 * Math.pow(speed * speed / (FETCH_METRES * GRAVITY), .22), .0081);
        double wind = Math.atan2(windY, windX), swellDirection = wind + .45;
        // Art direction, not physics: storm seas are drawn up to 1.5 times higher than this fetch would raise them, so
        // their crests read, fold and foam from a tactical camera as they do at sea level. Calm water stays physical.
        double storm = 1 + .5 * strength * strength;
        // An old swell ~110 m long, 0.3 m high in calm and 0.45 m in a gale; narrow, as swells are.
        double swellPeak = Math.sqrt(GRAVITY * MathUtils.PI2 / 110), swellAlpha = 5.6e-5 + 7e-5 * strength;
        int width = SIZE * PATCHES.length;
        float[] data = new float[width * SIZE * 4];
        for (int cascade = 0; cascade < PATCHES.length; cascade++) {
            float patch = PATCHES[cascade];
            double cell = MathUtils.PI2 / patch;
            double low = cascade == 0 ? 0 : HANDOVER * cell;
            double high = cascade + 1 < PATCHES.length ? HANDOVER * MathUtils.PI2 / PATCHES[cascade + 1] : Double.MAX_VALUE;
            float[] real = new float[SIZE * SIZE], imaginary = new float[SIZE * SIZE];
            // One fixed draw per texel: wind changes reshape the same sea instead of reshuffling it.
            Random random = new Random(0x6f6365616eL + cascade);
            for (int m = 0; m < SIZE; m++) {
                for (int n = 0; n < SIZE; n++) {
                    double gaussReal = random.nextGaussian(), gaussImaginary = random.nextGaussian();
                    double kx = cell * (n - SIZE / 2), ky = cell * (m - SIZE / 2), k = Math.hypot(kx, ky);
                    // The Nyquist rows have no conjugate partner; leaving them empty keeps the result real.
                    if (k < 1e-6 || n == 0 || m == 0) { continue; }
                    double share = (cascade == 0 ? 1 : handover(k, low)) * (1 - handover(k, high));
                    if (share <= 0) { continue; }
                    double omega = Math.sqrt(GRAVITY * k), theta = Math.atan2(ky, kx);
                    // S(ω)·D(θ)·dω/dk / k: variance per unit wave-number area, times the cell's area.
                    double sea = jonswap(omega, peak, alpha) * donelanBanner(omega / peak, angle(theta - wind))
                          + jonswap(omega, swellPeak, swellAlpha) * swellSpread(angle(theta - swellDirection));
                    double variance = sea * (GRAVITY / (2 * omega)) / k * cell * cell * share
                          // Capillary ripples below a few centimetres stay in the static detail map.
                          * Math.exp(-k * k * .0004);
                    // h0(k) and the mirrored conj(h0(-k)) both reach this wave vector: each carries half its variance.
                    double amplitude = Math.sqrt(variance / 4) * storm;
                    real[m * SIZE + n] = (float) (gaussReal * amplitude);
                    imaginary[m * SIZE + n] = (float) (gaussImaginary * amplitude);
                }
            }
            pack(data, width, cascade, real, imaginary);
        }
        return data;
    }

    /** Lava's single viscous cascade, independent of wind: broad folds, short waves suppressed. */
    static float[] lavaSpectrum() {
        float largest = 3.5f, smallest = 1.3f;
        float[] real = new float[SIZE * SIZE], imaginary = new float[SIZE * SIZE];
        Random random = new Random(0x6f6365616eL);
        double variance = 0;
        for (int m = 0; m < SIZE; m++) {
            for (int n = 0; n < SIZE; n++) {
                float kx = MathUtils.PI2 * (n - SIZE / 2) / LAVA_PATCH;
                float ky = MathUtils.PI2 * (m - SIZE / 2) / LAVA_PATCH;
                float k = (float) Math.hypot(kx, ky);
                double gaussReal = random.nextGaussian(), gaussImaginary = random.nextGaussian();
                if (k < 1e-6f || n == 0 || m == 0) { continue; }
                double phillips = Math.exp(-1 / (k * largest * k * largest)) / (k * k * k * k)
                      * Math.exp(-k * k * smallest * smallest);
                double amplitude = Math.sqrt(phillips / 2);
                real[m * SIZE + n] = (float) (gaussReal * amplitude);
                imaginary[m * SIZE + n] = (float) (gaussImaginary * amplitude);
                variance += k * k * phillips * (gaussReal * gaussReal + gaussImaginary * gaussImaginary);
            }
        }
        // Root-mean-square slope of the cooling skin's folds.
        float scale = (float) (.12 / Math.sqrt(Math.max(variance, 1e-20)));
        for (int i = 0; i < real.length; i++) { real[i] *= scale; imaginary[i] *= scale; }
        float[] data = new float[SIZE * SIZE * 4];
        pack(data, SIZE, 0, real, imaginary);
        return data;
    }

    /** Stores h0(k) and conj(h0(-k)) of one cascade into its block of the packed texture. */
    private static void pack(float[] data, int width, int cascade, float[] real, float[] imaginary) {
        for (int m = 0; m < SIZE; m++) {
            for (int n = 0; n < SIZE; n++) {
                int index = m * SIZE + n, mirror = ((SIZE - m) % SIZE) * SIZE + (SIZE - n) % SIZE;
                int texel = (m * width + cascade * SIZE + n) * 4;
                data[texel] = real[index];
                data[texel + 1] = imaginary[index];
                data[texel + 2] = real[mirror];
                data[texel + 3] = -imaginary[mirror];
            }
        }
    }

    /** JONSWAP frequency spectrum, m²·s, peak enhancement 3.3. */
    static double jonswap(double omega, double peak, double alpha) {
        double sigma = omega <= peak ? .07 : .09, offset = (omega - peak) / (sigma * peak);
        return alpha * GRAVITY * GRAVITY / Math.pow(omega, 5) * Math.exp(-1.25 * Math.pow(peak / omega, 4))
              * Math.pow(3.3, Math.exp(-.5 * offset * offset));
    }

    /** Donelan-Banner directional spreading: narrowest at the peak, broadening for shorter and longer waves. */
    static double donelanBanner(double ratio, double theta) {
        double beta = ratio < .95 ? 2.61 * Math.pow(Math.max(ratio, .56), 1.3)
              : ratio < 1.6 ? 2.28 * Math.pow(ratio, -1.3)
              : Math.pow(10, -.4 + .8393 * Math.exp(-.567 * Math.log(ratio * ratio)));
        double sech = 1 / Math.cosh(beta * theta);
        return beta / (2 * Math.tanh(beta * Math.PI)) * sech * sech;
    }

    /** A swell's narrow cos^2s(θ/2) spreading, s = 24: Γ(s+1) / (2√π Γ(s+½)) = 1.389 normalises it. */
    private static double swellSpread(double theta) {
        double c = Math.cos(theta / 2);
        return 1.389 * Math.pow(c * c, 24);
    }

    /** The angle wrapped into (-π, π]. */
    private static double angle(double theta) {
        return Math.IEEEremainder(theta, MathUtils.PI2);
    }

    /** 0 below 0.7 k, 1 above 1.4 k, smooth in log wave number: a power-complementary cross-fade. */
    private static double handover(double k, double boundary) {
        if (boundary <= 0) { return 1; }
        double t = MathUtils.clamp((float) (Math.log(k / (.7 * boundary)) / Math.log(2)), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private void upload(float[] data) {
        FloatBuffer buffer = BufferUtils.newFloatBuffer(data.length);
        buffer.put(data).flip();
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, initial);
        Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, SIZE * cascades, SIZE, 0, GL20.GL_RGBA,
              GL20.GL_FLOAT, buffer);
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
        // The wave spectrum of every cascade at this instant.
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, initial);
        spectrum.bind();
        spectrum.setUniformi("u_initial", 0);
        spectrum.setUniformf("u_time", time % LOOP_SECONDS);
        spectrum.setUniformf("u_loop", MathUtils.PI2 / LOOP_SECONDS);
        spectrum.setUniformf("u_patches", lava ? LAVA_PATCH : PATCHES[0], lava ? 1 : PATCHES[1], lava ? 1 : PATCHES[2]);
        spectrum.setUniformi("u_size", SIZE);
        spectrum.setUniformi("u_water", lava ? 0 : 1);
        pass(spectrum, work[0]);
        // Inverse transform: first along x, then along y, one radix-2 stage per pass, every cascade at once.
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
        // One MRT pass derives each cascade's shading, persistent foam and the swell's displacement.
        int next = 1 - latest;
        work[source].getColorBufferTexture().bind(0);
        finish.bind();
        finish.setUniformi("u_source", 0);
        finish.setUniformi("u_size", SIZE);
        if (!lava) {
            var previous = result[latest].getTextureAttachments();
            for (int cascade = 0; cascade < cascades; cascade++) {
                previous.get(cascade).bind(1 + cascade);
                finish.setUniformi(PREVIOUS[cascade], 1 + cascade);
            }
            finish.setUniformf("u_texels", PATCHES[0] / SIZE, PATCHES[1] / SIZE, PATCHES[2] / SIZE);
            // Horizontal displacement sharpens crests and broadens troughs; a gale folds the steepest over.
            finish.setUniformf("u_choppiness", .75f + .5f * windStrength, 1f, .6f);
            finish.setUniformf("u_delta", delta);
            // Foam drifts downwind at about 3 % of the wind's speed, measured in each cascade's texture.
            float drift = (.05f + .55f * windStrength) * delta;
            finish.setUniformf("u_drift", windX * drift, windY * drift);
            finish.setUniformf("u_patches", PATCHES[0], PATCHES[1], PATCHES[2]);
        }
        pass(finish, result[next]);
        for (int unit = 1; unit <= cascades; unit++) {
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        }
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        for (Texture waves : result[next].getTextureAttachments()) {
            waves.bind(0);
            Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
        }
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
        Gdx.gl.glViewport(0, 0, target.getWidth(), target.getHeight());
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
            if (program != null) { GpuShaderManager.dispose(program); }
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
