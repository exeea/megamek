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
    @Test
    void heightNormalsAndFoamComeFromTheSameFiniteMovingSurface() {
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
                    double calm = heightEnergy(read(ocean.displacement()));
                    for (int frame = 1; frame <= 180; frame++) { ocean.update(frame / 60f, new Vector3(.8f, .6f, 1)); }
                    float[] shape = read(ocean.displacement()), normals = read(ocean.texture());
                    assertTrue(heightEnergy(shape) > calm * 20, "Strong wind creates larger waves than calm water");
                    int foam = 0;
                    for (int i = 0; i < shape.length; i++) {
                        assertTrue(Float.isFinite(shape[i]) && Float.isFinite(normals[i]));
                        if (i % 4 == 2) { assertEquals(shape[i], normals[i], .001, "Crest light must follow the displaced height"); }
                        if (i % 4 == 3 && normals[i] > .5) { foam++; }
                    }
                    assertTrue(foam < GpuOcean.SIZE * GpuOcean.SIZE * .25, "Wind foam leaves open water between crests");
                    ocean.update(3.25f, new Vector3(.8f, .6f, 1));
                    float[] later = read(ocean.displacement());
                    double movement = 0;
                    for (int i = 2; i < shape.length; i += 4) { movement += Math.abs(later[i] - shape[i]); }
                    assertTrue(movement > 1, "The surface evolves; it cannot be a static normal texture");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { ocean.dispose(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("FFT water outputs", failure.get()); }
    }

    private static float[] read(Texture texture) {
        float[] data = new float[GpuOcean.SIZE * GpuOcean.SIZE * 4];
        var buffer = BufferUtils.newFloatBuffer(data.length);
        texture.bind(0);
        GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        buffer.get(data);
        return data;
    }

    private static double heightEnergy(float[] shape) {
        double energy = 0;
        for (int i = 2; i < shape.length; i += 4) { energy += shape[i] * shape[i]; }
        return energy / (GpuOcean.SIZE * GpuOcean.SIZE);
    }
}
