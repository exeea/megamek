/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;

import com.formdev.flatlaf.ui.FlatRoundBorder;
import com.formdev.flatlaf.ui.FlatScrollPaneBorder;
import com.formdev.flatlaf.ui.FlatTextBorder;
import com.formdev.flatlaf.util.UIScale;

/**
 * FlatLaf's input borders with the design system's hover and read-only edges ({@link FieldEdge}). The MegaMek look and
 * feels install them by class name, so they stay public.
 */
public final class FieldBorders {
    private FieldBorders() {
    }

    /** Text fields, password fields and formatted text fields. */
    public static class Text extends FlatTextBorder {
        @Override
        protected Paint getBorderColor(Component c) {
            return FieldEdge.color(c, super.getBorderColor(c), isEnabled(c), isFocused(c));
        }
    }

    /** Spinners and combo boxes. */
    public static class Round extends FlatRoundBorder {
        @Override
        protected Paint getBorderColor(Component c) {
            return FieldEdge.color(c, super.getBorderColor(c), isEnabled(c), isFocused(c));
        }
    }

    /** Scroll panes; only those around a text area or other text get the input edges. */
    public static class ScrollPane extends FlatScrollPaneBorder {
        @Override
        protected Paint getBorderColor(Component c) {
            return FieldEdge.color(c, super.getBorderColor(c), isEnabled(c), isFocused(c));
        }
    }

    /**
     * The inline select ({@link TokenSelects#inline}): no box, only a dotted underline that turns strong under the
     * pointer. Keyboard focus draws the ring around the whole select instead.
     */
    public static class InlineSelect extends FlatRoundBorder {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (isFocused(c)) {
                super.paintBorder(c, g, x, y, width, height);
                return;
            }
            Paint color = isEnabled(c) ? FieldEdge.color(c, borderColor, true, false) : disabledBorderColor;
            if (color == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setPaint(color);
                int line = Math.max(1, Math.round(UIScale.scale(borderWidth)));
                int dash = UIScale.scale(2);
                int baseline = y + height - line - Math.round(UIScale.scale(getFocusWidth(c))) - dash;
                for (int dx = x; dx < x + width; dx += dash * 2) {
                    g2.fillRect(dx, baseline, Math.min(dash, x + width - dx), line);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
