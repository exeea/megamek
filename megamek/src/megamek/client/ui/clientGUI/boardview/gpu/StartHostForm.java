/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.MMConstants;
import megamek.codeUtilities.StringUtility;

/** The start screen's Host and Connect page rules: which inputs are wrong and which options start open. */
final class StartHostForm {
    private StartHostForm() {
    }

    /** The port in {@code text}, or -1 when it is not a number in the allowed range. */
    static int port(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return (value >= MMConstants.MIN_PORT && value <= MMConstants.MAX_PORT) ? value : -1;
        } catch (NumberFormatException | NullPointerException ex) {
            return -1;
        }
    }

    /** Whether the online options start open: they do when the last game changed the password or the port. */
    static boolean onlineOpen(String lastPassword, int lastPort) {
        return !StringUtility.isNullOrBlank(lastPassword) || lastPort != MMConstants.DEFAULT_PORT;
    }
}
