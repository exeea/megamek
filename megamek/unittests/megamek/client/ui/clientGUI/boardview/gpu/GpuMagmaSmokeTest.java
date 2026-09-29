/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual material pixels, shared-clock motion, normal maps, darkness, live edits and both camera projections. */
@Tag("on-demand")
class GpuMagmaSmokeTest {
    private static final Coords CRUST = new Coords(2, 2), LAVA = new Coords(6, 2);

    @Test
    void volcanicMaterialsKeepTheirHeatAtNightAndMoveOnlyLiquid() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(settings(13));
                try {
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    BoardScene scene = scene(false);
                    terrain.update(scene);
                    terrain.animate(0, List.of());
                    var fftField = GpuTerrain.class.getDeclaredField("lavaOcean");
                    fftField.setAccessible(true);
                    GpuOcean lavaWaves = (GpuOcean) fftField.get(terrain);
                    assertNotNull(lavaWaves.texture(), "Lava must run the actual inverse FFT, not silently fall back");
                    var camera = new BoardCamera();
                    camera.resize(1280, 900);
                    camera.fit(scene);
                    camera.camera.zoom *= .7f;
                    camera.update();
                    terrain.setAtmosphere(frame.lighting());
                    int[] dayCrust = sample(terrain, camera, scene, CRUST);
                    int[] dayLava = sample(terrain, camera, scene, LAVA);
                    terrain.animate(3, List.of());
                    assertArrayEquals(dayCrust, sample(terrain, camera, scene, CRUST), "Solid crust must not drift");
                    int[] moved = sample(terrain, camera, scene, LAVA);
                    assertTrue(difference(dayLava, moved) > 1.5,
                          "Closed lava pools must visibly convect: mean channel change " + difference(dayLava, moved));
                    assertArrayEquals(moved, sample(terrain, camera, scene, LAVA), "Drawing does not advance time");
                    terrain.animate(0, List.of());
                    assertArrayEquals(moved, sample(terrain, camera, scene, LAVA), "A second view cannot advance the FFT");
                    terrain.setNormalMaps(false);
                    int[] flat = sample(terrain, camera, scene, CRUST);
                    terrain.setNormalMaps(true);
                    assertTrue(difference(dayCrust, flat) > .4, "Normal/height maps must affect the rendered relief");
                    terrain.setAtmosphere(new BoardAtmosphere.Lighting(new Vector3(0, 0, -1), Color.BLACK, Color.BLACK,
                          Color.BLACK, Color.BLACK, Color.BLACK, Color.WHITE, 1, 0, false));
                    int[] darkCrust = sample(terrain, camera, scene, CRUST);
                    int[] darkLava = sample(terrain, camera, scene, LAVA);
                    assertTrue(hot(darkCrust) > .015, "Crust fissures emit without any ambient or direct light");
                    double coldCrust = Arrays.stream(darkCrust)
                          .filter(p -> (p >>> 24) < 40 && (p >>> 16 & 255) < 40 && (p >>> 8 & 255) < 40)
                          .count() / (double) darkCrust.length;
                    assertTrue(coldCrust > .5,
                          "Most solid plate faces must stay cold, not glow like lava: cold fraction " + coldCrust);
                    assertTrue(hot(darkLava) > hot(darkCrust) + .05, "Lava exposes more heat than intact crust");
                    assertTrue(difference(dayCrust, darkCrust) > 1, "Cold basalt still responds to day/night lighting");
                    terrain.renderTransparent(camera.camera);
                    assertArrayEquals(darkLava, read(camera, scene, LAVA), "Lava writes the opaque depth pass");
                    // A reset in either advection phase has no pop. Two views and repeated draws share this clock.
                    terrain.animate(4.999f, List.of());
                    int[] beforeWrap = sample(terrain, camera, scene, LAVA);
                    terrain.animate(.002f, List.of());
                    assertTrue(difference(beforeWrap, sample(terrain, camera, scene, LAVA)) < .5,
                          "The flow-cycle boundary must remain continuous");
                    for (boolean perspective : new boolean[] { false, true }) {
                        camera.setPerspective(perspective);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        for (int hour : new int[] { 13, 0 }) {
                            frame.configure(settings(hour));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "magma-" + (hour == 0 ? "night" : "day")
                                  + (perspective ? "-perspective" : "-isometric") + ".png"));
                        }
                    }
                    camera.setPerspective(false);
                    camera.setIsometric(false);
                    camera.fit(scene);
                    frame.configure(settings(13));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "magma-top.png"));
                    for (Coords focus : List.of(CRUST, LAVA)) {
                        camera.setIsometric(true);
                        camera.camera.zoom = .12f;
                        camera.center(BoardGeometry.center(focus, scene.tile(focus).elevation()));
                        for (int hour : new int[] { 13, 0 }) {
                            frame.configure(settings(hour));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "magma-detail-" + (focus.equals(CRUST) ? "crust" : "lava")
                                  + (hour == 0 ? "-night" : "-day") + ".png"));
                        }
                    }
                    camera.setIsometric(false);
                    camera.fit(scene);
                    frame.configure(settings(13));
                    frame.render(terrain, camera, scene);
                    int[] beforeCooling = sample(terrain, camera, scene, LAVA);
                    BoardScene edited = scene(true);
                    terrain.update(edited);
                    assertTrue(difference(sample(terrain, camera, edited, LAVA), beforeCooling) > 3,
                          "Live cooling replaces the molten material at the same camera and clock");
                    terrain.animate(2, List.of());
                    int[] cooled = sample(terrain, camera, edited, LAVA);
                    terrain.animate(2, List.of());
                    assertArrayEquals(cooled, sample(terrain, camera, edited, LAVA), "Live cooling removes lava motion");
                    assertNull(lavaWaves.texture(), "Cooling the last pool releases the FFT targets");
                    terrain.update(scene);
                    terrain.animate(0, List.of());
                    assertNotNull(lavaWaves.texture(), "Reheating recreates the simulation at the shared clock");
                    assertTrue(difference(cooled, sample(terrain, camera, scene, LAVA)) > 3);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Volcanic terrain", failure.get()); }
    }

    private static BoardAtmosphere.Settings settings(float hour) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }

    private static BoardScene scene(boolean cooled) {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 6; y++) {
                boolean inside = y > 0 && y < 5 && x > 0 && x < 8 && x != 4;
                BoardLiquid liquid = inside ? new BoardLiquid(x < 4 || cooled ? BoardLiquid.Kind.MAGMA_CRUST
                      : BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.NONE;
                // A gentle grade at the back and a three-level spill in front exercise both forms of flow.
                tiles.add(new BoardScene.Tile(new Coords(x, y), y < 2 ? 3 : y < 4 ? 2 : -1, -1, false, 0, BoardScene.Surface.ROCK,
                      pixels, null, null, null, null, List.of(), List.of(), liquid, null, true));
            }
        }
        return new BoardScene(0, 9, 6, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static int[] sample(GpuTerrain terrain, BoardCamera camera, BoardScene scene, Coords tile) {
        ScreenUtils.clear(.02f, .025f, .035f, 1, true);
        terrain.render(camera.camera, false);
        return read(camera, scene, tile);
    }

    private static int[] read(BoardCamera camera, BoardScene scene, Coords tile) {
        Vector3 screen = camera.camera.project(BoardGeometry.center(tile, 0).add(0, 0, BoardGeometry.surfaceZ(scene.tile(tile))));
        Pixmap image = ScreenUtils.getFrameBufferPixmap(Math.round(screen.x) - 24, Math.round(screen.y) - 24, 48, 48);
        try {
            int[] result = new int[48 * 48];
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 48; x++) { result[y * 48 + x] = image.getPixel(x, y); }
            }
            return result;
        } finally { image.dispose(); }
    }

    private static double hot(int[] pixels) {
        return Arrays.stream(pixels).filter(p -> (p >>> 24) > 90 && (p >>> 24) > (p >>> 16 & 255) * 1.3
              && (p >>> 16 & 255) > (p >>> 8 & 255) * 1.5).count() / (double) pixels.length;
    }

    private static double difference(int[] a, int[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift : new int[] { 24, 16, 8 }) { sum += Math.abs((a[i] >>> shift & 255) - (b[i] >>> shift & 255)); }
        }
        return sum / (a.length * 3);
    }
}
