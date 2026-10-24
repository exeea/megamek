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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import java.util.function.Function;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.UIManager;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import megamek.client.ui.design.DesignTokens;

/**
 * The design system's lists and trees for Swing (docs/design-system/tokens.md, Lists and trees). The MegaMek look and
 * feels draw every list and tree this way: rows at least {@code control.sm} high, inset and rounded like the popup list,
 * a surface wash under the pointer and, for the selection, the mint wash plus a mint marker at the row's leading
 * edge, so a selected row never relies on color alone. This adds an empty-state text and a two-line row.
 */
public final class TokenLists {
    /** Client property holding the text a list shows while it has no items; set through {@link #emptyText}. */
    static final String EMPTY_TEXT = "MegaMek.list.emptyText";
    /** Client property freezing the hover wash on a row index, for the design gallery. */
    static final String FROZEN_HOVER = "MegaMek.list.frozenHover";
    static final String HOVER_BACKGROUND = "MegaMek.list.hoverBackground";
    static final String MARKER_COLOR = "MegaMek.list.markerColor";
    static final String ROW_HEIGHT = "MegaMek.list.rowHeight";
    static final String TWO_LINE_HEIGHT = "MegaMek.list.twoLineHeight";
    static final String EMPTY_FOREGROUND = "MegaMek.list.emptyForeground";
    static final String DETAIL_FOREGROUND = "MegaMek.list.detailForeground";
    /** The name Swing gives a combo box's popup list; it keeps the select's own rows. */
    static final String POPUP_LIST = "ComboBox.list";

    private TokenLists() {
    }

    /** Wraps a list or tree in the scroll pane that draws its frame and keyboard focus ring. */
    public static JScrollPane scroll(JComponent view) {
        return new JScrollPane(view);
    }

    /** Shows {@code text} in the muted color while {@code list} has no items. */
    public static <T extends JList<?>> T emptyText(T list, String text) {
        list.putClientProperty(EMPTY_TEXT, text);
        list.repaint();
        return list;
    }

    /**
     * Gives {@code list} two-line rows: {@code title} on top in the list's text color and {@code detail} below in a
     * smaller secondary color, such as a unit's name over its tonnage and movement.
     */
    public static <E> JList<E> twoLine(JList<E> list, Function<? super E, String> title,
          Function<? super E, String> detail) {
        list.setCellRenderer(new TwoLineRenderer<>(title, detail));
        return list;
    }

    static boolean isPopupList(JList<?> list) {
        return POPUP_LIST.equals(list.getName());
    }

    /** The row index frozen under the pointer for the gallery, or -1. */
    static int frozenHover(JComponent c) {
        return c.getClientProperty(FROZEN_HOVER) instanceof Integer row ? row : -1;
    }

    /** The scaled minimum row height, 0 outside the MegaMek look and feels. */
    static int rowHeight() {
        return UIScale.scale(Math.max(0, UIManager.getInt(ROW_HEIGHT)));
    }

    /** A row-shaped fill in {@code color}, inset and rounded like the selection; nothing when the color is null. */
    static void paintWash(Graphics g, Color color, int x, int y, int width, int height, Insets insets, int arc) {
        if (color == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(color);
            float radius = UIScale.scale(arc / 2f);
            FlatUIUtils.paintSelection(g2, x, y, width, height, UIScale.scale(insets), radius, radius, radius, radius,
                  0);
        } finally {
            g2.dispose();
        }
    }

    /**
     * The selection marker: a {@code selection.width} bar, {@code icon.sm} tall, at the leading edge of the row's
     * selection shape.
     */
    static void paintMarker(Graphics g, Component c, int x, int y, int width, int height, Insets insets) {
        Color color = UIManager.getColor(MARKER_COLOR);
        if (color == null) {
            return;
        }
        DesignTokens tokens = DesignTokens.shipped();
        Insets inset = UIScale.scale(insets == null ? new Insets(0, 0, 0, 0) : insets);
        float bar = Math.max(1, UIScale.scale(tokens.size().get("selection.width")));
        float length = Math.min(UIScale.scale(tokens.size().get("icon.sm")),
              height - inset.top - inset.bottom - 2 * UIScale.scale(tokens.space().get("xs")));
        if (length <= 0) {
            return;
        }
        float barX = c.getComponentOrientation().isLeftToRight() ? x + inset.left : x + width - inset.right - bar;
        float barY = y + (height - length) / 2f;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fill(new RoundRectangle2D.Float(barX, barY, bar, length, bar, bar));
        } finally {
            g2.dispose();
        }
    }

    /** The muted empty-state text, centered near the top of the visible area. */
    static void paintEmptyText(Graphics g, JComponent c, String text) {
        paintEmptyText(g, c, text, rowHeight());
    }

    /** The muted empty-state text, centered in the first {@code rowHeight} of the visible area. */
    static void paintEmptyText(Graphics g, JComponent c, String text, int rowHeight) {
        Rectangle visible = c.getVisibleRect();
        Insets insets = c.getInsets();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            FlatUIUtils.setRenderingHints(g2);
            g2.setFont(c.getFont());
            Color color = UIManager.getColor(EMPTY_FOREGROUND);
            g2.setColor(color == null ? c.getForeground() : color);
            FontMetrics metrics = g2.getFontMetrics();
            int textWidth = metrics.stringWidth(text);
            int x = visible.x + Math.max(insets.left, (visible.width - textWidth) / 2);
            int y = visible.y + insets.top + (rowHeight - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(text, x, y);
        } finally {
            g2.dispose();
        }
    }

    /**
     * A row with a title over a detail line. It stays a {@link DefaultListCellRenderer}, so the look and feel still
     * rounds and insets its selection.
     */
    static final class TwoLineRenderer<E> extends DefaultListCellRenderer {
        private final Function<? super E, String> title;
        private final Function<? super E, String> detail;
        private String detailText = "";
        private boolean enabled = true;

        TwoLineRenderer(Function<? super E, String> title, Function<? super E, String> detail) {
            this.title = Objects.requireNonNull(title);
            this.detail = Objects.requireNonNull(detail);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
              boolean cellHasFocus) {
            String titleText = value == null ? "" : Objects.toString(title.apply((E) value), "");
            detailText = value == null ? "" : Objects.toString(detail.apply((E) value), "");
            enabled = list.isEnabled();
            super.getListCellRendererComponent(list, titleText, index, isSelected, cellHasFocus);
            getAccessibleContext().setAccessibleName(detailText.isEmpty() ? titleText : titleText + ", " + detailText);
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            FontMetrics titleMetrics = getFontMetrics(getFont());
            FontMetrics detailMetrics = getFontMetrics(detailFont());
            int width = Math.max(titleMetrics.stringWidth(getText()), detailMetrics.stringWidth(detailText));
            int height = titleMetrics.getHeight() + detailMetrics.getHeight();
            int minimum = UIScale.scale(Math.max(0, UIManager.getInt(TWO_LINE_HEIGHT)));
            return new Dimension(insets.left + width + insets.right,
                  Math.max(minimum, insets.top + height + insets.bottom));
        }

        @Override
        protected void paintComponent(Graphics g) {
            // The fill goes through the look and feel's graphics, which turns it into the rounded selection
            if (isOpaque()) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlatUIUtils.setRenderingHints(g2);
                Insets insets = getInsets();
                FontMetrics titleMetrics = g2.getFontMetrics(getFont());
                FontMetrics detailMetrics = g2.getFontMetrics(detailFont());
                int block = titleMetrics.getHeight() + detailMetrics.getHeight();
                int top = insets.top + (getHeight() - insets.top - insets.bottom - block) / 2;
                boolean ltr = getComponentOrientation().isLeftToRight();
                int available = getWidth() - insets.left - insets.right;

                g2.setFont(getFont());
                g2.setColor(getForeground());
                drawLine(g2, getText(), titleMetrics, insets.left, available, top + titleMetrics.getAscent(), ltr);

                g2.setFont(detailFont());
                Color detailColor = enabled ? UIManager.getColor(DETAIL_FOREGROUND) : getForeground();
                g2.setColor(detailColor == null ? getForeground() : detailColor);
                drawLine(g2, detailText, detailMetrics, insets.left, available,
                      top + titleMetrics.getHeight() + detailMetrics.getAscent(), ltr);
            } finally {
                g2.dispose();
            }
        }

        private static void drawLine(Graphics2D g2, String text, FontMetrics metrics, int left, int available,
              int baseline, boolean ltr) {
            String shown = text;
            if (metrics.stringWidth(shown) > available) {
                String ellipsis = "\u2026";
                int end = shown.length();
                while (end > 0 && metrics.stringWidth(shown.substring(0, end) + ellipsis) > available) {
                    end--;
                }
                shown = shown.substring(0, end) + ellipsis;
            }
            int x = ltr ? left : left + available - metrics.stringWidth(shown);
            g2.drawString(shown, x, baseline);
        }

        private Font detailFont() {
            return TokenFonts.font("small");
        }
    }
}
