/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static megamek.client.ui.util.UIUtil.scaleForGUI;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JToolTip;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicHTML;

import com.formdev.flatlaf.ui.FlatToolTipUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.swing.TokenTooltips.Card;

/**
 * Tooltips of the MegaMek look and feels (docs/design-system/tokens.md, Tooltips): plain text wraps at a maximum width,
 * and a {@link TokenTooltips#card} draws its title, detail and shortcut chip. HTML tooltips are left to FlatLaf.
 */
public class TokenToolTipUI extends FlatToolTipUI {

    public static ComponentUI createUI(JComponent c) {
        return new TokenToolTipUI();
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        Layout layout = layout(c);
        if (layout == null) {
            return super.getPreferredSize(c);
        }
        Insets insets = c.getInsets();
        return new Dimension(layout.width() + insets.left + insets.right, layout.height() + insets.top + insets.bottom);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        Layout layout = layout(c);
        if (layout == null) {
            super.paint(g, c);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            FlatUIUtils.setRenderingHints(g2);
            Insets insets = c.getInsets();
            int x = insets.left;
            int y = insets.top + layout.textTop();
            y = paintLines(c, g2, layout.titleLines(), layout.titleFont(), c.getForeground(), x, y);
            if (!layout.titleLines().isEmpty() && !layout.detailLines().isEmpty()) {
                y += layout.gap();
            }
            paintLines(c, g2, layout.detailLines(), layout.detailFont(), layout.detailColor(), x, y);
            if (layout.key() != null) {
                paintKey(c, g2, layout, c.getWidth() - insets.right - layout.keyWidth(), insets.top);
            }
        } finally {
            g2.dispose();
        }
    }

    /** The card or wrapped text layout of {@code c}'s tip, or null for HTML and empty tips. */
    private static Layout layout(JComponent c) {
        if (!(c instanceof JToolTip tip) || tip.getTipText() == null || tip.getTipText().isEmpty()
              || BasicHTML.isHTMLString(tip.getTipText())) {
            return null;
        }
        Card card = tip.getComponent() != null
              && tip.getComponent().getClientProperty(TokenTooltips.CARD) instanceof Card found
              && found.text().equals(tip.getTipText()) ? found : new Card(null, tip.getTipText(), null);
        DesignTokens tokens = DesignTokens.shipped();
        Font titleFont = TokenFonts.font("body.strong");
        Font detailFont = TokenFonts.font("small");
        Font keyFont = TokenFonts.font("caption");
        FontMetrics titleMetrics = c.getFontMetrics(titleFont);
        FontMetrics detailMetrics = c.getFontMetrics(detailFont);
        FontMetrics keyMetrics = c.getFontMetrics(keyFont);

        int keyWidth = 0;
        int keyHeight = 0;
        int keyGap = 0;
        if (card.key() != null) {
            int padX = space("xs");
            int padY = space("xxs");
            keyWidth = keyMetrics.stringWidth(card.key()) + 2 * padX;
            keyHeight = keyMetrics.getHeight() + 2 * padY;
            keyGap = space("md");
        }
        int maxWidth = scaleForGUI(UIManager.getInt(TokenTooltips.MAX_WIDTH));
        int textWidth = Math.max(space("xxxl"), maxWidth - keyWidth - keyGap);
        List<String> titleLines = card.title() == null ? List.of() : wrap(card.title(), titleMetrics, textWidth);
        List<String> detailLines = card.detail() == null ? List.of() : wrap(card.detail(), detailMetrics, textWidth);

        int width = 0;
        for (String line : titleLines) {
            width = Math.max(width, titleMetrics.stringWidth(line));
        }
        for (String line : detailLines) {
            width = Math.max(width, detailMetrics.stringWidth(line));
        }
        int gap = space("xxs");
        int height = titleLines.size() * titleMetrics.getHeight() + detailLines.size() * detailMetrics.getHeight();
        if (!titleLines.isEmpty() && !detailLines.isEmpty()) {
            height += gap;
        }
        FontMetrics firstLine = titleLines.isEmpty() ? detailMetrics : titleMetrics;
        int textTop = 0;
        float keyTop = 0;
        if (card.key() != null) {
            // The chip's text shares the first line's baseline, and the chip centers on its capitals; the chip keeps
            // its fractional position so whole-pixel rounding doesn't push it off center at scaled sizes
            float capCenter = firstLine.getAscent() - capHeight(keyMetrics) / 2;
            keyTop = capCenter - keyHeight / 2f;
            if (keyTop < 0) {
                textTop = (int) Math.ceil(-keyTop);
                keyTop += textTop;
            }
            width += keyGap + keyWidth;
            height = Math.max(textTop + height, (int) Math.ceil(keyTop + keyHeight));
        }
        Color detailColor = card.title() == null ? c.getForeground()
              : UIManager.getColor(TokenTooltips.DETAIL_FOREGROUND);
        return new Layout(titleLines, detailLines, titleFont, detailFont, detailColor, gap, width, height, card.key(),
              keyFont, keyWidth, keyHeight, textTop, keyTop, firstLine.getAscent());
    }

    private static int space(String name) {
        return Math.round(scaleForGUI(DesignTokens.shipped().space().get(name)));
    }

    private static int paintLines(JComponent c, Graphics2D g2, List<String> lines, Font font, Color color, int x, int y) {
        g2.setFont(font);
        g2.setColor(color);
        FontMetrics metrics = c.getFontMetrics(g2.getFont());
        for (String line : lines) {
            FlatUIUtils.drawString(c, g2, line, x, y + metrics.getAscent());
            y += metrics.getHeight();
        }
        return y;
    }

    /** The shortcut chip: a key cap with a control edge, its text on the first line's baseline. */
    private static void paintKey(JComponent c, Graphics2D g2, Layout layout, int x, int top) {
        float y = top + layout.keyTop();
        float arc = scaleForGUI(DesignTokens.shipped().radius().get("sm") * 2);
        g2.setColor(UIManager.getColor(TokenTooltips.KEY_BORDER_COLOR));
        FlatUIUtils.paintOutline(g2, x, y, layout.keyWidth(), layout.keyHeight(), scaleForGUI(1), arc);
        g2.setFont(layout.keyFont());
        g2.setColor(UIManager.getColor(TokenTooltips.KEY_FOREGROUND));
        FontMetrics metrics = c.getFontMetrics(g2.getFont());
        int textX = x + (layout.keyWidth() - metrics.stringWidth(layout.key())) / 2;
        int textY = top + layout.textTop() + layout.firstAscent();
        FlatUIUtils.drawString(c, g2, layout.key(), textX, textY);
    }

    /** The height of a capital letter, so a line can be centered on its capitals rather than its whole line box. */
    private static float capHeight(FontMetrics metrics) {
        return (float) metrics.getFont().createGlyphVector(metrics.getFontRenderContext(), "H").getVisualBounds()
              .getHeight();
    }

    /** Breaks {@code text} into lines no wider than {@code width}, at line breaks and spaces where it can. */
    static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split(" +")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (metrics.stringWidth(candidate) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                // A word longer than the line is broken where it fills the line
                while (metrics.stringWidth(word) > width && word.length() > 1) {
                    int end = word.length() - 1;
                    while (end > 1 && metrics.stringWidth(word.substring(0, end)) > width) {
                        end--;
                    }
                    lines.add(word.substring(0, end));
                    word = word.substring(end);
                }
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private record Layout(List<String> titleLines, List<String> detailLines, Font titleFont, Font detailFont,
          Color detailColor, int gap, int width, int height, String key, Font keyFont, int keyWidth, int keyHeight,
          int textTop, float keyTop, int firstAscent) {
    }
}
