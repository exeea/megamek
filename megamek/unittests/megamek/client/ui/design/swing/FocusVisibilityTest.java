/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import javax.swing.JTextField;

import org.junit.jupiter.api.Test;

class FocusVisibilityTest {
    private final JButton source = new JButton();

    @Test
    void keyPressShowsAndMousePressHides() {
        FocusVisibility.input(mouse());
        assertFalse(FocusVisibility.keyboardActive());
        FocusVisibility.input(key(KeyEvent.VK_TAB));
        assertTrue(FocusVisibility.keyboardActive());
        FocusVisibility.input(mouse());
        assertFalse(FocusVisibility.keyboardActive());
    }

    @Test
    void modifierKeysDoNotShowTheRing() {
        FocusVisibility.input(mouse());
        for (int modifier : new int[] { KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT }) {
            FocusVisibility.input(key(modifier));
            assertFalse(FocusVisibility.keyboardActive());
        }
    }

    @Test
    void onlyEditableTextAlwaysShowsTheRing() {
        JTextField editable = new JTextField();
        JTextField readOnly = new JTextField();
        readOnly.setEditable(false);
        assertTrue(FocusVisibility.alwaysShows(editable));
        assertFalse(FocusVisibility.alwaysShows(readOnly));
        assertFalse(FocusVisibility.alwaysShows(source));
    }

    private KeyEvent key(int keyCode) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, keyCode, KeyEvent.CHAR_UNDEFINED);
    }

    private MouseEvent mouse() {
        return new MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1);
    }
}
