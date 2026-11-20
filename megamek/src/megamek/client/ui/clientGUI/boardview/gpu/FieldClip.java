/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes what lies inside convex regions from a height-field hex's block ({@link FieldStamps}), or with #split only
 * cuts along them: its triangles, of every list (level tops, top cover, step faces, walls), are cut exactly along the
 * regions' planes and the parts outside stay, as BoardSurface#subtract cuts Faces. A region is planes of six floats
 * each (normal x, y, z and a point on the plane); inside is where every plane's distance, {@code n . (p - point)}, is
 * at most 0, and a sample within a millimetre of a plane lies on it (neighbouring regions share sides). A new sample
 * on a cut edge takes every value of the edge's two samples, its chunk side included, interpolated from the edge's
 * lower end by position (the same edge gives the same sample wherever it is cut), and a morph parent on the same
 * plane (#parent), on the coarser detail's own cut where that is given (#snap). Plain arrays; the block is not
 * changed, a new one is returned without the new samples no triangle uses.
 */
final class FieldClip {
    private final ChunkField.Block block;
    /** The same hex's block at the detail this one morphs to, cut alike, whose cut the parents follow; or null. */
    private final ChunkField.Block coarser;
    /** Whether the parts inside the regions stay too (#split). */
    private final boolean keep;
    /** Per region and plane (#split's key): the coarser block's edges on it, six floats each (#snap). */
    private final Map<Integer, float[]> coarseEdges = new HashMap<>();
    private int size;
    private float[] x, y, z, nx, ny, nz, role, rock, rim, foot, steps, faceRim, faceFoot, projection, parents;
    private int[] sides;
    /** Each sample's nearest step's upper hex (ChunkField.Block#stepTop), or null where the block has none. */
    private int[] stepTop;
    /** The sample made on an edge for a plane, by the edge's two samples and the plane's number. */
    private final Map<Long, Integer> made = new HashMap<>();
    /** Closer than this to a plane a sample lies on it, in world units. */
    private final float snap = BoardRelief.metres(.001f);

    private FieldClip(ChunkField.Block block, ChunkField.Block coarser, boolean keep) {
        this.block = block;
        this.coarser = coarser;
        this.keep = keep;
        size = block.size();
        int capacity = size + 64;
        x = grow(block.x(), capacity); y = grow(block.y(), capacity); z = grow(block.z(), capacity);
        nx = grow(block.nx(), capacity); ny = grow(block.ny(), capacity); nz = grow(block.nz(), capacity);
        role = grow(block.role(), capacity); rock = grow(block.rock(), capacity); rim = grow(block.rim(), capacity);
        foot = grow(block.foot(), capacity); steps = grow(block.steps(), capacity);
        faceRim = grow(block.faceRim(), capacity); faceFoot = grow(block.faceFoot(), capacity);
        projection = grow(block.projection(), capacity); parents = grow(block.parents(), capacity * 6);
        sides = java.util.Arrays.copyOf(block.sides(), capacity);
        stepTop = block.stepTop() == null ? null : java.util.Arrays.copyOf(block.stepTop(), capacity);
    }

    /**
     * {@code block} without what lies inside {@code regions}, cut in the regions' order; {@code coarser}, the same
     * hex's block at the detail this one morphs to, stamped alike, or null.
     */
    static ChunkField.Block remove(ChunkField.Block block, List<float[]> regions, ChunkField.Block coarser) {
        return clip(block, regions, coarser, false);
    }

    /** {@code block} cut along the regions' planes as #remove cuts it, the parts inside kept as well. */
    static ChunkField.Block split(ChunkField.Block block, List<float[]> regions) {
        return clip(block, regions, null, true);
    }

    /**
     * {@code block} cut along stretches of lines ({@link FieldStamps}' wing walls): per line eighteen floats, a vertical
     * plane and two planes bounding its stretch (inside where they are at most 0). An edge is cut where it crosses the
     * line's plane inside the stretch, decided by the edge alone, so the hexes sharing an edge cut it alike; a triangle
     * with two cut edges parts along the chord between them, one with one cut edge from it to the opposite corner.
     */
    static ChunkField.Block cutLines(ChunkField.Block block, List<float[]> lines) {
        FieldClip clip = new FieldClip(block, null, true);
        int[][] lists = { block.levelTops(), block.ground(), block.faces(), block.walls() };
        // The ground only: a wall's column samples stand far from its coarser ones, and no wing stands on a wall.
        for (int l = 0; l < lines.size(); l++) {
            for (int k = 0; k < 3; k++) { lists[k] = clip.cutLine(lists[k], lines.get(l), LINES + l); }
        }
        return clip.block(lists[0], lists[1], lists[2], lists[3]);
    }

    /** The first plane number #cutLines gives its lines in #split's key, clear of the regions' (region * 64 + plane). */
    private static final int LINES = 1 << 18;

    /** A triangle list with every edge that crosses the line inside its stretch cut (#cutLines). */
    private int[] cutLine(int[] triangles, float[] line, int number) {
        int[] result = new int[triangles.length * 3];
        int count = 0;
        int[] v = new int[3], cut = new int[3];
        for (int t = 0; t < triangles.length; t += 3) {
            int n = 0;
            for (int i = 0; i < 3; i++) { v[i] = triangles[t + i]; }
            for (int i = 0; i < 3; i++) {
                cut[i] = crossing(v[i], v[(i + 1) % 3], line, number);
                if (cut[i] >= 0) { n++; }
            }
            if (n == 1) {
                int i = cut[0] >= 0 ? 0 : cut[1] >= 0 ? 1 : 2;
                int a = v[i], b = v[(i + 1) % 3], c = v[(i + 2) % 3], w = cut[i];
                result[count++] = a; result[count++] = w; result[count++] = c;
                result[count++] = w; result[count++] = b; result[count++] = c;
            } else if (n == 2) {
                // Edges s and s + 1 are cut: the corner between them lies alone on its side of the line.
                int s = cut[0] >= 0 && cut[1] >= 0 ? 0 : cut[1] >= 0 && cut[2] >= 0 ? 1 : 2;
                int a = v[s], tip = v[(s + 1) % 3], c = v[(s + 2) % 3], w0 = cut[s], w1 = cut[(s + 1) % 3];
                result[count++] = w0; result[count++] = tip; result[count++] = w1;
                result[count++] = a; result[count++] = w0; result[count++] = w1;
                result[count++] = a; result[count++] = w1; result[count++] = c;
            } else {
                result[count++] = v[0]; result[count++] = v[1]; result[count++] = v[2];
            }
        }
        return java.util.Arrays.copyOf(result, count);
    }

    /** The sample where edge u, v crosses a line's plane inside its stretch (one per edge and line); else -1. */
    private int crossing(int u, int v, float[] line, int number) {
        boolean swap = x[u] > x[v] || x[u] == x[v] && (y[u] > y[v] || y[u] == y[v] && z[u] > z[v]);
        int a = swap ? v : u, b = swap ? u : v;
        float da = distance(line, 0, a), db = distance(line, 0, b);
        if (!(da < 0 && db > 0 || da > 0 && db < 0)) { return -1; }
        float t = da / (da - db), px = lerp(x[a], x[b], t), py = lerp(y[a], y[b], t), pz = lerp(z[a], z[b], t);
        for (int p = 6; p < line.length; p += 6) {
            if (line[p] * (px - line[p + 3]) + line[p + 1] * (py - line[p + 4]) + line[p + 2] * (pz - line[p + 5]) > 0) {
                return -1;
            }
        }
        return split(a, b, line, 0, number);
    }

    private static ChunkField.Block clip(ChunkField.Block block, List<float[]> regions, ChunkField.Block coarser,
          boolean keep) {
        FieldClip clip = new FieldClip(block, coarser, keep);
        int[] levelTops = clip.list(block.levelTops(), regions), ground = clip.list(block.ground(), regions);
        int[] faces = clip.list(block.faces(), regions), walls = clip.list(block.walls(), regions);
        return clip.block(levelTops, ground, faces, walls);
    }

    private static float[] grow(float[] values, int capacity) { return java.util.Arrays.copyOf(values, capacity); }

    /** A triangle list with every triangle cut by every region in turn. */
    private int[] list(int[] triangles, List<float[]> regions) {
        int[] result = new int[triangles.length + 48];
        int count = 0;
        int[] pieces = new int[96], next = new int[96];
        for (int t = 0; t < triangles.length; t += 3) {
            int n = 3;
            System.arraycopy(triangles, t, pieces, 0, 3);
            for (int r = 0; r < regions.size() && n > 0; r++) {
                int m = 0;
                for (int p = 0; p < n; p += 3) {
                    if (next.length < m + 96) { next = java.util.Arrays.copyOf(next, next.length * 2); }
                    m = cut(pieces[p], pieces[p + 1], pieces[p + 2], regions.get(r), r, next, m);
                }
                int[] swap = pieces;
                pieces = next;
                next = swap.length >= pieces.length ? swap : new int[pieces.length];
                n = m;
            }
            if (result.length < count + n) { result = java.util.Arrays.copyOf(result, Math.max(result.length * 2, count + n)); }
            System.arraycopy(pieces, 0, result, count, n);
            count += n;
        }
        return java.util.Arrays.copyOf(result, count);
    }

    /**
     * Triangle a, b, c without what lies inside {@code region} (number {@code r}): its pieces outside, as triangles,
     * written to {@code out} from {@code at}, and with #keep the piece inside after them; returns the new end. The
     * polygon still inside is cut by each plane in turn; a sample on a plane belongs to both sides.
     */
    private int cut(int a, int b, int c, float[] region, int r, int[] out, int at) {
        for (int p = 0; p < region.length; p += 6) {
            if (distance(region, p, a) > 0 && distance(region, p, b) > 0 && distance(region, p, c) > 0) {
                out[at] = a; out[at + 1] = b; out[at + 2] = c;
                return at + 3;
            }
        }
        int[] inside = { a, b, c };
        int count = 3;
        for (int p = 0; p < region.length && count >= 3; p += 6) {
            int[] kept = new int[count + 2], still = new int[count + 2];
            int k = 0, s = 0;
            for (int i = 0; i < count; i++) {
                int u = inside[i], v = inside[(i + 1) % count];
                float du = distance(region, p, u), dv = distance(region, p, v);
                if (du <= 0) { still[s++] = u; }
                if (du >= 0) { kept[k++] = u; }
                if (du < 0 && dv > 0 || du > 0 && dv < 0) {
                    int w = split(u, v, region, p, r * 64 + p / 6);
                    kept[k++] = w;
                    still[s++] = w;
                }
            }
            for (int i = 1; i + 1 < k; i++) {
                out[at] = kept[0]; out[at + 1] = kept[i]; out[at + 2] = kept[i + 1];
                at += 3;
            }
            inside = still;
            count = s;
        }
        for (int i = 1; keep && i + 1 < count; i++) {
            out[at] = inside[0]; out[at + 1] = inside[i]; out[at + 2] = inside[i + 1];
            at += 3;
        }
        return at;
    }

    /** Sample v's distance from plane p of a region, scaled by the normal's length; 0 within {@link #snap} of it. */
    private float distance(float[] region, int p, int v) {
        float d = region[p] * (x[v] - region[p + 3]) + region[p + 1] * (y[v] - region[p + 4]) + region[p + 2] * (z[v] - region[p + 5]);
        float n2 = region[p] * region[p] + region[p + 1] * region[p + 1] + region[p + 2] * region[p + 2];
        return d * d <= snap * snap * n2 ? 0 : d;
    }

    /** The sample where edge u, v crosses plane p of a region: one per edge and plane, from the edge's lower end. */
    private int split(int u, int v, float[] region, int p, int plane) {
        long key = (long) plane << 40 | (long) Math.min(u, v) << 20 | Math.max(u, v);
        Integer known = made.get(key);
        if (known != null) { return known; }
        boolean swap = x[u] > x[v] || x[u] == x[v] && (y[u] > y[v] || y[u] == y[v] && z[u] > z[v]);
        int a = swap ? v : u, b = swap ? u : v;
        float da = distance(region, p, a), db = distance(region, p, b);
        float t = da / (da - db);
        int w = sample();
        x[w] = lerp(x[a], x[b], t); y[w] = lerp(y[a], y[b], t); z[w] = lerp(z[a], z[b], t);
        float mx = lerp(nx[a], nx[b], t), my = lerp(ny[a], ny[b], t), mz = lerp(nz[a], nz[b], t);
        float length = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        nx[w] = length > 0 ? mx / length : nx[a]; ny[w] = length > 0 ? my / length : ny[a]; nz[w] = length > 0 ? mz / length : nz[a];
        role[w] = lerp(role[a], role[b], t); rock[w] = lerp(rock[a], rock[b], t); rim[w] = lerp(rim[a], rim[b], t);
        foot[w] = lerp(foot[a], foot[b], t); steps[w] = lerp(steps[a], steps[b], t);
        faceRim[w] = lerp(faceRim[a], faceRim[b], t); faceFoot[w] = lerp(faceFoot[a], faceFoot[b], t);
        projection[w] = lerp(projection[a], projection[b], t);
        parent(a, b, t, region, p, w);
        if (coarser != null) { snap(region, p, plane, w); }
        // A sample on the hex's outline next to another chunk keeps that chunk's morph cap; one inside has none.
        sides[w] = sides[a] != 0 && sides[b] != 0 ? Math.max(sides[a], sides[b]) : 0;
        // The step its edge's lower end knows (the same end in every hex that cuts the edge).
        if (stepTop != null) { stepTop[w] = stepTop[a]; }
        made.put(key, w);
        return w;
    }

    /**
     * The new sample's morph parent: where its edge as the coarser detail has it (its ends' parents; a sample that is its
     * own parent counts with no offset and its own normal) crosses the same plane, so the sample stays on the cut through
     * the morph and meets the coarser detail's own cut at weight 0 (a wall's coarser columns lie far from its finer ones).
     */
    private void parent(int a, int b, float t, float[] region, int p, int w) {
        int pa = a * 6, pb = b * 6, pw = w * 6;
        boolean ownA = Float.isNaN(parents[pa]), ownB = Float.isNaN(parents[pb]);
        if (ownA && ownB) {
            java.util.Arrays.fill(parents, pw, pw + 6, Float.NaN);
            return;
        }
        float[] from = { x[a] + (ownA ? 0 : parents[pa]), y[a] + (ownA ? 0 : parents[pa + 1]), z[a] + (ownA ? 0 : parents[pa + 2]) };
        float[] to = { x[b] + (ownB ? 0 : parents[pb]), y[b] + (ownB ? 0 : parents[pb + 1]), z[b] + (ownB ? 0 : parents[pb + 2]) };
        float da = region[p] * (from[0] - region[p + 3]) + region[p + 1] * (from[1] - region[p + 4]) + region[p + 2] * (from[2] - region[p + 5]);
        float db = region[p] * (to[0] - region[p + 3]) + region[p + 1] * (to[1] - region[p + 4]) + region[p + 2] * (to[2] - region[p + 5]);
        // Where the coarser edge does not cross the plane, its end nearer to it.
        t = da == db ? t : Math.clamp(da / (da - db), 0, 1);
        float[] own = { x[w], y[w], z[w] };
        for (int k = 0; k < 3; k++) { parents[pw + k] = lerp(from[k], to[k], t) - own[k]; }
        float mx = lerp(ownA ? nx[a] : parents[pa + 3], ownB ? nx[b] : parents[pb + 3], t);
        float my = lerp(ownA ? ny[a] : parents[pa + 4], ownB ? ny[b] : parents[pb + 4], t);
        float mz = lerp(ownA ? nz[a] : parents[pa + 5], ownB ? nz[b] : parents[pb + 5], t);
        float length = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        parents[pw + 3] = mx / length;
        parents[pw + 4] = my / length;
        parents[pw + 5] = mz / length;
    }

    /**
     * Moves new sample w's parent (on plane p, #parent) to the nearest point of the coarser block's edges on the same
     * plane: its own cut there, so at weight 0 the rim lies on the coarser detail's rim and, the plane being flat,
     * stays on the plane through the morph. Without such an edge the parent stays.
     */
    private void snap(float[] region, int p, int plane, int w) {
        int pw = w * 6;
        if (Float.isNaN(parents[pw])) { return; }
        float[] edges = coarseEdges.computeIfAbsent(plane, key -> edges(region, p));
        float qx = x[w] + parents[pw], qy = y[w] + parents[pw + 1], qz = z[w] + parents[pw + 2];
        float best = Float.MAX_VALUE, bx = qx, by = qy, bz = qz;
        for (int i = 0; i < edges.length; i += 6) {
            float dx = edges[i + 3] - edges[i], dy = edges[i + 4] - edges[i + 1], dz = edges[i + 5] - edges[i + 2];
            float length = dx * dx + dy * dy + dz * dz;
            float along = (qx - edges[i]) * dx + (qy - edges[i + 1]) * dy + (qz - edges[i + 2]) * dz;
            float s = length == 0 ? 0 : Math.clamp(along / length, 0, 1);
            float ex = edges[i] + dx * s, ey = edges[i + 1] + dy * s, ez = edges[i + 2] + dz * s;
            float d = (ex - qx) * (ex - qx) + (ey - qy) * (ey - qy) + (ez - qz) * (ez - qz);
            if (d < best) {
                best = d;
                bx = ex;
                by = ey;
                bz = ez;
            }
        }
        parents[pw] = bx - x[w];
        parents[pw + 1] = by - y[w];
        parents[pw + 2] = bz - z[w];
    }

    /** The coarser block's triangle edges with both ends on plane p of a region, as their ends' positions. */
    private float[] edges(float[] region, int p) {
        float[] cx = coarser.x(), cy = coarser.y(), cz = coarser.z();
        float n2 = region[p] * region[p] + region[p + 1] * region[p + 1] + region[p + 2] * region[p + 2];
        boolean[] on = new boolean[coarser.size()];
        for (int v = 0; v < on.length; v++) {
            float d = region[p] * (cx[v] - region[p + 3]) + region[p + 1] * (cy[v] - region[p + 4])
                  + region[p + 2] * (cz[v] - region[p + 5]);
            on[v] = d * d <= snap * snap * n2;
        }
        float[] result = new float[48];
        int n = 0;
        for (int[] list : new int[][] { coarser.levelTops(), coarser.ground(), coarser.faces(), coarser.walls() }) {
            for (int i = 0; i < list.length; i += 3) {
                for (int k = 0; k < 3; k++) {
                    int u = list[i + k], v = list[i + (k + 1) % 3];
                    if (!on[u] || !on[v]) { continue; }
                    if (result.length < n + 6) { result = java.util.Arrays.copyOf(result, result.length * 2); }
                    result[n++] = cx[u]; result[n++] = cy[u]; result[n++] = cz[u];
                    result[n++] = cx[v]; result[n++] = cy[v]; result[n++] = cz[v];
                }
            }
        }
        return java.util.Arrays.copyOf(result, n);
    }

    private int sample() {
        if (size == x.length) {
            int capacity = x.length * 2;
            x = grow(x, capacity); y = grow(y, capacity); z = grow(z, capacity); nx = grow(nx, capacity);
            ny = grow(ny, capacity); nz = grow(nz, capacity); role = grow(role, capacity); rock = grow(rock, capacity);
            rim = grow(rim, capacity); foot = grow(foot, capacity); steps = grow(steps, capacity);
            faceRim = grow(faceRim, capacity); faceFoot = grow(faceFoot, capacity); projection = grow(projection, capacity);
            parents = grow(parents, capacity * 6);
            sides = java.util.Arrays.copyOf(sides, capacity);
            if (stepTop != null) { stepTop = java.util.Arrays.copyOf(stepTop, capacity); }
        }
        return size++;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** The new block: the original samples keep their indices (the lattice's are addressed by place), new unused ones go. */
    private ChunkField.Block block(int[] levelTops, int[] ground, int[] faces, int[] walls) {
        int original = block.size();
        boolean[] used = new boolean[size];
        for (int[] list : new int[][] { levelTops, ground, faces, walls }) { for (int v : list) { used[v] = true; } }
        int[] index = new int[size];
        for (int v = 0; v < original; v++) { index[v] = v; }
        int kept = original;
        for (int v = original; v < size; v++) {
            if (used[v]) { move(v, kept); index[v] = kept++; }
        }
        for (int[] list : new int[][] { levelTops, ground, faces, walls }) {
            for (int i = 0; i < list.length; i++) { list[i] = index[list[i]]; }
        }
        size = kept;
        return new ChunkField.Block(block.coords(), block.family(), block.level(), block.lattice(), trim(x), trim(y), trim(z),
              trim(nx), trim(ny), trim(nz), trim(role), trim(rock), trim(rim), trim(foot), trim(steps), trim(faceRim),
              trim(faceFoot), trim(projection), levelTops, ground, faces, walls, block.bounds(),
              java.util.Arrays.copyOf(parents, size * 6), java.util.Arrays.copyOf(sides, size),
              stepTop == null ? null : java.util.Arrays.copyOf(stepTop, size), null);
    }

    private float[] trim(float[] values) { return java.util.Arrays.copyOf(values, size); }

    /** Sample {@code from}'s values to {@code to}, no later than it (used samples move down in order). */
    private void move(int from, int to) {
        if (from == to) { return; }
        for (float[] a : new float[][] { x, y, z, nx, ny, nz, role, rock, rim, foot, steps, faceRim, faceFoot, projection }) {
            a[to] = a[from];
        }
        System.arraycopy(parents, from * 6, parents, to * 6, 6);
        sides[to] = sides[from];
        if (stepTop != null) { stepTop[to] = stepTop[from]; }
    }
}
