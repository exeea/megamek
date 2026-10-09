/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import javax.imageio.ImageIO;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardDecoration.Placement;
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
        return ground(COORDS, z);
    }

    private static List<BoardSurface.Face> ground(Coords coords, float z) {
        List<BoardSurface.Face> result = new ArrayList<>();
        Vector3 center = BoardGeometry.center(coords, 0);
        center.z = z;
        for (int i = 0; i < 6; i++) {
            Vector3 a = BoardGeometry.corner(coords, 0, i), b = BoardGeometry.corner(coords, 0, i + 1);
            a.z = b.z = z;
            result.add(new BoardSurface.Face(center, a, b, BoardSurface.Finish.BED));
        }
        return result;
    }

    @Test void rotatedScaledPaintCoversItsFullRectangleAcrossChunkEdgesAndRetiresBothFootprints() {
        Coords owner = new Coords(7, 7);
        var paint = new megamek.common.board.BoardDecoration("paint", "decal", "marking", null,
              .25, -.15, 35, false, 3, megamek.common.board.BoardDecoration.Placement.ground(), 0, false);
        Set<Coords> footprint = BoardDecals.footprint(owner, paint, 24, 24);
        assertTrue(footprint.stream().map(at -> new Coords(at.getX() / 8, at.getY() / 8)).distinct().count() >= 4);
        double area = footprint.stream().mapToDouble(at -> area(BoardDecals.project(owner, at, ground(at, at.getX() % 2), paint))).sum();
        assertEquals(9 * BoardGeometry.width() * BoardGeometry.height(), area, 1,
              "No seam, missing neighbour, double projection, or clipping at lower ground");
        var moved = paint.transform(6, 0, -45, false, 2, paint.placement());
        Set<Coords> next = BoardDecals.footprint(owner, moved, 24, 24);
        Map<Coords, List<BoardDecals.Stamp>> before = new HashMap<>(), after = new HashMap<>();
        footprint.forEach(at -> before.put(at, List.of(new BoardDecals.Stamp(owner, paint))));
        next.forEach(at -> after.put(at, List.of(new BoardDecals.Stamp(owner, moved))));
        Set<Coords> union = new java.util.HashSet<>(footprint); union.addAll(next);
        assertEquals(union, BoardDecals.changed(before, after));
        assertEquals(next, BoardDecals.changed(after, Map.of()), "Removal clears every receiving hex");
        assertTrue(union.size() < 100, "A local edit must not invalidate a 24 × 24 board");
    }

    @Test
    void translatedPaintClipsToItsHexAndCannotFallBackFromAMissingReceiver() {
        var paint = new megamek.common.board.BoardDecoration("paint", "decal", "marking", null,
              .2, 0, 35, true, 1, megamek.common.board.BoardDecoration.Placement.surface("bridge", "deck", 0), 2);
        var projected = BoardDecals.project(COORDS, ground(40), paint);
        assertTrue(area(projected) > 0 && area(projected) < area(ground(40)));
        assertEquals(40, height(projected, 0, 0), .001f);
        for (var face : projected) {
            for (Vector3 vertex : List.of(face.a(), face.b(), face.c())) {
                assertEquals(40, vertex.z, .001f);
            }
        }
        assertTrue(BoardDecals.project(COORDS, List.of(), paint).isEmpty());
        var outside = paint.transform(.45, 0, 0, false, 1, paint.placement());
        assertTrue(BoardDecals.project(COORDS, roof(40, 40), outside).isEmpty(), "Paint outside the deck must not land underneath it");
    }

    private static final String CROSS = "decal/emblems/red-cross";

    /** A grass hex at {@link #COORDS}, with a legacy paint overlay or none. */
    private static BoardScene.Tile grass(BoardScene.Pixels overlay) {
        return new BoardScene.Tile(COORDS, 0, -1, false, 0, BoardScene.Surface.GRASS, null, null, overlay, null, null,
              List.of(), List.of(), BoardLiquid.NONE, null, true);
    }

    private static BoardDecals.Opacity paint(BoardDecoration object) {
        return BoardDecals.Opacity.of(grass(null), List.of(new BoardDecals.Stamp(COORDS, object)));
    }

    /** The world point that {@code object}, owned by {@link #COORDS}, paints with texture coordinates (u, v). */
    private static float[] painted(BoardDecoration object, double u, double v) {
        var uv = BoardDecals.paintUv(object);
        double a = u - .5, b = v - .5, determinant = uv.ux() * uv.vy() - uv.uy() * uv.vx();
        return new float[] { (float) (BoardGeometry.centerX(COORDS) + object.x() * BoardGeometry.width()
              + (a * uv.vy() - b * uv.uy()) / determinant),
              (float) (BoardGeometry.centerY(COORDS) + object.y() * BoardGeometry.height() + (b * uv.ux() - a * uv.vx()) / determinant) };
    }

    @Test
    void groundCoverSeesPaintByItsImagesAlphaWhereverTheDecalIsDrawn() throws Exception {
        BufferedImage image = ImageIO.read(BoardDecalArt.image(CROSS));
        var plain = new BoardDecoration("cross", "decal", CROSS, null, .1, -.05, 0, false, .5, Placement.ground(), 0, false);
        var turned = new BoardDecoration("cross", "decal", CROSS, null, .1, -.05, 35, true, .5, Placement.ground(), 0, false);
        for (var object : List.of(plain, turned, plain.withStretch(new BoardDecoration.Stretch(1.4, .7, 1)))) {
            var paint = paint(object);
            int opaque = 0;
            for (int i = 0; i < 400; i++) {
                double u = (i % 20 + .5) / 20, v = (i / 20 + .5) / 20;
                if (image.getRGB((int) (u * image.getWidth()), (int) (v * image.getHeight())) >>> 24 != 255) { continue; }
                opaque++;
                float[] point = painted(object, u, v);
                assertTrue(paint.at(point[0], point[1]) >= .5f, "Opaque paint at " + u + ", " + v + " of " + object);
            }
            assertTrue(opaque > 20);
            // The round emblem's square has transparent corners: they stay open to cover.
            for (double[] corner : new double[][] { { .03, .03 }, { .97, .03 }, { .03, .97 }, { .97, .97 } }) {
                float[] point = painted(object, corner[0], corner[1]);
                assertEquals(0, paint.at(point[0], point[1]), "Transparent corner of " + object);
            }
        }
    }

    @Test
    void clippedPaintEndsAtItsHexAndPaintOffTheGroundGrowsNothingAnyway() throws Exception {
        BufferedImage image = ImageIO.read(BoardDecalArt.image(CROSS));
        var unclipped = new BoardDecoration("cross", "decal", CROSS, null, 0, 0, 0, false, 1, Placement.ground(), 0, false);
        var clipped = new BoardDecoration("cross", "decal", CROSS, null, 0, 0, 0, false, 1, Placement.ground(), 0, true);
        // An opaque texel at least two model px outside the owner hex.
        float[] outside = null;
        float reach = 2 * BoardGeometry.hexScale();
        for (int i = 0; i < 2500 && outside == null; i++) {
            double u = (i % 50 + .5) / 50, v = (i / 50 + .5) / 50;
            float[] point = painted(unclipped, u, v);
            boolean beyond = true;
            for (int d = 0; d < 5; d++) {
                beyond &= !BoardGeometry.contains(COORDS, point[0] + (d == 1 ? reach : d == 2 ? -reach : 0),
                      point[1] + (d == 3 ? reach : d == 4 ? -reach : 0));
            }
            if (beyond && image.getRGB((int) (u * image.getWidth()), (int) (v * image.getHeight())) >>> 24 == 255) { outside = point; }
        }
        assertNotNull(outside);
        assertTrue(paint(unclipped).at(outside[0], outside[1]) >= .5f);
        assertEquals(0, paint(clipped).at(outside[0], outside[1]), "Clipped paint ends at its hex");
        float[] centre = painted(clipped, .5, .5);
        assertTrue(paint(clipped).at(centre[0], centre[1]) >= .5f);
        // Roofs, decks and ice grow no cover: paint there leaves the hex's ground unpainted.
        for (var support : List.of("building", "bridge", "ice")) {
            assertNull(paint(new BoardDecoration("cross", "decal", CROSS, null, 0, 0, 0, false, 1, Placement.on(support, 0), 0, true)));
        }
        assertNull(BoardDecals.Opacity.of(grass(null), List.of()), "Nothing paints a plain hex");
    }

    @Test
    void theLegacyOverlayCountsWhereTheGroundsTopVerticesDrawIt() {
        // Its north-west quarter opaque.
        var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 36; y++) {
            for (int x = 0; x < 42; x++) { image.setRGB(x, y, 0xff808080); }
        }
        var paint = BoardDecals.Opacity.of(grass(new BoardScene.Pixels(image)), List.of());
        float x = BoardGeometry.centerX(COORDS), y = BoardGeometry.centerY(COORDS);
        float dx = BoardGeometry.width() / 5, dy = BoardGeometry.height() / 4;
        assertEquals(1, paint.at(x - dx, y + dy), "North-west");
        assertEquals(0, paint.at(x + dx, y + dy), "North-east");
        assertEquals(0, paint.at(x - dx, y - dy), "South-west");
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
