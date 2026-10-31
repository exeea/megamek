/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UiScrollBarTest {

    @Test
    void thePointerIsOnTheBarAcrossItsWholeStrip() {
        // A pane ending at 300 whose bar and gap take the last 14
        assertTrue(UiKit.overScrollBar(286, 300, 14));
        assertTrue(UiKit.overScrollBar(293, 300, 14));
        assertTrue(UiKit.overScrollBar(300, 300, 14));
    }

    @Test
    void thePointerIsOffTheBarOverTheListOrOutsideThePane() {
        assertFalse(UiKit.overScrollBar(285.5f, 300, 14));
        assertFalse(UiKit.overScrollBar(301, 300, 14));
        assertFalse(UiKit.overScrollBar(-1, 300, 14), "a negative x is a pointer that left the pane");
    }

    @Test
    void aPaneWithoutABarHasNoBarToBeOn() {
        assertFalse(UiKit.overScrollBar(299, 300, 0));
    }
}
