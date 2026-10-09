/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Shared terrain/foliage/building/unit reference under morning, noon and overcast light in the two play cameras. */
@Tag("on-demand")
class GpuModelMaterialReviewSmokeTest {
    @Test
    void capturesJointMaterialReference() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var units = new GpuUnitModels();
                var assets = new GpuAssets();
                var batch = new ModelBatch(GpuUnitShader.provider());
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0));
                var owned = new ArrayList<Model>();
                try {
                    BoardScene scene = scene();
                    terrain.update(scene);
                    var objects = new ArrayList<ModelInstance>();
                    for (int i = 0; i < 2; i++) {
                        var body = units.modular("units/modular/bodies/" + (i == 0 ? "atlas" : "warhammer") + ".json");
                        assertNotNull(body);
                        objects.add(placed(body.model(), new Coords(1 + i * 2, 1), 55));
                    }
                    String[] buildings = { "SMV_Buildings/light_a", "SMV_Buildings/medium_f", "misc/heavy_industrial_a",
                          "fuel_tanks/fuel_tank_hard_00", "hangar_hard/hangar_hard_00", "fortress_hard/fortress_hard_00" };
                    for (int i = 0; i < buildings.length; i++) {
                        objects.add(placed(assets.model("buildings/saxarba/" + buildings[i]),
                              new Coords(i < 2 ? 5 + i * 2 : 1 + (i - 2) * 2, i < 2 ? 1 : 3), 60));
                    }
                    float[][] samples = { { .65f, .58f, .48f, .9f, 0 }, { .25f, .45f, .3f, .5f, 0 }, { .65f, .65f, .65f, .3f, 1 } };
                    for (int i = 0; i < samples.length; i++) {
                        float[] sample = samples[i];
                        var sphere = new ModelBuilder().createSphere(30, 30, 30, 40, 24,
                              new Material(ColorAttribute.createDiffuse(sample[0], sample[1], sample[2], 1),
                                    new GpuModelMaterial(sample[3], sample[4], null)),
                              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
                        owned.add(sphere);
                        objects.add(placed(sphere, new Coords(2 + i * 2, 5), 30));
                    }
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    File output = new File(System.getProperty("megamek.gpu.screenshots"), "model-materials");
                    output.mkdirs();
                    for (boolean iso : new boolean[] { false, true }) {
                        camera.setIsometric(iso);
                        camera.fit(scene);
                        camera.camera.zoom *= .85f;
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0));
                        for (String light : List.of("morning", "noon", "overcast")) {
                            frame.configure(new BoardAtmosphere.Settings(light.equals("morning") ? 9 : 13,
                                  light.equals("overcast") ? 1 : 0, 0, 2.5f, 0, 0));
                            for (int warm = 0; warm < 8; warm++) { frame.render(terrain, camera, scene, objects, batch); }
                            GpuReviewFrame.save(new File(output, (iso ? "iso-" : "top-") + light + ".png"));
                            var profiler = new GLProfiler(Gdx.graphics);
                            GL20 rawGl20 = Gdx.gl20;
                            profiler.enable();
                            try {
                                frame.render(terrain, camera, scene, objects, batch);
                                System.out.printf("MATERIAL-REVIEW %s %s draws=%d vertices=%.0f textureBindings=%d%n",
                                      iso ? "iso" : "top", light, profiler.getDrawCalls(), profiler.getVertexCount().total,
                                      profiler.getTextureBindings());
                            } finally {
                                GpuStageTimings.stopCounting(profiler, rawGl20);
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    batch.dispose();
                    owned.forEach(Model::dispose);
                    assets.dispose();
                    units.dispose();
                    terrain.dispose();
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Joint material review", failure.get()); }
    }

    private static ModelInstance placed(Model model, Coords coords, float span) {
        var object = new ModelInstance(model);
        var bounds = object.calculateBoundingBox(new BoundingBox());
        float scale = span / Math.max(bounds.getDepth(), Math.max(bounds.getWidth(), bounds.getHeight()));
        Vector3 center = bounds.getCenter(new Vector3()), position = BoardGeometry.center(coords, 0);
        object.transform.setToTranslation(position.x - center.x * scale, position.y - center.y * scale, -bounds.min.z * scale)
              .scale(scale, scale, scale);
        return object;
    }

    private static BoardScene scene() {
        var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 84; x++) {
            for (int y = 0; y < 72; y++) { image.setRGB(x, y, 0xff8a8a70); }
        }
        var pixels = new BoardScene.Pixels(image);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                var hex = new Hex(0);
                if ((x == 0 || x == 8) && y == 3) {
                    hex.addTerrain(new Terrain(Terrains.WOODS, 1));
                    hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                }
                tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.GRASS,
                      pixels, null, null, null, null, BoardFeatures.capture(hex, coords, Map.of()), List.of(), BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, 9, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
