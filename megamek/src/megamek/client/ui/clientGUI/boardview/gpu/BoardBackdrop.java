/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.Hex;
import megamek.common.units.Terrains;

/** Empty aerospace hexes have a backdrop, independent of the physical ground material beneath other terrain. */
enum BoardBackdrop {
    NONE, SPACE, SKY;

    static BoardBackdrop capture(Hex hex) {
        // Pavement and liquid are real surfaces even when an imported hex retains an aerospace base marker.
        if (hex.containsAnyTerrainOf(Terrains.PAVEMENT, Terrains.WATER, Terrains.MAGMA,
              Terrains.HAZARDOUS_LIQUID)) { return NONE; }
        if (hex.containsTerrain(Terrains.SPACE)) { return SPACE; }
        return hex.containsTerrain(Terrains.SKY) ? SKY : NONE;
    }

    boolean present() { return this != NONE; }
}
