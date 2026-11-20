/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;

/**
 * A height-field hex's ground mesh ({@link TerrainField}), written on the chunk's preparation worker from its
 * {@link ChunkField.Block}: interleaved vertices in {@link TerrainField#LAYOUT}'s order (position 3, packed data 1,
 * normal 3, uv 2, field role 1, field parent 4) and 16-bit triangle indices, in two groups: the merged level tops,
 * which the ground material cache can draw, and everything else (sloping cover, step faces, walls), drawn directly.
 * The vertex data are today's sculpted contract ({@link GpuTerrain#shadeColor}): a sample in both a top and a step
 * face is written once for each.
 */
final class ChunkMesh {
    /** Floats a vertex; a hex with a cover border has three more (TerrainField#COVER_LAYOUT). */
    static final int FLOATS = 14, COVER_FLOATS = 17;

    private ChunkMesh() { }

    /**
     * {@code bounds}: min x, y, z, max x, y, z of every vertex. {@code palette}: the covers of the hex's draw, whose
     * entries its vertices' weights are for; null for a hex without cover weights.
     */
    record Hex(float[] direct, short[] directIndices, float[] levelTops, short[] levelTopIndices, float[] bounds,
          int[] palette) {
        int floats() { return palette == null ? FLOATS : COVER_FLOATS; }
    }

    /**
     * {@code water}: the block's hex where its ground lies under that hex's water (FieldShore), or the field water a
     * land block's low ground lies under (FieldWater#beside), else null. {@code palette}: the covers of the block's
     * draw ({@link FieldCover#draws}); null where the block has no cover.
     */
    static Hex of(ChunkField.Block block, BoardScene.Tile water, int[] palette) { return of(block, water, null, palette); }

    /**
     * {@link #of(ChunkField.Block, BoardScene.Tile, int[])}, the water's height at each sample from {@code shore}
     * (FieldShore#water: a descending stream's graded surface); its level where shore is null.
     */
    static Hex of(ChunkField.Block block, BoardScene.Tile water, FieldShore shore, int[] palette) {
        // A water hex's ground carries its water everywhere; land beside it only where it lies under the water.
        boolean own = water != null && water.coords().equals(block.coords());
        // Up to the hex's level: the bench and the bank's wet strip above the water (terrain-sculpt.frag's river bank).
        boolean[] wet = water == null || own ? null
              : wet(block, block.ground(), BoardGeometry.waterZ(water) + .99f * BoardGeometry.hexScale());
        Writer direct = new Writer(block, water, shore, wet, palette);
        Writer tops = new Writer(block, own ? water : null, shore, null, palette);
        direct.triangles(block.ground(), false);
        direct.triangles(block.faces(), true);
        direct.triangles(block.walls(), true);
        // Level tops under water carry it too, so they stay off the cached tops' materials.
        (own ? direct : tops).triangles(block.levelTops(), false);
        return new Hex(direct.vertices(), direct.indices(), tops.vertices(), tops.indices(), block.bounds(), palette);
    }

    /**
     * The samples of a land block's ground triangles {@code list} that carry the water beside it, whose surface is
     * {@code surface} high: those below it and the others of their triangles, so a triangle the waterline crosses is
     * wet whole and the change to dry cover lies just above the water.
     */
    private static boolean[] wet(ChunkField.Block block, int[] list, float surface) {
        boolean[] wet = new boolean[block.size()];
        float[] z = block.z();
        for (int i = 0; i < list.length; i += 3) {
            if (z[list[i]] < surface || z[list[i + 1]] < surface || z[list[i + 2]] < surface) {
                wet[list[i]] = wet[list[i + 1]] = wet[list[i + 2]] = true;
            }
        }
        return wet;
    }

    /** Sample {@code v}'s a_fieldParent, four floats into {@code out} from {@code o}; what is draped on it carries the same. */
    static void parent(ChunkField.Block block, int v, float[] out, int o) {
        float[] parents = block.parents();
        int p = v * 6;
        boolean own = Float.isNaN(parents[p]);
        out[o] = own ? 0 : parents[p];
        out[o + 1] = own ? 0 : parents[p + 1];
        out[o + 2] = own ? 0 : parents[p + 2];
        out[o + 3] = (own ? octahedral(block.nx()[v], block.ny()[v], block.nz()[v])
              : octahedral(parents[p + 3], parents[p + 4], parents[p + 5])) + (block.sides()[v] << 20);
    }

    /** A unit normal as two 10-bit octahedral coordinates in one exact integer (field-morph.glsl decodes it). */
    static int octahedral(float x, float y, float z) {
        float sum = Math.abs(x) + Math.abs(y) + Math.abs(z);
        float u = x / sum, v = y / sum;
        if (z < 0) {
            float ou = (1 - Math.abs(v)) * Math.signum(u == 0 ? 1 : u), ov = (1 - Math.abs(u)) * Math.signum(v == 0 ? 1 : v);
            u = ou;
            v = ov;
        }
        int a = Math.round((u * .5f + .5f) * 1023), b = Math.round((v * .5f + .5f) * 1023);
        return a | b << 10;
    }

    /** One group's vertices, each sample written once a kind (top cover or step face). */
    private static final class Writer {
        final ChunkField.Block block;
        final BoardScene.Tile water;
        /** The water's height at a sample (FieldShore#water); null: its level. */
        final FieldShore shore;
        /** Which samples carry the water: null for all (a water hex's own ground). */
        final boolean[] wet;
        final int[] cover, face;
        /** The draw's entry of each of the hex's own entries; null without cover weights. */
        final int[] slots;
        /** Which of the draw's entries are a wall's foot share, a bit each (a_fieldCoverCut's second byte). */
        final int feet;
        final int floats;
        float[] vertices;
        short[] indices = new short[192];
        int count, indexCount;

        Writer(ChunkField.Block block, BoardScene.Tile water, FieldShore shore, boolean[] wet, int[] palette) {
            this.block = block;
            this.water = water;
            this.shore = shore;
            this.wet = wet;
            int bits = 0;
            if (palette == null) {
                slots = null;
            } else {
                int[] covers = block.cover().covers();
                slots = new int[covers.length];
                for (int e = 0; e < covers.length; e++) { slots[e] = FieldCover.slot(palette, covers[e]); }
                for (int e = 0; e < palette.length; e++) { if (palette[e] >= FieldCover.FOOT) { bits |= 1 << e; } }
            }
            feet = bits;
            floats = palette == null ? FLOATS : COVER_FLOATS;
            vertices = new float[floats * 64];
            cover = new int[block.size()];
            face = new int[block.size()];
            Arrays.fill(cover, -1);
            Arrays.fill(face, -1);
        }

        void triangles(int[] list, boolean faces) {
            for (int i = 0; i < list.length; i++) {
                if (indexCount == indices.length) { indices = Arrays.copyOf(indices, indices.length * 2); }
                indices[indexCount++] = (short) vertex(list[i], faces);
            }
        }

        private int vertex(int v, boolean faces) {
            int[] written = faces ? face : cover;
            if (written[v] >= 0) { return written[v]; }
            if ((count + 1) * floats > vertices.length) { vertices = Arrays.copyOf(vertices, vertices.length * 2); }
            int o = count * floats;
            vertices[o] = block.x()[v];
            vertices[o + 1] = block.y()[v];
            vertices[o + 2] = block.z()[v];
            float u, w;
            if (faces) {
                vertices[o + 3] = GpuTerrain.packedShade(BoardRelief.Kind.CLIFF, block.faceOcclusion(v), block.rock()[v], block.bed(v),
                      block.projection()[v]);
                u = block.faceRim()[v];
                w = block.faceFoot()[v];
            } else if (water != null && (wet == null || wet[v])) {
                // A water hex's ground carries its water as today's wet ground does (GpuTerrain#sculptVertex).
                vertices[o + 3] = GpuTerrain.waterColor(block.occlusion(v), shore == null ? BoardGeometry.waterZ(water)
                      : shore.water(water.coords(), block.x()[v], block.y()[v]),
                      GpuTerrain.shoreTint(GpuWaterShader.palette(water.liquid()), block.steps()[v])).toFloatBits();
                u = block.footMetres(v);
                w = GpuTerrain.shoreRim(block.rimMetres(v), water);
            } else {
                vertices[o + 3] = GpuTerrain.packedShade(BoardRelief.Kind.GROUND, block.occlusion(v), block.level(),
                      block.coverTint(v), Float.NaN);
                u = block.rimMetres(v);
                w = block.footMetres(v);
            }
            vertices[o + 4] = block.nx()[v];
            vertices[o + 5] = block.ny()[v];
            vertices[o + 6] = block.nz()[v];
            vertices[o + 7] = u;
            vertices[o + 8] = w;
            // a_fieldRole: step role and rockiness as normalized bytes (the high bytes stay zero: never a NaN pattern).
            int role = Math.round(Math.clamp(faces ? 1 : block.role()[v], 0, 1) * 255);
            int rock = Math.round(Math.clamp(block.rock()[v], 0, 1) * 255);
            vertices[o + 9] = Float.intBitsToFloat(role | rock << 8);
            // a_fieldParent: the coarse parent's offset and normal, octahedral (a sample that is its own parent: no
            // offset, its own normal), and the neighbouring chunk the sample borders.
            parent(block, v, vertices, o + 10);
            if (slots != null) {
                FieldCover.Hex covers = block.cover();
                vertices[o + 14] = weights(covers, v * 8);
                vertices[o + 15] = weights(covers, v * 8 + 4);
                vertices[o + 16] = cuts(covers.cut(), v * 4, feet);
            }
            written[v] = count;
            return count++;
        }

        /**
         * a_fieldCover, a_fieldCoverParent: a sample's weights on the draw's four entries as normalized bytes, their
         * sum 255 (so never a NaN pattern: a top byte of 127 leaves at most 128 below it, an infinity's bits).
         */
        private float weights(FieldCover.Hex covers, int at) {
            int bits = 0;
            for (int e = 0; e < slots.length; e++) { bits |= (covers.weights()[at + e] & 255) << 8 * slots[e]; }
            return Float.intBitsToFloat(bits);
        }

        /**
         * a_fieldCoverCut as normalized bytes: the authored transition's share, the draw's foot entries (a bit each), the
         * coarse parent's share, 0 (so never a NaN pattern).
         */
        private static float cuts(byte[] cut, int at, int feet) {
            return Float.intBitsToFloat(cut[at] & 255 | feet << 8 | (cut[at + 2] & 255) << 16);
        }

        float[] vertices() { return Arrays.copyOf(vertices, count * floats); }

        short[] indices() { return Arrays.copyOf(indices, indexCount); }
    }
}
