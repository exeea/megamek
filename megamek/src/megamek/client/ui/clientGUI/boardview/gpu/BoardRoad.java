/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import com.badlogic.gdx.math.Vector2;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Cosmetic road footprints in tile pixels, shared by the road mesh and roadside vegetation/rock clearance. */
final class BoardRoad {
    enum Kind {
        NONE(0), PAVED, ALLEY, DIRT, GRAVEL;

        final float halfWidth;
        // Material choice must not pinch a continuous carriageway.
        Kind() { this(7.5f); }
        Kind(float halfWidth) { this.halfWidth = halfWidth; }
    }

    private record Point(float x, float y, float width) { }
    private record End(Point point, float nx, float ny) {
        float distance(float x, float y) { return (x - point.x) * nx + (y - point.y) * ny; }

        Area band(float from, float to) {
            return band(from, to, 128);
        }

        Area band(float from, float to, float width) {
            Path2D.Float shape = new Path2D.Float();
            shape.moveTo(point.x + from * nx - width * ny, point.y + from * ny + width * nx);
            shape.lineTo(point.x + to * nx - width * ny, point.y + to * ny + width * nx);
            shape.lineTo(point.x + to * nx + width * ny, point.y + to * ny - width * nx);
            shape.lineTo(point.x + from * nx + width * ny, point.y + from * ny - width * nx);
            shape.closePath();
            return new Area(shape);
        }
    }

    /** Both sides of a connected material change meet at half coverage on their shared edge. */
    record Join(int direction, Kind kind, End edge) {
        Area area() { return edge.band(-JOIN_REACH, 4); }
        float coverage(float x, float y) {
            return Math.clamp((edge.distance(x, y) + JOIN_REACH) / (2 * JOIN_REACH), 0, 1);
        }
        float lateral(float x, float y) {
            return -(x - edge.point.x) * edge.ny + (y - edge.point.y) * edge.nx;
        }
    }

    static final float SHOULDER = 1.5f;
    static final float WHEEL_OFFSET = 3;
    /** Overlapping passes wear a broad swath; this is not the width of a single tyre. */
    static final float TRACK_HALF_WIDTH = 1.4f;
    static final float JOIN_REACH = 16;
    static final float ROUNDABOUT_RADIUS = 18;
    private static final float END_REACH = .8f;
    private final Coords coords;
    private final List<List<Point>> paths;
    private final List<End> ends;
    private final List<Join> joins;
    private final boolean roundabout;
    private final Map<Float, Area> outlines = new HashMap<>();

    private BoardRoad(Coords coords, List<List<Point>> paths, List<End> ends, List<Join> joins, boolean roundabout) {
        this.coords = coords;
        this.paths = paths;
        this.ends = ends;
        this.joins = joins;
        this.roundabout = roundabout;
    }

    static Kind capture(Hex hex) {
        if (!hex.containsTerrain(Terrains.ROAD)) { return Kind.NONE; }
        return switch (hex.terrainLevel(Terrains.ROAD)) {
            case 1 -> Kind.PAVED;
            case 2 -> Kind.ALLEY;
            case Terrains.ROAD_LVL_DIRT -> Kind.DIRT;
            case Terrains.ROAD_LVL_GRAVEL -> Kind.GRAVEL;
            default -> Kind.NONE;
        };
    }

    /** Authored road fluff and unimplemented coexisting terrain retain their original artwork. */
    static boolean rendered(BoardScene.Tile tile) {
        return tile.road() != Kind.NONE && tile.detailedGround() && !tile.liquid().present() && !tile.frozen();
    }

    static BoardRoad of(BoardScene scene, BoardScene.Tile tile) {
        return layout(tile.coords(), tile.roadExits(), tile.road(), direction -> {
            var neighbor = scene.tile(tile.coords().translated(direction));
            return neighbor != null && (neighbor.roadExits() & (1 << ((direction + 3) % 6))) != 0
                  ? neighbor.road() : Kind.NONE;
        });
    }

    /** Capture has no scene yet. The widest supported verge conservatively clears mixed-width joins. */
    static BoardRoad clearance(Coords coords, int exits) {
        return layout(coords, exits, Kind.PAVED, direction -> Kind.PAVED);
    }

    private static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors) {
        List<Point> ends = new ArrayList<>();
        List<Join> joins = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & (1 << direction)) == 0) { continue; }
            Coords next = coords.translated(direction);
            // The true short/long lattice dimensions, rather than an assumed regular 60-degree hex.
            float x = (BoardGeometry.centerX(next) - BoardGeometry.centerX(coords)) / (2 * BoardGeometry.hexScale());
            float y = (BoardGeometry.centerY(next) - BoardGeometry.centerY(coords)) / (2 * BoardGeometry.hexScale());
            Kind neighbor = neighbors.apply(direction);
            float width = neighbor == Kind.NONE ? kind.halfWidth : (kind.halfWidth + neighbor.halfWidth) / 2;
            ends.add(new Point(x, y, width));
            if (neighbor != Kind.NONE && neighbor != kind) {
                float length = (float) Math.hypot(x, y);
                joins.add(new Join(direction, neighbor, new End(new Point(x, y, width), x / length, y / length)));
            }
        }
        List<List<Point>> paths = new ArrayList<>();
        List<End> terminals = new ArrayList<>();
        if (roundabout(exits)) {
            // A full-width circulating carriageway, with tangent entry/exit curves for each two-way approach.
            // The carriageway stays full width at the hex boundary; only its two lanes separate at the island.
            List<Point> ring = new ArrayList<>();
            for (int i = 0; i <= 96; i++) {
                double angle = i * Math.PI / 48;
                ring.add(new Point(ROUNDABOUT_RADIUS * (float) Math.cos(angle),
                      ROUNDABOUT_RADIUS * (float) Math.sin(angle), kind.halfWidth));
            }
            paths.add(ring);
            for (Point end : ends) {
                float length = (float) Math.hypot(end.x, end.y), nx = end.x / length, ny = end.y / length;
                List<Point> approachPath = new ArrayList<>();
                line(approachPath, scale(end, .78f, kind.halfWidth), scale(end, 1.06f, end.width));
                paths.add(approachPath);
                for (int side : new int[] { -1, 1 }) {
                    float width = kind.halfWidth / 2, offset = side * width;
                    List<Point> lane = new ArrayList<>();
                    Point start = local(nx, ny, length * 1.06f, offset, end.width / 2);
                    Point approach = local(nx, ny, length * .84f, offset, width);
                    line(lane, start, approach);
                    float cos = (float) Math.cos(Math.toRadians(40)), sin = (float) Math.sin(Math.toRadians(40));
                    Point join = local(nx, ny, ROUNDABOUT_RADIUS * cos, side * ROUNDABOUT_RADIUS * sin, width);
                    Point control = local(nx, ny, ROUNDABOUT_RADIUS * cos + 8 * sin,
                          side * (ROUNDABOUT_RADIUS * sin - 8 * cos), width);
                    curve(lane, approach, local(nx, ny, length * .65f, offset, width), control, join);
                    paths.add(lane);
                }
            }
        } else if (ends.size() == 2) {
            Point a = ends.get(0), b = ends.get(1);
            List<Point> path = new ArrayList<>();
            // Straight approaches occupy the existing ramp corridor. Only the flat central hub bends.
            line(path, scale(a, 1.06f, a.width), scale(a, .9f, a.width));
            line(path, scale(a, .9f, a.width), scale(a, .55f, kind.halfWidth));
            for (int i = 1; i <= 12; i++) {
                float t = i / 12f, s = 1 - t;
                path.add(new Point(.55f * (s * s * a.x + t * t * b.x),
                      .55f * (s * s * a.y + t * t * b.y), kind.halfWidth));
            }
            line(path, scale(b, .55f, kind.halfWidth), scale(b, .9f, b.width));
            line(path, scale(b, .9f, b.width), scale(b, 1.06f, b.width));
            paths.add(path);
        } else if (ends.isEmpty()) {
            float radius = Math.abs(BoardGeometry.centerY(coords.translated(0)) - BoardGeometry.centerY(coords))
                  / (2 * BoardGeometry.hexScale());
            List<Point> path = new ArrayList<>();
            line(path, new Point(0, -END_REACH * radius, kind.halfWidth), new Point(0, END_REACH * radius, kind.halfWidth));
            paths.add(path);
            terminals.add(new End(path.getFirst(), 0, -1));
            terminals.add(new End(path.getLast(), 0, 1));
        } else {
            for (Point end : ends) {
                List<Point> path = new ArrayList<>();
                if (ends.size() == 1) {
                    // A dead end occupies most of its tile, but never invents an exit into the opposite hex.
                    Point tip = scale(end, -END_REACH, kind.halfWidth);
                    line(path, tip, new Point(0, 0, kind.halfWidth));
                    float length = (float) Math.hypot(end.x, end.y);
                    terminals.add(new End(tip, -end.x / length, -end.y / length));
                }
                line(path, new Point(0, 0, kind.halfWidth), scale(end, .55f, kind.halfWidth));
                line(path, scale(end, .55f, kind.halfWidth), scale(end, .9f, end.width));
                line(path, scale(end, .9f, end.width), scale(end, 1.06f, end.width));
                paths.add(path);
            }
        }
        return new BoardRoad(coords, paths, terminals, joins, roundabout(exits));
    }

    private static Point scale(Point p, float scale, float width) { return new Point(p.x * scale, p.y * scale, width); }

    static boolean roundabout(int exits) { return Integer.bitCount(exits & 63) >= 5; }

    private static Point local(float nx, float ny, float along, float across, float width) {
        return new Point(nx * along - ny * across, ny * along + nx * across, width);
    }

    private static void curve(List<Point> path, Point a, Point b, Point c, Point d) {
        for (int i = 1; i <= 20; i++) {
            float t = i / 20f, s = 1 - t;
            path.add(new Point(s * s * s * a.x + 3 * s * s * t * b.x + 3 * s * t * t * c.x + t * t * t * d.x,
                  s * s * s * a.y + 3 * s * s * t * b.y + 3 * s * t * t * c.y + t * t * t * d.y, d.width));
        }
    }

    private static void line(List<Point> path, Point a, Point b) {
        if (path.isEmpty()) { path.add(a); }
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / 4));
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            path.add(new Point(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.width + t * (b.width - a.width)));
        }
    }

    /** Union before tessellation: junctions have one surface, without overlapping translucent road arms. */
    Area footprint(float margin) {
        // Road patches reuse several margins; callers subtract their own masks from a fresh copy.
        return new Area(outlines.computeIfAbsent(margin, this::outline));
    }

    private Area outline(float margin) {
        Area area = new Area();
        for (var path : paths) {
            if (roundabout) {
                // Each lane has constant width. Stroke it once instead of unioning hundreds of tiny discs.
                Path2D.Float lane = new Path2D.Float();
                lane.moveTo(path.getFirst().x, path.getFirst().y);
                for (int i = 1; i < path.size(); i++) { lane.lineTo(path.get(i).x, path.get(i).y); }
                float width = 2 * Math.max(.1f, path.getFirst().width + margin);
                area.add(new Area(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                      .createStrokedShape(lane)));
                continue;
            }
            for (int i = 0; i < path.size(); i++) {
                Point a = path.get(i);
                float radius = Math.max(.1f, a.width + margin);
                area.add(new Area(new Ellipse2D.Float(a.x - radius, a.y - radius, 2 * radius, 2 * radius)));
                if (i == 0) { continue; }
                Point b = path.get(i - 1);
                float length = (float) Math.hypot(b.x - a.x, b.y - a.y);
                if (length < .001f) { continue; }
                float nx = (b.y - a.y) / length, ny = (a.x - b.x) / length;
                float previous = Math.max(.1f, b.width + margin);
                Path2D.Float quad = new Path2D.Float();
                quad.moveTo(a.x + nx * radius, a.y + ny * radius);
                quad.lineTo(b.x + nx * previous, b.y + ny * previous);
                quad.lineTo(b.x - nx * previous, b.y - ny * previous);
                quad.lineTo(a.x - nx * radius, a.y - ny * radius);
                quad.closePath();
                area.add(new Area(quad));
            }
        }
        if (roundabout) {
            // Round the concave corners where approach envelopes meet the ring. Closing fills those small
            // notches without narrowing straight approaches or reducing the circulating carriageway.
            BasicStroke fillet = new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            Area rounded = new Area(area);
            rounded.add(new Area(fillet.createStrokedShape(rounded)));
            rounded.subtract(new Area(fillet.createStrokedShape(rounded)));
            area.add(rounded);
        }
        clipEnds(area, margin);
        return area;
    }

    /** Clip only real termini. Curves and the shared hub of a junction keep their continuous joins. */
    private void clipEnds(Area area, float margin) {
        for (End end : ends) { area.intersect(end.band(-128, margin)); }
    }

    Area endZone(float length) {
        Area result = new Area();
        if (length > 0) {
            for (End end : ends) { result.add(end.band(-length, SHOULDER)); }
        }
        return result;
    }

    float endDistance(float x, float y) {
        float distance = Float.NEGATIVE_INFINITY;
        for (End end : ends) { distance = Math.max(distance, end.distance(x, y)); }
        return distance;
    }

    List<Join> joins() { return joins; }

    /** Symmetric feathering changes tyre wear intensity, never the axle spacing or tyre width. */
    float wheelCoverage(float x, float y) {
        float nearest = pathSample(x, y).distance();
        float t = Math.clamp((TRACK_HALF_WIDTH - Math.abs(nearest - WHEEL_OFFSET)) / TRACK_HALF_WIDTH, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Disturbed soil/fines thin out in patches; their final extent still stays inside the terminal plane. */
    float endCoverage(float x, float y, float length) {
        float t = Math.clamp(-endDistance(x, y) / length, 0, 1);
        float wx = x + BoardGeometry.centerX(coords) / BoardGeometry.hexScale();
        float wy = y + BoardGeometry.centerY(coords) / BoardGeometry.hexScale();
        float noise = BoardRelief.noise(wx / 2.1f, wy / 2.1f);
        t = Math.clamp(t + (noise - .5f) * 5 * t * (1 - t), 0, 1);
        return t * t * (3 - 2 * t);
    }

    float distance(float x, float y) {
        return Math.max(pathSample(x, y).edge(), endDistance(x, y));
    }

    /** The same nearest path supplies the wheel mask and the direction of shallow, elongated scuffs. */
    Vector2 wheelDirection(float x, float y) {
        PathSample sample = pathSample(x, y);
        return new Vector2(sample.dx(), sample.dy()).nor();
    }

    private record PathSample(float distance, float edge, float dx, float dy) { }

    private PathSample pathSample(float x, float y) {
        float distance = Float.POSITIVE_INFINITY, edge = Float.POSITIVE_INFINITY, alongX = 0, alongY = 1;
        for (var path : paths) {
            for (int i = 1; i < path.size(); i++) {
                Point a = path.get(i - 1), b = path.get(i);
                float dx = b.x - a.x, dy = b.y - a.y;
                float t = Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy), 0, 1);
                float near = (float) Math.hypot(x - a.x - t * dx, y - a.y - t * dy);
                edge = Math.min(edge, near - a.width - t * (b.width - a.width));
                if (near < distance) { distance = near; alongX = dx; alongY = dy; }
            }
        }
        return new PathSample(distance, edge, alongX, alongY);
    }

    /** Short dashes stay out of junctions; their phase is anchored in board space along each approach. */
    Area markings(Coords coords, int exits) {
        Area area = new Area();
        if (exits == 0) { return area; }
        if (roundabout(exits)) {
            // End the approach separator before the circulating lane. No straight-through dashes cross the island.
            int remaining = exits;
            for (int i = 1; i < paths.size(); i += 3) {
                int arm = Integer.lowestOneBit(remaining);
                remaining &= ~arm;
                if (joins.stream().anyMatch(join -> (arm & (1 << join.direction())) != 0 && join.kind() != Kind.PAVED)) {
                    continue;
                }
                Point end = paths.get(i).getLast();
                float x = end.x, y = end.y;
                Path2D.Float separator = new Path2D.Float();
                separator.moveTo(x, y);
                separator.lineTo(x * .8f, y * .8f);
                area.add(new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                      .createStrokedShape(separator)));
            }
            return area;
        }
        boolean junction = Integer.bitCount(exits) > 2;
        int markedExits = exits;
        for (Join join : joins) {
            if (join.kind() != Kind.PAVED) { markedExits &= ~(1 << join.direction()); }
        }
        int throughExits = 0;
        for (int d = 0; d < 3; d++) {
            int pair = (1 << d) | (1 << (d + 3));
            if ((markedExits & pair) == pair) { throughExits |= pair; }
        }
        int remaining = exits;
        for (var points : paths) {
            int arm = Integer.lowestOneBit(remaining);
            remaining &= ~arm;
            if (junction && (markedExits & arm) == 0) { continue; }
            Point start = points.getFirst(), end = points.getLast();
            if (end.x < start.x || end.x == start.x && end.y < start.y) { points = points.reversed(); }
            Path2D.Float path = new Path2D.Float();
            Point first = points.getFirst(), last = points.getLast();
            path.moveTo(first.x, first.y);
            for (int i = 1; i < points.size(); i++) { path.lineTo(points.get(i).x, points.get(i).y); }
            float length = (float) Math.hypot(last.x - first.x, last.y - first.y);
            float worldX = BoardGeometry.centerX(coords) / BoardGeometry.hexScale() + first.x;
            float worldY = BoardGeometry.centerY(coords) / BoardGeometry.hexScale() + first.y;
            float phase = (worldX * (last.x - first.x) + worldY * (last.y - first.y)) / length;
            phase = (phase % 13 + 13) % 13;
            Area paint = new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                  10, new float[] { 7, 6 }, phase).createStrokedShape(path));
            if (junction && (throughExits & arm) == 0) {
                paint.subtract(new Area(new Ellipse2D.Float(-12, -12, 24, 24)));
            }
            area.add(paint);
        }
        for (Join join : joins) {
            if (junction || join.kind() == Kind.PAVED) { continue; }
            End edge = join.edge();
            area.subtract(edge.band(-8, 128, edge.point.width + 1));
        }
        clipEnds(area, -3);
        return area;
    }

    /** Two worn wheel paths make an unsealed road legible even where its soil matches the surrounding sand. */
    Area tracks(int exits) {
        Area result = new Area();
        for (var points : paths) {
            for (float offset : new float[] { -WHEEL_OFFSET, WHEEL_OFFSET }) {
                Path2D.Float path = new Path2D.Float();
                for (int i = 0; i < points.size(); i++) {
                    Point p = points.get(i), before = points.get(Math.max(0, i - 1));
                    Point after = points.get(Math.min(points.size() - 1, i + 1));
                    float dx = after.x - before.x, dy = after.y - before.y;
                    float length = (float) Math.hypot(dx, dy);
                    float x = p.x - dy / length * offset, y = p.y + dx / length * offset;
                    if (i == 0) { path.moveTo(x, y); } else { path.lineTo(x, y); }
                }
                Area track = new Area(new BasicStroke(2 * TRACK_HALF_WIDTH, BasicStroke.CAP_BUTT,
                      BasicStroke.JOIN_ROUND).createStrokedShape(path));
                clipEnds(track, offset < 0 ? -2 : 0);
                result.add(track);
            }
        }
        if (Integer.bitCount(exits) > 2) { result.subtract(new Area(new Ellipse2D.Float(-10, -10, 20, 20))); }
        return result;
    }
}
