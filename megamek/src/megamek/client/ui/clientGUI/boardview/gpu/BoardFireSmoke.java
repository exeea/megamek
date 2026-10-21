/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.Hex;
import megamek.common.units.Terrains;
import megamek.server.SmokeCloud;

/** Immutable terrain facts captured on Swing. Wind animates their appearance, never their game location. */
record BoardFireSmoke(int fire, int smoke, boolean screen) {
    static final BoardFireSmoke NONE = new BoardFireSmoke(0, SmokeCloud.SMOKE_NONE);

    BoardFireSmoke(int fire, int smoke) { this(fire, smoke, false); }

    static BoardFireSmoke capture(Hex hex) {
        return new BoardFireSmoke(Math.max(0, hex.terrainLevel(Terrains.FIRE)),
              Math.max(0, hex.terrainLevel(Terrains.SMOKE)), hex.containsTerrain(Terrains.SCREEN));
    }

    // LOS treats a screen's presence as blocking, including a stored screen:0; never erase that visual cue.
    boolean present() { return fire > 0 || smoke > SmokeCloud.SMOKE_NONE || screen; }

    float flame() { return fire == 0 ? 0 : fire == Terrains.FIRE_LVL_NORMAL ? 1 : 1.25f; }

    float density() {
        return switch (smoke) {
            case SmokeCloud.SMOKE_HEAVY, SmokeCloud.SMOKE_LI_HEAVY -> 2.1f;
            case SmokeCloud.SMOKE_NONE -> fire > 0 ? .9f : screen ? .85f : 0;
            default -> .85f;
        };
    }

    /** Palette index only; smoke types and gameplay modifiers remain the server's responsibility. */
    int palette() {
        // Screen launchers disperse chaff. Coexisting smoke keeps its own special-smoke palette.
        if (screen && smoke == SmokeCloud.SMOKE_NONE) { return 3; }
        return switch (smoke) {
            case SmokeCloud.SMOKE_GREEN -> 1;
            case SmokeCloud.SMOKE_LI_LIGHT, SmokeCloud.SMOKE_LI_HEAVY -> 2;
            case SmokeCloud.SMOKE_CHAFF_LIGHT -> 3;
            default -> 0;
        };
    }
}
