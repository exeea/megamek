/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.badlogic.gdx.math.Vector3;

import megamek.common.board.Coords;

/**
 * What is stamped into a height-field hex's block after it is built ({@link ChunkField#block}): a road hex's ground
 * lowered to the road out of its own tunnels' openings (#floor), then, clipped out by {@link FieldClip}, the portals of
 * the tunnels below it (BoardTunnel#region, held by {@link FieldBoard#portals}), as BoardSurface#walls cuts today's
 * walls, then the ground its own earthworks dig away (BoardGroundCut#footprint, held by {@link FieldBoard#cuts}), as
 * today's dig digs the hex's own top only. The tunnel's and the earthwork's models draw what shows through.
 */
final class FieldStamps {
    private FieldStamps() { }

    /** {@code coarser}: the same hex's block at the detail this one morphs to, stamped; null for the coarsest. */
    static ChunkField.Block apply(FieldBoard board, ChunkField.Block block,
          java.util.function.Supplier<ChunkField.Block> coarser, Function<Coords, FieldBoard.Shares> shares) {
        List<float[]> own = footprints(board.openings(block.coords()));
        List<float[]> below = footprints(board.entries(block.coords()));
        if (!own.isEmpty() || !below.isEmpty()) {
            // The road hex and its neighbours are all cut along the opening's footprint (theirs below the arch's
            // crown), so their shared borders have the same samples on its sides; only the road hex's ground comes
            // down.
            List<float[]> all = new ArrayList<>(own);
            all.addAll(below);
            block = FieldClip.split(block, all);
            if (!own.isEmpty()) { floor(board, block, own, coarser == null ? null : new FieldSupport(coarser.get())); }
        }
        float[][] mouths = board.mouths(block.coords());
        if (mouths != null) {
            ChunkField.Block lower = coarser == null ? null : coarser.get();
            block = wings(block, mouths);
            mound(block, mouths, lower);
        }
        block = contained(board, block, shares, coarser == null ? null : coarser.get());
        float[][] portals = board.portals(block.coords());
        float[] cut = board.cuts(block.coords());
        if (portals == null && cut == null) { return block; }
        List<float[]> regions = new ArrayList<>();
        if (portals != null) { regions.addAll(List.of(portals)); }
        for (int i = 0; cut != null && i < cut.length; i += 6) { regions.add(prism(cut, i)); }
        return FieldClip.remove(block, regions, portals == null || coarser == null ? null : coarser.get());
    }

    /**
     * A road hex's ground inside its own tunnels' footprints (BoardTunnel#footprint, along which the block is cut
     * first) comes down to the road's bed (FieldBoard#bed), so no bank or talus beside the carriageway stands in the
     * arch: the field's form of today's flat portal face (BoardTunnel#clearWings). Its morph parent, and that of a top
     * sample whose parent lies within reach of the footprint, is where the coarser detail, stamped alike, has its
     * ground ({@code coarser}; null for the coarsest). Changes the block's arrays in place: it is not yet shared.
     * Left open by design: beside the arch, where the lowered border meets the upper hex's wall, the two hexes part
     * (Mines 1: 40 points at FULL), all inside the portal model's shell and wings, which covers them.
     */
    private static void floor(FieldBoard board, ChunkField.Block block, List<float[]> own, FieldSupport coarser) {
        float[] x = block.x(), y = block.y(), z = block.z(), parents = block.parents();
        boolean[] wall = new boolean[block.size()];
        for (int v : block.walls()) { wall[v] = true; }
        float reach = BoardRelief.metres(9), moved = BoardRelief.metres(.01f);
        for (int v = 0; v < block.size(); v++) {
            boolean lower = inside(own, x[v], y[v], 0);
            float bed = lower ? board.bed(block.coords(), x[v], y[v]) : Float.NaN;
            lower &= bed < z[v];
            int p = v * 6;
            if (!Float.isNaN(parents[p])) {
                float px = x[v] + parents[p], py = y[v] + parents[p + 1], pz = z[v] + parents[p + 2];
                if (lower || !wall[v] && inside(own, px, py, reach)) {
                    float to = !wall[v] && coarser != null ? coarser.height(px, py) : Float.NaN;
                    if (Float.isNaN(to)) {
                        float parentBed = board.bed(block.coords(), px, py);
                        to = lower && parentBed < pz ? parentBed : pz;
                    }
                    // Where the coarser ground did not move, the parent stays as built (shared with the neighbour).
                    if (!lower && Math.abs(to - pz) <= moved) { to = pz; }
                    parents[p + 2] = to - (lower ? bed : z[v]);
                    if (lower) {
                        parents[p + 3] = 0;
                        parents[p + 4] = 0;
                        parents[p + 5] = 1;
                    }
                }
            }
            if (!lower) { continue; }
            z[v] = bed;
            block.bounds()[2] = Math.min(block.bounds()[2], bed);
            block.nx()[v] = 0;
            block.ny()[v] = 0;
            block.nz()[v] = 1;
        }
    }

    /** The scree's angle of repose (35 degrees) as its run over its rise. */
    private static final float REPOSE = 1 / .7f;

    /**
     * The scree a ground tunnel's mouth is set in (the portal's join), its part behind the headwall: in every hex near
     * the mouth (FieldBoard#mouths), each wall sample above the shell's roof that stands behind the scree's slope comes
     * forward onto it (#pull). The ground in front of the cliff's face rises onto the scree in FieldBoard#height
     * (#scree). Every sample in the pull's reach then takes as its parent the nearest point of the coarser detail's own
     * block there (stamped alike; {@code coarser}, null for the coarsest), so the morph ends on the coarser slope: the
     * slope is not linear, and the coarser triangles run straight between its moved corners.
     */
    private static void mound(ChunkField.Block block, float[][] mouths, ChunkField.Block coarser) {
        float[] x = block.x(), y = block.y(), bounds = block.bounds();
        float[] nx = block.nx(), ny = block.ny(), nz = block.nz();
        for (float[] f : mouths) {
            // The slope's normal leans out from the cliff by the angle of repose from the vertical.
            float lean = (float) Math.sqrt(1 + REPOSE * REPOSE), sx = -f[3] / lean, sy = -f[4] / lean, sz = REPOSE / lean;
            boolean moved = false;
            for (int v = 0; v < block.size(); v++) {
                float pull = pull(f, x[v], y[v], block.z()[v]);
                if (pull <= 0) { continue; }
                moved = true;
                x[v] -= f[3] * pull;
                y[v] -= f[4] * pull;
                nx[v] = sx;
                ny[v] = sy;
                nz[v] = sz;
                bounds[0] = Math.min(bounds[0], x[v]);
                bounds[1] = Math.min(bounds[1], y[v]);
                bounds[3] = Math.max(bounds[3], x[v]);
                bounds[4] = Math.max(bounds[4], y[v]);
            }
            float margin = 12 * f[6];
            if (moved && coarser != null) {
                reparent(block, coarser, (px, py, pz) -> reach(f, px, py, pz, margin, false),
                      (px, py, pz) -> reach(f, px, py, pz, margin, true), null);
            }
        }
    }

    /** Whether a point lies in a stamp's reach. */
    private interface Reach {
        boolean holds(float x, float y, float z);
    }

    /**
     * The parents of a block's samples {@code in} a stamp's reach: the nearest point of the coarser block's own
     * triangles there, those with a corner in the reach grown by a coarser triangle ({@code wide}). With {@code rim}
     * (the hex's corners, x and y) a sample on the hex's border takes the nearest point of the coarser block's border
     * there, which the next hex's coarser block has too: the two hexes sharing the sample give it one parent.
     */
    private static void reparent(ChunkField.Block block, ChunkField.Block coarser, Reach in, Reach wide, float[] rim) {
        float[] cx = coarser.x(), cy = coarser.y(), cz = coarser.z();
        boolean[] near = new boolean[coarser.size()];
        for (int v = 0; v < near.length; v++) { near[v] = wide.holds(cx[v], cy[v], cz[v]); }
        int[] candidates = new int[64];
        int count = 0;
        for (int[] list : new int[][] { coarser.levelTops(), coarser.ground(), coarser.faces(), coarser.walls() }) {
            for (int i = 0; i < list.length; i += 3) {
                if (!near[list[i]] && !near[list[i + 1]] && !near[list[i + 2]]) { continue; }
                if (count + 3 > candidates.length) { candidates = java.util.Arrays.copyOf(candidates, candidates.length * 2); }
                candidates[count++] = list[i];
                candidates[count++] = list[i + 1];
                candidates[count++] = list[i + 2];
            }
        }
        float[] x = block.x(), y = block.y(), z = block.z(), parents = block.parents(), at = new float[5];
        for (int v = 0; v < block.size(); v++) {
            int p = v * 6;
            // Above, behind and beside the pull too, as far as a coarser triangle that a moved corner bends reaches.
            if (Float.isNaN(parents[p]) || !in.holds(x[v], y[v], z[v])) { continue; }
            float nearest = Float.MAX_VALUE, bx = 0, by = 0, bz = 0, bu = 0, bv = 0;
            int a = -1, b = -1, c = -1, edge = -1;
            for (int e = 0; rim != null && edge < 0 && e < 6; e++) {
                if (on(rim, e, x[v], y[v])) { edge = e; }
            }
            // Each candidate triangle, or for a sample on the border each of its sides along that border (a triangle
            // with two corners alike).
            for (int t = 0; t < count * (edge < 0 ? 1 : 3); t += 3) {
                int s = edge < 0 ? 0 : t / count, at0 = edge < 0 ? t : t % count;
                int i = candidates[at0 + s], j = candidates[at0 + (s + 1) % 3], k = edge < 0 ? candidates[at0 + 2] : j;
                if (edge >= 0 && !(on(rim, edge, cx[i], cy[i]) && on(rim, edge, cx[j], cy[j]))) { continue; }
                closest(x[v], y[v], z[v], cx[i], cy[i], cz[i], cx[j], cy[j], cz[j], cx[k], cy[k], cz[k], at);
                float d = (at[0] - x[v]) * (at[0] - x[v]) + (at[1] - y[v]) * (at[1] - y[v]) + (at[2] - z[v]) * (at[2] - z[v]);
                if (d < nearest) {
                    nearest = d;
                    bx = at[0]; by = at[1]; bz = at[2]; bu = at[3]; bv = at[4];
                    a = i; b = j; c = k;
                }
            }
            if (a < 0) { continue; }
            parents[p] = bx - x[v];
            parents[p + 1] = by - y[v];
            parents[p + 2] = bz - z[v];
            float w = 1 - bu - bv;
            float mx = coarser.nx()[a] * w + coarser.nx()[b] * bu + coarser.nx()[c] * bv;
            float my = coarser.ny()[a] * w + coarser.ny()[b] * bu + coarser.ny()[c] * bv;
            float mz = coarser.nz()[a] * w + coarser.nz()[b] * bu + coarser.nz()[c] * bv;
            float length = (float) Math.sqrt(mx * mx + my * my + mz * mz);
            if (length > 0) { parents[p + 3] = mx / length; parents[p + 4] = my / length; parents[p + 5] = mz / length; }
        }
    }

    /** Whether (x, y) lies on edge e of a hex's border ({@code rim}: its corners' x and y), within a centimetre. */
    private static boolean on(float[] rim, int e, float x, float y) {
        float ax = rim[2 * e], ay = rim[2 * e + 1], ex = rim[(2 * e + 2) % 12] - ax, ey = rim[(2 * e + 3) % 12] - ay;
        float length = (float) Math.hypot(ex, ey), tolerance = BoardRelief.metres(.01f);
        float along = ((x - ax) * ex + (y - ay) * ey) / length, off = Math.abs((x - ax) * ey - (y - ay) * ex) / length;
        return off <= tolerance && along >= -tolerance && along <= length + tolerance;
    }

    /**
     * Whether (x, y, z) lies in a mouth's pull reach (above the roof, within the shell, up to where the slope ends),
     * grown by {@code margin} (world units) up, back and down, and sideways by that with {@code wide}, else by a
     * coarser triangle's width.
     */
    private static boolean reach(float[] f, float x, float y, float z, float margin, boolean wide) {
        float scale = f[6], dx = (x - f[0]) / scale, dy = (y - f[1]) / scale, lz = (z - f[2]) / scale, m = margin / scale;
        float lx = (dx * f[4] - dy * f[3]) / f[5], ly = dx * f[3] + dy * f[4];
        float top = BoardTunnel.HEADWALL + (BoardTunnel.DEPTH - BoardTunnel.HEADWALL_BACK) / REPOSE;
        return Math.abs(lx) <= BoardTunnel.SHELL_HALF + (wide ? m : 6) && lz >= BoardTunnel.ROOF - m && lz <= top + m
              && ly >= -m && ly <= BoardTunnel.DEPTH + m;
    }

    /**
     * The point of triangle a, b, c nearest (px, py, pz) into {@code out}: its x, y, z and its weights of b and c
     * (Ericson, Real-Time Collision Detection 5.1.5).
     */
    private static void closest(float px, float py, float pz, float ax, float ay, float az, float bx, float by, float bz,
          float cx, float cy, float cz, float[] out) {
        float abx = bx - ax, aby = by - ay, abz = bz - az, acx = cx - ax, acy = cy - ay, acz = cz - az;
        float apx = px - ax, apy = py - ay, apz = pz - az;
        float d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
        float u, v;
        if (d1 <= 0 && d2 <= 0) { u = 0; v = 0; } else {
            float bpx = px - bx, bpy = py - by, bpz = pz - bz;
            float d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
            float cpx = px - cx, cpy = py - cy, cpz = pz - cz;
            float d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
            float vc = d1 * d4 - d3 * d2, vb = d5 * d2 - d1 * d6, va = d3 * d6 - d5 * d4;
            if (d3 >= 0 && d4 <= d3) { u = 1; v = 0; }
            else if (vc <= 0 && d1 >= 0 && d3 <= 0) { u = d1 / (d1 - d3); v = 0; }
            else if (d6 >= 0 && d5 <= d6) { u = 0; v = 1; }
            else if (vb <= 0 && d2 >= 0 && d6 <= 0) { u = 0; v = d2 / (d2 - d6); }
            else if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) { v = (d4 - d3) / (d4 - d3 + d5 - d6); u = 1 - v; }
            else {
                float denominator = 1 / (va + vb + vc);
                u = vb * denominator;
                v = vc * denominator;
            }
        }
        out[0] = ax + abx * u + acx * v;
        out[1] = ay + aby * u + acy * v;
        out[2] = az + abz * u + acz * v;
        out[3] = u;
        out[4] = v;
    }

    /** Over how many tile pixels before the shell's side the pull onto the roof's slope eases out (#pull). */
    private static final float PULL_EASE = 6;

    /** How far (world units) (x, y, z) stands behind a mouth's scree slope, eased out at the shell's sides; else 0. */
    private static float pull(float[] f, float x, float y, float z) {
        float scale = f[6], dx = (x - f[0]) / scale, dy = (y - f[1]) / scale, lz = (z - f[2]) / scale;
        float lx = (dx * f[4] - dy * f[3]) / f[5], ly = dx * f[3] + dy * f[4];
        float side = BoardTunnel.SHELL_HALF - Math.abs(lx);
        if (lz < BoardTunnel.ROOF || ly > BoardTunnel.DEPTH || ly < BoardTunnel.HEADWALL_BACK || side <= 0) {
            return 0;
        }
        float slope = BoardTunnel.HEADWALL_BACK + Math.max(0, (lz - BoardTunnel.HEADWALL) * REPOSE);
        return Math.max(0, ly - slope) * scale * BoardRelief.smooth(side / PULL_EASE);
    }

    /**
     * The scree's world height at (x, y) over the frames of the mouths near a hex (FieldBoard#mouths, null for none):
     * the highest of theirs, capped at each entered hex's top; negative infinity where none lies (behind the cliff's
     * face, under the shell, inside the wing walls' span). A function of position only, so every hex and detail that
     * samples a point gets one height.
     */
    static float scree(float[][] frames, float x, float y) {
        float best = Float.NEGATIVE_INFINITY;
        if (frames == null) { return best; }
        for (float[] f : frames) { best = Math.max(best, scree(f, x, y)); }
        return best;
    }

    /** One mouth's scree's world height at (x, y) (BoardTunnel#frame), capped at the entered hex's top. */
    static float scree(float[] f, float x, float y) {
        float scale = f[6], dx = (x - f[0]) / scale, dy = (y - f[1]) / scale;
        float lx = (dx * f[4] - dy * f[3]) / f[5], ly = dx * f[3] + dy * f[4];
        return Math.min(f[2] + rise(f, lx, ly) * scale, f[7]);
    }

    /**
     * The ground of a mouth's approach (BoardTunnel#frame): between its wing walls, up to their outer faces, in front of
     * its face and on under its shell to the road hex's edge (its own samples, up to and on the edge) no higher than the
     * opening's floor, so the wings and the headwall retain the
     * scree and the road hex's own bank; past the wings' ends it rises at the angle of repose over a short run-out
     * (#RUNOUT), then ends: never further ahead, where it would cut other hexes' ground. The lowest over the frames
     * whose road hex is {@code owner} (FieldBoard#packed), positive infinity outside them all: the approach is the road
     * hex's own ground. A neighbour's samples are never lowered, the entered hex's rim above all (the top of the wall it
     * builds down to the road hex: ChunkField#walls), and a sample the road hex shares with a neighbour at its level takes
     * the neighbour's height (ChunkField's lattice).
     */
    /**
     * How far past the wings' ends the approach's floor runs on, and past their outer faces the scree they retain
     * keeps their tops (#rise), in tile pixels.
     */
    private static final float RUNOUT = 6;

    static float approach(float[][] frames, int owner, float x, float y) {
        float lowest = Float.POSITIVE_INFINITY;
        if (frames == null) { return lowest; }
        for (float[] f : frames) {
            if (f[13] != owner) { continue; }
            float scale = f[6], dx = (x - f[0]) / scale, dy = (y - f[1]) / scale;
            float lx = (dx * f[4] - dy * f[3]) / f[5], ly = dx * f[3] + dy * f[4];
            float ahead = -ly, outer = f[8] + (f[9] - f[8]) * Math.clamp(ahead / f[10], 0, 1);
            // No bound behind: every sample of the road hex is its ground, up to and on its own edge.
            if (ahead > f[10] + RUNOUT || Math.abs(lx) >= outer) { continue; }
            lowest = Math.min(lowest, f[2] + Math.max(0, ahead - f[10]) / REPOSE * scale);
        }
        return lowest;
    }

    /**
     * The ground at a mouth's wing walls: cut along each wall's two faces over its length (FieldClip#cutLines), so the
     * step from the approach's floor (#approach) up to the scree outside (#scree) happens under the concrete. The cut's
     * points on the ground within a wall's length come to the floor on its inner face and to the scree's height on its
     * outer one, each its own parent: the hexes that share a point cut and move it alike. Both heights are the road hex's
     * own ground (#approach): another hex's points on the faces (a cliff's foot reaching under the wings) keep theirs.
     */
    private static ChunkField.Block wings(ChunkField.Block block, float[][] mouths) {
        List<float[]> lines = new ArrayList<>();
        for (float[] f : mouths) {
            for (int side = -1; side <= 1; side += 2) {
                lines.add(face(f, side, 0));
                lines.add(face(f, side, BoardTunnel.Wings.THICK));
            }
        }
        int before = block.size(), owner = FieldBoard.packed(block.coords());
        block = FieldClip.cutLines(block, lines);
        boolean[] wall = new boolean[block.size()];
        for (int v : block.walls()) { wall[v] = true; }
        float[] x = block.x(), y = block.y(), z = block.z(), parents = block.parents();
        for (int v = before; v < block.size(); v++) {
            if (wall[v]) { continue; }
            float was = z[v];
            for (float[] f : mouths) {
                if (f[13] != owner) { continue; }
                float scale = f[6], dx = (x[v] - f[0]) / scale, dy = (y[v] - f[1]) / scale;
                float lx = (dx * f[4] - dy * f[3]) / f[5], ly = dx * f[3] + dy * f[4];
                if (ly > FACE || ly < -f[10]) { continue; }
                float outer = f[8] + (f[9] - f[8]) * Math.clamp(-ly / f[10], 0, 1), ax = Math.abs(lx);
                if (ax < outer - ON) { z[v] = Math.min(z[v], f[2]); }
                else if (ax <= outer + ON) { z[v] = scree(f, x[v], y[v]); }
            }
            if (z[v] != was) {
                block.bounds()[2] = Math.min(block.bounds()[2], z[v]);
                block.bounds()[5] = Math.max(block.bounds()[5], z[v]);
                // Its own parent: the coarser detail, cut along the same face by the same rule, has the floor or the
                // scree there too, and every hex that shares the sample gives it the same.
                parents[v * 6] = Float.NaN;
            }
        }
        return block;
    }

    /**
     * The ground at the containment walls beside the roads (FieldBoard#walls: today's panels over the field's own
     * ground): cut along each panel's back and front (FieldClip#cutLines), this hex's panels and its neighbours', so
     * the hexes sharing an edge cut it alike. The cut's points come to the soil's line on the panel's back (straight
     * between the field's heights behind its two ends, FieldRoads.Walls) and to the road on its front, each its own
     * parent: the soil ends at the wall, and the step down to the road stands inside the panel. Every other sample
     * within a coarser cell of a panel takes as its parent the nearest point of the coarser detail's own block (cut
     * alike; {@code coarser}, null for the coarsest), so the morph ends on the coarser ground beside the wall.
     */
    private static ChunkField.Block contained(FieldBoard board, ChunkField.Block block,
          Function<Coords, FieldBoard.Shares> shares, ChunkField.Block coarser) {
        List<float[]> panels = new ArrayList<>();
        for (int d = -1; d < 6; d++) {
            panels.addAll(board.walls(d < 0 ? block.coords() : block.coords().translated(d), shares));
        }
        if (panels.isEmpty()) { return block; }
        // A cut runs on through the joint between two panels.
        float joint = BoardRelief.metres(.03f), on = BoardRelief.metres(.02f);
        Coords h = block.coords();
        List<float[]> lines = new ArrayList<>();
        for (float[] p : panels) {
            lines.add(side(p, 0, joint));
            lines.add(side(p, p[6], joint));
        }
        int before = block.size();
        block = FieldClip.cutLines(block, lines);
        boolean[] wall = new boolean[block.size()];
        for (int v : block.walls()) { wall[v] = true; }
        float[] x = block.x(), y = block.y(), z = block.z(), parents = block.parents();
        for (int v = before; v < block.size(); v++) {
            if (wall[v]) { continue; }
            // Where two panels meet or overlap, every hex takes the same one's line: the first by its place.
            float[] first = null;
            float to = Float.NaN;
            for (float[] p : panels) {
                float ex = p[2] - p[0], ey = p[3] - p[1], length2 = ex * ex + ey * ey;
                float length = (float) Math.sqrt(length2), t = ((x[v] - p[0]) * ex + (y[v] - p[1]) * ey) / length2;
                if (t * length < -joint - on || (t - 1) * length > joint + on) { continue; }
                float off = (x[v] - p[0]) * p[4] + (y[v] - p[1]) * p[5], u = Math.clamp(t, 0, 1);
                boolean back = Math.abs(off) <= on;
                if (!back && Math.abs(off - p[6]) > on) { continue; }
                if (first != null && (p[0] > first[0] || p[0] == first[0] && p[1] >= first[1])) { continue; }
                first = p;
                // The back line closes at the wall's top: no soil stands over it.
                to = back ? Math.min(p[9] + (p[10] - p[9]) * u, p[11] + (p[12] - p[11]) * u) : p[7] + (p[8] - p[7]) * u;
            }
            if (first == null) { continue; }
            if (Float.isFinite(to)) { z[v] = to; }
            block.bounds()[2] = Math.min(block.bounds()[2], z[v]);
            block.bounds()[5] = Math.max(block.bounds()[5], z[v]);
            // Its own parent: every detail cuts along the same panels and gives the point the same height.
            parents[v * 6] = Float.NaN;
        }
        // The cut's back line and front: the soil behind the wall is earth, as the lattice's (#stepBack); a cliff keeps
        // its face.
        for (int v = before; v < block.size(); v++) {
            if (!wall[v] && Float.isNaN(parents[v * 6]) && block.role()[v] < 2 && block.rock()[v] < .5f) {
                block.rock()[v] = 0;
            }
        }
        if (coarser != null) {
            // A coarser triangle the cut passes through reaches a coarser cell from it.
            int rings = (int) Math.round((Math.sqrt(12.0 * (coarser.lattice() - 1) + 9) - 3) / 6);
            float cell = board.width / 2 / Math.max(1, rings);
            float[] rim = new float[12];
            for (int e = 0; e < 6; e++) {
                Vector3 corner = BoardGeometry.corner(h, 0, e);
                rim[2 * e] = corner.x;
                rim[2 * e + 1] = corner.y;
            }
            reparent(block, coarser, (px, py, pz) -> beside(panels, px, py, cell),
                  (px, py, pz) -> beside(panels, px, py, 2 * cell), rim);
        }
        return block;
    }

    /**
     * Whether a containment wall stands within two hexes of h: only there can a sample of h's block step back
     * (#stepBack, the panels round the hex the sample lies in).
     */
    static boolean nearWalls(FieldBoard board, Coords h, Function<Coords, FieldBoard.Shares> shares) {
        for (int d = -1; d < 6; d++) {
            Coords n = d < 0 ? h : h.translated(d);
            for (int e = -1; e < 6; e++) {
                if (!board.walls(e < 0 ? n : n.translated(e), shares).isEmpty()) { return true; }
            }
        }
        return false;
    }

    /**
     * A lattice sample at (x, y) beside a containment wall, from the field's {@code out} (z, role, rock), as hex h's
     * block asks it before it merges level ground (ChunkField): the panels of the hex the point lies in and its
     * neighbours decide, so the hexes sharing a sample give it one height. On the road's side of every wall round it,
     * reserved ground is its road's surface (the bank leaves it as high as a step's land where that stands higher than
     * it cuts; it is painted as road), but ground standing higher over it than a step a wall retains keeps its height:
     * the road runs under a cliff there (a ground tunnel's portal), whose wall closes down to it. Behind a wall the
     * land steps back from its top at the bank's slope, as today's bank retains it (BoardRoadBank#height), fully where
     * it stands up to half a level above the top, fading out by a level; the soil it leaves is earth, a bank's face. A
     * cliff keeps its face.
     */
    static void stepBack(FieldBoard board, Function<Coords, FieldBoard.Shares> shares, Coords h, float x, float y,
          float[] out) {
        if (out[2] >= .5f) { return; }
        Coords here = hexAt(h, x, y);
        float on = BoardRelief.metres(.02f), earth = BoardRelief.metres(.1f), level = BoardGeometry.level();
        // A point this far past a panel's end still lies behind it: the gap to the next panel of the run.
        float run = BoardRelief.metres(1), z = out[0], lowered = z;
        boolean any = false, behind = false;
        for (int d = -1; d < 6; d++) {
            for (float[] p : board.walls(d < 0 ? here : here.translated(d), shares)) {
                any = true;
                float ex = p[2] - p[0], ey = p[3] - p[1], length2 = ex * ex + ey * ey;
                float length = (float) Math.sqrt(length2);
                float t = ((x - p[0]) * ex + (y - p[1]) * ey) / length2, u = Math.clamp(t, 0, 1);
                if ((x - p[0]) * p[4] + (y - p[1]) * p[5] > -on) { continue; }
                behind |= t * length > -run && (t - 1) * length < run;
                float top = p[11] + (p[12] - p[11]) * u;
                if (!Float.isFinite(top)) { continue; }
                float cap = top + BoardRoadBank.SLOPE * (float) Math.hypot(p[0] + ex * u - x, p[1] + ey * u - y);
                float fade = 1 - BoardRelief.smooth((z - top - .5f * level) / (.5f * level));
                lowered = Math.min(lowered, z + (Math.min(z, cap) - z) * fade);
            }
        }
        if (!any) { return; }
        float road = behind ? Float.NaN : board.roadSurface(here, x, y);
        // A step taller than BoardRoadBank#WALLED_SLOPE recedes as rock, not cut down to the road.
        if (z - road > BoardRoadBank.WALLED_SLOPE * level) { return; }
        if (Float.isFinite(road) && Math.abs(z - road) > earth) {
            out[0] = road;
            out[1] = 0;
            return;
        }
        if (z - lowered > earth) {
            out[1] = Math.max(out[1], 1);
            out[2] = 0;
        }
        out[0] = lowered;
    }

    /**
     * The hex a point lies in, among h and its neighbours: on a border, the first by FieldBoard#packed of those holding
     * it, so every hex sharing the point names the same one; h where none does.
     */
    private static Coords hexAt(Coords h, float x, float y) {
        Coords best = null;
        for (int d = -1; d < 6; d++) {
            Coords c = d < 0 ? h : h.translated(d);
            if (BoardGeometry.contains(c, x, y) && (best == null || FieldBoard.packed(c) < FieldBoard.packed(best))) {
                best = c;
            }
        }
        return best == null ? h : best;
    }

    /** Whether (x, y) lies within {@code reach} of a containment wall's panel, in plan. */
    private static boolean beside(List<float[]> panels, float x, float y, float reach) {
        for (float[] p : panels) {
            float ex = p[2] - p[0], ey = p[3] - p[1];
            float t = Math.clamp(((x - p[0]) * ex + (y - p[1]) * ey) / (ex * ex + ey * ey), 0, 1);
            float dx = p[0] + ex * t - x, dy = p[1] + ey * t - y;
            if (dx * dx + dy * dy <= reach * reach) { return true; }
        }
        return false;
    }

    /**
     * One side of a containment wall's panel (FieldRoads#walls), {@code in} from its back towards its face, as
     * FieldClip#cutLines' line: its vertical plane, then two planes bounding its stretch, {@code reach} past its ends.
     */
    private static float[] side(float[] p, float in, float reach) {
        float ex = p[2] - p[0], ey = p[3] - p[1], length = (float) Math.hypot(ex, ey);
        ex /= length;
        ey /= length;
        float ax = p[0] + p[4] * in, ay = p[1] + p[5] * in;
        return new float[] { p[4], p[5], 0, ax, ay, 0, -ex, -ey, 0, ax - ex * reach, ay - ey * reach, 0,
              ex, ey, 0, ax + ex * (length + reach), ay + ey * (length + reach), 0 };
    }

    /** How far behind the opening the wing walls start (mm-data road_tunnel.py), and the width a cut point lies in. */
    private static final float FACE = .3f, ON = .05f;

    /**
     * One face of a wing wall (side -1 or 1 of a mouth's frame, {@code in} pixels in from its outer face) as
     * FieldClip#cutLines' line: its vertical plane, then two planes bounding its stretch to the wall's length.
     */
    private static float[] face(float[] f, int side, float in) {
        float slope = (f[9] - f[8]) / f[10];
        // In the frame: side * x + slope * y = root - in along the face; y within -length and FACE.
        float[][] local = { { side, slope, f[8] - in }, { 0, 1, FACE }, { 0, -1, f[10] } };
        float[] planes = new float[18];
        for (int i = 0; i < 3; i++) {
            float nx = local[i][0], ny = local[i][1], d = local[i][2];
            float lx = nx != 0 ? d / nx : 0, ly = nx != 0 ? 0 : d / ny;
            planes[i * 6] = nx * f[4] / f[5] + ny * f[3];
            planes[i * 6 + 1] = -nx * f[3] / f[5] + ny * f[4];
            planes[i * 6 + 3] = f[0] + (lx * f[5] * f[4] + ly * f[3]) * f[6];
            planes[i * 6 + 4] = f[1] + (-lx * f[5] * f[3] + ly * f[4]) * f[6];
            planes[i * 6 + 5] = f[2];
        }
        return planes;
    }


    /**
     * The headwall's cap's half width (mm-data road_tunnel.py); how steeply the scree falls where it ends over ground
     * walls may stand on, and inside the wing walls, over the approach.
     */
    private static final float CAP_HALF = BoardTunnel.SHELL_HALF + .3f, STEEP = 2, DROP = 20;

    /**
     * The scree's height over the opening at a point of a mouth's frame (tile pixels), NaN where none lies: rising at
     * the angle of repose behind the headwall's back and falling at it beside the cap; in front of the face only
     * outside the wing walls, which retain it at their tops, rising from them at repose, and keep it at least that
     * high for a short run-out (#RUNOUT) before it falls at repose: the rock closes onto the wings, as today's
     * relief onto its short ones, and no wing stands clear of it.
     */
    private static float rise(float[] f, float lx, float ly) {
        float ax = Math.abs(lx), side = Math.max(0, ax - CAP_HALF);
        float h = BoardTunnel.HEADWALL + (ly - BoardTunnel.HEADWALL_BACK - side) / REPOSE;
        if (ly < BoardTunnel.HEADWALL_BACK) {
            // The wing (frame floats 8 to 12: root, end, length, high, low), as BoardTunnel.Wings reads it.
            float t = Math.clamp(-ly / f[10], 0, 1), line = f[8] + (f[9] - f[8]) * t, top = f[11] + (f[12] - f[11]) * t;
            float out = ax - line, ahead = Math.max(0, -ly - f[10]);
            // Outside the wing walls it rises from their tops; inside them, over the approach, it drops off at once.
            float retained = top + (out >= 0 ? out / REPOSE : out * DROP) - ahead / REPOSE;
            // Between the face and the headwall's back the retained scree climbs to the slope behind.
            float w = Math.clamp(ly / BoardTunnel.HEADWALL_BACK, 0, 1);
            h = Math.min(h, retained + w * Math.max(0, h - retained));
            // The wing's top held for the run-out outside it; over the approach it drops off as before.
            float held = top + Math.min(0, out) * DROP - (Math.max(0, out - RUNOUT) + ahead) / REPOSE;
            h = Math.max(h, held);
        }
        // Inside the shell's sides and behind the cliff's face it falls away at once too: continuous everywhere, so
        // a wall whose foot it lifts keeps a foot each detail can follow.
        if (ly > -1) { h -= Math.max(0, BoardTunnel.SHELL_HALF - ax) * STEEP; }
        return h - Math.max(0, ly - BoardTunnel.SETBACK) * STEEP;
    }

    /** The footprints as a list, none for null. */
    private static List<float[]> footprints(float[][] footprints) {
        return footprints == null ? List.of() : List.of(footprints);
    }

    /** Whether (x, y) lies within a footprint, a sample within a millimetre of its side (FieldClip's cut) included. */
    private static boolean inside(List<float[]> footprints, float x, float y, float margin) {
        float snap = BoardRelief.metres(.001f) + margin;
        for (float[] r : footprints) {
            boolean in = true;
            for (int p = 0; p < r.length && in; p += 6) {
                in = r[p] * (x - r[p + 3]) + r[p + 1] * (y - r[p + 4]) <= snap * (float) Math.hypot(r[p], r[p + 1]);
            }
            if (in) { return true; }
        }
        return false;
    }

    /**
     * The vertical prism over the counter-clockwise triangle at {@code i}: per side, its outward normal (on its right)
     * and its first corner, as BoardGroundCut#dig's sides.
     */
    private static float[] prism(float[] cut, int i) {
        float[] planes = new float[18];
        for (int k = 0; k < 3; k++) {
            float ax = cut[i + k * 2], ay = cut[i + k * 2 + 1];
            float bx = cut[i + (k + 1) % 3 * 2], by = cut[i + (k + 1) % 3 * 2 + 1];
            planes[k * 6] = by - ay;
            planes[k * 6 + 1] = ax - bx;
            planes[k * 6 + 3] = ax;
            planes[k * 6 + 4] = ay;
        }
        return planes;
    }
}
