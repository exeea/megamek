/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatListUI;

/**
 * Lists of the MegaMek look and feels (docs/design-system/tokens.md, Lists and trees): rows at least
 * {@code control.sm} high, a surface wash under the pointer and a mint marker on selected rows, and the empty-state
 * text of {@link TokenLists#emptyText}. A combo box's popup list keeps the select's own rows.
 */
public class TokenListUI extends FlatListUI {
    static final String CELL_MARGINS = "MegaMek.list.cellMargins";

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
    private RowWash wash;
    private Insets defaultMargins;
    private Insets tokenMargins;

    public static ComponentUI createUI(JComponent c) {
        return new TokenListUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        defaultMargins = cellMargins;
        tokenMargins = UIManager.getInsets(CELL_MARGINS);
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        wash = new RowWash(list);
        list.addMouseListener(hoverTracker);
        list.addMouseMotionListener(hoverTracker);
    }

    @Override
    protected void uninstallListeners() {
        list.removeMouseListener(hoverTracker);
        list.removeMouseMotionListener(hoverTracker);
        hoverRow = -1;
        wash.clear();
        super.uninstallListeners();
    }

    @Override
    protected void updateLayoutState() {
        syncMargins();
        super.updateLayoutState();
        if (isPopup() || list.getFixedCellHeight() != -1 || list.getLayoutOrientation() != JList.VERTICAL) {
            return;
        }
        int minimum = TokenLists.rowHeight();
        if (cellHeights != null) {
            for (int i = 0; i < cellHeights.length; i++) {
                cellHeights[i] = Math.max(cellHeights[i], minimum);
            }
        } else if (cellHeight > 0) {
            cellHeight = Math.max(cellHeight, minimum);
        }
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        syncMargins();
        super.paint(g, c);
        if (!isPopup() && list.getModel().getSize() == 0
              && c.getClientProperty(TokenLists.EMPTY_TEXT) instanceof String text && !text.isEmpty()) {
            TokenLists.paintEmptyText(g, c, text);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    protected void paintCell(Graphics g, int row, Rectangle rowBounds, ListCellRenderer cellRenderer,
          ListModel dataModel, ListSelectionModel selModel, int leadIndex) {
        boolean decorated = !isPopup() && list.isEnabled();
        boolean selected = selModel.isSelectedIndex(row);
        if (decorated && !selected) {
            TokenLists.paintWash(g,
                  wash.color(row, TokenLists.frozenHover(list), UIManager.getColor(TokenLists.HOVER_BACKGROUND)),
                  rowBounds.x, rowBounds.y, rowBounds.width, rowBounds.height, selectionInsets, selectionArc);
        }
        super.paintCell(g, row, rowBounds, cellRenderer, dataModel, selModel, leadIndex);
        if (decorated && selected) {
            TokenLists.paintMarker(g, list, rowBounds.x, rowBounds.y, rowBounds.width, rowBounds.height,
                  selectionInsets);
        }
    }

    private boolean isPopup() {
        return TokenLists.isPopupList(list);
    }

    /**
     * Ordinary lists leave room for the selection marker before the text; the popup list keeps FlatLaf's margins,
     * which the select's own padding builds on. A margin set through a FlatLaf style is left alone.
     */
    private void syncMargins() {
        if (tokenMargins == null || (cellMargins != defaultMargins && cellMargins != tokenMargins)) {
            return;
        }
        cellMargins = isPopup() ? defaultMargins : tokenMargins;
    }

    private int rowAt(Point point) {
        int row = list.locationToIndex(point);
        Rectangle bounds = row < 0 ? null : list.getCellBounds(row, row);
        return bounds != null && bounds.contains(point) ? row : -1;
    }

    private void setHoverRow(int row) {
        if (row == hoverRow) {
            return;
        }
        wash.moveTo(row);
        repaintRow(hoverRow);
        hoverRow = row;
        repaintRow(hoverRow);
    }

    private void repaintRow(int row) {
        if (row >= 0 && row < list.getModel().getSize()) {
            Rectangle bounds = list.getCellBounds(row, row);
            if (bounds != null) {
                list.repaint(bounds);
            }
        }
    }
}
