/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Appearance edits preserve the other particle kinds and their lighting/blending behavior. */
@Tag("on-demand")
class GpuParticleShaderSmokeTest {
    @Test
    void separateAppearancesReloadThroughTheSharedParticleBatch() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(256, 256);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkParticles(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void checkParticles(GpuShaderManager manager) {
        var camera = new OrthographicCamera(40, 40);
        camera.position.set(0, 0, 100);
        camera.lookAt(Vector3.Zero);
        camera.near = 1;
        camera.far = 200;
        camera.update();
        var batch = new GpuEffectBatch(1);
        var files = List.of("particles-smoke.glsl", "particles-jet.glsl", "particles-fire.glsl");
        try {
            byte[][] original = frames(batch, camera);
            for (int kind = 0; kind < files.size(); kind++) {
                String file = files.get(kind);
                String source = GpuShaderSource.readDisk(file);
                var result = manager.apply(Map.of(file, source.replace("return vec4(", "return 0.5 * vec4(")));
                assertTrue(result.success(), result.message());
                assertEquals(1, result.updated(), "All appearance files compose into the existing particle batch");
                byte[][] changed = frames(batch, camera);
                for (int other = 0; other < files.size(); other++) {
                    if (other == kind) {
                        assertFalse(Arrays.equals(original[other], changed[other]), file + " edits must reach its pixels");
                    } else { assertArrayEquals(original[other], changed[other], "Other appearances remain unchanged"); }
                }
                assertFalse(manager.apply(Map.of(file, "unfinished particle edit")).success());
                byte[][] retained = frames(batch, camera);
                for (int other = 0; other < files.size(); other++) { assertArrayEquals(changed[other], retained[other]); }
                assertTrue(manager.apply(Map.of()).success());
            }
            byte[][] restored = frames(batch, camera);
            for (int kind = 0; kind < files.size(); kind++) { assertArrayEquals(original[kind], restored[kind]); }
            batch.setSmokeLight(Color.BLACK);
            byte[][] dark = frames(batch, camera);
            assertFalse(Arrays.equals(original[0], dark[0]), "Smoke responds to scene light");
            assertArrayEquals(original[1], dark[1], "Jets remain emissive");
            assertArrayEquals(original[2], dark[2], "Fire remains emissive");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { batch.dispose(); }
        assertEquals(0, ShaderProgram.getNumManagedShaderPrograms());
    }

    private static byte[][] frames(GpuEffectBatch batch, OrthographicCamera camera) {
        byte[][] result = new byte[3][];
        for (int kind = 0; kind < result.length; kind++) {
            ScreenUtils.clear(0, 0, 0, 1, true);
            batch.begin();
            batch.billboard(camera, Vector3.Zero, 12, kind == 2 ? 2.25f : kind, .75f);
            batch.render(camera, kind == 0 ? 1 : 0);
            result[kind] = ScreenUtils.getFrameBufferPixels(0, 0,
                  Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
        }
        return result;
    }
}
