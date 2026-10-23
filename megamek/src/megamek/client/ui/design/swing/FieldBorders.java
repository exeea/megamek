/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Paint;

import com.formdev.flatlaf.ui.FlatRoundBorder;
import com.formdev.flatlaf.ui.FlatScrollPaneBorder;
import com.formdev.flatlaf.ui.FlatTextBorder;

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
}
