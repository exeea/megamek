/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/** Selects the external, complete bridge model for the authoritative exit mask. */
final class BoardBridge {
    private BoardBridge() { }

    static String asset(int exits) {
        return exits == 9 ? "bridge" : "bridges/bridge-exits-" + String.format(java.util.Locale.ROOT, "%02d", exits);
    }
}
