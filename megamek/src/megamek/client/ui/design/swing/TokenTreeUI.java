/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.tree.TreePath;

import com.formdev.flatlaf.ui.FlatTreeUI;

/**
 * Trees of the MegaMek look and feels (docs/design-system/tokens.md, Lists and trees): the list's rows with chevrons
 * and indentation, a surface wash under the pointer and a violet marker at the leading edge of selected rows.
 */
public class TokenTreeUI extends FlatTreeUI {
    private final MouseAdapter hoverTracker = new MouseAdapter() {
        @Override
        public void mouseMoved(MouseEvent e) {
            setHoverRow(rowAt(e.getPoint()));
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            setHoverRow(rowAt(e.getPoint()));
        }

        @Override
        public void mouseExited(MouseEvent e) {
            setHoverRow(-1);
        }
    };
    private int hoverRow = -1;

    public static ComponentUI createUI(JComponent c) {
        return new TokenTreeUI();
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        tree.addMouseListener(hoverTracker);
        tree.addMouseMotionListener(hoverTracker);
    }

    @Override
    protected void uninstallListeners() {
        tree.removeMouseListener(hoverTracker);
        tree.removeMouseMotionListener(hoverTracker);
        hoverRow = -1;
        super.uninstallListeners();
    }

    @Override
    protected void paintRow(Graphics g, Rectangle clipBounds, Insets insets, Rectangle bounds, TreePath path, int row,
          boolean isExpanded, boolean hasBeenExpanded, boolean isLeaf) {
        boolean decorated = tree.isEnabled() && !(editingComponent != null && editingRow == row);
        boolean selected = tree.isRowSelected(row);
        int x = isWideSelection() ? 0 : bounds.x;
        int width = isWideSelection() ? tree.getWidth() : bounds.width;
        if (decorated && !selected && row == hoverRow()) {
            TokenLists.paintHover(g, x, bounds.y, width, bounds.height, selectionInsets, selectionArc);
        }
        super.paintRow(g, clipBounds, insets, bounds, path, row, isExpanded, hasBeenExpanded, isLeaf);
        if (decorated && selected && isPaintSelection()) {
            TokenLists.paintMarker(g, tree, x, bounds.y, width, bounds.height, selectionInsets);
        }
    }

    private int hoverRow() {
        int frozen = TokenLists.frozenHover(tree);
        return frozen >= 0 ? frozen : hoverRow;
    }

    private int rowAt(Point point) {
        int row = tree.getClosestRowForLocation(point.x, point.y);
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        if (bounds == null || point.y < bounds.y || point.y >= bounds.y + bounds.height) {
            return -1;
        }
        return isWideSelection() || bounds.contains(point) ? row : -1;
    }

    private void setHoverRow(int row) {
        if (row == hoverRow) {
            return;
        }
        repaintRow(hoverRow);
        hoverRow = row;
        repaintRow(hoverRow);
    }

    private void repaintRow(int row) {
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        if (bounds != null) {
            tree.repaint(0, bounds.y, tree.getWidth(), bounds.height);
        }
    }
}
