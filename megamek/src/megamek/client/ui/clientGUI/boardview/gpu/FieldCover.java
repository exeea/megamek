/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import megamek.common.board.Coords;

/**
 * The cover weights on the height field's vertices: for a hex with a cover border ({@link BoardSurfaceBlend#boundary})
 * what each sample of its {@link ChunkField.Block} is covered with, from the evaluator of today's blended ground
 * ({@link BoardSurfaceBlend.Sampler}), which nothing else asks for the field. Plain arrays.
 * An entry is a cover's id (0-13, {@link BoardSurfaceBlend#FAMILIES}), or that id plus {@link #FOOT} for a wall's share
 * of the ground below it, which the pixel cuts back by height. Lava's heat is in the weights, as in today's blend.
 */
final class FieldCover {
    /** The entries a hex's weights and a draw's palette hold. */
    static final int ENTRIES = 4;
    /** Added to a cover's id where the entry is a wall's foot share of it. */
    static final int FOOT = 16;
    private static final int IDS = FOOT + BoardSurfaceBlend.FAMILIES;
    /** Floats an evaluation: a weight per id, the authored transition's share. */
    private static final int STRIDE = IDS + 1;
    /** A weight from here on is the sample's one cover alone: its bytes are 255 on that entry. */
    private static final float ALONE = 1 - 1.5f / 255;
    /** A weight up to here does not make its cover an entry of the hex. */
    private static final float TRACE = 2.5f / 255;

    /**
     * A boundary hex's covers on its block's samples. {@code covers}: its entries, ascending, at most four.
     * {@code weights}: eight bytes a sample, its weight per entry (their sum 255), then its coarse parent's.
     * {@code cut}: four bytes a sample: the authored transition's share, 0, then its coarse parent's two.
     */
    record Hex(int[] covers, byte[] weights, byte[] cut) { }

    /** A chunk's draws: each hex's draw (in the order its entries were given) and each draw's palette, ascending. */
    record Draws(int[] draw, int[][] palettes) { }

    private final BoardSurfaceBlend.Sampler sampler;
    /** The hex's own cover. */
    private final int own;
    /** Whether the hex has a cover border; without one only scree makes it a cover hex. */
    private final boolean border;
    /** A water hex: a cover hex only where today's evaluator puts another cover on its ground (its banks). */
    private final boolean water;
    private final BoardScene scene;
    private final Coords coords;
    /** Whether a water hex's step took the cover of a dry wall it meets (wall): then it is a cover hex. */
    private boolean walled;
    /** Whether a lattice sample took scree. */
    private boolean scree;
    private float[] raw = new float[STRIDE * 64];

    private FieldCover(BoardScene scene, BoardScene.Tile tile, boolean border) {
        this.scene = scene;
        coords = tile.coords();
        this.border = border;
        water = tile.liquid().present();
        sampler = new BoardSurfaceBlend.Sampler(scene, tile);
        own = BoardSurfaceBlend.family(tile);
    }

    /**
     * The covers of the hex at {@code hex}; null where it has no border (its one cover is its family) and stands below no
     * neighbour of another family (whose cliff could shed scree on it), and for ice, which draws its own. A water
     * hex's ground, molten rock's too, is evaluated as land's is: today's evaluator gives its banks the cover of the
     * higher land they belong to (Sampler#sampleWater), its bed its bed's; where all of it comes out its own cover it
     * stays plain.
     */
    static FieldCover of(BoardScene scene, Coords hex) {
        BoardScene.Tile tile = scene.tile(hex);
        if (tile.liquid().present()) {
            return tile.frozen() ? null : new FieldCover(scene, tile, false);
        }
        boolean border = BoardSurfaceBlend.boundary(scene, tile);
        return border || belowOther(scene, tile) ? new FieldCover(scene, tile, border) : null;
    }

    private static boolean belowOther(BoardScene scene, BoardScene.Tile tile) {
        int family = BoardSurfaceBlend.family(tile);
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile next = scene.neighbor(tile, direction);
            if (next != null && next.elevation() > tile.elevation() && BoardSurfaceBlend.family(next) != family) { return true; }
        }
        return false;
    }

    /** A finished block's covers (a block whose samples moved after it was built asks again). */
    static Hex of(BoardScene scene, ChunkField.Block block, int segments) {
        FieldCover cover = of(scene, block.coords());
        if (cover == null) { return null; }
        cover.lattice(block.x(), block.y(), block.z(), block.role(), block.rock(), block.foot(), block.stepTop(),
              block.lattice());
        return cover.finish(block.x(), block.y(), block.z(), block.role(), block.faceRim(), block.lattice(), block.size(),
              segments, block.parents());
    }

    /**
     * Evaluates the lattice samples, the first {@code lattice} of the arrays, at their final places; {@code role},
     * {@code rock}, {@code foot} and {@code stepTop} as the block's (stepTop null for a block without step tops).
     */
    void lattice(float[] x, float[] y, float[] z, float[] role, float[] rock, float[] foot, int[] stepTop, int lattice) {
        for (int v = 0; v < lattice; v++) {
            ground(v, x[v], y[v], z[v]);
            if (stepTop != null) { scree(v, x[v], y[v], z[v], role[v], rock[v], foot[v], stepTop[v]); }
            // A stream hex's bank slope takes the gorge's rock (cliffs, 2026-11-20; the user's choice).
            if (stepTop != null) { gorge(v, role[v], stepTop[v]); }
        }
    }

    /**
     * Scree takes the family of the cliff it fell from (the user's rule, 2026-11-16): a talus sample (a step's face
     * role, not a wall) under a cliff whose upper hex has another family carries that family, its share the apron's
     * profile (FieldBoard#height: whole at the cliff's row, gone at twice the transition out) times how much of a
     * cliff the step is.
     */
    private void scree(int v, float x, float y, float z, float role, float rock, float foot, int top) {
        Coords upper = FieldBoard.unpacked(top);
        // A water hex's bank face (rock 0: no wall) belongs to the hex above it as a cliff's does: its family whole at
        // the face, so the upper hex's slope reaches the water and the river's gravel keeps to the flat wet margin.
        if (water) { rock = 1; }
        if (role < .5f || role > 1.5f || rock <= 0 || upper == null) { return; }
        BoardScene.Tile cliff = scene.tile(upper);
        int family = cliff == null ? own : BoardSurfaceBlend.family(cliff);
        float reach = foot / BoardRelief.metres(2 * BoardRelief.TRANSITION);
        float share = family == own || reach >= 1 ? 0 : rock * (1 - (float) Math.sqrt(reach));
        if (share <= TRACE) { return; }
        int o = v * STRIDE;
        // Beside lava the fallen cover melts as the evaluator's does (BoardSurfaceBlend.Sampler#scorched): snow to its
        // rock, turf to its soil.
        BoardSurfaceBlend.Cover fallen = sampler.scorched(BoardSurfaceBlend.solid(family), x, y, z);
        for (int f = 0; f < BoardSurfaceBlend.FAMILIES; f++) { raw[o + f] = raw[o + f] * (1 - share) + share * fallen.weight(f); }
        scree = true;
    }

    /**
     * A step-face sample under a stream hex (a water hex with no fall: FieldBoard#bankSlope's slope, and the gorge's
     * side down to today's waterline, on the stream hex's own strip and the pool's bank too) takes the rock of the
     * gorge (BoardRelief#gorgeFamily: the family of the stream hex's highest dry neighbour of another family), whole,
     * and so does a ground sample FieldBoard#height marked beside that waterline (FieldBoard#gorgeGround): no other
     * cover stands between that rock and the water.
     */
    private void gorge(int v, float role, int top) {
        boolean ground = top < -1;
        Coords upper = FieldBoard.unpacked(ground ? FieldBoard.gorgeGround(top) : top);
        if (upper == null || (ground ? role >= .5f : role < .5f || role > 1.5f)) { return; }
        BoardScene.Tile stream = scene.tile(upper);
        if (!FieldBoard.streamHex(scene, stream)) { return; }
        int family = BoardRelief.gorgeFamily(scene, stream, own);
        if (family == own) { return; }
        int o = v * STRIDE;
        for (int f = 0; f < BoardSurfaceBlend.FAMILIES; f++) { raw[o + f] = 0; }
        raw[o + family] = 1;
        scree = true;
    }

    /** Whether lattice samples a and c carry one and the same cover alone and equal cut values: a level triangle
     *  whose samples all do can stay whole. */
    boolean alike(int a, int c) {
        int one = alone(a);
        return one >= 0 && one == alone(c) && cutByte(raw[a * STRIDE + IDS]) == cutByte(raw[c * STRIDE + IDS]);
    }

    /** Whether any of the first {@code lattice} samples carries more than the hex's own cover. */
    private boolean mixed(int lattice) {
        for (int v = 0; v < lattice; v++) { if (!plain(v)) { return true; } }
        return false;
    }

    /** Whether lattice sample v carries the hex's own cover alone, out of an authored transition: a level triangle of
     *  such samples is the hex's plain top. */
    boolean plain(int v) { return alone(v) == own && cutByte(raw[v * STRIDE + IDS]) == 0; }

    /**
     * The hex's entries and every sample's bytes, once the samples after its lattice (from {@code lattice} to
     * {@code size}: its walls', and what a clip added) stand: {@code role}, {@code above} (a wall sample's height above
     * its wall's foot in metres) and {@code parents} as {@link ChunkField.Block#role()},
     * {@link ChunkField.Block#faceRim()} and {@link ChunkField.Block#parents()}, {@code segments} the lattice's a spoke.
     */
    Hex finish(float[] x, float[] y, float[] z, float[] role, float[] above, int lattice, int size, int segments,
          float[] parents) {
        float metre = BoardRelief.metres(1);
        for (int v = lattice; v < size; v++) { sample(v, role[v] > 1.5f, x[v], y[v], z[v], above[v]); }
        // Without a border, scree or a step that took a dry wall's cover, the hex's one cover is its family: a plain
        // hex after all; so is a water hex whose every lattice sample comes out its own cover alone.
        if (!border && !scree && !walled && !(water && mixed(lattice))) { return null; }
        int[] covers = entries(size);
        byte[] weights = new byte[size * 8], cut = new byte[size * 4];
        for (int v = 0; v < size; v++) { write(v, covers, weights, v * 8, cut, v * 4); }
        // A sample that is its own parent keeps its values at every morph weight.
        for (int v = 0; v < size; v++) {
            System.arraycopy(weights, v * 8, weights, v * 8 + 4, 4);
            System.arraycopy(cut, v * 4, cut, v * 4 + 2, 2);
        }
        // An odd lattice sample halves a coarse edge (ChunkField.Builder#parents): its parent has the mean of the
        // edge's ends, which the coarser mesh shows there.
        float[] mean = new float[STRIDE];
        for (int k = 0; segments >= 4 && k < 6; k++) {
            for (int i = 1; i <= segments; i++) {
                for (int j = 0; j < i; j++) {
                    if (i % 2 == 0 && j % 2 == 0) { continue; }
                    int a, c;
                    if (i % 2 == 1 && j % 2 == 1) { a = ChunkField.vertex(k, i - 1, j - 1); c = ChunkField.vertex(k, i + 1, j + 1); }
                    else if (i % 2 == 1) { a = ChunkField.vertex(k, i - 1, j); c = ChunkField.vertex(k, i + 1, j); }
                    else { a = ChunkField.vertex(k, i, j - 1); c = ChunkField.vertex(k, i, j + 1); }
                    int v = ChunkField.vertex(k, i, j);
                    Arrays.fill(mean, 0);
                    for (int e = 0; e < covers.length; e++) {
                        mean[covers[e]] = ((weights[a * 8 + e] & 255) + (weights[c * 8 + e] & 255)) / 510f;
                    }
                    mean[IDS] = ((cut[a * 4] & 255) + (cut[c * 4] & 255)) / 510f;
                    quantise(mean, 0, covers, weights, v * 8 + 4, cut, v * 4 + 2);
                }
            }
        }
        // A wall sample's parent is the coarser wall's point at its place: the evaluator's answer there, not the
        // coarser wall's own weights at that point, which differ from it by what its coarser samples miss.
        for (int v = lattice; v < size; v++) {
            int p = v * 6;
            if (Float.isNaN(parents[p])) { continue; }
            sample(size, role[v] > 1.5f, x[v] + parents[p], y[v] + parents[p + 1], z[v] + parents[p + 2],
                  above[v] + parents[p + 2] / metre);
            write(size, covers, weights, v * 8 + 4, cut, v * 4 + 2);
        }
        return new Hex(covers, weights, cut);
    }

    /**
     * A chunk's draws by the split rule: its hexes in the order given (a hex without a border gives its family), each
     * into the first draw whose palette still holds its entries within four.
     */
    static Draws draws(int[][] entries) {
        List<int[]> palettes = new ArrayList<>();
        int[] draw = new int[entries.length];
        for (int h = 0; h < entries.length; h++) {
            int[] joined = null;
            int d = 0;
            for (; d < palettes.size(); d++) {
                joined = union(palettes.get(d), entries[h]);
                if (joined.length <= ENTRIES) { break; }
            }
            if (d == palettes.size()) { palettes.add(entries[h].clone()); } else { palettes.set(d, joined); }
            draw[h] = d;
        }
        return new Draws(draw, palettes.toArray(int[][]::new));
    }

    /** The entry of {@code palette} holding {@code cover}. */
    static int slot(int[] palette, int cover) {
        int slot = Arrays.binarySearch(palette, cover);
        if (slot < 0) { throw new IllegalArgumentException("Cover " + cover + " is not in " + Arrays.toString(palette)); }
        return slot;
    }

    private static int[] union(int[] a, int[] c) {
        int[] joined = Arrays.copyOf(a, a.length + c.length);
        int count = a.length;
        for (int id : c) { if (Arrays.binarySearch(a, id) < 0) { joined[count++] = id; } }
        joined = Arrays.copyOf(joined, count);
        Arrays.sort(joined);
        return joined;
    }

    private float[] slot(int v) {
        if ((v + 1) * STRIDE > raw.length) { raw = Arrays.copyOf(raw, Math.max(raw.length * 2, (v + 1) * STRIDE)); }
        Arrays.fill(raw, v * STRIDE, (v + 1) * STRIDE, 0);
        return raw;
    }

    private void sample(int v, boolean wall, float x, float y, float z, float above) {
        if (wall) { wall(v, x, y, z, above); } else { ground(v, x, y, z); }
    }

    private void ground(int v, float x, float y, float z) {
        float[] values = slot(v);
        BoardSurfaceBlend.Cover cover = sampler.sample(x, y, z);
        for (int f = 0; f < BoardSurfaceBlend.FAMILIES; f++) { values[v * STRIDE + f] = cover.weight(f); }
        values[v * STRIDE + IDS] = cover.interpolation();
    }

    /**
     * A wall sample {@code above} metres over its wall's foot. The pixel cuts the foot share back by its height above
     * that foot (footReach); the evaluator measures from the ground below each cover stands on, which can lie lower or
     * higher. The share is scaled so that the pixel's cut leaves at the sample what the evaluator's does, and what the
     * evaluator keeps beyond it stays uncut.
     */
    private void wall(int v, float x, float y, float z, float above) {
        float[] values = slot(v);
        BoardSurfaceBlend.Footing footing = sampler.sampleFoot(x, y, z);
        float metre = BoardRelief.metres(1);
        float reach = BoardSurfaceBlend.footReach(Math.max(0, above), BoardSurfaceBlend.footTongue(x / metre, y / metre));
        float left = 0;
        for (int f = 0; f < BoardSurfaceBlend.FAMILIES; f++) {
            float all = footing.cover().weight(f), foot = Math.min(footing.foot().weight(f), all);
            float kept = Math.min(footing.kept().weight(f), foot), cut = reach <= .001f ? 0 : Math.min(foot, kept / reach);
            values[v * STRIDE + f] = all - foot + kept - cut * reach;
            values[v * STRIDE + FOOT + f] = cut;
            left += all - foot + kept;
        }
        if (left <= .0001f) {
            // Nothing but the ground below stood here and its height cut all of it: the evaluator then answers the
            // column's own cover.
            BoardSurfaceBlend.Cover column = sampler.sampleCliff(x, y, z);
            for (int f = 0; f < BoardSurfaceBlend.FAMILIES; f++) {
                values[v * STRIDE + f] = column.weight(f);
                values[v * STRIDE + FOOT + f] = 0;
            }
        }
        values[v * STRIDE + IDS] = footing.cover().interpolation();
        // A pool's rock step takes the rock of the dry wall it meets (BoardRelief#poolStepFamily), whole; its foot
        // shares stay.
        int step = water ? BoardRelief.poolStepFamily(scene, coords, x, y) : own;
        if (step != own) {
            float total = 0;
            for (int f = 0; f < BoardSurfaceBlend.FAMILIES; f++) {
                total += values[v * STRIDE + f];
                values[v * STRIDE + f] = 0;
            }
            values[v * STRIDE + step] = total;
            walled = true;
        }
    }

    private int alone(int v) {
        for (int f = 0; f < BoardSurfaceBlend.FAMILIES; f++) { if (raw[v * STRIDE + f] >= ALONE) { return f; } }
        return -1;
    }

    /** The hex's entries, ascending: the covers above a trace on a sample; of more than four the four strongest. */
    private int[] entries(int size) {
        float[] strength = new float[IDS];
        boolean[] present = new boolean[IDS];
        for (int v = 0; v < size; v++) {
            for (int id = 0; id < IDS; id++) {
                float w = raw[v * STRIDE + id];
                if (w <= TRACE) { continue; }
                present[id] = true;
                strength[id] += w;
            }
        }
        int count = 0;
        for (boolean one : present) { if (one) { count++; } }
        for (; count > ENTRIES; count--) {
            int weakest = -1;
            for (int id = 0; id < IDS; id++) {
                // A molten bank glows beside its pool: a weaker cover goes before it.
                if (present[id] && id != BoardSurfaceBlend.BANK && (weakest < 0 || strength[id] < strength[weakest])) {
                    weakest = id;
                }
            }
            present[weakest] = false;
        }
        int[] covers = new int[count];
        for (int id = 0, e = 0; id < IDS; id++) { if (present[id]) { covers[e++] = id; } }
        return covers;
    }

    private void write(int v, int[] covers, byte[] weights, int w, byte[] cut, int c) {
        quantise(raw, v * STRIDE, covers, weights, w, cut, c);
    }

    /**
     * One evaluation (at {@code o} of {@code values}) as bytes: its weights on the hex's entries, the rest folded into
     * its strongest entry, their sum 255 (largest remainder; a cover alone exactly 255), and its cut value.
     */
    private static void quantise(float[] values, int o, int[] covers, byte[] weights, int w, byte[] cut, int c) {
        float[] share = new float[ENTRIES];
        float kept = 0, total = 0;
        int strongest = 0;
        for (int id = 0; id < IDS; id++) { total += values[o + id]; }
        for (int e = 0; e < covers.length; e++) {
            share[e] = values[o + covers[e]];
            kept += share[e];
            if (share[e] > share[strongest]) { strongest = e; }
        }
        share[strongest] += total - kept;
        int[] bytes = new int[ENTRIES];
        if (total <= 0 || share[strongest] >= ALONE * total) {
            bytes[strongest] = 255;
        } else {
            int sum = 0;
            for (int e = 0; e < covers.length; e++) {
                share[e] *= 255 / total;
                bytes[e] = (int) share[e];
                sum += bytes[e];
            }
            for (; sum < 255; sum++) {
                int most = 0;
                for (int e = 1; e < covers.length; e++) { if (share[e] - bytes[e] > share[most] - bytes[most]) { most = e; } }
                bytes[most]++;
            }
        }
        for (int e = 0; e < ENTRIES; e++) { weights[w + e] = (byte) bytes[e]; }
        cut[c] = (byte) cutByte(values[o + IDS]);
        cut[c + 1] = 0;
    }

    private static int cutByte(float value) { return Math.round(Math.clamp(value, 0, 1) * 255); }
}
