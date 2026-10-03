/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/** Per-window presentation choice; unit identity comes from the captured game state. */
enum UnitDisplayMode {
    ALL_MEEPLES("All Meeples"),
    MEK_MEEPLES("Mek Meeples"),
    MODELS("3D Models");

    static final UnitDisplayMode DEFAULT = MEK_MEEPLES;
    private final String label;

    UnitDisplayMode(String label) {
        this.label = label;
    }

    boolean meeple(BoardScene.UnitModel unit) {
        return this == ALL_MEEPLES || (this == MEK_MEEPLES && unit != null && unit.state() != null
              && unit.state().structure().anatomy() != null);
    }

    @Override
    public String toString() {
        return label;
    }
}
