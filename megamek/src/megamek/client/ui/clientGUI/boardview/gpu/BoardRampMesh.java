/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.math.Vector3;

/** Removes redundant interior ramp samples, retaining boundary joins and checking the original height samples. */
final class BoardRampMesh {
    private static final class Vertex {
        final int id;
        final Vector3 point;
        final Set<Triangle> faces = new LinkedHashSet<>();
        boolean queued;
        boolean pinned;
        Vertex(int id, Vector3 point) { this.id = id; this.point = point; }
    }

    /** Interned by fixed source position for this simplification, including its lazy road classification. */
    private static final class Sample {
        final Vector3 point;
        final int hash;
        double tolerance = Double.NaN;

        Sample(Vector3 point) { this.point = point; hash = point.hashCode(); }

        @Override public int hashCode() { return hash; }

        double tolerance(Predicate<Vector3> road, float scale) {
            if (Double.isNaN(tolerance)) { tolerance = (road.test(point) ? .0001 : .1) * scale; }
            return tolerance;
        }
    }

    private record Triangle(Vertex a, Vertex b, Vertex c, List<Sample> samples) {
        List<Vertex> vertices() { return List.of(a, b, c); }
        BoardSurface.Face face() { return new BoardSurface.Face(a.point, b.point, c.point, BoardSurface.Finish.TOP); }
    }
    private record Key(long x, long y, long z) { }

    private BoardRampMesh() { }

    static void simplify(List<BoardSurface.Face> faces, Vector3 center, Predicate<Vector3> road, Predicate<Vector3> curved) {
        Map<Vector3, Sample> samples = new HashMap<>();
        float scale = BoardGeometry.hexScale();
        Map<Key, Vertex> vertices = new HashMap<>();
        Set<Triangle> triangles = new LinkedHashSet<>();
        Map<Long, Integer> edges = new HashMap<>();
        for (var face : faces) {
            Vertex a = vertex(vertices, face.a()), b = vertex(vertices, face.b()), c = vertex(vertices, face.c());
            if (a == b || b == c || c == a) { continue; }
            // Ear clipping assumes an upward, unfolded height field. Leave folded source contact patches intact.
            double area = (double) (b.point.x - a.point.x) * (c.point.y - a.point.y)
                  - (double) (b.point.y - a.point.y) * (c.point.x - a.point.x);
            if (area <= .00001) { a.pinned = b.pinned = c.pinned = true; }
            var triangle = new Triangle(a, b, c, List.of(sample(samples, a.point), sample(samples, b.point),
                  sample(samples, c.point), sample(samples, new Vector3(a.point).lerp(b.point, .5f)),
                  sample(samples, new Vector3(b.point).lerp(c.point, .5f)),
                  sample(samples, new Vector3(c.point).lerp(a.point, .5f))));
            triangles.add(triangle);
            for (var v : triangle.vertices()) { v.faces.add(triangle); }
            edges.merge(edge(a, b), 1, Integer::sum);
            edges.merge(edge(b, c), 1, Integer::sum);
            edges.merge(edge(c, a), 1, Integer::sum);
        }
        for (var t : triangles) {
            var ring = t.vertices();
            for (int i = 0; i < 3; i++) {
                var a = ring.get(i); var b = ring.get((i + 1) % 3);
                if (edges.get(edge(a, b)) != 2) { a.pinned = b.pinned = true; }
                if (a.point.epsilonEquals(center, .0001f) || curved.test(a.point)) { a.pinned = true; }
            }
        }
        var pending = new ArrayDeque<Vertex>();
        var triangulator = new EarClippingTriangulator();
        // Evaluate only the current neighbourhood when popped; a queued neighbour needs no stale replacement.
        // Source order keeps the choices deterministic without ranking and rebuilding every candidate by error.
        vertices.values().stream().sorted(Comparator.comparingInt(v -> v.id)).forEach(v -> enqueue(v, pending));
        while (!pending.isEmpty()) {
            Vertex v = pending.remove();
            v.queued = false;
            List<Triangle> replacement = replacement(v, road, scale, triangulator);
            if (replacement == null) { continue; }
            Set<Vertex> changed = new LinkedHashSet<>();
            for (var old : List.copyOf(v.faces)) {
                triangles.remove(old);
                for (var n : old.vertices()) { n.faces.remove(old); changed.add(n); }
            }
            for (var added : replacement) {
                triangles.add(added);
                for (var n : added.vertices()) { n.faces.add(added); }
            }
            for (var n : changed) { enqueue(n, pending); }
        }
        faces.clear();
        for (var t : triangles) { faces.add(t.face()); }
    }

    private static Sample sample(Map<Vector3, Sample> samples, Vector3 point) {
        return samples.computeIfAbsent(point, Sample::new);
    }

    private static Vertex vertex(Map<Key, Vertex> vertices, Vector3 p) {
        // Independent strips reach the same join with a few float ULPs of rounding. Weld that join before
        // identifying the boundary; otherwise each strip is mistakenly preserved as a separate open mesh.
        float unit = .001f * BoardGeometry.hexScale();
        Key key = new Key(Math.round(p.x / unit), Math.round(p.y / unit), Math.round(p.z / unit));
        return vertices.computeIfAbsent(key, unused -> new Vertex(vertices.size(), p));
    }

    private static long edge(Vertex a, Vertex b) {
        return (long) Math.min(a.id, b.id) << 32 | Math.max(a.id, b.id);
    }

    private static void enqueue(Vertex v, ArrayDeque<Vertex> pending) {
        if (!v.pinned && !v.faces.isEmpty() && !v.queued) { v.queued = true; pending.add(v); }
    }

    private static List<Triangle> replacement(Vertex v, Predicate<Vector3> road, float scale,
          EarClippingTriangulator triangulator) {
        if (v.pinned || v.faces.isEmpty()) { return null; }
        // Follow the original fan's opposite edges. No angular sorting or geometric guessing at concave banks.
        Map<Vertex, Vertex> links = new HashMap<>();
        Set<Sample> samples = new HashSet<>();
        for (var t : v.faces) {
            var points = t.vertices();
            int index = points.indexOf(v);
            links.put(points.get((index + 1) % 3), points.get((index + 2) % 3));
            samples.addAll(t.samples());
        }
        List<Vertex> ring = new ArrayList<>();
        Vertex first = links.keySet().stream().min(Comparator.comparingInt(a -> a.id)).orElseThrow();
        Vertex at = first;
        do {
            ring.add(at);
            at = links.get(at);
            if (at == null || ring.size() > links.size()) { return null; }
        } while (at != first);
        if (ring.size() != links.size()) { return null; }
        float[] xy = new float[ring.size() * 2];
        for (int i = 0; i < ring.size(); i++) {
            xy[2 * i] = ring.get(i).point.x; xy[2 * i + 1] = ring.get(i).point.y;
        }
        var indices = triangulator.computeTriangles(xy);
        List<Triangle> replacement = new ArrayList<>();
        for (int i = 0; i < indices.size; i += 3) {
            var t = new Triangle(ring.get(indices.get(i)), ring.get(indices.get(i + 2)), ring.get(indices.get(i + 1)),
                  new ArrayList<>());
            float area = new Vector3(t.b.point).sub(t.a.point).crs(new Vector3(t.c.point).sub(t.a.point)).z;
            if (area < -.00001f) { return null; }
            if (area <= .00001f) { continue; }
            replacement.add(t);
        }
        // Collinear ears may disappear only if the neighbours still meet exactly along the same 3D boundary.
        for (int i = 0; i < ring.size(); i++) {
            Vector3 a = ring.get(i).point, b = ring.get((i + 1) % ring.size()).point;
            boolean covered = replacement.stream().anyMatch(t -> containsEdge(t.a.point, t.b.point, a, b)
                  || containsEdge(t.b.point, t.c.point, a, b) || containsEdge(t.c.point, t.a.point, a, b));
            if (!covered) { return null; }
        }
        // Candidate vertices stay fixed: construct each plane once, rather than for every retained sample.
        List<BoardSurface.Face> planes = replacement.stream().map(Triangle::face).toList();
        for (var sample : samples) {
            Vector3 p = sample.point;
            Triangle owner = null;
            float best = Float.POSITIVE_INFINITY;
            for (int i = 0; i < replacement.size(); i++) {
                float height = planes.get(i).height(p.x, p.y);
                if (Float.isFinite(height) && Math.abs(height - p.z) < best) {
                    owner = replacement.get(i); best = Math.abs(height - p.z);
                    // Zero cannot improve; retain the same first minimum without scanning the remaining planes.
                    if (best == 0) { break; }
                }
            }
            if (owner == null || best > sample.tolerance(road, scale)) { return null; }
            owner.samples().add(sample);
        }
        return replacement;
    }

    private static boolean containsEdge(Vector3 from, Vector3 to, Vector3 a, Vector3 b) {
        Vector3 delta = new Vector3(to).sub(from);
        float length2 = delta.len2();
        if (length2 == 0) { return false; }
        for (Vector3 point : List.of(a, b)) {
            float t = Math.clamp(new Vector3(point).sub(from).dot(delta) / length2, 0, 1);
            if (point.dst2(new Vector3(from).mulAdd(delta, t)) > .00000001f) { return false; }
        }
        return true;
    }
}
