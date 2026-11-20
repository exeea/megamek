/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.ConcreteFit;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;

/**
 * The scene's fitted concrete outline ({@link ConcreteFit}), shared with adjoining terrain, water, artwork and picking:
 * cached per scene, with the hexes whose placed objects pin them. No game state or GL objects.
 */
final class BoardConcrete {
    final float scale = BoardGeometry.hexScale();
    private final ConcreteFit fit;
    /** The structures' footprints by paved hex (#standing), which the fit and the quay extensions read. */
    private final Map<Coords, List<ConcreteFit.Point>> footprints;
    /** The carriageways' courses as the board alone gives them (#courses), each made once. */
    private final Map<Coords, BoardRoad> courses = new ConcurrentHashMap<>();
    private final List<Cut> cuts;

    /** A placed object inside a wall or bank of its hex, whose Edges are Fitted: the editor lists it as an issue. */
    record Cut(Coords at, String object, String asset) { }

    static BoardConcrete of(BoardScene scene) {
        return scene.ground().concrete(scene);
    }

    /** The fit itself, with the hexes the scene's placed objects pin. */
    ConcreteFit fit() { return fit; }

    ConcreteFit.Shift shift(long corner) { return fit.shift(corner); }

    /** The same fitted corner for terrain, water, artwork and picking in both GPU views. */
    Vector3 corner(Coords coords, int corner) { return corner(fit, coords, corner); }

    private static Vector3 corner(ConcreteFit fit, Coords coords, int corner) {
        ConcreteFit.Shift delta = fit.shift(coords, Math.floorMod(corner, 6));
        return BoardGeometry.corner(coords, 0, corner).add(delta.x(), delta.y(), 0);
    }

    /** Signed distance to the fitted footprint; NaN means this hex still uses its original outline. */
    float distance(Coords coords, float x, float y) {
        float[] outline = fit.footprint(coords);
        return outline == null ? Float.NaN : signed(outline, x, y);
    }

    /**
     * Where the paved quay ends beside hex {@code h}, for the water drawn over it: the signed distance on the board
     * plane from (x, y) to the edge of its paved neighbours' pavement, their fitted outlines and their quay extensions
     * (#extensions) together. Negative on pavement, where no water or bed shows; positive in the open, the distance
     * from the quay's edge; positive infinity where no paved hex touches h.
     */
    float pavedEdge(BoardScene scene, Coords h, float x, float y) {
        BoardScene.Tile tile = scene.tile(h);
        float result = Float.POSITIVE_INFINITY;
        for (int d = 0; tile != null && d < 6; d++) {
            BoardScene.Tile next = scene.neighbor(tile, d);
            if (next == null || next.surface() != BoardScene.Surface.CONCRETE || next.liquid().present()) { continue; }
            float[] outline = fit.footprint(next.coords());
            if (outline == null) {
                outline = new float[12];
                for (int k = 0; k < 6; k++) {
                    Vector3 c = BoardGeometry.corner(next.coords(), 0, k);
                    outline[2 * k] = c.x;
                    outline[2 * k + 1] = c.y;
                }
            }
            result = Math.min(result, signed(outline, x, y));
            for (List<Vector3> polygon : extensions(scene, next)) {
                float[] xy = new float[polygon.size() * 2];
                for (int i = 0; i < polygon.size(); i++) { xy[2 * i] = polygon.get(i).x; xy[2 * i + 1] = polygon.get(i).y; }
                result = Math.min(result, signed(xy, x, y));
            }
        }
        return result;
    }

    /** Signed distance to the closed polygon {@code outline} (x, y pairs; negative inside). */
    private static float signed(float[] outline, float x, float y) {
        int n = outline.length / 2;
        // The nearest edge is chosen on exact squared offsets; only that offset is measured, as every edge was.
        double nearest = Double.POSITIVE_INFINITY;
        float nearestX = 0, nearestY = 0, ax = 0, ay = 0;
        boolean inside = false;
        for (int k = 0; k <= n; k++) {
            int index = (k % n) * 2;
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

    List<ConcreteFit.Shift> corners(Coords coords) { return fit.corners(coords); }

    boolean sameCorners(BoardConcrete other, Coords coords) {
        return other != null && fit.sameCorners(other.fit, coords);
    }

    BoardConcrete(BoardScene scene) {
        List<ConcreteFit.Cell> cells = new ArrayList<>(scene.tiles().size());
        for (BoardScene.Tile tile : scene.tiles()) { cells.add(cell(scene, tile)); }
        // A placed object keeps its hex's outline only where the fit moves a wall or bank into it. Pinning its hex can move
        // other edges, so the fit repeats until it reaches no further object; the set of pinned hexes only grows.
        Set<Coords> pins = new HashSet<>();
        // The structures' footprints (walkway and margin included), which no straightened quay cuts.
        Map<Coords, List<ConcreteFit.Point>> standing = new java.util.HashMap<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (tile.surface() != BoardScene.Surface.CONCRETE || tile.liquid().present()) { continue; }
            List<ConcreteFit.Point> footprint = standing(scene, tile);
            if (footprint != null) { standing.put(tile.coords(), footprint); }
        }
        footprints = standing;
        // Nor does a corner move into the course of a way that does not run on this pavement: it goes back on its hex,
        // and the fit repeats with it held (the held corners only grow, as the pinned hexes do). The same for a side
        // whose quay extension a way keeps a structure from having (#unpaved).
        Set<Long> held = new HashSet<>();
        ConcreteFit next = fit(scene, cells, pins, standing, held);
        while (true) {
            Set<Coords> reached = reached(scene, next, pins);
            Set<Long> ways = inWays(scene, next, held);
            ways.addAll(unpaved(scene, next));
            if (reached.isEmpty() && ways.isEmpty()) { break; }
            pins.addAll(reached);
            held.addAll(ways);
            next = fit(scene, cells, pins, standing, held);
        }
        fit = next;
        List<Cut> inside = new ArrayList<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (edges(tile) != HexAppearance.Edges.FITTED || fit.footprint(tile.coords()) == null) { continue; }
            for (BoardScene.Feature feature : tile.features()) {
                if (feature.decoration() == null || !fitFeature(feature)) { continue; }
                List<ConcreteFit.Point> footprint = footprint(scene, tile, feature);
                if (footprint != null && reaches(scene, fit, tile, feature, footprint)) {
                    inside.add(new Cut(tile.coords(), feature.decoration().id(), feature.decoration().asset()));
                }
            }
        }
        cuts = List.copyOf(inside);
    }

    /**
     * A pavement hex's {@link HexAppearance.Edges}, the one check of the 3D views: Hex keeps the hex's outline, Fitted is
     * never pinned by its objects ({@link #cuts} lists those it cuts). Auto on any other ground.
     */
    static HexAppearance.Edges edges(BoardScene.Tile tile) {
        return tile.surface() == BoardScene.Surface.CONCRETE ? HexAppearance.edges(tile.appearance()) : HexAppearance.Edges.AUTO;
    }

    /** How far, in metres, the quay reaches past a structure's corner, so its wall stands off the very edge. */
    static final float MARGIN = .5f;
    /** Each paved hex's quay extensions (#extensions), made on the first ask. */
    private final Map<Coords, List<List<Vector3>>> extensions = new ConcurrentHashMap<>();

    /**
     * More quay where a structure would stand past its hex's fitted side: per side toward water, other ground or a lower
     * level, the part of the hex's structures' footprint (a {@link #MARGIN} wider) beyond the side gives the outward convex
     * chain through that part's corners, from the side's line and back to it.
     * Each extension is that polygon (z 0), its first and last corners on the side's line (which it may follow past the
     * side's own corners); none where no structure stands past a side.
     * Today's surfaces and the height field's plates draw these alone; the hexes' fitted outlines stay as they are.
     */
    List<List<Vector3>> extensions(BoardScene scene, BoardScene.Tile tile) {
        return extensions.computeIfAbsent(tile.coords(), at -> quay(scene, tile, fit, null));
    }

    /**
     * The moved corners of the sides where {@code fit} leaves a structure without its quay extension, a way running
     * there (#quay): in the next fit they go back on their hex, so the building stands no farther past its pavement
     * than its hex gives.
     */
    private Set<Long> unpaved(BoardScene scene, ConcreteFit fit) {
        Set<Long> result = new HashSet<>();
        for (Coords at : footprints.keySet()) {
            List<Integer> without = new ArrayList<>();
            quay(scene, scene.tile(at), fit, without);
            for (int side : without) {
                for (int k : new int[] { side, (side + 1) % 6 }) {
                    if (!fit.shift(at, k).equals(ConcreteFit.Shift.ZERO)) { result.add(fit.cornerKey(at, k)); }
                }
            }
        }
        return result;
    }

    /** #extensions for {@code fit}; {@code without} (where given) takes the sides whose extension a way keeps away. */
    private List<List<Vector3>> quay(BoardScene scene, BoardScene.Tile tile, ConcreteFit fit, List<Integer> without) {
        List<ConcreteFit.Point> footprint = footprints.get(tile.coords());
        if (footprint == null) { return List.of(); }
        Coords at = tile.coords();
        Vector3 middle = BoardGeometry.center(at, 0);
        // Per side toward water, other ground or a lower level: the part of the footprint beyond the side's line, as the
        // footprint clipped to that half-plane (its corners there and where its sides cross the line).
        List<List<ConcreteFit.Point>> beyond = new ArrayList<>();
        // The courses of the roads and rails on the hex and round it (shoulders included), which no extension stands in.
        List<BoardRoad> ways = courses(scene, tile);
        for (int e = 0; e < 6; e++) {
            List<ConcreteFit.Point> part = List.of();
            BoardScene.Tile next = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
            boolean lower = next != null && (next.liquid().present() || next.surface() != BoardScene.Surface.CONCRETE
                  || next.elevation() < tile.elevation());
            Vector3 a = corner(fit, at, e), b = corner(fit, at, e + 1);
            float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
            if (lower && length >= .001f * scale) {
                float nx = dy / length, ny = -dx / length;
                if ((middle.x - a.x) * nx + (middle.y - a.y) * ny > 0) { nx = -nx; ny = -ny; }
                part = beyond(footprint, a, nx, ny, .001f * scale);
            }
            beyond.add(part);
        }
        List<List<Vector3>> result = new ArrayList<>();
        for (int e = 0; e < 6; e++) {
            if (beyond.get(e).isEmpty()) { continue; }
            Vector3 a = corner(fit, at, e), b = corner(fit, at, e + 1);
            List<ConcreteFit.Point> points = new ArrayList<>(beyond.get(e));
            // The quay runs along the footprint's side that faces the water, from where that side crosses the fitted
            // side to its corner beyond, and on from the outermost corner straight to the fitted side's end on that
            // side: the end where the facing side comes in from is not taken, so no tip runs back to it.
            int ends = facingEnds(footprint, a, b, middle);
            if ((ends & 1) != 0) { points.add(new ConcreteFit.Point(a.x, a.y)); }
            if ((ends & 2) != 0) { points.add(new ConcreteFit.Point(b.x, b.y)); }
            List<ConcreteFit.Point> hull = ConcreteFit.hull(points);
            // The polygon starts at its vertex on the fitted side nearest a and ends at the one nearest b, so its last
            // side (back to the first) lies on the fitted side and every other side is quay.
            float dxs = b.x - a.x, dys = b.y - a.y, len2 = dxs * dxs + dys * dys;
            int first = -1, last = -1;
            float tFirst = Float.MAX_VALUE, tLast = -Float.MAX_VALUE;
            for (int i = 0; i < hull.size(); i++) {
                ConcreteFit.Point p = hull.get(i);
                float off = Math.abs((p.x() - a.x) * dys - (p.y() - a.y) * dxs) / (float) Math.sqrt(len2);
                if (off > .01f * scale) { continue; }
                float s = ((p.x() - a.x) * dxs + (p.y() - a.y) * dys) / len2;
                if (s < tFirst) { tFirst = s; first = i; }
                if (s > tLast) { tLast = s; last = i; }
            }
            if (first < 0 || last < 0 || first == last) { continue; }
            List<Vector3> polygon = new ArrayList<>(hull.size());
            // Walk from first to last the way that does not run along the fitted side.
            int step = (first + 1) % hull.size() == last ? -1 : 1;
            for (int i = first; ; i = Math.floorMod(i + step, hull.size())) {
                polygon.add(new Vector3(hull.get(i).x(), hull.get(i).y(), 0));
                if (i == last) { break; }
            }
            // Not where the structure itself stands in a way: its corners beyond the side (the polygon's first and last
            // lie on the side's line, where it only tapers out).
            if (polygon.subList(1, polygon.size() - 1).stream().noneMatch(p -> inWay(ways, p.x, p.y))) { result.add(polygon); }
            else if (without != null) { without.add(e); }
        }
        return result;
    }

    /**
     * Which ends of the fitted side a to b the quay's chain runs to (1 a, 2 b): of the footprint's sides with a corner
     * beyond the fitted side, the one facing the water most; where it crosses the fitted side, only the end away from
     * that crossing; where both its corners lie beyond, both ends.
     */
    private int facingEnds(List<ConcreteFit.Point> footprint, Vector3 a, Vector3 b, Vector3 middle) {
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
        float nx = dy / length, ny = -dx / length;
        if ((middle.x - a.x) * nx + (middle.y - a.y) * ny > 0) { nx = -nx; ny = -ny; }
        float best = -2, eps = .001f * scale;
        int ends = 3;
        for (int i = 0; i < footprint.size(); i++) {
            ConcreteFit.Point p = footprint.get(i), q = footprint.get((i + 1) % footprint.size());
            float dp = (p.x() - a.x) * nx + (p.y() - a.y) * ny, dq = (q.x() - a.x) * nx + (q.y() - a.y) * ny;
            if (dp <= eps && dq <= eps) { continue; }
            float ex = q.x() - p.x(), ey = q.y() - p.y(), el = (float) Math.hypot(ex, ey);
            if (el < eps) { continue; }
            // The footprint side's outward normal (its hull runs counter-clockwise) against the fitted side's.
            float facing = (ey * nx - ex * ny) / el;
            if (facing <= best) { continue; }
            best = facing;
            if (dp > eps && dq > eps) { ends = 3; continue; }
            float s = dp / (dp - dq);
            float cx = p.x() + s * ex, cy = p.y() + s * ey;
            ConcreteFit.Point out = dp > eps ? p : q;
            float tCross = ((cx - a.x) * dx + (cy - a.y) * dy) / (length * length);
            float tOut = ((out.x() - a.x) * dx + (out.y() - a.y) * dy) / (length * length);
            ends = tCross < tOut ? 2 : 1;
        }
        return ends;
    }

    /** The part of a convex polygon beyond the line through a with outward normal (nx, ny), by more than {@code by}. */
    private static List<ConcreteFit.Point> beyond(List<ConcreteFit.Point> polygon, Vector3 a, float nx, float ny, float by) {
        List<ConcreteFit.Point> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            ConcreteFit.Point p = polygon.get(i), q = polygon.get((i + 1) % polygon.size());
            float dp = (p.x() - a.x) * nx + (p.y() - a.y) * ny, dq = (q.x() - a.x) * nx + (q.y() - a.y) * ny;
            if (dp > by) { result.add(p); }
            if ((dp > by) != (dq > by)) {
                float s = (dp - by) / (dp - dq);
                result.add(new ConcreteFit.Point(p.x() + s * (q.x() - p.x()), p.y() + s * (q.y() - p.y())));
            }
        }
        return result;
    }

    /**
     * The footprint of the structures on a paved hex (buildings, industrial plants, fuel tanks), a {@link #MARGIN} wider:
     * the hull of a placed model's outline and of a kit building's ground outline (#kit), its joined hexes' too; null
     * for none.
     */
    private static List<ConcreteFit.Point> standing(BoardScene scene, BoardScene.Tile tile) {
        List<ConcreteFit.Point> points = new ArrayList<>();
        float walkway = 0;
        for (BoardScene.Feature feature : tile.features()) {
            if (!fitFeature(feature) || feature.kind() == BoardScene.FeatureKind.PROP) { continue; }
            List<ConcreteFit.Point> outline = feature.decoration() == null ? kit(scene, tile, feature)
                  : footprint(scene, tile, feature);
            if (outline == null) { continue; }
            points.addAll(outline);
            if (feature.decoration() != null) { continue; }
            walkway = Math.max(walkway, (float) GpuBuilding.walkway(feature.asset()) * feature.modelScale());
            // The whole building: the hexes it joins across a hexside (an exit that connects back, as ConnectedKit
            // draws it), so its quay runs along the building's side and not back to a hex corner half-way.
            for (int direction = 0; direction < 6; direction++) {
                if ((tile.cover().exits() & 1 << direction) == 0 || !ConnectedKit.back(scene, tile, direction)) { continue; }
                BoardScene.Tile next = scene.neighbor(tile, direction);
                for (BoardScene.Feature joined : next.features()) {
                    List<ConcreteFit.Point> theirs = joined.kind() == feature.kind() && joined.decoration() == null
                          ? kit(scene, next, joined) : null;
                    if (theirs != null) { points.addAll(theirs); }
                }
            }
        }
        if (points.size() < 3) { return null; }
        List<ConcreteFit.Point> hull = new ArrayList<>(ConcreteFit.hull(points));
        // A corner given twice (a model's outline does that) would be mitred as two corners and so cut off.
        for (int i = hull.size() - 1; i >= 0 && hull.size() > 3; i--) {
            ConcreteFit.Point p = hull.get(i), q = hull.get((i + 1) % hull.size());
            if (Math.hypot(q.x() - p.x(), q.y() - p.y()) < .12f * BoardGeometry.hexScale()) { hull.remove(i); }
        }
        // The pavement runs a walkway's width past the building as it is drawn, the kit's own walkway included
        // (#kit). Each side moves out by that, the corners mitred (the hull runs counter-clockwise: outward is
        // (ey, -ex)).
        float by = BoardRelief.metres(MARGIN) + walkway * BoardGeometry.hexScale();
        List<ConcreteFit.Point> grown = new ArrayList<>(hull.size());
        for (int i = 0; i < hull.size(); i++) {
            ConcreteFit.Point o = hull.get((i + hull.size() - 1) % hull.size()), p = hull.get(i), q = hull.get((i + 1) % hull.size());
            float ax = p.x() - o.x(), ay = p.y() - o.y(), al = Math.max((float) Math.hypot(ax, ay), 1e-6f);
            float bx = q.x() - p.x(), byy = q.y() - p.y(), bl = Math.max((float) Math.hypot(bx, byy), 1e-6f);
            float n1x = ay / al, n1y = -ax / al, n2x = byy / bl, n2y = -bx / bl;
            float mitre = by / Math.max(1 + n1x * n2x + n1y * n2y, .25f);
            grown.add(new ConcreteFit.Point(p.x() + (n1x + n2x) * mitre, p.y() + (n1y + n2y) * mitre));
        }
        return grown;
    }

    /**
     * A kit building's ground outline on its hex, from the kit's data alone (GpuBuilding#outline; no model is read): its
     * footprint's floor polygons for the sides it links across (ConnectedKit#reach), placed as the building is drawn.
     * Null for a structure whose kit gives no such outline.
     */
    private static List<ConcreteFit.Point> kit(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        int links = 0;
        int[] reach = ConnectedKit.reach(scene, tile, Math.max(1, Math.round(feature.height())));
        for (int direction = 0; direction < 6; direction++) { links |= reach[direction] > 0 ? 1 << direction : 0; }
        List<double[]> outline = GpuBuilding.outline(feature.asset(), feature.footprintScale(), links);
        if (outline == null) { return null; }
        double turn = Math.toRadians(feature.rotation()), c = Math.cos(turn), s = Math.sin(turn);
        float scale = feature.modelScale() * BoardGeometry.hexScale();
        float x = BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale();
        float y = BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale();
        List<ConcreteFit.Point> points = new ArrayList<>(outline.size());
        for (double[] p : outline) {
            double px = p[0] * feature.stretch().x(), py = p[1] * feature.stretch().y();
            points.add(new ConcreteFit.Point((float) (x + scale * (c * px - s * py)), (float) (y + scale * (s * px + c * py))));
        }
        return points;
    }

    /** A road's end onto a bridge deck, which keeps its hex so the deck's footing stays where it stands. */
    private static boolean bridgeEnd(BoardScene scene, BoardScene.Tile tile) {
        for (int d = 0; d < 6; d++) {
            BoardScene.Tile next = scene.neighbor(tile, d);
            if (next != null && BoardSurface.connectingBridge(tile, next, d) != null) { return true; }
        }
        return false;
    }

    /** The placed objects that stand inside a wall or bank of their Fitted hex, in board order. */
    List<Cut> cuts() { return cuts; }

    private static ConcreteFit fit(BoardScene scene, List<ConcreteFit.Cell> cells, Set<Coords> pins,
          Map<Coords, List<ConcreteFit.Point>> standing, Set<Long> held) {
        return new ConcreteFit(scene.width(), scene.height(), cells, BoardGeometry.hexScale(), true, pins, standing, held);
    }

    /**
     * The lattice corners this fit put in the course of a way that does not run on this pavement (a road, a rail or
     * a bridge deck beside or below it: #ways, #inWay). In the next fit each goes back on its hex and its run stays as
     * fitted. A way that runs on the pavement moves with it: that is what paving under a road means.
     */
    private static Set<Long> inWays(BoardScene scene, ConcreteFit fit, Set<Long> held) {
        Set<Long> result = new HashSet<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (tile.surface() != BoardScene.Surface.CONCRETE || tile.liquid().present()) { continue; }
            List<ConcreteFit.Shift> shifts = fit.corners(tile.coords());
            List<BoardRoad> ways = null;
            for (int k = 0; k < 6; k++) {
                ConcreteFit.Shift shift = shifts.get(k);
                long key = fit.cornerKey(tile.coords(), k);
                if (shift.x() == 0 && shift.y() == 0 || held.contains(key)) { continue; }
                if (ways == null) { ways = ways(scene, tile); }
                Vector3 corner = BoardGeometry.corner(tile.coords(), 0, k);
                if (inWay(ways, corner.x + shift.x(), corner.y + shift.y())) { result.add(key); }
            }
        }
        return result;
    }

    /**
     * The courses of the roads and rails round a paved hex that do not run on its pavement (on other ground, or on
     * pavement at another level), each the widest its exits allow (BoardRoad#clearance): from the board alone, since a
     * road's drawn course reads the fitted pavement, which is being made here.
     */
    private static List<BoardRoad> ways(BoardScene scene, BoardScene.Tile tile) {
        List<BoardRoad> result = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile source = scene.neighbor(tile, direction);
            if (source == null || !BoardRoad.carriageway(source)) { continue; }
            boolean own = source.surface() == BoardScene.Surface.CONCRETE && !source.liquid().present()
                  && source.groundLevel() == tile.groundLevel();
            if (!own) { result.add(BoardRoad.clearance(source.coords(), source.wayExits())); }
        }
        return result;
    }

    /**
     * The courses of the carriageways on a hex and round it as the board alone gives them: a road's from its kind, its
     * exits and its neighbours' kinds, a railed hex's as BoardRoad#way. A road's drawn course (BoardRoad#of) reads the
     * fitted pavement, which the fit cannot ask while it is being made; an extension asks the same course after it.
     */
    private List<BoardRoad> courses(BoardScene scene, BoardScene.Tile tile) {
        List<BoardRoad> result = new ArrayList<>();
        for (int direction = -1; direction < 6; direction++) {
            BoardScene.Tile source = direction < 0 ? tile : scene.neighbor(tile, direction);
            if (source == null || !BoardRoad.carriageway(source)) { continue; }
            result.add(courses.computeIfAbsent(source.coords(), at -> source.railExits() != 0 ? BoardRoad.way(scene, source)
                  : BoardRoad.layout(at, source.roadExits(), source.road(), toward -> {
                      BoardScene.Tile next = scene.neighbor(source, toward);
                      return next != null && (next.roadExits() & 1 << (toward + 3) % 6) != 0 ? next.road() : BoardRoad.Kind.NONE;
                  })));
        }
        return result;
    }

    /** Whether a point stands in the course of one of {@code ways} (#ways, #courses), its shoulder included. */
    private static boolean inWay(List<BoardRoad> ways, float x, float y) {
        return BoardRoad.distance(ways, x, y) < BoardRoad.SHOULDER;
    }

    /**
     * What the fit reads of a tile: a hex whose Edges is Hex keeps its outline, and so does a road's end onto a bridge
     * deck. A kit building pins nothing: where it stands past a fitted side, that side gets more quay (#extensions). A
     * building, industry or tank whose outline its kit's data does not give (a modelled kit) keeps its hex as before.
     */
    private static ConcreteFit.Cell cell(BoardScene scene, BoardScene.Tile tile) {
        boolean fixed = edges(tile) == HexAppearance.Edges.HEX || bridgeEnd(scene, tile) || tile.features().stream()
              .anyMatch(feature -> fitFeature(feature) && feature.kind() != BoardScene.FeatureKind.PROP
                    && feature.decoration() == null && kit(scene, tile, feature) == null);
        return new ConcreteFit.Cell(tile.coords(), tile.elevation(), tile.surface() == BoardScene.Surface.CONCRETE,
              tile.liquid().present(), tile.liquid().molten(), tile.frozen(), tile.ultraSublevel(), fixed, tile.wayExits(),
              BoardSurface.ramps(scene, tile), tile.pavementSides());
    }

    /**
     * The features the fit reads, the one predicate {@code GpuTerrain#concreteInputsChanged} compares too: structures
     * (buildings, industrial, bridges, fuel tanks), which keep their hex's outline, and placed objects, which keep it
     * only where the fit moves a wall or bank into them ({@link #reached}).
     */
    static boolean fitFeature(BoardScene.Feature feature) {
        return feature.kind() == BoardScene.FeatureKind.BUILDING || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL
              || feature.kind() == BoardScene.FeatureKind.FUEL_TANK || feature.kind() == BoardScene.FeatureKind.PROP;
    }

    /**
     * The hexes, not yet pinned and not Fitted, holding a placed object that a wall or bank this fit moved reaches
     * ({@link ConcreteFit#reaches}). An object whose footprint is unknown keeps its hex, as a structure does.
     */
    private static Set<Coords> reached(BoardScene scene, ConcreteFit fit, Set<Coords> pins) {
        Set<Coords> result = new HashSet<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            // Only a hex the fit moved has a footprint of its own.
            if (fit.footprint(tile.coords()) == null || pins.contains(tile.coords())
                  || edges(tile) == HexAppearance.Edges.FITTED) { continue; }
            for (BoardScene.Feature feature : tile.features()) {
                // A structure stands on more quay where it needs it (#extensions); a placed prop pins its hex where it is cut.
                if (feature.decoration() == null || feature.kind() != BoardScene.FeatureKind.PROP) { continue; }
                List<ConcreteFit.Point> footprint = footprint(scene, tile, feature);
                if (footprint == null || reaches(scene, fit, tile, feature, footprint)) { result.add(tile.coords()); break; }
            }
        }
        return result;
    }

    /** Ground rails cross their graded openings; their side walls still have to clear the track. */
    private static boolean reaches(BoardScene scene, ConcreteFit fit, BoardScene.Tile tile,
          BoardScene.Feature feature, List<ConcreteFit.Point> footprint) {
        int openings = BoardRail.route(feature) && RailRoute.kind(feature.decoration()) == RailRoute.Kind.RAIL
              && !RailRoute.onDeck(feature.decoration()) && BoardRail.level(tile, feature) == tile.elevation()
              ? BoardSurface.ramps(scene, tile) & BoardRail.drawnSides(tile, feature) : 0;
        return fit.reaches(footprint, tile.coords(), openings);
    }

    /** A placed object's footprint on the board plane: the hull of {@link BoardObstacles#outline}; null when unknown. */
    private static List<ConcreteFit.Point> footprint(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        List<Vector3> object = BoardObstacles.outline(scene, tile, feature);
        if (object == null) { return null; }
        List<ConcreteFit.Point> points = new ArrayList<>(object.size());
        for (Vector3 p : object) { points.add(new ConcreteFit.Point(p.x, p.y)); }
        return ConcreteFit.hull(points);
    }

    static boolean concreteBank(BoardScene scene, BoardScene.Tile water, int edge) {
        BoardScene.Tile land = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(edge)));
        return land != null && land.surface() == BoardScene.Surface.CONCRETE && !land.liquid().present()
              && land.elevation() >= water.elevation();
    }
}
