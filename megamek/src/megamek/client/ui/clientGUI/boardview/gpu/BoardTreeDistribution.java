/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import megamek.common.board.Coords;

/** Stateless visual stands in board space; the caller still owns cover, counts, biome and clearance. */
final class BoardTreeDistribution {
    /** About three hexes across, in authored tile pixels, independent of render scale or camera. */
    private static final float STAND_SIZE = 210;

    private BoardTreeDistribution() { }

    static String species(List<String> trees, Coords coords, float x, float y, long individual) {
        // Feature offsets are Z-up world axes; board rows run in the opposite Y direction.
        float worldX = coords.getX() * BoardGeometry.TILE_WIDTH * .75f + x;
        float worldY = -(coords.getY() + (coords.getX() & 1) * .5f) * BoardGeometry.TILE_HEIGHT + y;
        String dominant = trees.get(Math.floorMod(stand(worldX, worldY), trees.size()));
        // Seedlings/occasional other species soften the stand boundary, rather than stamping monocultures.
        if (fraction(individual) < .22f) {
            return trees.get(Math.floorMod(individual, trees.size()));
        }
        int count = 0;
        for (String tree : trees) {
            if (sameFamily(tree, dominant)) { count++; }
        }
        // A single family (for example orchard forms) still groups its dominant form instead of bypassing stands.
        if (count == trees.size()) { return dominant; }
        int choice = Math.floorMod(individual, count);
        for (String tree : trees) {
            if (sameFamily(tree, dominant) && choice-- == 0) { return tree; }
        }
        return dominant;
    }

    private static boolean sameFamily(String a, String b) {
        if (a.startsWith("tree-dead") != b.startsWith("tree-dead")) { return false; }
        int endA = a.indexOf('-'), endB = b.indexOf('-');
        if (endA < 0) { endA = a.length(); }
        if (endB < 0) { endB = b.length(); }
        return endA == endB && a.regionMatches(0, b, 0, endA);
    }

    /** Nearest jittered seed, shared across hex boundaries. No board-sized cache or neighbor capture. */
    private static long stand(float x, float y) {
        int cellX = (int) Math.floor(x / STAND_SIZE), cellY = (int) Math.floor(y / STAND_SIZE);
        float nearest = Float.POSITIVE_INFINITY;
        long selected = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int cx = cellX + dx, cy = cellY + dy;
                long seed = mix(cx * 0x9E3779B97F4A7C15L ^ cy * 0xC2B2AE3D27D4EB4FL);
                float sx = (cx + .15f + .7f * fraction(seed)) * STAND_SIZE - x;
                float sy = (cy + .15f + .7f * fraction(mix(seed))) * STAND_SIZE - y;
                float distance = sx * sx + sy * sy;
                if (distance < nearest) {
                    nearest = distance;
                    selected = seed;
                }
            }
        }
        return selected;
    }

    private static float fraction(long seed) {
        return (seed >>> 40) * 0x1.0p-24f;
    }

    private static long mix(long seed) {
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        return seed ^ (seed >>> 31);
    }
}
