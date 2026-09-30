/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoadRampTest {
    @ParameterizedTest
    @ValueSource(ints = { 9, 31, 63 })
    void concreteRoadGatesHaveNoWallAcrossTheCarriageway(int exits) {
        var scene = concreteJunction(exits);
        var at = BoardRoadTest.CENTER;
        for (var lod : TerrainLod.values()) {
            var surfaces = new HashMap<Coords, BoardSurface>();
            for (var tile : scene.tiles()) { surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
            var surface = surfaces.get(at);
            for (int d = 0; d < 6; d++) {
                if ((exits & 1 << d) == 0) { continue; }
                var adjacent = surfaces.get(at.translated(d));
                Vector3 center = BoardGeometry.center(at, 0), next = BoardGeometry.center(at.translated(d), 0);
                Vector3 along = new Vector3(next).sub(center).nor();
                Vector3 across = new Vector3(-along.y, along.x, 0);
                Vector3 gate = new Vector3(center).lerp(next, .5f);
                for (float offset : new float[] { -4, 0, 4 }) {
                    Vector3 a = new Vector3(gate).mulAdd(across, offset * BoardGeometry.hexScale())
                          .mulAdd(along, -BoardGeometry.hexScale());
                    Vector3 b = new Vector3(gate).mulAdd(across, offset * BoardGeometry.hexScale())
                          .mulAdd(along, BoardGeometry.hexScale());
                    a.z = surface.height(a.x, a.y) + BoardGeometry.hexScale();
                    b.z = adjacent.height(b.x, b.y) + BoardGeometry.hexScale();
                    Ray ray = new Ray(a, new Vector3(b).sub(a).nor());
                    Vector3 hit = new Vector3();
                    for (var owner : List.of(surface, adjacent)) {
                        for (var wall : owner.walls(scene, BoardGeometry.floor(scene), surfaces)) {
                            boolean blocked = Intersector.intersectRayTriangle(ray, wall.a(), wall.b(), wall.c(), hit)
                                  && hit.dst2(a) < a.dst2(b);
                            assertTrue(!blocked, lod + " exit " + d + " wall blocks the road at " + hit);
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 9, 31, 63 })
    void concreteKeepsLevelSlabsAndVerticalRetainingWallsOutsideItsRoads(int exits) {
        var original = BoardConcrete.mode();
        try {
            for (var mode : BoardConcrete.Mode.values()) {
                BoardConcrete.tune(mode);
                var at = BoardRoadTest.CENTER;
                var scene = concreteJunction(exits);
                var surface = new BoardSurface(scene, scene.tile(at));
                var center = BoardGeometry.center(at, 2);
                var road = BoardRoad.clearance(at, exits);
                float scale = BoardGeometry.hexScale();
                for (float x = -35; x <= 35; x += 2) {
                    for (float y = -30; y <= 30; y += 2) {
                        float px = center.x + x * scale, py = center.y + y * scale;
                        if (!BoardGeometry.contains(at, px, py) || road.distance(x, y) < BoardRoad.SHOULDER + .5f) { continue; }
                        assertEquals(center.z, surface.height(px, py), .001f, "Concrete outside the ramp stays flat in " + mode);
                    }
                }
                double area = 0;
                for (var face : surface.faces) {
                    float cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                    assertTrue(cross > 0, "Concrete top triangles cannot fold");
                    area += cross / 2;
                }
                assertEquals(.75 * BoardGeometry.width() * BoardGeometry.height(), area, .1,
                      "Flat slab and ramp must cover the hex exactly once");
                assertTrue(!surface.retainingWalls.isEmpty());
                for (var face : surface.retainingWalls) {
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    assertEquals(0, normal.z, .0001f, "An engineered retaining wall stands vertically");
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        assertEquals(BoardRelief.Kind.CLIFF, surface.relief.shade(p).kind());
                    }
                }
            }
        } finally { BoardConcrete.tune(original); }
    }

    static BoardScene concreteJunction(int exits) {
        var at = BoardRoadTest.CENTER;
        int[] rises = { -2, 1, 2, -1, 0, 2 };
        return BoardSurfaceBlendTest.scene(c -> {
            for (int d = 0; d < 6; d++) {
                if (c.equals(at.translated(d))) {
                    return BoardRoadTest.tile(c, BoardRoad.Kind.PAVED, (exits & 1 << d) == 0 ? 0 : 1 << ((d + 3) % 6),
                          2 + rises[d], BoardScene.Surface.CONCRETE);
                }
            }
            return BoardRoadTest.tile(c, c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(at) ? exits : 0, 2, BoardScene.Surface.CONCRETE);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 6 })
    void rampsStayWithinTheirTerrainTriangleBudgets(int layout) {
        var at = BoardRoadTest.CENTER;
        var scene = layout < 6 ? BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.getX() == at.getX() ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.getX() == at.getX() ? 9 : 0,
              c.getY() >= at.getY() ? layout : 0, BoardScene.Surface.GRASS)) : junction(at, new int[] { -2, 1, 2, -1, 0, 2 });
        var surface = new BoardSurface(scene, scene.tile(at));
        int budget = layout == 1 ? 1000 : layout == 2 ? 2000 : 4000;
        assertTrue(surface.faces.size() < budget, "Count the entire terrain roof, including the earthworks: " + surface.faces.size());
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3 })
    void retainingWallsBesideRampsKeepWallMaterialsAcrossEveryTriangle(int rise) {
        for (var family : List.of(BoardScene.Surface.CONCRETE, BoardScene.Surface.GRASS)) {
            var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
                  c.getX() == 4 ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.getX() == 4 ? 9 : 0,
                  c.getY() >= 4 ? rise : 0, family));
            int checked = 0;
            for (int x = 3; x <= 5; x++) {
                var surface = new BoardSurface(scene, scene.tile(new Coords(x, 4)));
                for (var face : surface.walls(scene, -BoardGeometry.level())) {
                    if (face.landEdge() < 0 || !surface.relief.naturalEdge(face.landEdge())) { continue; }
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        var shade = surface.relief.shade(p);
                        assertNotNull(shade, "Every retaining-wall vertex needs its own wall material data");
                        assertEquals(BoardRelief.Kind.CLIFF, shade.kind(), "Do not project horizontal ground onto a wall");
                        assertTrue(shade.rim() >= -.001f && shade.foot() >= -.001f);
                        assertEquals(1, shade.normal().len(), .001f);
                        checked++;
                    }
                }
            }
            assertTrue(checked > 0, "Exercise the " + family + " retaining walls at rise " + rise);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadMeshRoundsBothFlatJoinsAndKeepsAContinuousGradeAcrossTheHexEdge(int direction) {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4), next = at.translated(direction);
            for (int rise : new int[] { -2, -1, 1, 2 }) {
                var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.PAVED,
                      c.equals(at) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
                      c.equals(at) ? rise : 0, BoardScene.Surface.GRASS));
                var a = new BoardSurface(scene, scene.tile(at));
                var b = new BoardSurface(scene, scene.tile(next));
                Vector3 start = BoardGeometry.center(at, rise), end = BoardGeometry.center(next, 0);
                Vector3 gate = new Vector3(start).lerp(end, .5f);
                assertEquals(gate.z, a.height(gate.x, gate.y), .001f);
                assertEquals(a.height(gate.x, gate.y), b.height(gate.x, gate.y), .001f);
                assertTrue(a.roadNormal(gate).epsilonEquals(b.roadNormal(gate), .0001f),
                      "Lighting must also be continuous at the shared gate");
                assertEquals(grade(a, start, end, .48f, .5f), grade(b, start, end, .5f, .52f), .002f,
                      "The gate is the middle of the incline, not another crest or dip");

                float previous = 0;
                float steepest = 0;
                for (int i = 1; i <= 96; i++) {
                    float from = (i - 1) / 96f, to = i / 96f;
                    float slope = grade(i <= 48 ? a : b, start, end, from, to);
                    assertTrue(slope * rise <= .001f, "No hump or depression in a monotonic ramp");
                    assertTrue(Math.abs(slope - previous) < .14f,
                          "Actual triangle grades must change gradually, even without smooth shading: " + previous + " -> " + slope);
                    previous = slope;
                    steepest = Math.max(steepest, Math.abs(slope));
                }
                assertTrue(steepest < (Math.abs(rise) == 2 ? .8f : .6f), "The climb must use enough of these two hexes");
                float departure = Math.abs(rise) == 2 ? .04f : .19f;
                assertTrue(Math.abs(grade(a, start, end, departure, departure + .01f)) < .075f, "Gentle departure from the plateau");
                assertTrue(Math.abs(grade(b, start, end, 1 - departure - .01f, 1 - departure)) < .075f, "Gentle arrival on the plateau");
                for (float t : new float[] { 0, departure, 1 - departure, 1 }) {
                    Vector3 point = new Vector3(start).lerp(end, t);
                    var surface = t < .5f ? a : b;
                    point.z = surface.height(point.x, point.y);
                    assertTrue(surface.roadNormal(point).epsilonEquals(Vector3.Z, .0001f), "Level plateau tangent");
                }
            }
        }
    }

    private static float grade(BoardSurface surface, Vector3 a, Vector3 b, float from, float to) {
        Vector3 p = new Vector3(a).lerp(b, from), q = new Vector3(a).lerp(b, to);
        return (surface.height(q.x, q.y) - surface.height(p.x, p.y)) / (float) Math.hypot(q.x - p.x, q.y - p.y);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void shouldersGradeIntoTheHexWithoutVerticalWallsAndPickingUsesTheBank(int direction) {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4), next = at.translated(direction);
            for (int rise : new int[] { -2, -1, 1, 2 }) {
                var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.DIRT,
                      c.equals(at) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
                      c.equals(at) ? rise : 0, BoardScene.Surface.GRASS));
                var surface = new BoardSurface(scene, scene.tile(at));
                Vector3 center = BoardGeometry.center(at, rise);
                Vector3 gate = new Vector3(center).lerp(BoardGeometry.center(next, 0), .5f);
                Vector3 along = new Vector3(gate.x - center.x, gate.y - center.y, 0).nor();
                Vector3 across = new Vector3(-along.y, along.x, 0);
                Vector3 station = new Vector3(center).lerp(gate, .85f);
                float road = surface.height(station.x, station.y);
                assertTrue(surface.faces.stream().allMatch(f -> f.finish() == BoardSurface.Finish.TOP),
                      "A dry road has graded ground, not vertical bank panels");
                for (int side : new int[] { -1, 1 }) {
                    Vector3 onRoad = new Vector3(station).mulAdd(across, side * 8.8f * BoardGeometry.hexScale());
                    Vector3 offRoad = new Vector3(station).mulAdd(across, side * 9.2f * BoardGeometry.hexScale());
                    assertEquals(surface.height(onRoad.x, onRoad.y), surface.height(offRoad.x, offRoad.y),
                          .13f * BoardGeometry.hexScale(), "No sheer cut or fill along the shoulder");
                    Vector3 bank = new Vector3(station).mulAdd(across, side * 13 * BoardGeometry.hexScale());
                    float height = surface.height(bank.x, bank.y);
                    assertTrue(Math.abs(height - road) > .5f && height >= Math.min(0, center.z) - .01f
                                && height <= Math.max(0, center.z) + .01f,
                          "The shoulder must grade towards the neighbouring land without an overshoot");
                    assertEquals(at, BoardGeometry.pick(scene, new Ray(new Vector3(bank.x, bank.y, 200),
                          new Vector3(0, 0, -1))), "The visible earthwork belongs to the same pickable hex");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void sixConnectionsShareOneSurfaceWithMatchingGatesAndUnchangedCentres(int layout) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            int[] rises = layout == 0 ? new int[] { 2, 2, 2, 2, 2, 2 }
                  : layout == 1 ? new int[] { -2, -2, -2, -2, -2, -2 } : new int[] { -2, 1, 2, -1, 0, 2 };
            var scene = junction(at, rises);
            var surface = new BoardSurface(scene, scene.tile(at));
            unfolded(surface);
            var center = BoardGeometry.center(at, 2);
            assertEquals(center.z, surface.height(center.x, center.y), .001f, "Keep the unit anchor level");
            for (int d = 0; d < 6; d++) {
                var next = at.translated(d);
                var other = new BoardSurface(scene, scene.tile(next));
                unfolded(other);
                var end = BoardGeometry.center(next, 2 + rises[d]);
                var gate = new Vector3(center).lerp(end, .5f);
                assertEquals(gate.z, surface.height(gate.x, gate.y), .001f);
                assertEquals(gate.z, other.height(gate.x, gate.y), .001f);
                assertEquals(end.z, other.height(end.x, end.y), .001f, "The other hex centre keeps its elevation");
                assertEquals(grade(surface, center, end, .48f, .5f), grade(other, center, end, .5f, .52f), .003f);
                for (int i = 1; i < 40; i++) {
                    var p = new Vector3(center).lerp(gate, i / 40f);
                    float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                    for (var face : surface.faces) {
                        float z = face.height(p.x, p.y);
                        if (Float.isFinite(z)) { min = Math.min(min, z); max = Math.max(max, z); }
                    }
                    assertTrue(Float.isFinite(max), "Every arm has continuous supporting ground");
                    assertEquals(min, max, .01f, "Overlapping arm meshes must not create different ground heights");
                }
            }
        }
    }

    static BoardScene junction(Coords at, int[] rises) {
        return BoardSurfaceBlendTest.scene(c -> {
            for (int d = 0; d < 6; d++) {
                if (c.equals(at.translated(d))) {
                    return BoardRoadTest.tile(c, BoardRoad.Kind.PAVED, 1 << ((d + 3) % 6), 2 + rises[d], BoardScene.Surface.SAND);
                }
            }
            return BoardRoadTest.tile(c, c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(at) ? 63 : 0, 2, BoardScene.Surface.SAND);
        });
    }

    private static void unfolded(BoardSurface surface) {
        for (var face : surface.faces) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            float rise = Math.max(face.a().z, Math.max(face.b().z, face.c().z))
                  - Math.min(face.a().z, Math.min(face.b().z, face.c().z));
            // A folded slope would stand up as a fin. Small slivers can still fold where two rims cross at a corner;
            // docs/gpu-road-slopes-tunnels.md bounds them at 0.9 m tall and under 0.3 m² (shipped maps stay lower).
            float area = normal.len() / 2, metre = BoardRelief.metres(1);
            assertTrue(normal.z > 0 || rise < BoardRelief.metres(.9f) && area < .3f * metre * metre,
                  "Every earthwork triangle has a positive footprint: " + surface.tile.coords() + " " + face);
        }
        for (int corner = 0; corner < 6; corner++) {
            // A gate has no wall; its corner only has to meet the others where both roads' banks meet it.
            float gate = surface.relief.gateCorner(corner);
            for (float level = 0; level <= 4; level += .25f) {
                float z = Float.isNaN(gate) ? level * BoardGeometry.level() : gate;
                var a = surface.relief.roadPoint(corner, 0, z);
                var b = surface.relief.roadPoint(Math.floorMod(corner - 1, 6), 1, z);
                assertTrue(a.epsilonEquals(b, .0001f), "The walls beside an earthwork must close at shared corners");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void theRoadMouthBanksMeetWithoutLeavingCliffSpikesAtTheirCorners(int direction) {
        var at = BoardRoadTest.CENTER;
        var next = at.translated(direction);
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.PAVED,
              c.equals(at) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
              c.equals(at) ? 2 : 0, BoardScene.Surface.GRASS));
        var high = new BoardSurface(scene, scene.tile(at));
        for (var side : high.sides(scene, BoardGeometry.floor(scene))) {
            if (BoardGeometry.edgeDirection(side.edge()) == direction) {
                assertTrue(Math.max(side.a().z - side.lowA(), side.b().z - side.lowB()) < .025f,
                      "The two banks must meet along the whole mouth, including both corners");
            }
        }
    }
}
