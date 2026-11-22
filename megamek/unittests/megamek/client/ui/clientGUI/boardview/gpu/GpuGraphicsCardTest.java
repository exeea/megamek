/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.client.ui.clientGUI.GUIPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The saved graphics card is the one store of the choice: nothing saved is no choice (the start screen asks) while
 * the board still draws on the NVIDIA card; either saved card is a choice; the system property chooses for one run.
 */
class GpuGraphicsCardTest {
    private String property;

    @BeforeEach
    void noProperty() {
        property = System.clearProperty(GpuGraphicsCard.PROPERTY);
    }

    @AfterEach
    void restore() {
        GUIPreferences.getInstance().setBoardGraphicsCard("");
        if (property != null) {
            System.setProperty(GpuGraphicsCard.PROPERTY, property);
        }
    }

    @Test
    void nothingSavedIsNoChoiceAndDrawsOnTheNvidiaCard() {
        GUIPreferences.getInstance().setBoardGraphicsCard("");
        assertFalse(GpuGraphicsCard.chosen(), "an unset card must not read as a choice of DISCRETE");
        assertEquals(GpuGraphicsCard.DISCRETE, GpuGraphicsCard.preferred());
    }

    @Test
    void eitherSavedCardIsAChoice() {
        for (GpuGraphicsCard card : GpuGraphicsCard.values()) {
            GUIPreferences.getInstance().setBoardGraphicsCard(card.name());
            assertEquals(card.name(), GUIPreferences.getInstance().getBoardGraphicsCard(), "saved as itself");
            assertTrue(GpuGraphicsCard.chosen(), card + " saved");
            assertEquals(card, GpuGraphicsCard.preferred());
        }
    }

    @Test
    void thePropertyChoosesForTheRun() {
        GUIPreferences.getInstance().setBoardGraphicsCard("");
        System.setProperty(GpuGraphicsCard.PROPERTY, "integrated");
        try {
            assertTrue(GpuGraphicsCard.chosen(), "a run with the property never asks");
            assertFalse(GpuGraphicsCard.asks());
            assertEquals(GpuGraphicsCard.INTEGRATED, GpuGraphicsCard.preferred());
        } finally {
            System.clearProperty(GpuGraphicsCard.PROPERTY);
        }
    }
}
