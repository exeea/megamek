/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import megamek.client.ui.Messages;

/**
 * When the 3D board shows every unit's nameplate: while the Show All Nameplates key is held, toggled by that key, or
 * always. The other units show their pips meanwhile.
 */
public enum NameplateMode {
    HOLD,
    TOGGLE,
    ALWAYS;

    @Override
    public String toString() {
        return Messages.getString("NameplateMode." + name());
    }
}
