/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/**
 * Level water on the height field ({@link TerrainField}) for one scene revision: the water hexes the shore operator
 * shapes, and the bed it lowers the field's ground by. A shaped water hex stands in the field at its surface's level,
 * so its shore is no step; the bed is a function of the world position alone (today's waterline of each water hex,
 * BoardSurface's, and the depths of the water hexes round the point), so every hex, chunk and detail gives a point one
 * height, and land beside water dips below the waterline where today's shore runs into it. Where a cliff stands in water (today's
 * tuning, off by default: a cliff beside water then keeps its talus bank, with today's rocks on it) the field gives
 * its foot no talus and the bed its full depth ({@link #wet}), and a pool above lower ground or water keeps a
 * sill along its rim. Owned by its {@link FieldBoard}; filled on demand and shared by the chunk workers, a value
 * computed twice being the same value.
 */
final class FieldShore {
    /** The waterline distance's grid: its spacing in metres, and the nodes along a page's side. */
    private static final float GRID = 2;
    private static final int PAGE = 16;
    /** The bed's fall from the bank to its depth, in tile pixels from today's waterline. */
    private static final float RUN = 22;
    /** How far from today's waterline its distance is measured, in tile pixels; beyond it the distance is clamped. */
    private static final float REACH = 40;
    /** The least kernel weight a point's depth is divided by: the depth fades out with the last water hex in reach. */
    private static final float LEAST = .02f;
    /** How much of its depth the bed's swells (today's, BoardSurface#meander) deepen or lift it away from a hex's centre. */
    private static final float SWELL = .25f;
    /**
     * Every shore's margin: within MARGIN metres inside the waterline the bed falls gently, to MARGIN_DROP metres under
     * the water (a quarter of the water's depth where that is less), and the bank's steep fall starts only there, where the lattice's steps lie under the surface; above
     * the waterline the bank keeps at least as wide a gentle strip.
     */
    private static final float MARGIN = 1.5f, MARGIN_DROP = .4f;
    /**
     * A cliff's foot stands in water where the water hexes' kernel reaches WET (a lone water hex's own edges and as
     * far beyond them as the rim move carries a cliff's row, FieldBoard's MAX_MOVE) and on dry ground below DRY.
     */
    private static final float WET = .07f, DRY = .03f;
    /**
     * Beside a hex below the cliff that is no shaped water (dry ground, or water today's code still draws) the foot
     * keeps the shore's own bank, as that hex has it: dry while the shaped water's share of the two kernels is a half
     * or less (the edge between the two hexes and its line on into the cliff), wet from OPEN up, some 12 m along the
     * foot (the bank's own run).
     */
    private static final float OPEN = .95f;

    /**
     * One board column's hexes by row: the bed's depth below the surface's level (0 where not shaped), level, family,
     * and the ground's level where the hex is no shaped water.
     */
    private record Column(float[] depth, int[] level, int[] family, int[] land) { }

    /**
     * Today's waterline of a water hex (BoardSurface#waterGeometry), x and y a point; none for a dry hex. Its
     * {@code banks}: the stretches that face land (x0, y0, x1, y1 a segment); its {@code mouths}: those that face
     * the water of the hex beside it, inside the water on both sides.
     */
    private record Line(float[] ring, float[] banks, float[] mouths) { }

    private static final Line DRY_HEX = new Line(new float[0], new float[0], new float[0]);

    /** A water hex's sampled bed (today's BED faces, indexed), or none (null). */
    private record Bed(GpuBiomeVegetation.Support index) { }

    private static final Bed NO_BED = new Bed(null);

    private final BoardScene scene;
    private final int cols, rows;
    private final float width, height, levelZ, scale, step, reach;
    /** Today's rule for a natural cliff beside water (BoardRelief#wetCliff): only this tuning stands it in the water. */
    private final boolean cliffsIntoWater;
    private final AtomicReferenceArray<Column> columns;
    /** The waterline distance's nodes on one board-wide grid from the world's origin, by page. */
    private final Map<Long, float[]> pages = new ConcurrentHashMap<>();
    /** Today's waterline of each hex asked for, as a ring; its banks and mouths, which need its neighbours' rings. */
    private final Map<Coords, float[]> rings = new ConcurrentHashMap<>();
    /**
     * Today's bed of each water hex whose ring was asked for and whose bed the field samples (#bed): shallow (depth 0)
     * level water and descending (graded) water at any depth, its BED faces indexed; NO_BED for the others.
     */
    private final Map<Coords, Bed> beds = new ConcurrentHashMap<>();
    /** Today's surface (BoardSurface) of each descending (graded) water hex whose ring was asked for: its graded water. */
    private final Map<Coords, BoardSurface> grades = new ConcurrentHashMap<>();
    private final Map<Coords, Line> lines = new ConcurrentHashMap<>();
    /**
     * Each shaped water hex's edges to water the operator does not shape at its surface (#seam): x0, y0, x1, y1 and
     * that hex's column and row, per edge; empty for none.
     */
    private final Map<Coords, float[]> seams = new ConcurrentHashMap<>();
    /** Today's ground faces (BoardSurface's) of the unshaped water hexes across those edges. */
    private final Map<Coords, List<BoardSurface.Face>> todays = new ConcurrentHashMap<>();

    FieldShore(BoardScene scene) {
        this.scene = scene;
        cols = scene.width();
        rows = scene.height();
        width = BoardGeometry.width();
        height = BoardGeometry.height();
        levelZ = BoardGeometry.level();
        scale = BoardGeometry.hexScale();
        step = BoardRelief.metres(GRID);
        reach = width;
        cliffsIntoWater = BoardRelief.tuning().cliffsIntoWater();
        columns = new AtomicReferenceArray<>(cols);
    }

    /**
     * The bed's family (a {@link BoardScene.Surface} ordinal) where the operator shapes a hex's water, which the field
     * then takes, else -1: level water on natural ground, unfrozen, without a road, ford or ramp (today's geometry
     * until the roads' corridor is tried under water), its land neighbours all the field's. A bridge's deck stands
     * over the water as over today's.
     */
    private int family(BoardScene.Tile t) {
        if (!t.liquid().present() || t.liquid().molten() || t.frozen() || t.ultraSublevel() || t.backdrop().present()
              || BoardFeatures.shaft(t) || t.wayExits() != 0 || t.road() != BoardRoad.Kind.NONE
              || BoardSurface.ramps(scene, t) != 0) { return -1; }
        for (int d = 0; d < 6; d++) {
            BoardScene.Tile next = scene.tile(t.coords().translated(d));
            if (next != null && !next.liquid().present() && !FieldBoard.dryNatural(next)) { return -1; }
        }
        int family = ground(scene, t, new BoardRelief(scene, t, 0).family());
        return family == BoardScene.Surface.CONCRETE.ordinal() ? -1 : family;
    }

    /**
     * The ground family of water hex t, whose bed's is {@code today} (BoardRelief's: the commonest of its dry neighbours',
     * so a plain water hex on a desert map runs between sand): that one, but a tie goes to the hex's own ground, so a
     * stream through grassland keeps its grass beside a desert cliff. The field's shapes and its materials read this.
     */
    static int ground(BoardScene scene, BoardScene.Tile t, int today) {
        int[] counts = new int[BoardScene.Surface.values().length];
        for (int d = 0; d < 6; d++) {
            BoardScene.Tile next = scene.tile(t.coords().translated(d));
            if (next != null && !next.liquid().present() && !next.backdrop().present()) { counts[next.surface().ordinal()]++; }
        }
        int own = t.surface().ordinal();
        return counts[own] > 0 && counts[own] == counts[today] ? own : today;
    }

    private Column column(int c) {
        Column column = columns.get(c);
        if (column == null) {
            float[] depth = new float[rows];
            int[] level = new int[rows], family = new int[rows], land = new int[rows];
            for (int r = 0; r < rows; r++) {
                BoardScene.Tile t = scene.tile(new Coords(c, r));
                family[r] = family(t);
                if (family[r] >= 0) {
                    depth[r] = t.groundLevel() * levelZ - BoardGeometry.groundZ(t);
                    level[r] = t.groundLevel();
                }
                land[r] = family[r] >= 0 ? Integer.MAX_VALUE : t.groundLevel();
            }
            column = new Column(depth, level, family, land);
            columns.set(c, column);
        }
        return column;
    }

    /** The bed's family of a board hex whose water the operator shapes (a hex the field takes), else -1. */
    int family(Coords h) { return column(h.getX()).family[h.getY()]; }

    /** Whether a hex is on the board and dry: land whose bank the water beside it may reach into. */
    boolean land(Coords h) {
        BoardScene.Tile t = scene.tile(h);
        return t != null && !t.liquid().present();
    }

    /**
     * The shaped hexes below level {@code below} round (x, y) under a kernel on their centres (HexTile's): the sum of
     * their weights, of their depths by their weights, and of the weights of the other hexes below that level; zeros
     * beyond the reach of any.
     */
    private float[] kernel(float x, float y, int below) {
        float across = width * .75f, inverse = 1 / (reach * reach), sum = 0, weight = 0, dry = 0;
        int near = Math.round((x - width / 2) / across);
        for (int c = Math.max(0, near - 2); c <= Math.min(cols - 1, near + 2); c++) {
            Column column = column(c);
            float dx = x - (c * across + width / 2), odd = (c & 1) * height / 2 + height / 2;
            int row = Math.round((-y - odd) / height);
            for (int r = Math.max(0, row - 2); r <= Math.min(rows - 1, row + 2); r++) {
                float dy = y + r * height + odd, q = 1 - (dx * dx + dy * dy) * inverse;
                if (q > 0 && column.land[r] < below) { dry += q * q * q; }
                if (q <= 0 || column.depth[r] == 0 || column.level[r] >= below) { continue; }
                sum += q * q * q * column.depth[r];
                weight += q * q * q;
            }
        }
        return new float[] { weight, sum, dry };
    }

    /**
     * How far the foot of a step up to level {@code top} stands in water at (x, y), 0 (dry ground, and everywhere
     * unless today's tuning lets cliffs stand in water) to 1: the kernel of
     * the water hexes below that level, by their share against the other hexes below it, so the wet foot fades out
     * along the wall toward such a hex and is dry on the edge they share.
     */
    float wet(float x, float y, int top) {
        if (!cliffsIntoWater) { return 0; }
        float[] kernel = kernel(x, y, top);
        if (kernel[0] <= DRY) { return 0; }
        float t = Math.min((kernel[0] - DRY) / (WET - DRY), 1);
        float u = Math.clamp((kernel[0] / (kernel[0] + kernel[2]) - .5f) / (OPEN - .5f), 0, 1);
        return t * t * (3 - 2 * t) * u * u * (3 - 2 * u);
    }

    /**
     * How steeply the bed at (x, y) falls under its banks: today's rule (BoardSurface#bankSteep) for each land hex in
     * reach, from how many levels it rises above the nearest shaped water's surface and the water's depth in levels,
     * weighed under the kernel as the depths are.
     */
    private float steep(float x, float y, float waterDepth) {
        float across = width * .75f, inverse = 1 / (reach * reach), nearest = 0, sum = 0, weight = 0;
        int near = Math.round((x - width / 2) / across), water = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int c = Math.max(0, near - 2); c <= Math.min(cols - 1, near + 2); c++) {
                Column column = column(c);
                float dx = x - (c * across + width / 2), odd = (c & 1) * height / 2 + height / 2;
                int row = Math.round((-y - odd) / height);
                for (int r = Math.max(0, row - 2); r <= Math.min(rows - 1, row + 2); r++) {
                    float dy = y + r * height + odd, q = 1 - (dx * dx + dy * dy) * inverse;
                    if (q <= 0) { continue; }
                    if (pass == 0 && column.depth[r] > 0 && q > nearest) {
                        nearest = q;
                        water = column.level[r];
                    } else if (pass == 1 && column.land[r] != Integer.MAX_VALUE) {
                        sum += q * q * q * BoardSurface.bankSteep(column.land[r] - water, waterDepth);
                        weight += q * q * q;
                    }
                }
            }
        }
        return weight == 0 ? BoardSurface.bankSteep(Float.NaN, waterDepth) : sum / weight;
    }

    /**
     * How far the higher side of a step up to level {@code top} at (x, y) is shaped water, 0 to 1: the kernel share of
     * the water hexes at that level or above among all hexes there. Below such a step the water pours in: no shelf.
     */
    float upper(float x, float y, int top) {
        float across = width * .75f, inverse = 1 / (reach * reach), water = 0, land = 0;
        int near = Math.round((x - width / 2) / across);
        for (int c = Math.max(0, near - 2); c <= Math.min(cols - 1, near + 2); c++) {
            Column column = column(c);
            float dx = x - (c * across + width / 2), odd = (c & 1) * height / 2 + height / 2;
            int row = Math.round((-y - odd) / height);
            for (int r = Math.max(0, row - 2); r <= Math.min(rows - 1, row + 2); r++) {
                float dy = y + r * height + odd, q = 1 - (dx * dx + dy * dy) * inverse;
                if (q <= 0) { continue; }
                if (column.depth[r] > 0 && column.level[r] >= top) { water += q * q * q; }
                else if (column.depth[r] == 0 && column.land[r] >= top) { land += q * q * q; }
            }
        }
        return water == 0 ? 0 : water / (water + land);
    }

    /**
     * The water's surface at (x, y), a point inside today's waterline: its hex's water, or where the line runs into a
     * dry hex the highest water beside it; NaN where none.
     */
    float surface(float x, float y) { return surface(x, y, true); }

    /** {@link #surface(float, float)}, a descending stream's graded surface when {@code graded}, else every water's level. */
    float surface(float x, float y, boolean graded) {
        Coords at = GpuStarShells.hexAt(x, y);
        BoardScene.Tile tile = at.getX() < 0 || at.getY() < 0 || at.getX() >= cols || at.getY() >= rows ? null : scene.tile(at);
        if (tile != null && tile.liquid().present() && !tile.liquid().molten()) {
            return graded ? water(at, x, y) : BoardGeometry.waterZ(tile);
        }
        float top = Float.NaN;
        for (int d = 0; d < 6; d++) {
            Coords next = at.translated(d);
            BoardScene.Tile water = next.getX() < 0 || next.getY() < 0 || next.getX() >= cols || next.getY() >= rows ? null
                  : scene.tile(next);
            if (water != null && water.liquid().present() && !water.liquid().molten()) {
                float z = graded ? water(next, x, y) : BoardGeometry.waterZ(water);
                top = Float.isNaN(top) ? z : Math.max(top, z);
            }
        }
        return top;
    }

    /**
     * Today's water surface of water hex h (on the board, not molten) at (x, y): a descending stream's graded surface
     * (BoardSurface#waterHeight, which carries the nearest shore's height past its mesh), else the level surface. Where
     * the point lies in another water hex, that hex's: one function of the position, so the planes of two hexes meet, and a
     * hex whose outline the field moved past its neighbour's reads the water today draws there; a pool across a fall
     * keeps its own.
     */
    float water(Coords h, float x, float y) {
        Coords at = GpuStarShells.hexAt(x, y);
        // A point that is no place (hexAt has no hex for a coordinate that is not finite) keeps h's own water.
        BoardScene.Tile there = at == null || at.getX() < 0 || at.getY() < 0 || at.getX() >= cols || at.getY() >= rows ? null
              : scene.tile(at);
        // Only water joined to h within two levels (one sloping surface, BoardSurface#waterSlope): across a fall each
        // plane keeps its own, or the lower pool's plane, carried past the wall's row, would climb to the pool above.
        if (there != null && there.liquid().present() && !there.liquid().molten()
              && (at.equals(h) || BoardSurface.waterSlope(scene.tile(h), there))) { h = at; }
        ring(h);
        BoardSurface graded = grades.get(h);
        return graded == null ? BoardGeometry.waterZ(scene.tile(h)) : graded.waterHeight(x, y);
    }

    /** Whether water hex h's surface descends (today's graded water), which the field's plane then follows. */
    boolean graded(Coords h) {
        ring(h);
        return grades.containsKey(h);
    }

    /** The shallow margin's depth under the water at (x, y), {@code inside} tile pixels inside today's waterline, world units. */
    float shallow(float x, float y, float inside) {
        float[] kernel = kernel(x, y, Integer.MAX_VALUE);
        return drop(kernel[1] / Math.max(kernel[0], LEAST)) * Math.clamp(inside / (BoardRelief.metres(MARGIN) / scale), 0, 1);
    }

    /** The margin's drop under water whose bed lies {@code depth} below its steps' height, world units. */
    private float drop(float depth) {
        return Math.clamp(.25f * (depth - scale), 0, BoardRelief.metres(MARGIN_DROP));
    }

    /**
     * The ground at (x, y), {@code z} high: in a shallow (depth 0) hex of level water, a descending stream's hex at
     * any depth, or a pool a fall pours into (its plunge scour), today's own bed where it has one there (BoardSurface's mesh inside today's waterline, sampled: its
     * rims, rings, bars and plateau, under its graded water), right up to the line, so a pass a few metres wide keeps
     * today's depth and a stream runs down as today's; else z.
     */
    float shallows(float x, float y, float z) {
        GpuBiomeVegetation.Support bed = bed(GpuStarShells.hexAt(x, y));
        float today = bed == null ? Float.NaN : bed.height(x, y);
        return Float.isNaN(today) ? z : today;
    }

    /**
     * The ground at (x, y), {@code z} high, beside an edge between a shaped water hex and water the operator does not
     * shape at the same surface (water beside paving, which today's code still draws): today's ground of that hex
     * across the edge, taken over twice the shallow margin's width, so the field's bed meets today's along the edge
     * with no step (the field counts unshaped water at its floor, shaped water at its surface); else z. In dry hexes z.
     */
    float seam(float x, float y, float z) {
        Coords at = GpuStarShells.hexAt(x, y);
        BoardScene.Tile here = scene.tile(at);
        if (here == null || !here.liquid().present()) { return z; }
        float radius = 2 * BoardRelief.metres(MARGIN), weight = 0, sum = 0, total = 0;
        for (int d = -1; d < 6; d++) {
            Coords a = d < 0 ? at : at.translated(d);
            if (a.getX() < 0 || a.getY() < 0 || a.getX() >= cols || a.getY() >= rows || family(a) < 0) { continue; }
            float[] edges = seams.computeIfAbsent(a, this::seamEdges);
            for (int k = 0; k < edges.length; k += 6) {
                float ax = edges[k], ay = edges[k + 1], ex = edges[k + 2] - ax, ey = edges[k + 3] - ay;
                float len = ex * ex + ey * ey, t = len == 0 ? 0 : Math.clamp(((x - ax) * ex + (y - ay) * ey) / len, 0, 1);
                float px = ax + t * ex, py = ay + t * ey, distance = (float) Math.hypot(x - px, y - py);
                if (distance >= radius) { continue; }
                // Today's ground of the other hex just across the edge (a centimetre into it).
                Coords other = new Coords((int) edges[k + 4], (int) edges[k + 5]);
                float cx = BoardGeometry.centerX(other) - px, cy = BoardGeometry.centerY(other) - py, c = (float) Math.hypot(cx, cy);
                float nudge = BoardRelief.metres(.01f) / Math.max(c, 1e-6f);
                float today = BoardSurface.sampleHeight(todays.computeIfAbsent(other, this::todayGround), px + cx * nudge,
                      py + cy * nudge, Float.NaN);
                if (Float.isNaN(today)) { continue; }
                // The nearest edge rules on it, so the edges' samples are today's own; between edges the shares blend.
                float w = BoardRelief.smooth(1 - distance / radius), share = w / Math.max(distance * distance, 1e-6f);
                weight = Math.max(weight, w);
                sum += share * today;
                total += share;
            }
        }
        return total == 0 ? z : BoardRelief.lerp(z, sum / total, weight);
    }

    /** Shaped water hex a's edges to unshaped water at its surface (#seams). */
    private float[] seamEdges(Coords a) {
        float surface = BoardGeometry.waterZ(scene.tile(a));
        float[] edges = new float[0];
        for (int e = 0; e < 6; e++) {
            Coords g = a.translated(BoardGeometry.edgeDirection(e));
            BoardScene.Tile next = g.getX() < 0 || g.getY() < 0 || g.getX() >= cols || g.getY() >= rows ? null : scene.tile(g);
            if (next == null || !next.liquid().present() || next.liquid().molten() || next.frozen() || family(g) >= 0
                  || Math.abs(BoardGeometry.waterZ(next) - surface) > .001f * scale) { continue; }
            Vector3 c0 = BoardGeometry.corner(a, 0, e), c1 = BoardGeometry.corner(a, 0, (e + 1) % 6);
            edges = Arrays.copyOf(edges, edges.length + 6);
            System.arraycopy(new float[] { c0.x, c0.y, c1.x, c1.y, g.getX(), g.getY() }, 0, edges, edges.length - 6, 6);
        }
        return edges;
    }

    /** Today's ground of water hex h (BoardSurface's faces at full detail, its water's surface not among them). */
    private List<BoardSurface.Face> todayGround(Coords h) {
        return new BoardSurface(scene, scene.tile(h), TerrainLod.FULL).waterGeometry().faces();
    }

    /** Today's bed of water hex h the field shapes where it samples it (#beds), indexed; null for any other hex. */
    private GpuBiomeVegetation.Support bed(Coords h) {
        if (h.getX() < 0 || h.getY() < 0 || h.getX() >= cols || h.getY() >= rows || column(h.getX()).depth[h.getY()] == 0) {
            return null;
        }
        ring(h);
        return beds.get(h).index();
    }

    /** A bank's width, in world units: how far a bed falls from its rim or from the foot of a step. */
    float run() { return RUN * scale; }

    /** Whether a shaped water hex's kernel reaches (x, y): only there can the ground lie under water. */
    boolean near(float x, float y) { return kernel(x, y, Integer.MAX_VALUE)[0] > 0; }

    /**
     * How far (x, y) lies inside today's waterline, in tile pixels (negative on land, clamped at REACH): the signed
     * distance to the waterlines of the water hexes round it, on the board-wide grid, bilinear.
     */
    float distance(float x, float y) {
        float fx = x / step, fy = y / step;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
        float u = fx - i, v = fy - j;
        return (1 - v) * ((1 - u) * node(i, j) + u * node(i + 1, j)) + v * ((1 - u) * node(i, j + 1) + u * node(i + 1, j + 1));
    }

    private float node(int i, int j) {
        long key = (long) Math.floorDiv(i, PAGE) << 32 | Math.floorDiv(j, PAGE) & 0xffffffffL;
        return pages.computeIfAbsent(key, this::page)[Math.floorMod(j, PAGE) * PAGE + Math.floorMod(i, PAGE)];
    }

    private float[] page(long key) {
        int i0 = (int) (key >> 32) * PAGE, j0 = (int) key * PAGE;
        float[] values = new float[PAGE * PAGE];
        for (int j = 0; j < PAGE; j++) {
            for (int i = 0; i < PAGE; i++) { values[j * PAGE + i] = signed((i0 + i) * step, (j0 + j) * step); }
        }
        return values;
    }

    /**
     * The signed distance from (x, y) to today's waterline, in tile pixels, clamped at REACH: positive inside a water
     * hex's line or on the mouth between two, the distance to the nearest bank of the water hexes within two hexes.
     */
    private float signed(float x, float y) {
        float across = width * .75f, best = REACH * scale, mouth = Float.POSITIVE_INFINITY;
        boolean inside = false;
        int near = Math.round((x - width / 2) / across);
        for (int c = Math.max(0, near - 2); c <= Math.min(cols - 1, near + 2); c++) {
            float odd = (c & 1) * height / 2 + height / 2;
            int row = Math.round((-y - odd) / height);
            for (int r = Math.max(0, row - 2); r <= Math.min(rows - 1, row + 2); r++) {
                Line line = line(new Coords(c, r));
                if (line.ring().length == 0) { continue; }
                inside |= contains(line.ring(), x, y);
                best = Math.min(best, nearest(line.banks(), x, y));
                mouth = Math.min(mouth, nearest(line.mouths(), x, y));
            }
        }
        // Two hexes' rings meet along a mouth: a point on it is inside the water whichever ring holds it. Off the board
        // the water runs on past a mouth into the board's edge for a grid step, so the grid does not raise the edge.
        Coords at = GpuStarShells.hexAt(x, y);
        boolean off = at.getX() < 0 || at.getY() < 0 || at.getX() >= cols || at.getY() >= rows;
        return (inside || mouth < (off ? step : .5f * scale) ? best : -best) / scale;
    }

    /** Hex h's waterline with its banks and mouths: a mouth's stretch is a bank where no water lies just outside it. */
    private Line line(Coords h) {
        Line known = lines.get(h);
        if (known != null) { return known; }
        float[] ring = ring(h);
        Line line = DRY_HEX;
        if (ring.length > 0) {
            float cx = h.getX() * width * .75f + width / 2, cy = -(h.getY() * height + (h.getX() & 1) * height / 2 + height / 2);
            int n = ring.length / 2, segments = n / 6, mouths = mouths(h);
            float[] banks = new float[4 * n], open = new float[4 * n];
            int b = 0, o = 0;
            for (int k = 0; k < n; k++) {
                float x0 = ring[2 * k], y0 = ring[2 * k + 1], x1 = ring[2 * ((k + 1) % n)], y1 = ring[2 * ((k + 1) % n) + 1];
                int edge = k / segments;
                boolean bank = (mouths & 1 << edge) == 0;
                if (!bank) {
                    BoardScene.Tile next = scene.tile(h.translated(BoardGeometry.edgeDirection(edge)));
                    float mx = (x0 + x1) / 2, my = (y0 + y1) / 2, dx = mx - cx, dy = my - cy;
                    float len = Math.max((float) Math.hypot(dx, dy), 1e-6f);
                    bank = next != null && !contains(ring(next.coords()), mx + dx / len * scale, my + dy / len * scale);
                }
                float[] to = bank ? banks : open;
                int at = bank ? b : o;
                to[at] = x0;
                to[at + 1] = y0;
                to[at + 2] = x1;
                to[at + 3] = y1;
                if (bank) { b += 4; } else { o += 4; }
            }
            line = new Line(ring, Arrays.copyOf(banks, b), Arrays.copyOf(open, o));
        }
        Line raced = lines.putIfAbsent(h, line);
        return raced == null ? line : raced;
    }

    /** Whether a fall pours into water hex {@code tile} (BoardSurface#river's plunge: it scours today's bed there). */
    private boolean plunged(BoardScene.Tile tile) {
        for (int d = 0; d < 6; d++) {
            int direction = BoardGeometry.edgeDirection(d);
            Coords c = tile.coords().translated(direction);
            BoardScene.Tile next = c.getX() < 0 || c.getY() < 0 || c.getX() >= cols || c.getY() >= rows ? null : scene.tile(c);
            if (next != null && FieldWater.fallsInto(next, tile, (direction + 3) % 6)) { return true; }
        }
        return false;
    }

    /** Today's waterline of water hex h (BoardSurface's) as a ring of x, y; empty for a dry hex or off the board. */
    private float[] ring(Coords h) {
        float[] known = rings.get(h);
        if (known != null) { return known; }
        BoardScene.Tile tile = h.getX() < 0 || h.getY() < 0 || h.getX() >= cols || h.getY() >= rows ? null : scene.tile(h);
        float[] ring = new float[0];
        if (tile != null && tile.liquid().present() && !tile.liquid().molten()) {
            BoardSurface surface = new BoardSurface(scene, tile, TerrainLod.FULL);
            BoardSurface.WaterGeometry today = surface.waterGeometry();
            Vector3[] line = today.waterline();
            boolean graded = today.graded() && !tile.frozen();
            if (graded) { grades.putIfAbsent(h, surface); }
            List<BoardSurface.Face> bed = tile.frozen() || tile.waterDepth() != 0 && !graded && !plunged(tile) ? List.of()
                  : today.faces().stream().filter(face -> face.finish() == BoardSurface.Finish.BED).toList();
            beds.putIfAbsent(h, bed.isEmpty() ? NO_BED : new Bed(new GpuBiomeVegetation.Support(bed)));
            if (line != null) {
                ring = new float[2 * line.length];
                for (int k = 0; k < line.length; k++) {
                    ring[2 * k] = line[k].x;
                    ring[2 * k + 1] = line[k].y;
                }
            }
        }
        float[] raced = rings.putIfAbsent(h, ring);
        return raced == null ? ring : raced;
    }

    /** The edges of water hex h that open onto the water beside it or the board's edge (BoardSurface's open mouths). */
    private int mouths(Coords h) {
        int mouths = 0;
        BoardScene.Tile tile = scene.tile(h);
        for (int e = 0; e < 6; e++) {
            BoardScene.Tile next = scene.tile(h.translated(BoardGeometry.edgeDirection(e)));
            if (next == null || tile.joins(next, BoardGeometry.edgeDirection(e))) { mouths |= 1 << e; }
        }
        return mouths;
    }

    /** Whether (x, y) lies inside {@code ring} (x, y a point; even-odd). */
    private static boolean contains(float[] ring, float x, float y) {
        boolean inside = false;
        for (int i = 0, n = ring.length / 2, j = n - 1; i < n; j = i++) {
            float ax = ring[2 * i], ay = ring[2 * i + 1], bx = ring[2 * j], by = ring[2 * j + 1];
            if (ay > y != by > y && x < (bx - ax) * (y - ay) / (by - ay) + ax) { inside = !inside; }
        }
        return inside;
    }

    /** The distance from (x, y) to the nearest of {@code segments} (x0, y0, x1, y1 each); infinite for none. */
    private static float nearest(float[] segments, float x, float y) {
        float best = Float.POSITIVE_INFINITY;
        for (int k = 0; k < segments.length; k += 4) {
            float ax = segments[k], ay = segments[k + 1], ex = segments[k + 2] - ax, ey = segments[k + 3] - ay;
            float len = ex * ex + ey * ey, t = len == 0 ? 0 : Math.clamp(((x - ax) * ex + (y - ay) * ey) / len, 0, 1);
            float dx = x - ax - t * ex, dy = y - ay - t * ey;
            best = Math.min(best, dx * dx + dy * dy);
        }
        return (float) Math.sqrt(best);
    }

    /**
     * How far the ground at (x, y) lies below its steps' height, in world units: the bank into the water and its bed.
     * The water hexes' own depths meet in a slope, under the kernel. {@code wall}: how much of the full depth the
     * point has as the wet foot of a cliff, whatever the shore (0 to 1); {@code sill}: how much of the bed's depth the
     * rim of a cliff the water stands above takes away (0 to 1).
     */
    float bed(float x, float y, float wall, float sill) {
        float[] kernel = kernel(x, y, Integer.MAX_VALUE);
        if (kernel[1] == 0) { return 0; }
        float depth = kernel[1] / Math.max(kernel[0], LEAST);
        // Toward a water hex's centre the bed reaches that hex's own depth, as today's plateau keeps the game depth
        // under a unit; away from it the bed swells as today's does between its rim and its plateau.
        Coords at = GpuStarShells.hexAt(x, y);
        float own = at.getX() >= 0 && at.getX() < cols && at.getY() >= 0 && at.getY() < rows ? column(at.getX()).depth[at.getY()] : 0;
        float dx = x - (at.getX() * width * .75f + width / 2), dy = y + at.getY() * height + (at.getX() & 1) * height / 2 + height / 2;
        // A broad plateau that ends at the hex's inner circle, so beds of two depths meet without a step at their edge;
        // shallow (depth 0) water's ends at today's plateau ring (BoardSurface#PLATEAU), so from a mouth onto deeper water
        // its bed runs in deep, as today's rings do.
        float plateau = own > 2.01f * scale ? height / 2 : (1 - BoardSurface.PLATEAU) * height / 2;
        float centre = own > 0 ? BoardRelief.smooth(1 - (dx * dx + dy * dy) / (plateau * plateau)) : 0;
        depth = (depth + (own - depth) * centre) * (1 + SWELL * BoardSurface.meander(x, y, 5.1f) * (1 - centre));
        // Where along the profile the bed is one tile pixel down, the water's surface (BoardGeometry#waterZ): the
        // smooth step's inverse. That point lies on the waterline whatever the depth, so pools of any depths share it.
        // The bank falls as steeply as today's beside such land (BoardSurface#bankSteep, #descend); the step's value
        // where that fall is one tile pixel down, and the smooth step's inverse there.
        float steep = steep(x, y, depth / levelZ), e = Math.min(1, scale / depth);
        float fall = steep >= 0 ? 1 - (float) Math.pow(1 - e, 1 / (1 + 2 * steep)) : (float) Math.pow(e, 1 / (1 - 1.5f * steep));
        float line = .5f - (float) Math.sin(Math.asin(1 - 2 * fall) / 3);
        // Beside land at the water's own level a full margin (deeper water) is narrower: today's bank deepens as
        // quickly there. Shallow water's margin, a quarter of its depth, keeps its width.
        float margin = drop(depth), full = BoardRelief.smooth(2 * margin / BoardRelief.metres(MARGIN_DROP) - 1);
        float m = BoardRelief.metres(MARGIN) / scale * (1 - wall)
              * BoardRelief.lerp(1, BoardRelief.lerp(.4f, 1, Math.clamp(steep + .5f, 0, 1)), full);
        float drop = margin * (1 - wall);
        float inside = distance(x, y), shifted = inside - m, t = Math.clamp(shifted / RUN + line, 0, 1);
        // Narrow water reaches its full depth toward the hex's centre too, as today's basin falls from its rim to its
        // plateau along rays, once a bank's width inside the waterline.
        t = Math.max(t, centre * BoardRelief.smooth(shifted / RUN));
        t = (t + (1 - t) * wall) * (1 - sill);
        float sink = depth * BoardSurface.descend(t * t * (3 - 2 * t), steep);
        // The steep fall starts at the margin's foot; a cliff's wet foot (wall) falls from the line itself.
        float deep = inside < m ? sink * wall : Math.min(sink + drop * (1 - sill), depth);
        // Above the waterline the bank reaches its level over the margin's width: the strip the river bank's wet soil
        // is painted on (terrain-sculpt.frag, by height above the water), as narrow as today's rim.
        float bench = m;
        float lip = inside < 0 ? (bench <= 0 ? 0 : scale * Math.clamp(1 + inside / bench, 0, 1))
              : scale + drop * Math.min(1, inside / Math.max(m, 1e-3f));
        return Math.max(deep, lip * (1 - sill));
    }
}
