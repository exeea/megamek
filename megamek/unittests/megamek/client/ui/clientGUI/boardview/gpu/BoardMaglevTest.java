/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.MaglevRoute;
import org.junit.jupiter.api.Test;

class BoardMaglevTest {
    private static final Coords CENTER = new Coords(4, 4);

    /** Markers at fixed levels that leave towards each neighbouring marker, as a painted network does. */
    static BoardScene scene(Map<Coords, Double> nodes) {
        var sides = new java.util.HashMap<Coords, Integer>();
        for (var at : nodes.keySet()) {
            int mask = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (nodes.containsKey(at.translated(direction))) { mask |= MaglevRoute.side(direction); }
            }
            sides.put(at, mask);
        }
        return scene(nodes, sides);
    }

    static BoardScene scene(Map<Coords, Double> nodes, Map<Coords, Integer> sides) {
        return BoardSurfaceBlendTest.scene(coords -> {
            var tile = BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0);
            var features = new ArrayList<BoardScene.Feature>();
            if (nodes.containsKey(coords)) {
                var route = new BoardDecoration("rail-" + coords, "prop", MaglevRoute.ASSET, null, 0, 0, 0, false, 1,
                      BoardDecoration.Placement.absolute(nodes.get(coords)), 0).withConnections(sides.get(coords));
                features.add(new BoardScene.Feature(MaglevRoute.ASSET, 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, route));
            }
            return new BoardScene.Tile(coords, 0, -1, false, 0, tile.surface(), tile.ground(), null, null, null, null,
                  features, List.of(), BoardLiquid.NONE, null, true);
        });
    }

    static BoardMaglev.Layout layout(BoardScene scene, Coords at) {
        return BoardMaglev.layout(scene, scene.tile(at), BoardMaglev.route(scene.tile(at)));
    }

    @Test void storedSidesDecideJoinsSoParallelRoutesStayApartAndUnmatchedSidesEndLevel() {
        var east = CENTER.translated(1);
        // Two parallel N/S routes in adjacent hexes: neither leaves towards the other.
        var parallel = scene(Map.of(CENTER, 2.0, east, 2.0), Map.of(CENTER, 9, east, 9));
        var ends = layout(parallel, CENTER).ends();
        assertEquals(2, ends.size());
        for (var end : ends) {
            assertEquals(0, end.x(), .0001f, "Each side runs to its own hex edge");
            assertEquals(0, end.z(), .0001f);
        }
        // A side whose neighbour does not leave back is an open, level end at the edge, whatever that marker's level.
        var north = CENTER.translated(0);
        var open = layout(scene(Map.of(CENTER, 2.0, north, 5.0), Map.of(CENTER, 1, north, 1)), CENTER);
        assertEquals(1, open.ends().size());
        assertEquals(0, open.ends().getFirst().z(), .0001f);
        assertEquals(0, open.ends().getFirst().slope(), .0001f);
        var joined = layout(scene(Map.of(CENTER, 2.0, north, 5.0), Map.of(CENTER, 1, north, 8)), CENTER);
        assertTrue(joined.ends().getFirst().z() > 0, "A joined side climbs towards its neighbour's level");
        // An empty mask still draws the short N/S marker stub.
        var stub = layout(scene(Map.of(CENTER, 2.0), Map.of(CENTER, 0)), CENTER);
        assertTrue(stub.ends().isEmpty());
        assertEquals(1, BoardMaglev.paths(stub).size());
    }

    @Test void allSixConnectionsMeetAtExactlyTheSameMeshCornersAcrossElevations() {
        for (int direction = 0; direction < 6; direction++) {
            var next = CENTER.translated(direction);
            var scene = scene(Map.of(CENTER, 1.0, next, 3.5,
                  CENTER.translated((direction + 2) % 6), 2.0, CENTER.translated((direction + 4) % 6), .5));
            var a = layout(scene, CENTER); var b = layout(scene, next);
            Vector3 edge = b.ends().getFirst().point().add(BoardGeometry.center(next, 3.5f));
            assertTrue(a.ends().stream().anyMatch(end -> end.point().add(BoardGeometry.center(CENTER, 1)).epsilonEquals(edge, .0001f)));
            var first = edgeValues(a, CENTER, 1, edge, false);
            var second = edgeValues(b, next, 3.5f, edge, false);
            assertEquals(8, first.size()); assertEquals(first.size(), second.size());
            for (var vertex : first) {
                assertTrue(second.stream().anyMatch(v -> v.epsilonEquals(vertex, .0001f)), "Unmatched seam vertex " + vertex);
            }
            var firstNormals = edgeValues(a, CENTER, 1, edge, true);
            var secondNormals = edgeValues(b, next, 3.5f, edge, true);
            assertEquals(1, firstNormals.size()); assertEquals(1, secondNormals.size());
            assertTrue(firstNormals.getFirst().epsilonEquals(secondNormals.getFirst(), .0001f),
                  "Lighting normals must carry the same slope across every turnout seam");
            assertTrue(Math.abs(firstNormals.getFirst().z - 1) > .0001f, "An inclined join must not flatten");
        }
    }

    private static List<Vector3> edgeValues(BoardMaglev.Layout layout, Coords at, float level, Vector3 edge, boolean normals) {
        var data = BoardMaglev.model(layout).meshes.first();
        var origin = BoardGeometry.center(at, level);
        var axis = new Vector3(edge).sub(origin); axis.z = 0; axis.nor();
        var points = new ArrayList<Vector3>();
        for (int i = 0; i < data.vertices.length; i += 7) {
            var point = new Vector3(data.vertices[i], data.vertices[i + 1], data.vertices[i + 2]).add(origin);
            if (Math.abs(new Vector3(point).sub(edge).dot(axis)) >= .001f) { continue; }
            if (normals) {
                if (data.vertices[i + 5] <= 0) { continue; }
                point.set(data.vertices[i + 3], data.vertices[i + 4], data.vertices[i + 5]);
            }
            if (points.stream().noneMatch(v -> v.epsilonEquals(point, .0001f))) { points.add(point); }
        }
        return points;
    }

    @Test void everyExitPatternHasBoundedGeometryAndTurnsKeepAnOpenInside() {
        for (int mask = 0; mask < 64; mask++) {
            var nodes = new java.util.HashMap<Coords, Double>(); nodes.put(CENTER, 2.0);
            for (int direction = 0; direction < 6; direction++) {
                if ((mask & (1 << direction)) != 0) { nodes.put(CENTER.translated(direction), 1.0 + direction); }
            }
            var layout = layout(scene(nodes), CENTER);
            var mesh = BoardMaglev.model(layout).meshes.first();
            assertTrue(mesh.parts[0].indices.length / 3 <= 1000, "At most five bounded turnout curves");
            assertEquals(1, mesh.parts.length);
            for (int i = 0; i < mesh.vertices.length; i += 7) {
                for (int channel = 0; channel < 6; channel++) { assertTrue(Float.isFinite(mesh.vertices[i + channel])); }
                assertEquals(1, new Vector3(mesh.vertices[i + 3], mesh.vertices[i + 4], mesh.vertices[i + 5]).len(), .001);
                var color = new com.badlogic.gdx.graphics.Color();
                com.badlogic.gdx.graphics.Color.abgr8888ToColor(color, mesh.vertices[i + 6]);
                assertTrue(color.r > .3f && color.g > .3f && color.b > .3f, "Mesh attribute order must preserve rail pigment");
            }
        }
        var straight = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(3), 2.0)), CENTER);
        var mesh = BoardMaglev.model(straight).meshes.first();
        assertEquals(24, mesh.parts[0].indices.length / 3);
        for (int i = 0; i < mesh.vertices.length; i += 7) { assertTrue(Math.abs(mesh.vertices[i]) <= 2); }
        var turn = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(2), 2.0)), CENTER);
        assertEquals(1, BoardMaglev.paths(turn).size());
        assertTrue(BoardMaglev.paths(turn).getFirst().stream().allMatch(p -> new Vector3(p.x, p.y, 0).len() > 5),
              "A rounded turn leaves the inside open instead of drawing a bridge deck or radial spokes");
    }

    @Test void steadyClimbsAndDescentsKeepAConstantGradeWithoutPerHexFlatSpots() {
        for (int direction = 0; direction < 6; direction++) {
            for (double change : new double[] { -1.5, 1.5 }) {
                var nodes = new java.util.HashMap<Coords, Double>();
                for (int step = -2; step <= 2; step++) {
                    nodes.put(CENTER.translated(step < 0 ? (direction + 3) % 6 : direction, Math.abs(step)), 5 + change * step);
                }
                var scene = scene(nodes);
                Vector3 axis = BoardGeometry.center(CENTER.translated(direction), 0).sub(BoardGeometry.center(CENTER, 0));
                float grade = (float) (change * BoardGeometry.level()) / axis.len(); axis.nor();
                for (int step = -1; step <= 1; step++) {
                    var at = CENTER.translated(step < 0 ? (direction + 3) % 6 : direction, Math.abs(step));
                    var shape = layout(scene, at);
                    for (var point : BoardMaglev.paths(shape).getFirst()) {
                        assertEquals(new Vector3(point.x, point.y, 0).dot(axis) * grade, point.z, .0001f,
                              "A steady ramp must lie on one incline, not a chain of easing curves");
                    }
                    assertEquals(24, BoardMaglev.model(shape).meshes.first().parts[0].indices.length / 3,
                          "A constant incline needs no extra subdivisions");
                }
            }
        }
    }

    @Test void changingGradesStayMonotoneAndCrestsNeverOvershootChosenHeights() {
        for (double incoming : new double[] { -3, -1, 0, 1, 3 }) {
            for (double outgoing : new double[] { -3, -1, 0, 1, 3 }) {
                var shape = layout(scene(Map.of(CENTER, 5.0, CENTER.translated(0), 5 + incoming,
                      CENTER.translated(3), 5 + outgoing)), CENTER);
                var path = BoardMaglev.paths(shape).getFirst();
                int middle = path.size() / 2;
                if (path.size() == 2) { continue; }
                assertEquals(0, path.get(middle).z, .0001f);
                for (int i = 0; i < path.size(); i++) {
                    float end = (i <= middle ? path.getFirst() : path.getLast()).z;
                    assertTrue(path.get(i).z >= Math.min(0, end) - .0001f && path.get(i).z <= Math.max(0, end) + .0001f,
                          "Transitions must remain between the authored heights");
                    if (i > 0) {
                        double direction = i <= middle ? -incoming : outgoing;
                        assertTrue((path.get(i).z - path.get(i - 1).z) * direction >= -.0001f);
                    }
                }
            }
        }
    }

    @Test void junctionsUseContinuousExitToExitTurnoutsInsteadOfAnImpossibleCentralHub() {
        for (int mask = 0; mask < 64; mask++) {
            if (Integer.bitCount(mask) < 3) { continue; }
            var nodes = new java.util.HashMap<Coords, Double>(); nodes.put(CENTER, 2.0);
            for (int direction = 0; direction < 6; direction++) {
                if ((mask & (1 << direction)) != 0) { nodes.put(CENTER.translated(direction), 2.0); }
            }
            var layout = layout(scene(nodes), CENTER);
            var paths = BoardMaglev.paths(layout);
            assertEquals(Integer.bitCount(mask) - 1, paths.size());
            var approach = paths.getFirst().getFirst();
            for (var path : paths) {
                assertEquals(approach, path.getFirst(), "Branches share an approach, not a central corner");
                assertTrue(layout.ends().stream().anyMatch(end -> end.point().equals(path.getLast())));
                assertTrue(layout.ends().stream().anyMatch(end -> end.point().equals(path.getFirst())));
            }
        }
        var y = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0,
              CENTER.translated(2), 2.0, CENTER.translated(4), 2.0)), CENTER);
        for (var path : BoardMaglev.paths(y)) {
            assertTrue(path.stream().allMatch(p -> p.len() > 5), "A symmetric Y needs curved turnouts, not three radial beams");
        }
        var turnout = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0,
              CENTER.translated(3), 2.0, CENTER.translated(2), 2.0)), CENTER);
        var paths = BoardMaglev.paths(turnout);
        assertTrue(paths.stream().anyMatch(path -> path.size() == 2), "An ordinary turnout retains its straight through line");
        assertTrue(paths.getFirst().getFirst().epsilonEquals(turnout.ends().getFirst().point(), .0001f),
              "The branch faces the opposing approach instead of folding back toward the adjacent exit");
    }

    @Test void neighborEditsChangeOnlyDerivedLayoutsAndIdenticalRoutesShareKeys() {
        var next = CENTER.translated(0);
        var before = scene(Map.of(CENTER, 2.0, next, 2.0));
        var higher = scene(Map.of(CENTER, 2.0, next, 3.0));
        assertNotEquals(layout(before, CENTER), layout(higher, CENTER));
        assertNotEquals(layout(before, CENTER), layout(scene(Map.of(CENTER, 2.0)), CENTER));
        var shifted = scene(Map.of(CENTER, 5.0, next, 5.0));
        assertEquals(layout(before, CENTER), layout(shifted, CENTER), "Absolute elevation does not duplicate shared meshes");
        var beyond = next.translated(0);
        assertNotEquals(layout(scene(Map.of(CENTER, 2.0, next, 3.0, beyond, 3.0)), CENTER),
              layout(scene(Map.of(CENTER, 2.0, next, 3.0, beyond, 4.0)), CENTER),
              "An edge's grade also follows its neighbour's continuation, within the existing two-hex edit invalidation");
        var valley = layout(scene(Map.of(CENTER, 2.0, next, 4.0, CENTER.translated(3), 4.0)), CENTER);
        var path = BoardMaglev.paths(valley).getFirst();
        assertEquals(0, path.get(path.size() / 2).z, .0001f, "Equal higher neighbours still respect this hex's chosen level");
    }
}
