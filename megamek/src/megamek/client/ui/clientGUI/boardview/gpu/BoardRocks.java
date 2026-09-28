/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Closed terrain rocks for rough ground, rims, slopes and cliffs, with shared geology selection. */
final class BoardRocks {
    static final int BLOCKS = 8;
    static final int BOULDERS = 8;
    private static volatile Map<String, List<BoardShape>> rocks = loadRocks();

    private BoardRocks() { }

    /** Publish complete immutable geometry; terrain workers never observe a partially reloaded kit. */
    static void reload() { rocks = loadRocks(); }

    /** One geology rule for rough, rim, slope, cliff and scattered rocks. */
    static BoardShape rock(BoardScene.Surface surface, int variant, TerrainLod detail) {
        return rock(blocks(surface), variant, detail);
    }

    static boolean blocks(BoardScene.Surface surface) {
        return surface == BoardScene.Surface.SAND || surface == BoardScene.Surface.CONCRETE;
    }

    static BoardShape rock(boolean block, int variant) {
        return rock(block, variant, TerrainLod.FULL);
    }

    static BoardShape rock(boolean block, int variant, TerrainLod detail) {
        // Terrain's first two sampling levels share rock LOD0, preserving the existing transition distances.
        int level = Math.max(0, detail.ordinal() - 1);
        return rocks.get(name(block, variant)).get(level);
    }

    static String name(boolean block, int variant) {
        return (block ? "block-" : "boulder-") + Math.floorMod(variant, block ? BLOCKS : BOULDERS);
    }

    private static Map<String, List<BoardShape>> loadRocks() {
        Map<String, List<BoardShape>> rocks = new HashMap<>();
        for (boolean block : new boolean[] { true, false }) {
            for (int variant = 0; variant < (block ? BLOCKS : BOULDERS); variant++) {
                String shape = name(block, variant);
                Map<String, BoardShape> levels = BoardShape.loadKit("rocks/" + shape);
                for (String node : levels.keySet()) {
                    if (!node.matches(shape + "-lod[0-2]")) {
                        throw new IllegalArgumentException("Unexpected mesh in rock " + shape + ": " + node);
                    }
                }
                rocks.put(shape, MeshLod.load(shape, 3, levels::get));
            }
        }
        return Map.copyOf(rocks);
    }
}
