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
import com.badlogic.gdx.math.Vector2;
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
 * <p>
 * A new wind never replaces the sea. Its spectrum is generated on the GPU in one draw and the sea blends towards it
 * over {@link #TRANSITION_SECONDS}: every wave keeps its phase and keeps travelling, those along the new wind grow and
 * the others die away. A further change during a transition starts from wherever the sea has got to. The water shaders
 * read the same eased wind ({@link #wind()}), so ripples, gusts and foam turn with the waves instead of snapping.
 */
final class GpuOcean implements Disposable {
    /** Texels along each side of one cascade. */
    static final int SIZE = 128;
    /** Metres over which each water cascade repeats, longest first. Their ratios are deliberately not integers. */
    static final float[] PATCHES = { 250, 55, 12.5f };
    /** Lava's single patch. */
    static final float LAVA_PATCH = 64;
    /**
     * Crest-to-trough height, in metres, of the tallest waves at full wind: the one control for how rough the sea
     * gets. A board-sized patch of sea has a few hundred waves, whose tallest are about 1.6 times its significant
     * height; lighter winds scale down from here, and calm water keeps its physical ripples and swell.
     */
    static final float MAX_WAVE_HEIGHT = 12;
    /** Seconds a change of wind takes to reshape the sea: the old waves run on while the new ones build. */
    static final float TRANSITION_SECONDS = 8;
    private static final int BITS = Integer.numberOfTrailingZeros(SIZE);
    private static final float GRAVITY = 9.81f;
    /** Every frequency is a multiple of 2π over this many seconds, so the clock can wrap without a jump. */
    private static final float LOOP_SECONDS = 1000;
    /** The finish pass's previous result of the swell and chop, for foam that outlives its crest. */
    private static final String[] PREVIOUS = { "u_previous0", "u_previous1" };
    /** Effective open-sea fetch. Enclosed water carries less of it through the exposure map, never more. */
    private static final float FETCH_METRES = 60_000;
    /** A calm swell ~110 m long, from a steady quarter to the wind. */
    private static final float SWELL_METRES = 110, SWELL_TURN = .45f;
    /** How much higher than its fetch would raise it a full gale is drawn; see {@link #storm}. */
    private static final double GALE_GAIN = MAX_WAVE_HEIGHT / 1.6 / significantHeight(1);
    /** Smaller changes of wind leave the spectrum alone. */
    private static final float WIND_EPSILON = .001f;
    /** The same FFT with a slowly evolving, short-wave-damped spectrum and displacement output for lava. */
    private final boolean lava;
    private final int cascades;

    private ShaderProgram spectrum;
    private ShaderProgram butterfly;
    private ShaderProgram finish;
    /** Water only: generates a spectrum for a wind, or freezes a transition by mixing two. */
    private ShaderProgram seed;
    private Mesh quad;
    /** Water only: two independent standard normal deviates per texel, fixed for the life of the simulation. */
    private int gauss;
    /** Initial spectra: the sea blends from one towards another, the third is free. Lava keeps one. */
    private final FrameBuffer[] spectra = new FrameBuffer[3];
    private int from, to;
    private float blend = 1;
    private boolean seeded;
    /** The next wind, when it differs from the one the sea is heading for. */
    private boolean retarget;
    /** Ping-pong targets of the transform, and of the result, whose foam carries over from the previous frame. */
    private final FrameBuffer[] work = new FrameBuffer[2];
    private final FrameBuffer[] result = new FrameBuffer[2];
    private int latest;
    private boolean failed;
    /** Winds as direction XY and strength Z: where the sea comes from, where it is heading, and between them now. */
    private final Vector3 fromWind = new Vector3(), toWind = new Vector3(), target = new Vector3(), eased = new Vector3();
    /** Downwind travel of the eased wind, seconds times its direction, wrapped every {@link #LOOP_SECONDS}. */
    private final Vector2 drift = new Vector2();
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

    /** The spectrum the sea is heading for: h0(k) and conj(h0(-k)) per texel, every cascade side by side. */
    Texture spectrum() {
        return spectra[to] == null ? null : spectra[to].getColorBufferTexture();
    }

    /**
     * The wind the waves currently answer to: unit direction in XY, strength in Z. It eases with the sea, so shaders
     * turn with the waves. Null until the first update.
     */
    Vector3 wind() { return seeded ? eased : null; }

    /** How far the eased wind has carried the surface: seconds along its direction, continuous as the wind turns. */
    Vector2 drift() { return drift; }

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
        if (!supported()) { return; }
        float delta = Float.isNaN(previousTime) ? 0 : Math.clamp(time - previousTime, 0, .25f);
        previousTime = time;
        // Lava's convection is internal. Weather must not start or stop its motion.
        float x = lava ? .8f : wind.x, y = lava ? .6f : wind.y;
        float length = (float) Math.hypot(x, y);
        if (length < .01f) { x = .8f; y = .6f; } else { x /= length; y /= length; }
        target.set(x, y, lava ? .35f : MathUtils.clamp(wind.z, 0, 1));
        if (failed) {
            // The static ripple fallback still rolls its gusts downwind.
            drift.set((drift.x + x * delta) % LOOP_SECONDS, (drift.y + y * delta) % LOOP_SECONDS);
            return;
        }
        retarget = seeded && !target.epsilonEquals(toWind, WIND_EPSILON);
        try {
            if (quad == null) { create(); }
            simulate(lava ? time * .12f : time, delta);
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
        // A live edit of the generator regenerates the spectrum, so the change shows at once.
        if (!lava) {
            seed = GpuShaderManager.program(() -> program("ocean-initial.frag"), next -> { seed = next; seeded = false; });
        }
        quad = new Mesh(true, 4, 0, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        for (int i = 0; i < (lava ? 1 : spectra.length); i++) { spectra[i] = floatTarget(SIZE * cascades); }
        for (int i = 0; i < 2; i++) {
            work[i] = floatTarget(SIZE * cascades);
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
        IntBuffer state = saveState();
        if (lava) {
            spectra[0].getColorBufferTexture().bind(0);
            FloatBuffer buffer = BufferUtils.newFloatBuffer(SIZE * SIZE * 4);
            buffer.put(lavaSpectrum()).flip();
            Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        } else {
            gauss = Gdx.gl.glGenTexture();
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, gauss);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST);
            FloatBuffer buffer = BufferUtils.newFloatBuffer(SIZE * cascades * SIZE * 2);
            buffer.put(deviates()).flip();
            Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RG32F, SIZE * cascades, SIZE, 0, GL30.GL_RG,
                  GL20.GL_FLOAT, buffer);
        }
        // The first frame reads a previous result; start both without foam.
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

    private static FrameBuffer floatTarget(int width) {
        return new GLFrameBuffer.FrameBufferBuilder(width, SIZE)
              .addFloatAttachment(GL30.GL_RGBA32F, GL30.GL_RGBA, GL30.GL_FLOAT, true).build();
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
     * Two standard normal deviates per texel of every cascade. The same draw serves every wind, so a change of wind
     * reshapes the same sea instead of reshuffling it.
     */
    static float[] deviates() {
        int width = SIZE * PATCHES.length;
        float[] data = new float[width * SIZE * 2];
        for (int cascade = 0; cascade < PATCHES.length; cascade++) {
            Random random = new Random(0x6f6365616eL + cascade);
            for (int m = 0; m < SIZE; m++) {
                for (int n = 0; n < SIZE; n++) {
                    int texel = (m * width + cascade * SIZE + n) * 2;
                    data[texel] = (float) random.nextGaussian();
                    data[texel + 1] = (float) random.nextGaussian();
                }
            }
        }
        return data;
    }

    /**
     * The wind sea's JONSWAP peak angular frequency, its alpha, and the swell's alpha, for a strength from 0 to 1:
     * fetch-limited (Hasselmann et al. 1973), never beyond a fully developed sea. Calm air still ripples; a full gale
     * (Beaufort 8) raises a steep, breaking sea with ~70 m peak waves.
     */
    static float[] sea(float strength) {
        float speed = 1.5f + 18.5f * strength;
        double peak = Math.max(22 * Math.cbrt(GRAVITY * GRAVITY / (speed * FETCH_METRES)), .855 * GRAVITY / speed);
        double alpha = Math.max(.076 * Math.pow(speed * speed / (FETCH_METRES * GRAVITY), .22), .0081);
        // The swell is 0.3 m high in calm and 0.45 m in a gale.
        return new float[] { (float) peak, (float) alpha, 5.6e-5f + 7e-5f * strength };
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

    /**
     * Significant height in metres of the sea the spectrum describes before any storm scaling: 4√m0, with JONSWAP's
     * m0 ≈ 1.52 αg²/(5ωp⁴) at a peak enhancement of 3.3, for the wind sea and the swell alike.
     */
    static double significantHeight(float strength) {
        float[] sea = sea(strength);
        double swell = Math.sqrt(GRAVITY * MathUtils.PI2 / SWELL_METRES);
        double m0 = 1.52 * GRAVITY * GRAVITY / 5 * (sea[1] / Math.pow(sea[0], 4) + sea[2] / Math.pow(swell, 4));
        return 4 * Math.sqrt(m0);
    }

    /**
     * Art direction, not physics: how much higher than its fetch would raise it the sea is drawn, so that at full wind
     * its tallest waves reach {@link #MAX_WAVE_HEIGHT} and storm crests fold and foam visibly from a tactical camera.
     * Calm water stays physical.
     */
    static float storm(float strength) {
        return (float) (1 + (GALE_GAIN - 1) * strength * strength);
    }

    /** Heads the sea for {@link #target}: first seeding, or from wherever a transition has got to. */
    private void steer() {
        if (!seeded) {
            generate(to);
            from = to;
            blend = 1;
            fromWind.set(target);
            toWind.set(target);
            eased.set(target);
            seeded = true;
            return;
        }
        if (blend < 1) {
            // Freeze the transition where it is, so the new wind takes over from the sea as it now looks.
            int frozen = spare(from, to);
            seed.bind();
            spectra[from].getColorBufferTexture().bind(1);
            spectra[to].getColorBufferTexture().bind(2);
            seed.setUniformi("u_from", 1);
            seed.setUniformi("u_to", 2);
            seed.setUniformf("u_blend", ease(blend));
            pass(seed, spectra[frozen]);
            // The old target is about to be overwritten: leave no unit sampling it.
            for (int unit = 2; unit > 0; unit--) {
                Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
                Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
            }
            from = frozen;
        } else {
            from = to;
        }
        fromWind.set(eased);
        toWind.set(target);
        generate(to = spare(from, from));
        blend = 0;
    }

    /** Renders the spectrum of {@link #target}'s wind into one of the spectra. */
    private void generate(int into) {
        float[] sea = sea(target.z);
        float wind = MathUtils.atan2(target.y, target.x);
        seed.bind();
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, gauss);
        seed.setUniformi("u_gauss", 0);
        seed.setUniformf("u_blend", -1);
        seed.setUniformi("u_size", SIZE);
        seed.setUniformf("u_patches", PATCHES[0], PATCHES[1], PATCHES[2]);
        seed.setUniformf("u_directions", wind, wind + SWELL_TURN);
        seed.setUniformf("u_sea", sea[0], sea[1], (float) Math.sqrt(GRAVITY * MathUtils.PI2 / SWELL_METRES), sea[2]);
        seed.setUniformf("u_storm", storm(target.z));
        pass(seed, spectra[into]);
    }

    /** An index of the three spectra that is neither a nor b. */
    private static int spare(int a, int b) {
        for (int i = 0; ; i++) { if (i != a && i != b) { return i; } }
    }

    /**
     * Eases out, never in: a transition leaves at full pace and settles gently, so a wind that keeps changing (a dragged
     * slider) keeps the sea moving instead of restarting each step from rest. Every stage uses this one curve, so a
     * frozen transition matches what was on screen.
     */
    private static float ease(float t) { return t * (2 - t); }

    /** The wind between where the sea came from and where it is heading, turning the short way round. */
    private void easeWind(float delta) {
        blend = Math.min(1, blend + delta / TRANSITION_SECONDS);
        float t = ease(blend);
        float start = MathUtils.atan2(fromWind.y, fromWind.x), turn = MathUtils.atan2(toWind.y, toWind.x) - start;
        turn = (float) Math.IEEEremainder(turn, MathUtils.PI2);
        float angle = start + turn * t;
        eased.set(MathUtils.cos(angle), MathUtils.sin(angle), MathUtils.lerp(fromWind.z, toWind.z, t));
        drift.set((drift.x + eased.x * delta) % LOOP_SECONDS, (drift.y + eased.y * delta) % LOOP_SECONDS);
    }

    private void simulate(float time, float delta) {
        IntBuffer state = saveState();
        boolean depthTest = Gdx.gl.glIsEnabled(GL20.GL_DEPTH_TEST), blending = Gdx.gl.glIsEnabled(GL20.GL_BLEND);
        boolean cull = Gdx.gl.glIsEnabled(GL20.GL_CULL_FACE), scissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        if (lava) {
            seeded = true;
        } else {
            if (!seeded || retarget) { steer(); }
            easeWind(delta);
        }
        // The wave spectrum of every cascade at this instant, part-way between two winds while the sea changes.
        spectrum.bind();
        spectra[from].getColorBufferTexture().bind(1);
        spectra[to].getColorBufferTexture().bind(0);
        spectrum.setUniformi("u_to", 0);
        spectrum.setUniformi("u_from", 1);
        spectrum.setUniformf("u_blend", ease(blend));
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
            for (int cascade = 0; cascade < PREVIOUS.length; cascade++) {
                previous.get(cascade).bind(1 + cascade);
                finish.setUniformi(PREVIOUS[cascade], 1 + cascade);
            }
            finish.setUniformf("u_texels", PATCHES[0] / SIZE, PATCHES[1] / SIZE, PATCHES[2] / SIZE);
            // Horizontal displacement sharpens crests and broadens troughs, the more so the stronger the wind; a gale
            // brings the steepest to the point of folding over.
            finish.setUniformf("u_choppiness", 1 + .8f * eased.z, 1.1f, .6f);
            finish.setUniformf("u_delta", delta);
            // Foam drifts downwind at about 3 % of the wind's speed and spreads along it into streaks.
            float travel = (.05f + .55f * eased.z) * delta;
            finish.setUniformf("u_drift", eased.x * travel, eased.y * travel);
            finish.setUniformf("u_downwind", eased.x, eased.y);
            finish.setUniformf("u_patches", PATCHES[0], PATCHES[1], PATCHES[2]);
        }
        pass(finish, result[next]);
        for (int unit = 1; unit <= PREVIOUS.length; unit++) {
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
        enable(GL20.GL_BLEND, blending);
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
        for (ShaderProgram program : new ShaderProgram[] { spectrum, butterfly, finish, seed }) {
            if (program != null) { GpuShaderManager.dispose(program); }
        }
        spectrum = butterfly = finish = seed = null;
        if (quad != null) { quad.dispose(); }
        quad = null;
        for (int i = 0; i < spectra.length; i++) {
            if (spectra[i] != null) { spectra[i].dispose(); }
            spectra[i] = null;
        }
        for (int i = 0; i < 2; i++) {
            if (work[i] != null) { work[i].dispose(); }
            if (result[i] != null) { result[i].dispose(); }
            work[i] = result[i] = null;
        }
        if (gauss != 0) { Gdx.gl.glDeleteTexture(gauss); }
        gauss = 0;
        // A recreated simulation seeds its spectrum again on its first update.
        seeded = false;
        from = to = 0;
        blend = 1;
        drift.setZero();
        previousTime = Float.NaN;
    }
}
