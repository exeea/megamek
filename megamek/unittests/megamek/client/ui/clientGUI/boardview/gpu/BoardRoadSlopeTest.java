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
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoadSlopeTest {
    @Test
    void mesaCityRoadCutsKeepClosedBoundaries() {
        var scene = BoardCliffSeamTest.scene(new File(
              "data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board"));
        for (Coords at : List.of(new Coords(27, 21), new Coords(27, 22), new Coords(26, 21))) {
            assertClosed(scene, at);
        }
    }

    @Test
    void mesaCityRoadCliffKeepsItsRimAtLargeCoordinates() {
        var scene = BoardCliffSeamTest.scene(new File(
              "data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board"));
        var tile = scene.tile(new Coords(54, 33));
        assertTrue(BoardRoad.rendered(tile), "Use the same sculpted road metadata as the rendered map");
        GdxNativesLoader.load();
        var camera = new BoardCamera();
        camera.resize(1440, 1080);
        camera.setIsometric(true);
        camera.camera.zoom = .18f;
        var focus = scene.tile(new Coords(55, 33));
        camera.center(BoardGeometry.center(focus.coords(), focus.elevation()));
        var surface = new BoardSurface(scene, tile);
        var faces = new ArrayList<>(surface.groundFaces());
        faces.addAll(surface.walls(scene, BoardGeometry.floor(scene)));
        // Rays through the reported blue wedge beside slab 5634. Float rounding at these world coordinates
        // previously made the top's untangler move two shared rim vertices while their cliff stayed put.
        // Neighbour cuts also add cliff columns between this top's original vertices. Both sides of the
        // road mouth must interpolate the actual emitted boundary, including these subpixel crack samples.
        for (float[] pixel : new float[][] { {737, 340}, {742, 340}, {747, 340},
              {763.5f, 333.5f}, {764.5f, 333.5f}, {742.5f, 343.5f}, {740.5f, 344.5f}, {738.5f, 345.5f},
              {612.5f, 388.5f}, {612.5f, 389.5f}, {613.5f, 389.5f}, {613.5f, 390.5f}, {614.5f, 390.5f},
              {614.5f, 391.5f}, {615.5f, 391.5f}, {615.5f, 392.5f}, {616.5f, 392.5f}, {615.5f, 393.5f},
              {616.5f, 393.5f}, {617.5f, 393.5f} }) {
            var origin = new Vector3(pixel[0] / 1440f * 2 - 1, 1 - pixel[1] / 1080f * 2, -1)
                  .prj(camera.camera.invProjectionView);
            var ray = new Ray(origin, camera.camera.direction);
            assertTrue(faces.stream().anyMatch(face -> {
                var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                return normal.dot(ray.direction) < 0
                      && Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), new Vector3());
            }), "The visible road and cliff must cover the rim instead of exposing sky at pixel "
                  + pixel[0] + "," + pixel[1]);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aGradedRimKeepsTheNativeCliffBelowTheRoadContact(int direction) {
        var at = BoardRoadTest.CENTER;
        var approach = at.translated(direction);
        var continuation = at.translated((direction + 1) % 6);
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(at) ? (1 << direction) | (1 << ((direction + 1) % 6)) : 0,
              c.equals(at) || c.equals(continuation) ? 6 : c.equals(approach) ? 5 : 0, BoardScene.Surface.SAND));
        var natural = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.NONE, 0,
              scene.tile(c).elevation(), BoardScene.Surface.SAND));
        int edge = Math.floorMod(2 - direction, 6);
        for (var lod : TerrainLod.values()) {
            var surface = new BoardSurface(scene, scene.tile(at), lod);
            var nativeSurface = new BoardSurface(natural, natural.tile(at), lod);
            var walls = surface.walls(scene, BoardGeometry.floor(scene));
            var rendered = GpuTerrain.prepareWalls(scene, surface, BoardGeometry.floor(scene), Map.of());
            assertEquals(walls.stream().filter(face -> face.landEdge() == edge).toList(), rendered.entrySet().stream()
                  .filter(entry -> entry.getKey().edge() == edge).flatMap(entry -> entry.getValue().stream()).toList(),
                  "Rendering must reuse the completed cliff, without rebuilding overlapping walls per road segment");
            for (int level : new int[] { 2, 3, 4 }) {
                var nativePoint = nativeSurface.relief.wetCliffContact(edge, .5f, level * BoardGeometry.level());
                float nearest = Float.POSITIVE_INFINITY;
                for (var face : walls) {
                    if (face.landEdge() != edge) { continue; }
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        nearest = Math.min(nearest, nativePoint.dst(p));
                    }
                }
                assertTrue(nearest < .01f, lod + " graded cliff lost its native relief by " + nearest);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aGradedExitKeepsTheSharedCliffProfileOnAnUnchangedEdge(int direction) {
        var at = BoardRoadTest.CENTER;
        var approach = at.translated(direction);
        var continuation = at.translated((direction + 1) % 6);
        for (var lod : TerrainLod.values()) {
            var paved = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
                  c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(at) ? (1 << direction) | (1 << ((direction + 1) % 6)) : 0,
                  c.equals(at) || c.equals(continuation) ? 3 : c.equals(approach) ? 2 : 0, BoardScene.Surface.GRASS));
            var roadSurface = new BoardSurface(paved, paved.tile(at), lod);
            var actual = roadSurface.walls(paved, BoardGeometry.floor(paved));
            int opposite = Math.floorMod(1 - direction - 3, 6);
            for (int level : new int[] { 1, 2 }) {
                // Sample the shared relief engine, including its road clearance. An unrelated road grade
                // must not replace its rock ledges with a straight foot-to-rim retaining wall.
                var p = roadSurface.relief.wetCliffContact(opposite, .5f, level * BoardGeometry.level());
                float nearest = Float.POSITIVE_INFINITY;
                for (var face : actual) {
                    if (face.landEdge() != opposite) { continue; }
                    for (var q : List.of(face.a(), face.b(), face.c())) {
                        nearest = Math.min(nearest, p.dst(q));
                    }
                }
                assertTrue(nearest < .01f, lod + " road wall departed from shared relief by " + nearest + " at " + p);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = BoardScene.Surface.class, names = "CONCRETE", mode = EnumSource.Mode.EXCLUDE)
    void nativeSlopesJoinRoadsAboveAndBelowInEveryNaturalFamily(BoardScene.Surface family) {
        var at = BoardRoadTest.CENTER;
        for (int rise : new int[] { -2, -1, 1, 2 }) {
            var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
                  c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 9 : 0,
                  c.equals(at.translated(2)) ? rise : 0, family));
            assertClosed(scene, at);
            var surface = new BoardSurface(scene, scene.tile(at));
            for (var face : surface.groundFaces()) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                assertTrue(normal.z > 0, family + " roadside top must never fold into the road");
            }
        }
    }

    @Test
    void levelRoadsOnFlatGroundDoNotAddTerrainSubdivisions() {
        var at = BoardRoadTest.CENTER;
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.PAVED, 9, 0,
              BoardScene.Surface.SAND));
        var surface = new BoardSurface(scene, scene.tile(at));
        assertTrue(surface.faces.size() <= 6, "Flat road hexes use the existing small terrain carrier");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void nativeGroundMatchesTheUnsplitEdgeOfAFlatRoadAtEveryDetail(int edge) {
        var at = BoardRoadTest.CENTER;
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 9 : 0, 0,
              BoardScene.Surface.SAND));
        var neighbor = scene.tile(at.translated(BoardGeometry.edgeDirection(edge)));
        var a = BoardGeometry.corner(at, 0, edge);
        var b = BoardGeometry.corner(at, 0, (edge + 1) % 6);
        for (var lod : TerrainLod.values()) {
            var surface = new BoardSurface(scene, neighbor, lod);
            boolean matched = false;
            for (var face : surface.groundFaces()) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                var points = List.of(face.a(), face.b(), face.c());
                for (int i = 0; i < 3; i++) {
                    var p = points.get(i);
                    var q = points.get((i + 1) % 3);
                    matched |= p.epsilonEquals(a, .0001f) && q.epsilonEquals(b, .0001f)
                          || p.epsilonEquals(b, .0001f) && q.epsilonEquals(a, .0001f);
                }
            }
            assertTrue(matched, lod + " must share the road's whole edge; collinear T junctions can leave sky pixels");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3 })
    void roadsBesideStepsKeepTheNativeSlopeOutsideTheirCorridor(int rise) {
        var at = BoardRoadTest.CENTER;
        var low = at.translated(0);
        var natural = scene(at, low, rise, false);
        var paved = scene(at, low, rise, true);
        var nativeSurface = new BoardSurface(natural, natural.tile(at));
        var roadSurface = new BoardSurface(paved, paved.tile(at));
        var original = nativeSurface.walls(natural, BoardGeometry.floor(natural));
        var actual = roadSurface.walls(paved, BoardGeometry.floor(paved));
        int compared = 0;
        for (var face : original) {
            if (face.landEdge() < 0 || BoardGeometry.edgeDirection(face.landEdge()) != 0) { continue; }
            for (var p : List.of(face.a(), face.b(), face.c())) {
                // The road is in the lower hex's far half; it must not squeeze this entire slope against the hex edge.
                float nearest = Float.POSITIVE_INFINITY;
                for (var candidate : actual) {
                    for (var q : List.of(candidate.a(), candidate.b(), candidate.c())) { nearest = Math.min(nearest, p.dst(q)); }
                }
                assertTrue(nearest < .01f, "An untouched native slope moved by " + nearest + " at " + p);
                compared++;
            }
        }
        assertTrue(compared > 0);
    }

    private static BoardScene scene(Coords at, Coords low, int rise, boolean road) {
        return BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              road && c.equals(low) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              road && c.equals(low) ? 3 : 0, c.equals(at) ? rise : 0, BoardScene.Surface.SAND));
    }

    @ParameterizedTest
    @ValueSource(ints = { -2, 2 })
    void concreteRampsClipEachGradedFaceOnceAgainstTheCorridor(int rise) {
        // Clipping every corridor triangle against every graded face multiplied the two tessellations into
        // 8,000-14,000 slab triangles per ramp hex (MesaCity1 N); a face clipped once keeps the same surface.
        var at = BoardRoadTest.CENTER;
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 9 : 0,
              c.equals(at.translated(0)) ? rise : 0, BoardScene.Surface.CONCRETE));
        var surface = new BoardSurface(scene, scene.tile(at));
        assertTrue(BoardSurface.ramps(scene, scene.tile(at)) != 0, "the slab must carry a ramp approach");
        long top = surface.groundFaces().stream().filter(face -> face.finish() == BoardSurface.Finish.TOP).count();
        assertTrue(top < 1500, "concrete ramp slab emitted " + top + " top triangles");
        assertClosed(scene, at);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadSlopesCloseTheirTopsFeetAndThreeHexCorners(int direction) {
        var at = BoardRoadTest.CENTER;
        var low = at.translated(direction);
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.equals(low) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(low) ? 1 << direction : 0, c.equals(at) ? 2 : 0, BoardScene.Surface.SAND));
        assertClosed(scene, at);
    }

    private record Segment(Vector3 a, Vector3 b, Coords owner, BoardSurface.Finish finish, int side) { }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void climbingJunctionsCloseTheSurroundingNativeSlopes(int layout) {
        var at = BoardRoadTest.CENTER;
        int[] rises = layout == 0 ? new int[] { 2, 2, 2, 2, 2, 2 }
              : layout == 1 ? new int[] { -2, -2, -2, -2, -2, -2 } : new int[] { -2, 1, 2, -1, 0, 2 };
        assertClosed(BoardRoadRampTest.junction(at, rises), at);
    }

    /** Check the emitted mesh, including T junctions, rather than just comparing the boundary helper to itself. */
    private static void assertClosed(BoardScene scene, Coords at) {
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        for (var tile : scene.tiles()) {
            if (at.distance(tile.coords()) <= 2) { surfaces.put(tile.coords(), new BoardSurface(scene, tile)); }
        }
        Map<String, List<Segment>> edges = new HashMap<>();
        for (var surface : surfaces.values()) {
            var faces = new ArrayList<>(surface.groundFaces());
            faces.addAll(surface.walls(scene, BoardGeometry.floor(scene), surfaces));
            for (var face : faces) {
                if (face.finish() == BoardSurface.Finish.DRESSING || face.finish() == BoardSurface.Finish.OUTCROP) { continue; }
                var points = List.of(face.a(), face.b(), face.c());
                for (int i = 0; i < 3; i++) {
                    var a = points.get(i);
                    var b = points.get((i + 1) % 3);
                    if (a.dst2(b) < .000001f) { continue; }
                    String first = key(a), second = key(b);
                    String key = first.compareTo(second) < 0 ? first + "/" + second : second + "/" + first;
                    edges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Segment(a, b, surface.tile.coords(),
                          face.finish(), face.landEdge()));
                }
            }
        }
        List<Segment> open = edges.values().stream().filter(list -> list.size() == 1).map(List::getFirst).toList();
        var center = BoardGeometry.center(at, 0);
        List<String> gaps = new ArrayList<>();
        for (var edge : open) {
            for (float t : new float[] { .25f, .5f, .75f }) {
                var p = new Vector3(edge.a()).lerp(edge.b(), t);
                if (Math.hypot(p.x - center.x, p.y - center.y) > BoardGeometry.width() * .7f) { continue; }
                boolean covered = false;
                for (var other : open) {
                    if (other == edge) { continue; }
                    var delta = new Vector3(other.b()).sub(other.a());
                    float u = Math.clamp(new Vector3(p).sub(other.a()).dot(delta) / delta.len2(), 0, 1);
                    if (p.dst2(new Vector3(other.a()).mulAdd(delta, u)) < .0001f) { covered = true; break; }
                }
                if (!covered && gaps.size() < 12) { gaps.add(p + " on " + edge); }
            }
        }
        assertTrue(gaps.isEmpty(), "Open terrain boundaries near " + at + ": " + gaps);
    }

    private static String key(Vector3 p) {
        return Math.round(p.x * 1000) + ":" + Math.round(p.y * 1000) + ":" + Math.round(p.z * 1000);
    }
}
