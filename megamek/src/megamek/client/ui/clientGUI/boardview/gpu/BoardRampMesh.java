/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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
        int revision;
        boolean pinned;
        Vertex(int id, Vector3 point) { this.id = id; this.point = point; }
    }

    private record Triangle(Vertex a, Vertex b, Vertex c, List<Vector3> samples) {
        List<Vertex> vertices() { return List.of(a, b, c); }
        BoardSurface.Face face() { return new BoardSurface.Face(a.point, b.point, c.point, BoardSurface.Finish.TOP); }
    }
    private record Candidate(Vertex vertex, int revision, float error, List<Triangle> replacement) { }

    private BoardRampMesh() { }

    static void simplify(List<BoardSurface.Face> faces, Predicate<Vector3> pinned, float tolerance) {
        Map<Vector3, Vertex> vertices = new HashMap<>();
        Set<Triangle> triangles = new LinkedHashSet<>();
        Map<Long, Integer> edges = new HashMap<>();
        for (var face : faces) {
            Vertex a = vertices.computeIfAbsent(face.a(), p -> new Vertex(vertices.size(), p));
            Vertex b = vertices.computeIfAbsent(face.b(), p -> new Vertex(vertices.size(), p));
            Vertex c = vertices.computeIfAbsent(face.c(), p -> new Vertex(vertices.size(), p));
            var triangle = new Triangle(a, b, c, List.of(a.point, b.point, c.point));
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
                if (pinned.test(a.point)) { a.pinned = true; }
            }
        }
        var pending = new PriorityQueue<Candidate>(Comparator.comparingDouble(Candidate::error)
              .thenComparingInt(c -> c.vertex().id));
        vertices.values().forEach(v -> offer(v, tolerance, pending));
        while (!pending.isEmpty()) {
            Candidate next = pending.remove();
            Vertex v = next.vertex();
            if (v.revision != next.revision() || v.faces.isEmpty()) { continue; }
            Set<Vertex> changed = new LinkedHashSet<>();
            for (var old : List.copyOf(v.faces)) {
                triangles.remove(old);
                for (var n : old.vertices()) { n.faces.remove(old); changed.add(n); }
            }
            for (var added : next.replacement()) {
                triangles.add(added);
                for (var n : added.vertices()) { n.faces.add(added); }
            }
            for (var n : changed) { n.revision++; offer(n, tolerance, pending); }
        }
        faces.clear();
        for (var t : triangles) { faces.add(t.face()); }
    }

    private static long edge(Vertex a, Vertex b) {
        return (long) Math.min(a.id, b.id) << 32 | Math.max(a.id, b.id);
    }

    private static void offer(Vertex v, float tolerance, PriorityQueue<Candidate> pending) {
        if (v.pinned || v.faces.isEmpty()) { return; }
        // Follow the original fan's opposite edges. No angular sorting or geometric guessing at concave banks.
        Map<Vertex, Vertex> links = new HashMap<>();
        Set<Vector3> samples = new HashSet<>();
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
            if (at == null || ring.size() > links.size()) { return; }
        } while (at != first);
        if (ring.size() != links.size()) { return; }
        float[] xy = new float[ring.size() * 2];
        for (int i = 0; i < ring.size(); i++) {
            xy[2 * i] = ring.get(i).point.x; xy[2 * i + 1] = ring.get(i).point.y;
        }
        var indices = new EarClippingTriangulator().computeTriangles(xy);
        List<Triangle> replacement = new ArrayList<>();
        for (int i = 0; i < indices.size; i += 3) {
            var t = new Triangle(ring.get(indices.get(i)), ring.get(indices.get(i + 2)), ring.get(indices.get(i + 1)),
                  new ArrayList<>());
            if (new Vector3(t.b.point).sub(t.a.point).crs(new Vector3(t.c.point).sub(t.a.point)).z <= 0) { return; }
            replacement.add(t);
        }
        float error = 0;
        for (var p : samples) {
            Triangle owner = null;
            float best = Float.POSITIVE_INFINITY;
            for (var t : replacement) {
                float height = t.face().height(p.x, p.y);
                if (Float.isFinite(height) && Math.abs(height - p.z) < best) {
                    owner = t; best = Math.abs(height - p.z);
                }
            }
            if (owner == null || best > tolerance) { return; }
            owner.samples().add(p);
            error = Math.max(error, best);
        }
        pending.add(new Candidate(v, v.revision, error, replacement));
    }
}
