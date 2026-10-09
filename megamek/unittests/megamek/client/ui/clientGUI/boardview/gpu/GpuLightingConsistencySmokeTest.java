/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Checks radiance at the actual scene/composite and standard-model lighting boundaries. */
@Tag("on-demand")
class GpuLightingConsistencySmokeTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final String SOURCE = """
          #version 330 core
          layout(location = 0) out vec4 fragColor;
          uniform float u_radiance;
          void main() {
              fragColor = vec4(vec3(pow(u_radiance, 1.0 / 2.2)), 1.0);
              gl_FragDepth = .5;
          }
          """;

    @Test
    void exposureRecoversOrdinaryHighlightsAndLiquidChangesKeepTheSceneTarget() {
        nativeCheck(() -> {
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            Mesh quad = GpuAtmosphere.screenQuad();
            ShaderProgram source = GpuGlsl.compile("Lighting radiance reference",
                  GpuShaderSource.read("atmosphere.vert"), SOURCE);
            var camera = camera(false);
            var dry = scene(false);
            var molten = scene(true);
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, -2));
                int dim = radianceFrame(atmosphere, terrain, dry, camera, quad, source, 1);
                var depth = atmosphere.depthTexture();
                int bright = radianceFrame(atmosphere, terrain, dry, camera, quad, source, 4);
                assertTrue(bright > dim + 50,
                      "Negative exposure must recover distinct highlights above one: " + dim + "/" + bright);
                int withLava = radianceFrame(atmosphere, terrain, molten, camera, quad, source, 4);
                assertEquals(bright, withLava, 1, "White surface highlights keep the same response beside lava");
                assertSame(depth, atmosphere.depthTexture(), "A liquid edit must not replace the scene color/depth target");
                radianceFrame(atmosphere, terrain, dry, camera, quad, source, 4);
                assertSame(depth, atmosphere.depthTexture(), "Removing lava retains that same target");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                source.dispose();
                quad.dispose();
                terrain.dispose();
                atmosphere.dispose();
            }
        });
    }

    @Test
    void standardModelsAddEmissionToReflectedLinearLightInTopAndIsometricViews() {
        nativeCheck(() -> {
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            var batch = new ModelBatch(GpuUnitShader.provider());
            var model = new ModelBuilder().createBox(80, 80, 4,
                  new Material(ColorAttribute.createDiffuse(new Color(.4f, .4f, .4f, 1)),
                        ColorAttribute.createEmissive(new Color(.25f, .25f, .25f, 1))),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            var instance = new ModelInstance(model);
            var environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(1, 1, 1, 1));
            var scene = scene(false);
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0));
                // Unit sky light on the horizontal face is one. Both authored colors encode radiance;
                // their sum stays below the highlight shoulder and therefore has this display value.
                int expected = (int) Math.round(255 * Math.pow(Math.pow(.4, 2.2) + Math.pow(.25, 2.2), 1 / 2.2));
                for (boolean isometric : new boolean[] { false, true }) {
                    var camera = camera(isometric);
                    atmosphere.updateLight(camera);
                    atmosphere.configureClouds(terrain, scene);
                    atmosphere.begin(WIDTH, HEIGHT, 0);
                    batch.begin(camera);
                    batch.render(instance, environment);
                    batch.end();
                    atmosphere.end(camera, terrain, scene, 0);
                    assertEquals(expected, centerRed(), 3,
                          "Model emission and reflected light must add before display encoding; iso=" + isometric);
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                model.dispose();
                batch.dispose();
                terrain.dispose();
                atmosphere.dispose();
            }
        });
    }

    private static int radianceFrame(GpuAtmosphere atmosphere, GpuTerrain terrain, BoardScene scene,
          OrthographicCamera camera, Mesh quad, ShaderProgram source, float radiance) {
        atmosphere.updateLight(camera);
        atmosphere.configureClouds(terrain, scene);
        atmosphere.begin(WIDTH, HEIGHT, 0);
        GpuAtmosphere.screenState();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        source.bind();
        source.setUniformf("u_radiance", radiance);
        quad.render(source, GL20.GL_TRIANGLES);
        atmosphere.end(camera, terrain, scene, 0);
        return centerRed();
    }

    private static int centerRed() {
        Pixmap image = Pixmap.createFromFrameBuffer(WIDTH / 2, HEIGHT / 2, 1, 1);
        try { return image.getPixel(0, 0) >>> 24; }
        finally { image.dispose(); }
    }

    private static OrthographicCamera camera(boolean isometric) {
        var camera = new OrthographicCamera(WIDTH, HEIGHT);
        camera.near = 1;
        camera.far = 500;
        camera.position.set(isometric ? 120 : 0, isometric ? -160 : 0, 200);
        camera.up.set(0, isometric ? 0 : 1, isometric ? 1 : 0);
        camera.lookAt(0, 0, 0);
        camera.update();
        return camera;
    }

    private static BoardScene scene(boolean molten) {
        var tile = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.GRASS,
              null, null, null, null, null, List.of(), List.of(),
              molten ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.NONE);
        return new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
    }

    private static void nativeCheck(Runnable check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(WIDTH, HEIGHT, -1, -1);
        config.setWindowedMode(WIDTH, HEIGHT);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check.run(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Lighting consistency", failure.get()); }
    }
}
