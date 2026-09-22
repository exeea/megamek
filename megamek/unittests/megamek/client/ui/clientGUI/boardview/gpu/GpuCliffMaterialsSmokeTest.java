/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native rendering of full-resolution materials, relief toggling and all six cliff orientations. */
@Tag("on-demand")
class GpuCliffMaterialsSmokeTest {
    private static final Coords RAISED = new Coords(3, 3);

    @Test
    void lightsEveryCliffOrientationAndPreservesMaterialOwnership() throws Exception {
        BoardScene review = reviewScene();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkMaterials();
                    checkRendering(review);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Cliff material rendering", failure.get()); }
    }

    private static void checkMaterials() {
        GpuAssets assets = new GpuAssets();
        try {
            for (BoardScene.Surface family : BoardScene.Surface.values()) {
                GpuAssets.Cliff maps = assets.cliff(family.wall);
                assertNotNull(maps.normal(), family.name());
                assertNotNull(maps.surface(), family.name());
                assertSame(maps, assets.cliff(family.wall), "All hexes borrow one material set");
                for (Texture texture : List.of(maps.color(), maps.normal(), maps.surface())) {
                    assertEquals(1024, texture.getWidth());
                    assertEquals(1024, texture.getHeight());
                    assertEquals(Texture.TextureWrap.Repeat, texture.getUWrap());
                    assertEquals(Texture.TextureFilter.MipMapLinearLinear, texture.getMinFilter());
                }
            }
            assertNull(assets.cliff("terrain/water_bed").normal(), "Older/custom art retains its color fallback");
        } finally {
            assets.dispose();
        }
    }

    private static void checkRendering(BoardScene review) throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        GpuTerrain terrain = new GpuTerrain();
        try {
            for (BoardScene.Surface family : BoardScene.Surface.values()) {
                terrain.update(scene(family));
                for (int edge = 0; edge < 6; edge++) {
                    Vector3 a = BoardGeometry.corner(RAISED, 6, edge);
                    Vector3 b = BoardGeometry.corner(RAISED, 6, edge + 1);
                    Vector3 along = b.cpy().sub(a).nor();
                    Vector3 outward = along.cpy().crs(Vector3.Z);
                    Vector3 target = a.cpy().lerp(b, .5f);
                    target.z = 3 * BoardGeometry.LEVEL;
                    OrthographicCamera camera = new OrthographicCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.zoom = .09f;
                    camera.near = 1;
                    camera.far = 3000;
                    camera.up.set(Vector3.Z);
                    camera.position.set(target).mulAdd(outward, 700).mulAdd(along, 160).add(0, 0, 100);
                    camera.lookAt(target);
                    camera.update();
                    Vector3 light = outward.cpy().mulAdd(along, .7f).add(0, 0, .65f).nor().scl(-1);
                    terrain.setAtmosphere(new BoardAtmosphere.Lighting(light, new Color(.85f, .82f, .76f, 1),
                          new Color(.23f, .25f, .28f, 1), Color.BLACK, Color.BLACK, Color.BLACK, Color.WHITE, 1, 1, true));
                    terrain.renderShadows(camera, List.of());
                    terrain.setNormalMaps(false);
                    int[] flat = samples(terrain, camera, a, b);
                    if (edge == 4) { GpuBoardTestUi.capture(new File(output, "cliff-" + family + "-flat.png")); }
                    terrain.setNormalMaps(true);
                    int[] relief = samples(terrain, camera, a, b);
                    double difference = difference(flat, relief);
                    assertTrue(difference > .5, family + " edge " + edge + " must respond to mapped relief: " + difference);
                    if (edge == 4) {
                        GpuBoardTestUi.capture(new File(output, "cliff-" + family + "-relief.png"));
                        terrain.setWetness(1);
                        int[] wet = samples(terrain, camera, a, b);
                        if (family != BoardScene.Surface.SNOW) {
                            assertTrue(difference(wet, relief) > 1, "Exposed cliffs respond to rain: " + family);
                        } else {
                            assertEquals(0, difference(wet, relief), "Snow never takes the liquid rain film");
                        }
                        GpuBoardTestUi.capture(new File(output, "cliff-" + family + "-wet.png"));
                        terrain.setWetness(0);
                    }
                }
            }
            captureOverview(terrain, review, output);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
        }
    }

    /** Shipped ground art and connected multi-hex cliffs, alongside the isolated material probes. */
    private static BoardScene reviewScene() throws Exception {
        Hex[] hexes = new Hex[11 * 10];
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 11; x++) {
                int level = x >= 2 && x <= 8 && y <= 6 ? y < 4 ? 5 : x < 7 ? 3 : 0 : 0;
                Hex hex = new Hex(level);
                hex.setTheme("desert");
                hexes[y * 11 + x] = hex;
            }
        }
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(11, 10, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            return fixture.source.takeFrame().scene();
        }
    }

    private static void captureOverview(GpuTerrain terrain, BoardScene scene, File output) throws Exception {
        terrain.update(scene);
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.tilt(10);
        camera.camera.zoom = .32f;
        camera.center(BoardGeometry.center(new Coords(5, 5), 2));
        terrain.setAtmosphere(new BoardAtmosphere.Lighting(new Vector3(-.9f, .5f, -.65f).nor(),
              new Color(.9f, .84f, .75f, 1), new Color(.25f, .28f, .32f, 1),
              Color.BLACK, Color.BLACK, Color.BLACK, Color.WHITE, 1, 1, true));
        terrain.renderShadows(camera.camera, List.of());
        for (boolean relief : new boolean[] { false, true }) {
            terrain.setNormalMaps(relief);
            ScreenUtils.clear(.16f, .20f, .24f, 1, true);
            terrain.render(camera.camera, false);
            terrain.renderTransparent(camera.camera);
            GpuBoardTestUi.capture(new File(output, "cliff-board-" + (relief ? "relief" : "flat") + ".png"));
        }
    }

    private static int[] samples(GpuTerrain terrain, OrthographicCamera camera, Vector3 a, Vector3 b) {
        ScreenUtils.clear(.06f, .07f, .09f, 1, true);
        terrain.render(camera, false);
        terrain.renderTransparent(camera);
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int[] samples = new int[121];
            for (int y = 0; y <= 10; y++) {
                for (int x = 0; x <= 10; x++) {
                    Vector3 point = a.cpy().lerp(b, .2f + x * .06f);
                    point.z = BoardGeometry.LEVEL * (2 + y * .2f);
                    Vector3 screen = camera.project(point);
                    int px = (int) (screen.x * image.getWidth() / Gdx.graphics.getWidth());
                    int py = (int) (screen.y * image.getHeight() / Gdx.graphics.getHeight());
                    samples[y * 11 + x] = image.getPixel(px, py);
                }
            }
            return samples;
        } finally {
            image.dispose();
        }
    }

    private static double difference(int[] a, int[] b) {
        long total = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift : new int[] { 8, 16, 24 }) {
                total += Math.abs(((a[i] >>> shift) & 255) - ((b[i] >>> shift) & 255));
            }
        }
        return total / (a.length * 3.0);
    }

    private static BoardScene scene(BoardScene.Surface family) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        int color = switch (family) {
            case GRASS -> 0xff7c9262;
            case DIRT -> 0xff9a8065;
            case SAND -> 0xffcdb18a;
            case ROCK -> 0xff9a9992;
            case CONCRETE -> 0xff999996;
            case SNOW -> 0xffdedee0;
        };
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) { image.setRGB(x, y, color); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, coords.equals(RAISED) ? 6 : 0, -1, false, 0,
                      family, pixels, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
