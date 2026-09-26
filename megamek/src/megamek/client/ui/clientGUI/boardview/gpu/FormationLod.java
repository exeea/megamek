/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Level of detail for a unit with a far model, chosen like a tree's: by how tall the unit stands on screen. Level 0 is
 * the full model; level 1 is the simpler far one, drawn once the full model's small details would be only a pixel or
 * two. A battle armour squad measures one suit; a Mek measures its whole body.
 */
final class FormationLod {
    /** Below this height in framebuffer pixels a suit is drawn with its far detail. */
    static final float FAR_PIXELS = 48;
    /**
     * Below this height in framebuffer pixels a Mek is drawn with its far body. A tuning value, to be settled in
     * play: below it a near body's panel lines and vent slats no longer read.
     */
    static final float MEK_FAR_PIXELS = 96;
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
        return level(pixels, previous, FAR_PIXELS);
    }

    /**
     * @param pixels    the unit's measured height in framebuffer pixels
     * @param previous  the level shown now, so the switch back needs a clear margin
     * @param farPixels the height below which the far model is drawn
     *
     * @return {@code 0} for the full model, {@code 1} for the far one
     */
    static int level(float pixels, int previous, float farPixels) {
        float threshold = farPixels * (previous == 0 ? 1 - HYSTERESIS : 1 + HYSTERESIS);
        return pixels < threshold ? 1 : 0;
    }
}
