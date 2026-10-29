/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * The horizon search behind the sky occlusion (GpuSkyVisibility), against the contract sky-visibility.glsl reads: RGBA
 * holds the quarters towards +x+y, -x+y, -x-y and +x-y, a cell inside the ground holds the surface's values, and the
 * sky opens with height.
 */
class GpuSkyVisibilityTest {
    private static final int SIZE = 16;
    /** A wall standing from this column eastwards (+x). */
    private static final int WALL = 10;

    /** Three layers a cell apart: under the ground, on it, and a cell above it. */
    private static GpuSkyVisibility.Field field(float wall) {
        float cell = BoardRelief.metres(GpuSkyVisibility.CELL_METRES);
        float[] heights = new float[SIZE * SIZE];
        for (int j = 0; j < SIZE; j++) {
            for (int i = WALL; i < SIZE; i++) { heights[j * SIZE + i] = wall; }
        }
        return new GpuSkyVisibility.Field(SIZE, SIZE, 3, 0, 0, -cell, cell, cell, heights, heights);
    }

    private static int value(byte[] values, int i, int j, int layer, int quarter) {
        return values[((layer * SIZE + j) * SIZE + i) * 4 + quarter] & 255;
    }

    @Test
    void levelGroundSeesTheWholeSky() {
        byte[] values = GpuSkyVisibility.search(field(0));
        for (byte value : values) { assertEquals(255, value & 255); }
    }

    @Test
    void aWallHidesOnlyTheQuartersFacingIt() {
        byte[] values = GpuSkyVisibility.search(field(BoardRelief.metres(12)));
        int i = WALL - 2, j = SIZE / 2;
        // +x+y and +x-y face the wall; -x+y and -x-y look away over level ground.
        assertTrue(value(values, i, j, 1, 0) < 200, "towards the wall, north side");
        assertTrue(value(values, i, j, 1, 3) < 200, "towards the wall, south side");
        assertEquals(255, value(values, i, j, 1, 1));
        assertEquals(255, value(values, i, j, 1, 2));
    }

    @Test
    void theRockBehindAWallsFootHoldsTheFootsShade() {
        byte[] values = GpuSkyVisibility.search(field(BoardRelief.metres(12)));
        int j = SIZE / 2;
        // The wall's first column at the floor's height, which filtering at the foot reads: shade, not the open top.
        assertTrue(value(values, WALL, j, 1, 0) < 200);
        // Deep in the wall the cells hold its top's open sky.
        assertEquals(255, value(values, WALL + 3, j, 1, 0));
    }

    @Test
    void theGroundHoldsItsSurfaceValuesAndTheSkyOpensWithHeight() {
        byte[] values = GpuSkyVisibility.search(field(BoardRelief.metres(12)));
        int layer = SIZE * SIZE * 4;
        // Filtering towards the surface never reads rock: under the ground is the surface itself.
        assertTrue(Arrays.equals(values, 0, layer, values, layer, 2 * layer));
        int i = WALL - 2, j = SIZE / 2;
        assertTrue(value(values, i, j, 2, 0) > value(values, i, j, 1, 0));
    }
}
