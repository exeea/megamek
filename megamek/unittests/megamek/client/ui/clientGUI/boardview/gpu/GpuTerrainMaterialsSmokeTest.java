/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The sculpted materials in native OpenGL: grassland banks of two levels are earth while cliffs of three are rock,
 * concrete steps are cast slabs while a three-level concrete cliff is a slab on darker bedrock, rain darkens exposed
 * ground and rock but never snow, and special ground art keeps its own colour on its top.
 */
@Tag("on-demand")
class GpuTerrainMaterialsSmokeTest {
    /** Half the edge of the sampled screen window, in pixels. */
    private static final int WINDOW = 6;

    @Test
    void rendersSculptedMaterialsRainAndSpecialArt() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                try {
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.camera.zoom = .18f;
                    BoardAtmosphere.Settings settings = new BoardAtmosphere.Settings(13, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
                    terrain.setAtmosphere(BoardAtmosphere.lighting(settings));

                    // Grassland: the same south face as a two-level bank and as a three-level cliff.
                    float[] bank = wallColor(terrain, camera, step(BoardScene.Surface.GRASS, 2, true), 2, output, "bank");
                    float[] cliff = wallColor(terrain, camera, step(BoardScene.Surface.GRASS, 3, true), 3, output, "cliff");
                    assertTrue(bank[0] / bank[2] > cliff[0] / cliff[2] + .15f,
                          "A two-level grassland step is an earth bank, a three-level one rock: bank "
                                + ratio(bank) + ", cliff " + ratio(cliff));

                    // Concrete: a two-level step is cast concrete down to its foot; from three levels a pale slab
                    // one level thick caps darker bedrock.
                    BoardScene pavedStep = step(BoardScene.Surface.CONCRETE, 2, true);
                    BoardScene pavedCliff = step(BoardScene.Surface.CONCRETE, 3, true);
                    float[] wall = sample(terrain, camera, pavedStep, facePoint(.25f), output, "concrete-wall");
                    float[] slab = sample(terrain, camera, pavedCliff, facePoint(2.5f), output, "concrete-slab");
                    float[] bedrock = sample(terrain, camera, pavedCliff, facePoint(1.1f), null, null);
                    float rock = luminance(bedrock) * 1.2f;
                    assertTrue(luminance(wall) > rock && luminance(slab) > rock,
                          "Cast concrete is paler than the bedrock under a slab: wall " + ratio(wall) + ", slab "
                                + ratio(slab) + ", bedrock " + ratio(bedrock));

                    // Rain darkens every exposed family except snow.
                    for (BoardScene.Surface family : BoardScene.Surface.values()) {
                        BoardScene scene = step(family, 3, true);
                        terrain.setWetness(0);
                        float[] dry = wallColor(terrain, camera, scene, 3, output, null);
                        terrain.setWetness(1);
                        float[] wet = wallColor(terrain, camera, scene, 3, output, null);
                        terrain.setWetness(0);
                        float darker = luminance(dry) - luminance(wet);
                        if (family == BoardScene.Surface.SNOW) {
                            assertEquals(0, darker, .002f, "Snow never takes the liquid rain film");
                        } else {
                            assertTrue(darker > .01f, family + " cliffs darken in rain: " + darker);
                        }
                    }

                    // Special ground art keeps its own texture on its sculpted top.
                    float[] green = topColor(terrain, camera, step(BoardScene.Surface.GRASS, 3, false, 0xff30b030));
                    float[] red = topColor(terrain, camera, step(BoardScene.Surface.GRASS, 3, false, 0xffb03030));
                    assertTrue(red[0] - green[0] > .1f && green[1] - red[1] > .1f,
                          "Retinted special art must retint its top: green " + ratio(green) + ", red " + ratio(red));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Sculpted materials", failure.get()); }
    }

    /** Rows 0-2 stand {@code levels} above rows 3-5, so the step's face looks south, toward the default camera. */
    private static BoardScene step(BoardScene.Surface family, int levels, boolean detailed) {
        return step(family, levels, detailed, 0xff8a8a70);
    }

    private static BoardScene step(BoardScene.Surface family, int levels, boolean detailed, int argb) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, argb); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 6; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), y < 3 ? levels : 0, -1, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, detailed));
            }
        }
        return new BoardScene(0, 6, 6, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Mid-height of the face between hexes (2, 2) and (2, 3), averaged over a small window. */
    private static float[] wallColor(GpuTerrain terrain, BoardCamera camera, BoardScene scene, int levels, File output,
          String name) {
        return sample(terrain, camera, scene, facePoint(levels * .5f), output, name);
    }

    /** The middle of the logical edge between hexes (2, 2) and (2, 3), {@code levels} above the foot. */
    private static Vector3 facePoint(float levels) {
        Vector3 edge = BoardGeometry.corner(new Coords(2, 2), 0, 4).lerp(BoardGeometry.corner(new Coords(2, 2), 0, 5), .5f);
        edge.z = levels * BoardGeometry.LEVEL;
        return edge;
    }

    private static float[] topColor(GpuTerrain terrain, BoardCamera camera, BoardScene scene) {
        return sample(terrain, camera, scene, BoardGeometry.center(new Coords(2, 1), 3), null, null);
    }

    private static float[] sample(GpuTerrain terrain, BoardCamera camera, BoardScene scene, Vector3 point, File output,
          String name) {
        terrain.update(scene);
        camera.center(point);
        terrain.renderShadows(camera.camera, List.of());
        ScreenUtils.clear(.4f, .5f, .6f, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
        if (name != null) { GpuBoardTestUi.capture(new File(output, "terrain-materials-" + name + ".png")); }
        Vector3 screen = camera.camera.project(new Vector3(point));
        Pixmap pixels = ScreenUtils.getFrameBufferPixmap((int) screen.x - WINDOW, (int) screen.y - WINDOW,
              2 * WINDOW + 1, 2 * WINDOW + 1);
        try {
            float[] sum = new float[3];
            int count = 0;
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    int rgba = pixels.getPixel(x, y);
                    sum[0] += (rgba >>> 24) / 255f;
                    sum[1] += (rgba >>> 16 & 255) / 255f;
                    sum[2] += (rgba >>> 8 & 255) / 255f;
                    count++;
                }
            }
            return new float[] { sum[0] / count, sum[1] / count, sum[2] / count };
        } finally {
            pixels.dispose();
        }
    }

    private static float luminance(float[] c) { return .2126f * c[0] + .7152f * c[1] + .0722f * c[2]; }

    private static String ratio(float[] c) { return String.format("rgb(%.3f, %.3f, %.3f)", c[0], c[1], c[2]); }
}
