/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** Fitted concrete rectangles and their joins, shared with adjoining terrain. No game state or GL objects. */
final class BoardConcrete {
    enum Mode { OFF, WATER_ONLY, EVERYWHERE }

    static final Mode DEFAULT_MODE = Mode.WATER_ONLY;
    private static Mode mode = DEFAULT_MODE;
    private static Mode cachedMode;

    static Mode mode() { return mode; }

    static void tune(Mode next) {
        if (next != mode) {
            mode = java.util.Objects.requireNonNull(next);
            BoardGeometry.terrainChanged();
        }
    }
    record Shift(float x, float y) { }

    private static final Shift ZERO = new Shift(0, 0);
    /** One immutable derived outline, shared by terrain and picking; no scene, artwork or GL resources are retained. */
    private static WeakReference<List<BoardScene.Tile>> snapshot = new WeakReference<>(null);
    private static BoardConcrete cached;
    private static int width, height;
    private static float scale;
    private final Map<Long, Shift> shifts;

    static synchronized BoardConcrete of(BoardScene scene) {
        if (snapshot.get() != scene.tiles() || width != scene.width() || height != scene.height()
              || scale != BoardGeometry.HEX_SCALE || cachedMode != mode) {
            cached = new BoardConcrete(scene);
            snapshot = new WeakReference<>(scene.tiles());
            width = scene.width();
            height = scene.height();
            scale = BoardGeometry.HEX_SCALE;
            cachedMode = mode;
        }
        return cached;
    }

    Shift shift(long corner) { return shifts.getOrDefault(corner, ZERO); }

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
        return ((long) Math.round(p.x / (BoardGeometry.WIDTH / 4)) << 32)
              ^ (Math.round(p.y / (BoardGeometry.HEIGHT / 2)) & 0xffffffffL);
    }

    /** Construction-only vertices. Each is the same lattice corner seen by its three adjoining hexes. */
    private static final class Corner {
        final Vector3 original;
        final Vector3 point;
        final Set<BoardScene.Tile> tiles = new HashSet<>();
        Corner previous, next;
        Line rail;
        boolean pinned, dock;

        Corner(Vector3 point) {
            original = point;
            this.point = new Vector3(point);
        }
    }

    private BoardConcrete(BoardScene scene) {
        if (mode == Mode.OFF) { shifts = Map.of(); return; }
        Map<Long, Corner> corners = new TreeMap<>();
        for (BoardScene.Tile land : scene.tiles()) {
            if (land.liquid().present() || land.surface() != BoardScene.Surface.CONCRETE) { continue; }
            for (int e = 0; e < 6; e++) {
                BoardScene.Tile water = scene.tile(land.coords().translated(BoardGeometry.edgeDirection(e)));
                if (!outside(water) || water.liquid().present() && water.elevation() > land.elevation()) { continue; }
                Corner a = coastCorner(corners, land.coords(), e), b = coastCorner(corners, land.coords(), (e + 1) % 6);
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
            corner.pinned = corner.tiles.size() != 3 || corner.previous == null || corner.next == null;
            for (BoardScene.Tile tile : corner.tiles) {
                if (outside(tile)) {
                    if (tile.liquid().present()) {
                        corner.pinned |= tile.frozen() || waterLevel != null && waterLevel != tile.elevation();
                        waterLevel = tile.elevation();
                    } else {
                        corner.pinned |= protectedOutside(scene, tile);
                    }
                } else {
                    corner.pinned |= protectedLand(scene, tile) || dryLevel != null && dryLevel != tile.elevation();
                    corner.pinned |= dockLinks(scene, tile) == 0;
                    dryLevel = tile.elevation();
                    // A foundation also pins its immediate apron: adjoining empty paving must not form a notch
                    // against a building's unchanged hex boundary.
                    for (int d = 0; d < 6; d++) {
                        BoardScene.Tile neighbor = scene.tile(tile.coords().translated(d));
                        corner.pinned |= neighbor != null && !neighbor.liquid().present()
                              && (outside(neighbor) ? protectedOutside(scene, neighbor) : protectedLand(scene, neighbor));
                    }
                }
            }
            if (!corner.pinned) {
                Line before = corner.previous.rail, after = corner.rail;
                corner.dock = before != null || after != null;
                if (before != null && after != null && !before.same(after)) {
                    Vector3 joint = before.intersection(after);
                    if (joint != null) { corner.point.set(joint); corner.pinned = true; }
                } else if (corner.dock) {
                    corner.point.set((before != null ? before : after).project(corner.point));
                }
            }
        }
        Set<Corner> visited = new HashSet<>();
        // Open chains first; otherwise starting midway would split a continuous quay at an arbitrary hex.
        for (Corner corner : corners.values()) {
            if (corner.previous == null) { simplifyChain(corner, visited, scene); }
        }
        for (Corner corner : corners.values()) { simplifyChain(corner, visited, scene); }
        Map<Long, Shift> result = new TreeMap<>();
        corners.forEach((key, corner) -> {
            float dx = corner.point.x - corner.original.x, dy = corner.point.y - corner.original.y;
            if (Math.hypot(dx, dy) > .001f) { result.put(key, new Shift(dx, dy)); }
        });
        shifts = Map.copyOf(result);
    }

    private static Corner coastCorner(Map<Long, Corner> corners, Coords coords, int k) {
        Vector3 point = BoardGeometry.corner(coords, 0, k);
        return corners.computeIfAbsent(key(point), ignored -> new Corner(point));
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
        for (float angle : new float[] { 0, (float) Math.atan2(BoardGeometry.HEIGHT / 2, .75f * BoardGeometry.WIDTH),
              -(float) Math.atan2(BoardGeometry.HEIGHT / 2, .75f * BoardGeometry.WIDTH) }) {
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
            if (Math.min(u1 - u0, v1 - v0) < 54 * BoardGeometry.HEX_SCALE) { continue; }
            float margin = 16 * BoardGeometry.HEX_SCALE;
            Line[] sides = { new Line(ux, uy, u0 - margin), new Line(vx, vy, v0 - margin),
                  new Line(-ux, -uy, -u1 - margin), new Line(-vx, -vy, -v1 - margin) };
            Line[] edges = new Line[chain.size()];
            int[] starts = new int[4];
            for (int k = 0; k < 4; k++) {
                Vector3 corner = sides[(k + 3) % 4].intersection(sides[k]);
                float nearest = Float.POSITIVE_INFINITY;
                for (int i = 0; i < chain.size(); i++) {
                    float distance = corner.dst2(chain.get(i).original);
                    if (distance < nearest) { nearest = distance; starts[k] = i; }
                }
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
                if (p == null || p.dst(chain.get(i).original) > 42 * BoardGeometry.HEX_SCALE) { fits = false; break; }
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

    private static void simplifyChain(Corner start, Set<Corner> visited, BoardScene scene) {
        if (visited.contains(start)) { return; }
        List<Corner> chain = new ArrayList<>();
        for (Corner at = start; at != null && visited.add(at); at = at.next) {
            chain.add(at);
        }
        if (chain.size() < 3) { return; }
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
                  && Math.abs(offset - other.offset) < .001f * BoardGeometry.HEX_SCALE;
        }

        Vector3 intersection(Line other) {
            float determinant = nx * other.ny - ny * other.nx;
            return Math.abs(determinant) < .001f ? null : new Vector3(
                  (offset * other.ny - ny * other.offset) / determinant,
                  (nx * other.offset - offset * other.nx) / determinant, 0);
        }
    }

    private record Run(int first, int last, Line line, boolean rectangle) { }

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
                fits = Math.abs(rail.side(chain.get(j).point)) <= 24 * BoardGeometry.HEX_SCALE;
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
        Vector3[] points = new Vector3[last - first + 1];
        points[0] = new Vector3(chain.get(first).point);
        points[points.length - 1] = new Vector3(chain.get(last).point);
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            for (int j = run.first + 1; j < run.last; j++) {
                points[j - first] = run.line.project(chain.get(j).point);
            }
            if (i + 1 < runs.size()) {
                Line next = runs.get(i + 1).line;
                Vector3 joint = run.line.same(next) ? run.line.project(chain.get(run.last).point)
                      : run.line.intersection(next);
                if (joint == null) { return; }
                points[run.last - first] = joint;
            }
        }
        if (chain.get(first) == chain.get(last) && !chain.get(first).pinned && !runs.isEmpty()) {
            Line before = runs.getLast().line, after = runs.getFirst().line;
            Vector3 joint = before.same(after) ? after.project(chain.get(first).point) : before.intersection(after);
            if (joint == null) { return; }
            points[0] = joint;
            points[points.length - 1] = joint;
        }
        // A failed fit leaves the original rectangle intact. Never taper a pier to accommodate its landing.
        for (int i = first; i <= last; i++) {
            Vector3 p = points[i - first];
            if (p == null || p.dst(chain.get(i).original) > 42.001f * BoardGeometry.HEX_SCALE) { return; }
            if (i < last && points[i + 1 - first] == null) { return; }
            if (i < last && !clearEdge(scene, chain.get(i), p, points[i + 1 - first])) { return; }
        }
        for (int i = first; i <= last; i++) { chain.get(i).point.set(points[i - first]); }
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
                if (outside(tile)) { low = Math.max(low, at + 18 * BoardGeometry.HEX_SCALE); }
                else { high = Math.min(high, at - 16 * BoardGeometry.HEX_SCALE); }
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
        if (last - first > 1 && (error > 24 * BoardGeometry.HEX_SCALE || low > high)) {
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
            if (outside(tile) ? side > -17.999f * BoardGeometry.HEX_SCALE
                  : side < 15.999f * BoardGeometry.HEX_SCALE) { return false; }
            if (!tile.liquid().present() && (outside(tile) ? protectedOutside(scene, tile) : protectedLand(scene, tile))) {
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
        return !tile.detailedGround() || tile.roadExits() != 0 || BoardSurface.ramps(scene, tile) != 0
              || tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.BUILDING
              || feature.kind() == BoardScene.FeatureKind.PROP);
    }

    private static boolean protectedOutside(BoardScene scene, BoardScene.Tile tile) {
        // A flat road can meet a moved concrete edge on the same ground plane. Height-changing approaches and
        // foundations stay fixed. The centre and the road connection remain in their original game hex.
        if (tile.roadExits() != 0 && BoardSurface.ramps(scene, tile) == 0 && tile.features().isEmpty()) { return false; }
        return protectedLand(scene, tile);
    }

    private static boolean outside(BoardScene.Tile tile) {
        return tile != null && !tile.liquid().molten() && (tile.liquid().present()
              || mode == Mode.EVERYWHERE && tile.surface() != BoardScene.Surface.CONCRETE);
    }

    static boolean concreteBank(BoardScene scene, BoardScene.Tile water, int edge) {
        BoardScene.Tile land = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(edge)));
        return land != null && land.surface() == BoardScene.Surface.CONCRETE
              && !land.liquid().present() && land.elevation() >= water.elevation();
    }

    /**
     * Join paved banks across water-side notches, keeping the canonical mouth endpoints. A shortcut that would
     * cross the water centre is rejected using the actual lattice geometry, irrespective of direction.
     */
    static void straighten(BoardScene scene, BoardScene.Tile water, Vector3[] shore) {
        if (mode == Mode.OFF || water.liquid().molten()) { return; }
        int paved = 0;
        for (int e = 0; e < 6; e++) {
            if (concreteBank(scene, water, e)) { paved |= 1 << e; }
        }
        if (paved == 63) {
            for (int e = 0; e < 6; e++) { chord(water, shore, e, 1); }
            return;
        }
        for (int e = 0; e < 6; e++) {
            if ((paved & 1 << e) == 0 || (paved & 1 << (e + 5) % 6) != 0) { continue; }
            int count = 1;
            while ((paved & 1 << (e + count) % 6) != 0) { count++; }
            boolean fitted = false;
            for (int k = 0; k < count; k++) {
                BoardScene.Tile land = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(e + k)));
                fitted |= dockLinks(scene, land) >= 0 || of(scene).corners(land.coords()).stream()
                      .anyMatch(shift -> shift.x != 0 || shift.y != 0);
            }
            if (fitted) { continue; }
            if (!chord(water, shore, e, count)) {
                // Keep an inlet open, with individual straight sections along its banks.
                for (int k = 0; k < count; k++) { chord(water, shore, (e + k) % 6, 1); }
            }
        }
    }

    private static boolean chord(BoardScene.Tile water, Vector3[] shore, int first, int count) {
        int perEdge = shore.length / 6;
        Vector3 a = shore[first * perEdge], b = shore[(first + count) % 6 * perEdge];
        float dx = b.x - a.x, dy = b.y - a.y;
        float cx = BoardGeometry.centerX(water.coords()), cy = BoardGeometry.centerY(water.coords());
        float clearance = (dx * (cy - a.y) - dy * (cx - a.x)) / (float) Math.hypot(dx, dy);
        if (!(clearance >= (water.waterDepth() > 0 ? 12 : 4) * BoardGeometry.HEX_SCALE)) { return false; }
        int samples = count * perEdge;
        for (int i = 1; i < samples; i++) {
            shore[(first * perEdge + i) % shore.length] = new Vector3(a).lerp(b, i / (float) samples);
        }
        return true;
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
