/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import megamek.client.ui.design.DesignTokens;

/**
 * Tabs with the design-system focus ring: a {@code focus.width} ring inside the selected tab while the tab strip has
 * keyboard focus. FlatLaf only tints the focused tab, which reads the same as hover.
 */
public class TokenTabbedPaneUI extends FlatTabbedPaneUI {
    public static ComponentUI createUI(JComponent c) {
        return new TokenTabbedPaneUI();
    }

    @Override
    protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h,
          boolean isSelected) {
        super.paintTabBorder(g, tabPlacement, tabIndex, x, y, w, h, isSelected);
        if (isSelected && tabPane.isEnabled() && FlatUIUtils.isPermanentFocusOwner(tabPane)) {
            float ring = UIScale.scale(DesignTokens.shipped().size().get("focus.width"));
            Area area = new Area(new Rectangle2D.Float(x, y, w, h));
            area.subtract(new Area(new Rectangle2D.Float(x + ring, y + ring, w - ring * 2, h - ring * 2)));
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(UIManager.getColor("Component.focusColor"));
                g2.fill(area);
            } finally {
                g2.dispose();
            }
        }
    }
}
