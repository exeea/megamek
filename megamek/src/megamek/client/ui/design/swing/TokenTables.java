/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Insets;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.JTableHeader;

import com.formdev.flatlaf.util.UIScale;

/**
 * The design system's tables for Swing (docs/design-system/tokens.md, Tables). The MegaMek look and feels draw every
 * {@link JTable} with the list's rows stretched across its columns: zebra stripes, a surface wash under the pointer
 * and, for the selection, the selection wash plus a selection marker at the row's leading edge. Headers are upper-case
 * {@code overline} labels on the table's own surface, aligned like their column. This adds an empty-state text.
 */
public final class TokenTables {
    /** The translucent surface wash painted over the row under the pointer, cells and all. */
    static final String HOVER_WASH = "MegaMek.table.hoverWash";
    /**
     * The cell margins at the table's two outer edges: the leading one leaves room for the selection marker, the
     * trailing one for the row shape's inset.
     */
    static final String EDGE_MARGINS = "MegaMek.table.edgeMargins";
    /** Client property freezing the header's hover look on a view column, for the design gallery. */
    static final String FROZEN_HEADER_HOVER = "MegaMek.tableHeader.frozenHover";
    /** The gap between a header's label and its sort chevron. */
    static final String SORT_ICON_GAP = "MegaMek.tableHeader.sortIconGap";

    private TokenTables() {
    }

    /**
     * Shows {@code text} in the muted color while {@code table} has no rows. The table then fills its scroll pane, so
     * the text has somewhere to go.
     */
    public static <T extends JTable> T emptyText(T table, String text) {
        table.putClientProperty(TokenLists.EMPTY_TEXT, text);
        table.setFillsViewportHeight(true);
        table.repaint();
        return table;
    }

    /**
     * Widens {@code insets} at the table's outer edges when {@code cell}, a renderer being painted, sits in the first
     * or last column. Renderers learn their cell's bounds before they paint, so the bounds tell the column apart.
     */
    static Insets withEdges(Component cell, Insets insets) {
        Insets edges = UIManager.getInsets(EDGE_MARGINS);
        JTable table = tableOf(cell);
        if (edges == null || table == null || table.getColumnCount() == 0) {
            return insets;
        }
        int total = table.getColumnModel().getTotalColumnWidth();
        int left = cell.getX();
        int right = left + cell.getWidth();
        boolean ltr = table.getComponentOrientation().isLeftToRight();
        int width = cell.getParent() == null || cell.getParent().getParent() == null
              ? total : cell.getParent().getParent().getWidth();
        boolean leading = ltr ? left <= 0 : right >= width;
        boolean trailing = ltr ? right >= total : left <= width - total;
        if (leading) {
            setSide(insets, ltr, UIScale.scale(edges.left));
        }
        if (trailing) {
            setSide(insets, !ltr, UIScale.scale(edges.right));
        }
        return insets;
    }

    private static void setSide(Insets insets, boolean left, int value) {
        if (left) {
            insets.left = value;
        } else {
            insets.right = value;
        }
    }

    private static JTable tableOf(Component cell) {
        JTable table = (JTable) SwingUtilities.getAncestorOfClass(JTable.class, cell);
        if (table != null) {
            return table;
        }
        JTableHeader header = (JTableHeader) SwingUtilities.getAncestorOfClass(JTableHeader.class, cell);
        return header == null ? null : header.getTable();
    }
}
