/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardCameraPanTest {
    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void panningCannotLoseTheBoardAtEdgesOrRotatedCorners() {
        for (int[] size : new int[][] { { 1, 1 }, { 1, 3 }, { 2, 1 }, { 16, 17 } }) {
            BoardScene scene = scene(size[0], size[1]);
            for (boolean perspective : new boolean[] { false, true }) {
                for (float[] angle : new float[][] { { 0, 0 }, { 45, 55 }, { 135, 80 } }) {
                    BoardCamera view = camera(scene, perspective);
                    view.orbit(angle[0], angle[1]);
                    for (float zoom : new float[] { .05f, 1, 20 }) {
                        view.zoom(zoom / view.camera.zoom);
                        for (int x = -1; x <= 1; x++) {
                            for (int y = -1; y <= 1; y++) {
                                view.center(BoardGeometry.center(new Coords(size[0] / 2, size[1] / 2), 0));
                                view.pan(x * 100000, y * 100000);
                                assertBoardAtCenter(view, scene);
                                for (int step = 0; step < 10; step++) { view.pan(x * 20, y * 20); }
                                assertBoardAtCenter(view, scene);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void blockedPanningSlidesAlongTheEdgeAndReversesImmediately() {
        BoardScene scene = scene(16, 17);
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera view = camera(scene, perspective);
            view.zoom(1 / view.camera.zoom);
            view.pan(100000, 0);
            Vector3 edge = view.focus.cpy();
            for (int step = 0; step < 20; step++) { view.pan(100, 0); }
            assertTrue(edge.epsilonEquals(view.focus, .01f), "Held input must not accumulate beyond the boundary");

            view.pan(100, 20);
            assertEquals(edge.x, view.focus.x, .01f);
            assertEquals(edge.y + 20, view.focus.y, .01f, "The unblocked axis keeps moving");
            assertEquals(edge.z, view.focus.z);
            view.pan(-10, 0);
            assertEquals(edge.x + 10, view.focus.x, .01f, "Moving back responds on the first input");
            assertBoardAtCenter(view, scene);
        }
    }

    @Test
    void pointerZoomCannotPullTheBoardAwayFromTheUsableCenter() {
        BoardScene scene = scene(5, 4);
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera view = camera(scene, perspective);
            view.orbit(35, 55);
            for (float x : new float[] { 100, 800 }) {
                for (float y : new float[] { 0, 800 }) {
                    view.fit(scene);
                    view.zoom(20 / view.camera.zoom);
                    for (int step = 0; step < 12; step++) {
                        view.zoomAt(.5f, x, y);
                        assertBoardAtCenter(view, scene);
                    }
                    for (int step = 0; step < 12; step++) {
                        view.zoomAt(2, x, y);
                        assertBoardAtCenter(view, scene);
                    }
                }
            }
        }
    }

    @Test
    void ordinaryDraggingAndPointerZoomStillTrackThePointerInsideTheBoard() {
        BoardScene scene = scene(32, 32);
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera view = camera(scene, perspective);
            view.orbit(35, 55);
            view.zoom(1 / view.camera.zoom);
            view.center(BoardGeometry.center(new Coords(16, 16), 0));
            Vector3 anchor = view.focus.cpy();
            Vector3 before = project(view, anchor);
            view.pan(30, -20);
            Vector3 panned = project(view, anchor);
            assertEquals(before.x + 30, panned.x, .05f);
            assertEquals(before.y + 20, panned.y, .05f);
            view.zoomAt(.7f, panned.x, panned.y);
            Vector3 zoomed = project(view, anchor);
            assertEquals(panned.x, zoomed.x, .05f);
            assertEquals(panned.y, zoomed.y, .05f);
        }
    }

    @Test
    void tacticalViewAndReplacementBoardsUseTheirCurrentDimensionsAndScale() {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        try {
            BoardCamera view = camera(scene(32, 32), false);
            view.pan(-100000, -100000);
            BoardScene next = scene(2, 1);
            view.boardChanged();
            view.setTactical(true, next);
            view.fit(next);
            view.resize(600, 1000, next, 2);
            view.viewableArea(60, 360);
            BoardGeometry.tune(new BoardGeometry.Tuning(original.hexScale() * 2, original.unitScale(),
                  original.unitHeightScale(), original.levelHeight(), original.gridShade()));
            view.pan(-100000, -100000);
            assertBoardAtCenter(view, next);
            view.pan(100000, 100000);
            assertBoardAtCenter(view, next);
        } finally {
            BoardGeometry.tune(original);
        }
    }

    @Test
    void freeFlightKeepsItsIndependentMovementAndRestoresTheOrbitPivot() {
        BoardScene scene = scene(2, 2);
        BoardCamera view = camera(scene, false);
        view.pan(100000, 0);
        Vector3 pivot = view.focus.cpy();
        view.setFirstPerson(true);
        Vector3 eye = view.camera.position.cpy();
        view.pan(100000, 0);
        assertEquals(100000, eye.dst(view.camera.position), .1f);
        assertEquals(pivot, view.focus);
        view.setFirstPerson(false);
        assertEquals(pivot, view.focus);
        assertBoardAtCenter(view, scene);
    }

    private static BoardCamera camera(BoardScene scene, boolean perspective) {
        BoardCamera view = new BoardCamera();
        view.resize(1200, 800);
        view.viewableArea(100, 700);
        view.setPerspective(perspective);
        view.fit(scene);
        return view;
    }

    private static void assertBoardAtCenter(BoardCamera view, BoardScene scene) {
        Vector3 center = project(view, view.focus);
        assertEquals(view.camera.viewportWidth == 600 ? 240 : 450, center.x, .05f);
        assertEquals(view.camera.viewportHeight / 2, center.y, .05f);
        Ray ray = new Ray(view.camera.position, view.focus.cpy().sub(view.camera.position));
        assertNotNull(BoardGeometry.hit(scene, ray),
              () -> "The usable screen center must still point at terrain: " + view.focus
                    + ", angle=" + view.azimuth() + "/" + view.tilt() + ", zoom=" + view.camera.zoom);
    }

    private static Vector3 project(BoardCamera view, Vector3 point) {
        return view.camera.project(point.cpy(), 0, 0, view.camera.viewportWidth, view.camera.viewportHeight);
    }

    private static BoardScene scene(int width, int height) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0,
                      BoardScene.Surface.GRASS, null, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
