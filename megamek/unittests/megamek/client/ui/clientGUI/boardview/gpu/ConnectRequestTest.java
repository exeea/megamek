/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import megamek.MMConstants;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.ConnectRequest;
import org.junit.jupiter.api.Test;

class ConnectRequestTest {
    @Test
    void thePlayerJoinsEitherGameAndABotOnlyTotalWarfare() {
        assertDoesNotThrow(() -> new ConnectRequest("Natasha", "localhost", MMConstants.DEFAULT_PORT, false, false));
        assertDoesNotThrow(() -> new ConnectRequest("Natasha", "localhost", MMConstants.DEFAULT_PORT, false, true));
        assertDoesNotThrow(() -> new ConnectRequest("Princess", "localhost", MMConstants.DEFAULT_PORT, true, false));
        assertThrows(IllegalArgumentException.class,
              () -> new ConnectRequest("Princess", "localhost", MMConstants.DEFAULT_PORT, true, true));
    }
}
