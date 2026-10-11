/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import megamek.common.Hex;
import megamek.common.units.Terrains;

/**
 * A hex as the boards draw it, for code that runs without a view (the legacy conversion on the server): the tile size
 * and level height in authored model units, and what the 3D board draws from a hex's terrain. The 2D tileset and the 3D
 * board's geometry, liquid, road and rough art call these, so each has one statement.
 */
public final class HexTile {
    /** The width of a hex image, and of a hex in authored board model units. */
    public static final int WIDTH = 84;
    /** The height of a hex image, and of a hex in authored board model units. */
    public static final int HEIGHT = 72;
    /** One level in authored board models, in the units of an 84 by 72 hex. */
    public static final int LEVEL_HEIGHT = 18;

    private HexTile() { }

    /** The x of the hex's centre on a board of hexes {@code width} wide (east positive). */
    public static float centerX(Coords coords, float width) {
        return coords.getX() * width * 0.75f + width / 2;
    }

    /** The y of the hex's centre on a board of hexes {@code height} tall (north positive, so rows run negative). */
    public static float centerY(Coords coords, float height) {
        return -(coords.getY() * height + (coords.getX() & 1) * height / 2 + height / 2);
    }

    /** Whether the 3D board draws a liquid on the hex: water, a hazardous liquid or molten magma, not magma crust. */
    public static boolean liquid(Hex hex) {
        int magma = hex.terrainLevel(Terrains.MAGMA);
        return magma == 2 || magma != 1 && hex.containsAnyTerrainOf(Terrains.WATER, Terrains.HAZARDOUS_LIQUID);
    }

    /** Whether the 3D board draws the hex's road: a paved, alley, dirt or gravel ROAD. */
    public static boolean road(Hex hex) {
        if (!hex.containsTerrain(Terrains.ROAD)) { return false; }
        int level = hex.terrainLevel(Terrains.ROAD);
        return level == 1 || level == 2 || level == Terrains.ROAD_LVL_DIRT || level == Terrains.ROAD_LVL_GRAVEL;
    }

    /**
     * The modelled rough variant of the hex (its rough design, else its own terrain): 1 dragon's teeth, 2 felled trees,
     * else 0. Other uses of fluff keep their tileset art.
     */
    public static int roughVariant(Hex hex) {
        if (hex.getAppearance().containsKey("rough")) { hex = BoardEditorBlueprint.get().artwork(hex, "rough"); }
        int fluff = hex.terrainLevel(Terrains.FLUFF);
        return hex.containsTerrain(Terrains.ROUGH) && (fluff == 1 || fluff == 2) ? fluff : 0;
    }
}
