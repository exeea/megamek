/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.Locale;

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

    private static final int[] CORNER_DX = { 2, 1, -1, -2, -1, 1 };
    private static final int[] CORNER_DY = { 0, 1, 1, 0, -1, -1 };

    /**
     * The x of corner k (0..5: east, then counter-clockwise) on a board of hexes {@code width} wide. Corners lie on a
     * lattice of quarter widths and half heights, so every hex sharing a corner computes bit-identical coordinates.
     */
    public static float cornerX(Coords coords, int k, float width) {
        return (3 * coords.getX() + 2 + CORNER_DX[k]) * (width / 4);
    }

    /** The y of corner k (0..5) on a board of hexes {@code height} tall; see {@link #cornerX}. */
    public static float cornerY(Coords coords, int k, float height) {
        return (-(2 * coords.getY() + (coords.getX() & 1) + 1) + CORNER_DY[k]) * (height / 2);
    }

    /** Corner k (0..5) relative to its hex's centre, x. */
    public static float cornerOffsetX(int k, float width) { return CORNER_DX[k] * (width / 4); }

    /** Corner k (0..5) relative to its hex's centre, y. */
    public static float cornerOffsetY(int k, float height) { return CORNER_DY[k] * (height / 2); }

    /** The hex direction across edge k, the edge from corner k to corner k + 1. */
    public static int edgeDirection(int edge) {
        return Math.floorMod(1 - edge, 6);
    }

    /** Whether the footprint of the hex at (column, row) on a board of {@code width} by {@code height} hexes holds (x, y). */
    public static boolean contains(int column, int row, float x, float y, float width, float height) {
        float dx = Math.abs(x - (column * width * 0.75f + width / 2));
        float dy = Math.abs(y - -(row * height + (column & 1) * height / 2 + height / 2));
        return dy <= height / 2 + 0.001f && (height / 2) * dx + (width / 4) * dy <= width * height / 4 + 0.001f;
    }

    /**
     * The board index (column * rows + row) of the hex whose footprint holds (x, y), on a board of {@code columns} by
     * {@code rows} hexes {@code width} by {@code height}; -1 off the board.
     */
    public static int at(float x, float y, float width, float height, int columns, int rows) {
        int column = (int) Math.floor(x / (width * .75f));
        int row = (int) Math.floor(-y / height);
        for (int cx = column - 1; cx <= column + 1; cx++) {
            for (int cy = row - 1; cy <= row + 1; cy++) {
                if (cx >= 0 && cy >= 0 && cx < columns && cy < rows && contains(cx, cy, x, y, width, height)) {
                    return cx * rows + cy;
                }
            }
        }
        return -1;
    }

    /** Whether the 3D board draws a liquid on the hex: water, a hazardous liquid or molten magma, not magma crust. */
    public static boolean liquid(Hex hex) {
        int magma = hex.terrainLevel(Terrains.MAGMA);
        return magma == 2 || magma != 1 && hex.containsAnyTerrainOf(Terrains.WATER, Terrains.HAZARDOUS_LIQUID);
    }

    /** Whether the 3D board draws the hex's ground as concrete: pavement, unless magma or snow (terrain or theme) covers it. */
    public static boolean concrete(Hex hex) {
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        return hex.containsTerrain(Terrains.PAVEMENT) && !hex.containsTerrain(Terrains.MAGMA)
              && !hex.containsTerrain(Terrains.SNOW) && !theme.contains("snow");
    }

    /**
     * Whether a road end meets the neighbouring hex as a ramp: the road continues across the edge, or one side's road
     * meets unpaved ground across at most two levels ({@code levels} apart).
     */
    public static boolean roadApproach(boolean exit, boolean continuation, int levels) {
        return (exit && continuation) || ((exit || continuation) && levels <= 2);
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
