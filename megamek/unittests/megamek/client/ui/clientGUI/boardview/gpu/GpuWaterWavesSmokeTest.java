/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

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
    private static final int TEXELS = GpuOcean.SIZE * GpuOcean.SIZE;

    @Test
    void cascadesDescribeOneMovingSeaWithSharpCrestsAndFoamOnThem() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var ocean = new GpuOcean();
                try {
                    ocean.update(0, new Vector3(.8f, .6f, 0));
                    assertNotNull(ocean.displacement(), "Real displacement must compile and attach, not silently fall back");
                    double calm = variance(read(ocean.displacement()), 2);
                    for (int frame = 1; frame <= 180; frame++) { ocean.update(frame / 60f, new Vector3(.8f, .6f, 1)); }
                    float[] shape = read(ocean.displacement()), swell = read(ocean.waves(0));
                    double gale = variance(shape, 2);
                    assertTrue(gale > calm * 20, "A gale raises far higher waves than calm water: " + calm + " " + gale);
                    // Heights come out in metres: four standard deviations of the longest cascade, about 4.5 m.
                    assertTrue(4 * Math.sqrt(gale) > 3 && 4 * Math.sqrt(gale) < 6, "Gale height " + 4 * Math.sqrt(gale));
                    double crests = 0, troughs = 0;
                    int crestCount = 0, troughCount = 0, foam = 0;
                    for (int i = 0; i < TEXELS; i++) {
                        for (int channel = 0; channel < 4; channel++) {
                            assertTrue(Float.isFinite(shape[i * 4 + channel]) && Float.isFinite(swell[i * 4 + channel]));
                        }
                        assertEquals(shape[i * 4 + 2], swell[i * 4 + 2], .001, "Crest light must follow the displaced height");
                        float height = shape[i * 4 + 2], compression = shape[i * 4 + 3];
                        if (height > 0) { crests += compression; crestCount++; } else { troughs += compression; troughCount++; }
                        if (swell[i * 4 + 3] > .5) { foam++; }
                    }
                    // Choppy displacement gathers the surface into crests and spreads it through troughs.
                    assertTrue(crests / crestCount > troughs / troughCount + .02, "Crests, not troughs, are sharp");
                    assertTrue(foam > 0 && foam < TEXELS * .25, "Wind foam leaves open water between crests: " + foam);
                    for (int cascade = 1; cascade < GpuOcean.PATCHES.length; cascade++) {
                        assertTrue(variance(read(ocean.waves(cascade)), 0) > 0, "Every cascade carries slopes");
                    }
                    ocean.update(3.25f, new Vector3(.8f, .6f, 1));
                    float[] later = read(ocean.displacement());
                    double movement = 0;
                    for (int i = 2; i < shape.length; i += 4) { movement += Math.abs(later[i] - shape[i]); }
                    assertTrue(movement > 1, "The surface evolves; it cannot be a static normal texture");
                    // Travelling waves obey dh/dt = -c dh/dx: with wind along +x, a rising surface lies where it
                    // slopes down toward +x, so the product sums negative. Waves running upwind would sum positive.
                    ocean.update(10, new Vector3(1, 0, 1));
                    float[] before = read(ocean.displacement());
                    ocean.update(10.1f, new Vector3(1, 0, 1));
                    float[] after = read(ocean.displacement());
                    double travel = 0;
                    int size = GpuOcean.SIZE;
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < size; x++) {
                            float east = before[(y * size + (x + 1) % size) * 4 + 2];
                            float west = before[(y * size + (x + size - 1) % size) * 4 + 2];
                            travel += (after[(y * size + x) * 4 + 2] - before[(y * size + x) * 4 + 2]) * (east - west);
                        }
                    }
                    assertTrue(travel < 0, "Waves travel downwind, with the foam: " + travel);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { ocean.dispose(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("FFT water outputs", failure.get()); }
    }

    private static float[] read(Texture texture) {
        float[] data = new float[TEXELS * 4];
        var buffer = BufferUtils.newFloatBuffer(data.length);
        texture.bind(0);
        GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        buffer.get(data);
        return data;
    }

    private static double variance(float[] data, int channel) {
        double sum = 0, squares = 0;
        for (int i = channel; i < data.length; i += 4) { sum += data[i]; squares += data[i] * data[i]; }
        double mean = sum / TEXELS;
        return squares / TEXELS - mean * mean;
    }
}
