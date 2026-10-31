/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class GpuLobbyProbeTest {
    @Test
    void onlyTheLobbyPageMakesAProbe() {
        assertNull(GpuLobbyProbe.from(Map.of()));
        assertNull(GpuLobbyProbe.from(Map.of("MM_START_PAGE", "skirmish")));
    }

    @Test
    void readsTheWindowSize() {
        GpuLobbyProbe probe = GpuLobbyProbe.from(Map.of("MM_START_PAGE", "lobby", "MM_LOBBY_SIZE", "1600x900"));

        assertTrue(probe.sized());
        assertEquals(1600, probe.width());
        assertEquals(900, probe.height());
    }

    @Test
    void narrowSizesTheWindowUnlessASizeIsGiven() {
        GpuLobbyProbe narrow = GpuLobbyProbe.from(Map.of("MM_START_PAGE", "lobby+narrow+rail"));
        GpuLobbyProbe sized = GpuLobbyProbe.from(Map.of("MM_START_PAGE", "lobby+narrow",
              "MM_LOBBY_SIZE", "1000X700"));

        assertEquals(GpuLobbyProbe.NARROW_WIDTH, narrow.width());
        assertEquals(GpuLobbyProbe.NARROW_HEIGHT, narrow.height());
        assertTrue(narrow.options().contains("rail"));
        assertEquals(1000, sized.width());
        assertEquals(700, sized.height());
    }

    @Test
    void ignoresASizeItCannotRead() {
        for (String size : new String[] { "wide", "1600", "1600x", "0x900", "1600x900x2" }) {
            assertFalse(GpuLobbyProbe.from(Map.of("MM_START_PAGE", "lobby", "MM_LOBBY_SIZE", size)).sized(), size);
        }
    }
}
