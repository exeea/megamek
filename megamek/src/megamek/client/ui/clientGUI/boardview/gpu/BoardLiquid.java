/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Locale;

import com.badlogic.gdx.graphics.Color;
import megamek.common.Hex;
import megamek.common.units.Terrains;

/** Immutable liquid/crust appearance captured from terrain; depth and elevation stay on the tile. */
record BoardLiquid(Kind kind, String theme, int rapids) {
    enum Kind { NONE, WATER, HAZARDOUS, MAGMA, MAGMA_CRUST }

    static final BoardLiquid NONE = new BoardLiquid(Kind.NONE, "", 0);
    static final BoardLiquid WATER = new BoardLiquid(Kind.WATER, "", 0);
    /** The green tint of a hazardous liquid's water art, in the board's liquid material and on the minimap. */
    static final Color HAZARDOUS_TINT = new Color(0.4f, 1, 0.12f, 1);

    record Textures(String base, String foam) { }

    static BoardLiquid capture(Hex hex) {
        if (hex.terrainLevel(Terrains.MAGMA) == 2) {
            return new BoardLiquid(Kind.MAGMA, "", 0);
        }
        if (hex.terrainLevel(Terrains.MAGMA) == 1) {
            return new BoardLiquid(Kind.MAGMA_CRUST, "", 0);
        }
        boolean hazardous = hex.containsTerrain(Terrains.HAZARDOUS_LIQUID);
        if (!hazardous && !hex.containsTerrain(Terrains.WATER)) { return NONE; }
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        // Hazardous pools use the ordinary water animation with a green material tint.
        if (hazardous || !(theme.equals("mars") || theme.equals("volcano"))) { theme = ""; }
        return new BoardLiquid(hazardous ? Kind.HAZARDOUS : Kind.WATER, theme,
              Math.clamp(hex.terrainLevel(Terrains.RAPIDS), 0, 2));
    }

    boolean present() { return kind != Kind.NONE && kind != Kind.MAGMA_CRUST; }

    boolean molten() { return kind == Kind.MAGMA; }

    boolean volcanic() { return molten() || kind == Kind.MAGMA_CRUST; }

    boolean connects(BoardLiquid other) {
        return present() && other.present() && molten() == other.molten();
    }

    Textures textures(int depth, int elevation) {
        String base = molten() ? "saxarba/base/base_magma_anim_" + Math.clamp(elevation, -3, 10) + ".gif"
              : theme.isEmpty() ? "saxarba/anim_water_" + Math.clamp(depth, 0, 4) + ".gif"
              : "saxarba/theme_" + theme + "/water_anim_" + theme + "_" + Math.clamp(depth, 0, 4) + ".gif";
        String foam = rapids == 0 || molten() ? ""
              : "saxarba/water/" + (rapids == 1 ? "rapids_anim.gif" : "torrent_anim.gif");
        return new Textures(base, foam);
    }
}
