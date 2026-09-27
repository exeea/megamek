/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Screen-size selection between a unit's LOD0 and authored LOD1. A battle armour squad measures one suit; a Mek
 * measures its whole body. The instance retains LOD0 when the optional LOD1 mesh is absent.
 */
final class FormationLod {
    /** Below this height in framebuffer pixels a suit selects LOD1. */
    static final float LOD1_PIXELS = 48;
    /**
     * Below this height in framebuffer pixels a Mek selects LOD1. This tuning value marks where LOD0's panel lines
     * and vent slats no longer read.
     */
    static final float MEK_LOD1_PIXELS = 96;
    /** Keeps a squad at the zoom boundary from flickering between its two suits. */
    private static final float HYSTERESIS = 0.1f;

    private FormationLod() { }

    /**
     * @param pixels   one suit's height in framebuffer pixels
     * @param previous the level shown now, so the switch back needs a clear margin
     *
     * @return the requested numeric LOD
     */
    static int level(float pixels, int previous) {
        return level(pixels, previous, LOD1_PIXELS);
    }

    /**
     * @param pixels     the unit's measured height in framebuffer pixels
     * @param previous   the level shown now, so the switch back needs a clear margin
     * @param lod1Pixels the height below which LOD1 is selected
     *
     * @return the requested numeric LOD
     */
    static int level(float pixels, int previous, float lod1Pixels) {
        float threshold = lod1Pixels * (previous == 0 ? 1 - HYSTERESIS : 1 + HYSTERESIS);
        return pixels < threshold ? 1 : 0;
    }
}
