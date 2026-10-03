/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Test;

class BoardRampMeshTest {
    @Test
    void repeatedCandidatesClassifyEachSourcePointOnceAndKeepTheRamp() {
        float scale = BoardGeometry.hexScale();
        var faces = grid((x, y) -> .25f * x + .5f * y);
        int before = faces.size();
        Map<Vector3, Integer> classifications = new HashMap<>();
        BoardRampMesh.simplify(faces, new Vector3(), p -> {
            classifications.merge(p, 1, Integer::sum);
            return p.y >= 0;
        }, p -> false);

        assertTrue(faces.size() < before, "The fixture must revisit candidates while removing interior samples");
        assertTrue(classifications.size() > 10, "Exercise road classification across the ramp");
        assertTrue(classifications.values().stream().allMatch(count -> count == 1),
              "A source point's road distance must not be recomputed for each candidate replacement");
        for (var face : faces) {
            assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z > 0);
        }
        // Collinear boundary vertices may disappear. The whole ramp, including all four shared edges,
        // must still be covered at its original height.
        for (float x = -3; x <= 3; x += .25f) {
            for (float y = -3; y <= 3; y += .25f) {
                assertEquals((.25f * x + .5f * y) * scale, height(faces, x * scale, y * scale), .00002f * scale);
            }
        }
    }

    @Test
    void roadToleranceIsLocalToOneSimplificationAndKeepsOriginalHeights() {
        float scale = BoardGeometry.hexScale();
        BiFunction<Float, Float, Float> bump = (x, y) -> x == 0 && y == 0 ? .04f : 0;
        var roadside = grid(bump);
        var road = grid(bump);
        var source = List.copyOf(road);
        var outside = new Vector3(-10 * scale, -10 * scale, 0);
        BoardRampMesh.simplify(roadside, outside, p -> false, p -> false);
        BoardRampMesh.simplify(road, outside, p -> true, p -> false);

        assertTrue(roadside.size() < road.size(), "The broader roadside tolerance may remove the shallow bump");
        for (var face : source) {
            for (var p : List.of(face.a(), face.b(), face.c(), new Vector3(face.a()).lerp(face.b(), .5f),
                  new Vector3(face.b()).lerp(face.c(), .5f), new Vector3(face.c()).lerp(face.a(), .5f))) {
                assertEquals(p.z, height(road, p.x, p.y), .00011f * scale,
                      "Reusing classifications must preserve the road's stricter height tolerance");
            }
        }
    }

    @Test
    void successiveRemovalsRetainTheOriginalRoadAndBankSamples() {
        float scale = BoardGeometry.hexScale();
        var faces = grid((x, y) -> .25f * x + .06f * (float) (Math.sin(x * .7) * Math.cos(y * .5)));
        var source = List.copyOf(faces);
        BoardRampMesh.simplify(faces, new Vector3(-10, -10, 0).scl(scale),
              p -> Math.abs(p.x) <= .5f * scale, p -> false);
        assertTrue(faces.size() < source.size() - 8, "Exercise accumulated error through several removals");
        for (var f : source) {
            for (var p : List.of(f.a(), f.b(), f.c(), new Vector3(f.a()).lerp(f.b(), .5f),
                  new Vector3(f.b()).lerp(f.c(), .5f), new Vector3(f.c()).lerp(f.a(), .5f))) {
                float tolerance = (Math.abs(p.x) <= .5f * scale ? .00011f : .10001f) * scale;
                assertEquals(p.z, height(faces, p.x, p.y), tolerance,
                      "Every original sample must survive accumulated removals at " + p);
            }
        }
    }

    @Test
    void concaveFarCoordinateMeshesAreDeterministicWithoutMovingSourcePoints() {
        var source = grid((x, y) -> .25f * x + .5f * y);
        source.removeIf(f -> (f.a().x + f.b().x + f.c().x) > 0 && (f.a().y + f.b().y + f.c().y) > 0);
        var offset = new Vector3(3481, -2443, 38.55f);
        for (int i = 0; i < source.size(); i++) {
            var f = source.get(i);
            var points = new Vector3[] {new Vector3(f.a()).add(offset), new Vector3(f.b()).add(offset),
                  new Vector3(f.c()).add(offset)};
            if (i % 2 != 0) {
                for (var p : points) { p.x = Math.nextUp(p.x); p.y = Math.nextDown(p.y); }
            }
            source.set(i, new BoardSurface.Face(points[0], points[1], points[2], f.finish()));
        }
        var saved = copy(source);
        var first = new ArrayList<>(source);
        var second = copy(source);
        BoardRampMesh.simplify(first, offset, p -> false, p -> false);
        BoardRampMesh.simplify(second, offset, p -> false, p -> false);
        assertEquals(first, second, "Queue order must not depend on object identity");
        assertEquals(saved, source, "Removing vertices must never move the source contacts");
        assertTrue(first.size() < source.size());
        for (var f : source) {
            for (var p : List.of(f.a(), f.b(), f.c())) {
                assertEquals(p.z, height(first, p.x, p.y), .003f, "The concave outline remains covered");
            }
        }
    }

    @Test
    void foldedContactKeepsItsVisibleFootprint() {
        // A folded contact in MesaCity 5435. Retriangulating the whole star removes this tiny upward sliver
        // even though the replacement passes its vertex/midpoint checks. Preserve the original patch.
        var center = new Vector3(3399.9246f, -2520.211f, 72);
        var ring = List.of(new Vector3(3400.2441f, -2519.27f, 72),
              new Vector3(3400.1755f, -2519.4058f, 72), new Vector3(3399.9785f, -2520.281f, 72),
              new Vector3(3399.6963f, -2521.2305f, 72), new Vector3(3399.6697f, -2520.1382f, 72));
        var faces = new ArrayList<BoardSurface.Face>();
        for (int i = 0; i < ring.size(); i++) {
            faces.add(new BoardSurface.Face(center, ring.get(i), ring.get((i + 1) % ring.size()), BoardSurface.Finish.TOP));
        }
        BoardRampMesh.simplify(faces, new Vector3(), p -> false, p -> false);
        for (var p : List.of(new Vector3(3400.1472f, -2519.539f, 72), new Vector3(3400.1643f, -2519.5054f, 72))) {
            assertTrue(faces.stream().filter(f -> new Vector3(f.b()).sub(f.a()).crs(new Vector3(f.c()).sub(f.a())).z > 0)
                  .anyMatch(f -> Float.isFinite(f.height(p.x, p.y))), "Keep the upward-facing contact at " + p);
        }
    }

    private static List<BoardSurface.Face> copy(List<BoardSurface.Face> faces) {
        return faces.stream().map(f -> new BoardSurface.Face(new Vector3(f.a()), new Vector3(f.b()),
              new Vector3(f.c()), f.finish())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static List<BoardSurface.Face> grid(BiFunction<Float, Float, Float> elevation) {
        float scale = BoardGeometry.hexScale();
        Vector3[][] points = new Vector3[7][7];
        for (int x = 0; x < points.length; x++) {
            for (int y = 0; y < points[x].length; y++) {
                points[x][y] = new Vector3(x - 3, y - 3, elevation.apply((float) x - 3, (float) y - 3)).scl(scale);
            }
        }
        List<BoardSurface.Face> faces = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 6; y++) {
                faces.add(new BoardSurface.Face(points[x][y], points[x + 1][y], points[x + 1][y + 1],
                      BoardSurface.Finish.TOP));
                faces.add(new BoardSurface.Face(points[x][y], points[x + 1][y + 1], points[x][y + 1],
                      BoardSurface.Finish.TOP));
            }
        }
        return faces;
    }

    private static float height(List<BoardSurface.Face> faces, float x, float y) {
        float height = Float.NEGATIVE_INFINITY;
        for (var face : faces) { height = Math.max(height, face.height(x, y)); }
        return height;
    }
}
