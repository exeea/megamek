/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.board.Coords;

/** Render sampling only: the board's dimensions never choose terrain quality. */
enum TerrainLod {
    FULL(6, 1, true),
    MEDIUM(3, 2, true),
    COARSE(2, 3, false),
    DISTANT(1, 6, false);

    static final int CHUNK_SIZE = 8;
    private static final TerrainLod[] LEVELS = values();
    record Tuning(int fullPixels, int mediumPixels, float coarsePixels) {
        Tuning {
            if (mediumPixels < 1 || fullPixels < mediumPixels || !(coarsePixels > 0 && coarsePixels <= mediumPixels)) {
                throw new IllegalArgumentException("Invalid terrain detail");
            }
        }

        /** DISTANT below a quarter of MEDIUM's threshold. */
        Tuning(int fullPixels, int mediumPixels) { this(fullPixels, mediumPixels, mediumPixels * .25f); }

        float threshold(int level) {
            return switch (level) {
                case 0 -> fullPixels;
                case 1 -> mediumPixels;
                default -> coarsePixels;
            };
        }
    }
    static final Tuning DEFAULTS = new Tuning(64, 24);
    /**
     * The height field's sizes in px a hex (TerrainField; the user's choice, the same at every preset; kept adjustable
     * here). A detail of threshold t shows its own shape from 1.6 t, or from where it is first drawn if that is lower,
     * and the next coarser one's at 1.1 t (GpuTerrain#fieldWeights); it gives way below .9 t (select). FULL 40: its own
     * shape down to 64 px a hex, MEDIUM's from 44, MEDIUM drawn below 36. MEDIUM 20: COARSE's shape from 22 px a hex,
     * COARSE drawn below 18. COARSE 13.64: HEXFAN's shape (ChunkField's DISTANT) from 15 px a hex, HEXFAN drawn below
     * 12.3.
     */
    static final int FIELD_FULL_PIXELS = 40, FIELD_MEDIUM_PIXELS = 20;
    static final float FIELD_COARSE_PIXELS = 13.64f;
    static final Tuning FIELD = new Tuning(FIELD_FULL_PIXELS, FIELD_MEDIUM_PIXELS, FIELD_COARSE_PIXELS);
    private static Tuning tuning = TerrainField.ON ? FIELD : DEFAULTS;
    static Tuning tuning() { return tuning; }
    /** Test hook. Threshold changes select cached meshes; they do not change the terrain's shape or invalidate its geometry. */
    static void tune(Tuning next) { tuning = next; }
    final int steps;
    final int rowStride;
    final boolean dressing;

    TerrainLod(int steps, int rowStride, boolean dressing) {
        this.steps = steps;
        this.rowStride = rowStride;
        this.dressing = dressing;
    }

    /**
     * Whether the build's switches ask for medium detail late ({@link BoardSwitch#DETAIL_SCALE}): coarse carries what
     * medium shows. The build in this thread's scope (TerrainSettings).
     */
    static boolean lateDetail() {
        // As the detail is chosen (#detailPixels): the height field takes it at the hex's own size at every scale.
        return detailPixels(1, TerrainSettings.switchesInScope().get(BoardSwitch.DETAIL_SCALE)) < 1;
    }

    /**
     * Whether this detail carries the ground's small formations (rocks, planting pits): from medium, or coarse on a late
     * level, whose first close zoom would otherwise wait for each chunk's rebuild. They stand in the same places at every
     * detail.
     */
    boolean formations() {
        return dressing || this == COARSE && lateDetail();
    }

    /**
     * Nearest-point projected hex width, with separate refinement/coarsening thresholds to avoid oscillation; each level
     * taken at {@code detailScale} times the hex's size ({@link BoardSwitch#DETAIL_SCALE}).
     */
    static TerrainLod select(float pixels, TerrainLod current, float detailScale) {
        pixels = detailPixels(pixels, detailScale);
        int level = current == null ? 3 : current.ordinal();
        while (level > 0 && pixels >= tuning.threshold(level - 1) * (current == null ? 1 : 1.1f)) { level--; }
        while (level < 3 && pixels < tuning.threshold(level) * .9f) { level++; }
        return LEVELS[level];
    }

    /** A hex's size in pixels as the detail is chosen by it: over the detail scale, which the height field ignores. */
    static float detailPixels(float pixels, float detailScale) { return TerrainField.ON ? pixels : pixels / detailScale; }

    /**
     * Neighbouring chunks at most one level apart, as the height field's morph needs (TerrainField: a border vertex
     * takes its coarser neighbour's weight): a chunk more than one level coarser than a neighbour refines.
     * {@code levels} by chunk index, {@code rows} chunks a column.
     */
    static void restrict(TerrainLod[] levels, int rows) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < levels.length; i++) {
                int cx = i / rows, cy = i % rows;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int x = cx + dx, y = cy + dy, j = x * rows + y;
                        if (dx == 0 && dy == 0 || x < 0 || y < 0 || y >= rows || j >= levels.length) { continue; }
                        if (levels[i].ordinal() > levels[j].ordinal() + 1) {
                            levels[i] = LEVELS[levels[j].ordinal() + 1];
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * {@code target} kept within one level of every installed neighbour of chunk {@code i} ({@code installed} by chunk
     * index, null where none): a chunk changes detail only to a level its neighbours' meshes border without a gap.
     */
    static TerrainLod reachable(TerrainLod target, TerrainLod[] installed, int i, int rows) {
        int low = 0, high = LEVELS.length - 1, cx = i / rows, cy = i % rows;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = cx + dx, y = cy + dy, j = x * rows + y;
                if (dx == 0 && dy == 0 || x < 0 || y < 0 || y >= rows || j >= installed.length || installed[j] == null) { continue; }
                low = Math.max(low, installed[j].ordinal() - 1);
                high = Math.min(high, installed[j].ordinal() + 1);
            }
        }
        return LEVELS[Math.clamp(target.ordinal(), low, Math.max(low, high))];
    }

    static boolean sameChunk(Coords a, Coords b) {
        return a.getX() / CHUNK_SIZE == b.getX() / CHUNK_SIZE && a.getY() / CHUNK_SIZE == b.getY() / CHUNK_SIZE;
    }
}
