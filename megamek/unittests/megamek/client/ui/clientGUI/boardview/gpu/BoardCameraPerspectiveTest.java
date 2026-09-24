/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardCameraPerspectiveTest {
    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void switchingProjectionOrFieldOfViewKeepsThePivotPlaneStillAndIsClamped() {
        BoardCamera camera = camera();
        assertFalse(camera.perspective());
        // Straight down, a point beside the pivot lies in the pivot's plane; one raised above it does not.
        Vector3 beside = camera.focus.cpy().add(100, 0, 0), raised = camera.focus.cpy().add(100, 0, 100);
        float orthographic = project(camera, beside).x;
        camera.setPerspective(true);
        assertEquals(orthographic, project(camera, beside).x, .01f, "Switching projection must not zoom the view");
        float narrow = project(camera, raised).x;
        Vector3 position = camera.camera.position.cpy();
        camera.setFieldOfView(90);
        assertEquals(orthographic, project(camera, beside).x, .01f, "The field of view must not zoom the view");
        assertTrue(camera.camera.position.dst(camera.focus) < position.dst(camera.focus),
              "A wider field of view moves the camera closer instead");
        assertTrue(project(camera, raised).x > narrow, "A wider field of view shows more perspective");
        camera.setFieldOfView(0);
        assertEquals(BoardCamera.MIN_FIELD_OF_VIEW, camera.fieldOfView());
        camera.setFieldOfView(180);
        assertEquals(BoardCamera.MAX_FIELD_OF_VIEW, camera.fieldOfView());
        camera.setFieldOfView(Float.NaN);
        assertEquals(BoardCamera.MAX_FIELD_OF_VIEW, camera.fieldOfView());
    }

    @Test
    void perspectiveUsesWorldDepthAndBothProjectionsPickTheSameBoardGeometry() {
        BoardCamera camera = camera();
        BoardScene scene = scene(0);
        camera.setPerspective(true);
        Vector3 near = camera.focus.cpy().add(0, 0, 100);
        Vector3 far = camera.focus.cpy().add(0, 0, -100);
        assertTrue(BoardCamera.worldUnitsPerPixel(camera.camera, near)
              < BoardCamera.worldUnitsPerPixel(camera.camera, far));
        for (boolean perspective : new boolean[] { false, true }) {
            camera.setPerspective(perspective);
            camera.setIsometric(true);
            camera.fit(scene);
            for (var tile : scene.tiles()) {
                Vector3 point = BoardGeometry.center(tile.coords(), tile.elevation());
                Vector3 screen = project(camera, point);
                Vector3 origin = new Vector3(2 * screen.x / 1200 - 1, 2 * screen.y / 800 - 1, -1)
                      .prj(camera.camera.invProjectionView);
                Vector3 direction = new Vector3(2 * screen.x / 1200 - 1, 2 * screen.y / 800 - 1, 1)
                      .prj(camera.camera.invProjectionView).sub(origin).nor();
                assertEquals(tile.coords(), BoardGeometry.pick(scene, new Ray(origin, direction)));
            }
        }
    }

    @Test
    void perspectiveFitKeepsRaisedTerrainInsideTheAvailableBoardArea() {
        for (float fieldOfView : new float[] { 20, 45, 100 }) {
            for (float tilt : new float[] { 0, 55, 80 }) {
                BoardCamera camera = camera();
                camera.setPerspective(true);
                camera.setFieldOfView(fieldOfView);
                camera.orbit(30, tilt);
                camera.viewableArea(100, 600);
                BoardScene scene = scene(2);
                camera.fit(scene);
                for (var tile : scene.tiles()) {
                    for (float elevation : new float[] { BoardGeometry.floor(scene) / BoardGeometry.LEVEL, tile.elevation() }) {
                        for (int corner = 0; corner < 6; corner++) {
                            Vector3 screen = project(camera, BoardGeometry.corner(tile.coords(), elevation, corner));
                            assertTrue(screen.x > 100 && screen.x < 700 && screen.y > 0 && screen.y < 800,
                                  () -> "Corner outside perspective fit: " + screen);
                        }
                    }
                    assertTrue(camera.visibleArea(scene).contains(tile.coords().getX(), tile.coords().getY()));
                }
            }
        }
    }

    @Test
    void perspectivePanAndPointerZoomKeepTheirWorldPlaneAnchors() {
        for (float fieldOfView : new float[] { 20, 45, 100 }) {
            for (float tilt : new float[] { 0, 40, 70 }) {
                BoardCamera camera = camera();
                camera.setPerspective(true);
                camera.setFieldOfView(fieldOfView);
                camera.orbit(40, tilt);
                camera.viewableArea(0, 800);
                Vector3 anchor = camera.focus.cpy();
                Vector3 before = project(camera, anchor);
                camera.pan(30, -20);
                Vector3 panned = project(camera, anchor);
                assertEquals(before.x + 30, panned.x, .05f);
                assertEquals(before.y + 20, panned.y, .05f);
                camera.zoomAt(.7f, panned.x, panned.y);
                Vector3 zoomed = project(camera, anchor);
                assertEquals(panned.x, zoomed.x, .05f);
                assertEquals(panned.y, zoomed.y, .05f);
                assertEquals(anchor.z, camera.focus.z, .001f);
            }
        }
    }

    @Test
    void selectionFramingIncludesTheWholeUnitInPerspective() {
        for (float fieldOfView : new float[] { 20, 45, 100 }) {
            BoardCamera camera = camera();
            camera.setPerspective(true);
            camera.setFieldOfView(fieldOfView);
            camera.orbit(50, 70);
            camera.zoom(.1f);
            BoardScene.Unit unit = new BoardScene.Unit(1, -1, "Unit", new BoardScene.Waypoint(new Coords(8, 7), 4, 0),
                  null, false, null, 4, true);
            camera.frameSelection(unit, 500);
            camera.advance(BoardCamera.CAMERA_FRAMING_SECONDS);
            BoardCameraFramingTest.assertVisible(camera, 500, unit);
        }
    }

    @Test
    void perspectiveFramingPansToTheEdgeWithoutMovingAnAxisThatAlreadyFits() {
        BoardCamera camera = camera();
        camera.setPerspective(true);
        camera.viewableArea(0, 700);
        camera.center(BoardGeometry.center(new Coords(0, 6), 0));
        float y = camera.focus.y;
        BoardScene.Unit unit = new BoardScene.Unit(1, -1, "Unit", new BoardScene.Waypoint(new Coords(8, 6), 0, 0),
              null, false, null, 2, false);
        camera.frameSelection(unit, 700);
        camera.advance(BoardCamera.CAMERA_FRAMING_SECONDS);
        assertEquals(1, camera.camera.zoom);
        assertEquals(y, camera.focus.y, .001f);
        BoardCameraFramingTest.assertVisible(camera, 700, unit);
        float rightmost = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 6; corner++) {
            rightmost = Math.max(rightmost, project(camera, BoardGeometry.corner(unit.location().coords(), 3, corner)).x);
        }
        assertEquals(700 - 64, rightmost, .05f);
    }

    private static BoardCamera camera() {
        BoardCamera camera = new BoardCamera();
        camera.resize(1200, 800);
        return camera;
    }

    private static Vector3 project(BoardCamera camera, Vector3 point) {
        return camera.camera.project(point.cpy(), 0, 0, 1200, 800);
    }

    private static BoardScene scene(int raisedLevel) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), x == 2 && y == 2 ? raisedLevel : 0, -1, false, 0,
                      BoardScene.Surface.GRASS, null, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
