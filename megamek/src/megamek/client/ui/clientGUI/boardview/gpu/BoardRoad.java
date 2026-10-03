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
import java.util.function.Function;
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
        Area area() { return edge.band(-JOIN_REACH, 128); }
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
    /** The largest angle between a bent road and the normal of the border it crosses. */
    private static final float MAX_BEND = (float) Math.toRadians(40);
    /** A square approach runs straight in to this share of its border's distance from the centre, holding a ramp. */
    private static final float CORRIDOR = .55f;
    /** Centre-line dash and gap lengths; a road fits whole dashes between the borders it crosses. */
    private static final float DASH = 7, GAP = 6;
    private final Coords coords;
    private final List<List<Point>> paths;
    /** Where the road crosses each border it leaves by: its dashes are anchored there, identically on both sides. */
    private final List<Point> borders;
    private final List<End> ends;
    private final List<Join> joins;
    private final boolean roundabout;
    private final Map<Float, Area> outlines = new HashMap<>();

    /** What a road's course needs of one hex, whether from a scene tile or a captured hex. */
    record Node(int exits, int elevation, boolean plain) {
        static Node of(BoardScene.Tile tile) {
            return tile == null ? null : new Node(tile.roadExits() & 63, tile.elevation(), rendered(tile)
                  && tile.features().stream().noneMatch(feature -> feature.asset().equals("bridge")));
        }

        static Node of(Hex hex) {
            if (hex == null) { return null; }
            int exits = hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0;
            return new Node(exits, hex.getLevel(), capture(hex) != Kind.NONE && !hex.containsTerrain(Terrains.WATER)
                  && !hex.containsTerrain(Terrains.BRIDGE));
        }

        /** A plain two-way road, whose course may bend through its borders. */
        boolean simple() { return plain && Integer.bitCount(exits) == 2; }

        /** Opposite exits already describe a straight course through this hex. */
        boolean straight() { return simple() && (exits & (exits >> 3)) != 0; }
    }

    private BoardRoad(Coords coords, List<List<Point>> paths, List<Point> borders, List<End> ends, List<Join> joins,
          boolean roundabout) {
        this.coords = coords;
        this.paths = paths;
        this.borders = borders;
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

    /** Unsupported road decoration and coexisting terrain retain their original artwork. */
    static boolean rendered(BoardScene.Tile tile) {
        return tile.road() != Kind.NONE && tile.detailedGround() && !tile.liquid().present();
    }

    static BoardRoad of(BoardScene scene, BoardScene.Tile tile) {
        return layout(tile.coords(), tile.roadExits(), tile.road(), direction -> {
            var neighbor = scene.tile(tile.coords().translated(direction));
            if (neighbor != null && BoardSurface.connectingBridge(tile, neighbor, direction) != null) {
                return BoardBridge.kind(scene, neighbor);
            }
            return neighbor != null && (neighbor.roadExits() & (1 << ((direction + 3) % 6))) != 0
                  ? neighbor.road() : Kind.NONE;
        }, bends(tile.coords(), at -> Node.of(scene.tile(at))));
    }

    /** Capture has no scene yet. The widest supported verge conservatively clears mixed-width joins. */
    static BoardRoad clearance(Coords coords, int exits) {
        return layout(coords, exits, Kind.PAVED, direction -> Kind.PAVED, direction -> null);
    }

    /** As {@link #clearance(Coords, int)}, following the course the scene will give the road through its borders. */
    static BoardRoad clearance(Coords coords, Hex hex, Function<Coords, Hex> board) {
        return layout(coords, hex.getTerrain(Terrains.ROAD).getExits(), Kind.PAVED, direction -> Kind.PAVED,
              bends(coords, at -> Node.of(at.equals(coords) ? hex : board.apply(at))));
    }

    /**
     * Where a road runs on at one level through plain two-way hexes, it crosses their shared border as a real road
     * does, instead of turning at every hex centre. Between two such crossings it follows the line from the border
     * before to the border after. Other crossings (junctions, ends, bridges, climbs) stay square to their border and
     * hold a straight corridor; next to one, the crossing turns so that the hex's whole course into that corridor is
     * one circular arc, rather than a late sharp turn at its mouth. Both hexes derive the same crossing; the result is
     * its direction, pointing out of this hex, or null for a square crossing. Opposite-exit roads keep their straight
     * axis; their turning neighbours approach that same axis without pulling the straight road sideways.
     */
    static IntFunction<Vector2> bends(Coords coords, Function<Coords, Node> nodes) {
        return direction -> {
            if (!bent(coords, direction, nodes)) { return null; }
            Coords after = coords.translated(direction);
            int mine = other(nodes.apply(coords), direction), theirs = other(nodes.apply(after), (direction + 3) % 6);
            Vector2 normal = border(coords, direction);
            // A neighbouring turn must not pull an otherwise straight road into an S-bend. Both sides use this
            // same tangent; keeping it non-null lets the turning hex use its full width for a broad approach.
            if (nodes.apply(coords).straight() || nodes.apply(after).straight()) { return normal.nor(); }
            Vector2 offset = new Vector2(BoardGeometry.centerX(after) - BoardGeometry.centerX(coords),
                  BoardGeometry.centerY(after) - BoardGeometry.centerY(coords)).scl(1 / BoardGeometry.hexScale());
            Vector2 before = border(coords, mine), beyond = border(after, theirs);
            boolean held = !bent(coords, mine, nodes), holds = !bent(after, theirs, nodes);
            Vector2 tangent = held || holds ? new Vector2() : new Vector2(beyond).add(offset).sub(before);
            if (held) { tangent.sub(arc(Vector2.Zero, before, normal)); }
            if (holds) { tangent.add(arc(offset, beyond, normal)); }
            normal.nor();
            if (tangent.len2() < 1e-8f) { return normal; }
            tangent.nor();
            float along = tangent.dot(normal);
            if (along >= (float) Math.cos(MAX_BEND)) { return tangent; }
            // A sharp turn keeps its crossing within MAX_BEND of square, turned towards the same side.
            Vector2 aside = new Vector2(tangent).mulAdd(normal, -along);
            if (aside.len2() < 1e-8f) { return normal; }
            aside.nor();
            return normal.scl((float) Math.cos(MAX_BEND)).mulAdd(aside, (float) Math.sin(MAX_BEND));
        };
    }

    /** Whether the road crosses this border between plain two-way hexes at one level, so it may bend there. */
    private static boolean bent(Coords coords, int direction, Function<Coords, Node> nodes) {
        Node self = nodes.apply(coords), next = nodes.apply(coords.translated(direction));
        return self != null && next != null && self.simple() && next.simple() && self.elevation() == next.elevation()
              && (self.exits() & 1 << direction) != 0 && (next.exits() & 1 << (direction + 3) % 6) != 0;
    }

    /** A two-way road's exit other than the given one. */
    private static int other(Node node, int direction) {
        return Integer.numberOfTrailingZeros(node.exits() & ~(1 << direction));
    }

    /**
     * The direction at a crossing of the circular arc that runs into a square approach's corridor along its axis:
     * the axis mirrored in the chord between them. It points into the corridor's hex, whose centre and border
     * midpoint are given.
     */
    private static Vector2 arc(Vector2 center, Vector2 border, Vector2 crossing) {
        Vector2 axis = new Vector2(border).nor();
        Vector2 chord = new Vector2(center).mulAdd(border, CORRIDOR).sub(crossing).nor();
        return chord.scl(2 * chord.dot(axis)).sub(axis);
    }

    /** The midpoint of the border in the given direction, in this hex's tile pixels. */
    private static Vector2 border(Coords coords, int direction) {
        Coords next = coords.translated(direction);
        // The true short/long lattice dimensions, rather than an assumed regular 60-degree hex.
        return new Vector2((BoardGeometry.centerX(next) - BoardGeometry.centerX(coords)) / (2 * BoardGeometry.hexScale()),
              (BoardGeometry.centerY(next) - BoardGeometry.centerY(coords)) / (2 * BoardGeometry.hexScale()));
    }

    static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors) {
        return layout(coords, exits, kind, neighbors, direction -> null);
    }

    static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors, IntFunction<Vector2> bends) {
        List<Point> ends = new ArrayList<>();
        List<Vector2> directions = new ArrayList<>();
        List<Join> joins = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & (1 << direction)) == 0) { continue; }
            Vector2 middle = border(coords, direction);
            float x = middle.x, y = middle.y;
            Kind neighbor = neighbors.apply(direction);
            float width = neighbor == Kind.NONE ? kind.halfWidth : (kind.halfWidth + neighbor.halfWidth) / 2;
            ends.add(new Point(x, y, width));
            directions.add(bends.apply(direction));
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
            // A square approach keeps to the ramp corridor and turns in the flat hub; a bent one turns from its border.
            List<Point> path = new ArrayList<>(), tail = new ArrayList<>();
            Point from = approach(path, ends.get(0), directions.get(0), kind);
            Point to = approach(tail, ends.get(1), directions.get(1), kind);
            Vector2 in = inward(ends.get(0), directions.get(0)), out = inward(ends.get(1), directions.get(1));
            float dx = to.x - from.x, dy = to.y - from.y, cross = in.x * out.y - in.y * out.x;
            float chord = (float) Math.hypot(dx, dy), reachIn = .39f * chord, reachOut = reachIn;
            if (Math.abs(dx * in.y - dy * in.x) < .0001f * chord
                  && Math.abs(dx * out.y - dy * out.x) < .0001f * chord
                  && dx * in.x + dy * in.y > 0 && dx * out.x + dy * out.y < 0) {
                line(path, from, to);
            } else {
                if (Math.abs(cross) > 1e-4f) {
                    // Converging tangents meet where a quadratic would put its control point; the cubic keeps that shape.
                    float s = (dx * out.y - dy * out.x) / cross, u = (dx * in.y - dy * in.x) / cross;
                    if (s > 0 && u > 0) {
                        reachIn = 2 * s / 3;
                        reachOut = 2 * u / 3;
                    }
                }
                for (int i = 1; i <= 16; i++) {
                    float t = i / 16f, s = 1 - t;
                    float bx = from.x + in.x * reachIn, by = from.y + in.y * reachIn;
                    float cx = to.x + out.x * reachOut, cy = to.y + out.y * reachOut;
                    path.add(new Point(s * s * s * from.x + 3 * s * s * t * bx + 3 * s * t * t * cx + t * t * t * to.x,
                          s * s * s * from.y + 3 * s * s * t * by + 3 * s * t * t * cy + t * t * t * to.y,
                          from.width + t * (to.width - from.width)));
                }
            }
            path.addAll(tail.reversed().subList(1, tail.size()));
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
                List<Point> arm = new ArrayList<>();
                approach(arm, end, null, kind);
                line(path, new Point(0, 0, kind.halfWidth), arm.getLast());
                path.addAll(arm.reversed().subList(1, arm.size()));
                paths.add(path);
            }
        }
        return new BoardRoad(coords, paths, List.copyOf(ends), terminals, joins, roundabout(exits));
    }

    /**
     * Adds the approach through one border, from just outside it inwards, including the border crossing itself, and
     * returns where the approach may start to turn: halfway in for a square approach, which holds the ramp corridor,
     * or at the border for a bent one.
     */
    private static Point approach(List<Point> path, Point end, Vector2 bend, Kind kind) {
        if (bend == null) {
            line(path, scale(end, 1.06f, end.width), end);
            line(path, end, scale(end, .9f, end.width));
            line(path, scale(end, .9f, end.width), scale(end, CORRIDOR, kind.halfWidth));
        } else {
            float reach = .06f * (float) Math.hypot(end.x, end.y);
            line(path, new Point(end.x + bend.x * reach, end.y + bend.y * reach, end.width), end);
        }
        return path.getLast();
    }

    /** The direction an approach heads into the hex at its turning point; see {@link #approach}. */
    private static Vector2 inward(Point end, Vector2 bend) {
        return bend == null ? new Vector2(-end.x, -end.y).nor() : new Vector2(bend).scl(-1);
    }

    private static Point scale(Point p, float scale, float width) { return new Point(p.x * scale, p.y * scale, width); }

    /** Continue bridge paint and wear in world space through bank extensions, preserving the central junction. */
    BoardRoad extended(List<Float> lengths) {
        return extended(lengths, 0);
    }

    BoardRoad extended(List<Float> lengths, int terminalExits) {
        List<List<Point>> extended = new ArrayList<>();
        for (var path : paths) {
            var points = new ArrayList<>(path);
            for (int d = 0; d < 6; d++) {
                if (lengths.get(d) <= 0) { continue; }
                Coords next = coords.translated(d);
                float x = (BoardGeometry.centerX(next) - BoardGeometry.centerX(coords)) / (2 * BoardGeometry.hexScale());
                float y = (BoardGeometry.centerY(next) - BoardGeometry.centerY(coords)) / (2 * BoardGeometry.hexScale());
                float length = (float) Math.hypot(x, y), reach = lengths.get(d) + .25f;
                for (int end : new int[] { 0, points.size() - 1 }) {
                    var p = points.get(end);
                    if (Math.hypot(p.x - x * 1.06f, p.y - y * 1.06f) < .01f) {
                        points.set(end, new Point(x * (1 + reach / length), y * (1 + reach / length), p.width));
                    }
                }
            }
            extended.add(List.copyOf(points));
        }
        var terminals = new ArrayList<>(ends);
        for (int d = 0; d < 6; d++) {
            if ((terminalExits & (1 << d)) == 0) { continue; }
            Coords next = coords.translated(d);
            float x = (BoardGeometry.centerX(next) - BoardGeometry.centerX(coords)) / (2 * BoardGeometry.hexScale());
            float y = (BoardGeometry.centerY(next) - BoardGeometry.centerY(coords)) / (2 * BoardGeometry.hexScale());
            float length = (float) Math.hypot(x, y), reach = 1 + lengths.get(d) / length;
            terminals.add(new End(new Point(x * reach, y * reach, Kind.PAVED.halfWidth), x / length, y / length));
        }
        return new BoardRoad(coords, extended, borders, terminals, joins, roundabout);
    }

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
        // A line needs only its endpoints. Retain each explicit border point, which anchors the centre-line dashes.
        path.add(b);
    }

    /** Union before tessellation: junctions have one surface, without overlapping translucent road arms. */
    Area footprint(float margin) {
        // Road patches reuse several margins; callers subtract their own masks from a fresh copy.
        return new Area(outlines.computeIfAbsent(margin, this::outline));
    }

    private Area outline(float margin) {
        Area area = new Area();
        for (var path : paths) {
            if (path.stream().allMatch(point -> point.width == path.getFirst().width)) {
                // A round stroke is the same constant-width envelope as the segment rectangles and vertex discs.
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
        for (End end : ends) {
            var bounds = area.getBounds2D();
            // Bridge footings and their aprons can reach farther than a tile-sized clipping rectangle.
            float reach = (float) (Math.hypot(bounds.getWidth(), bounds.getHeight()) / 2
                  + Math.hypot(bounds.getCenterX() - end.point.x, bounds.getCenterY() - end.point.y) + 1);
            area.intersect(end.band(-reach, margin, reach));
        }
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

    Coords coords() { return coords; }
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
            Path2D.Float path = new Path2D.Float();
            path.moveTo(points.getFirst().x, points.getFirst().y);
            float along = 0;
            List<Float> anchors = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                if (i > 0) {
                    along += (float) Math.hypot(p.x - points.get(i - 1).x, p.y - points.get(i - 1).y);
                    path.lineTo(p.x, p.y);
                }
                if (borders.stream().anyMatch(border -> border.x == p.x && border.y == p.y)) { anchors.add(along); }
            }
            // Every border a road crosses sits in the middle of a gap, seen from both of its hexes, and a road
            // between two borders fits whole dashes, so the centre line continues through turns and bridges alike.
            // A junction's arm fits the period of the straight road through it, so a through route keeps its dashes.
            float span = anchors.size() > 1 ? anchors.getLast() - anchors.getFirst()
                  : anchors.isEmpty() ? 0 : 2 * anchors.getFirst();
            float period = span > 0 ? span / Math.max(1, Math.round(span / (DASH + GAP))) : DASH + GAP;
            float dash = period * DASH / (DASH + GAP), anchor = anchors.isEmpty() ? 0 : anchors.getFirst();
            float phase = ((dash + (period - dash) / 2 - anchor) % period + period) % period;
            Area paint = new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                  10, new float[] { dash, period - dash }, phase).createStrokedShape(path));
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
