/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Level of detail for a battle armour squad with a far suit, chosen like a tree's: by how tall one suit stands on
 * screen. Level 0 is the full suit; level 1 is the simpler far suit, drawn once the full suit's small details (the
 * launcher ears, the knee pads) would be only a pixel or two.
 */
final class FormationLod {
    /** Below this height in framebuffer pixels a suit is drawn with its far detail. */
    static final float FAR_PIXELS = 48;
    /** Keeps a squad at the zoom boundary from flickering between its two suits. */
    private static final float HYSTERESIS = 0.1f;

    private FormationLod() { }

    /**
     * @param pixels   one suit's height in framebuffer pixels
     * @param previous the level shown now, so the switch back needs a clear margin
     *
     * @return {@code 0} for the full suit, {@code 1} for the far suit
     */
    static int level(float pixels, int previous) {
        float threshold = FAR_PIXELS * (previous == 0 ? 1 - HYSTERESIS : 1 + HYSTERESIS);
        return pixels < threshold ? 1 : 0;
    }
}
