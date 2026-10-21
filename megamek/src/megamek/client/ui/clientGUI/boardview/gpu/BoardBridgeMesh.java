/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.RailRoute;

/** Local bridge facets, derived at chunk preparation from the same carriageway that carries roads and rails. */
final class BoardBridgeMesh {
    private static final float PARAPET = 2.5f;
    private static final double FLATNESS = .05;

    private BoardBridgeMesh() { }

    /** Model coordinates: deck top at zero, before the shared bridge grade and placement are applied. */
    static List<BoardBridge.Facet> build(BoardScene.Tile tile, BoardBridge.Deck deck) {
        var outline = outline(tile, deck);
        Area carriageway = outline.carriageway(), slab = outline.slab();
        Area kerbs = new Area(slab);
        kerbs.subtract(carriageway);
        var faces = new ArrayList<BoardBridge.Facet>();
        cap(faces, carriageway, 0, true, BoardBridge.Part.TOP, null);
        cap(faces, slab, -BoardBridge.SLAB, false, BoardBridge.Part.STRUCTURE, null);
        if (deck.truss()) {
            Area steel = BoardBridgeTruss.append(faces, carriageway, kerbs, deck);
            slabWalls(faces, slab, steel);
        } else {
            walls(faces, slab, -BoardBridge.SLAB, 0, BoardBridge.Part.STRUCTURE, null);
            cap(faces, kerbs, PARAPET, true, BoardBridge.Part.STRUCTURE, null);
            walls(faces, kerbs, 0, PARAPET, BoardBridge.Part.STRUCTURE, null);
        }
        return List.copyOf(faces);
    }

    /** The same clipped boundaries form the suspended deck and the solid support at a bank. */
    record Outline(Area carriageway, Area slab) { }

    static Outline outline(BoardScene.Tile tile, BoardBridge.Deck deck) {
        BoardRoad road = deck.slab(tile.coords());
        Area carriageway = road.footprint(0), slab = road.footprint(BoardBridge.SHOULDER);
        boolean rail = false;
        for (var feature : tile.features()) {
            var marker = feature.decoration();
            if (RailRoute.kind(marker) != RailRoute.Kind.RAIL || !RailRoute.onDeck(marker)) { continue; }
            rail = true;
            int sides = RailRoute.drawnSides(marker, deck.exits());
            for (var line : RailRoute.lines(sides, RailRoute.pieces(marker, sides), deck.corridor())) {
                Path2D.Float path = new Path2D.Float();
                float[] first = line.at(0);
                path.moveTo(first[0], first[1]);
                int samples = line.from() >= 0 && line.to() >= 0
                      && (!RailRoute.straight(RailRoute.piece(line.from(), line.to())) || line.oa() != line.ob()) ? 24 : 1;
                for (int i = 1; i <= samples; i++) {
                    float[] point = line.at(i / (float) samples);
                    path.lineTo(point[0], point[1]);
                }
                float half = deck.corridor().sides() == 0 ? BoardRoad.Kind.PAVED.halfWidth
                      : BoardRoad.CORRIDOR_HALF - RailRoute.BESIDE_RAIL;
                carriageway.add(band(path, half));
                slab.add(band(path, half + BoardBridge.SHOULDER));
            }
        }
        if (BoardRoad.roundabout(deck.exits())) {
            float radius = BoardRoad.ROUNDABOUT_RADIUS;
            Area island = new Area(new Ellipse2D.Float(-radius, -radius, 2 * radius, 2 * radius));
            slab.add(island);
            if (rail) { carriageway.add(island); }
        }
        clipExits(carriageway, deck.exits());
        clipExits(slab, deck.exits());
        // One polygon per boundary is shared by caps, walls and the kerb subtraction.
        carriageway = polygon(carriageway);
        slab = polygon(slab);
        return new Outline(carriageway, slab);
    }

    private static Area band(Path2D path, float halfWidth) {
        return new Area(new BasicStroke(halfWidth * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
              .createStrokedShape(path));
    }

    /** Joined mouths share exact seam planes; wide shoulders may overhang an unused neighbouring hex. */
    static void clipExits(Area area, int exits) {
        var bounds = area.getBounds2D();
        List<Vector3> boundary = List.of(new Vector3((float) bounds.getMinX(), (float) bounds.getMinY(), 0),
              new Vector3((float) bounds.getMaxX(), (float) bounds.getMinY(), 0),
              new Vector3((float) bounds.getMaxX(), (float) bounds.getMaxY(), 0),
              new Vector3((float) bounds.getMinX(), (float) bounds.getMaxY(), 0));
        for (int edge = 0; edge < 6; edge++) {
            if ((exits & 1 << BoardGeometry.edgeDirection(edge)) != 0) {
                boundary = BoardTacticalGeometry.clip(boundary, BoardGeometry.modelCorner(edge), BoardGeometry.modelCorner(edge + 1));
            }
        }
        Path2D.Float path = new Path2D.Float();
        for (int i = 0; i < boundary.size(); i++) {
            var point = boundary.get(i);
            if (i == 0) { path.moveTo(point.x, point.y); }
            else { path.lineTo(point.x, point.y); }
        }
        if (boundary.isEmpty()) { area.reset(); return; }
        path.closePath();
        area.intersect(new Area(path));
    }

    private static Area polygon(Area area) {
        Path2D.Float path = new Path2D.Float();
        path.append(area.getPathIterator(null, FLATNESS), false);
        return new Area(path);
    }

    /** Closed exposed contours, with filled area on the right; internal Area seams never become kerbs. */
    static List<List<Vector3>> contours(Area area) {
        Area shape = polygon(area);
        Map<Long, Vector3> vertices = new LinkedHashMap<>();
        Map<Vector3, List<Vector3>> edges = new LinkedHashMap<>();
        List<Vector3[]> segments = new ArrayList<>();
        float[] point = new float[6];
        Vector3 first = null, previous = null;
        for (var it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(point);
            Vector3 next = type == PathIterator.SEG_CLOSE ? first : vertex(vertices, point[0], point[1]);
            if (type == PathIterator.SEG_MOVETO) {
                first = previous = next;
                continue;
            }
            if (previous != next) { segments.add(new Vector3[] { previous, next }); }
            previous = next;
        }
        for (Vector3[] segment : segments) {
            Vector3 a = segment[0], b = segment[1];
            double dx = b.x - a.x, dy = b.y - a.y, length2 = dx * dx + dy * dy;
            var cuts = new TreeMap<Double, Vector3>();
            cuts.put(0d, a); cuts.put(1d, b);
            // Boolean subtraction can leave only part of a collinear segment exposed (especially at exit mouths).
            for (Vector3 pointOnEdge : vertices.values()) {
                double px = pointOnEdge.x - a.x, py = pointOnEdge.y - a.y;
                double t = (px * dx + py * dy) / length2;
                if (t > 0 && t < 1 && Math.abs(px * dy - py * dx) < 1e-5 * Math.sqrt(length2)) {
                    cuts.put(t, pointOnEdge);
                }
            }
            previous = null;
            for (Vector3 next : cuts.values()) {
                if (previous != null) { boundary(edges, previous, next); }
                previous = next;
            }
        }
        List<List<Vector3>> result = new ArrayList<>();
        while (!edges.isEmpty()) {
            List<Vector3> loop = new ArrayList<>();
            first = edges.keySet().iterator().next();
            Vector3 current = first;
            previous = null;
            do {
                loop.add(current);
                var following = edges.get(current);
                if (following == null) { throw new IllegalStateException("Open bridge contour at " + current); }
                Vector3 next = following.getFirst();
                if (previous != null && following.size() > 1) {
                    double turn = Double.POSITIVE_INFINITY;
                    for (Vector3 candidate : following) {
                        double ax = current.x - previous.x, ay = current.y - previous.y;
                        double bx = candidate.x - current.x, by = candidate.y - current.y;
                        double angle = Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
                        if (angle < turn) { turn = angle; next = candidate; }
                    }
                }
                following.remove(next);
                if (following.isEmpty()) { edges.remove(current); }
                previous = current;
                current = next;
            } while (current != first);
            if (loop.size() >= 3) { result.add(loop); }
        }
        return result;
    }

    private static void boundary(Map<Vector3, List<Vector3>> edges, Vector3 from, Vector3 to) {
        if (from == to) { return; }
        // Area contours keep their interior on the right. A shared internal seam occurs in opposite directions;
        // cancel those pairs after splitting, preserving closed boundaries even around float-sized slivers.
        var reverse = edges.get(to);
        if (reverse != null && reverse.remove(from)) {
            if (reverse.isEmpty()) { edges.remove(to); }
        } else {
            edges.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
        }
    }

    /** Weld float-rounding duplicates without moving the first boundary coordinate. */
    private static Vector3 vertex(Map<Long, Vector3> vertices, float x, float y) {
        int column = Math.round(x * 100000), row = Math.round(y * 100000);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                long near = (long) (column + dx) << 32 | Integer.toUnsignedLong(row + dy);
                Vector3 point = vertices.get(near);
                if (point != null && Math.hypot(point.x - x, point.y - y) <= 1e-5) { return point; }
            }
        }
        long key = (long) column << 32 | Integer.toUnsignedLong(row);
        Vector3 point = new Vector3(x, y, 0);
        vertices.put(key, point);
        return point;
    }

    static void cap(List<BoardBridge.Facet> faces, Area area, float z, boolean up, BoardBridge.Part part, Color colour) {
        for (var triangle : BoardTacticalGeometry.flat(area, -1)) {
            Vector3 a = new Vector3(triangle.a()).add(0, 0, z);
            Vector3 b = new Vector3(triangle.b()).add(0, 0, z);
            Vector3 c = new Vector3(triangle.c()).add(0, 0, z);
            BoardBridge.triangle(faces, a, up ? b : c, up ? c : b, part, colour);
        }
    }

    static void walls(List<BoardBridge.Facet> faces, Area area, float low, float high, BoardBridge.Part part, Color colour) {
        for (var loop : contours(area)) {
            for (int i = 0; i < loop.size(); i++) {
                wall(faces, loop.get(i), loop.get((i + 1) % loop.size()), low, high, part, colour);
            }
        }
    }

    /** Keep exposed slab ends, but omit concrete faces buried in the coplanar steel fascia. */
    private static void slabWalls(List<BoardBridge.Facet> faces, Area slab, Area steel) {
        var covered = contours(steel);
        for (var loop : contours(slab)) {
            for (int i = 0; i < loop.size(); i++) {
                Vector3 a = loop.get(i), b = loop.get((i + 1) % loop.size());
                double dx = b.x - a.x, dy = b.y - a.y, length2 = dx * dx + dy * dy;
                var changes = new TreeMap<Double, Integer>();
                changes.put(0d, 0); changes.put(1d, 0);
                for (var boundary : covered) {
                    for (int j = 0; j < boundary.size(); j++) {
                        Vector3 c = boundary.get(j), d = boundary.get((j + 1) % boundary.size());
                        double cx = c.x - a.x, cy = c.y - a.y, ex = d.x - a.x, ey = d.y - a.y;
                        double tolerance = 1e-5 * Math.sqrt(length2);
                        if (Math.abs(cx * dy - cy * dx) > tolerance || Math.abs(ex * dy - ey * dx) > tolerance) {
                            continue;
                        }
                        double from = (cx * dx + cy * dy) / length2, to = (ex * dx + ey * dy) / length2;
                        double start = Math.max(0, Math.min(from, to)), end = Math.min(1, Math.max(from, to));
                        if (end > start) {
                            changes.merge(start, 1, Integer::sum); changes.merge(end, -1, Integer::sum);
                        }
                    }
                }
                double previous = 0;
                int coverage = 0;
                for (var change : changes.entrySet()) {
                    double t = change.getKey();
                    if (coverage == 0 && t > previous) {
                        wall(faces, new Vector3(a).lerp(b, (float) previous), new Vector3(a).lerp(b, (float) t),
                              -BoardBridge.SLAB, 0, BoardBridge.Part.STRUCTURE, null);
                    }
                    coverage += change.getValue();
                    previous = t;
                }
            }
        }
    }

    private static void wall(List<BoardBridge.Facet> faces, Vector3 a, Vector3 b, float low, float high,
          BoardBridge.Part part, Color colour) {
        Vector3 bottomA = new Vector3(a).add(0, 0, low), topA = new Vector3(a).add(0, 0, high);
        Vector3 bottomB = new Vector3(b).add(0, 0, low), topB = new Vector3(b).add(0, 0, high);
        BoardBridge.triangle(faces, bottomA, topB, bottomB, part, colour);
        BoardBridge.triangle(faces, bottomA, topA, topB, part, colour);
    }
}
