/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** Artwork on the first solid support, clipped at drops; shared by both terrain presentations. */
final class BoardDecals {
    static final float LIFT = .08f;
    private static final float EPSILON = .0001f;

    private record Receiver(BoardSurface.Face face, Vector3 normal, Area footprint, Rectangle2D bounds, boolean ground) {
        float height(Vector3 point) {
            Vector3 a = face.a();
            return a.z - (normal.x * (point.x - a.x) + normal.y * (point.y - a.y)) / normal.z;
        }
    }

    /** Weld coincident corners across material/normal seams, without joining vertically separated levels. */
    private record Corner(int x, int y, int z, boolean ground) {
        Corner(Vector3 point, boolean ground) {
            this(Math.round(point.x * 1000), Math.round(point.y * 1000), Math.round(point.z * 1000), ground);
        }
    }

    private BoardDecals() { }

    /** Finished ground is a height field; roofs and decks can overlap it and each other. Liquids are never supplied. */
    static List<BoardSurface.Face> project(Coords coords, List<BoardSurface.Face> ground, List<BoardSurface.Face> raised) {
        List<Vector3> corners = new ArrayList<>();
        for (int i = 0; i < 6; i++) { corners.add(BoardGeometry.corner(coords, 0, i)); }
        Area hex = area(corners);
        List<Receiver> receivers = new ArrayList<>();
        for (var faces : List.of(raised, ground)) {
            for (var face : faces) {
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                if (normal.z <= EPSILON) { continue; }
                Area footprint = area(List.of(face.a(), face.b(), face.c()));
                footprint.intersect(hex);
                if (!footprint.isEmpty()) {
                    receivers.add(new Receiver(face, normal, footprint, footprint.getBounds2D(), faces == ground));
                }
            }
        }
        receivers = supportLevel(coords, receivers);
        if (raised.isEmpty()) { return receivers.stream().map(Receiver::face).toList(); }
        List<BoardSurface.Face> result = new ArrayList<>();
        for (int i = 0; i < receivers.size(); i++) {
            Receiver receiver = receivers.get(i);
            Area visible = new Area(receiver.footprint());
            for (int j = 0; j < receivers.size() && !visible.isEmpty(); j++) {
                Receiver other = receivers.get(j);
                if (i == j || receiver.ground() && other.ground() || !receiver.bounds().intersects(other.bounds())) { continue; }
                // Clip at the planes' intersection too: sorting by average height fails on sloped roofs/decks.
                List<Vector3> above = above(other.face(), receiver, j < i ? -EPSILON : EPSILON);
                if (above.size() >= 3) { visible.subtract(area(above)); }
            }
            for (var triangle : BoardTacticalGeometry.flat(visible, -1)) {
                Vector3 a = triangle.a(), b = triangle.b(), c = triangle.c();
                a.z = receiver.height(a); b.z = receiver.height(b); c.z = receiver.height(c);
                result.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP));
            }
        }
        return result;
    }

    /**
     * Never resume artwork on a lower roof, terrace or the ground below its anchor. Keep connected slopes whole:
     * clipping every vertex below the anchor's Z would cut a pitched roof in half. Vertical risers are not receivers,
     * so their upper and lower surfaces remain separate components.
     */
    private static List<Receiver> supportLevel(Coords coords, List<Receiver> receivers) {
        int[] parents = new int[receivers.size()];
        Map<Corner, Integer> corners = new HashMap<>();
        Vector3 anchor = BoardGeometry.center(coords, 0);
        float height = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < receivers.size(); i++) {
            parents[i] = i;
            var face = receivers.get(i).face();
            height = Math.max(height, face.height(anchor.x, anchor.y));
            for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                Integer previous = corners.putIfAbsent(new Corner(point, receivers.get(i).ground()), i);
                if (previous != null) { parents[root(parents, i)] = root(parents, previous); }
            }
        }
        float[] highest = new float[parents.length];
        Arrays.fill(highest, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < receivers.size(); i++) {
            var face = receivers.get(i).face();
            int root = root(parents, i);
            highest[root] = Math.max(highest[root], Math.max(face.a().z, Math.max(face.b().z, face.c().z)));
        }
        List<Receiver> result = new ArrayList<>();
        for (int i = 0; i < receivers.size(); i++) {
            if (highest[root(parents, i)] >= height - EPSILON) { result.add(receivers.get(i)); }
        }
        return result;
    }

    private static int root(int[] parents, int index) {
        while (parents[index] != index) {
            parents[index] = parents[parents[index]];
            index = parents[index];
        }
        return index;
    }

    static boolean upward(BoardSurface.Face face) {
        return (face.b().x - face.a().x) * (face.c().y - face.a().y)
              - (face.b().y - face.a().y) * (face.c().x - face.a().x) > EPSILON;
    }

    private static List<Vector3> above(BoardSurface.Face face, Receiver receiver, float threshold) {
        List<Vector3> result = new ArrayList<>(4);
        Vector3 previous = face.c();
        float before = previous.z - receiver.height(previous) - threshold;
        for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
            float after = point.z - receiver.height(point) - threshold;
            if ((before >= 0) != (after >= 0)) {
                result.add(new Vector3(previous).lerp(point, before / (before - after)));
            }
            if (after >= 0) { result.add(point); }
            previous = point;
            before = after;
        }
        return result;
    }

    private static Area area(List<Vector3> points) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(points.getFirst().x, points.getFirst().y);
        for (int i = 1; i < points.size(); i++) { path.lineTo(points.get(i).x, points.get(i).y); }
        path.closePath();
        return new Area(path);
    }
}
