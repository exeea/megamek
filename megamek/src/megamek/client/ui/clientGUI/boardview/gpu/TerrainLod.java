/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.board.Coords;

/** Render sampling only: the board's dimensions never choose terrain quality. */
enum TerrainLod {
    FULL(6, 1, 3, true),
    MEDIUM(3, 2, 2, true),
    COARSE(2, 3, 1, false),
    DISTANT(1, 6, 0, false);

    static final int CHUNK_SIZE = 8;
    static final boolean DEFAULT_ENABLED = true;
    private static final TerrainLod[] LEVELS = values();
    record Tuning(int fullPixels, int mediumPixels) {
        Tuning {
            if (mediumPixels < 1 || fullPixels < mediumPixels) { throw new IllegalArgumentException("Invalid terrain detail"); }
        }

        float threshold(int level) {
            return switch (level) {
                case 0 -> fullPixels;
                case 1 -> mediumPixels;
                default -> mediumPixels * .25f;
            };
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
    final int topSamples;
    final boolean dressing;

    TerrainLod(int steps, int rowStride, int topSamples, boolean dressing) {
        this.steps = steps;
        this.rowStride = rowStride;
        this.topSamples = topSamples;
        this.dressing = dressing;
    }

    /** Nearest-point projected hex width, with separate refinement/coarsening thresholds to avoid oscillation. */
    static TerrainLod select(float pixels, TerrainLod current) {
        if (!enabled) { return FULL; }
        int level = current == null ? 3 : current.ordinal();
        while (level > 0 && pixels >= tuning.threshold(level - 1) * (current == null ? 1 : 1.1f)) { level--; }
        while (level < 3 && pixels < tuning.threshold(level) * .9f) { level++; }
        return LEVELS[level];
    }

    static boolean sameChunk(Coords a, Coords b) {
        return a.getX() / CHUNK_SIZE == b.getX() / CHUNK_SIZE && a.getY() / CHUNK_SIZE == b.getY() / CHUNK_SIZE;
    }
}
