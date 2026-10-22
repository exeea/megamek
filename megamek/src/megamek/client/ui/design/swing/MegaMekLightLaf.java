/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

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
    public void initialize() {
        super.initialize();
        FocusVisibility.install();
    }

    @Override
    public void uninitialize() {
        FocusVisibility.uninstall();
        super.uninitialize();
    }
}
