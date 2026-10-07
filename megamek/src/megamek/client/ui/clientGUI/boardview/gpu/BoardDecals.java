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
import java.util.HashSet;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.BoardDecoration;

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

    /** A decal has one owner and one transform, even when several chunks receive its paint. */
    record Stamp(Coords owner, BoardDecoration object) { }

    /** Built once per scene update on the terrain worker, then borrowed by every chunk and camera. */
    static Map<Coords, List<Stamp>> index(BoardScene scene) {
        Map<Coords, List<Stamp>> result = new HashMap<>();
        for (var tile : scene.tiles()) {
            // A composition may repeat one decoration on several features; index the logical object only once.
            Set<String> seen = new HashSet<>();
            for (var feature : tile.features()) {
                BoardDecoration object = feature.decoration();
                if (object == null || !object.kind().equals("decal") || !seen.add(object.id())) { continue; }
                Stamp stamp = new Stamp(tile.coords(), object);
                for (Coords target : footprint(tile.coords(), object, scene.width(), scene.height())) {
                    result.computeIfAbsent(target, key -> new ArrayList<>()).add(stamp);
                }
            }
        }
        var order = java.util.Comparator.comparingInt((Stamp stamp) -> stamp.object().drawOrder())
              .thenComparing(stamp -> stamp.object().id());
        result.replaceAll((at, values) -> values.stream().sorted(order).toList());
        return Map.copyOf(result);
    }

    /** Untouched owners keep their footprint lists; a terrain edit need not reproject every decal on the map. */
    static Map<Coords, List<Stamp>> update(BoardScene before, BoardScene after, Map<Coords, List<Stamp>> previous) {
        Set<Coords> owners = new HashSet<>(), affected = new HashSet<>();
        for (int i = 0; i < after.tiles().size(); i++) {
            var old = before.tiles().get(i); var next = after.tiles().get(i);
            if (old == next || paint(old).equals(paint(next))) { continue; }
            owners.add(next.coords());
            for (var object : paint(old)) { affected.addAll(footprint(old.coords(), object, after.width(), after.height())); }
            for (var object : paint(next)) { affected.addAll(footprint(next.coords(), object, after.width(), after.height())); }
        }
        if (owners.isEmpty()) { return previous; }
        Map<Coords, List<Stamp>> result = new HashMap<>(previous);
        for (Coords at : affected) {
            result.put(at, new ArrayList<>(previous.getOrDefault(at, List.of()).stream().filter(s -> !owners.contains(s.owner())).toList()));
        }
        for (Coords owner : owners) {
            for (var object : paint(after.tile(owner))) {
                Stamp stamp = new Stamp(owner, object);
                for (Coords at : footprint(owner, object, after.width(), after.height())) { result.get(at).add(stamp); }
            }
        }
        var order = java.util.Comparator.comparingInt((Stamp stamp) -> stamp.object().drawOrder()).thenComparing(stamp -> stamp.object().id());
        for (Coords at : affected) {
            if (result.get(at).isEmpty()) { result.remove(at); }
            else { result.put(at, result.get(at).stream().sorted(order).toList()); }
        }
        return Map.copyOf(result);
    }

    private static List<BoardDecoration> paint(BoardScene.Tile tile) {
        return tile.features().stream().map(BoardScene.Feature::decoration).filter(java.util.Objects::nonNull)
              .filter(d -> d.kind().equals("decal")).distinct().toList();
    }

    /** Symmetric difference of paint contents includes removed, moved, resized, reordered and replaced decals. */
    static Set<Coords> changed(Map<Coords, List<Stamp>> before, Map<Coords, List<Stamp>> after) {
        if (before == after) { return Set.of(); }
        Set<Coords> result = new HashSet<>(before.keySet()); result.addAll(after.keySet());
        result.removeIf(at -> java.util.Objects.equals(before.get(at), after.get(at)));
        return result;
    }

    static Set<Coords> footprint(Coords owner, BoardDecoration object, int width, int height) {
        Area paint = footprint(owner, object);
        Rectangle2D bounds = paint.getBounds2D();
        // Bound the search by the rotated rectangle, not by every hex or a guessed fixed neighbour radius.
        int minX = (int) Math.max(0, Math.floor((bounds.getMinX() / BoardGeometry.width() - 1) / .75));
        int maxX = (int) Math.min(width - 1, Math.ceil(bounds.getMaxX() / BoardGeometry.width() / .75));
        int minY = (int) Math.max(0, Math.floor(-bounds.getMaxY() / BoardGeometry.height() - 1.5));
        int maxY = (int) Math.min(height - 1, Math.ceil(-bounds.getMinY() / BoardGeometry.height()));
        Set<Coords> result = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Coords at = new Coords(x, y);
                Area intersection = hex(at); intersection.intersect(paint);
                if (!intersection.isEmpty()) { result.add(at); }
            }
        }
        return result;
    }

    private static Area hex(Coords coords) {
        List<Vector3> corners = new ArrayList<>();
        for (int i = 0; i < 6; i++) { corners.add(BoardGeometry.corner(coords, 0, i)); }
        return area(corners);
    }

    private static Area footprint(Coords owner, BoardDecoration object) {
        Vector3 anchor = anchor(owner, object);
        List<Vector3> corners = new ArrayList<>();
        for (int[] corner : new int[][] { {-1, -1}, {1, -1}, {1, 1}, {-1, 1} }) {
            corners.add(new Vector3(corner[0] * BoardGeometry.width() / 2, corner[1] * BoardGeometry.height() / 2, 0)
                  .scl((float) object.scale()).rotate(Vector3.Z, (float) object.rotation()).add(anchor));
        }
        Area result = area(corners);
        if (object.clipToHex()) { result.intersect(hex(owner)); }
        return result;
    }

    private static Vector3 anchor(Coords coords, BoardDecoration object) {
        return BoardGeometry.center(coords, 0).add((float) object.x() * BoardGeometry.width(),
              (float) object.y() * BoardGeometry.height(), 0);
    }

    /** Finished ground is a height field; roofs and decks can overlap it and each other. Liquids are never supplied. */
    static List<BoardSurface.Face> project(Coords coords, List<BoardSurface.Face> ground, List<BoardSurface.Face> raised) {
        List<Vector3> corners = new ArrayList<>();
        for (int i = 0; i < 6; i++) { corners.add(BoardGeometry.corner(coords, 0, i)); }
        return project(BoardGeometry.center(coords, 0), area(corners), ground, raised);
    }

    /** Historical callers project on the owning hex. */
    static List<BoardSurface.Face> project(Coords coords, List<BoardSurface.Face> raised,
          BoardDecoration object) {
        return project(coords, coords, raised, object);
    }

    /** Each recipient owns its triangles. UVs still use the owner's transform, so chunk seams stay continuous. */
    static List<BoardSurface.Face> project(Coords owner, Coords recipient, List<BoardSurface.Face> raised,
          BoardDecoration object) {
        if (!object.clipToHex() && object.placement().receiver().terrain().equals("ground")) {
            return projectGround(owner, recipient, raised, object);
        }
        Area clip = footprint(owner, object); clip.intersect(hex(recipient));
        Vector3 anchor = anchor(owner, object);
        if (object.clipToHex() && raised.stream().noneMatch(face -> Float.isFinite(face.height(anchor.x, anchor.y)))) {
            return List.of();
        }
        // Unbounded paint drapes on the explicitly chosen surface in every recipient, including lower ground.
        return project(object.clipToHex() ? anchor : null, clip, List.of(), raised);
    }

    /** Ground is a height field: its triangles do not occlude each other. Clip each once, without pairwise Areas. */
    private static List<BoardSurface.Face> projectGround(Coords owner, Coords recipient, List<BoardSurface.Face> faces,
          BoardDecoration object) {
        Vector3 anchor = anchor(owner, object);
        List<Vector3> rectangle = new ArrayList<>(), hex = new ArrayList<>();
        for (int[] corner : new int[][] { {-1, -1}, {1, -1}, {1, 1}, {-1, 1} }) {
            rectangle.add(new Vector3(corner[0] * BoardGeometry.width() / 2, corner[1] * BoardGeometry.height() / 2, 0)
                  .scl((float) object.scale()).rotate(Vector3.Z, (float) object.rotation()).add(anchor));
        }
        for (int i = 0; i < 6; i++) { hex.add(BoardGeometry.corner(recipient, 0, i)); }
        List<BoardSurface.Face> result = new ArrayList<>();
        for (var face : faces) {
            if (!upward(face)) { continue; }
            List<Vector3> polygon = clip(clip(List.of(face.a(), face.b(), face.c()), rectangle), hex);
            for (int i = 1; i + 1 < polygon.size(); i++) {
                var triangle = new BoardSurface.Face(polygon.getFirst(), polygon.get(i), polygon.get(i + 1), BoardSurface.Finish.TOP);
                if (upward(triangle)) { result.add(triangle); }
            }
        }
        return result;
    }

    /** Convex XY clipping retains the receiving triangle's interpolated Z and reuses wholly covered vertices. */
    private static List<Vector3> clip(List<Vector3> polygon, List<Vector3> boundary) {
        for (int edge = 0; edge < boundary.size() && !polygon.isEmpty(); edge++) {
            Vector3 a = boundary.get(edge), b = boundary.get((edge + 1) % boundary.size());
            float dx = b.x - a.x, dy = b.y - a.y;
            if (polygon.stream().allMatch(p -> dx * (p.y - a.y) - dy * (p.x - a.x) >= 0)) { continue; }
            List<Vector3> next = new ArrayList<>(polygon.size() + 1);
            Vector3 previous = polygon.getLast();
            float before = dx * (previous.y - a.y) - dy * (previous.x - a.x);
            for (Vector3 point : polygon) {
                float after = dx * (point.y - a.y) - dy * (point.x - a.x);
                if ((before >= 0) != (after >= 0)) { next.add(new Vector3(previous).lerp(point, before / (before - after))); }
                if (after >= 0) { next.add(point); }
                previous = point; before = after;
            }
            polygon = next;
        }
        return polygon;
    }

    private static List<BoardSurface.Face> project(Vector3 anchor, Area hex, List<BoardSurface.Face> ground,
          List<BoardSurface.Face> raised) {
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
        if (anchor != null) { receivers = supportLevel(anchor, receivers); }
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
    private static List<Receiver> supportLevel(Vector3 anchor, List<Receiver> receivers) {
        int[] parents = new int[receivers.size()];
        Map<Corner, Integer> corners = new HashMap<>();
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
