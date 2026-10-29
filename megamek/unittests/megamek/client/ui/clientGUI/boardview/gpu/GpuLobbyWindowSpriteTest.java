/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class GpuLobbyWindowSpriteTest {

    @Test
    void trimmedKeepsTheVisiblePixelsAndAPixelOfMargin() {
        BufferedImage tile = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        tile.setRGB(20, 10, 0xFF112233);
        tile.setRGB(50, 40, 0xFF445566);

        BufferedImage cut = GpuLobbyWindow.trimmed(tile);

        assertEquals(33, cut.getWidth());
        assertEquals(33, cut.getHeight());
        assertEquals(0xFF112233, cut.getRGB(1, 1));
        assertEquals(0xFF445566, cut.getRGB(31, 31));
    }

    @Test
    void trimmedStopsAtTheImageEdges() {
        BufferedImage tile = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        tile.setRGB(0, 0, 0xFFFFFFFF);
        tile.setRGB(9, 9, 0xFFFFFFFF);

        BufferedImage cut = GpuLobbyWindow.trimmed(tile);

        assertEquals(10, cut.getWidth());
        assertEquals(10, cut.getHeight());
    }

    @Test
    void trimmedLeavesAClearImageAlone() {
        BufferedImage tile = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);

        assertSame(tile, GpuLobbyWindow.trimmed(tile));
    }
}
