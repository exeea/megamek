/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

import com.formdev.flatlaf.ui.FlatSplitPaneUI;
import com.formdev.flatlaf.util.UIScale;

/**
 * Split panes of the MegaMek look and feels (docs/design-system/tokens.md, Split panes): the divider is a subtle line
 * in the middle of a wider grab area, and turns into a thicker accent line under the pointer and while dragged.
 */
public class TokenSplitPaneUI extends FlatSplitPaneUI {
    /** Client property freezing the divider in its hover look, for the design gallery. */
    static final String FROZEN_HOVER = "MegaMek.splitPane.frozenHover";
    static final String LINE_COLOR = "MegaMek.splitPane.lineColor";
    static final String ACTIVE_COLOR = "MegaMek.splitPane.activeColor";
    static final String LINE_WIDTH = "MegaMek.splitPane.lineWidth";
    static final String ACTIVE_WIDTH = "MegaMek.splitPane.activeWidth";

    public static ComponentUI createUI(JComponent c) {
        return new TokenSplitPaneUI();
    }

    @Override
    public BasicSplitPaneDivider createDefaultDivider() {
        return new LineDivider(this);
    }

    /** The divider: a line along its middle, accent while the pointer is over it or it is dragged. */
    protected class LineDivider extends FlatSplitPaneDivider {
        protected LineDivider(BasicSplitPaneUI ui) {
            super(ui);
        }

        private boolean active() {
            return dragger != null || isMouseOver() || Boolean.TRUE.equals(splitPane.getClientProperty(FROZEN_HOVER));
        }

        @Override
        protected void paintStyle(Graphics g, int x, int y, int width, int height) {
            boolean active = splitPane.isEnabled() && active();
            Color color = UIManager.getColor(active ? ACTIVE_COLOR : LINE_COLOR);
            if (color == null) {
                super.paintStyle(g, x, y, width, height);
                return;
            }
            float line = UIScale.scale((float) Math.max(1, UIManager.getInt(active ? ACTIVE_WIDTH : LINE_WIDTH)));
            boolean vertical = splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(color);
                g2.fill(vertical
                      ? new Rectangle2D.Float(x, y + (height - line) / 2f, width, line)
                      : new Rectangle2D.Float(x + (width - line) / 2f, y, line, height));
            } finally {
                g2.dispose();
            }
        }

        // FlatLaf repaints on these only when it has hover colors; the line changes in any case
        @Override
        protected void setMouseOver(boolean mouseOver) {
            super.setMouseOver(mouseOver);
            repaint();
        }

        @Override
        protected void prepareForDragging() {
            super.prepareForDragging();
            repaint();
        }

        @Override
        protected void finishDraggingTo(int location) {
            super.finishDraggingTo(location);
            repaint();
        }
    }
}
