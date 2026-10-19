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
import com.badlogic.gdx.utils.BufferUtils;
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
        try (var outputFrame = new GpuSceneTestOutput()) {
            byte[][] original = frames(outputFrame, batch, camera);
            for (int kind = 0; kind < files.size(); kind++) {
                String file = files.get(kind);
                String source = GpuShaderSource.readDisk(file);
                var result = manager.apply(Map.of(file, source.replace("return vec4(", "return 0.5 * vec4(")));
                assertTrue(result.success(), result.message());
                assertEquals(1, result.updated(), "All appearance files compose into the existing particle batch");
                byte[][] changed = frames(outputFrame, batch, camera);
                for (int other = 0; other < files.size(); other++) {
                    if (other == kind) {
                        assertFalse(Arrays.equals(original[other], changed[other]), file + " edits must reach its pixels");
                    } else { assertArrayEquals(original[other], changed[other], "Other appearances remain unchanged"); }
                }
                assertFalse(manager.apply(Map.of(file, "unfinished particle edit")).success());
                byte[][] retained = frames(outputFrame, batch, camera);
                for (int other = 0; other < files.size(); other++) { assertArrayEquals(changed[other], retained[other]); }
                assertTrue(manager.apply(Map.of()).success());
            }
            byte[][] restored = frames(outputFrame, batch, camera);
            for (int kind = 0; kind < files.size(); kind++) { assertArrayEquals(original[kind], restored[kind]); }
            batch.setSmokeLight(Color.BLACK);
            byte[][] dark = frames(outputFrame, batch, camera);
            assertFalse(Arrays.equals(original[0], dark[0]), "Smoke responds to scene light");
            assertArrayEquals(original[1], dark[1], "Jets remain emissive");
            assertArrayEquals(original[2], dark[2], "Fire remains emissive");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { batch.dispose(); }
        checkLinearBlending(camera);
        assertEquals(0, ShaderProgram.getNumManagedShaderPrograms());
    }

    /** A real scene target must mix decoded colours and retain additive radiance above display white. */
    private static void checkLinearBlending(OrthographicCamera camera) {
        var target = GpuHeatGlow.buffer(1, 1);
        var batch = new GpuEffectBatch(2);
        var sparks = new GpuEffectBatch(1, "projectiles");
        var pixel = BufferUtils.newFloatBuffer(4);
        try {
            target.begin();
            ScreenUtils.clear(.1f * GpuSceneRadiance.SCALE, .1f * GpuSceneRadiance.SCALE,
                  .1f * GpuSceneRadiance.SCALE, 0, false);
            batch.begin();
            batch.billboard(camera, Vector3.Zero, 12, 0, .5f);
            batch.render(camera, 1);
            Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
            double smokeRed = .5 * (Math.pow((.39 + .055) / 1.055, 2.4) + Math.pow((.72 + .055) / 1.055, 2.4));
            assertEquals(.5 * smokeRed + .05, pixel.get(0) / GpuSceneRadiance.SCALE, .002,
                  "Smoke palette interpolation and alpha blending must both use linear radiance");
            assertEquals(.5, pixel.get(3), .002, "A half-opaque puff covers half of the clear sky");
            batch.render(camera, 1);
            pixel.clear();
            Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
            assertEquals(.75, pixel.get(3), .002, "Two overlapping half-opaque puffs cover three quarters");

            ScreenUtils.clear(0, 0, 0, .375f, false);
            batch.begin();
            batch.billboard(camera, Vector3.Zero, 12, 1, 1);
            batch.billboard(camera, Vector3.Zero, 12, 1, 1);
            batch.render(camera, 0);
            pixel.clear();
            Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
            assertEquals(2 * Math.pow((.85 + .055) / 1.055, 2.4), pixel.get(0) / GpuSceneRadiance.SCALE, .003,
                  "Overlapping emissive jets must add in linear space without clipping at one");
            assertEquals(.375, pixel.get(3), .002, "Additive jets preserve the existing scene coverage");
            ScreenUtils.clear(0, 0, 0, 0, false);
            batch.render(camera, 0);
            pixel.clear();
            Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
            assertEquals(0, pixel.get(3), .002, "Additive jets over uncovered sky must not create coverage");

            ScreenUtils.clear(0, 0, 0, 0, false);
            sparks.begin();
            sparks.quad(new Vector3(0, -12, 0), new Vector3(12, 0, 0), new Vector3(0, 24, 0), 0, 7, 1);
            sparks.render(camera, 0);
            pixel.clear();
            Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
            assertEquals(4 * .625, pixel.get(0) / GpuSceneRadiance.SCALE, .004,
                  "Spark strength is linear radiance multiplied by coverage, never an encoded HDR colour");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            target.end();
            batch.dispose();
            sparks.dispose();
            target.dispose();
        }
    }

    private static byte[][] frames(GpuSceneTestOutput outputFrame, GpuEffectBatch batch, OrthographicCamera camera) {
        byte[][] result = new byte[3][];
        for (int kind = 0; kind < result.length; kind++) {
            outputFrame.begin(0, 0, 0, 1);
            batch.begin();
            batch.billboard(camera, Vector3.Zero, 12, kind == 2 ? 2.25f : kind, .75f);
            batch.render(camera, kind == 0 ? 1 : 0);
            outputFrame.display();
            result[kind] = ScreenUtils.getFrameBufferPixels(0, 0,
                  Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
        }
        return result;
    }
}
