/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Change the sampled map values, not the LOD code: distant rendering must become independent of those maps. */
@Tag("on-demand")
class GpuMaterialLodSmokeTest {
    @Test
    void distantMaterialsIgnoreDetailMapsButKeepTheirColourAndHeat() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkMaterials(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Material LOD", failure.get()); }
    }

    private static void checkMaterials(GpuShaderManager manager) {
        var terrain = new GpuTerrain();
        try {
            var camera = new OrthographicCamera();
            var center = BoardGeometry.center(new Coords(3, 3), 0);
            camera.position.set(center).add(0, 0, 5000);
            camera.direction.set(0, 0, -1);
            camera.up.set(0, 1, 0);
            camera.near = 1;
            camera.far = 10000;
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            for (String material : List.of("sand", "desert", "mars", "concrete", "crust", "lava")) {
                assertTrue(manager.apply(Map.of()).success());
                terrain.update(scene(material));
                double closeDifference = 0;
                for (float footprint : new float[] { .1f, .8f, 2f }) {
                    camera.viewportWidth = Gdx.graphics.getBackBufferWidth() * BoardRelief.metres(footprint);
                    camera.viewportHeight = Gdx.graphics.getBackBufferHeight() * BoardRelief.metres(footprint);
                    camera.update();
                    assertTrue(manager.apply(Map.of()).success());
                    byte[] original = render(terrain, camera);
                    var changed = manager.apply(changedMaps(false));
                    assertTrue(changed.success(), changed.message());
                    byte[] altered = render(terrain, camera);
                    String description = material + " at " + footprint + " m/pixel";
                    double difference = difference(original, altered);
                    System.out.println(description + ": detail-map difference " + difference);
                    if (footprint < .2f) {
                        closeDifference = difference;
                        // Molten emission dominates its reflected light; even a large normal change is subtle.
                        assertTrue(difference > .001, description + " still uses its normal map");
                    } else if (footprint < 1f) {
                        // Small stone sides retain cliff detail after the surrounding flat top stops sampling.
                        assertTrue(difference < closeDifference * .1, description + " removes ground detail first");
                    } else {
                        assertArrayEquals(original, altered, description + " must not use its fine normals/AO");
                    }
                    if (footprint == 2f && (material.equals("crust") || material.equals("lava"))) {
                        changed = manager.apply(changedMaps(true));
                        assertTrue(changed.success(), changed.message());
                        assertArrayEquals(original, render(terrain, camera), description + " skips surface maps too");
                    }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { terrain.dispose(); }
    }

    private static Map<String, String> changedMaps(boolean surfaces) {
        var result = new HashMap<String, String>();
        String material = GpuShaderSource.readDisk("terrain-materials.glsl");
        result.put("terrain-materials.glsl", material.replace(
              "vec4 materialTexel(float layer, vec2 uv, mat2 gradient) {",
              "vec4 materialTexel(float layer, vec2 uv, mat2 gradient) {"
                    + " if (mod(layer, 2.0) > .5) return vec4(.95, .05, .65, .1);"));
        String projection = GpuShaderSource.readDisk("terrain-projection.glsl");
        result.put("terrain-projection.glsl", projection.replace("vec4 mapTexel(float layer, vec2 uv) {",
              "vec4 mapTexel(float layer, vec2 uv) {"
                    + " if (mod(layer, 2.0) > .5) return vec4(.95, .05, .65, .1);"));
        String magma = GpuShaderSource.readDisk("terrain-magma.glsl");
        result.put("terrain-magma.glsl", magma.replace("vec4 magmaTexel(float map, vec2 uv, vec2 dx, vec2 dy) {",
              "vec4 magmaTexel(float map, vec2 uv, vec2 dx, vec2 dy) {"
                    + " if (map == MAGMA_NORMAL) return vec4(.95, .05, .65, .1);"
                    + (surfaces ? " if (map == MAGMA_SURFACE) return vec4(.1, .1, .1, .1);" : "")));
        return result;
    }

    private static byte[] render(GpuTerrain terrain, OrthographicCamera camera) {
        ScreenUtils.clear(.02f, .025f, .035f, 1, true);
        terrain.render(camera, false);
        return ScreenUtils.getFrameBufferPixels(false);
    }

    private static double difference(byte[] a, byte[] b) {
        long total = 0;
        for (int i = 0; i < a.length; i++) { total += Math.abs((a[i] & 255) - (b[i] & 255)); }
        return total / (double) a.length;
    }

    private static BoardScene scene(String material) {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        BoardLiquid liquid = switch (material) {
            case "crust" -> new BoardLiquid(BoardLiquid.Kind.MAGMA_CRUST, "", 0);
            case "lava" -> new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0);
            default -> BoardLiquid.NONE;
        };
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0,
                      switch (material) {
                          case "concrete" -> BoardScene.Surface.CONCRETE;
                          case "desert" -> BoardScene.Surface.DESERT;
                          case "mars" -> BoardScene.Surface.MARS;
                          default -> BoardScene.Surface.SAND;
                      },
                      pixels, null, null, null, null, List.of(), List.of(), liquid, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
