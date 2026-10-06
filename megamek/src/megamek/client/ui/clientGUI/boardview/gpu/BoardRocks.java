/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Closed terrain rocks for rough ground, rims, slopes and cliffs, with shared geology selection. */
final class BoardRocks {
    static final int BLOCKS = 8;
    static final int BOULDERS = 8;
    static final int OUTCROPS = 8;
    /** Rough cover without gravity: bedrock rising from the ground instead of loose boulders (see BoardScene.Tile.lunar). */
    static final String OUTCROP = "rough-outcrop";
    private static final BoardKit<Map<String, List<BoardShape>>> ROCKS = new BoardKit<>(BoardRocks::loadRocks);

    private BoardRocks() { }

    /** Publish complete immutable geometry; terrain workers never observe a partially reloaded kit. */
    static void reload() { ROCKS.reload(); }

    /** One geology rule for rough, rim, slope, cliff and scattered rocks. */
    static BoardShape rock(BoardScene.Surface surface, int variant, TerrainLod detail) {
        return rock(blocks(surface), variant, detail);
    }

    static boolean blocks(BoardScene.Surface surface) {
        return switch (surface) {
            case SAND, DESERT, MARS, VOLCANO, CONCRETE -> true;
            default -> false;
        };
    }

    static BoardShape rock(boolean block, int variant) {
        return rock(block, variant, TerrainLod.FULL);
    }

    static BoardShape rock(boolean block, int variant, TerrainLod detail) {
        // Each sampling tier takes the next rock level: a block is a few pixels wide where MEDIUM begins.
        int level = Math.min(2, detail.ordinal());
        return ROCKS.get().get(name(block, variant)).get(level);
    }

    static String name(boolean block, int variant) {
        return (block ? "block-" : "boulder-") + Math.floorMod(variant, block ? BLOCKS : BOULDERS);
    }

    /** A bedrock formation, at the same rock level per sampling tier as the other rocks. */
    static BoardShape outcrop(int variant, TerrainLod detail) {
        return ROCKS.get().get("outcrop-" + Math.floorMod(variant, OUTCROPS)).get(Math.min(2, detail.ordinal()));
    }

    private static Map<String, List<BoardShape>> loadRocks() {
        Map<String, List<BoardShape>> rocks = new HashMap<>();
        for (int variant = 0; variant < BLOCKS; variant++) { load(rocks, name(true, variant)); }
        for (int variant = 0; variant < BOULDERS; variant++) { load(rocks, name(false, variant)); }
        for (int variant = 0; variant < OUTCROPS; variant++) { load(rocks, "outcrop-" + variant); }
        return Map.copyOf(rocks);
    }

    private static void load(Map<String, List<BoardShape>> rocks, String shape) {
        Map<String, BoardShape> levels = BoardShape.loadKit("rocks/" + shape);
        for (String node : levels.keySet()) {
            if (!node.matches(shape + "-lod[0-2]")) {
                throw new IllegalArgumentException("Unexpected mesh in rock " + shape + ": " + node);
            }
        }
        rocks.put(shape, MeshLod.load(shape, 3, levels::get));
    }
}
