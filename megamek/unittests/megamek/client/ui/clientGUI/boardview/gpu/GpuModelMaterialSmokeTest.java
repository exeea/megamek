/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.environment.ShadowMap;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native pixels at the shared standard-model shader boundary, including orthographic viewing and linear maps. */
@Tag("on-demand")
class GpuModelMaterialSmokeTest {
    private static final int SIZE = 256;

    @Test
    void boardCameraProjectionDeterminesWhetherViewRaysAreParallel() {
        nativeCheck(() -> {
            var model = new ModelBuilder().createBox(120, 120, 4,
                  new Material(ColorAttribute.createDiffuse(.2f, .2f, .2f, 1), new GpuModelMaterial(.28f, 0, null)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
            var object = new ModelInstance(model);
            var batch = new ModelBatch(GpuUnitShader.provider());
            var camera = new BoardProjectionCamera();
            camera.viewportWidth = SIZE;
            camera.viewportHeight = SIZE;
            camera.near = 1;
            camera.far = 500;
            camera.position.set(0, 0, 200);
            camera.direction.set(0, 0, -1);
            camera.up.set(0, 1, 0);
            var environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
            environment.add(new DirectionalLight().set(.2f, .2f, .2f, -.4f, 0, -1));
            try {
                for (boolean perspective : new boolean[] { false, true }) {
                    camera.perspective = perspective;
                    camera.update();
                    draw(batch, object, camera, environment);
                    Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
                    try {
                        int left = red(pixels.getPixel(SIZE / 2 - 45, SIZE / 2));
                        int right = red(pixels.getPixel(SIZE / 2 + 45, SIZE / 2));
                        if (perspective) {
                            assertTrue(Math.abs(left - right) > 10,
                                  "Perspective highlight follows the eye ray: " + left + "/" + right);
                        } else {
                            assertEquals(left, right, "Orthographic board rays are parallel across the face");
                        }
                    } finally { pixels.dispose(); }
                }
            } finally {
                batch.dispose();
                model.dispose();
            }
        });
    }

    @Test
    void geometryAndCloudShadowsAttenuateReflectionsWithoutDimmingEmission() {
        nativeCheck(() -> {
            var model = new ModelBuilder().createBox(120, 120, 4,
                  new Material(ColorAttribute.createDiffuse(.2f, .2f, .2f, 1),
                        ColorAttribute.createEmissive(.1f, .1f, .1f, 1), new GpuModelMaterial(.28f, 0, null)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
            var object = new ModelInstance(model);
            var batch = new ModelBatch(GpuUnitShader.provider());
            Texture dark = texture(0x000000ff), clear = texture(0xffffffff), cloud = texture(0x404040ff);
            try {
                for (boolean iso : new boolean[] { false, true }) {
                    var camera = camera(iso);
                    var environment = new Environment();
                    environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
                    environment.add(new DirectionalLight().set(.2f, .2f, .2f,
                          -camera.direction.x, -camera.direction.y, camera.direction.z));
                    var depth = new TextureDescriptor<>(clear);
                    environment.shadowMap = new ShadowMap() {
                        private final Matrix4 projection = new Matrix4().setToScaling(0, 0, 0);

                        @Override public Matrix4 getProjViewTrans() { return projection; }
                        @Override public TextureDescriptor<Texture> getDepthMap() { return depth; }
                    };
                    int lit = red(draw(batch, object, camera, environment));
                    depth.texture = dark;
                    int shadowed = red(draw(batch, object, camera, environment));
                    assertEquals(26, shadowed, 1, "Geometry shadow removes diffuse and specular light, leaving emission");
                    depth.texture = clear;
                    var projection = new Matrix4().setToScaling(0, 0, 0).setTranslation(.5f, .5f, 0);
                    environment.set(new GpuCloudShadow(cloud, projection));
                    int cloudy = red(draw(batch, object, camera, environment));
                    assertTrue(cloudy > shadowed + 15 && cloudy < lit - 20,
                          "Cloud transmission dims the direct highlight: " + lit + "/" + cloudy + "/" + shadowed);
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                cloud.dispose();
                clear.dispose();
                dark.dispose();
                batch.dispose();
                model.dispose();
            }
        });
    }

    @Test
    void authoredSurfacesAndMapsControlLightingInTopAndIsometricViews() {
        nativeCheck(() -> {
            var model = new ModelBuilder().createBox(120, 120, 4,
                  new Material(ColorAttribute.createDiffuse(new Color(.2f, .2f, .2f, 1))),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
            var object = new ModelInstance(model);
            var material = object.materials.first();
            var batch = new ModelBatch(GpuUnitShader.provider());
            Texture packed = texture(0x188000ff), occlusion = texture(0x40ffffff), normal = texture(0xf080bfff);
            try {
                for (boolean iso : new boolean[] { false, true }) {
                    var camera = camera(iso);
                    var sun = new DirectionalLight().set(.2f, .2f, .2f,
                          -camera.direction.x, -camera.direction.y, camera.direction.z);
                    var environment = new Environment();
                    environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
                    environment.add(sun);
                    material.set(new GpuModelMaterial(.28f, 0, null));
                    int smooth = red(draw(batch, object, camera, environment));
                    material.set(new GpuModelMaterial(.9f, 0, null));
                    int rough = red(draw(batch, object, camera, environment));
                    assertTrue(smooth > rough + 30, "Roughness must broaden the sun highlight: " + smooth + "/" + rough);

                    material.set(new GpuModelMaterial(.5f, 1, packed));
                    int mapped = draw(batch, object, camera, environment);
                    material.set(new GpuModelMaterial(.5f * 128 / 255, 0, null));
                    assertEquals(mapped, draw(batch, object, camera, environment),
                          "Packed G/B are linear multipliers; R must not change surface response");

                    material.set(ColorAttribute.createDiffuse(new Color(.8f, .25f, .08f, 1)),
                          new GpuModelMaterial(.45f, 0, null));
                    int paint = draw(batch, object, camera, environment);
                    material.set(new GpuModelMaterial(.45f, 1, null));
                    int metal = draw(batch, object, camera, environment);
                    assertTrue(red(metal) > green(metal) * 3, "Metal reflection must inherit the base color");
                    assertTrue((paint >>> 8 & 255) > (metal >>> 8 & 255) + 15,
                          "Dielectric highlights add neutral light even to the dark blue channel: " + paint + "/" + metal);

                    material.set(ColorAttribute.createDiffuse(new Color(.5f, .5f, .5f, 1)),
                          new GpuModelMaterial(.8f, 0, null));
                    // Ambient occlusion attenuates indirect sky/ground, without painting dark spots over direct sunlight.
                    sun.set(0, 0, 0, 0, 0, -1);
                    environment.set(ColorAttribute.createAmbientLight(.6f, .6f, .6f, 1));
                    int ambient = red(draw(batch, object, camera, environment));
                    material.set(TextureAttribute.createAmbient(occlusion));
                    int occluded = red(draw(batch, object, camera, environment));
                    assertTrue(ambient > occluded + 25, "AO must attenuate ambient light");
                    environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
                    sun.set(.5f, .5f, .5f, 0, 0, -1);
                    int directWithAo = draw(batch, object, camera, environment);
                    material.remove(TextureAttribute.Ambient);
                    assertEquals(directWithAo, draw(batch, object, camera, environment), "AO must leave direct light intact");
                    int flat = red(directWithAo);
                    material.set(TextureAttribute.createNormal(normal));
                    int bumped = red(draw(batch, object, camera, environment));
                    assertTrue(flat > bumped + 10, "The original-UV normal map must change light incidence: " + flat + "/" + bumped);
                    sun.set(.5f, .5f, .5f, -1, -1, -.4f);
                    int originalUv = red(draw(batch, object, camera, environment));
                    mirrorUv(model);
                    int mirroredUv = red(draw(batch, object, camera, environment));
                    assertTrue(Math.abs(originalUv - mirroredUv) > 25,
                          "Mirroring mesh U must reverse the mapped tangent direction: " + originalUv + "/" + mirroredUv);
                    mirrorUv(model);
                    material.remove(TextureAttribute.Normal);

                    // Translating a surface parallel to the image must not move its highlight in an orthographic view.
                    material.set(new GpuModelMaterial(.28f, 0, null));
                    sun.set(.2f, .2f, .2f, -camera.direction.x, -camera.direction.y, camera.direction.z);
                    int center = draw(batch, object, camera, environment);
                    object.transform.setToTranslation(25, 0, 0);
                    assertEquals(center, draw(batch, object, camera, environment), "Orthographic rays remain parallel");
                    object.transform.idt();
                    System.out.printf("Model surfaces iso=%s: smooth=%d rough=%d ambient=%d AO=%d normal=%d/%d%n",
                          iso, smooth, rough, ambient, occluded, flat, bumped);
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                normal.dispose();
                occlusion.dispose();
                packed.dispose();
                batch.dispose();
                model.dispose();
            }
        });
    }

    private static int draw(ModelBatch batch, ModelInstance object, Camera camera, Environment environment) {
        ScreenUtils.clear(0, 0, 0, 1, true);
        batch.begin(camera);
        batch.render(object, environment);
        batch.end();
        Pixmap pixels = Pixmap.createFromFrameBuffer(SIZE / 2, SIZE / 2, 1, 1);
        try { return pixels.getPixel(0, 0); }
        finally { pixels.dispose(); }
    }

    private static int red(int pixel) { return pixel >>> 24; }
    private static int green(int pixel) { return pixel >>> 16 & 255; }

    private static void mirrorUv(com.badlogic.gdx.graphics.g3d.Model model) {
        for (var mesh : model.meshes) {
            int stride = mesh.getVertexSize() / Float.BYTES;
            int uv = mesh.getVertexAttribute(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices);
            for (int i = uv; i < vertices.length; i += stride) { vertices[i] = 1 - vertices[i]; }
            mesh.setVertices(vertices);
        }
    }

    private static Texture texture(int color) {
        var pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pixels.setColor(color);
        pixels.fill();
        try { return new Texture(pixels); }
        finally { pixels.dispose(); }
    }

    private static OrthographicCamera camera(boolean iso) {
        var camera = new OrthographicCamera(SIZE, SIZE);
        camera.near = 1;
        camera.far = 500;
        camera.position.set(0, iso ? -120 : 0, 200);
        camera.up.set(0, iso ? 0 : 1, iso ? 1 : 0);
        camera.lookAt(0, 0, 0);
        camera.update();
        return camera;
    }

    private static void nativeCheck(Runnable check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(SIZE, SIZE, -1, -1);
        config.setWindowedMode(SIZE, SIZE);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check.run(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Model surface lighting", failure.get()); }
    }
}
