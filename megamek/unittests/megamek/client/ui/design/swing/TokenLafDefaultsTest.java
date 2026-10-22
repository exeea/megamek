/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Color;
import java.util.Map;
import javax.swing.UIDefaults;

import com.formdev.flatlaf.FlatLaf;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import org.junit.jupiter.api.Test;

class TokenLafDefaultsTest {
    private final DesignTokens tokens = DesignTokens.shipped();

    @Test
    void darkLookAndFeelUsesTheTokens() {
        assertUsesTokens(new MegaMekDarkLaf(), Mode.DARK);
    }

    @Test
    void lightLookAndFeelUsesTheTokens() {
        assertUsesTokens(new MegaMekLightLaf(), Mode.LIGHT);
    }

    @Test
    void everyColorValueIsOpaque() {
        for (Mode mode : Mode.values()) {
            for (Map.Entry<String, String> entry : TokenLafDefaults.of(mode).entrySet()) {
                if (entry.getValue().startsWith("#")) {
                    assertEquals(7, entry.getValue().length(), entry.getKey() + " must be opaque");
                }
            }
        }
    }

    private void assertUsesTokens(FlatLaf lookAndFeel, Mode mode) {
        UIDefaults defaults = lookAndFeel.getDefaults();
        assertColor(defaults, "Panel.background", "surface.base", mode);
        assertColor(defaults, "Label.foreground", "text.primary", mode);
        assertColor(defaults, "TextField.background", "surface.input", mode);
        assertColor(defaults, "Component.borderColor", "border.control", mode);
        assertColor(defaults, "Component.focusColor", "focus.ring", mode);
        assertColor(defaults, "Button.default.background", "accent.primary", mode);
        assertColor(defaults, "Button.default.foreground", "text.onAccent", mode);
        assertColor(defaults, "ProgressBar.foreground", "accent.primary", mode);
        assertColor(defaults, "Component.error.borderColor", "status.danger", mode);
        int selection = ColorMath.over(tokens.color("selected.background", mode), tokens.color("surface.input", mode));
        assertEquals(new Color(selection), new Color(defaults.getColor("Table.selectionBackground").getRGB()));
        assertNotNull(defaults.get("[style]Button.primary"), "primary button style class");
    }

    private void assertColor(UIDefaults defaults, String key, String role, Mode mode) {
        Color actual = defaults.getColor(key);
        assertNotNull(actual, key);
        assertEquals(new Color(tokens.color(role, mode), true), new Color(actual.getRGB(), true), key);
    }
}
