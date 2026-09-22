/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Both cliff-edge patterns respond to moving light through the existing ground normal atlas. */
@Tag("on-demand")
class GpuRimMaterialSmokeTest {
    @Test
    void rendersTwoAndThreeLevelCliffsWithTheShippedGroundArtwork() throws Exception {
        Hex[] hexes = new Hex[7 * 3];
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 7; x++) {
                Hex hex = new Hex(y == 1 && x == 2 ? 2 : y == 1 && x == 4 ? 3 : 0);
                hex.setTheme("grass");
                hexes[y * 7 + x] = hex;
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(7, 3, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    try {
                        terrain.update(scene);
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.fit(scene);
                        camera.camera.zoom *= 0.85f;
                        camera.center(BoardGeometry.center(new Coords(3, 1), 1));
                        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                        assertTrue(output.isDirectory() || output.mkdirs());
                        for (boolean isometric : new boolean[] { false, true }) {
                            camera.setIsometric(isometric);
                            for (int direction = 0; direction < 2; direction++) {
                                terrain.setAtmosphere(new BoardAtmosphere.Lighting(
                                      new Vector3(direction == 0 ? -1 : 1, -0.4f, -0.7f).nor(),
                                      new Color(0.75f, 0.71f, 0.65f, 1), new Color(0.3f, 0.32f, 0.36f, 1),
                                      Color.BLACK, Color.BLACK, Color.BLACK, Color.WHITE, 1, 1, true));
                                terrain.renderShadows(camera.camera, List.of());
                                samples(terrain, camera, List.of());
                                GpuBoardTestUi.capture(new File(output, "cliff-edges-" + (isometric ? "iso" : "top")
                                      + "-light-" + direction + ".png"));
                            }
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        terrain.dispose();
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Cliff-edge artwork rendering", failure.get()); }
    }

    @Test
    void bothCliffPatternsHaveReliefUnderOpposingLightsWithoutExtraDraws() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkRendering(2);
                    checkRendering(3);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Rim material rendering", failure.get()); }
    }

    private static void checkRendering(int drop) throws Exception {
        Coords raised = new Coords(3, 3);
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        BufferedImage flat = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) {
                image.setRGB(x, y, 0xffa0a0a0);
                flat.setRGB(x, y, 0xff8080ff);
            }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image), normals = new BoardScene.Pixels(flat);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, coords.equals(raised) ? drop : 0, -1, false, 0,
                      BoardScene.Surface.GRASS, pixels, normals, null, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
        GpuTerrain terrain = new GpuTerrain();
        try {
            terrain.update(scene);
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.camera.zoom = 0.18f;
            camera.center(BoardGeometry.center(raised, drop));
            List<Vector3> probes = new ArrayList<>();
            for (int edge = 0; edge < 6; edge++) {
                Vector3 a = BoardGeometry.corner(raised, drop, edge), b = BoardGeometry.corner(raised, drop, edge + 1);
                Vector3 along = b.cpy().sub(a).nor(), inward = new Vector3(-along.y, along.x, 0);
                for (int u = 2; u <= 8; u++) {
                    for (int depth = 3; depth <= 21; depth += 2) {
                        probes.add(a.cpy().lerp(b, u / 10f).mulAdd(inward, depth));
                    }
                }
            }
            probes.add(BoardGeometry.center(raised, drop));
            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(output.isDirectory() || output.mkdirs());
            for (boolean isometric : new boolean[] { false, true }) {
                camera.setIsometric(isometric);
                int centreIndex = probes.size() - 1;
                int[][] light = new int[2][];
                int[][] flatLight = new int[2][];
                for (int direction = 0; direction < 2; direction++) {
                    Vector3 direction3 = new Vector3(direction == 0 ? -1 : 1, 0, -0.5f).nor();
                    terrain.setAtmosphere(new BoardAtmosphere.Lighting(direction3, new Color(0.7f, 0.7f, 0.7f, 1),
                          new Color(0.25f, 0.25f, 0.25f, 1), Color.BLACK, Color.BLACK, Color.BLACK, Color.WHITE, 1, 1, true));
                    terrain.renderShadows(camera.camera, List.of());
                    GLProfiler profiler = new GLProfiler(Gdx.graphics);
                    profiler.enable();
                    try {
                        terrain.setNormalMaps(false);
                        flatLight[direction] = samples(terrain, camera, probes);
                        int draws = profiler.getDrawCalls();
                        profiler.reset();
                        terrain.setNormalMaps(true);
                        light[direction] = samples(terrain, camera, probes);
                        assertEquals(draws, profiler.getDrawCalls(), "Relief uses the existing terrain draws");
                    } finally {
                        profiler.disable();
                    }
                    GpuBoardTestUi.capture(new File(output, "rim-drop-" + drop + "-" + (isometric ? "iso" : "top")
                          + "-light-" + direction + ".png"));
                }
                int relit = 0;
                float reliefChange = 0, flatChange = 0;
                for (int index = 0; index < centreIndex; index++) {
                    float change = Math.abs(ratio(light[0], index, centreIndex) - ratio(light[1], index, centreIndex));
                    reliefChange += change;
                    flatChange += Math.abs(ratio(flatLight[0], index, centreIndex) - ratio(flatLight[1], index, centreIndex));
                    if (change > 0.06f) { relit++; }
                }
                assertTrue(relit > 20, "Drop " + drop + " must visibly react to light: " + relit + " probes");
                assertTrue(reliefChange > flatChange + 5, "Normals must relight the pattern beyond flat-face lighting");
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
        }
    }

    /** Red-channel sample at a probe, relative to the untouched centre of the raised tile. */
    private static float ratio(int[] samples, int index, int centreIndex) {
        return (samples[index] >>> 24) / (float) (samples[centreIndex] >>> 24);
    }

    private static int[] samples(GpuTerrain terrain, BoardCamera camera, List<Vector3> probes) {
        ScreenUtils.clear(0.03f, 0.045f, 0.06f, 1, true);
        terrain.render(camera.camera, false);
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int[] result = new int[probes.size()];
            for (int index = 0; index < probes.size(); index++) {
                Vector3 screen = camera.camera.project(probes.get(index).cpy());
                int x = (int) (screen.x * image.getWidth() / Gdx.graphics.getWidth());
                int y = (int) (screen.y * image.getHeight() / Gdx.graphics.getHeight());
                result[index] = image.getPixel(x, y);
            }
            return result;
        } finally {
            image.dispose();
        }
    }
}
