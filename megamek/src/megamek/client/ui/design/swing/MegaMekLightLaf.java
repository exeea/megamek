/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import javax.swing.UIDefaults;

import com.formdev.flatlaf.FlatLightLaf;
import megamek.client.ui.design.DesignTokens.Mode;

/** The light MegaMek look and feel: FlatLaf light with every value taken from the design tokens. */
public class MegaMekLightLaf extends FlatLightLaf {
    public static final String NAME = "MegaMek Light";

    public MegaMekLightLaf() {
        setExtraDefaults(TokenLafDefaults.of(Mode.LIGHT));
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
    }

    @Override
    public void uninitialize() {
        FieldEdge.uninstall();
        FocusVisibility.uninstall();
        super.uninitialize();
    }
}
