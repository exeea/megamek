/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every range mode's GPU walls in both cameras; the map edge is no range boundary. */
@Tag("on-demand")
class GpuRangeWallSmokeTest {
    private static final String[] MODES = { "sensor", "air-sensor", "visual", "dark-visual" };

    private static Board board() {
        int[] levels = { 0, 0, 2, 2, 0, 0, -1, -1, 0 };
        Hex[] hexes = new Hex[81];
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                Hex hex = new Hex(x < 3 ? levels[y] : 0);
                if (x < 2 && (y == 4 || y == 5)) {
                    hex.addTerrain(new Terrain(Terrains.WATER, 4));
                    if (y == 5) {
                        hex.addTerrain(new Terrain(Terrains.ICE, 1));
                    }
                }
                hexes[y * 9 + x] = hex;
            }
        }
        return new Board(9, 9, hexes);
    }

    /** A column of ranges along the map edge, whose outside sides face off the map, and one inside it. */
    private static List<TacticalMark> markers(GpuBoardFixture fixture, int mode) {
        List<TacticalMark> marks = new ArrayList<>();
        for (int x : new int[] { 0, 4 }) {
            for (int y = 0; y < 9; y++) {
                marks.add(new TacticalMark.SensorRange(new Coords(x, y), x == 0 ? 48 : 6, mode));
            }
        }
        fixture.view.addMarks(marks);
        fixture.source.refresh();
        return marks;
    }

    @Test
    void dashesScrollInBothPresentationsAndZeroSpeedRemainsStatic() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(() -> markers(fixture, TacticalStyle.VISUAL));
            BoardScene scene = fixture.source.takeFrame().scene();
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTactical moving = new GpuTactical(4f), stopped = new GpuTactical(0f);
                    GpuTactical splitFrames = new GpuTactical(4f), reverse = new GpuTactical(-4f);
                    var controls = List.of(moving, stopped, splitFrames, reverse);
                    try {
                        controls.forEach(control -> control.update(scene));
                        // GpuBattleView's warm-up: the walls and dashed outlines then draw without linking a program.
                        moving.prepare();
                        int programs = GpuShaderLoadSmokeTest.programs(moving);
                        long builds = moving.builds();
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.center(BoardGeometry.center(new Coords(1, 4), 0));
                        camera.zoom(0.65f);
                        // Flat on the surface in the Tactical View (tilt 0), upright in 3D at a steep and a low angle.
                        for (float tilt : new float[] { 0, 30, 55 }) {
                            boolean flat = tilt == 0;
                            camera.setIsometric(false);
                            camera.orbit(45, tilt);
                            byte[] initial = draw(moving, camera.camera, flat, 0);
                            byte[] advanced = draw(moving, camera.camera, flat, 0.25f);
                            int changes = 0;
                            for (int i = 0; i < initial.length; i++) {
                                if (initial[i] != advanced[i]) {
                                    changes++;
                                }
                            }
                            assertTrue(changes > 100, "Dashes must visibly move at tilt " + tilt);
                            assertArrayEquals(initial, draw(stopped, camera.camera, flat, 10),
                                  "0f must keep the static pattern");
                            draw(splitFrames, camera.camera, flat, 0.125f);
                            assertArrayEquals(advanced, draw(splitFrames, camera.camera, flat, 0.125f),
                                  "Travel must depend on elapsed time rather than frame count");
                            assertArrayEquals(advanced, draw(reverse, camera.camera, flat, 0.75f),
                                  "Negative speed must reverse travel along the same dash pattern");
                            assertArrayEquals(initial, draw(moving, camera.camera, flat, 0.75f),
                                  "The pattern repeats seamlessly");
                            draw(splitFrames, camera.camera, flat, 0.75f);
                            draw(reverse, camera.camera, flat, 0.25f);
                            moving.update(scene);
                            assertEquals(builds, moving.builds(), "Animation and camera changes reuse the meshes");
                        }
                        byte[] advanced = draw(moving, camera.camera, false, 0.25f);
                        moving.update(new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(),
                              List.of(), List.of(), -1, "", List.of(), null, List.of(), List.of(), List.of(), BoardTactical.EMPTY));
                        moving.update(scene);
                        assertArrayEquals(advanced, draw(moving, camera.camera, false, 0),
                              "Rebuilding must retain the dash phase");
                        assertEquals(programs, GpuShaderLoadSmokeTest.programs(moving), "The range walls linked a program");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        controls.forEach(GpuTactical::dispose);
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static byte[] draw(GpuTactical control, Camera camera, boolean flat, float seconds) {
        ScreenUtils.clear(0, 0, 0, 1, true);
        control.render(camera, seconds, flat);
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            byte[] pixels = new byte[image.getPixels().remaining()];
            image.getPixels().get(pixels);
            return pixels;
        } finally {
            image.dispose();
        }
    }

    @Test
    void comparesEveryRangeModeInBothGpuCameras() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "range-walls");
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board())) {
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int tick;
                private int comparison;
                private List<TacticalMark> marks = List.of();
                private GpuTactical probe;
                private long builds;

                private void selectComparison() throws Exception {
                    int mode = comparison / 2;
                    SwingUtilities.invokeAndWait(() -> {
                        fixture.view.removeMarks(marks);
                        marks = markers(fixture, mode);
                    });
                    // The isometric 3D view, or the Tactical View with its flat walls ("-top").
                    setTacticalView(comparison % 2 == 1);
                    var scene = fixture.source.takeFrame().scene();
                    assertEquals(mode < TacticalStyle.VISUAL ? 0 : 9, scene.tactical().walls().size());
                    probe.update(scene);
                    builds = probe.builds();
                }

                @Override
                public void render() {
                    try {
                        super.render();
                        if (GpuBoardTestUi.loading(this)) { return; }
                        tick++;
                        if (tick == 1) {
                            probe = new GpuTactical();
                            boardCamera.center(BoardGeometry.center(new Coords(1, 4), 0));
                            boardCamera.zoom(0.65f);
                            selectComparison();
                        } else if (tick % 5 == 0) {
                            probe.update(fixture.source.takeFrame().scene());
                            assertEquals(builds, probe.builds(), "Camera changes must reuse the range meshes");
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            String name = MODES[comparison / 2] + (comparison % 2 == 0 ? "-isometric.png" : "-top.png");
                            GpuBoardTestUi.capture(new File(output, name));
                            if (++comparison == MODES.length * 2) {
                                Gdx.app.exit();
                            } else {
                                selectComparison();
                            }
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    if (probe != null) {
                        probe.dispose();
                    }
                    super.dispose();
                }
            }, GpuBoardWindow.configuration(false));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }
}
