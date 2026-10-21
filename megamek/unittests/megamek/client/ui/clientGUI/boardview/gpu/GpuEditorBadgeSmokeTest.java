/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.BasementType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The editor's per-hex terrain badges, including stacked flags, in both native cameras. */
@Tag("on-demand")
class GpuEditorBadgeSmokeTest {
    private static final int WIDTH = 8, HEIGHT = 7;
    private static final List<Coords> CONNECTED = List.of(new Coords(2, 2), new Coords(3, 2),
          new Coords(2, 3), new Coords(3, 3));
    private static final List<BasementType> BASEMENTS = List.of(BasementType.ONE_DEEP_NORMAL,
          BasementType.TWO_DEEP_HEAD, BasementType.UNKNOWN, BasementType.NONE);
    private static final Coords DETACHED = new Coords(5, 3);
    private static final Coords COLLAPSED = new Coords(5, 1);
    private static final Coords BLACK_ICE = new Coords(1, 3);
    private static final Coords METAL_ONE = new Coords(3, 5);
    private static final Coords METAL_TWO = new Coords(5, 5);

    @Test
    void capturesLocalAndStackedTerrainBadges() throws Exception {
        var preferences = GUIPreferences.getInstance();
        var graphics = preferences.getBoardGraphics();
        float scale = preferences.getBoardUiScale();
        var setup = new FutureTask<GpuMapSource>(() -> {
            preferences.setBoardGraphics(BoardGraphics.ENHANCED);
            preferences.setBoardUiScale(1);
            var editor = new BoardEditorSession();
            editor.game().setBoard(board());
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "editor-badges");
        assertTrue(output.isDirectory() || output.mkdirs());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        config.setInitialVisible(false);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var view = new GpuBattleView(source);
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        view.setTacticalView(false);
                        var camera = view.boardCamera;
                        camera.setPerspective(false);
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = BoardGeometry.width() / 180;
                            camera.center(BoardGeometry.center(new Coords(3, 3), 2));
                            camera.update();
                            GpuBoardTestUi.present(view);
                            for (int i = 0; i < 4; i++) { view.render(); }
                            GpuBoardTestUi.capture(new File(output, "editor-badges-" + (oblique ? "oblique" : "top") + ".png"));
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally {
                        view.dispose();
                        Gdx.app.exit();
                    }
                }
            }, config);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                source.close();
                preferences.setBoardGraphics(graphics);
                preferences.setBoardUiScale(scale);
            });
        }
        if (failure.get() != null) { throw new AssertionError("Editor terrain badge review", failure.get()); }
    }

    private static Board board() {
        Hex[] hexes = new Hex[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                hexes[y * WIDTH + x] = new Hex(0, "pavement:1", "", new Coords(x, y));
            }
        }
        for (int i = 0; i < CONNECTED.size(); i++) {
            var at = CONNECTED.get(i);
            int exits = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (CONNECTED.contains(at.translated(direction))) { exits |= 1 << direction; }
            }
            building(hexes, at, BASEMENTS.get(i), exits);
        }
        building(hexes, DETACHED, BasementType.ONE_DEEP_NORMAL, 0);
        var stacked = hex(hexes, DETACHED);
        stacked.addTerrain(new Terrain(Terrains.BLACK_ICE, 1));
        stacked.addTerrain(new Terrain(Terrains.BLDG_BASE_COLLAPSED, 1));
        stacked.addTerrain(new Terrain(Terrains.METAL_CONTENT, 2));
        building(hexes, COLLAPSED, BasementType.NONE, 0);
        hex(hexes, COLLAPSED).addTerrain(new Terrain(Terrains.BLDG_BASE_COLLAPSED, 1));
        hex(hexes, BLACK_ICE).addTerrain(new Terrain(Terrains.BLACK_ICE, 1));
        hex(hexes, METAL_ONE).addTerrain(new Terrain(Terrains.METAL_CONTENT, 1));
        hex(hexes, METAL_TWO).addTerrain(new Terrain(Terrains.METAL_CONTENT, 2));
        var board = new Board();
        board.newData(WIDTH, HEIGHT, hexes, null);
        var connected = board.getBuildingAt(CONNECTED.getFirst());
        assertNotNull(connected);
        for (int i = 0; i < CONNECTED.size(); i++) {
            var at = CONNECTED.get(i);
            assertSame(connected, board.getBuildingAt(at), "All four basement variants belong to one building");
            assertEquals(BASEMENTS.get(i).ordinal(), board.getHex(at).terrainLevel(Terrains.BLDG_BASEMENT_TYPE));
        }
        assertNotNull(board.getBuildingAt(DETACHED));
        assertNotSame(connected, board.getBuildingAt(DETACHED));
        return board;
    }

    private static void building(Hex[] hexes, Coords at, BasementType basement, int exits) {
        var hex = hex(hexes, at);
        hex.addTerrain(new Terrain(Terrains.BUILDING, 2, true, exits));
        hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 3));
        hex.addTerrain(new Terrain(Terrains.BLDG_CF, 40));
        hex.addTerrain(new Terrain(Terrains.BLDG_BASEMENT_TYPE, basement.ordinal()));
    }

    private static Hex hex(Hex[] hexes, Coords at) {
        return hexes[at.getY() * WIDTH + at.getX()];
    }
}
