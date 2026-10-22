/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;

/**
 * Natural ground beside reserved carriageways (Enhanced). Near a road every relief vertex moves only vertically, by one
 * field of its position that every hex evaluates alike, so tops, slopes and cliffs stay joined across hexes. Ground
 * within the height range a road spans, or down to the foot of a shallow drop beside it, eases into it as a bank: no
 * higher above, or lower below, the nearest reserved
 * edge than {@link #SLOPE} times its distance, and over the reserved ground it lies on the road. Ground above or below
 * that range keeps its shape, fading in over {@link #RELEASE}: a cliff gives way to a road by receding from it
 * (BoardRelief#roadPush), not by being cut down. Poured and wet hexes keep their ground; a bridge's ramp keeps its
 * footing instead of a bank, and a road built in concrete its concrete walls (BoardRoadGrade#poured).
 */
final class BoardRoadBank {
    /** The most, in levels, a containment wall retains. */
    static final float WALL = .5f;
    /** The tallest step, in levels, whose toe a containment wall retains; a taller cliff recedes as rock. */
    static final float WALLED_SLOPE = 2;
    /**
     * Width, in tile pixels, of the level verge beside a road before a bank leaves it. Within it ground up to the road's
     * headroom gives way, fading back to the road's own height range across a second verge width; steps recede clear of
     * it (BoardRelief#roadPush).
     */
    static final float VERGE = 3;
    /** Rise per run of the steepest part of a bank. */
    private static final float SLOPE = .75f;
    /** Height, in levels, over which a bank rounds into the ground it leaves. */
    private static final float ROUND = .35f;
    /** Height, in levels, past a road's range over which ground fades back to its own shape. */
    private static final float RELEASE = .5f;
    /** Spacing of the world-aligned planes ground near a road is split on, in tile pixels. */
    private static final float GRID = 3;
    /** How far from a poured or wet hex natural ground fades into its bank, in tile pixels. */
    private static final float FADE = 12;
    /** How far, in hexes, a grade's reserved ground and bank can reach. */
    private static final int REACH = 3;

    /** A grade shaping the ground, how far its bank reaches from its outline, and the box that reach spans. */
    private record Bank(BoardRoadGrade grade, int index, float fill, float reach, float minX, float minY, float maxX,
          float maxY) { }

    /** A point cut on an edge between two given vertices; the same edge in two faces gives the same point. */
    private record Cut(Vector3 a, Vector3 b, int axis, float at) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Cut cut && cut.a == a && cut.b == b && cut.axis == axis && cut.at == at;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * System.identityHashCode(a) + System.identityHashCode(b)) + Float.hashCode(at) + axis;
        }
    }

    /** Every grade within reach, in one order on every hex. */
    private final List<BoardRoadGrade> grades = new ArrayList<>();
    private final List<Bank> banks = new ArrayList<>();
    /** Centres of the poured and wet hexes within reach, whose ground keeps its shape. */
    private final List<float[]> kept = new ArrayList<>();
    private final float level = BoardGeometry.level(), scale = BoardGeometry.hexScale();
    /** The hex's relief, which decides where containment walls retain the slopes beside a road. */
    private final BoardRelief relief;

    BoardRoadBank(BoardScene scene, BoardScene.Tile tile, BoardRelief relief) {
        this.relief = relief;
        Coords at = tile.coords();
        for (int x = at.getX() - REACH; x <= at.getX() + REACH; x++) {
            for (int y = at.getY() - REACH - 1; y <= at.getY() + REACH + 1; y++) {
                var other = scene.tile(x, y);
                if (other == null || at.distance(other.coords()) > REACH) { continue; }
                var grade = BoardRoadGrade.of(scene, other, true);
                if (grade != null) { grades.add(grade); }
                if (other.surface() == BoardScene.Surface.CONCRETE || other.liquid().present()) {
                    kept.add(new float[] { BoardGeometry.centerX(other.coords()), BoardGeometry.centerY(other.coords()) });
                }
            }
        }
        grades.sort(Comparator.comparingInt((BoardRoadGrade grade) -> grade.tile().coords().getX())
              .thenComparingInt(grade -> grade.tile().coords().getY()));
        for (var grade : grades) {
            if (grade.bridgeRamp() || grade.poured()) { continue; }
            // A road above shallow drops only fills them up as an embankment (BoardRoadGrade#FILL); beside a deeper
            // drop its fill stays at its own level, so the cliff below keeps its face.
            float floor = grade.low();
            for (int d = 0; d < 6; d++) {
                var next = scene.neighbor(grade.tile(), d);
                if (next != null && !next.liquid().present()) { floor = Math.min(floor, next.elevation() * level); }
            }
            float fill = grade.low() - floor <= BoardRoadGrade.FILL * level ? BoardRoadGrade.FILL * level : 0;
            float reach = (grade.high() - grade.low() + fill + (RELEASE + ROUND) * level) / SLOPE + VERGE * scale;
            float[] box = grade.bounds();
            if (grade.outline().length > 0) {
                banks.add(new Bank(grade, grades.indexOf(grade), fill, reach, box[0] - reach, box[1] - reach, box[2] + reach,
                      box[3] + reach));
            }
        }
    }

    /** Whether any carriageway's ground or bank reaches this hex's surroundings. */
    boolean active() { return !grades.isEmpty(); }

    /**
     * The surface of the carriageway at a point: the highest reserving it, else the nearest's own grade continued; NaN
     * without any.
     */
    float bed(float x, float y) {
        float inside = Float.NaN, outside = Float.NaN, nearest = Float.POSITIVE_INFINITY;
        for (var grade : grades) {
            if (grade.contains(x, y)) {
                inside = Float.isNaN(inside) ? grade.height(x, y) : Math.max(inside, grade.height(x, y));
                continue;
            }
            float d = distance(grade.outline(), x, y, null);
            if (d < nearest) {
                nearest = d;
                outside = grade.height(x, y);
            }
        }
        return Float.isNaN(inside) ? outside : inside;
    }

    /** The ground height at a point that stood at height z. */
    float height(float x, float y, float z) {
        boolean[] inside = new boolean[grades.size()];
        boolean reserved = false;
        for (int i = 0; i < inside.length; i++) { reserved |= inside[i] = grades.get(i).contains(x, y); }
        float weight = weight(x, y);
        if (weight == 0) { return z; }
        int count = banks.size();
        float[] beds = new float[count], limits = new float[count];
        // Reserved ground follows only the roads reserving it: a bank beside another road never lifts it over this one.
        float cut = z;
        for (int i = 0; i < count; i++) {
            Bank bank = banks.get(i);
            limits[i] = Float.NaN;
            if (x < bank.minX() || x > bank.maxX() || y < bank.minY() || y > bank.maxY()
                  || reserved && !inside[bank.index()]) { continue; }
            BoardRoadGrade grade = bank.grade();
            float[] q = new float[2];
            float d = inside[bank.index()] ? 0 : distance(grade.outline(), x, y, q);
            beds[i] = d == 0 ? grade.height(x, y) : grade.height(q[0], q[1]);
            // A level verge first, so ground cut at the reserved edge lies on the road, not a sliver above it.
            limits[i] = SLOPE * Math.max(0, d - VERGE * scale);
            // Above the road the bank cuts the ground down, over the range the road spans; beside it up to its headroom.
            float verge = 1 - smooth((d - VERGE * scale) / (VERGE * scale));
            float release = smooth((z - grade.high() - verge * BoardRoadGrade.CLEARANCE * level) / (RELEASE * level));
            if (release < 1) {
                // Behind a containment wall the slope keeps up to the wall's height over the road.
                float retained = d == 0 ? 0 : Math.min(z - beds[i], WALL * level) * relief.walled(q[0], q[1]);
                cut = Math.min(cut, lerp(beds[i] + settle(z - beds[i], limits[i] + retained), z, release));
            }
        }
        float result = cut;
        for (int i = 0; i < count; i++) {
            if (Float.isNaN(limits[i])) { continue; }
            // Below it the bank fills the ground up, down to a shallow drop's foot below the road beside it: a deeper
            // cliff keeps its face below a rounded rim, even beside the high end of a ramp.
            float release = smooth((beds[i] - cut - banks.get(i).fill()) / (RELEASE * level));
            if (release < 1) { result = Math.max(result, lerp(beds[i] - settle(beds[i] - cut, limits[i]), cut, release)); }
        }
        return lerp(z, result, weight);
    }

    /** A height difference to a road kept within a bank's limit, rounded where the bank leaves the ground. */
    private float settle(float height, float limit) {
        if (height <= 0) { return height; }
        float round = Math.min(ROUND * level, 2 * limit);
        return round <= 0 ? Math.min(height, limit)
              : Math.min(height, Math.max(0, BoardRelief.smoothMin(height, limit, round)));
    }

    /**
     * One away from poured or wet hexes, fading to none on their outlines: on reserved ground too, whose top gives way to
     * the road anyway (BoardRoadCut#clip), so a step down to the water under a bridge's landing keeps its face.
     */
    private float weight(float x, float y) {
        float nearest = Float.POSITIVE_INFINITY, fade = FADE * scale;
        for (float[] center : kept) {
            if (Math.hypot(x - center[0], y - center[1]) < BoardGeometry.width() / 2 + fade) {
                nearest = Math.min(nearest, cellDistance(center, x, y));
            }
        }
        return nearest >= fade ? 1 : smooth(nearest / fade);
    }

    /** Distance from a point to a hex's outline, zero inside. */
    private float cellDistance(float[] center, float x, float y) {
        float nearest = Float.POSITIVE_INFINITY;
        boolean inside = true;
        for (int k = 0; k < 6; k++) {
            float ax = center[0] + HexTile.cornerOffsetX(k, HexTile.WIDTH) * scale;
            float ay = center[1] + HexTile.cornerOffsetY(k, HexTile.HEIGHT) * scale;
            float bx = center[0] + HexTile.cornerOffsetX((k + 1) % 6, HexTile.WIDTH) * scale;
            float by = center[1] + HexTile.cornerOffsetY((k + 1) % 6, HexTile.HEIGHT) * scale;
            inside &= (bx - ax) * (y - ay) - (by - ay) * (x - ax) >= 0;
            nearest = Math.min(nearest, BoardRelief.distance(x, y, ax, ay, bx, by));
        }
        return inside ? 0 : nearest;
    }

    /** Distance from a point to the nearest of an outline's segments, and that nearest point into q if given. */
    private static float distance(float[] outline, float x, float y, float[] q) {
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < outline.length; i += 4) {
            float ax = outline[i], ay = outline[i + 1], dx = outline[i + 2] - ax, dy = outline[i + 3] - ay;
            float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
            float px = ax + dx * t, py = ay + dy * t, d = (x - px) * (x - px) + (y - py) * (y - py);
            if (d < nearest) {
                nearest = d;
                if (q != null) { q[0] = px; q[1] = py; }
            }
        }
        return (float) Math.sqrt(nearest);
    }

    /**
     * The faces with the ground near roads eased into them. Faces the field can move are first split on the world's
     * grid, so neighbouring hexes sample it at the same points; every vertex then moves once, and moved ground takes the
     * field's own normal. A face none of whose pieces moves stays whole. Nothing on the board's {@code floor} moves.
     */
    List<BoardSurface.Face> conform(List<BoardSurface.Face> faces, float floor) {
        if (banks.isEmpty()) { return faces; }
        float grid = GRID * scale;
        Map<Cut, Vector3> cuts = new HashMap<>();
        Map<Vector3, Vector3> moved = new IdentityHashMap<>();
        List<BoardSurface.Face> result = new ArrayList<>(faces.size());
        List<BoardSurface.Face> pieces = new ArrayList<>();
        for (var face : faces) {
            if (!reached(face)) {
                result.add(face);
                continue;
            }
            pieces.clear();
            split(face, grid, cuts, relief, pieces);
            int first = result.size();
            boolean changed = false;
            for (var piece : pieces) {
                Vector3[] corners = { piece.a(), piece.b(), piece.c() };
                for (int i = 0; i < 3; i++) {
                    Vector3 p = corners[i];
                    corners[i] = moved.computeIfAbsent(p, key -> {
                        // The board's cut stands on the board's floor, which no bank lifts.
                        if (key.z <= floor + 1e-4f * scale) { return key; }
                        float z = height(key.x, key.y, key.z);
                        return Math.abs(z - key.z) < 1e-4f * scale ? key : new Vector3(key.x, key.y, z);
                    });
                    changed |= corners[i] != p;
                }
                Vector3 a = corners[0], b = corners[1], c = corners[2];
                float longest = Math.max(a.dst2(b), Math.max(b.dst2(c), c.dst2(a)));
                // A face collapsing onto the road has no plane left; picking needs one.
                if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-10f * longest * longest) {
                    result.add(new BoardSurface.Face(a, b, c, face.finish(), face.landEdge(), face.scatter()));
                }
            }
            // Ground the bank leaves as it was keeps its own face: its cuts lie on its straight, unmoved edges.
            if (!changed) {
                result.subList(first, result.size()).clear();
                result.add(face);
            }
        }
        Map<Vector3, Vector3> normals = BoardSurface.vertexNormals(result);
        for (var entry : moved.entrySet()) {
            Vector3 from = entry.getKey(), to = entry.getValue();
            if (from == to) { continue; }
            var shade = relief.shade(from);
            boolean ground = shade != null && shade.normal().z > .95f;
            relief.reshade(from, to, ground ? normal(from) : normals.get(to));
        }
        return result;
    }

    /** Moves every vertex of the faces in place by the field, without splitting any face or changing the list. */
    void move(List<BoardSurface.Face> faces) {
        Set<Vector3> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var face : faces) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (seen.add(p)) { p.z = height(p.x, p.y, p.z); }
            }
        }
    }

    /**
     * Whether the field can move any point of the face: some of it lies within a bank's reach of its outline. Only where
     * it lies, so the faces either side of an edge, top or wall, split it alike; beyond the reach the field is identity.
     */
    private boolean reached(BoardSurface.Face face) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        float minX = Math.min(a.x, Math.min(b.x, c.x)), maxX = Math.max(a.x, Math.max(b.x, c.x));
        float minY = Math.min(a.y, Math.min(b.y, c.y)), maxY = Math.max(a.y, Math.max(b.y, c.y));
        float mx = (a.x + b.x + c.x) / 3, my = (a.y + b.y + c.y) / 3;
        float radius = (float) Math.sqrt(Math.max(Math.max(dst2(a, mx, my), dst2(b, mx, my)), dst2(c, mx, my)));
        for (var bank : banks) {
            if (maxX >= bank.minX() && minX <= bank.maxX() && maxY >= bank.minY() && minY <= bank.maxY()
                  && (bank.grade().contains(mx, my) || distance(bank.grade().outline(), mx, my, null) <= bank.reach() + radius)) {
                return true;
            }
        }
        return false;
    }

    private static float dst2(Vector3 p, float x, float y) { return (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y); }

    /** The field's normal at a ground point: the gradient of the height it moves level ground to. */
    private Vector3 normal(Vector3 p) {
        float e = .5f * scale;
        float dx = height(p.x + e, p.y, p.z) - height(p.x - e, p.y, p.z);
        float dy = height(p.x, p.y + e, p.z) - height(p.x, p.y - e, p.z);
        return new Vector3(-dx / (2 * e), -dy / (2 * e), 1).nor();
    }

    /** A face cut by every grid plane it crosses, keeping its winding and its shading at the new vertices. */
    private static void split(BoardSurface.Face face, float grid, Map<Cut, Vector3> cuts, BoardRelief relief,
          List<BoardSurface.Face> out) {
        List<Vector3[]> pieces = new ArrayList<>();
        pieces.add(new Vector3[] { face.a(), face.b(), face.c() });
        for (int axis = 0; axis < 2; axis++) {
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                float value = axis == 0 ? p.x : p.y;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            // Each plane from its own index, so every face cuts the same plane at the same coordinate.
            for (long k = (long) Math.ceil(min / grid); k * grid < max; k++) {
                float at = k * grid;
                List<Vector3[]> next = new ArrayList<>(pieces.size() + 4);
                for (Vector3[] piece : pieces) { cut(piece, axis, at, grid, cuts, relief, next); }
                pieces = next;
            }
        }
        for (Vector3[] piece : pieces) {
            Vector3 a = piece[0], b = piece[1], c = piece[2];
            float longest = Math.max(a.dst2(b), Math.max(b.dst2(c), c.dst2(a)));
            if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-12f * longest * longest) {
                out.add(new BoardSurface.Face(a, b, c, face.finish(), face.landEdge(), face.scatter()));
            }
        }
    }

    /**
     * A triangle cut by the plane where the axis coordinate is at: itself, or the pieces on both sides. A vertex within a
     * two-hundredth of the grid of the plane counts as on it, so no sliver is cut beside it; every face through it agrees.
     */
    private static void cut(Vector3[] piece, int axis, float at, float grid, Map<Cut, Vector3> cuts, BoardRelief relief,
          List<Vector3[]> out) {
        float tolerance = Math.max(1e-5f * Math.max(1, Math.abs(at)), .005f * grid);
        float[] side = new float[3];
        for (int i = 0; i < 3; i++) {
            float value = (axis == 0 ? piece[i].x : piece[i].y) - at;
            side[i] = Math.abs(value) <= tolerance ? 0 : value;
        }
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3, k = (i + 2) % 3;
            if (side[i] == 0 && side[j] * side[k] < 0) {
                // Through a vertex and across the opposite edge.
                Vector3 p = point(piece[j], piece[k], axis, at, cuts, relief);
                out.add(new Vector3[] { piece[i], piece[j], p });
                out.add(new Vector3[] { piece[i], p, piece[k] });
                return;
            }
            if (side[i] != 0 && side[j] * side[i] < 0 && side[k] * side[i] < 0) {
                // A lone vertex on one side.
                Vector3 p = point(piece[i], piece[j], axis, at, cuts, relief);
                Vector3 q = point(piece[k], piece[i], axis, at, cuts, relief);
                out.add(new Vector3[] { piece[i], p, q });
                out.add(new Vector3[] { p, piece[j], piece[k] });
                out.add(new Vector3[] { p, piece[k], q });
                return;
            }
        }
        out.add(piece);
    }

    /** Where an edge crosses a grid plane, computed from its ends in one order whichever face asks. */
    private static Vector3 point(Vector3 a, Vector3 b, int axis, float at, Map<Cut, Vector3> cuts, BoardRelief relief) {
        if (a.x > b.x || a.x == b.x && (a.y > b.y || a.y == b.y && a.z > b.z)) {
            Vector3 swap = a;
            a = b;
            b = swap;
        }
        Vector3 first = a, second = b;
        return cuts.computeIfAbsent(new Cut(first, second, axis, at), key -> {
            float from = axis == 0 ? first.x : first.y, to = axis == 0 ? second.x : second.y;
            float t = (at - from) / (to - from);
            Vector3 p = new Vector3(first).lerp(second, t);
            if (axis == 0) { p.x = at; } else { p.y = at; }
            relief.interpolateShade(first, second, t, p);
            return p;
        });
    }

    private static float smooth(float t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
