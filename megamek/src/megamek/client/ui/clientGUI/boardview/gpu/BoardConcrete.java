/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.LongMap;
import megamek.common.board.Coords;

/** Fitted concrete rectangles and their joins, shared with adjoining terrain. No game state or GL objects. */
final class BoardConcrete {
    enum Mode { OFF, WATER_ONLY, EVERYWHERE }

    static final Mode DEFAULT_MODE = Mode.EVERYWHERE;
    private static Mode mode = DEFAULT_MODE;

    static Mode mode() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? mode : settings.concrete();
    }

    static void tune(Mode next) {
        if (next != mode) {
            mode = java.util.Objects.requireNonNull(next);
            BoardGeometry.terrainChanged();
        }
    }
    record Shift(float x, float y) { }

    private static final Shift ZERO = new Shift(0, 0);
    /** One immutable derived outline, shared by terrain and picking; no scene, artwork or GL resources are retained. */
    private record Cached(WeakReference<List<BoardScene.Tile>> tiles, int width, int height, float scale,
          Mode mode, BoardConcrete shape) {
        boolean matches(BoardScene scene) {
            return tiles.get() == scene.tiles() && width == scene.width() && height == scene.height()
                  && scale == BoardGeometry.hexScale() && mode == BoardConcrete.mode();
        }
    }
    // Retain the displayed and pending outlines. A cache miss must not hold a monitor during board construction.
    private static volatile Cached cached, previous;
    // Packed lattice coordinates collide heavily in Long.hashCode(); use the primitive map's mixed lookup.
    // Both maps are filled only during construction and then published with this immutable derived outline.
    private final LongMap<Shift> shifts = new LongMap<>(0);
    private final LongMap<float[]> footprints = new LongMap<>(0);

    static BoardConcrete of(BoardScene scene) {
        Cached first = cached, second = previous;
        if (first != null && first.matches(scene)) { return first.shape(); }
        if (second != null && second.matches(scene)) { return second.shape(); }
        BoardConcrete shape = new BoardConcrete(scene);
        synchronized (BoardConcrete.class) {
            if (cached != null && cached.matches(scene)) { return cached.shape(); }
            previous = cached;
            cached = new Cached(new WeakReference<>(scene.tiles()), scene.width(), scene.height(), BoardGeometry.hexScale(), mode(), shape);
        }
        return shape;
    }

    Shift shift(long corner) { return shifts.get(corner, ZERO); }

    /** The same fitted corner for terrain, water, artwork and picking in both GPU views. */
    Vector3 corner(Coords coords, int corner) {
        Vector3 point = BoardGeometry.corner(coords, 0, corner);
        Shift delta = shift(key(point));
        return point.add(delta.x(), delta.y(), 0);
    }

    /** Signed distance to the fitted footprint; NaN means this hex still uses its original outline. */
    float distance(Coords coords, float x, float y) {
        float[] outline = footprints.get(tileKey(coords));
        if (outline == null) { return Float.NaN; }
        // The nearest edge is chosen on exact squared offsets; only that offset is measured, as every edge was.
        double nearest = Double.POSITIVE_INFINITY;
        float nearestX = 0, nearestY = 0, ax = 0, ay = 0;
        boolean inside = false;
        for (int k = 0; k <= 6; k++) {
            int index = (k % 6) * 2;
            float bx = outline[index], by = outline[index + 1];
            if (k > 0) {
                float dx = bx - ax, dy = by - ay;
                float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / Math.max(dx * dx + dy * dy, .00001f), 0, 1);
                float px = x - ax - t * dx, py = y - ay - t * dy;
                double squared = (double) px * px + (double) py * py;
                if (squared < nearest) {
                    nearest = squared;
                    nearestX = px;
                    nearestY = py;
                }
                if ((ay > y) != (by > y) && x < ax + (y - ay) * dx / dy) { inside = !inside; }
            }
            ax = bx;
            ay = by;
        }
        float distance = (float) Math.hypot(nearestX, nearestY);
        return inside ? -distance : distance;
    }

    List<Shift> corners(Coords coords) {
        List<Shift> result = new ArrayList<>(6);
        for (int k = 0; k < 6; k++) { result.add(shift(key(BoardGeometry.corner(coords, 0, k)))); }
        return List.copyOf(result);
    }

    boolean sameCorners(BoardConcrete other, Coords coords) {
        if (other == null) { return false; }
        for (int k = 0; k < 6; k++) {
            long key = key(BoardGeometry.corner(coords, 0, k));
            if (!shift(key).equals(other.shift(key))) { return false; }
        }
        return true;
    }

    private static long key(Vector3 p) {
        return ((long) Math.round(p.x / (BoardGeometry.width() / 4)) << 32)
              ^ (Math.round(p.y / (BoardGeometry.height() / 2)) & 0xffffffffL);
    }

    private static long tileKey(Coords coords) {
        return ((long) coords.getX() << 32) ^ (coords.getY() & 0xffffffffL);
    }

    /** Construction-only vertices. Each is the same lattice corner seen by its three adjoining hexes. */
    private static final class Corner {
        final Vector3 original;
        final Vector3 point;
        final Set<BoardScene.Tile> tiles = new HashSet<>();
        Corner previous, next;
        Line rail;
        boolean pinned, dock, border;

        Corner(Vector3 point) {
            original = point;
            this.point = new Vector3(point);
        }
    }

    private BoardConcrete(BoardScene scene) {
        if (mode() == Mode.OFF) { return; }
        Map<Long, Corner> corners = new TreeMap<>();
        for (BoardScene.Tile land : scene.tiles()) {
            if (land.liquid().present() || land.surface() != BoardScene.Surface.CONCRETE) { continue; }
            for (int e = 0; e < 6; e++) {
                BoardScene.Tile water = scene.tile(land.coords().translated(BoardGeometry.edgeDirection(e)));
                if (!outside(water) || water.liquid().present() && water.elevation() > land.elevation()) { continue; }
                Corner a = coastCorner(corners, scene, land.coords(), e);
                Corner b = coastCorner(corners, scene, land.coords(), (e + 1) % 6);
                a.next = b;
                b.previous = a;
                a.rail = dockRail(scene, land, a.point, b.point);
                a.tiles.add(land);
                a.tiles.add(water);
                b.tiles.add(land);
                b.tiles.add(water);
            }
        }
        for (Corner corner : corners.values()) {
            Integer dryLevel = null, waterLevel = null;
            corner.border &= corner.tiles.size() == 2 && (corner.previous == null) != (corner.next == null);
            corner.pinned = !corner.border && (corner.tiles.size() != 3 || corner.previous == null || corner.next == null);
            for (BoardScene.Tile tile : corner.tiles) {
                corner.pinned |= fixedBoundary(scene, corner, tile);
                if (outside(tile)) {
                    if (tile.liquid().present()) {
                        corner.pinned |= tile.frozen() || waterLevel != null && waterLevel != tile.elevation();
                        waterLevel = tile.elevation();
                    }
                } else {
                    corner.pinned |= dryLevel != null && dryLevel != tile.elevation();
                    corner.pinned |= dockLinks(scene, tile) == 0;
                    dryLevel = tile.elevation();
                }
            }
            if (!corner.pinned) {
                Line before = corner.previous == null ? null : corner.previous.rail, after = corner.rail;
                corner.dock = before != null || after != null;
                if (before != null && after != null && !before.same(after)) {
                    Vector3 joint = before.intersection(after);
                    if (joint != null) { corner.point.set(joint); corner.pinned = true; }
                } else if (corner.dock) {
                    corner.point.set((before != null ? before : after).project(corner.point));
                }
            }
        }
        simplify(corners.values(), scene, null);
        Set<Corner> continued = continueSides(corners.values(), scene);
        if (!continued.isEmpty()) { simplify(corners.values(), scene, continued); }
        Set<Coords> fitted = new HashSet<>();
        corners.forEach((key, corner) -> {
            float dx = corner.point.x - corner.original.x, dy = corner.point.y - corner.original.y;
            if (Math.hypot(dx, dy) > .001f) {
                shifts.put(key, new Shift(dx, dy));
                corner.tiles.forEach(tile -> fitted.add(tile.coords()));
            }
        });
        // Surface blending asks for this polygon repeatedly per vertex. Derive it once from the same corner shifts
        // used by geometry/picking, and retain only moved hexes; ordinary ground returns NaN in one lookup.
        for (Coords coords : fitted) {
            float[] outline = new float[12];
            for (int k = 0; k < 6; k++) {
                Vector3 point = corner(coords, k);
                outline[k * 2] = point.x;
                outline[k * 2 + 1] = point.y;
            }
            footprints.put(tileKey(coords), outline);
        }
    }

    private static void simplify(Collection<Corner> corners, BoardScene scene, Set<Corner> changed) {
        Set<Corner> visited = new HashSet<>();
        // Open chains first; otherwise starting midway would split a continuous quay at an arbitrary hex.
        for (Corner corner : corners) {
            if (corner.previous == null) { simplifyChain(corner, visited, scene, changed); }
        }
        for (Corner corner : corners) { simplifyChain(corner, visited, scene, changed); }
    }

    private static Corner coastCorner(Map<Long, Corner> corners, BoardScene scene, Coords coords, int k) {
        Vector3 point = BoardGeometry.corner(coords, 0, k);
        Corner corner = corners.computeIfAbsent(key(point), ignored -> new Corner(point));
        corner.border |= scene.tile(coords.translated(BoardGeometry.edgeDirection(k))) == null
              || scene.tile(coords.translated(BoardGeometry.edgeDirection((k + 5) % 6))) == null;
        return corner;
    }

    private static int nearest(List<Corner> chain, Vector3 point, int first, int last) {
        int result = first;
        for (int i = first + 1; i <= last; i++) {
            if (point.dst2(chain.get(i).original) < point.dst2(chain.get(result).original)) { result = i; }
        }
        return result;
    }

    /** A broad patch can be one rectangle too. Try the three lattice axes and keep the least displaced safe fit. */
    private static boolean fitRectangle(List<Corner> chain, BoardScene scene) {
        if (chain.stream().anyMatch(corner -> corner.pinned && !corner.dock)) { return false; }
        float area = 0;
        Set<BoardScene.Tile> land = new HashSet<>();
        for (Corner corner : chain) {
            area += corner.point.x * corner.next.point.y - corner.point.y * corner.next.point.x;
            for (BoardScene.Tile tile : corner.tiles) { if (!outside(tile)) { land.add(tile); } }
        }
        // An inner hole has the reverse winding and must stay open.
        if (area <= 0 || land.size() < 6) { return false; }
        Vector3[] best = null;
        float bestError = Float.POSITIVE_INFINITY;
        for (float angle : new float[] { 0, (float) Math.atan2(BoardGeometry.height() / 2, .75f * BoardGeometry.width()),
              -(float) Math.atan2(BoardGeometry.height() / 2, .75f * BoardGeometry.width()) }) {
            float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle), vx = -uy, vy = ux;
            float u0 = Float.POSITIVE_INFINITY, u1 = Float.NEGATIVE_INFINITY;
            float v0 = Float.POSITIVE_INFINITY, v1 = Float.NEGATIVE_INFINITY;
            for (BoardScene.Tile tile : land) {
                float x = BoardGeometry.centerX(tile.coords()), y = BoardGeometry.centerY(tile.coords());
                u0 = Math.min(u0, ux * x + uy * y);
                u1 = Math.max(u1, ux * x + uy * y);
                v0 = Math.min(v0, vx * x + vy * y);
                v1 = Math.max(v1, vx * x + vy * y);
            }
            if (Math.min(u1 - u0, v1 - v0) < 54 * BoardGeometry.hexScale()) { continue; }
            float margin = 16 * BoardGeometry.hexScale();
            Line[] sides = { new Line(ux, uy, u0 - margin), new Line(vx, vy, v0 - margin),
                  new Line(-ux, -uy, -u1 - margin), new Line(-vx, -vy, -v1 - margin) };
            Line[] edges = new Line[chain.size()];
            int[] starts = new int[4];
            for (int k = 0; k < 4; k++) {
                Vector3 corner = sides[(k + 3) % 4].intersection(sides[k]);
                starts[k] = nearest(chain, corner, 0, chain.size() - 1);
            }
            boolean ordered = true;
            for (int k = 0; k < 4 && ordered; k++) {
                int end = starts[(k + 1) % 4];
                if (starts[k] == end) { ordered = false; break; }
                for (int i = starts[k]; i != end; i = (i + 1) % chain.size()) {
                    if (edges[i] != null) { ordered = false; break; }
                    edges[i] = sides[k];
                }
            }
            if (!ordered) { continue; }
            Vector3[] points = new Vector3[chain.size()];
            int turns = 0;
            float error = 0;
            boolean fits = true;
            for (int i = 0; i < chain.size(); i++) {
                Line previous = edges[(i + chain.size() - 1) % chain.size()], next = edges[i];
                if (previous != next) { turns++; }
                Vector3 p = previous == next ? next.project(chain.get(i).original) : previous.intersection(next);
                if (p == null || p.dst(chain.get(i).original) > 42 * BoardGeometry.hexScale()) { fits = false; break; }
                points[i] = p;
                error += p.dst2(chain.get(i).original);
            }
            if (!fits || turns != 4) { continue; }
            for (int i = 0; i < chain.size() && fits; i++) {
                fits = clearEdge(scene, chain.get(i), points[i], points[(i + 1) % chain.size()]);
            }
            if (fits && error < bestError) { best = points; bestError = error; }
        }
        if (best == null) { return false; }
        for (int i = 0; i < chain.size(); i++) { chain.get(i).point.set(best[i]); }
        return true;
    }

    private static void simplifyChain(Corner start, Set<Corner> visited, BoardScene scene, Set<Corner> changed) {
        if (visited.contains(start)) { return; }
        List<Corner> chain = new ArrayList<>();
        for (Corner at = start; at != null && visited.add(at); at = at.next) {
            chain.add(at);
        }
        if (chain.size() < 3 || changed != null && Collections.disjoint(chain, changed)) { return; }
        if (chain.getLast().next == start && fitRectangle(chain, scene)) { return; }
        if (chain.getLast().next == start) {
            int anchor = 0;
            Vector3 center = new Vector3();
            for (Corner corner : chain) { center.add(corner.point); }
            center.scl(1f / chain.size());
            for (int i = 0; i < chain.size(); i++) {
                if (chain.get(i).pinned) { anchor = i; break; }
                if (chain.get(i).point.dst2(center) > chain.get(anchor).point.dst2(center)) { anchor = i; }
            }
            List<Corner> ring = new ArrayList<>(chain.size() + 1);
            for (int i = 0; i <= chain.size(); i++) { ring.add(chain.get((anchor + i) % chain.size())); }
            chain = ring;
        }
        int first = 0;
        for (int i = 1; i < chain.size(); i++) {
            if (chain.get(i).pinned || i == chain.size() - 1) {
                fitLanding(chain, first, i, scene);
                first = i;
            }
        }
    }

    /** An inward unit normal and offset. Both sides of a dock come from one axis and one width. */
    private record Line(float nx, float ny, float offset) {
        float side(Vector3 p) { return nx * p.x + ny * p.y - offset; }

        Vector3 project(Vector3 p) { return new Vector3(p.x - nx * side(p), p.y - ny * side(p), 0); }

        boolean same(Line other) {
            return Math.abs(nx - other.nx) + Math.abs(ny - other.ny) < .0001f
                  && Math.abs(offset - other.offset) < .001f * BoardGeometry.hexScale();
        }

        Vector3 intersection(Line other) {
            float determinant = nx * other.ny - ny * other.nx;
            return Math.abs(determinant) < .001f ? null : new Vector3(
                  (offset * other.ny - ny * other.offset) / determinant,
                  (nx * other.offset - offset * other.nx) / determinant, 0);
        }
    }

    private record Run(int first, int last, Line line, boolean rectangle) { }

    /** An established straight side can continue beyond its end across a paved junction. */
    private record Side(Line line, Vector3 a, Vector3 b) { }

    /** Prefer a longer established side across a short paved branch, then refit only the affected chains. */
    private static Set<Corner> continueSides(Collection<Corner> corners, BoardScene scene) {
        Map<Coords, List<Side>> nearby = new HashMap<>();
        Map<Corner, Float> lengths = new HashMap<>();
        Set<Corner> visited = new HashSet<>();
        float scale = BoardGeometry.hexScale();
        for (Corner corner : corners) {
            if (corner.next == null || visited.contains(corner)) { continue; }
            if (corner.point.dst2(corner.next.point) < .000001f * scale * scale) { visited.add(corner); continue; }
            Vector3 along = new Vector3(corner.next.point).sub(corner.point).nor();
            Line line = new Line(-along.y, along.x, -along.y * corner.point.x + along.x * corner.point.y);
            Corner first = corner, last = corner.next;
            while (first.previous != null && first.previous != corner && !visited.contains(first.previous)
                  && Math.abs(line.side(first.previous.point)) < .003f * scale) { first = first.previous; }
            while (last.next != null && last != first && !visited.contains(last)
                  && Math.abs(line.side(last.next.point)) < .003f * scale) { last = last.next; }
            float length = first.point.dst(last.point);
            for (Corner at = first; at != last; at = at.next) {
                visited.add(at);
                lengths.put(at, length);
                // Reuse an exact fixed-width rail rather than recovering a slightly different line from float vertices.
                if (at.rail != null && line.same(at.rail)) { line = at.rail; }
            }
            if (length < 1.5f * BoardGeometry.width()) { continue; }
            Side side = new Side(line, new Vector3(first.point), new Vector3(last.point));
            for (Corner end : List.of(first, last)) {
                for (BoardScene.Tile tile : end.tiles) {
                    nearby.computeIfAbsent(tile.coords(), ignored -> new ArrayList<>()).add(side);
                }
            }
        }
        Set<Corner> changed = new HashSet<>();
        for (Corner corner : corners) {
            if (corner.next == null || corner.rail != null || corner.pinned || corner.next.pinned
                  || lengths.getOrDefault(corner, 0f) == 0) { continue; }
            Vector3 middle = new Vector3(corner.original).lerp(corner.next.original, .5f);
            BoardScene.Tile owner = corner.tiles.stream().filter(tile -> tile.surface() == BoardScene.Surface.CONCRETE
                  && !tile.liquid().present() && corner.next.tiles.contains(tile)).findFirst().orElse(null);
            if (owner == null) { continue; }
            Set<Side> candidates = new HashSet<>();
            for (Coords at : owner.coords().allAtDistanceOrLess(3)) { candidates.addAll(nearby.getOrDefault(at, List.of())); }
            float error = 24 * scale;
            Vector3 direction = new Vector3(corner.next.point).sub(corner.point).nor();
            for (Side side : candidates) {
                if (side.a.dst(side.b) <= lengths.get(corner) + .001f * scale) { continue; }
                float facing = -direction.y * side.line.nx + direction.x * side.line.ny;
                // A continuation repairs a turn at a branch; it must not shift an already parallel side.
                if (facing <= 0 || facing > .98f) { continue; }
                float distance = Math.abs(side.line.side(middle));
                if (distance >= error) { continue; }
                Vector3 point = side.line.project(middle), along = new Vector3(side.b).sub(side.a);
                float t = new Vector3(point).sub(side.a).dot(along) / along.len2();
                if (t >= 0 && t <= 1) { continue; }
                Vector3 end = t < 0 ? side.a : side.b;
                float gap = end.dst(point);
                if (gap < BoardGeometry.width() / 2 || gap > 3 * BoardGeometry.width()) { continue; }
                boolean paved = true;
                int steps = (int) Math.ceil(gap / (BoardGeometry.width() / 4));
                for (int step = 1; paved && step < steps; step++) {
                    Vector3 p = new Vector3(end).lerp(point, step / (float) steps);
                    BoardScene.Tile tile = BoardGeometry.tile(scene, p.x, p.y);
                    paved &= tile != null && (corner.tiles.contains(tile) || corner.next.tiles.contains(tile)
                          || tile.surface() == BoardScene.Surface.CONCRETE && !tile.liquid().present()
                                && tile.elevation() == owner.elevation());
                }
                if (paved && clearEdge(scene, corner, side.line.project(corner.original), side.line.project(corner.next.original))) {
                    corner.rail = side.line;
                    error = distance;
                    changed.add(corner);
                }
            }
        }
        return changed;
    }

    private static Line dockRail(BoardScene scene, BoardScene.Tile land, Vector3 a, Vector3 b) {
        int links = dockLinks(scene, land);
        if (links <= 0) { return null; } // An isolated concrete tile always keeps its hex footprint.
        Vector3 c = BoardGeometry.center(land.coords(), 0);
        float mx = (a.x + b.x) / 2 - c.x, my = (a.y + b.y) / 2 - c.y;
        int first = Integer.numberOfTrailingZeros(links), direction = first;
        float best = Float.NEGATIVE_INFINITY;
        boolean straight = links == ((1 << first) | (1 << (first + 3) % 6));
        // At a bend, each bank follows the nearest connected arm. Their fixed-width rails meet at a mitre.
        for (int d = 0; d < 6 && !straight; d++) {
            if ((links & 1 << d) == 0) { continue; }
            float[] candidate = axis(land.coords(), d);
            float along = mx * candidate[0] + my * candidate[1];
            if (along > best) { direction = d; best = along; }
        }
        float[] axis = axis(land.coords(), direction);
        float nx = -axis[1], ny = axis[0], half = dockHalf(land, axis);
        float across = mx * nx + my * ny;
        if (Integer.bitCount(links) == 1 && Math.abs(across) < half / 2) {
            // A square end cap, perpendicular to the same axis as the two parallel sides.
            float along = mx * axis[0] + my * axis[1];
            float sign = Math.signum(along);
            nx = -sign * axis[0];
            ny = -sign * axis[1];
            half = BoardGeometry.center(land.coords().translated(direction), 0).dst(c) / 2;
        } else {
            float sign = Math.signum(across);
            nx *= -sign;
            ny *= -sign;
        }
        return new Line(nx, ny, nx * c.x + ny * c.y - half);
    }

    /** Fit the quay between fixed rectangle sides, then use their intersections as the landing corners. */
    private static void fitLanding(List<Corner> chain, int first, int last, BoardScene scene) {
        if (last - first < 2) { return; }
        List<Run> runs = new ArrayList<>();
        for (int i = first; i < last;) {
            Line rail = chain.get(i).rail;
            int end = i + 1;
            while (end < last && (rail == null ? chain.get(end).rail == null
                  : chain.get(end).rail != null && rail.same(chain.get(end).rail))) { end++; }
            if (rail == null) { fitQuay(chain, i, end, scene, runs); }
            else { runs.add(new Run(i, end, rail, true)); }
            i = end;
        }
        // A short, almost-parallel apron belongs to the same rectangular side. Keeping it as a separate line
        // would create either a taper or a distant intersection at the dock root.
        for (int i = 0; i + 1 < runs.size();) {
            Run a = runs.get(i), b = runs.get(i + 1);
            Line rail = a.rectangle ? a.line : b.rectangle ? b.line : null;
            boolean fits = rail != null && a.rectangle != b.rectangle
                  && (a.line.nx * b.line.nx + a.line.ny * b.line.ny > .98f
                        || (a.rectangle ? b.last - b.first : a.last - a.first) <= 2);
            for (int j = a.first; fits && j <= b.last; j++) {
                fits = Math.abs(rail.side(chain.get(j).point)) <= 24 * BoardGeometry.hexScale();
            }
            if (fits) {
                runs.set(i, new Run(a.first, b.last, rail, true));
                runs.remove(i + 1);
            } else if (!a.rectangle && !b.rectangle) {
                List<Run> merged = new ArrayList<>();
                fitQuay(chain, a.first, b.last, scene, merged);
                if (merged.size() == 1) {
                    runs.set(i, merged.getFirst());
                    runs.remove(i + 1);
                } else { i++; }
            } else { i++; }
        }
        // A short hex-shaped detour at a junction can hide the intersection of its two longer sides. Extend
        // those sides only if the resulting corner and every adjoining edge still clear their terrain centres.
        for (int i = 0; i + 2 < runs.size();) {
            Run a = runs.get(i), middle = runs.get(i + 1), b = runs.get(i + 2);
            Vector3 joint = a.line.intersection(b.line);
            if (middle.rectangle || joint == null) { i++; continue; }
            int split = nearest(chain, joint, middle.first, middle.last);
            List<Run> candidate = new ArrayList<>(runs);
            candidate.set(i, new Run(a.first, split, a.line, a.rectangle));
            candidate.set(i + 1, new Run(split, b.last, b.line, b.rectangle));
            candidate.remove(i + 2);
            if (landingPoints(chain, first, last, scene, candidate) != null) { runs = candidate; }
            else { i++; }
        }
        Vector3[] points = landingPoints(chain, first, last, scene, runs);
        if (points == null) { return; }
        for (int i = first; i <= last; i++) { chain.get(i).point.set(points[i - first]); }
    }

    private static Vector3[] landingPoints(List<Corner> chain, int first, int last, BoardScene scene, List<Run> runs) {
        // The fitted intersection may belong to a neighbouring lattice corner. Assign it to the nearest one,
        // instead of pulling the arbitrary subdivision endpoint across a whole hex and rejecting both long sides.
        List<Run> joined = new ArrayList<>(runs);
        for (int i = 0; i + 1 < joined.size(); i++) {
            Run a = joined.get(i), b = joined.get(i + 1);
            Vector3 joint = a.line.intersection(b.line);
            if (joint == null) { continue; }
            int split = nearest(chain, joint, a.first + 1, b.last - 1);
            joined.set(i, new Run(a.first, split, a.line, a.rectangle));
            joined.set(i + 1, new Run(split, b.last, b.line, b.rectangle));
        }
        Vector3[] points = outlinePoints(chain, first, last, scene, joined);
        return points == null && !joined.equals(runs) ? outlinePoints(chain, first, last, scene, runs) : points;
    }

    private static Vector3[] outlinePoints(List<Corner> chain, int first, int last, BoardScene scene, List<Run> runs) {
        Vector3[] points = new Vector3[last - first + 1];
        points[0] = new Vector3(chain.get(first).point);
        points[points.length - 1] = new Vector3(chain.get(last).point);
        // A run ends where the map ends; an unprotected border corner must not introduce a final hex bevel.
        if (!runs.isEmpty()) {
            if (chain.get(first).border && !chain.get(first).pinned) {
                points[0] = runs.getFirst().line.project(chain.get(first).point);
            }
            if (chain.get(last).border && !chain.get(last).pinned) {
                points[points.length - 1] = runs.getLast().line.project(chain.get(last).point);
            }
        }
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            for (int j = run.first + 1; j < run.last; j++) {
                points[j - first] = run.line.project(chain.get(j).point);
            }
            if (i + 1 < runs.size()) {
                Line next = runs.get(i + 1).line;
                Vector3 joint = run.line.same(next) ? run.line.project(chain.get(run.last).point)
                      : run.line.intersection(next);
                if (joint == null) { return null; }
                points[run.last - first] = joint;
            }
        }
        if (chain.get(first) == chain.get(last) && !chain.get(first).pinned && !runs.isEmpty()) {
            Line before = runs.getLast().line, after = runs.getFirst().line;
            Vector3 joint = before.same(after) ? after.project(chain.get(first).point) : before.intersection(after);
            if (joint == null) { return null; }
            points[0] = joint;
            points[points.length - 1] = joint;
        }
        // A failed fit leaves the original rectangle intact. Never taper a pier to accommodate its landing.
        for (int i = first; i <= last; i++) {
            Vector3 p = points[i - first];
            if (p == null || p.dst(chain.get(i).original) > 42.001f * BoardGeometry.hexScale()) { return null; }
            if (i < last && points[i + 1 - first] == null) { return null; }
            if (i < last && !clearEdge(scene, chain.get(i), p, points[i + 1 - first])) { return null; }
        }
        return points;
    }

    /** Straight quay runs use the board's three construction axes or their perpendiculars. */
    private static void fitQuay(List<Corner> chain, int first, int last, BoardScene scene, List<Run> runs) {
        Vector3 a = chain.get(first).point, b = chain.get(last).point;
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
        if (length < .001f) {
            if (last - first < 2) { return; }
            int middle = (first + last) / 2;
            fitQuay(chain, first, middle, scene, runs);
            fitQuay(chain, middle, last, scene, runs);
            return;
        }
        float angle = (float) (Math.round(Math.atan2(dy, dx) / (Math.PI / 6)) * Math.PI / 6);
        float nx = -(float) Math.sin(angle), ny = (float) Math.cos(angle);
        float offset = 0;
        int begin = last - first > 1 ? first + 1 : first, end = last - first > 1 ? last - 1 : last;
        for (int i = begin; i <= end; i++) { offset += nx * chain.get(i).point.x + ny * chain.get(i).point.y; }
        offset /= end - begin + 1;
        float low = Float.NEGATIVE_INFINITY, high = Float.POSITIVE_INFINITY;
        for (int i = begin; i <= end; i++) {
            for (BoardScene.Tile tile : chain.get(i).tiles) {
                float at = nx * BoardGeometry.centerX(tile.coords()) + ny * BoardGeometry.centerY(tile.coords());
                if (outside(tile)) { low = Math.max(low, at + 18 * BoardGeometry.hexScale()); }
                else { high = Math.min(high, at - 16 * BoardGeometry.hexScale()); }
            }
        }
        if (low <= high) { offset = Math.clamp(offset, low, high); }
        Line line = new Line(nx, ny, offset);
        if (last - first == 1) {
            // An unsimplifiable single edge retains its endpoints.
            line = new Line(-dy / length, dx / length, (-dy * a.x + dx * a.y) / length);
        }
        int split = (first + last) / 2;
        float error = 0;
        for (int i = first + 1; i < last; i++) {
            float distance = Math.abs(line.side(chain.get(i).point));
            if (distance > error) { error = distance; split = i; }
        }
        if (last - first > 1 && (error > 24 * BoardGeometry.hexScale() || low > high)) {
            fitQuay(chain, first, split, scene, runs);
            fitQuay(chain, split, last, scene, runs);
        } else { runs.add(new Run(first, last, line, false)); }
    }

    private static boolean clearEdge(BoardScene scene, Corner corner, Vector3 a, Vector3 b) {
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
        if (length < .001f) { return false; }
        for (BoardScene.Tile tile : corner.tiles) {
            if (!corner.next.tiles.contains(tile)) { continue; }
            float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
            float side = (dx * (cy - a.y) - dy * (cx - a.x)) / length;
            if (outside(tile) ? side > -17.999f * BoardGeometry.hexScale()
                  : side < 15.999f * BoardGeometry.hexScale()) { return false; }
            if (fixedBoundary(scene, corner, tile)) {
                for (int k = 0; k < 6; k++) {
                    Vector3 p = BoardGeometry.corner(tile.coords(), 0, k);
                    float clearance = dx * (p.y - a.y) - dy * (p.x - a.x);
                    if (outside(tile) ? clearance > .001f : clearance < -.001f) { return false; }
                }
            }
        }
        return true;
    }

    private static boolean protectedLand(BoardScene scene, BoardScene.Tile tile) {
        return fixedFootprint(tile) || tile.roadExits() != 0 || BoardSurface.ramps(scene, tile) != 0;
    }

    private static boolean fixedFootprint(BoardScene.Tile tile) {
        return !tile.detailedGround() || tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.BUILDING
              || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL || feature.kind() == BoardScene.FeatureKind.PROP);
    }

    private static boolean fixedBoundary(BoardScene scene, Corner corner, BoardScene.Tile tile) {
        // Moving a material boundary on one ground plane preserves the complete neighbouring foundation.
        // Structures on the concrete itself, height changes and road ramps keep their original footprint.
        return !tile.liquid().present() && protectedLand(scene, tile)
              && (!outside(tile) || tile.ultraSublevel() || BoardSurface.ramps(scene, tile) != 0
                    || corner.tiles.stream().anyMatch(other -> other.liquid().present() || other.elevation() != tile.elevation()));
    }

    private static boolean outside(BoardScene.Tile tile) {
        return tile != null && !tile.liquid().molten() && (tile.liquid().present()
              || mode() == Mode.EVERYWHERE && tile.surface() != BoardScene.Surface.CONCRETE);
    }

    static boolean concreteBank(BoardScene scene, BoardScene.Tile water, int edge) {
        BoardScene.Tile land = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(edge)));
        return land != null && land.surface() == BoardScene.Surface.CONCRETE && !land.liquid().present()
              && land.elevation() >= water.elevation();
    }

    private static float dockHalf(BoardScene.Tile land, float[] axis) {
        float nx = -axis[1], ny = axis[0], half = Float.POSITIVE_INFINITY;
        float cx = BoardGeometry.centerX(land.coords()), cy = BoardGeometry.centerY(land.coords());
        // The end edge supplies the dock's width; intermediate hex tips are clipped to its parallel sides.
        for (int k = 0; k < 6; k++) {
            Vector3 corner = BoardGeometry.corner(land.coords(), 0, k);
            half = Math.min(half, Math.abs((corner.x - cx) * nx + (corner.y - cy) * ny));
        }
        return half;
    }

    /** Zero links: platform; one: end; two opposite: span; two others: angle. Other patterns are quay landings. */
    private static int dockLinks(BoardScene scene, BoardScene.Tile land) {
        if (land.liquid().present() || land.surface() != BoardScene.Surface.CONCRETE
              || protectedLand(scene, land)) { return -1; }
        int links = 0;
        Integer waterLevel = null;
        for (int d = 0; d < 6; d++) {
            BoardScene.Tile other = scene.tile(land.coords().translated(d));
            if (other == null) { return -1; }
            if (outside(other)) {
                if (other.liquid().present()) {
                    if (other.frozen() || other.elevation() > land.elevation()
                          || waterLevel != null && waterLevel != other.elevation()) { return -1; }
                    waterLevel = other.elevation();
                }
            } else {
                if (other.surface() != BoardScene.Surface.CONCRETE || other.elevation() != land.elevation()) { return -1; }
                links |= 1 << d;
            }
        }
        return Integer.bitCount(links) <= 2 ? links : -1;
    }

    private static float[] axis(Coords coords, int direction) {
        var next = coords.translated(direction);
        float dx = BoardGeometry.centerX(next) - BoardGeometry.centerX(coords);
        float dy = BoardGeometry.centerY(next) - BoardGeometry.centerY(coords);
        float length = (float) Math.hypot(dx, dy);
        return new float[] { dx / length, dy / length };
    }
}
