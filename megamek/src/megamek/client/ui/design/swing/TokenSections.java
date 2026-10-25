/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Presentation;

/**
 * Section headers of the MegaMek look and feels (docs/design-system/tokens.md, Sections): an upper-case
 * {@code overline} title in the secondary text color, then a subtle line to the far edge. The same header names a
 * group of fields ({@link #header}), titles a panel's border ({@link #titled}) and opens and closes a section
 * ({@link #collapsible}). Plain separators stay {@link javax.swing.JSeparator}s.
 * <p>
 * Borders made with {@link javax.swing.BorderFactory#createTitledBorder(String)} keep the JDK's drawing, with the
 * theme's title color and a top line instead of a box; their titles stay in mixed case and sit a little further in
 * than the panel's content.
 */
public final class TokenSections {
    static final String TITLE_FOREGROUND = "MegaMek.section.titleForeground";
    static final String HOVER_FOREGROUND = "MegaMek.section.hoverForeground";
    static final String LINE_COLOR = "MegaMek.section.lineColor";
    static final String HOVER_BACKGROUND = "MegaMek.section.hoverBackground";
    static final String HEIGHT = "MegaMek.section.height";
    static final String TITLE_GAP = "MegaMek.section.titleGap";
    static final String ROW_ARC = "MegaMek.section.arc";

    private TokenSections() {
    }

    /** A section header naming the fields below it; with an empty title it is just the line. */
    public static JLabel header(String title) {
        return new Header(title);
    }

    /** A border titling a panel with a section header; the content starts below it, flush with the title. */
    public static TitledBorder titled(String title) {
        return new TokenTitledBorder(title);
    }

    /** A section whose header opens and closes {@code content}, starting open or closed. */
    public static TokenCollapsibleSection collapsible(String title, JComponent content, boolean expanded) {
        return new TokenCollapsibleSection(title, content, expanded);
    }

    /** The scaled header height: {@code control.sm}, the height of a list row. */
    static int height() {
        int height = UIManager.getInt(HEIGHT);
        return UIScale.scale(height > 0 ? height : 28);
    }

    static int titleGap() {
        return UIScale.scale(UIManager.getInt(TITLE_GAP));
    }

    static Font titleFont() {
        return TokenFonts.font("overline");
    }

    /** The title font for {@code c}'s screen: overline, condensed in game mode. */
    static Font titleFont(Component c) {
        Presentation mode = c == null ? Presentation.APP : TokenPresentation.mode(c);
        return TokenFonts.font("overline", true, DesignTokens.shipped().condensed("overline", mode));
    }

    /** The font a header in {@code c} draws its title in: {@code set}, unless it is the theme's title font. */
    static Font titleFont(Component c, Font set) {
        return set == null || set.equals(titleFont()) ? titleFont(c) : set;
    }

    static String upperCase(String title, Component c) {
        Locale locale = c == null ? Locale.getDefault() : c.getLocale();
        return title == null ? "" : title.toUpperCase(locale);
    }

    /** The width of {@code text} in {@code font}, tracking included. */
    static float advance(String text, Font font, FontRenderContext context) {
        return text.isEmpty() ? 0 : new TextLayout(text, font, context).getAdvance();
    }

    /**
     * Paints a header in the given bounds: the title at the leading edge, centred vertically, and the line from past it
     * to the trailing edge, level with the middle of the capitals.
     */
    static void paintHeader(Graphics g, Component c, String title, Font font, Color titleColor, int x, int y,
          int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            FlatUIUtils.setRenderingHints(g2);
            g2.setFont(font);
            FontMetrics metrics = g2.getFontMetrics();
            String text = upperCase(title, c);
            float textWidth = advance(text, font, g2.getFontRenderContext());
            int baseline = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
            boolean leftToRight = c == null || c.getComponentOrientation().isLeftToRight();
            if (!text.isEmpty()) {
                g2.setColor(titleColor);
                int textX = leftToRight ? x : x + width - Math.round(textWidth);
                FlatUIUtils.drawString(c instanceof JComponent component ? component : null, g2, text, textX,
                      baseline);
            }
            Color lineColor = UIManager.getColor(LINE_COLOR);
            if (lineColor == null) {
                return;
            }
            float gap = text.isEmpty() ? 0 : textWidth + titleGap();
            float line = UIScale.scale(1f);
            Rectangle2D caps = font.createGlyphVector(g2.getFontRenderContext(), "H").getVisualBounds();
            float lineY = (float) (baseline + caps.getCenterY() - line / 2f);
            float lineWidth = width - gap;
            if (lineWidth > 0) {
                g2.setColor(lineColor);
                g2.fill(new Rectangle2D.Float(leftToRight ? x + gap : x, Math.round(lineY), lineWidth, line));
            }
        } finally {
            g2.dispose();
        }
    }

    /** The title color for a component's state. */
    static Color titleColor(Component c, boolean hover) {
        if (c != null && !c.isEnabled()) {
            return UIManager.getColor("Label.disabledForeground");
        }
        Color color = UIManager.getColor(hover ? HOVER_FOREGROUND : TITLE_FOREGROUND);
        return color != null ? color : UIManager.getColor("Label.foreground");
    }

    /** The narrowest a header can be: its title and a short stretch of line. */
    static int minimumWidth(String title, Component c) {
        String text = upperCase(title, c);
        Font font = titleFont(c);
        FontRenderContext context = c == null ? new FontRenderContext(null, true, true)
              : c.getFontMetrics(font).getFontRenderContext();
        return (int) Math.ceil(advance(text, font, context)) + 2 * titleGap();
    }

    /** A standalone header: a label, so screen readers read its title as written rather than in capitals. */
    private static final class Header extends JLabel {
        Header(String title) {
            super(title);
            setFont(titleFont());
            setAlignmentX(LEFT_ALIGNMENT);
        }

        @Override
        public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) {
                return super.getPreferredSize();
            }
            Insets insets = getInsets();
            return new Dimension(minimumWidth(getText(), this) + insets.left + insets.right,
                  height() + insets.top + insets.bottom);
        }

        @Override
        public Dimension getMinimumSize() {
            return isMinimumSizeSet() ? super.getMinimumSize() : getPreferredSize();
        }

        /** As wide as its container allows, so the line runs to the far edge. */
        @Override
        public Dimension getMaximumSize() {
            return isMaximumSizeSet() ? super.getMaximumSize()
                  : new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (isOpaque()) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            Insets insets = getInsets();
            paintHeader(g, this, getText(), titleFont(this, getFont()), titleColor(this, false), insets.left,
                  insets.top, getWidth() - insets.left - insets.right, getHeight() - insets.top - insets.bottom);
        }
    }
}
