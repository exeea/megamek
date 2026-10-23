/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Insets;

import com.formdev.flatlaf.ui.FlatTableCellBorder;
import com.formdev.flatlaf.ui.FlatTableHeaderBorder;

/**
 * Cell borders of the MegaMek tables (docs/design-system/tokens.md, Tables): FlatLaf's, with the wider margins of
 * {@link TokenTables#withEdges} at the table's outer edges. The lead cell has no focus rectangle of its own, since the
 * selected row is the cursor; FlatLaf still marks the cell being edited in a table with editable cells.
 */
public final class TokenTableCellBorder {
    private TokenTableCellBorder() {
    }

    /** Every unselected cell, the lead one included. */
    public static class Plain extends FlatTableCellBorder.Default {
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            return TokenTables.withEdges(c, super.getBorderInsets(c, insets));
        }
    }

    /** The lead cell of a selected row. */
    public static class Selected extends FlatTableCellBorder.Selected {
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            return TokenTables.withEdges(c, super.getBorderInsets(c, insets));
        }
    }

    /** Header cells, so that labels line up with their column's text. */
    public static class Header extends FlatTableHeaderBorder {
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            return TokenTables.withEdges(c, super.getBorderInsets(c, insets));
        }
    }
}
