/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.gameConnectionDialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import megamek.MMConstants;
import org.junit.jupiter.api.Test;

class AbstractGameConnectionDialogTest {
    @Test
    void portMustBeANumberInTheAllowedRange() {
        assertEquals(MMConstants.DEFAULT_PORT, AbstractGameConnectionDialog.parsePort(" " + MMConstants.DEFAULT_PORT));
        assertEquals(MMConstants.MIN_PORT, AbstractGameConnectionDialog.parsePort(String.valueOf(MMConstants.MIN_PORT)));
        assertEquals(MMConstants.MAX_PORT, AbstractGameConnectionDialog.parsePort(String.valueOf(MMConstants.MAX_PORT)));
        assertEquals(-1, AbstractGameConnectionDialog.parsePort(String.valueOf(MMConstants.MIN_PORT - 1)));
        assertEquals(-1, AbstractGameConnectionDialog.parsePort(String.valueOf(MMConstants.MAX_PORT + 1)));
        assertEquals(-1, AbstractGameConnectionDialog.parsePort(""));
        assertEquals(-1, AbstractGameConnectionDialog.parsePort("2,346"));
    }
}
