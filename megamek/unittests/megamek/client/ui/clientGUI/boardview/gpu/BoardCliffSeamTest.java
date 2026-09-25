/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shared cliff corners, rims and feet on the shipped map, including the artwork-covered bunker. */
class BoardCliffSeamTest {
    private static BoardScene commCenter() {
        Board board = new Board();
        board.load(new File("data/boards/GrassLands/16x17 Grasslands River CommCenter.board"));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                tiles.add(new BoardScene.Tile(at, hex.getLevel(),
                      hex.containsTerrain(Terrains.WATER) ? hex.terrainLevel(Terrains.WATER) : -1,
                      hex.containsTerrain(Terrains.ICE),
                      hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0,
                      BoardFeatures.surface(hex), null, null, null, null, null,
                      BoardFeatures.capture(hex, at, Map.of()), List.of(), BoardLiquid.capture(hex), null,
                      BoardFeatures.detailedGround(hex, Map.of())));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void adjoiningCliffsShareTheirBoundaries(boolean transitions) {
        BoardSculptTest.withTransitions(transitions, BoardCliffSeamTest::checkBoundaries);
    }

    private static void checkBoundaries() {
        BoardScene scene = commCenter();
        float floor = BoardGeometry.floor(scene);
        Map<Segment, Integer> joined = new HashMap<>();
        Map<Coords, List<BoardSurface.Face>> cliffs = new HashMap<>();
        // The cliffs at 1111 and 1112 and all the adjoining ground, banks and walls.
        for (int x = 9; x <= 11; x++) {
            for (int y = 9; y <= 12; y++) {
                BoardScene.Tile tile = scene.tile(new Coords(x, y));
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> walls = surface.walls(scene, floor);
                countEdges(joined, surface.faces);
                countEdges(joined, walls);
                if (x == 10 && (y == 10 || y == 11)) { cliffs.put(tile.coords(), walls); }
            }
        }
        for (var cliff : cliffs.entrySet()) {
            for (int e = 0; e < 6; e++) {
                if (!scene.tile(cliff.getKey().translated(BoardGeometry.edgeDirection(e))).liquid().present()) {
                    continue;
                }
                int edge = e;
                Map<Segment, Integer> boundary = new HashMap<>();
                countEdges(boundary, cliff.getValue().stream().filter(face -> face.landEdge() == edge).toList());
                assertFalse(boundary.isEmpty(), "The exposed cliff must exist");
                List<Segment> open = boundary.entrySet().stream()
                      .filter(entry -> entry.getValue() == 1 && joined.get(entry.getKey()) < 2
                            && !covered(entry.getKey(), joined, boundary))
                      .map(Map.Entry::getKey).toList();
                assertTrue(open.isEmpty(), cliff.getKey() + " edge " + edge
                      + " must meet its adjoining cliff, rim and bank: " + open.stream().limit(5).toList());
            }
        }
    }

    private record Point(long x, long y, long z) implements Comparable<Point> {
        static Point of(Vector3 p) {
            return new Point(Math.round(p.x * 1000), Math.round(p.y * 1000), Math.round(p.z * 1000));
        }

        @Override
        public int compareTo(Point other) {
            return x != other.x ? Long.compare(x, other.x) : y != other.y ? Long.compare(y, other.y)
                  : Long.compare(z, other.z);
        }
    }

    private record Segment(Point a, Point b) { }

    /** A collapsed bank may join two collinear samples; compare coverage across that subdivision too. */
    private static boolean covered(Segment edge, Map<Segment, Integer> joined, Map<Segment, Integer> own) {
        double dx = edge.b.x - edge.a.x, dy = edge.b.y - edge.a.y, dz = edge.b.z - edge.a.z;
        double length2 = dx * dx + dy * dy + dz * dz;
        double tolerance = 2 / Math.sqrt(length2); // Two thousandths of a world unit.
        List<double[]> spans = new ArrayList<>();
        for (Segment other : joined.keySet()) {
            if (own.containsKey(other)) { continue; }
            double[] span = new double[2];
            boolean collinear = true;
            int i = 0;
            for (Point p : List.of(other.a, other.b)) {
                double x = p.x - edge.a.x, y = p.y - edge.a.y, z = p.z - edge.a.z;
                double t = (x * dx + y * dy + z * dz) / length2;
                double rx = x - t * dx, ry = y - t * dy, rz = z - t * dz;
                collinear &= rx * rx + ry * ry + rz * rz <= 4;
                span[i++] = t;
            }
            if (collinear) { spans.add(new double[] { Math.min(span[0], span[1]), Math.max(span[0], span[1]) }); }
        }
        spans.sort(java.util.Comparator.comparingDouble(span -> span[0]));
        double end = 0;
        for (double[] span : spans) {
            if (span[0] > end + tolerance) { return false; }
            end = Math.max(end, span[1]);
            if (end >= 1 - tolerance) { return true; }
        }
        return false;
    }

    private static void countEdges(Map<Segment, Integer> edges, List<BoardSurface.Face> faces) {
        for (BoardSurface.Face face : faces) {
            if (face.finish() == BoardSurface.Finish.OUTCROP || face.finish() == BoardSurface.Finish.DRESSING) { continue; }
            Vector3[] vertices = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                Point a = Point.of(vertices[i]), b = Point.of(vertices[(i + 1) % 3]);
                edges.merge(a.compareTo(b) < 0 ? new Segment(a, b) : new Segment(b, a), 1, Integer::sum);
            }
        }
    }
}
