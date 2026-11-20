/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.board.Coords;

/**
 * The water's surface on the height field ({@link TerrainField}): a layer over the ground, not a shape cut to its
 * banks. Each water hex the field takes carries one level plane: its own hex, out to a wall's row where the field
 * moved one ({@link FieldBoard#edgeMove}), and the margin of the land round it where the bank dips below the
 * waterline ({@link FieldShore#bed}); the ground hides the plane wherever it stands above it. The plane follows from
 * the board alone, so it is the same at every detail, and a piece of margin belongs to one water hex only.
 */
final class WaterLayer {
    private WaterLayer() { }

    /** How far into a land hex, from its edge toward its centre, the plane of the water beside it reaches. */
    private static final float MARGIN = .5f;

    /** How far beyond its hex a plane reaches. */
    static float reach() { return MARGIN * BoardGeometry.width() / 2; }

    /**
     * The plane of water hex h as triangles, x and y per corner, counter-clockwise: the hex as a fan round its centre
     * to its moved outline, and of every land neighbour the margin along the shared edge. At a corner whose other two hexes are land, or
     * land and water the field does not take, it also has the land's margin along the edge between those two, up to
     * the edge's middle; where both of a corner's other hexes are land, each gives its half. Points along an edge two
     * planes share are placed alike from either side ({@link #along}).
     */
    static float[] plane(FieldBoard board, Coords h) {
        int n = FieldBoard.FULL;
        float[] out = new float[6 * (6 * 3 * n + 6 * 4)];
        int at = 0;
        float cx = board.centerX(h), cy = board.centerY(h);
        float[] p = new float[2], q = new float[2];
        for (int e = 0; e < 6; e++) {
            float ax = board.cornerX(h, e), ay = board.cornerY(h, e), bx = board.cornerX(h, e + 1), by = board.cornerY(h, e + 1);
            Coords next = h.translated(BoardGeometry.edgeDirection(e)), before = h.translated(BoardGeometry.edgeDirection(e + 5));
            boolean land = bank(board, h, next);
            float[][] move = board.edgeMove(h, e);
            float mx = ax + move[0][0], my = ay + move[0][1];
            // The margin's inner line: from each end of the edge toward the land's centre.
            float nx = board.centerX(next), ny = board.centerY(next);
            float iax = ax + (nx - ax) * MARGIN, iay = ay + (ny - ay) * MARGIN, ibx = bx + (nx - bx) * MARGIN, iby = by + (ny - by) * MARGIN;
            for (int j = 0; j < n; j++) {
                along(ax, ay, bx, by, j, n, p);
                along(ax, ay, bx, by, j + 1, n, q);
                p[0] += move[j][0];
                p[1] += move[j][1];
                q[0] += move[j + 1][0];
                q[1] += move[j + 1][1];
                at = triangle(out, at, cx, cy, p[0], p[1], q[0], q[1]);
                if (!land) { continue; }
                float t0 = j / (float) n, t1 = (j + 1) / (float) n;
                float rx = iax + (ibx - iax) * t0, ry = iay + (iby - iay) * t0, sx = iax + (ibx - iax) * t1, sy = iay + (iby - iay) * t1;
                at = triangle(out, at, q[0], q[1], p[0], p[1], rx, ry);
                at = triangle(out, at, q[0], q[1], rx, ry, sx, sy);
            }
            // Corner e, shared with the hexes across the edge before and across this one.
            if (land && !water(board, before)) { at = corner(out, at, board, next, before, mx, my); }
            if (bank(board, h, before) && !water(board, next)) { at = corner(out, at, board, before, next, mx, my); }
        }
        return java.util.Arrays.copyOf(out, at);
    }

    static boolean water(FieldBoard board, Coords c) { return board.taken(c) && board.shore.family(c) >= 0; }

    /**
     * How far the ground of land hex {@code land}, {@code z} high at (x, y), may lie below its height (the bed the shore
     * gives it may take): any depth well inside the plane of the water beside it, down to just above that water's
     * surface where no plane reaches (so no dry hollow lies below the waterline), eased between the two.
     */
    static float dip(FieldBoard board, Coords land, float x, float y, float z) {
        float top = Float.NEGATIVE_INFINITY;
        for (int d = 0; d < 6; d++) {
            Coords next = land.translated(d);
            if (water(board, next)) { top = Math.max(top, board.level(next) * BoardGeometry.level() - BoardGeometry.hexScale()); }
        }
        if (top == Float.NEGATIVE_INFINITY) { return Float.POSITIVE_INFINITY; }
        float covered = covered(board, land, x, y), floor = Math.max(0, z - top - BoardRelief.metres(.1f));
        return covered >= 1 ? Float.POSITIVE_INFINITY : floor + covered / (1 - covered) * BoardGeometry.level();
    }

    /**
     * How far the planes of the water beside land hex {@code land} cover (x, y) in it, 0 to 1: their margins as
     * {@link #plane} lays them (along an edge to a water hex whose plane reaches over this land, and from a corner with
     * such a hex to the middle of the edge to land), 1 well inside, falling to 0 at their inner line and, for a
     * corner's piece, at the edge's middle.
     */
    private static float covered(FieldBoard board, Coords land, float x, float y) {
        float cx = board.centerX(land), cy = board.centerY(land), ease = .15f;
        for (int e = 0; e < 6; e++) {
            float ax = board.cornerX(land, e), ay = board.cornerY(land, e), bx = board.cornerX(land, e + 1), by = board.cornerY(land, e + 1);
            float det = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            float u = ((x - ax) * (cy - ay) - (y - ay) * (cx - ax)) / det, w = ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) / det;
            float v = 1 - u - w;
            if (u < -1e-4f || w < -1e-4f || v < -1e-4f) { continue; }
            float inner = BoardRelief.smooth((MARGIN - w) / ease);
            Coords next = land.translated(BoardGeometry.edgeDirection(e));
            if (water(board, next)) { return bank(board, next, land) ? inner : 0; }
            // A corner's piece: from the corner this point is nearer to, up to the edge's middle.
            Coords other = land.translated(BoardGeometry.edgeDirection(v >= u ? e + 5 : e + 1));
            return water(board, other) && bank(board, other, land) ? inner * BoardRelief.smooth(Math.abs(v - u) / ease) : 0;
        }
        return 0;
    }

    /**
     * Whether water hex h's plane reaches over its neighbour: land that slopes into the water. A pool ends on its sill
     * above lower land, and at a cliff's foot on the wall's row.
     */
    static boolean bank(FieldBoard board, Coords h, Coords next) {
        return board.shore.land(next) && board.level(next) >= board.level(h) && !board.cliff(h, next);
    }

    /**
     * The hex whose ground lies at (x, y), a point of hex h's own outline: h, or the lower neighbour where the field
     * moved a wall's row into h and the point lies beyond it.
     */
    static Coords owner(FieldBoard board, Coords h, float x, float y) {
        float px = x - board.centerX(h), py = y - board.centerY(h);
        for (int e = 0; e < 6; e++) {
            Coords next = h.translated(BoardGeometry.edgeDirection(e));
            if (!board.taken(next) || board.level(next) >= board.level(h) || !board.cliff(h, next)) { continue; }
            float ax = board.cornerX(h, e) - board.centerX(h), ay = board.cornerY(h, e) - board.centerY(h);
            float bx = board.cornerX(h, e + 1) - board.centerX(h), by = board.cornerY(h, e + 1) - board.centerY(h);
            float[][] move = board.edgeMove(h, e);
            for (int j = 0; j < FieldBoard.FULL; j++) {
                float t0 = j / (float) FieldBoard.FULL, t1 = (j + 1) / (float) FieldBoard.FULL;
                float x0 = ax + (bx - ax) * t0 + move[j][0], y0 = ay + (by - ay) * t0 + move[j][1];
                float x1 = ax + (bx - ax) * t1 + move[j + 1][0], y1 = ay + (by - ay) * t1 + move[j + 1][1];
                // Between the two spokes, and beyond the row's piece from the centre.
                float turn = Math.signum(x0 * y1 - y0 * x1);
                if ((x0 * py - y0 * px) * turn >= 0 && (px * y1 - py * x1) * turn >= 0
                      && ((x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)) * turn < 0) { return next; }
            }
        }
        return h;
    }

    /** Land hex {@code land}'s margin along its edge toward {@code other}, from their corner (x, y) to the edge's middle. */
    private static int corner(float[] out, int at, FieldBoard board, Coords land, Coords other, float x, float y) {
        int e = FieldBoard.edgeIndex(land, other);
        float ax = board.cornerX(land, e), ay = board.cornerY(land, e), bx = board.cornerX(land, e + 1), by = board.cornerY(land, e + 1);
        float mx = (ax + bx) / 2, my = (ay + by) / 2, lx = board.centerX(land), ly = board.centerY(land);
        float ix = x + (lx - x) * MARGIN, iy = y + (ly - y) * MARGIN, jx = mx + (lx - mx) * MARGIN, jy = my + (ly - my) * MARGIN;
        at = triangle(out, at, x, y, mx, my, jx, jy);
        return triangle(out, at, x, y, jx, jy, ix, iy);
    }

    /** Point j of n along the edge from a to b, computed from the edge's first end in coordinate order whichever way it is asked. */
    static void along(float ax, float ay, float bx, float by, int j, int n, float[] out) {
        boolean ordered = ax < bx || ax == bx && ay <= by;
        float t = (ordered ? j : n - j) / (float) n;
        float fx = ordered ? ax : bx, fy = ordered ? ay : by, tx = ordered ? bx : ax, ty = ordered ? by : ay;
        out[0] = j == 0 ? ax : j == n ? bx : fx + (tx - fx) * t;
        out[1] = j == 0 ? ay : j == n ? by : fy + (ty - fy) * t;
    }

    /** Appends a triangle, turned counter-clockwise. */
    private static int triangle(float[] out, int at, float ax, float ay, float bx, float by, float cx, float cy) {
        boolean turn = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) < 0;
        out[at++] = ax;
        out[at++] = ay;
        out[at++] = turn ? cx : bx;
        out[at++] = turn ? cy : by;
        out[at++] = turn ? bx : cx;
        out[at++] = turn ? by : cy;
        return at;
    }
}
