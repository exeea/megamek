/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.UIManager.LookAndFeelInfo;

import megamek.client.ui.clientGUI.GUIPreferences;
import org.junit.jupiter.api.Test;

/** The settings' theme picker leads with the MegaMek themes and lists the Flat themes as "classic" ones. */
class MegaMekThemeSelectionTest {

    @Test
    void megaMekThemesAreSupportedAndNotClassic() {
        for (String className : new String[] { MegaMekDarkLaf.class.getName(), MegaMekLightLaf.class.getName() }) {
            assertTrue(GUIPreferences.isMegaMekTheme(className), className);
            assertTrue(GUIPreferences.isSupportedLookAndFeel(new LookAndFeelInfo("theme", className)), className);
        }
    }

    @Test
    void flatThemesStaySupportedAsClassicThemes() {
        String darcula = "com.formdev.flatlaf.FlatDarculaLaf";
        assertFalse(GUIPreferences.isMegaMekTheme(darcula));
        assertTrue(GUIPreferences.isSupportedLookAndFeel(new LookAndFeelInfo("Flat Darcula", darcula)));
    }

    @Test
    void otherLookAndFeelsAreNeitherMegaMekNorSupported() {
        String metal = "javax.swing.plaf.metal.MetalLookAndFeel";
        assertFalse(GUIPreferences.isMegaMekTheme(metal));
        assertFalse(GUIPreferences.isMegaMekTheme(null));
        assertFalse(GUIPreferences.isSupportedLookAndFeel(new LookAndFeelInfo("Metal", metal)));
    }
}
