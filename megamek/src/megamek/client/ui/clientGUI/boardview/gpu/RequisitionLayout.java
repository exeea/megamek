/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Where the Requisition view's results and dossier go, without OpenGL. The dossier takes {@link #DOSSIER_SHARE} of
 * the width, or more to stay {@link #DOSSIER_MIN} wide; when the results would fall under {@link #RESULTS_MIN} beside
 * it, they take the whole width and the dossier slides over all but their leading {@link #OVERLAY_EDGE}, where the
 * sprites and the start of the names stay in sight.
 */
final class RequisitionLayout {
    static final float DOSSIER_SHARE = .45f;
    static final float DOSSIER_MIN = 440;
    static final float RESULTS_MIN = 320;
    static final float OVERLAY_EDGE = 160;
    /** The least a result's name keeps before its columns give way. */
    static final float NAME_MIN = 200;

    /**
     * The places of the results and the dossier.
     *
     * @param resultsWidth the results' width, from the left
     * @param dossierX     the dossier's left edge
     * @param dossierWidth the dossier's width; 0 while it is shut
     * @param overlay      whether the dossier lies over the results rather than beside them
     */
    record Dock(float resultsWidth, float dossierX, float dossierWidth, boolean overlay) { }

    private RequisitionLayout() {
    }

    /** The places in a view {@code width} wide, with the dossier {@code open} or shut, {@code gap} between the two. */
    static Dock dock(float width, boolean open, float gap) {
        if (!open || (width <= 0)) {
            return new Dock(Math.max(0, width), Math.max(0, width), 0, false);
        }
        float dossier = Math.min(width, Math.max(DOSSIER_MIN, width * DOSSIER_SHARE));
        float results = width - dossier - gap;
        if (results >= RESULTS_MIN) {
            return new Dock(results, width - dossier, dossier, false);
        }
        float edge = Math.min(OVERLAY_EDGE, width / 3);
        return new Dock(width, edge, width - edge, true);
    }

    /**
     * How many of a result row's columns, {@code widths} wide in their order and each {@code gap} after what comes
     * before it, fit in {@code room}, the row's width less its sprite, value and paddings, while the name keeps
     * {@link #NAME_MIN}: the first ones that fit, the rest going from the last.
     */
    static int columns(float room, float[] widths, float gap) {
        float left = room - NAME_MIN;
        int count = 0;
        for (float width : widths) {
            left -= gap + width;
            if (left < 0) {
                break;
            }
            count++;
        }
        return count;
    }
}
