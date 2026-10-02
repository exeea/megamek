/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

@Tag("on-demand")
class GpuWaterWavesSmokeTest {
    private static final int SIZE = GpuOcean.SIZE, TEXELS = SIZE * SIZE;
    private static final float FRAME = 1 / 60f;
    /** Half a minute: whitecaps settle on their cover within about fifteen seconds of a change of wind. */
    private static final int FOAM_FRAMES = 1800;

    @Test
    void cascadesDescribeOneMovingSeaWithSharpCrestsAndFoamOnThem() {
        run(ocean -> {
            float time = run(ocean, 0, new Vector3(.8f, .6f, 0), 1);
            assertNotNull(ocean.displacement(), "Real displacement must compile and attach, not silently fall back");
            double calm = variance(read(ocean.displacement()), 2);
            time = settle(ocean, time, new Vector3(.8f, .6f, .5f));
            double breeze = variance(read(ocean.displacement()), 2);
            // Whitecaps take longer than the waves to settle on the cover the wind calls for.
            time = run(ocean, time, new Vector3(.8f, .6f, .5f), FOAM_FRAMES);
            assertEquals(GpuOcean.foamCover(.5f), whitecaps(ocean), .015, "A moderate wind whitens its share of the sea");
            time = settle(ocean, time, new Vector3(.8f, .6f, 1));
            time = run(ocean, time, new Vector3(.8f, .6f, 1), FOAM_FRAMES);
            assertEquals(GpuOcean.FOAM_COVERAGE, whitecaps(ocean), .03, "A gale keeps the configured whitecap cover");
            float[] shape = read(ocean.displacement()), swell = read(ocean.waves(0));
            double gale = variance(shape, 2);
            // Heights come out in metres: four standard deviations of the longest cascade.
            assertTrue(4 * Math.sqrt(calm) > .15 && 4 * Math.sqrt(calm) < .5, "Calm swell " + 4 * Math.sqrt(calm));
            assertTrue(4 * Math.sqrt(breeze) > 1.2 && 4 * Math.sqrt(breeze) < 2.6, "Breeze " + 4 * Math.sqrt(breeze));
            // A full gale is drawn to the configured height: its tallest waves are about 1.6 significant heights.
            double target = GpuOcean.MAX_WAVE_HEIGHT / 1.6;
            assertEquals(target, 4 * Math.sqrt(gale), target * .15, "Gale significant height");
            double crests = 0, troughs = 0;
            int crestCount = 0, troughCount = 0;
            for (int i = 0; i < TEXELS; i++) {
                for (int channel = 0; channel < 4; channel++) {
                    assertTrue(Float.isFinite(shape[i * 4 + channel]) && Float.isFinite(swell[i * 4 + channel]));
                }
                assertEquals(shape[i * 4 + 2], swell[i * 4 + 2], .001, "Crest light must follow the displaced height");
                float height = shape[i * 4 + 2], compression = shape[i * 4 + 3];
                if (height > 0) { crests += compression; crestCount++; } else { troughs += compression; troughCount++; }
            }
            // Choppy displacement gathers the surface into crests and spreads it through troughs.
            assertTrue(crests / crestCount > troughs / troughCount + .02, "Crests, not troughs, are sharp");
            for (int cascade = 1; cascade < GpuOcean.PATCHES.length; cascade++) {
                assertTrue(variance(read(ocean.waves(cascade)), 0) > 0, "Every cascade carries slopes");
            }
            assertConjugates(read(ocean.spectrum()));
            float[] before = read(ocean.displacement());
            time = run(ocean, time, new Vector3(.8f, .6f, 1), 30);
            double movement = 0;
            float[] later = read(ocean.displacement());
            for (int i = 2; i < later.length; i += 4) { movement += Math.abs(later[i] - before[i]); }
            assertTrue(movement > 1, "The surface evolves; it cannot be a static normal texture");
        });
    }

    @Test
    void aChangeOfWindReshapesTheSeaWithoutAJumpAndItsWavesTravelDownwind() {
        run(ocean -> {
            var east = new Vector3(1, 0, 1);
            float time = settle(ocean, run(ocean, 0, east, 1), east);
            // Travelling waves obey dh/dt = -c dh/dx: with wind along +x, a rising surface lies where it slopes down
            // toward +x, so the product sums negative. Waves running upwind would sum positive.
            float[] before = read(ocean.displacement());
            time = run(ocean, time, east, 6);
            float[] after = read(ocean.displacement());
            double travel = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    float ahead = before[(y * SIZE + (x + 1) % SIZE) * 4 + 2], behind = before[(y * SIZE + (x + SIZE - 1) % SIZE) * 4 + 2];
                    travel += (after[(y * SIZE + x) * 4 + 2] - before[(y * SIZE + x) * 4 + 2]) * (ahead - behind);
                }
            }
            assertTrue(travel < 0, "Waves travel downwind, with the foam: " + travel);
            // One ordinary frame of motion, then the same frame with the wind turned to the north and dropped.
            double settled = change(ocean, time, east);
            time += FRAME;
            var north = new Vector3(0, 1, .3f);
            double turned = change(ocean, time, north);
            time += FRAME;
            assertTrue(turned < settled * 1.5, "A new wind must not replace the sea at once: " + turned + " vs " + settled);
            assertTrue(Math.abs(ocean.wind().x - 1) < .01 && Math.abs(ocean.wind().z - 1) < .01,
                  "The water still answers to the old wind the moment it changes");
            // Half-way, the wind changes again: the sea carries on from where it is.
            time = run(ocean, time, north, (int) (GpuOcean.TRANSITION_SECONDS / 2 / FRAME));
            assertTrue(ocean.wind().y > .2 && ocean.wind().x > .2, "Half-way, the wind has turned part of the way");
            double again = change(ocean, time, new Vector3(-1, 0, .6f));
            time += FRAME;
            assertTrue(again < settled * 1.5, "Changing course mid-transition must not jump either: " + again);
            time = settle(ocean, time, north);
            assertTrue(ocean.wind().epsilonEquals(0, 1, .3f, .001f), "The water ends up on the new wind: " + ocean.wind());
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        });
    }

    @Test
    void gravityChangesReseedMovingWavesAndZeroGravityCanRecover() {
        run(ocean -> {
            var wind = new Vector3(.8f, .6f, .7f);
            ocean.update(1, wind, 9.81f);
            assertNotNull(ocean.displacement());
            float[] normal = read(ocean.spectrum());
            for (float gravity : new float[] { .0981f, 4.905f, 19.62f, 98.1f }) {
                ocean.update(1, wind, gravity);
                assertNotNull(ocean.displacement(), "Changing gravity must not disable the simulation");
                assertFalse(Arrays.equals(normal, read(ocean.spectrum())), "Gravity must regenerate the spectrum");
                float[] before = read(ocean.displacement());
                ocean.update(1.1f, wind, gravity);
                float[] after = read(ocean.displacement());
                for (float value : after) { assertTrue(Float.isFinite(value), "Gravity must keep waves finite"); }
                assertFalse(Arrays.equals(before, after), "Waves must keep moving at each positive gravity");
            }
            ocean.update(2, wind, 0);
            assertNull(ocean.displacement(), "Zero gravity releases the water simulation");
            assertNull(ocean.spectrum());
            ocean.update(2, wind, 9.81f);
            assertNotNull(ocean.displacement(), "Water returns when gravity is restored");
            assertArrayEquals(normal, read(ocean.spectrum()), "Returning to 1 g restores the original sea");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        });
    }

    private interface Check { void run(GpuOcean ocean); }

    private static void run(Check check) {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var ocean = new GpuOcean();
                try { check.run(ocean); } catch (Throwable error) { failure.set(error); }
                finally { ocean.dispose(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("FFT water outputs", failure.get()); }
    }

    /** Frames at 60 Hz with a steady wind; returns the clock after them. */
    private static float run(GpuOcean ocean, float time, Vector3 wind, int frames) {
        for (int frame = 0; frame < frames; frame++) { ocean.update(time += FRAME, wind, 9.81f); }
        return time;
    }

    /** Long enough for any change of wind to have finished reshaping the sea. */
    private static float settle(GpuOcean ocean, float time, Vector3 wind) {
        return run(ocean, time, wind, (int) ((GpuOcean.TRANSITION_SECONDS + 1) / FRAME));
    }

    /** Share of the open sea showing whitecaps up close, as the finish pass reckons it for the ripple result. */
    private static double whitecaps(GpuOcean ocean) {
        return mean(read(ocean.waves(2)), 3);
    }

    /** Mean absolute change of height over one frame, under the given wind. */
    private static double change(GpuOcean ocean, float time, Vector3 wind) {
        float[] before = read(ocean.displacement());
        ocean.update(time + FRAME, wind, 9.81f);
        float[] after = read(ocean.displacement());
        double sum = 0;
        for (int i = 2; i < after.length; i += 4) { sum += Math.abs(after[i] - before[i]); }
        return sum / TEXELS;
    }

    private static void assertConjugates(float[] spectrum) {
        int width = SIZE * GpuOcean.PATCHES.length;
        for (int cascade = 0; cascade < GpuOcean.PATCHES.length; cascade++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int a = (y * width + cascade * SIZE + x) * 4;
                    int b = (((SIZE - y) % SIZE) * width + cascade * SIZE + (SIZE - x) % SIZE) * 4;
                    assertEquals(spectrum[b], spectrum[a + 2], 1e-9, "Conjugate partners keep the transform real");
                    assertEquals(-spectrum[b + 1], spectrum[a + 3], 1e-9);
                }
            }
        }
    }

    private static float[] read(Texture texture) {
        float[] data = new float[texture.getWidth() * texture.getHeight() * 4];
        var buffer = BufferUtils.newFloatBuffer(data.length);
        texture.bind(0);
        GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        buffer.get(data);
        return data;
    }

    private static double mean(float[] data, int channel) {
        double sum = 0;
        for (int i = channel; i < data.length; i += 4) { sum += data[i]; }
        return sum / (data.length / 4);
    }

    private static double variance(float[] data, int channel) {
        double sum = 0, squares = 0;
        for (int i = channel; i < data.length; i += 4) { sum += data[i]; squares += data[i] * data[i]; }
        int count = data.length / 4;
        double mean = sum / count;
        return squares / count - mean * mean;
    }
}
