/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/** The window can pause or close while its loading screen shows, before any map is attached. */
class GpuBattleViewPauseTest {

    @Test
    void pausingBeforeAMapIsAttachedDoesNotCrash() {
        var view = new GpuBattleView(null);
        assertDoesNotThrow(view::pause, "Closing the window during loading must not ask the absent map's editor");
    }
}
