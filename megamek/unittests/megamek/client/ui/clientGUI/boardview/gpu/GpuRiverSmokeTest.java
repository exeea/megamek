/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native visual review of a winding river, waterfalls, tree silhouettes and low rubble. */
@Tag("on-demand")
class GpuRiverSmokeTest {
    @Test
    void rendersClosedRiverbanksAtTheBoardBoundary() throws Exception {
        Hex[] hexes = new Hex[10 * 34];
        for (int y = 0; y < 34; y++) {
            for (int x = 0; x < 10; x++) {
                Hex hex = new Hex(0);
                if (x == 7 && y >= 31) {
                    hex.addTerrain(new Terrain(Terrains.WATER, 1));
                }
                hexes[y * 10 + x] = hex;
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(10, 34, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            new Lwjgl3Application(new ApplicationAdapter() {
                GpuTerrain terrain;
                GpuSceneTestOutput outputFrame;
                BoardCamera camera;

                @Override
                public void create() {
                    terrain = new GpuTerrain();
                    outputFrame = new GpuSceneTestOutput();
                    terrain.update(scene);
                    camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.center(BoardGeometry.center(new Coords(7, 33), 0));
                    camera.zoom(0.2f);
                }

                @Override
                public void render() {
                    try {
                        terrain.renderShadows(List.of());
                        outputFrame.begin(0.035f, 0.055f, 0.075f, 1);
                        terrain.render(camera.camera);
                        terrain.renderTransparent(camera.camera);
                        outputFrame.display();
                        File output = new File(System.getProperty("megamek.gpu.screenshots"));
                        GpuBoardTestUi.capture(new File(output, "river-board-edge.png"));
                        Pixmap pixels = ScreenUtils.getFrameBufferPixmap(0, 0,
                              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                        try {
                            int background = pixels.getPixel(0, 0);
                            Vector3 a = BoardGeometry.corner(new Coords(7, 33), -0.5f, 4);
                            Vector3 b = BoardGeometry.corner(new Coords(7, 33), -0.5f, 5);
                            for (int sample = 1; sample < 100; sample++) {
                                Vector3 screen = camera.camera.project(new Vector3(a).lerp(b, sample / 100f));
                                assertNotEquals(background, pixels.getPixel((int) screen.x, (int) screen.y),
                                      "The exposed riverbank wall must not have holes: sample " + sample);
                            }
                        } finally {
                            pixels.dispose();
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    outputFrame.close();
                    terrain.dispose();
                }
            }, GpuBoardWindow.configuration(false));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    @Test
    void rendersContinuousChannelsAnimatedDropsAndNatureVariants() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                List<Coords> river = new ArrayList<>();
                Coords next = new Coords(2, 0);
                river.add(next);
                for (int direction : new int[] { 3, 3, 3, 2, 2, 2, 2, 3, 3, 3 }) {
                    next = next.translated(direction);
                    river.add(next);
                }
                Hex[] hexes = new Hex[100];
                for (int y = 0; y < 10; y++) {
                    for (int x = 0; x < 10; x++) {
                        // Drops of two levels or less are graded slopes; a waterfall needs three (docs/gpu-water.md).
                        Hex hex = new Hex(y < 3 ? 3 : 0);
                        if (river.contains(new Coords(x, y))) {
                            hex.addTerrain(new Terrain(Terrains.WATER, 1));
                        }
                        if (x == 2 && y == 1) {
                            hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 18));
                            hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 0));
                            hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
                        }
                        if (x == 1 && y == 1 || x == 3 && y == 0) {
                            hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 18));
                        }
                        if ((x == 0 || x == 8) && y % 2 == 1) {
                            hex.addTerrain(new Terrain(Terrains.WOODS, x == 0 && y <= 3 ? 1 : 2));
                            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                            if (x == 0 && y == 1) {
                                hex.setTheme("desert");
                            }
                            if (x == 8 && y <= 3) {
                                hex.setTheme("snow");
                                hex.addTerrain(new Terrain(Terrains.SNOW, 1));
                            }
                        }
                        if (y == 1 && x >= 4 && x <= 6 || x == 8 && y == 4) {
                            hex.addTerrain(new Terrain(Terrains.RUBBLE, 4));
                            if (x == 6) {
                                hex.setTheme("snow");
                            }
                        }
                        hexes[y * 10 + x] = hex;
                    }
                }
                fixture.game.setBoard(new Board(10, 10, hexes));
                BoardSceneryLayouts.convert(fixture.game.getBoard());
                fixture.source.refresh();
            });
            BoardScene scene = fixture.source.takeFrame().scene();
            new Lwjgl3Application(new ApplicationAdapter() {
                GpuTerrain terrain;
                GpuSceneTestOutput outputFrame;
                BoardCamera camera;
                int frames;
                int fallPixels;

                @Override
                public void create() {
                    terrain = new GpuTerrain();
                    outputFrame = new GpuSceneTestOutput();
                    terrain.update(scene);
                    camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.fit(scene);
                }

                @Override
                public void render() {
                    try {
                        terrain.animate(1f / 12, List.of());
                        terrain.renderShadows(List.of());
                        outputFrame.begin(0.035f, 0.055f, 0.075f, 1);
                        terrain.render(camera.camera);
                        terrain.renderTransparent(camera.camera);
                        outputFrame.display();
                        File output = new File(System.getProperty("megamek.gpu.screenshots"));
                        if (++frames == 3) {
                            GpuBoardTestUi.capture(new File(output, "river-channels-top.png"));
                            camera.setIsometric(true);
                            camera.fit(scene);
                        } else if (frames == 6) {
                            GpuBoardTestUi.capture(new File(output, "river-channels-isometric.png"));
                            camera.center(BoardGeometry.center(new Coords(2, 3), 2));
                            camera.zoom(0.4f);
                        } else if (frames == 9) {
                            GpuBoardTestUi.capture(new File(output, "waterfall-closeup.png"));
                            fallPixels = waterfallPixels(camera, scene);
                            outputFrame.begin(0.035f, 0.055f, 0.075f, 1);
                            terrain.render(camera.camera);
                            terrain.renderTransparent(camera.camera);
                            outputFrame.display();
                            assertEquals(fallPixels, waterfallPixels(camera, scene),
                                  "A paused waterfall keeps the same sheet pixels");
                        } else if (frames == 13) {
                            GpuBoardTestUi.capture(new File(output, "waterfall-flow.png"));
                            assertNotEquals(fallPixels, waterfallPixels(camera, scene), "The visible waterfall sheet must animate");
                            camera.center(BoardGeometry.center(new Coords(6, 2), 3));
                        } else if (frames == 16) {
                            GpuBoardTestUi.capture(new File(output, "nature-and-rubble.png"));
                            camera.center(BoardGeometry.center(new Coords(2, 1), 3));
                        } else if (frames == 19) {
                            GpuBoardTestUi.capture(new File(output, "bridge-road-isometric.png"));
                            camera.setIsometric(false);
                        } else if (frames == 22) {
                            GpuBoardTestUi.capture(new File(output, "bridge-road-top.png"));
                            camera.setIsometric(true);
                            camera.center(BoardGeometry.center(new Coords(0, 1), 3));
                        } else if (frames == 25) {
                            GpuBoardTestUi.capture(new File(output, "woods-light-desert.png"));
                            camera.center(BoardGeometry.center(new Coords(0, 3), 2));
                        } else if (frames == 28) {
                            GpuBoardTestUi.capture(new File(output, "woods-light-grass.png"));
                            camera.center(BoardGeometry.center(new Coords(2, 3), 2));
                            camera.orbit(180, 0);
                        } else if (frames == 31) {
                            GpuBoardTestUi.capture(new File(output, "waterfall-from-behind.png"));
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    outputFrame.close();
                    terrain.dispose();
                }
            }, GpuBoardWindow.configuration(false));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static int waterfallPixels(BoardCamera camera, BoardScene scene) {
        BoardSurface surface = new BoardSurface(scene, scene.tile(new Coords(2, 2)));
        Vector3[][] sheet = GpuWaterfall.grid(surface, surface.waterfalls.getFirst());
        Pixmap pixels = ScreenUtils.getFrameBufferPixmap(0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            // The sheet is thrown away from its cliff. The old vertical Side-plane probe could read the
            // stationary rock behind it; sample actual interior sheet triangles over its falling length.
            List<Integer> colors = new ArrayList<>();
            for (int column = 2; column + 3 < sheet.length; column++) {
                for (int row = 2; row + 3 < sheet[column].length; row++) {
                    Vector3 point = new Vector3(sheet[column][row]).add(sheet[column + 1][row])
                          .add(sheet[column][row + 1]).scl(1 / 3f);
                    Vector3 screen = camera.camera.project(point);
                    int x = Math.round(screen.x * pixels.getWidth() / Gdx.graphics.getWidth());
                    int y = Math.round(screen.y * pixels.getHeight() / Gdx.graphics.getHeight());
                    assertTrue(x >= 0 && x < pixels.getWidth() && y >= 0 && y < pixels.getHeight(),
                          "Waterfall sample must remain in the framebuffer: " + x + ", " + y);
                    colors.add(pixels.getPixel(x, y));
                }
            }
            assertTrue(!colors.isEmpty(), "The waterfall probe must sample sheet triangles");
            return colors.hashCode();
        } finally {
            pixels.dispose();
        }
    }
}
