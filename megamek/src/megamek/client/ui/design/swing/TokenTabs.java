/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Insets;
import javax.swing.JTabbedPane;

import com.formdev.flatlaf.FlatClientProperties;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.swing.TokenButtons.Size;

/**
 * The design system's tabs for Swing (docs/design-system/tokens.md, Tabs): secondary labels on the window surface, a
 * mint underline under the selected tab and a hairline under the strip. The MegaMek look and feels draw every tab
 * pane this way at the compact size; this gives a tab pane the regular size, for top-level page switching.
 */
public final class TokenTabs {
    private TokenTabs() {
    }

    public static JTabbedPane tabs(Size size) {
        return size(new JTabbedPane(), size);
    }

    /**
     * Gives a tab pane the tab height and side padding of {@code size}; its titles follow their screen's text case
     * mode ({@link TokenPresentation}).
     */
    public static <T extends JTabbedPane> T size(T tabs, Size size) {
        int side = Math.round(DesignTokens.shipped().space().get(size == Size.REGULAR ? "lg" : "md"));
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT, size.height());
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_INSETS, new Insets(0, side, 0, side));
        return TokenPresentation.role(tabs, "tab");
    }
}
