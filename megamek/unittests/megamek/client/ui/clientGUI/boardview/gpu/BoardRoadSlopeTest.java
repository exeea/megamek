/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoadSlopeTest {
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
              road && c.equals(low) ? 1 : 0, c.equals(at) ? rise : 0, BoardScene.Surface.SAND));
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

    private record Segment(Vector3 a, Vector3 b) { }

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
        surfaces.put(at, new BoardSurface(scene, scene.tile(at)));
        for (int d = 0; d < 6; d++) {
            var next = at.translated(d);
            surfaces.put(next, new BoardSurface(scene, scene.tile(next)));
        }
        Map<String, List<Segment>> edges = new HashMap<>();
        for (var surface : surfaces.values()) {
            var faces = new ArrayList<>(surface.groundFaces());
            faces.addAll(surface.walls(scene, BoardGeometry.floor(scene), surfaces));
            for (var face : faces) {
                if (face.finish() == BoardSurface.Finish.DRESSING) { continue; }
                var points = List.of(face.a(), face.b(), face.c());
                for (int i = 0; i < 3; i++) {
                    var a = points.get(i);
                    var b = points.get((i + 1) % 3);
                    if (a.dst2(b) < .000001f) { continue; }
                    String first = key(a), second = key(b);
                    String key = first.compareTo(second) < 0 ? first + "/" + second : second + "/" + first;
                    edges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Segment(a, b));
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
