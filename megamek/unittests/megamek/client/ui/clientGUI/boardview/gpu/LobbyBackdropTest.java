/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class LobbyBackdropTest {
    private static final int BASE = 0x101418;

    @Test
    void paintsAQuarterOfTheWindow() {
        BufferedImage backdrop = LobbyBackdrop.paint(null, 1921, 1080, BASE);

        assertEquals(481, backdrop.getWidth());
        assertEquals(270, backdrop.getHeight());
        assertEquals(BASE, backdrop.getRGB(240, 135) & 0xFFFFFF);
    }

    @Test
    void theBlurKeepsAnEvenImageEven() {
        int[] pixels = new int[8 * 4];
        java.util.Arrays.fill(pixels, 0x808080);
        int[] scratch = new int[pixels.length];

        LobbyBackdrop.boxBlur(pixels, scratch, 8, 4, 2);
        LobbyBackdrop.boxBlur(scratch, pixels, 4, 8, 2);

        for (int pixel : pixels) {
            assertEquals(0x808080, pixel);
        }
    }

    @Test
    void theBlurTurnsAnEdgeIntoARampWithoutSteps() {
        int width = 16;
        int[] row = new int[width];
        for (int x = width / 2; x < width; x++) {
            row[x] = 0xF0F0F0;
        }
        int[] blurred = new int[width];

        LobbyBackdrop.boxBlur(row, blurred, width, 1, 2);

        for (int x = 1; x < width; x++) {
            assertTrue((blurred[x] & 0xFF) >= (blurred[x - 1] & 0xFF));
            assertTrue((blurred[x] & 0xFF) - (blurred[x - 1] & 0xFF) <= 0xF0 / 5 + 1);
        }
    }
}
