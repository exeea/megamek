/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Screen-pixel budgets for the shared orthographic board, independent of orbit and camera distance: the one rule every
 * board model takes its mesh level by ({@link #level(float[], float, int)}), with two sets of thresholds. Trees in a
 * stand have four levels by their own diameter: the near mesh, two simpler meshes and, below the last threshold, the
 * impostor; scatter plants have three meshes and below the last threshold none ({@link #SCATTER}). Every other prop
 * (buildings, scenery, placed objects) and the effect volumes have three by their larger side ({@link #prop}).
 */
final class TreeLod {
    private static final float[] PIXELS = { 80, 48, 24 };
    static final float[] PROPS = { 80, 24 };
    /**
     * A scatter plant's levels by its own diameter (scatter_lods.py keeps each far level's coverage at its threshold):
     * below the last it draws nothing, into which it dissolves as a tree does into its impostor.
     */
    static final float[] SCATTER = { 20, 8, 2 };
    /**
     * The longest side, in world units, that counts against {@link #PROPS}: five hexes. A giant's parts are small against a
     * hex, not against its whole side, so without this the 1,100-unit HPG would keep all its triangles down to 6 pixels a hex.
     */
    static final float PROPS_SIDE = 415;
    /** Placed decorative objects whose world-box diagonal is under this many hex widths are small (GpuTerrain.Prop.small). */
    static final float SMALL_OBJECT = .25f;
    /** Small objects hide while a hex is narrower than this on screen, in framebuffer pixels; {@link #level} adds ±10 %. */
    static final float[] SMALL_OBJECTS = { 12 };
    /**
     * Building shells join the depth pre-pass while a hex is at least this wide on screen (GpuTerrain.twin): further out
     * their twins cost more than the shading they save. On an Iris Xe at 1080p, 29-pixel hexes (the 48 by 48 city
     * fitted) saved 0.5 to 0.9 ms, within the rounds' spread, and 9.5-pixel ones (Valencia City fitted) cost 0.7 ms of
     * pre-pass and saved nothing; this lies near their geometric mean, untuned.
     */
    static final float[] PREPASS_OBJECTS = { 16 };
    /** Medium quality's relief joins the depth pre-pass while a hex is narrower than this on screen (GpuTerrain.twin). */
    static final float[] PREPASS_RELIEF = { 96 };
    static final int LEVELS = PIXELS.length + 1;
    /** Seconds a tree takes to dissolve from one level into the next (GpuTreeInstances.Stand#detail). */
    static final float DISSOLVE = .25f;
    static final float HYSTERESIS = 0.1f;

    private TreeLod() { }

    /**
     * Whether a change of scale (pixels a world unit) is a cut: further in one step than the widest gap between two
     * neighbouring tree levels (48 to 24 pixels). A cut is a new view, with no picture to dissolve from, so levels
     * switch at once (GpuTreeInstances.Stand#detail); a wheel's notches and a gliding zoom stay under it.
     */
    static boolean cut(float from, float to) {
        return from > 0 && to > 0 && Math.max(from / to, to / from) > PIXELS[1] / PIXELS[2];
    }

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

    /** The thresholds a stand's species takes: a scatter plant's (its file under {@code scatter/}) or a tree's. */
    static float[] pixels(String species) {
        return species.startsWith("scatter/") ? SCATTER : PIXELS;
    }

    /** The level of a building or placed object with this larger side, in world units, at this scale. */
    static int prop(float side, float pixelsPerUnit, int previous) {
        return level(PROPS, Math.min(side, PROPS_SIDE) * pixelsPerUnit, previous);
    }

    static String asset(String name, int level) {
        return MeshLod.name(name, level);
    }
}
