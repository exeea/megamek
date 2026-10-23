/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatScrollPaneUI;
import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * Scroll panes of the MegaMek look and feels: they fill behind their scroll bars and the corner between them with the
 * color of the content they scroll, so a list's bars sit on the list's surface (docs/design-system/tokens.md,
 * Scrollbars).
 */
public class TokenScrollPaneUI extends FlatScrollPaneUI {
    public static ComponentUI createUI(JComponent c) {
        return new TokenScrollPaneUI();
    }

    @Override
    public void update(Graphics g, JComponent c) {
        Color content = TokenScrollBarUI.contentBackground(scrollpane);
        if (!c.isOpaque() || content == null || content.equals(c.getBackground())) {
            super.update(g, c);
            return;
        }
        FlatUIUtils.paintParentBackground(g, c);
        Insets insets = c.getInsets();
        g.setColor(content);
        g.fillRect(insets.left, insets.top, c.getWidth() - insets.left - insets.right,
              c.getHeight() - insets.top - insets.bottom);
        paint(g, c);
    }
}
