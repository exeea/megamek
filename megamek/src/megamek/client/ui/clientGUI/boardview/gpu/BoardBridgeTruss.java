/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;

/** Pony-truss members derived from the same live carriageway as the deck, in local board-model units. */
final class BoardBridgeTruss {
    private static final List<RigidGlb.ColourSlot.Preset> PAINTS = List.of(
          new RigidGlb.ColourSlot.Preset("Oxide red", "#732417"),
          new RigidGlb.ColourSlot.Preset("Red", "#a6140a"),
          new RigidGlb.ColourSlot.Preset("Orange", "#d1611a"),
          new RigidGlb.ColourSlot.Preset("Yellow", "#dba31f"),
          new RigidGlb.ColourSlot.Preset("Green", "#2e5233"),
          new RigidGlb.ColourSlot.Preset("Olive", "#4c5730"),
          new RigidGlb.ColourSlot.Preset("Blue", "#24407a"),
          new RigidGlb.ColourSlot.Preset("Grey", "#808585"),
          new RigidGlb.ColourSlot.Preset("Gunmetal", "#3d454c"),
          new RigidGlb.ColourSlot.Preset("Silver", "#b8bab8"),
          new RigidGlb.ColourSlot.Preset("Cream", "#d6ccab"),
          new RigidGlb.ColourSlot.Preset("Black", "#212124"));
    /** The runtime steel and editor swatches share the same Body slot and default paint. */
    static final RigidGlb.ColourSlot BODY = new RigidGlb.ColourSlot("Body", PAINTS.getFirst().colour(), PAINTS);
    private static final float BOTTOM = -2, BAND = 1.2f, TOP = 12.5f, FOOT = .8f;
    private static final double OFFSET = .75, PANEL = 12, SEAT = .7;
    private static final Section TOP_CHORD = new Section(1.5f, 1.3f), END_POST = new Section(1.4f, 1.3f),
          VERTICAL = new Section(1.1f, .8f), DIAGONAL = new Section(1, .7f);
    private static final List<Point> CORNERS = IntStream.range(0, 6).mapToObj(BoardGeometry::modelCorner)
          .map(p -> new Point(p.x, p.y)).toList();
    private static final int[][] EDGES = { { 2, 1 }, { 1, 0 }, { 0, 5 }, { 5, 4 }, { 4, 3 }, { 3, 2 } };

    private BoardBridgeTruss() { }

    private record Point(double x, double y) {
        Point add(Point p) { return new Point(x + p.x, y + p.y); }
        Point sub(Point p) { return new Point(x - p.x, y - p.y); }
        Point scale(double amount) { return new Point(x * amount, y * amount); }
        double dot(Point p) { return x * p.x + y * p.y; }
        double length() { return Math.hypot(x, y); }
        Point unit() { return scale(1 / length()); }
        Vector3 at(float z) { return new Vector3((float) x, (float) y, z); }
    }

    private record Section(float width, float depth) { }
    private record Edge(Point middle, Point along, Point inward) { }
    private record Chain(List<Point> points, int first, int last) { }
    private record Plane(Vector3 point, Vector3 normal) { }
    private record Surface(List<Vector3> points, Vector3 outward, boolean visible) { }

    static Area append(List<BoardBridge.Facet> faces, Area carriageway, Area kerbs, BoardBridge.Deck bridge) {
        String replacement = bridge.colours().slot(0);
        // Authored Body vertices were uniformly coloured: this is the same linear replacement as RigidGlb.recolour.
        Color colour = GpuColor.linear(Color.valueOf(replacement == null ? BODY.colour() : replacement));
        Area steel = new Area(), concrete = new Area();
        for (var contour : BoardBridgeMesh.contours(kerbs)) {
            Area loop = area(contour);
            if (contour.stream().anyMatch(p -> exit(new Point(p.x, p.y), bridge.exits()) >= 0)) {
                steel.exclusiveOr(loop);
            } else {
                concrete.exclusiveOr(loop);
            }
        }
        BoardBridgeMesh.cap(faces, steel, BAND, true, BoardBridge.Part.STEEL, colour);
        BoardBridgeMesh.cap(faces, steel, BOTTOM, false, BoardBridge.Part.STEEL, colour);
        BoardBridgeMesh.walls(faces, steel, BOTTOM, BAND, BoardBridge.Part.STEEL, colour);
        BoardBridgeMesh.cap(faces, concrete, 2.5f, true, BoardBridge.Part.STRUCTURE, null);
        BoardBridgeMesh.walls(faces, concrete, 0, 2.5f, BoardBridge.Part.STRUCTURE, null);
        Builder builder = new Builder(faces, colour);
        Path2D.Double boundary = new Path2D.Double();
        for (var contour : BoardBridgeMesh.contours(carriageway)) { boundary.append(path(contour), false); }
        // Stroke only exposed boundaries: Area can retain internal Boolean seams which are not structural edges.
        Area outside = new Area(carriageway);
        outside.add(new Area(new BasicStroke((float) (2 * OFFSET), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
              .createStrokedShape(boundary)));
        outside.intersect(area(CORNERS.stream().map(p -> p.at(0)).toList()));
        for (var chain : chains(outside, bridge.exits())) {
            truss(builder, chain.points(), chain.first(), chain.last(), bridge.joints());
        }
        return steel;
    }

    private static Area area(List<Vector3> points) {
        return new Area(path(points));
    }

    private static Path2D path(List<Vector3> points) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(points.getFirst().x, points.getFirst().y);
        for (int i = 1; i < points.size(); i++) { path.lineTo(points.get(i).x, points.get(i).y); }
        path.closePath();
        return path;
    }

    private static Edge edge(int direction) {
        Point a = CORNERS.get(EDGES[direction][0]), b = CORNERS.get(EDGES[direction][1]);
        Point along = b.sub(a).unit(), middle = a.add(b).scale(.5);
        Point inward = new Point(-along.y(), along.x());
        if (inward.dot(middle) > 0) { inward = inward.scale(-1); }
        return new Edge(middle, along, inward);
    }

    private static int exit(Point point, int exits) {
        int result = -1;
        for (int d = 0; d < 6; d++) {
            if ((exits & (1 << d)) == 0) { continue; }
            Point a = CORNERS.get(EDGES[d][0]), b = CORNERS.get(EDGES[d][1]);
            if (distance(point, a, b) < .001) {
                if (result >= 0) { return -1; }
                result = d;
            }
        }
        return result;
    }

    /** Open exit mouths divide each closed deck boundary into the kerbs which carry one continuous truss. */
    private static List<Chain> chains(Area deck, int exits) {
        List<Chain> result = new ArrayList<>();
        for (var contour : BoardBridgeMesh.contours(deck)) {
            List<Point> loop = clean(contour.stream().map(p -> new Point(p.x, p.y)).toList());
            int count = loop.size(), start = -1;
            boolean[] mouth = new boolean[count];
            for (int i = 0; i < count; i++) {
                int d = exit(loop.get(i), exits);
                mouth[i] = d >= 0 && d == exit(loop.get((i + 1) % count), exits);
                if (mouth[i] && start < 0) { start = i; }
            }
            if (start < 0) { continue; }
            int i = (start + 1) % count;
            do {
                List<Point> points = new ArrayList<>();
                points.add(loop.get(i));
                while (!mouth[i]) {
                    i = (i + 1) % count;
                    points.add(loop.get(i));
                }
                if (points.size() > 1) {
                    result.add(new Chain(points, exit(points.getFirst(), exits), exit(points.getLast(), exits)));
                }
                i = (i + 1) % count;
            } while (i != (start + 1) % count);
        }
        return result;
    }

    private static List<Point> clean(List<Point> points) {
        List<Point> result = new ArrayList<>();
        for (Point point : points) {
            if (result.isEmpty() || point.sub(result.getLast()).length() > .0001) { result.add(point); }
        }
        if (result.size() > 1 && result.getFirst().sub(result.getLast()).length() < .0001) { result.removeLast(); }
        // Area's Boolean operations split straight edges at float-rounded intersections. Offsetting these tiny
        // artificial corners magnifies their rounding into backward steps; retain the same boundary to its precision.
        for (int i = 1; i + 1 < result.size();) {
            if (distance(result.get(i), result.get(i - 1), result.get(i + 1)) < .0001) { result.remove(i); }
            else { i++; }
        }
        return result;
    }

    private static double distance(Point point, Point a, Point b) {
        Point span = b.sub(a);
        double squared = span.dot(span);
        double t = squared == 0 ? 0 : Math.clamp(point.sub(a).dot(span) / squared, 0, 1);
        return point.sub(a.add(span.scale(t))).length();
    }

    private static Point at(List<Point> line, double[] along, double position) {
        for (int i = 0; i + 1 < line.size(); i++) {
            if (position <= along[i + 1] + 1e-9 || i == line.size() - 2) {
                double t = Math.clamp((position - along[i]) / (along[i + 1] - along[i]), 0, 1);
                return line.get(i).add(line.get(i + 1).sub(line.get(i)).scale(t));
            }
        }
        return line.getLast();
    }

    /**
     * Warren panels keep even parity across a joint; their nodes do not simplify the upper chord's contour. A run of
     * sharp points joined by short segments (a rounded join) is one corner; corners a real edge apart, such as the two
     * of a dead end, each take a node.
     */
    private static List<Point> nodes(List<Point> line) {
        double[] along = new double[line.size()];
        for (int i = 1; i < line.size(); i++) { along[i] = along[i - 1] + line.get(i).sub(line.get(i - 1)).length(); }
        boolean[] sharp = new boolean[line.size()];
        List<Integer> turns = new ArrayList<>();
        for (int i = 1; i + 1 < line.size(); i++) {
            Point before = line.get(i).sub(line.get(i - 1)).unit(), after = line.get(i + 1).sub(line.get(i)).unit();
            double turn = Math.toDegrees(Math.acos(Math.clamp(before.dot(after), -1, 1)));
            sharp[i] = turn > 30 || turn / ((along[i + 1] - along[i - 1]) / 2) > 4;
            if (distance(line.get(i), line.get(i - 1), line.get(i + 1)) > .0001) { turns.add(i); }
        }
        List<Double> stops = new ArrayList<>();
        stops.add(0.0);
        for (int i = 1; i + 1 < line.size(); i++) {
            if (!sharp[i]) { continue; }
            int end = i;
            while (end + 2 < line.size() && sharp[end + 1] && along[end + 1] - along[end] < PANEL / 2) { end++; }
            double middle = (along[i] + along[end]) / 2;
            int corner = i;
            for (int j = i + 1; j <= end; j++) {
                if (Math.abs(along[j] - middle) < Math.abs(along[corner] - middle)) { corner = j; }
            }
            stops.add(along[corner]);
            i = end;
        }
        stops.add(along[along.length - 1]);
        int[] panels = new int[stops.size() - 1];
        int count = 0, longest = 0;
        for (int i = 0; i < panels.length; i++) {
            panels[i] = Math.max(1, (int) Math.rint((stops.get(i + 1) - stops.get(i)) / PANEL));
            count += panels[i];
            if ((stops.get(i + 1) - stops.get(i)) / panels[i]
                  > (stops.get(longest + 1) - stops.get(longest)) / panels[longest]) { longest = i; }
        }
        if (count % 2 != 0) { panels[longest]++; }
        List<Double> positions = new ArrayList<>();
        positions.add(0.0);
        for (int i = 0; i < panels.length; i++) {
            for (int k = 1; k <= panels[i]; k++) {
                positions.add(stops.get(i) + (stops.get(i + 1) - stops.get(i)) * k / panels[i]);
            }
        }
        if (!turns.isEmpty()) {
            double first = along[turns.getFirst()] - SEAT, last = along[turns.getLast()] + SEAT;
            if (first > 0 && first < last && last < along[along.length - 1] && positions.size() == 3) {
                positions = new ArrayList<>(List.of(0.0, first, (first + last) / 2, last, along[along.length - 1]));
            } else if (first > 0 && first < last && last < along[along.length - 1]) {
                positions.set(1, Math.min(positions.get(1), first));
                positions.set(positions.size() - 2, Math.max(positions.get(positions.size() - 2), last));
            }
        }
        double allowance = OFFSET - Math.max(VERTICAL.width(), DIAGONAL.width()) / 2;
        while (true) {
            List<Double> refined = new ArrayList<>();
            refined.add(positions.getFirst());
            for (int i = 1; i < positions.size(); i++) {
                double start = positions.get(i - 1), end = positions.get(i), worst = 0;
                Point a = at(line, along, start), b = at(line, along, end);
                for (int j = 0; j < line.size(); j++) {
                    if (along[j] > start && along[j] < end) { worst = Math.max(worst, distance(line.get(j), a, b)); }
                }
                double clearance = i == 1 || i == positions.size() - 1
                      ? OFFSET - END_POST.width() / 2 : allowance;
                if (worst > clearance) {
                    refined.add(start + (end - start) / 3);
                    refined.add(start + 2 * (end - start) / 3);
                }
                refined.add(end);
            }
            if (refined.size() == positions.size()) { break; }
            positions = refined;
        }
        return positions.stream().map(position -> at(line, along, position)).toList();
    }

    private static int segment(List<Point> line, Point point) {
        int result = 0;
        for (int i = 1; i + 1 < line.size(); i++) {
            if (distance(point, line.get(i), line.get(i + 1))
                  < distance(point, line.get(result), line.get(result + 1))) { result = i; }
        }
        return result;
    }

    private static Plane join(List<Point> chord, int i) {
        Point normal = chord.get(i).sub(chord.get(i - 1)).unit().add(chord.get(i + 1).sub(chord.get(i)).unit()).unit();
        return new Plane(chord.get(i).at(0), normal.at(0));
    }

    private static Vector3 across(Vector3 direction) { return new Vector3(-direction.y, direction.x, 0).nor(); }

    private static void truss(Builder builder, List<Point> line, int first, int last, int joints) {
        List<Point> points = nodes(line);
        int n = points.size() - 1;
        float centre = TOP - TOP_CHORD.depth() / 2, high = TOP - TOP_CHORD.depth();
        double[] along = new double[line.size()];
        for (int i = 1; i < line.size(); i++) { along[i] = along[i - 1] + line.get(i).sub(line.get(i - 1)).length(); }
        int startSegment = segment(line, points.get(1)), endSegment = segment(line, points.get(n - 1));
        double startAlong = (joints & (1 << first)) != 0 ? 0
              : Math.max(0, along[startSegment] + points.get(1).sub(line.get(startSegment)).length() - SEAT);
        double endAlong = (joints & (1 << last)) != 0 ? along[along.length - 1]
              : Math.min(along[along.length - 1], along[endSegment]
                    + points.get(n - 1).sub(line.get(endSegment)).length() + SEAT);
        List<Point> chord = new ArrayList<>();
        chord.add(at(line, along, startAlong));
        for (int i = 1; i + 1 < line.size(); i++) {
            if (along[i] > startAlong && along[i] < endAlong) { chord.add(line.get(i)); }
        }
        chord.add(at(line, along, endAlong));
        chord = clean(chord);
        for (int i = 0; i + 1 < chord.size(); i++) {
            Vector3 a = chord.get(i).at(centre), b = chord.get(i + 1).at(centre), direction = new Vector3(b).sub(a).nor();
            boolean atStart = i == 0, atEnd = i + 2 == chord.size();
            Plane firstPlane = startAlong == 0 ? new Plane(edge(first).middle().at(0), edge(first).inward().at(0)) : null;
            Plane lastPlane = endAlong == along[along.length - 1]
                  ? new Plane(edge(last).middle().at(0), edge(last).inward().at(0)) : null;
            builder.member(a, b, across(direction), TOP_CHORD, atStart, atEnd,
                  atStart ? firstPlane : join(chord, i), atEnd ? lastPlane : join(chord, i + 1));
        }
        for (int i = 1; i < n; i++) {
            Point tangent = points.get(i + 1).sub(points.get(i - 1)).unit();
            builder.vertical(points.get(i), tangent, BAND, high);
        }
        for (int i = 1; i < n - 1; i++) {
            builder.diagonal(points.get(i).at(i % 2 == 0 ? FOOT : centre),
                  points.get(i + 1).at(i % 2 == 0 ? centre : FOOT));
        }
        for (int end : new int[] { 0, n }) {
            int inner = end == 0 ? 1 : n - 1, direction = end == 0 ? first : last;
            Point point = points.get(end), approach = points.get(inner).sub(point).unit();
            Edge edge = edge(direction);
            Point setback = approach.scale(1 / approach.dot(edge.inward()));
            if ((joints & (1 << direction)) != 0) {
                List<Point> half = new ArrayList<>();
                half.add(point.add(edge.along().scale(-VERTICAL.width() / 2)));
                half.add(point.add(edge.along().scale(VERTICAL.width() / 2)));
                half.add(half.get(1).add(edge.inward().scale(VERTICAL.depth() / 2)));
                half.add(half.getFirst().add(edge.inward().scale(VERTICAL.depth() / 2)));
                builder.verticalRing(half, point.add(edge.inward().scale(VERTICAL.depth() / 4)), BAND, high);
                builder.diagonal(point.add(setback.scale(.7)).at(FOOT), points.get(inner).at(centre));
            } else {
                Vector3 a = point.add(setback.scale(.9)).at(.3f), b = points.get(inner).at(centre);
                builder.member(a, b, across(new Vector3(b).sub(a)), END_POST, false, false, null, null);
            }
        }
    }

    /** Owns only this rebuild's emitted facets; no GL resources or retained mutable geometry. */
    private record Builder(List<BoardBridge.Facet> faces, Color colour) {
        /** Clip each closed member before triangulation, closing any cut at the shared tile plane. */
        void solid(List<Surface> surfaces) {
            for (int edge = 0; edge < CORNERS.size(); edge++) {
                Vector3 a = CORNERS.get(edge).at(0), b = CORNERS.get((edge + 1) % CORNERS.size()).at(0);
                Vector3 along = new Vector3(b).sub(a).nor(), outward = new Vector3(along.y, -along.x, 0);
                if (surfaces.stream().flatMap(s -> s.points().stream())
                      .noneMatch(p -> new Vector3(p).sub(a).dot(outward) > .00001f)) { continue; }
                List<Surface> clipped = new ArrayList<>();
                List<Vector3> cut = new ArrayList<>();
                for (Surface surface : surfaces) {
                    List<Vector3> polygon = BoardTacticalGeometry.clip(surface.points(), a, b);
                    if (polygon.size() < 3) { continue; }
                    clipped.add(new Surface(polygon, surface.outward(), surface.visible()));
                    for (Vector3 point : polygon) {
                        if (Math.abs(new Vector3(point).sub(a).dot(outward)) < .00001f
                              && cut.stream().noneMatch(p -> p.dst2(point) < 1e-10f)) { cut.add(point); }
                    }
                }
                if (cut.size() >= 3) {
                    Vector3 centre = new Vector3();
                    cut.forEach(centre::add);
                    centre.scl(1f / cut.size());
                    cut.sort(Comparator.comparingDouble(p -> Math.atan2(p.z - centre.z,
                          new Vector3(p).sub(centre).dot(along))));
                    clipped.add(new Surface(cut, outward, true));
                }
                surfaces = clipped;
            }
            for (Surface surface : surfaces) {
                if (surface.visible()) { face(surface.points(), surface.outward()); }
            }
        }

        void face(List<Vector3> points, Vector3 outward) {
            Vector3 normal = new Vector3(points.get(1)).sub(points.getFirst())
                  .crs(new Vector3(points.get(2)).sub(points.getFirst()));
            for (int i = 3; i < points.size() && normal.len2() < 1e-12f; i++) {
                normal.set(points.get(i - 1)).sub(points.getFirst()).crs(new Vector3(points.get(i)).sub(points.getFirst()));
            }
            if (normal.len2() < 1e-12f) { return; }
            boolean reverse = normal.dot(outward) < 0;
            for (int i = 1; i + 1 < points.size(); i++) {
                BoardBridge.triangle(faces, points.getFirst(), points.get(reverse ? i + 1 : i),
                      points.get(reverse ? i : i + 1), BoardBridge.Part.STEEL, colour);
            }
        }

        void member(Vector3 a, Vector3 b, Vector3 across, Section section, boolean capA, boolean capB,
              Plane clipA, Plane clipB) {
            Vector3 axis = new Vector3(b).sub(a).nor(), depth = new Vector3(axis).crs(across).nor();
            var first = ring(a, across, depth, axis, section, clipA);
            var last = ring(b, across, depth, axis, section, clipB);
            Vector3 centre = new Vector3(a).add(b).scl(.5f);
            List<Surface> surfaces = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                List<Vector3> quad = List.of(first.get(i), first.get((i + 1) % 4), last.get((i + 1) % 4), last.get(i));
                Vector3 middle = new Vector3();
                quad.forEach(middle::add);
                middle.scl(.25f).sub(centre);
                Vector3 outward = new Vector3(middle).mulAdd(axis, -middle.dot(axis));
                surfaces.add(new Surface(quad, outward, true));
            }
            // Hidden end faces still bound the solid when a tile plane cuts through an inclined member.
            surfaces.add(new Surface(first, new Vector3(axis).scl(-1), capA));
            surfaces.add(new Surface(last, axis, capB));
            solid(surfaces);
        }

        private List<Vector3> ring(Vector3 end, Vector3 across, Vector3 depth, Vector3 axis,
              Section section, Plane clip) {
            List<Vector3> points = new ArrayList<>();
            for (int[] corner : new int[][] { { 1, 1 }, { -1, 1 }, { -1, -1 }, { 1, -1 } }) {
                Vector3 p = new Vector3(end).mulAdd(across, corner[0] * section.width() / 2)
                      .mulAdd(depth, corner[1] * section.depth() / 2);
                if (clip != null) {
                    p.mulAdd(axis, new Vector3(clip.point()).sub(p).dot(clip.normal()) / axis.dot(clip.normal()));
                }
                points.add(p);
            }
            return points;
        }

        void vertical(Point point, Point tangent, float low, float high) {
            Point across = new Point(-tangent.y(), tangent.x());
            List<Point> ring = new ArrayList<>();
            for (int[] corner : new int[][] { { 1, 1 }, { -1, 1 }, { -1, -1 }, { 1, -1 } }) {
                ring.add(point.add(across.scale(corner[0] * VERTICAL.width() / 2))
                      .add(tangent.scale(corner[1] * VERTICAL.depth() / 2)));
            }
            verticalRing(ring, point, low, high);
        }

        void verticalRing(List<Point> ring, Point centre, float low, float high) {
            List<Surface> surfaces = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Point a = ring.get(i), b = ring.get((i + 1) % 4);
                surfaces.add(new Surface(List.of(a.at(low), b.at(low), b.at(high), a.at(high)),
                      a.add(b).scale(.5).sub(centre).at(0), true));
            }
            surfaces.add(new Surface(ring.stream().map(p -> p.at(low)).toList(), new Vector3(0, 0, -1), false));
            surfaces.add(new Surface(ring.stream().map(p -> p.at(high)).toList(), new Vector3(0, 0, 1), false));
            solid(surfaces);
        }

        void diagonal(Vector3 a, Vector3 b) {
            member(a, b, across(new Vector3(b).sub(a)), DIAGONAL, false, false, null, null);
        }
    }
}


