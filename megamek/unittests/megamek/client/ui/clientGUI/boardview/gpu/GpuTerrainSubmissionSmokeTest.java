/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Changing submission order must preserve mixed materials and transparency within measured reference quantization. */
@Tag("on-demand")
class GpuTerrainSubmissionSmokeTest {
    @Test
    void shaderGroupingPreservesTerrainAndTransparentPixelsAcrossCameraViews() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                ModelBatch units = new ModelBatch();
                Model model = new ModelBuilder().createBox(14, 14, 10,
                      new Material(ColorAttribute.createDiffuse(Color.RED)),
                      VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
                try {
                    BoardScene scene = scene();
                    terrain.update(scene);
                    terrain.setWind(BoardAtmosphere.Effects.NONE);
                    List<ModelInstance> occupants = new ArrayList<>();
                    for (Coords coords : List.of(new Coords(4, 4), new Coords(6, 6))) {
                        ModelInstance unit = new ModelInstance(model);
                        Vector3 center = BoardGeometry.center(coords, 0);
                        unit.transform.setToTranslation(center.x, center.y, BoardGeometry.groundZ(scene.tile(coords)) + 5.5f);
                        occupants.add(unit);
                    }
                    Field terrainBatch = GpuTerrain.class.getDeclaredField("batch");
                    terrainBatch.setAccessible(true);
                    ModelBatch batch = (ModelBatch) terrainBatch.get(terrain);
                    Field sorter = ModelBatch.class.getDeclaredField("sorter");
                    sorter.setAccessible(true);
                    RenderableSorter original = batch.getRenderableSorter();
                    RenderableSorter reference = new DefaultRenderableSorter(), grouped = new GpuOpaqueSorter();
                    try {
                        for (float opacity : new float[] { 1, .4f }) {
                            // Set occupancy and water once; neither animation time nor opacity advances during comparisons.
                            terrain.animate(0, occupants, opacity);
                            for (boolean perspective : new boolean[] { false, true }) {
                                for (float tilt : new float[] { 0, 54.73561f, 80 }) {
                                    BoardCamera camera = new BoardCamera();
                                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                    camera.setPerspective(perspective);
                                    camera.setIsometric(false);
                                    camera.orbit(45, tilt);
                                    camera.fit(scene);
                                    String name = (perspective ? "perspective" : "orthographic") + "-" + tilt + "-" + opacity;
                                    // Warm both submission orders so shader creation, grass and tree caches are settled.
                                    for (RenderableSorter order : List.of(reference, grouped)) {
                                        sorter.set(batch, order);
                                        for (int warmup = 0; warmup < 30; warmup++) {
                                            draw(terrain, camera, units, occupants);
                                        }
                                    }
                                    sorter.set(batch, reference);
                                    byte[] before = pixels(terrain, camera, units, occupants);
                                    sorter.set(batch, grouped);
                                    byte[] after = pixels(terrain, camera, units, occupants);
                                    sorter.set(batch, reference);
                                    byte[] restored = pixels(terrain, camera, units, occupants);
                                    Difference referenceDifference = difference(before, restored);
                                    Difference groupedDifference = difference(before, after);
                                    System.out.printf("Terrain submission %s: reference %s; grouped %s; total pixels=%d%n",
                                          name, referenceDifference, groupedDifference, before.length / 4);
                                    if (!referenceDifference.acceptable() || !groupedDifference.acceptable()) {
                                        save(before, name + "-reference.png");
                                        save(after, name + "-grouped.png");
                                        save(restored, name + "-reference-restored.png");
                                    }
                                    assertTrue(referenceDifference.acceptable(),
                                          "The frozen reference must remain stable: " + name + " " + referenceDifference);
                                    assertTrue(groupedDifference.acceptable(),
                                          "Shader grouping must preserve pixels: " + name + " " + groupedDifference);
                                }
                            }
                        }
                    } finally {
                        sorter.set(batch, original);
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    units.dispose();
                    model.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Terrain submission parity", failure.get()); }
    }

    private static BoardScene scene() {
        BoardScene relief = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.GRASS);
        BufferedImage decal = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        var graphics = decal.createGraphics();
        graphics.setColor(new java.awt.Color(70, 120, 230, 140));
        graphics.fillRect(22, 15, 35, 35);
        graphics.dispose();
        BoardScene.Pixels marking = new BoardScene.Pixels(decal);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (BoardScene.Tile tile : relief.tiles()) {
            Coords coords = tile.coords();
            List<BoardScene.Feature> features = coords.equals(new Coords(4, 4))
                  ? List.of(new BoardScene.Feature("buildings/saxarba/building_hard/building_hard_00",
                        0, 0, 0, 1, 3, 0, BoardScene.FeatureKind.BUILDING))
                  : coords.equals(new Coords(2, 4))
                        ? List.of(new BoardScene.Feature("tree", 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.TREE))
                        : List.of();
            tiles.add(new BoardScene.Tile(coords, tile.elevation(), tile.waterDepth(), false, 0,
                  BoardScene.Surface.values()[coords.getX() % BoardScene.Surface.values().length], tile.ground(),
                  null, tile.water() ? null : marking, null, null, features, List.of(), tile.liquid(), null, true));
        }
        return new BoardScene(0, relief.width(), relief.height(), tiles, List.of(), List.of(), -1, "", List.of(),
              new BoardScene.Light(-24, -30));
    }

    private static void draw(GpuTerrain terrain, BoardCamera camera, ModelBatch units, List<ModelInstance> occupants) {
        terrain.renderShadows(camera.camera, occupants);
        ScreenUtils.clear(.2f, .26f, .31f, 1, true);
        terrain.render(camera.camera, false);
        units.begin(camera.camera);
        units.render(occupants, terrain.environment());
        units.end();
        terrain.renderTransparent(camera.camera);
        terrain.render(camera.camera, true);
    }

    private static byte[] pixels(GpuTerrain terrain, BoardCamera camera, ModelBatch units, List<ModelInstance> occupants) {
        draw(terrain, camera, units, occupants);
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight(), false);
        int varied = 0;
        for (int i = 4; i < pixels.length; i += 4) {
            if (pixels[i] != pixels[0] || pixels[i + 1] != pixels[1] || pixels[i + 2] != pixels[2]) { varied++; }
        }
        assertTrue(varied > 2000, "The comparison must contain visible rendered terrain");
        return pixels;
    }

    private static void save(byte[] pixels, String name) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Pixmap image = new Pixmap(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Pixmap.Format.RGBA8888);
        try {
            image.getPixels().put(pixels);
            PixmapIO.writePNG(new FileHandle(new File(directory, "terrain-submission-" + name)), image, -1, true);
        } finally {
            image.dispose();
        }
    }

    private record Difference(int changedPixels, int maximumChannelDelta) {
        // Repeated frozen reference draws vary by 1 LSB at one cliff/shore pixel, even after 30 warmup frames.
        // Permit only that quantization noise in <=8 of 1,152,000 pixels; no shifted edges or structural tolerance.
        boolean acceptable() { return changedPixels <= 8 && maximumChannelDelta <= 1; }
    }

    private static Difference difference(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        int changedPixels = 0, maximum = 0;
        for (int pixel = 0; pixel < expected.length; pixel += 4) {
            int delta = 0;
            for (int channel = 0; channel < 4; channel++) {
                delta = Math.max(delta, Math.abs(Byte.toUnsignedInt(expected[pixel + channel])
                      - Byte.toUnsignedInt(actual[pixel + channel])));
            }
            if (delta != 0) { changedPixels++; maximum = Math.max(maximum, delta); }
        }
        return new Difference(changedPixels, maximum);
    }
}
