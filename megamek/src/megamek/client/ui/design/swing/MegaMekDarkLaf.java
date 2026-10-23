/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import javax.swing.UIDefaults;

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

    @Override
    public UIDefaults getDefaults() {
        UIDefaults defaults = super.getDefaults();
        TokenLafDefaults.putFonts(defaults);
        return defaults;
    }

    @Override
    public void initialize() {
        super.initialize();
        FocusVisibility.install();
        FieldEdge.install();
        TokenPopupFactory.install();
    }

    @Override
    public void uninitialize() {
        TokenPopupFactory.uninstall();
        FieldEdge.uninstall();
        FocusVisibility.uninstall();
        super.uninitialize();
    }
}
