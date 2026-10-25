/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.geom.Rectangle2D;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.UIResource;

import com.formdev.flatlaf.util.UIScale;

/**
 * A panel border that is a section header (docs/design-system/tokens.md, Sections): the upper-case title and its line
 * across the top, and the content below, flush with the title. Made by {@link TokenSections#titled}. The title's
 * position and justification are ignored; a title font or color set on the border replaces the theme's.
 */
public class TokenTitledBorder extends TitledBorder {
    public TokenTitledBorder(String title) {
        super(title);
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        // The fields, not the getters: those fall back to the legacy TitledBorder keys
        Color color = titleColor != null ? titleColor : TokenSections.titleColor(c, false);
        TokenSections.paintHeader(g, c, getTitle(), titleFont != null ? titleFont : TokenSections.titleFont(c), color,
              x, y, width, TokenSections.height());
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.set(TokenSections.height(), 0, 0, 0);
        return insets;
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }

    @Override
    public Dimension getMinimumSize(Component c) {
        return new Dimension(TokenSections.minimumWidth(getTitle(), c), TokenSections.height());
    }

    @Override
    public int getBaseline(Component c, int width, int height) {
        return -1;
    }

    /**
     * The theme's {@code TitledBorder.border}: what legacy titled borders draw around their content, here only the
     * line through the middle of their title. It starts past the stub the JDK leaves before the title.
     */
    public static class LegacyLine extends AbstractBorder implements UIResource {
        /** The JDK's gap between the border's edge and the title's clip, in unscaled pixels as the JDK uses them. */
        private static final int STUB = 3;

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Color color = UIManager.getColor(TokenSections.LINE_COLOR);
            if (color == null || width <= STUB) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(color);
                boolean leftToRight = c == null || c.getComponentOrientation().isLeftToRight();
                g2.fill(new Rectangle2D.Float(leftToRight ? x + STUB : x, y, width - STUB, UIScale.scale(1f)));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(1, 0, 0, 0);
            return insets;
        }
    }
}
