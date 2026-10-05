/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardDecalsTest {
    private static final Coords COORDS = new Coords(1, 1);

    @Test
    void roofArtworkStopsAtTheEdgeInsteadOfRepeatingOnTheGroundBelow() {
        var ground = ground(-20);
        var projected = BoardDecals.project(COORDS, ground, roof(10, 10));
        assertEquals(10, height(projected, 0, 0), .001f);
        assertEquals(Float.NEGATIVE_INFINITY, height(projected, 20, 0), "The part past the drop is truncated");
        assertEquals(24 * 24, area(projected), .01, "Only the roof receives the artwork");
        for (var face : projected) {
            for (Vector3 vertex : List.of(face.a(), face.b(), face.c())) { assertEquals(10, vertex.z, .001f); }
        }
    }

    @Test
    void continuousRoofSlopesKeepTheirArtworkButADisconnectedLowerShelfDoesNot() {
        var roofs = new ArrayList<>(roof(4, 16));
        roofs.addAll(rectangle(12, 24, 8, 8));
        var projected = BoardDecals.project(COORDS, ground(-20), roofs);
        for (float x : new float[] { -10, -5, 0, 5, 10 }) {
            assertEquals(10 + x / 2, height(projected, x, 0), .002f, "A slope is a continuous support");
        }
        assertEquals(Float.NEGATIVE_INFINITY, height(projected, 20, 0), "The lower shelf cannot resume the text");
        assertEquals(24 * 24, area(projected), .02);
    }

    @Test
    void aRoofMeetingTheGroundDoesNotExtendItsArtworkOntoThatLowerSurface() {
        var projected = BoardDecals.project(COORDS, rectangle(-24, -12, 0, 0), roof(0, 20));
        assertEquals(10, height(projected, 0, 0), .001f);
        assertEquals(Float.NEGATIVE_INFINITY, height(projected, -20, 0), "A shared eave must not join roof and ground artwork");
        assertEquals(24 * 24, area(projected), .01);
    }

    @Test
    void aGroundTerraceAlsoClipsAtTheStepWithoutDrawingOnTheVerticalRiser() {
        var terraces = new ArrayList<>(roof(10, 10));
        terraces.addAll(rectangle(12, 24, 0, 0));
        Vector3 center = BoardGeometry.center(COORDS, 0);
        terraces.add(new BoardSurface.Face(new Vector3(center.x + 12, center.y - 12, 10),
              new Vector3(center.x + 12, center.y - 12, 0), new Vector3(center.x + 12, center.y + 12, 0),
              BoardSurface.Finish.WALL));
        var projected = BoardDecals.project(COORDS, terraces, List.of());
        assertEquals(24 * 24, area(projected), .01);
        assertEquals(Float.NEGATIVE_INFINITY, height(projected, 20, 0));
    }

    @Test
    void anUnobstructedLakebedRetainsTheWholeDecal() {
        var bed = ground(-20);
        var projected = BoardDecals.project(COORDS, bed, List.of());
        assertEquals(area(bed), area(projected), .01);
        assertTrue(projected.stream().allMatch(face -> face.a().z == -20 && face.b().z == -20 && face.c().z == -20));
    }

    @Test
    void intersectingSlopesUseTheHigherPlaneAtEveryPoint() {
        var roofs = new ArrayList<>(roof(4, 16));
        roofs.addAll(roof(16, 4));
        var ground = ground(-20);
        var projected = BoardDecals.project(COORDS, ground, roofs);
        for (float x : new float[] { -10, -5, 0, 5, 10 }) {
            assertEquals(10 + Math.abs(x) / 2, height(projected, x, 0), .002f);
        }
        assertEquals(24 * 24, area(projected), .02, "Crossing planes cannot leave overlaps or gaps");
    }

    @Test
    void repeatedCoplanarFacesDoNotDoubleTheDecal() {
        var roofs = new ArrayList<>(roof(10, 10));
        roofs.addAll(roof(10, 10));
        var ground = ground(0);
        assertEquals(24 * 24, area(BoardDecals.project(COORDS, ground, roofs)), .01);
    }

    private static List<BoardSurface.Face> ground(float z) {
        List<BoardSurface.Face> result = new ArrayList<>();
        Vector3 center = BoardGeometry.center(COORDS, 0);
        center.z = z;
        for (int i = 0; i < 6; i++) {
            Vector3 a = BoardGeometry.corner(COORDS, 0, i), b = BoardGeometry.corner(COORDS, 0, i + 1);
            a.z = b.z = z;
            result.add(new BoardSurface.Face(center, a, b, BoardSurface.Finish.BED));
        }
        return result;
    }

    private static List<BoardSurface.Face> roof(float left, float right) {
        return rectangle(-12, 12, left, right);
    }

    private static List<BoardSurface.Face> rectangle(float minX, float maxX, float left, float right) {
        Vector3 c = BoardGeometry.center(COORDS, 0);
        Vector3 a = new Vector3(c.x + minX, c.y - 12, left), b = new Vector3(c.x + maxX, c.y - 12, right);
        Vector3 d = new Vector3(c.x + minX, c.y + 12, left), e = new Vector3(c.x + maxX, c.y + 12, right);
        return List.of(new BoardSurface.Face(a, b, e, BoardSurface.Finish.TOP),
              new BoardSurface.Face(a, e, d, BoardSurface.Finish.TOP));
    }

    private static float height(List<BoardSurface.Face> faces, float x, float y) {
        Vector3 c = BoardGeometry.center(COORDS, 0);
        float height = Float.NEGATIVE_INFINITY;
        for (var face : faces) { height = Math.max(height, face.height(c.x + x, c.y + y)); }
        return height;
    }

    private static double area(List<BoardSurface.Face> faces) {
        double sum = 0;
        for (var face : faces) {
            sum += Math.abs((face.b().x - face.a().x) * (face.c().y - face.a().y)
                  - (face.b().y - face.a().y) * (face.c().x - face.a().x)) / 2;
        }
        return sum;
    }
}
