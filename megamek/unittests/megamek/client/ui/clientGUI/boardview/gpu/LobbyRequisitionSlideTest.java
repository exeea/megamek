/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LobbyRequisitionSlideTest {

    @Test
    void theDrawerSlidesFromWhereItStandsToItsTarget() {
        assertEquals(0, LobbyRequisitionPanel.slideHeight(0, 120, 0, .25f), .001f);
        assertEquals(120, LobbyRequisitionPanel.slideHeight(0, 120, .25f, .25f), .001f);
        assertEquals(120, LobbyRequisitionPanel.slideHeight(0, 120, 1, .25f), .001f, "a slide over stays put");
    }

    @Test
    void theSlideIsQuickAtFirstThenEases() {
        float half = LobbyRequisitionPanel.slideHeight(0, 120, .125f, .25f);
        assertTrue(half > 60 && half < 120, "past halfway at half time: " + half);
        float shutting = LobbyRequisitionPanel.slideHeight(120, 0, .125f, .25f);
        assertTrue(shutting < 60 && shutting > 0, "shutting runs the other way: " + shutting);
    }

    @Test
    void withoutADurationTheDrawerJumps() {
        assertEquals(80, LobbyRequisitionPanel.slideHeight(10, 80, 0, 0), .001f);
    }
}
