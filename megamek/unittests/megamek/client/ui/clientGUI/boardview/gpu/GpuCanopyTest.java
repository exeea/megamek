/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GpuCanopyTest {
    /**
     * A hex's row of the canopy's table: its crowns' colour weighted by the area each covers, and their share of it.
     */
    @Test
    void aHexSumsItsCrownsByTheAreaTheyCover() {
        var dark = new GpuCanopy.Species(.06f, .09f, .03f, .25f);
        var light = new GpuCanopy.Species(.14f, .19f, .03f, .5f);
        var hex = new GpuCanopy.Hex();
        // 0.25 of a 20 by 20 frame is 100 square units; 0.5 of a 10 by 10 frame is 50.
        hex.add(dark, 20);
        hex.add(light, 10);
        float[] row = new float[8];
        hex.write(600, row, 4);
        assertArrayEquals(new float[] { 0, 0, 0, 0 }, new float[] { row[0], row[1], row[2], row[3] }, 0,
              "Only its own four floats are written");
        assertEquals((.06f * 100 + .14f * 50) / 150, row[4], 1e-6f);
        assertEquals((.09f * 100 + .19f * 50) / 150, row[5], 1e-6f);
        assertEquals(.03f, row[6], 1e-6f);
        assertEquals(150 / 600f, row[7], 1e-6f, "The crowns' share of the hex");
        // Crowns overlapping past the hex's area cover it whole, never more.
        hex.add(light, 40);
        hex.write(600, row, 4);
        assertEquals(1, row[7], 0);
        // A hex without trees leaves its row as it is.
        float[] bare = { 1, 2, 3, 4 };
        new GpuCanopy.Hex().write(600, bare, 0);
        assertArrayEquals(new float[] { 1, 2, 3, 4 }, bare, 0);
    }
}
