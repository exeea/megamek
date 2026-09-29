/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
            assertEquals(flowing, program.getUniformLocation("u_magmaField") >= 0);
        }
    }

    private static void checkOceans(GpuShaderManager manager) {
        var water = new GpuOcean();
        var lava = new GpuOcean(true);
        var wind = new Vector3(.8f, .6f, .7f);
        try {
            water.update(2, wind);
            lava.update(2, wind);
            assertNotNull(water.texture(), "Run water FFT without falling back");
            assertNotNull(lava.texture(), "Run lava FFT without falling back");
            var field = GpuOcean.class.getDeclaredField("finish");
            field.setAccessible(true);
            for (String file : List.of("ocean-water-finish.frag", "ocean-lava-finish.frag", "ocean-finish.glsl")) {
                var waterBefore = (ShaderProgram) field.get(water);
                var lavaBefore = (ShaderProgram) field.get(lava);
                assertTrue(waterBefore.getUniformLocation("u_previous") >= 0, "Water needs foam history");
                assertEquals(-1, lavaBefore.getUniformLocation("u_previous"), "Lava has no foam history sampler");
                assertEquals(-1, lavaBefore.getUniformLocation("u_lava"), "No runtime mode branch");
                var changed = manager.apply(Map.of(file, GpuShaderSource.readDisk(file) + "\n// reload finish\n"));
                assertTrue(changed.success(), changed.message());
                assertEquals(file.endsWith(".glsl") ? 2 : 1, changed.updated());
                assertEquals(!file.equals("ocean-lava-finish.frag"), waterBefore != field.get(water));
                assertEquals(!file.equals("ocean-water-finish.frag"), lavaBefore != field.get(lava));
                int programs = ShaderProgram.getNumManagedShaderPrograms();
                assertFalse(manager.apply(Map.of(file, "unfinished finish edit")).success());
                assertEquals(programs, ShaderProgram.getNumManagedShaderPrograms(), "Rejected replacements do not leak");
                water.update(3, wind);
                lava.update(3, wind);
                assertNotNull(water.texture());
                assertNotNull(lava.texture());
                assertTrue(manager.apply(Map.of()).success());
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally { water.dispose(); lava.dispose(); }
        assertEquals(0, ShaderProgram.getNumManagedShaderPrograms());
    }
}
