/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Screen-pixel budgets for the shared orthographic board, independent of orbit and camera distance. Trees have four
 * levels: the near mesh, two simpler meshes and, below the last threshold, the impostor cards. Buildings and effect
 * volumes keep the three-level thresholds.
 */
final class TreeLod {
    private static final float[] PIXELS = { 80, 48, 24 };
    static final float[] PROPS = { 80, 24 };
    static final int LEVELS = PIXELS.length + 1;
    private static final float HYSTERESIS = 0.1f;

    private TreeLod() { }

    /** A conservative projected bounding diameter, measured in framebuffer pixels. */
    static int level(float pixels, int previous) {
        return level(PIXELS, pixels, previous);
    }

    /** The level for a diameter against descending thresholds, changing only past each threshold's hysteresis. */
    static int level(float[] thresholds, float pixels, int previous) {
        int level = previous;
        while (level < thresholds.length && pixels < thresholds[level] * (1 - HYSTERESIS)) {
            level++;
        }
        while (level > 0 && pixels > thresholds[level - 1] * (1 + HYSTERESIS)) {
            level--;
        }
        return level;
    }

    static String asset(String name, int level) {
        return MeshLod.name(name, level);
    }
}
