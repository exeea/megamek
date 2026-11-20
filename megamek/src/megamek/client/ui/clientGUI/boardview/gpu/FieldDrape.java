/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;

/**
 * What is draped on a height-field hex's ground ({@link TerrainField}: road coats and paint, and whatever else is a
 * lifted copy of its triangles) moves with it: a draped vertex carries the morph parent of the ground under it
 * (a_fieldParent, as {@link ChunkMesh} writes the ground's) and its material is a field material of the hex's chunk
 * ({@link TerrainField#draped}), so field-morph.glsl moves both alike at every weight. Plain arrays over the hex's
 * block, owned by its adapter (BoardSurface#fieldDrape); the index is built on the first question.
 */
final class FieldDrape {
    /** Floats of a_fieldParent. */
    static final int FLOATS = 4;
    private final ChunkField.Block block;
    /** The block's samples by place (the bits of x and y); several where a wall's rows stand over one place. */
    private HashMap<Long, int[]> samples;
    FieldDrape(ChunkField.Block block) { this.block = block; }

    /**
     * a_fieldParent of a draped vertex at (x, y) whose ground stands at {@code z} (the vertex's height less its lift),
     * into {@code out} from {@code o}: that of the block's sample there. A vertex at no sample's place (a tunnel's
     * floor, a slab's side) does not move: no offset, a level normal.
     */
    void parent(float x, float y, float z, float[] out, int o) {
        if (samples == null) { samples = index(); }
        int[] at = samples.get(key(x, y));
        int best = -1;
        float tolerance = .001f * BoardGeometry.hexScale(), distance = tolerance;
        for (int i = 0; at != null && i < at.length; i++) {
            float d = Math.abs(block.z()[at[i]] - z);
            // Of several at one place the first: a top's sample stands before a wall's, whose normal is not the coat's.
            if (d <= tolerance && (best < 0 || d < distance)) {
                best = at[i];
                distance = d;
            }
        }
        if (best < 0) {
            out[o] = out[o + 1] = out[o + 2] = 0;
            out[o + 3] = ChunkMesh.octahedral(0, 0, 1);
            return;
        }
        ChunkMesh.parent(block, best, out, o);
    }

    private HashMap<Long, int[]> index() {
        HashMap<Long, int[]> result = new HashMap<>(block.size() * 2);
        for (int v = 0; v < block.size(); v++) {
            int sample = v;
            result.merge(key(block.x()[v], block.y()[v]), new int[] { v }, (old, one) -> {
                int[] grown = java.util.Arrays.copyOf(old, old.length + 1);
                grown[old.length] = sample;
                return grown;
            });
        }
        return result;
    }

    private static long key(float x, float y) {
        // -0 and 0 are one place.
        return (long) Float.floatToIntBits(x + 0f) << 32 | Float.floatToIntBits(y + 0f) & 0xffffffffL;
    }
}
