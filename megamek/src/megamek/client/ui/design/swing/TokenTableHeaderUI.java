/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.List;
import javax.swing.CellRendererPane;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

import com.formdev.flatlaf.ui.FlatTableHeaderUI;
import com.formdev.flatlaf.util.UIScale;

/**
 * Table headers of the MegaMek look and feels (docs/design-system/tokens.md, Tables): upper-case {@code overline}
 * labels in the secondary text color on the table's own surface, aligned like their column's cells. The label under
 * the pointer, the one being dragged and the sorted one turn primary; the sorted one also shows its chevron.
 */
public class TokenTableHeaderUI extends FlatTableHeaderUI {

    public static ComponentUI createUI(JComponent c) {
        return new TokenTableHeaderUI();
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        // FlatLaf's own pane paints hover backgrounds and moves sort icons; these headers only change the label
        header.remove(rendererPane);
        rendererPane = new LabelPane();
        header.add(rendererPane);
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        // FlatLaf's font syntax takes whole sizes only, and overline is 10.5
        Font font = header.getFont();
        if (font == null || font instanceof UIResource) {
            header.setFont(new FontUIResource(TokenFonts.font("overline")));
        }
    }

    /** The view column whose header cell is painted at {@code x} with {@code width}, or -1. */
    private int columnAt(int x, int width) {
        TableColumn dragged = header.getDraggedColumn();
        for (int i = 0; i < header.getColumnModel().getColumnCount(); i++) {
            Rectangle cell = header.getHeaderRect(i);
            if (cell.width != width) {
                continue;
            }
            boolean isDragged = header.getColumnModel().getColumn(i) == dragged;
            if (cell.x == x || (isDragged && cell.x + header.getDraggedDistance() == x)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isActive(int column) {
        Object frozen = header.getClientProperty(TokenTables.FROZEN_HEADER_HOVER);
        int hover = frozen instanceof Integer frozenColumn ? frozenColumn : getRolloverColumn();
        TableColumn dragged = header.getDraggedColumn();
        return column == hover || (dragged != null && header.getColumnModel().getColumn(column) == dragged)
              || isSorted(column);
    }

    private boolean isSorted(int column) {
        JTable table = header.getTable();
        RowSorter<?> sorter = table == null ? null : table.getRowSorter();
        if (sorter == null) {
            return false;
        }
        List<? extends RowSorter.SortKey> keys = sorter.getSortKeys();
        return !keys.isEmpty() && keys.get(0).getSortOrder() != SortOrder.UNSORTED
              && table.convertColumnIndexToView(keys.get(0).getColumn()) == column;
    }

    /** The alignment of the column's cells when its renderer is a label, such as right for numbers. */
    private int alignmentOf(int column, int fallback) {
        JTable table = header.getTable();
        if (table == null) {
            return fallback;
        }
        TableCellRenderer renderer = header.getColumnModel().getColumn(column).getCellRenderer();
        if (renderer == null) {
            renderer = table.getDefaultRenderer(table.getColumnClass(column));
        }
        return renderer instanceof JLabel label ? label.getHorizontalAlignment() : SwingConstants.LEADING;
    }

    private final class LabelPane extends CellRendererPane {
        @Override
        public void paintComponent(Graphics g, Component c, Container p, int x, int y, int w, int h,
              boolean shouldValidate) {
            int column = c instanceof JLabel ? columnAt(x, w) : -1;
            if (column < 0) {
                super.paintComponent(g, c, p, x, y, w, h, shouldValidate);
                return;
            }
            JLabel label = (JLabel) c;
            String text = label.getText();
            int alignment = label.getHorizontalAlignment();
            Color foreground = label.getForeground();
            boolean enabled = label.isEnabled();
            int gap = label.getIconTextGap();
            if (text != null && !BasicHTML.isHTMLString(text)) {
                label.setText(text.toUpperCase(header.getLocale()));
            }
            label.setHorizontalAlignment(alignmentOf(column, alignment));
            label.setEnabled(enabled && header.isEnabled());
            label.setIconTextGap(UIScale.scale(UIManager.getInt(TokenTables.SORT_ICON_GAP)));
            if (hoverForeground != null && header.isEnabled() && isActive(column)) {
                label.setForeground(hoverForeground);
            }
            if (pressedBackground != null && header.getDraggedColumn() != null
                  && header.getColumnModel().getColumn(column) == header.getDraggedColumn()) {
                boolean opaque = label.isOpaque();
                Color background = label.getBackground();
                label.setOpaque(true);
                label.setBackground(pressedBackground);
                super.paintComponent(g, c, p, x, y, w, h, shouldValidate);
                label.setOpaque(opaque);
                label.setBackground(background);
            } else {
                super.paintComponent(g, c, p, x, y, w, h, shouldValidate);
            }
            label.setText(text);
            label.setHorizontalAlignment(alignment);
            label.setForeground(foreground);
            label.setEnabled(enabled);
            label.setIconTextGap(gap);
        }
    }
}
