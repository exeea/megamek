/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Screen-size selection between a unit's three mesh levels. A formation measures one figure; a Mek its body.
 * Asset fallback is resolved separately, so zoom hysteresis also works when an intermediate mesh is absent.
 */
final class FormationLod {
    /** Below this height in framebuffer pixels a suit selects LOD1. */
    static final float LOD1_PIXELS = 48;
    /** Distant figure silhouette, measured in framebuffer pixels. */
    static final float LOD2_PIXELS = 16;
    /**
     * Below this height in framebuffer pixels a Mek selects LOD1. This tuning value marks where LOD0's panel lines
     * and vent slats no longer read.
     */
    static final float MEK_LOD1_PIXELS = 96;
    /** Distant Mek silhouette, measured in framebuffer pixels. */
    static final float MEK_LOD2_PIXELS = 32;
    /** Keeps a unit at either zoom boundary from flickering between adjacent levels. */
    private static final float HYSTERESIS = 0.1f;

    private FormationLod() { }

    /**
     * @param pixels   one suit's height in framebuffer pixels
     * @param previous the level shown now, so the switch back needs a clear margin
     *
     * @return the requested numeric LOD
     */
    static int level(float pixels, int previous) {
        return level(pixels, previous, LOD1_PIXELS, LOD2_PIXELS);
    }

    /**
     * @param pixels     the unit's measured height in framebuffer pixels
     * @param previous   the level shown now, so the switch back needs a clear margin
     * @param lod1Pixels the height below which LOD1 is selected
     *
     * @return the requested numeric LOD
     */
    static int level(float pixels, int previous, float lod1Pixels) {
        return level(pixels, previous, lod1Pixels, 0);
    }

    /** Both boundaries are evaluated so a large zoom change can go directly between LOD0 and LOD2. */
    static int level(float pixels, int previous, float lod1Pixels, float lod2Pixels) {
        float distant = lod2Pixels * (previous < 2 ? 1 - HYSTERESIS : 1 + HYSTERESIS);
        if (pixels < distant) { return 2; }
        float near = lod1Pixels * (previous == 0 ? 1 - HYSTERESIS : 1 + HYSTERESIS);
        return pixels < near ? 1 : 0;
    }
}
