/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual submitted triangle counts, shared render passes, and pixel comparisons to the original catalog. */
@Tag("on-demand")
class GpuTreeLodSmokeTest {
    private static final List<String> TREES = List.of("tree", "tree-broad", "tree-slender", "birch", "willow", "pine",
          "pine-tall", "palm", "palm-bent", "tree-snow", "tree-broad-snow", "tree-slender-snow", "birch-snow",
          "willow-snow", "pine-snow", "pine-tall-snow", "pine-broad", "pine-broad-snow", "tree-dead",
          "tree-dead-snow", "cactus", "cactus-flowers");
    /** A projected diameter within each level's band, past the hysteresis of the thresholds on both sides. */
    static final float[] PIXELS = { 200, 60, 32, 12 };

    @Test
    void changesSubmittedGeometryWithoutChangingPickingOpacityOrShadows() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuAssets assets = new GpuAssets();
                GpuTerrain terrain = new GpuTerrain();
                ModelBatch depth = new ModelBatch(GpuTreeInstances.depthProvider(new DepthShader.Config()));
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                Model occupant = new ModelBuilder().createBox(4, 4, 40, new Material(), VertexAttributes.Usage.Position);
                try {
                    profiler.enable();
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    Coords coords = new Coords(0, 0);
                    Vector3 center = BoardGeometry.center(coords, 0);
                    camera.center(new Vector3(center).add(0, 0, 18));
                    ModelInstance unit = new ModelInstance(occupant);
                    unit.transform.setToTranslation(new Vector3(center).add(0, 0, 18));
                    for (String name : TREES) {
                        BoardScene scene = scene(name);
                        terrain.update(scene);
                        int[] triangles = new int[TreeLod.LEVELS];
                        for (int level = 0; level < TreeLod.LEVELS; level++) {
                            Model model = assets.lodModel(name, level);
                            for (var part : model.meshParts) {
                                triangles[level] += part.size / 3;
                            }
                            assertEquals(name.contains("snow"), model.getMaterial("snow") != null, name);
                        }
                        assertTrue(triangles[1] <= 240 && triangles[2] <= 96 && triangles[3] <= 12, name);
                        BoundingBox bounds = assets.model(name).calculateBoundingBox(new BoundingBox());
                        assertEquals(30, bounds.getDepth(), .001f, "Plant GLBs must not contain flattened geometry: " + name);
                        float diameter = bounds.getDimensions(new Vector3()).scl(1, 1, 36 / bounds.getDepth()).len();
                        BoardGeometry.Hit nearHit = terrain.hit(scene, new Ray(new Vector3(center).add(0, 0, 1000),
                              new Vector3(0, 0, -1)));
                        int nearCount = 0;
                        int nearShadowCount = 0;
                        for (int level : new int[] { 0, 1, 2, 3, 0, 3, 2, 1, 0 }) {
                            camera.camera.zoom = level == 0 ? 0.1f : zoomForSize(diameter, PIXELS[level]);
                            camera.update();
                            terrain.animate(0, List.of(), 1);
                            // A level change alone keeps the shadow map until the next refit; redraw it to count
                            // the selected level's geometry.
                            terrain.refreshShadows();
                            int shadowCount = count(profiler, () -> terrain.renderShadows(camera.camera, List.of()));
                            int colorCount = count(profiler, () -> terrain.render(camera.camera, false));
                            if (level == 0) {
                                nearCount = colorCount;
                                nearShadowCount = shadowCount;
                            }
                            assertEquals(3 * (triangles[0] - triangles[level]), nearCount - colorCount, name);
                            assertEquals(3 * (triangles[0] - triangles[level]), nearShadowCount - shadowCount,
                                  "Shadow uses the selected tree geometry: " + name);
                            assertEquals(shadowCount, count(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth)));
                            assertEquals(nearHit, terrain.hit(scene, new Ray(new Vector3(center).add(0, 0, 1000),
                                  new Vector3(0, 0, -1))), "Picking remains stable across detail levels");
                            terrain.animate(0, List.of(unit), 0);
                            assertEquals(0, count(profiler, () -> terrain.renderTransparent(camera.camera)),
                                  "Trees must stay in the opaque pass when a unit enters");
                            assertEquals(colorCount, count(profiler, () -> terrain.render(camera.camera, false)));
                            assertEquals(shadowCount,
                                  count(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth)));
                        }
                        // An occupied tree stays opaque through both LoD thresholds, including camera depth
                        // for the unit outline. The same selected geometry casts its shadow.
                        for (int level : new int[] { 1, 2, 3, 0 }) {
                            camera.camera.zoom = level == 0 ? 0.1f : zoomForSize(diameter, PIXELS[level]);
                            camera.update();
                            terrain.refreshShadows();
                            assertEquals(nearShadowCount - 3 * (triangles[0] - triangles[level]),
                                  count(profiler, () -> terrain.renderShadows(camera.camera, List.of())));
                            assertEquals(0, count(profiler, () -> terrain.renderTransparent(camera.camera)));
                            assertEquals(nearShadowCount - 3 * (triangles[0] - triangles[level]),
                                  count(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth)));
                            assertEquals(0, count(profiler, () -> terrain.renderShadows(camera.camera, List.of())),
                                  "An unchanged camera and scene must reuse the shadow map");
                        }
                    }
                    profiler.disable();
                    compareReferences(assets);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    profiler.disable();
                    occupant.dispose();
                    depth.dispose();
                    terrain.dispose();
                    assets.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static int count(GLProfiler profiler, Runnable draw) {
        profiler.reset();
        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
        draw.run();
        return (int) profiler.getVertexCount().total;
    }

    private static BoardScene scene(String name) {
        BufferedImage ground = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ground.getHeight(); y++) {
            for (int x = 0; x < ground.getWidth(); x++) {
                ground.setRGB(x, y, 0xff6b8255);
            }
        }
        BoardScene.Tile tile = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.GRASS,
              new BoardScene.Pixels(ground), null, null,
              List.of(new BoardScene.Feature(name, 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.TREE)),
              List.of());
        return new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of(), new BoardScene.Light(-24, -30));
    }

    /** The complete authored meshes remain available as the visual reference for optimized geometry. */
    private static void compareReferences(GpuAssets assets) throws Exception {
        Environment environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(0.55f, 0.58f, 0.62f, 1));
        environment.add(new DirectionalLight().set(0.55f, 0.53f, 0.48f, -24, -30, -18));
        ModelBatch batch = new ModelBatch();
        try {
            for (String name : TREES) {
                var file = new com.badlogic.gdx.files.FileHandle(new File(
                      System.getProperty("megamek.gpu.referenceFoliage"), name + ".glb"));
                var data = RigidGlb.loadLods(file, new File(System.getProperty("megamek.gpu.referenceFoliage"))
                      .toPath().toAbsolutePath().getParent().getParent().getParent()).getFirst();
                Model reference = new Model(data, filename -> assets.material("foliage/"
                      + new com.badlogic.gdx.files.FileHandle(filename).nameWithoutExtension()));
                // Reference geometry is test-owned; its textures belong to the shared cache.
                var owned = reference.getManagedDisposables().iterator();
                while (owned.hasNext()) {
                    if (owned.next() instanceof com.badlogic.gdx.graphics.Texture) { owned.remove(); }
                }
                try {
                for (float tilt : new float[] { 0, 54.73561f, 80 }) {
                    for (int bearing = 0; bearing < 360; bearing += 90) {
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.orbit(bearing, tilt);
                        camera.center(new Vector3(0, 0, 18));
                        camera.zoom(0.001f);
                        BufferedImage original = render(batch, environment, reference, camera);
                        BufferedImage optimized = render(batch, environment, assets.lodModel(name, 0), camera);
                        int changed = differences(original, optimized);
                        assertEquals(0, changed, name + " at tilt " + tilt + ", bearing " + bearing);
                        if (tilt == 54.73561f && bearing == 0) {
                            BufferedImage half = render(batch, environment, assets.lodModel(name, 1), camera);
                            BufferedImage comparison = new BufferedImage(original.getWidth() * 3, original.getHeight(),
                                  BufferedImage.TYPE_INT_RGB);
                            var graphics = comparison.createGraphics();
                            try {
                                graphics.drawImage(original, 0, 0, null);
                                graphics.drawImage(optimized, original.getWidth(), 0, null);
                                graphics.drawImage(half, original.getWidth() * 2, 0, null);
                                graphics.setColor(java.awt.Color.WHITE);
                                graphics.drawString("Original / minimum zoom 0.1", 20, 30);
                                graphics.drawString("Optimized near / zoom 0.1", original.getWidth() + 20, 30);
                                graphics.drawString("Half triangle budget / zoom 0.1 (comparison only)", original.getWidth() * 2 + 20, 30);
                            } finally {
                                graphics.dispose();
                            }
                            File directory = new File(System.getProperty("megamek.gpu.screenshots"));
                            assertTrue(directory.isDirectory() || directory.mkdirs());
                            ImageIO.write(comparison, "png", new File(directory, "tree-detail-" + name + ".png"));
                        }
                    }
                }
                } finally { reference.dispose(); }
            }
            compareDistantLevels(assets, batch, environment);
        } finally {
            batch.dispose();
        }
    }

    /** Native pixel scale at the largest size each coarse mesh can retain through hysteresis. */
    private static void compareDistantLevels(GpuAssets assets, ModelBatch batch, Environment environment) throws Exception {
        String[] labels = { "Near / 150px", "Medium / 150px", "Near / 88px", "Medium / 88px", "Far / 52px", "Far / 26px", "Impostor / 26px", "Impostor / 12px" };
        int[] levels = { 0, 1, 0, 1, 2, 2, 3, 3 };
        float[] sizes = { 150, 150, 88, 88, 52.8f, 26.4f, 26.4f, 12 };
        BufferedImage sheet = new BufferedImage(192 + labels.length * 128, TREES.size() * 132 + 32, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            for (int column = 0; column < labels.length; column++) {
                graphics.drawString(labels[column], 192 + column * 128, 20);
            }
            for (int row = 0; row < TREES.size(); row++) {
                String name = TREES.get(row);
                BoundingBox bounds = assets.model(name).calculateBoundingBox(new BoundingBox());
                float diameter = bounds.getDimensions(new Vector3()).scl(1, 1, 36 / bounds.getDepth()).len();
                graphics.drawString(name, 12, row * 132 + 94);
                BoardCamera camera = new BoardCamera();
                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                camera.setIsometric(true);
                camera.center(new Vector3(0, 0, 18));
                for (int column = 0; column < labels.length; column++) {
                    camera.camera.zoom = zoomForSize(diameter, sizes[column]);
                    camera.update();
                    BufferedImage frame = render(batch, environment, assets.lodModel(name, levels[column]), camera);
                    graphics.drawImage(frame.getSubimage(frame.getWidth() / 2 - 64, frame.getHeight() / 2 - 64, 128, 128),
                          192 + column * 128, row * 132 + 32, null);
                }
            }
        } finally {
            graphics.dispose();
        }
        ImageIO.write(sheet, "png", new File(System.getProperty("megamek.gpu.screenshots"), "tree-lod-transitions.png"));
    }

    private static BufferedImage render(ModelBatch batch, Environment environment, Model model, BoardCamera camera) {
        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
        ModelInstance instance = new ModelInstance(model);
        // All levels share the LOD0 coordinate frame, whose catalog height is 30.
        instance.transform.setToScaling(1, 1, 36 / 30f);
        batch.begin(camera.camera);
        batch.render(instance, environment);
        batch.end();
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            BufferedImage image = new BufferedImage(pixels.getWidth(), pixels.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    image.setRGB(x, pixels.getHeight() - y - 1, pixels.getPixel(x, y) >>> 8);
                }
            }
            return image;
        } finally {
            pixels.dispose();
        }
    }

    private static float zoomForSize(float diameter, float pixels) {
        return diameter * Gdx.graphics.getBackBufferHeight() / Gdx.graphics.getHeight() / pixels;
    }

    private static int differences(BufferedImage a, BufferedImage b) {
        int changed = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                // Linear glTF colors round through float32; permit one display-channel step, not silhouette changes.
                int first = a.getRGB(x, y), second = b.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    if (Math.abs((first >> shift & 255) - (second >> shift & 255)) > 1) {
                        changed++;
                        break;
                    }
                }
            }
        }
        return changed;
    }
}
