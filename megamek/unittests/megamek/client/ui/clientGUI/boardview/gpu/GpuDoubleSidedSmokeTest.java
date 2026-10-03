/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Imported thin surfaces remain visible and correctly lit from either side with normal back-face culling. */
@Tag("on-demand")
class GpuDoubleSidedSmokeTest {
    @TempDir
    Path directory;

    @Test
    void rendersAndLightsBothSidesWithoutChangingSingleSidedMaterials() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(128, 128);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                ModelBatch batch = new ModelBatch(GpuUnitShader.provider());
                try {
                    var fixture = new RigidGlbTest();
                    fixture.directory = directory;
                    for (boolean doubleSided : new boolean[] { false, true }) {
                        Model model = new Model(RigidGlb.load(fixture.sided(doubleSided)));
                        try {
                            var instance = new ModelInstance(model);
                            int front = green(batch, instance, 1, 1);
                            int back = green(batch, instance, -1, -1);
                            assertTrue(front > 180, "The original face must remain lit: " + front);
                            if (doubleSided) {
                                assertTrue(back > 180, "The reverse face must be visible and lit by a light behind it: " + back);
                                assertTrue(green(batch, instance, -1, 1) < back - 60,
                                      "The reverse side must use its own normal, not the front side's lighting");
                            } else {
                                assertEquals(0, back, "Single-sided back faces must still be culled");
                            }
                        } finally { model.dispose(); }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    batch.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Double-sided GLB rendering", failure.get()); }
    }

    private static int green(ModelBatch batch, ModelInstance instance, int viewSide, int lightSide) {
        // Fixture triangle is at Z=2, spanning X=1..2 and Y=-3..-2; sample safely inside its edges.
        var camera = new OrthographicCamera(2, 2);
        camera.position.set(1.25f, -2.75f, 2 + 2 * viewSide);
        camera.direction.set(0, 0, -viewSide);
        camera.up.set(0, 1, 0);
        camera.near = .1f;
        camera.far = 10;
        camera.update();
        var environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
        environment.add(new DirectionalLight().set(1, 1, 1, 0, 0, -lightSide));
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(0, 0, 0, 1, true);
        batch.begin(camera);
        batch.render(instance, environment);
        batch.end();
        Pixmap pixel = Pixmap.createFromFrameBuffer(Gdx.graphics.getBackBufferWidth() / 2,
              Gdx.graphics.getBackBufferHeight() / 2, 1, 1);
        try { return (pixel.getPixel(0, 0) >>> 16) & 255; }
        finally { pixel.dispose(); }
    }
}
