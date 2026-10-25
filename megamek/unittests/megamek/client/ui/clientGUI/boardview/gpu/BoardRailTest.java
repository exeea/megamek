/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardRailTest {
    private static final Coords CENTER = new Coords(4, 4);

    /** Markers at fixed levels that leave towards each neighbouring marker, as a painted network does. */
    static BoardScene scene(Map<Coords, Double> nodes) {
        var sides = new java.util.HashMap<Coords, Integer>();
        for (var at : nodes.keySet()) {
            int mask = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (nodes.containsKey(at.translated(direction))) { mask |= RailRoute.side(direction); }
            }
            sides.put(at, mask);
        }
        return scene(nodes, sides);
    }

    static BoardScene scene(Map<Coords, Double> nodes, Map<Coords, Integer> sides) {
        return scene(nodes, sides, RailRoute.Kind.MAGLEV);
    }

    /** Native way terrain: a maglev on an implicit deck at the node's level, a rail on the ground. */
    static BoardScene scene(Map<Coords, Double> nodes, Map<Coords, Integer> sides, RailRoute.Kind kind) {
        return BoardSurfaceBlendTest.scene(coords -> nodes.containsKey(coords)
              ? BoardBridgeFootingTest.routeTile(coords, false, kind, nodes.get(coords).intValue(), sides.get(coords), false, 0)
              : BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0));
    }

    static BoardRail.Layout layout(BoardScene scene, Coords at) {
        return BoardRail.layout(scene, scene.tile(at), BoardRail.route(scene.tile(at)));
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
        var joined = layout(scene(Map.of(CENTER, 2.0, north, 3.0), Map.of(CENTER, 1, north, 8)), CENTER);
        assertTrue(joined.ends().getFirst().z() > 0, "A joined side climbs towards its neighbour's level");
        var unreachable = layout(scene(Map.of(CENTER, 2.0, north, 5.0), Map.of(CENTER, 1, north, 8)), CENTER);
        assertEquals(0, unreachable.ends().getFirst().z(), "Separate bridge levels do not form a steep unsupported ramp");
        // An empty mask still draws the short N/S marker stub.
        var stub = layout(scene(Map.of(CENTER, 2.0), Map.of(CENTER, 0)), CENTER);
        assertTrue(stub.ends().isEmpty());
        assertEquals(1, BoardRail.paths(stub).size());
    }

    @Test void allSixConnectionsMeetAtExactlyTheSameMeshCornersAcrossElevations() {
        for (int direction = 0; direction < 6; direction++) {
            var next = CENTER.translated(direction);
            var scene = scene(Map.of(CENTER, 2.0, next, 3.0,
                  CENTER.translated((direction + 2) % 6), 2.0, CENTER.translated((direction + 4) % 6), 1.0));
            var a = layout(scene, CENTER); var b = layout(scene, next);
            Vector3 edge = b.ends().getFirst().point().add(BoardGeometry.center(next, 3));
            assertTrue(a.ends().stream().anyMatch(end -> end.point().add(BoardGeometry.center(CENTER, 2)).epsilonEquals(edge, .0001f)));
            var first = edgeValues(a, CENTER, 2, edge, false);
            var second = edgeValues(b, next, 3, edge, false);
            assertEquals(8, first.size()); assertEquals(first.size(), second.size());
            for (var vertex : first) {
                assertTrue(second.stream().anyMatch(v -> v.epsilonEquals(vertex, .0001f)), "Unmatched seam vertex " + vertex);
            }
            var firstNormals = edgeValues(a, CENTER, 2, edge, true);
            var secondNormals = edgeValues(b, next, 3, edge, true);
            assertEquals(1, firstNormals.size()); assertEquals(1, secondNormals.size());
            assertTrue(firstNormals.getFirst().epsilonEquals(secondNormals.getFirst(), .0001f),
                  "Lighting normals must carry the same slope across every turnout seam");
            assertTrue(Math.abs(firstNormals.getFirst().z - 1) > .0001f, "An inclined join must not flatten");
        }
    }

    private static List<Vector3> edgeValues(BoardRail.Layout layout, Coords at, float level, Vector3 edge, boolean normals) {
        var data = BoardRail.model(layout).meshes.first();
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
                if ((mask & (1 << direction)) != 0) { nodes.put(CENTER.translated(direction), 1.0 + direction % 3); }
            }
            var layout = layout(scene(nodes), CENTER);
            var mesh = BoardRail.model(layout).meshes.first();
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
        var mesh = BoardRail.model(straight).meshes.first();
        assertEquals(24, mesh.parts[0].indices.length / 3);
        for (int i = 0; i < mesh.vertices.length; i += 7) { assertTrue(Math.abs(mesh.vertices[i]) <= 2); }
        var turn = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(2), 2.0)), CENTER);
        assertEquals(1, BoardRail.paths(turn).size());
        assertTrue(BoardRail.paths(turn).getFirst().stream().allMatch(p -> new Vector3(p.x, p.y, 0).len() > 5),
              "A rounded turn leaves the inside open instead of drawing a bridge deck or radial spokes");
    }

    @Test void steadyClimbsAndDescentsKeepAConstantGradeWithoutPerHexFlatSpots() {
        for (int direction = 0; direction < 6; direction++) {
            for (double change : new double[] { -1, 1 }) {
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
                    for (var point : BoardRail.paths(shape).getFirst()) {
                        assertEquals(new Vector3(point.x, point.y, 0).dot(axis) * grade, point.z, .0001f,
                              "A steady ramp must lie on one incline, not a chain of easing curves");
                    }
                    assertEquals(24, BoardRail.model(shape).meshes.first().parts[0].indices.length / 3,
                          "A constant incline needs no extra subdivisions");
                }
            }
        }
    }

    @Test void changingGradesStayMonotoneAndCrestsNeverOvershootChosenHeights() {
        for (double incoming : new double[] { -1, 0, 1 }) {
            for (double outgoing : new double[] { -1, 0, 1 }) {
                var shape = layout(scene(Map.of(CENTER, 5.0, CENTER.translated(0), 5 + incoming,
                      CENTER.translated(3), 5 + outgoing)), CENTER);
                var path = BoardRail.paths(shape).getFirst();
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

    /**
     * Every side mask lays its gentle pieces ({@link RailRoute#pieces}) for both kinds: a straight between opposite
     * edges, a 60-degree arc between edges two apart (radius sqrt(3) apothem, 62.4 px, to within half a pixel), a short
     * stub on each stranded edge; a crossing is two real straights, never a fan from one approach.
     */
    @Test void junctionsLayOnlyGentlePiecesRealCrossingsAndStubs() {
        for (var kind : RailRoute.Kind.values()) {
            for (int mask = 0; mask < 64; mask++) {
                var nodes = new java.util.HashMap<Coords, Double>(); nodes.put(CENTER, 2.0);
                for (int direction = 0; direction < 6; direction++) {
                    if ((mask & (1 << direction)) != 0) { nodes.put(CENTER.translated(direction), 2.0); }
                }
                var scene = scene(nodes, sides(nodes), kind);
                var paths = layout(scene, CENTER).paths();
                if (Integer.bitCount(mask) < 2) { assertEquals(1, paths.size()); continue; }
                int stranded = RailRoute.stranded(mask);
                assertEquals(Integer.bitCount(RailRoute.pieces(mask)) + Integer.bitCount(stranded), paths.size(), kind + " " + mask);
                int stubs = 0;
                for (var path : paths) {
                    var first = horizontal(path.getFirst()); var last = horizontal(path.getLast());
                    float turn = angle(first, last);
                    if (Math.abs(last.len() - first.len()) > 1) {
                        // A stub runs from its edge straight towards the centre.
                        stubs++;
                        assertEquals(RailRoute.stub(mask), first.len() - last.len(), .01f, kind + " " + mask);
                        assertEquals(0, first.crs(last).len(), .01f);
                    } else if (Math.abs(turn - 180) > .5f) {
                        // Edges two apart lie 120 degrees apart round the centre.
                        assertEquals(120, turn, 1, kind + " " + mask + ": an arc joins edges two apart, never adjacent ones");
                        // The arc's centre is the centre of the hex across the edge between its ends.
                        var centre = new Vector3(first).add(last).scl(2);
                        float from = new Vector3(first).sub(centre).len(), to = new Vector3(last).sub(centre).len();
                        assertEquals(62.4f, (from + to) / 2, 1, "sqrt(3) apothems");
                        for (var point : path) {
                            float radius = horizontal(point).sub(centre).len();
                            assertTrue(radius > Math.min(from, to) - .5f && radius < Math.max(from, to) + .5f, kind + " " + mask);
                        }
                    }
                }
                assertEquals(Integer.bitCount(stranded), stubs, kind + " " + mask);
            }
            // N, NE, S, SW: the N-S and NE-SW lines cross, each a straight.
            var crossing = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(1), 2.0,
                  CENTER.translated(3), 2.0, CENTER.translated(4), 2.0), Map.of(CENTER, 27, CENTER.translated(0), 8,
                  CENTER.translated(1), 16, CENTER.translated(3), 1, CENTER.translated(4), 2), kind), CENTER).paths();
            assertEquals(2, crossing.size());
            for (var path : crossing) { assertEquals(180, angle(horizontal(path.getFirst()), horizontal(path.getLast())), .01f); }
        }
    }

    /** Sides of a node map, as {@link #scene(Map)} derives them. */
    private static Map<Coords, Integer> sides(Map<Coords, Double> nodes) {
        var sides = new java.util.HashMap<Coords, Integer>();
        for (var at : nodes.keySet()) {
            int mask = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (nodes.containsKey(at.translated(direction))) { mask |= RailRoute.side(direction); }
            }
            sides.put(at, mask);
        }
        return sides;
    }

    private static Vector3 horizontal(Vector3 point) { return new Vector3(point.x, point.y, 0); }

    /** The angle between two directions from the hex centre, in degrees. */
    private static float angle(Vector3 a, Vector3 b) {
        return (float) Math.toDegrees(Math.acos(Math.clamp(a.dot(b) / (a.len() * b.len()), -1f, 1f)));
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
        var valley = layout(scene(Map.of(CENTER, 2.0, next, 3.0, CENTER.translated(3), 3.0)), CENTER);
        var path = BoardRail.paths(valley).getFirst();
        assertEquals(0, path.get(path.size() / 2).z, .0001f, "Equal higher neighbours still respect this hex's chosen level");
    }

    @Test void implicitMaglevIsTheNarrowPickableBeamWithoutSlabOrLandings() {
        var scene = scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(3), 2.0));
        var tile = scene.tile(CENTER);
        assertTrue(BoardBridge.guideway(tile));
        var surfaces = new java.util.HashMap<Coords, BoardSurface>();
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
        assertTrue(footing.shape().facets().isEmpty(), "No ordinary slab supports or landings");
        assertTrue(footing.lengths().stream().allMatch(length -> length == 0));
        var shape = footing.withDeck(scene, tile, BoardBridge.deck(scene, tile), TerrainLod.FULL, surfaces);
        assertEquals(4 * BoardGeometry.hexScale(), shape.bounds().getWidth(), .001f);
        var centre = BoardGeometry.center(CENTER, 2);
        assertEquals(centre.z + 4 * BoardGeometry.hexScale(), BoardBridge.top(shape, centre.x, centre.y), .001f);
        var ray = new com.badlogic.gdx.math.collision.Ray(new Vector3(centre).add(0, 0, 50), new Vector3(0, 0, -1));
        assertTrue(Float.isFinite(shape.hit(ray)), "The guideway remains a pickable bridge deck");
        ray.origin.x += 6 * BoardGeometry.hexScale();
        assertFalse(Float.isFinite(shape.hit(ray)), "No invisible wide slab next to the beam");
    }
}
