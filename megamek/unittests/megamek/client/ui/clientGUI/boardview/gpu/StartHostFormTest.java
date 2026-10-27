/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.MMConstants;
import org.junit.jupiter.api.Test;

class StartHostFormTest {
    @Test
    void aPortIsANumberInTheAllowedRange() {
        assertEquals(MMConstants.DEFAULT_PORT, StartHostForm.port(String.valueOf(MMConstants.DEFAULT_PORT)));
        assertEquals(MMConstants.MIN_PORT, StartHostForm.port(" " + MMConstants.MIN_PORT + " "));
        assertEquals(-1, StartHostForm.port(String.valueOf(MMConstants.MAX_PORT + 1)));
        assertEquals(-1, StartHostForm.port(String.valueOf(MMConstants.MIN_PORT - 1)));
        assertEquals(-1, StartHostForm.port("port"));
        assertEquals(-1, StartHostForm.port(""));
        assertEquals(-1, StartHostForm.port(null));
    }

    @Test
    void theOnlineOptionsOpenWhenThePasswordOrPortWasChanged() {
        assertFalse(StartHostForm.onlineOpen("", MMConstants.DEFAULT_PORT));
        assertFalse(StartHostForm.onlineOpen(null, MMConstants.DEFAULT_PORT));
        assertTrue(StartHostForm.onlineOpen("secret", MMConstants.DEFAULT_PORT));
        assertTrue(StartHostForm.onlineOpen("", MMConstants.DEFAULT_PORT + 1));
    }
}
