/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.board.Coords;

/** Render sampling only: the board's dimensions never choose terrain quality. */
enum TerrainLod {
    FULL(12, 1, new float[] { .26f, .52f, .78f }, new int[] { 3, 6, 12 }, true),
    MEDIUM(6, 2, new float[] { .35f, .7f }, new int[] { 3, 6 }, true),
    COARSE(3, 4, new float[] { .55f }, new int[] { 2 }, false),
    DISTANT(1, 12, new float[0], new int[0], false);

    static final int CHUNK_SIZE = 8;
    static final boolean DEFAULT_ENABLED = true;
    record Tuning(int fullPixels, int mediumPixels) {
        Tuning {
            if (mediumPixels < 1 || fullPixels < mediumPixels) { throw new IllegalArgumentException("Invalid terrain detail"); }
        }
    }
    static final Tuning DEFAULTS = new Tuning(64, 24);
    private static Tuning tuning = DEFAULTS;
    private static boolean enabled = DEFAULT_ENABLED;
    static Tuning tuning() { return tuning; }
    static boolean enabled() { return enabled; }
    static void setEnabled(boolean value) { enabled = value; }
    /** Threshold changes select cached meshes; they do not change the terrain's shape or invalidate its geometry. */
    static void tune(Tuning next) { tuning = next; }
    final int steps;
    final int rowStride;
    final float[] rings;
    final int[] ringSamples;
    final boolean dressing;

    TerrainLod(int steps, int rowStride, float[] rings, int[] ringSamples, boolean dressing) {
        this.steps = steps;
        this.rowStride = rowStride;
        this.rings = rings;
        this.ringSamples = ringSamples;
        this.dressing = dressing;
    }

    /** Nearest-point projected hex width, with separate refinement/coarsening thresholds to avoid oscillation. */
    static TerrainLod select(float pixels, TerrainLod current) {
        if (!enabled) { return FULL; }
        float full = tuning.fullPixels(), medium = tuning.mediumPixels();
        float[] thresholds = { full, medium, medium * .25f };
        int level = current == null ? 3 : current.ordinal();
        while (level > 0 && pixels >= thresholds[level - 1] * (current == null ? 1 : 1.1f)) { level--; }
        while (level < 3 && pixels < thresholds[level] * .9f) { level++; }
        return values()[level];
    }

    static boolean sameChunk(Coords a, Coords b) {
        return a.getX() / CHUNK_SIZE == b.getX() / CHUNK_SIZE && a.getY() / CHUNK_SIZE == b.getY() / CHUNK_SIZE;
    }
}
