/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.LayoutManager;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatComboBoxUI;
import com.formdev.flatlaf.ui.FlatFormattedTextFieldUI;
import com.formdev.flatlaf.ui.FlatPasswordFieldUI;
import com.formdev.flatlaf.ui.FlatSpinnerUI;
import com.formdev.flatlaf.ui.FlatTextFieldUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import megamek.client.ui.util.UIUtil;

/**
 * FlatLaf's single-line input delegates, grown to the design system's control height so fields line up with buttons:
 * control.sm unless the component sets {@code JComponent.minimumHeight}, as {@link TokenFields} does for regular
 * inputs. FlatLaf has a minimum height for buttons only. Editors inside a spinner or combo box and cell editors keep
 * their natural height. The MegaMek look and feels install these by class name, so they stay public.
 */
public final class FieldUIs {
    private FieldUIs() {
    }

    /** Grows {@code size} to the input height of {@code component}. */
    static Dimension tall(JComponent component, Dimension size) {
        if (size == null || FlatUIUtils.isCellEditor(component) || isInnerEditor(component)) {
            return size;
        }
        int height = FlatUIUtils.minimumHeight(component, UIManager.getInt(FieldEdge.HEIGHT));
        if (height > 0) {
            size.height = Math.max(size.height, UIUtil.scaleForGUI(height));
        }
        return size;
    }

    private static boolean isInnerEditor(Component component) {
        for (Component parent = component.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof JSpinner || parent instanceof JComboBox) {
                return true;
            }
        }
        return false;
    }

    public static class TextField extends FlatTextFieldUI {
        public static ComponentUI createUI(JComponent c) {
            return new TextField();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return tall(c, super.getPreferredSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return tall(c, super.getMinimumSize(c));
        }
    }

    public static class PasswordField extends FlatPasswordFieldUI {
        public static ComponentUI createUI(JComponent c) {
            return new PasswordField();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return tall(c, super.getPreferredSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return tall(c, super.getMinimumSize(c));
        }
    }

    public static class FormattedTextField extends FlatFormattedTextFieldUI {
        public static ComponentUI createUI(JComponent c) {
            return new FormattedTextField();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return tall(c, super.getPreferredSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return tall(c, super.getMinimumSize(c));
        }
    }

    public static class Spinner extends FlatSpinnerUI {
        public static ComponentUI createUI(JComponent c) {
            return new Spinner();
        }

        // The spinner's size comes from its layout, which the delegate does not otherwise report
        @Override
        public Dimension getPreferredSize(JComponent c) {
            LayoutManager layout = c.getLayout();
            return layout == null ? super.getPreferredSize(c) : tall(c, layout.preferredLayoutSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            LayoutManager layout = c.getLayout();
            return layout == null ? super.getMinimumSize(c) : tall(c, layout.minimumLayoutSize(c));
        }
    }

    public static class ComboBox extends FlatComboBoxUI {
        public static ComponentUI createUI(JComponent c) {
            return new ComboBox();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return tall(c, super.getPreferredSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return tall(c, super.getMinimumSize(c));
        }
    }
}
