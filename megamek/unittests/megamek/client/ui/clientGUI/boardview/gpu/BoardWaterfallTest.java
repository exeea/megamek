/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardWaterfallTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void domeVentWaterfallWallsAreClosedForLavaAndWater(TerrainLod lod) {
        BoardScene lava = BoardCliffSeamTest.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"));
        for (BoardScene scene : List.of(lava, withWater(lava))) {
            for (boolean transitions : new boolean[] { false, true }) {
                BoardSculptTest.withTransitions(transitions, () -> checkDomeVentCliffEnds(scene, lod));
            }
        }
    }

    /** Exercise ordinary water with exactly the same drops, adjoining cliffs and connected mouths as the lava map. */
    static BoardScene withWater(BoardScene scene) {
        var tiles = scene.tiles().stream().map(t -> !t.liquid().molten() ? t
              : new BoardScene.Tile(t.coords(), t.elevation(), 1, t.frozen(), t.roadExits(), t.surface(),
                    t.ground(), t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(), t.features(), t.text(),
                    BoardLiquid.WATER, t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable())).toList();
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static void checkDomeVentCliffEnds(BoardScene scene, TerrainLod lod) {
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        for (var tile : scene.tiles()) { surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
        float floor = BoardGeometry.floor(scene);
        int checked = 0;
        for (var surface : surfaces.values()) {
            for (var fall : surface.waterfalls) {
                int edge = fall.edge();
                var walls = surface.walls(scene, floor, surfaces);
                var cliff = walls.stream().filter(f -> f.landEdge() == edge).toList();
                List<BoardSurface.Face> joined = new ArrayList<>(surface.groundFaces());
                joined.addAll(walls.stream().filter(f -> f.landEdge() != edge).toList());
                for (int direction = 0; direction < 6; direction++) {
                    BoardSurface neighbor = surfaces.get(surface.tile.coords().translated(direction));
                    if (neighbor == null) { continue; }
                    joined.addAll(neighbor.groundFaces());
                    joined.addAll(neighbor.walls(scene, floor, surfaces));
                }
                joined.removeIf(f -> f.finish() == BoardSurface.Finish.OUTCROP || f.finish() == BoardSurface.Finish.DRESSING);
                for (var entry : segments(cliff).entrySet()) {
                    Segment segment = entry.getKey();
                    if (entry.getValue() != 1 || Math.abs(segment.a().z - segment.b().z) < .001f) { continue; }
                    Vector3 p = new Vector3(segment.a()).lerp(segment.b(), .5f);
                    if (p.z <= fall.lowA() + .1f * BoardGeometry.level()
                          || p.z >= BoardGeometry.waterZ(surface.tile) - .1f * BoardGeometry.level()) { continue; }
                    assertTrue(onSurface(p, joined), () -> "Open waterfall wall at " + surface.tile.coords()
                          + ", edge " + edge + ", " + surface.tile.liquid().kind() + ", " + lod
                          + ", transitions " + BoardGeometry.tuning().transitions() + ": " + p);
                    checked++;
                }
            }
        }
        assertTrue(checked > 10, "Exercise the exposed cliff joins behind the Dome Vent falls");
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void waterfallEndsShareTheAdjoiningCliffShapeAndShading(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            BoardScene scene = mesa(direction);
            var surface = new BoardSurface(scene, scene.tile(new Coords(3, 3)), lod);
            var walls = surface.walls(scene, BoardGeometry.floor(scene));
            int edge = surface.waterfalls.getFirst().edge();
            var fall = walls.stream().filter(f -> f.landEdge() == edge).toList();
            Map<Vector3, BoardRelief.Shade> fallShades = new HashMap<>();
            for (var face : fall) {
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    fallShades.put(p, surface.relief.shade(p));
                }
            }
            for (int end = 0; end < 2; end++) {
                int adjacent = Math.floorMod(edge + (end == 0 ? -1 : 1), 6);
                Vector3 corner = BoardGeometry.corner(surface.tile.coords(), 0, edge + end);
                var cliff = walls.stream().filter(f -> f.landEdge() == adjacent).toList();
                Map<Segment, Integer> counts = segments(cliff);
                int shared = 0;
                float rounding = 0;
                for (var segment : counts.entrySet()) {
                    if (segment.getValue() != 1) { continue; }
                    for (Vector3 p : List.of(segment.getKey().a(), segment.getKey().b())) {
                        float distance = (float) Math.hypot(p.x - corner.x, p.y - corner.y);
                        if (distance > BoardGeometry.WIDTH * .2f || p.z < .1f * BoardGeometry.LEVEL
                              || p.z >= 5 * BoardGeometry.LEVEL) { continue; }
                        Vector3 joined = fallShades.keySet().stream().filter(q -> q.epsilonEquals(p, .002f))
                              .findFirst().orElse(null);
                        assertTrue(joined != null, "The fall must share the cliff's corner rows: " + p + ", " + lod);
                        assertTrue(fallShades.get(joined).normal().dot(surface.relief.shade(p).normal()) > .995f,
                              "The waterfall join must not have a lighting crease: " + p + ", " + lod);
                        assertEquals(surface.relief.shade(p).level(), fallShades.get(joined).level(), .0001f,
                              "Rock must blend into the adjoining bank material: " + p + ", " + lod);
                        assertEquals(surface.relief.shade(p).occlusion(), fallShades.get(joined).occlusion(), .0001f,
                              "The shared corner must not have an ambient lighting seam: " + p + ", " + lod);
                        rounding = Math.max(rounding, distance);
                        shared++;
                    }
                }
                assertTrue(shared > 2, "Both ends must exercise the common cliff boundary");
                assertTrue(rounding > BoardRelief.metres(.1f), "The side must round into the cliff instead of a pillar");
                // Below the dry bank's level, the same corner belongs to that bank's wall into the lower pool.
                Coords bank = surface.tile.coords().translated(BoardGeometry.edgeDirection(adjacent));
                Coords pool = surface.tile.coords().translated(direction);
                var bankSurface = new BoardSurface(scene, scene.tile(bank), lod);
                var lowerCliff = bankSurface.walls(scene, BoardGeometry.floor(scene)).stream()
                      .filter(f -> f.landEdge() >= 0 && bank.translated(BoardGeometry.edgeDirection(f.landEdge())).equals(pool))
                      .toList();
                int lowerShared = 0;
                for (var segment : segments(lowerCliff).entrySet()) {
                    if (segment.getValue() != 1 || Math.abs(segment.getKey().a().z - segment.getKey().b().z) < .001f) { continue; }
                    for (Vector3 p : List.of(segment.getKey().a(), segment.getKey().b())) {
                        if (p.z <= -BoardGeometry.LEVEL || p.z >= 0
                              || Math.hypot(p.x - corner.x, p.y - corner.y) > BoardGeometry.WIDTH * .2f) { continue; }
                        assertTrue(fallShades.keySet().stream().anyMatch(q -> q.epsilonEquals(p, .002f)),
                              "The lower three-way cliff join must stay closed: " + p + ", " + lod);
                        lowerShared++;
                    }
                }
                assertTrue(lowerShared > 2, "Exercise the side join immediately above the receiving pool");
            }
        }
    }

    private record Segment(Vector3 a, Vector3 b) { }

    private static Map<Segment, Integer> segments(List<BoardSurface.Face> faces) {
        Map<Segment, Integer> counts = new HashMap<>();
        for (var face : faces) {
            Vector3[] points = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                var a = points[i];
                var b = points[(i + 1) % 3];
                var reverse = new Segment(b, a);
                counts.merge(counts.containsKey(reverse) ? reverse : new Segment(a, b), 1, Integer::sum);
            }
        }
        return counts;
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void waterfallRockAndWaterMeetTheReceivingBasin(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            for (int depth : new int[] { 1, 3 }) {
                BoardScene scene = mesa(direction, depth);
                Coords high = new Coords(3, 3), low = high.translated(direction);
                var upper = new BoardSurface(scene, scene.tile(high), lod);
                var lower = new BoardSurface(scene, scene.tile(low), lod);
                int edge = upper.waterfalls.getFirst().edge(), receivingEdge = (edge + 3) % 6;
                float contact = lower.tile.elevation() * BoardGeometry.level();
                var cliff = upper.walls(scene, BoardGeometry.floor(scene), Map.of(low, lower)).stream()
                      .filter(f -> f.landEdge() == edge).toList();
                var submerged = lower.groundFaces().stream()
                      .filter(f -> f.finish() == BoardSurface.Finish.WALL && f.landEdge() == receivingEdge).toList();
                assertTrue(!submerged.isEmpty(), "The basin must close the rock down to its bed");
                Map<Vector3, Vector3> normals = new HashMap<>();
                for (var face : cliff) {
                    for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(p.z >= contact - .001f, "The upper wall must not project a separate fin into the basin");
                        if (Math.abs(p.z - contact) < .001f) {
                            assertTrue(onSurface(p, submerged), "The cliff's foot must meet the basin: " + p);
                        }
                        if (p.z < 2 * BoardGeometry.level() || p.z > 4 * BoardGeometry.level()) { continue; }
                        Vector3 normal = upper.relief.shade(p).normal();
                        Vector3 previous = normals.putIfAbsent(p, normal);
                        assertTrue(previous == null || previous.dot(normal) > .999f,
                              "A continuous rock face must not have separate strip normals: " + p);
                    }
                }
                var boundary = lower.waterBoundary(receivingEdge);
                for (int i = 0; i + 1 < boundary.size(); i++) {
                    for (float t : new float[] { .2f, .5f, .8f }) {
                        Vector3 p = new Vector3(boundary.get(i)).lerp(boundary.get(i + 1), t);
                        assertEquals(BoardGeometry.waterZ(lower.tile), p.z, .001f);
                        assertTrue(onSurface(p, submerged), "The waterline must follow the actual rock triangles: " + p);
                    }
                }
            }
        }
    }

    private static boolean onSurface(Vector3 point, List<BoardSurface.Face> faces) {
        Vector3 hit = new Vector3();
        for (var face : faces) {
            Vector3[] vertices = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                Vector3 a = vertices[i], b = vertices[(i + 1) % 3], edge = new Vector3(b).sub(a);
                float t = edge.len2() == 0 ? 0 : Math.clamp(new Vector3(point).sub(a).dot(edge) / edge.len2(), 0, 1);
                if (new Vector3(a).mulAdd(edge, t).epsilonEquals(point, .002f)) { return true; }
            }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            Ray ray = new Ray(new Vector3(point).mulAdd(normal, 1), new Vector3(normal).scl(-1));
            if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit) && hit.epsilonEquals(point, .002f)) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void fallingWaterClearsTheRockLip(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            BoardScene scene = mesa(direction);
            var upper = new BoardSurface(scene, scene.tile(new Coords(3, 3)), lod);
            var solid = new ArrayList<>(upper.groundFaces().stream()
                  .filter(f -> f.finish() != BoardSurface.Finish.OUTCROP && f.finish() != BoardSurface.Finish.DRESSING).toList());
            solid.addAll(upper.walls(scene, BoardGeometry.floor(scene)));
            Vector3[][] sheet = GpuWaterfall.grid(upper, upper.waterfalls.getFirst());
            // Exercise the actual triangles as the sheet turns over the ledge, inside its frayed free sides.
            // Vertex-only checks miss the rock cutting through between consecutive water rows.
            for (int column = 3; column + 3 < sheet.length; column++) {
                for (int row = 0; row < 4; row++) {
                    assertAboveRock(sheet[column][row], sheet[column][row + 1], sheet[column + 1][row + 1], solid);
                    assertAboveRock(sheet[column + 1][row + 1], sheet[column + 1][row], sheet[column][row], solid);
                }
            }
        }
    }

    private static void assertAboveRock(Vector3 a, Vector3 b, Vector3 c, List<BoardSurface.Face> rock) {
        Vector3 hit = new Vector3();
        for (int i = 1; i < 10; i += 2) {
            for (int j = 1; i + j < 10; j += 2) {
                Vector3 p = new Vector3(a).scl(1 - (i + j) / 10f).mulAdd(b, i / 10f).mulAdd(c, j / 10f);
                Ray ray = new Ray(new Vector3(p).add(0, 0, .002f), Vector3.Z);
                for (var face : rock) {
                    assertTrue(!Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit),
                          () -> "The rock lip must stay below the falling water: water " + p + ", rock " + hit);
                }
            }
        }
    }

    /** A raised pool above land on five sides, with a six-level waterfall on the remaining side. */
    private static BoardScene mesa(int direction) {
        return mesa(direction, 1);
    }

    private static BoardScene mesa(int direction, int depth) {
        Coords high = new Coords(3, 3), low = high.translated(direction);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean water = coords.equals(high) || coords.equals(low);
                tiles.add(tile(coords, coords.equals(high) ? 5 : coords.equals(low) ? -1 : 0,
                      coords.equals(low) ? depth : water ? 1 : -1));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void rockBehindTheCurtainBlocksRaysFromObliqueAngles() {
        var original = BoardRelief.tuning();
        try {
            for (float width : new float[] { .05f, .5f, 1 }) {
                GpuRiverTerrainSmokeTest.setWidth(width);
                for (int direction = 0; direction < 6; direction++) {
                    BoardScene scene = scene(direction, 1);
                    BoardSurface upper = new BoardSurface(scene, scene.tile(new Coords(3, 3)));
                    for (var face : upper.waterFaces) {
                        // Collinear mouth samples can leave subpixel ears through float rounding.
                        assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z
                                    >= -.001f * BoardGeometry.HEX_SCALE * BoardGeometry.HEX_SCALE,
                              "The upper pool must not fold over where its lip meets a bank: width " + width + ", direction " + direction);
                    }
                    var fall = upper.waterfalls.getFirst();
                    var crest = upper.crest(fall);
                    List<BoardSurface.Face> solid = new ArrayList<>(upper.walls(scene, BoardGeometry.floor(scene)));
                    solid.addAll(upper.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList());
                    // Avoid casting precisely along a triangle edge, where float ray tests can miss both faces.
                    for (int column = 1; column < 23; column++) {
                        float s = column / 23f;
                        for (int row = 1; row < 13; row++) {
                            Vector3 p = crest.point(s);
                            p.z = fall.lowA() + (p.z - fall.lowA()) * row / 13;
                            for (float angle : new float[] { -.7f, 0, .7f }) {
                                Vector3 outward = crest.normal(s).mulAdd(crest.tangent(s), angle).nor();
                                float reach = BoardGeometry.WIDTH * .4f;
                                Ray ray = new Ray(new Vector3(p).mulAdd(outward, reach), new Vector3(outward).scl(-1));
                                Vector3 hit = new Vector3();
                                assertTrue(solid.stream().anyMatch(f -> Intersector.intersectRayTriangle(ray,
                                            f.a(), f.b(), f.c(), hit) && hit.dst(ray.origin) < 2 * reach
                                            && new Vector3(f.b()).sub(f.a()).crs(new Vector3(f.c()).sub(f.a()))
                                                  .dot(ray.direction) < 0),
                                      "Open cliff: width " + width + ", direction " + direction + ", at " + p + ", angle " + angle);
                            }
                        }
                    }
                }
            }
        } finally { BoardRelief.tune(original); }
    }

    @Test
    void waterfallRocksKeepFoundationsAtEveryWaterDepth() {
        var original = BoardRelief.tuning();
        try {
            GpuRiverTerrainSmokeTest.setWidth(.5f);
            for (int depth : new int[] { 0, 1, 3 }) {
                BoardScene scene = scene(3, depth);
                for (Coords coords : List.of(new Coords(3, 3), new Coords(3, 4))) {
                    BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
                    List<BoardSurface.Face> ground = surface.faces.stream()
                          .filter(f -> f.finish() != BoardSurface.Finish.OUTCROP && f.finish() != BoardSurface.Finish.DRESSING).toList();
                    // Every placed rock carries its own deterministic tint on all its faces.
                    Map<Float, List<Vector3>> rocks = new HashMap<>();
                    for (var face : surface.faces) {
                        if (face.finish() == BoardSurface.Finish.OUTCROP
                              && surface.relief.shade(face.a()).kind() == BoardRelief.Kind.ROCK) {
                            rocks.computeIfAbsent(surface.relief.shade(face.a()).tint(), key -> new ArrayList<>())
                                  .addAll(List.of(face.a(), face.b(), face.c()));
                        }
                    }
                    assertTrue(!rocks.isEmpty(), "Geological lip/pool rocks remain at depth " + depth);
                    for (var rock : rocks.values()) {
                        assertTrue(rock.stream().anyMatch(p -> p.z < BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN)),
                              "A rock must enter the terrain, including the depth-" + depth + " bed at " + coords);
                    }
                }
            }
        } finally { BoardRelief.tune(original); }
    }

    @Test
    void terrainSlopeIncludesWaterDepthWhileSurfaceHeightStaysTheSame() {
        BoardSculptTest.withTransitions(true, () -> {
            Coords water = new Coords(3, 3), land = water.translated(0);
            for (int depth : new int[] { 1, 2, 3 }) {
                BoardScene scene = scene(3, depth);
                List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
                tiles.replaceAll(t -> t.coords().equals(water) ? tile(water, 0, depth)
                      : t.coords().equals(land) ? tile(land, 1, -1) : t);
                scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface surface = new BoardSurface(scene, scene.tile(water));
                assertEquals(depth == 1, surface.relief.slope(1), "Land is one surface level above water; bed depth adds to its drop");
                Vector3 center = BoardGeometry.center(water, 0);
                assertEquals(-depth * BoardGeometry.LEVEL, surface.height(center.x, center.y), .001f);
                assertEquals(BoardGeometry.waterZ(scene.tile(water)), surface.waterHeight(center.x, center.y), .001f);
            }
        });
    }

    private static BoardScene scene(int direction, int depth) {
        Coords high = new Coords(3, 3), low = high.translated(direction);
        Vector3 a = BoardGeometry.center(high, 0), b = BoardGeometry.center(low, 0);
        Vector3 middle = new Vector3(a).lerp(b, .5f), toward = new Vector3(b).sub(a);
        List<Coords> river = List.of(high.translated((direction + 3) % 6), high, low, low.translated(direction));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                int level = new Vector3(BoardGeometry.center(coords, 0)).sub(middle).dot(toward) < 0 ? 3 : 0;
                tiles.add(tile(coords, level, river.contains(coords) ? depth : -1));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile tile(Coords coords, int level, int depth) {
        return new BoardScene.Tile(coords, level, depth, false, 0, BoardScene.Surface.SAND, null,
              null, null, null, null, List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true);
    }
}
