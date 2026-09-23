/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
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

/** Real GL integration of physical tops, skirts, cliffs, water, moving cover, and both camera views. */
@Tag("on-demand")
class GpuTerrainReliefSmokeTest {
    @Test
    void rendersEverySurfaceFamilyWithAnimatedWaterAndWind() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                try {
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    StringBuilder timing = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER))
                          .append("\n1440x1080, terrain-only native frames; median of 12 synchronized samples after warmup.\n");
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    BoardAtmosphere.Lighting daylight = new BoardAtmosphere.Lighting(new Vector3(-.8f, .45f, -.85f).nor(),
                          new Color(.88f, .84f, .77f, 1), new Color(.31f, .34f, .38f, 1), Color.BLACK,
                          new Color(.39f, .57f, .72f, 1), new Color(.69f, .75f, .78f, 1), Color.WHITE, 1, 1, true);
                    terrain.setAtmosphere(daylight);
                    terrain.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, .65f, 115));
                    for (BoardScene.Surface family : BoardScene.Surface.values()) {
                        BoardScene scene = scene(family);
                        terrain.update(scene);
                        camera.setIsometric(true);
                        camera.tilt(5);
                        camera.camera.zoom = .26f;
                        camera.center(BoardGeometry.center(new Coords(4, 5), 1));
                        terrain.renderShadows(camera.camera, List.of());
                        // Warm shaders, shadows and the offscreen vegetation guard band before comparisons.
                        for (int warmup = 0; warmup < 12; warmup++) { frame(terrain, camera); }
                        GpuBoardTestUi.capture(new File(output, "terrain-" + family + "-iso.png"));
                        long[] times = new long[12];
                        for (int sample = 0; sample < times.length; sample++) {
                            Gdx.gl.glFinish();
                            long start = System.nanoTime();
                            frame(terrain, camera);
                            Gdx.gl.glFinish();
                            times[sample] = System.nanoTime() - start;
                        }
                        Arrays.sort(times);
                        timing.append(family).append(": ").append(times[times.length / 2] / 1_000_000.0).append(" ms\n");
                        Pixmap before = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        terrain.animate(.7f, List.of());
                        frame(terrain, camera);
                        Pixmap after = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        int changed = 0;
                        for (int y = 0; y < before.getHeight(); y += 3) {
                            for (int x = 0; x < before.getWidth(); x += 3) {
                                if (before.getPixel(x, y) != after.getPixel(x, y)) { changed++; }
                            }
                        }
                        before.dispose(); after.dispose();
                        assertTrue(changed > 100, "Water and cover must animate: " + family);
                        checkMaterialToggle(terrain, camera);
                        if (family == BoardScene.Surface.GRASS) {
                            checkWind(terrain, camera);
                            checkCoverCache(scene);
                        }
                        camera.setIsometric(false);
                        camera.center(BoardGeometry.center(new Coords(4, 4), 3));
                        terrain.renderShadows(camera.camera, List.of());
                        frame(terrain, camera);
                        GpuBoardTestUi.capture(new File(output, "terrain-" + family + "-top.png"));
                        if (family == BoardScene.Surface.SAND) {
                            var shadows = terrain.environment().shadowMap;
                            terrain.environment().shadowMap = null;
                            try {
                                frame(terrain, camera);
                                GpuBoardTestUi.capture(new File(output, "terrain-SAND-top-no-shadows.png"));
                            } finally { terrain.environment().shadowMap = shadows; }
                        }
                        if (family == BoardScene.Surface.GRASS) {
                            terrain.setAtmosphere(new BoardAtmosphere.Lighting(new Vector3(-.8f, .45f, -.35f).nor(),
                                  new Color(1, .77f, .53f, 1), new Color(.24f, .27f, .33f, 1), Color.BLACK,
                                  daylight.sky(), daylight.horizon(), Color.WHITE, 1, 1, true));
                            terrain.renderShadows(camera.camera, List.of());
                            frame(terrain, camera);
                            GpuBoardTestUi.capture(new File(output, "terrain-GRASS-warm-top.png"));
                            terrain.setAtmosphere(daylight);
                        }
                        if (family == BoardScene.Surface.SAND || family == BoardScene.Surface.ROCK) {
                            terrain.setNormalMaps(false);
                            frame(terrain, camera);
                            GpuBoardTestUi.capture(new File(output, "terrain-" + family + "-top-no-relief.png"));
                            terrain.setNormalMaps(true);
                        }
                    }
                    Files.writeString(new File(output, "terrain-rendering-timing.txt").toPath(), timing);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain relief rendering", failure.get()); }
    }

    private static void frame(GpuTerrain terrain, BoardCamera camera) {
        ScreenUtils.clear(.2f, .26f, .31f, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
    }

    private static void checkWind(GpuTerrain terrain, BoardCamera camera) {
        terrain.setWind(BoardAtmosphere.Effects.NONE);
        frame(terrain, camera);
        Pixmap calm = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        terrain.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, .85f, 115));
        frame(terrain, camera);
        Pixmap windy = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        try {
            Vector3 center = camera.camera.project(BoardGeometry.center(new Coords(4, 4), 3));
            int changed = 0;
            for (int y = -24; y <= 24; y++) {
                for (int x = -24; x <= 24; x++) {
                    int px = (int) center.x + x, py = (int) center.y + y;
                    if (calm.getPixel(px, py) != windy.getPixel(px, py)) { changed++; }
                }
            }
            assertTrue(changed > 100, "Wind must change the grass itself, independently of water");
        } finally {
            calm.dispose(); windy.dispose();
        }
    }

    private static void checkMaterialToggle(GpuTerrain terrain, BoardCamera camera) {
        int[] mapped = groundSamples(terrain, camera);
        terrain.setNormalMaps(false);
        int[] flat = groundSamples(terrain, camera);
        terrain.setNormalMaps(true);
        assertArrayEquals(mapped, groundSamples(terrain, camera), "Material toggle restores identical lighting");
        int changed = 0;
        for (int i = 0; i < mapped.length; i++) { if (mapped[i] != flat[i]) { changed++; } }
        assertTrue(changed > 20, "Normal/height maps must change the actual ground surface");
    }

    private static int[] groundSamples(GpuTerrain terrain, BoardCamera camera) {
        frame(terrain, camera);
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        try {
            Vector3 center = camera.camera.project(BoardGeometry.center(new Coords(4, 4), 3));
            int[] result = new int[49 * 49];
            for (int y = -24; y <= 24; y++) {
                for (int x = -24; x <= 24; x++) {
                    result[(y + 24) * 49 + x + 24] = pixels.getPixel((int) center.x + x, (int) center.y + y);
                }
            }
            return result;
        } finally { pixels.dispose(); }
    }

    private static void checkCoverCache(BoardScene source) {
        BoardScene.Pixels pixels = source.tiles().getFirst().ground();
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.camera.zoom = .26f;
        camera.center(BoardGeometry.center(new Coords(0, 0), 0));
        GpuGroundCover cover = new GpuGroundCover();
        try {
            BoardScene first = coverScene(pixels, null, 0);
            var model = cover.visible(first, camera.camera, first.tiles()).getFirst();
            BoardScene tactical = coverScene(pixels, pixels, 0), edited = coverScene(pixels, pixels, 1);
            assertSame(model, cover.visible(tactical, camera.camera, tactical.tiles()).getFirst(),
                  "Tactical-only snapshot replacement must retain grass GPU resources");
            assertNotSame(model, cover.visible(edited, camera.camera, edited.tiles()).getFirst(),
                  "A real terrain edit must replace the derived cover");
        } finally { cover.dispose(); }
    }

    private static BoardScene coverScene(BoardScene.Pixels pixels, BoardScene.Pixels tactical, int elevation) {
        var tile = new BoardScene.Tile(new Coords(0, 0), elevation, -1, false, 0, BoardScene.Surface.GRASS, pixels,
              null, null, null, tactical, List.of(), List.of(), BoardLiquid.NONE, null, true);
        return new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene scene(BoardScene.Surface family) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff778966); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                int level = x > 1 && x < 7 && y < 6 ? y < 3 ? 5 : y < 5 ? 3 : 1 : 0;
                boolean water = (x >= 6 && y >= 4) || y >= 6;
                if (water) { level = 0; }
                int depth = water ? x >= 7 ? 2 : 1 : -1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 9, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
