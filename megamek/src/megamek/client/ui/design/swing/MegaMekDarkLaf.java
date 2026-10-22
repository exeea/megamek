/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import megamek.client.ui.design.DesignTokens.Mode;

/** The dark MegaMek look and feel: FlatLaf dark with every value taken from the design tokens. */
public class MegaMekDarkLaf extends FlatDarkLaf {
    public static final String NAME = "MegaMek Dark";

    public MegaMekDarkLaf() {
        setExtraDefaults(TokenLafDefaults.of(Mode.DARK));
    }

    @Override
    public String getName() {
        return NAME;
    }
}
