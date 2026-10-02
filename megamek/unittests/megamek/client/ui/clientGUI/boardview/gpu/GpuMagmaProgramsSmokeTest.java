/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

/** Solid/flowing material identity and independent water/lava FFT finishing survive live source replacement. */
@Tag("on-demand")
class GpuMagmaProgramsSmokeTest {
    @Test
    void materialAndSimulationModesRemainDistinctAfterReload() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(() -> { checkMaterials(manager); checkOceans(manager); });
                } catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void checkMaterials(GpuShaderManager manager) {
        var terrain = new GpuTerrain();
        var assets = new GpuAssets();
        long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates;
        var model = new ModelBuilder().createBox(1, 1, 1, new Material(), attributes);
        try {
            var environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(.4f, .4f, .4f, 1));
            environment.add(new DirectionalLight().set(1, 1, 1, -.4f, -.3f, -1));
            var parts = new ArrayList<Renderable>();
            for (int mode : List.of(GpuMagmaShader.CRUST, GpuMagmaShader.BANK, GpuMagmaShader.LAVA, GpuMagmaShader.FALL)) {
                var part = new ModelInstance(model).getRenderable(new Renderable());
                part.material = GpuMagmaShader.material(assets, mode, null);
                assertNotNull(part.material, "Use the shipped volcanic maps");
                part.environment = environment;
                parts.add(part);
            }
            var field = GpuTerrain.class.getDeclaredField("batch");
            field.setAccessible(true);
            var provider = ((ModelBatch) field.get(terrain)).getShaderProvider();
            checkCache(provider, parts);
            for (String file : List.of("magma-solid.glsl", "magma-flow.glsl", "magma-lighting.glsl", "terrain-magma.glsl")) {
                var changed = manager.apply(Map.of(file, GpuShaderSource.readDisk(file) + "\n// reload magma\n"));
                assertTrue(changed.success(), changed.message());
                assertEquals(2, changed.updated(), "The terrain provider rebuilds its two live magma programs");
                checkCache(provider, parts);
                var original = GpuShaderProvider.unwrap(provider.getShader(parts.getFirst()));
                assertFalse(manager.apply(Map.of(file, "unfinished magma edit")).success());
                assertSame(original, GpuShaderProvider.unwrap(provider.getShader(parts.getFirst())));
                assertTrue(manager.apply(Map.of()).success());
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally { terrain.dispose(); model.dispose(); assets.dispose(); }
        assertEquals(0, ShaderProgram.getNumManagedShaderPrograms());
    }

    private static void checkCache(ShaderProvider provider, List<Renderable> parts) {
        for (var part : parts) {
            boolean flowing = part.material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE).flowing();
            var shader = provider.getShader(part);
            for (var other : parts) {
                assertEquals(flowing == other.material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE).flowing(),
                      shader.canRender(other), "Share crust/bank and pool/fall, but reject the opposite program");
            }
            var copy = new Renderable().set(part);
            copy.material = new Material(part.material);
            assertSame(shader, provider.getShader(copy));
            var program = ((BaseShader) GpuShaderProvider.unwrap(shader)).program;
            assertEquals(flowing, program.getUniformLocation("u_magmaTime") >= 0);
            assertEquals(flowing, program.getUniformLocation("u_magmaOcean") >= 0);
            assertTrue(program.getUniformLocation("u_magmaField") >= 0,
                  "Lava reads its currents and banks; a cooled bank reads how close the melt is");
        }
    }

    private static void checkOceans(GpuShaderManager manager) {
        var water = new GpuOcean();
        var lava = new GpuOcean(true);
        var wind = new Vector3(.8f, .6f, .7f);
        try {
            water.update(2, wind, 9.81f);
            lava.update(2, wind, 9.81f);
            assertNotNull(water.texture(), "Run water FFT without falling back");
            assertNotNull(lava.texture(), "Run lava FFT without falling back");
            var field = GpuOcean.class.getDeclaredField("finish");
            field.setAccessible(true);
            for (String file : List.of("ocean-water-finish.frag", "ocean-lava-finish.frag", "ocean-finish.glsl")) {
                var waterBefore = (ShaderProgram) field.get(water);
                var lavaBefore = (ShaderProgram) field.get(lava);
                assertTrue(waterBefore.getUniformLocation("u_previous0") >= 0, "Water needs foam history");
                assertEquals(-1, lavaBefore.getUniformLocation("u_previous0"), "Lava has no foam history sampler");
                assertEquals(-1, lavaBefore.getUniformLocation("u_lava"), "No runtime mode branch");
                var changed = manager.apply(Map.of(file, GpuShaderSource.readDisk(file) + "\n// reload finish\n"));
                assertTrue(changed.success(), changed.message());
                assertEquals(file.endsWith(".glsl") ? 2 : 1, changed.updated());
                assertEquals(!file.equals("ocean-lava-finish.frag"), waterBefore != field.get(water));
                assertEquals(!file.equals("ocean-water-finish.frag"), lavaBefore != field.get(lava));
                int programs = ShaderProgram.getNumManagedShaderPrograms();
                assertFalse(manager.apply(Map.of(file, "unfinished finish edit")).success());
                assertEquals(programs, ShaderProgram.getNumManagedShaderPrograms(), "Rejected replacements do not leak");
                water.update(3, wind, 9.81f);
                lava.update(3, wind, 9.81f);
                assertNotNull(water.texture());
                assertNotNull(lava.texture());
                assertTrue(manager.apply(Map.of()).success());
            }
            checkGravity(lava, wind);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally { water.dispose(); lava.dispose(); }
        assertEquals(0, ShaderProgram.getNumManagedShaderPrograms());
    }

    private static void checkGravity(GpuOcean lava, Vector3 wind) {
        double previousMotion = 0;
        for (float gravity : new float[] { .25f, 1, 4 }) {
            float acceleration = gravity * BoardAtmosphere.STANDARD_GRAVITY;
            lava.update(0, wind, acceleration);
            float[] before = read(lava.texture());
            lava.update(.2f, wind, acceleration);
            float[] after = read(lava.texture());
            double motion = 0;
            for (int i = 0; i < after.length; i++) {
                assertTrue(Float.isFinite(after[i]), "Gravity must keep the molten surface finite");
                motion += Math.abs(after[i] - before[i]);
            }
            assertTrue(motion > previousMotion * 1.8,
                  "A fourfold gravity increase must speed up lava's waves: " + motion + " vs " + previousMotion);
            previousMotion = motion;
        }
        float[] moving = read(lava.texture());
        lava.update(.2f, new Vector3(-1, 0, 0), 4 * BoardAtmosphere.STANDARD_GRAVITY);
        assertArrayEquals(moving, read(lava.texture()), "Gravity, not wind, drives lava's waves");
        lava.update(.2f, wind, 0);
        assertNull(lava.texture(), "Zero gravity releases lava's wave targets just like water's");
        assertNull(lava.spectrum());
        lava.update(.2f, wind, 4 * BoardAtmosphere.STANDARD_GRAVITY);
        assertNotNull(lava.texture(), "Positive gravity recreates lava's waves");
        assertArrayEquals(moving, read(lava.texture()), "Restoring gravity and time restores the same surface");
    }

    private static float[] read(Texture texture) {
        texture.bind(0);
        var pixels = BufferUtils.newFloatBuffer(texture.getWidth() * texture.getHeight() * 4);
        GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
        float[] result = new float[pixels.capacity()];
        pixels.get(result);
        return result;
    }
}
