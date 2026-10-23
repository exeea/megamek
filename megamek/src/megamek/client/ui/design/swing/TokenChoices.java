/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.event.ActionListener;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JRadioButton;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * The design-system choices: check box, radio button and switch. The box or circle is {@code icon.sm}, the label
 * sits on the right, and a click on either flips the choice. Checked is an accent fill with a mark in the on-accent
 * color, so the state never rests on color alone. Plain {@code JCheckBox} and {@code JRadioButton} get the same look
 * from the MegaMek themes; this class adds the mixed check box and the switch.
 *
 * <p>A check box is for a choice confirmed with the rest of a form; a switch applies at once, like a setting.</p>
 */
public final class TokenChoices {
    private TokenChoices() {
    }

    /** A check box with its label on the right. */
    public static JCheckBox check(String text, boolean checked) {
        return new JCheckBox(text, checked);
    }

    /**
     * Shows {@code box} mixed (a dash: some of the items it stands for are checked, as a "select all" over a list) or
     * clears that. A mixed box reads as unchecked, so a click checks it, and any click clears the mixed look.
     */
    public static JCheckBox mixed(JCheckBox box, boolean mixed) {
        if (mixed) {
            box.setSelected(false);
        }
        box.putClientProperty(FlatClientProperties.SELECTED_STATE,
              mixed ? FlatClientProperties.SELECTED_STATE_INDETERMINATE : null);
        if (box.getClientProperty(MixedReset.class) == null) {
            ActionListener reset = event -> box.putClientProperty(FlatClientProperties.SELECTED_STATE, null);
            box.addActionListener(reset);
            box.putClientProperty(MixedReset.class, reset);
        }
        box.repaint();
        return box;
    }

    /** Whether {@code box} shows the mixed look. */
    public static boolean isMixed(JCheckBox box) {
        return FlatClientProperties.SELECTED_STATE_INDETERMINATE.equals(
              box.getClientProperty(FlatClientProperties.SELECTED_STATE));
    }

    /** A radio button with its label on the right; {@link #group} makes a set of them exclusive. */
    public static JRadioButton radio(String text, boolean selected) {
        return new JRadioButton(text, selected);
    }

    /** Groups {@code radios} so that selecting one clears the others. */
    public static ButtonGroup group(JRadioButton... radios) {
        ButtonGroup group = new ButtonGroup();
        for (JRadioButton radio : radios) {
            group.add(radio);
        }
        return group;
    }

    /** A switch with its label on the right, for a setting that applies as soon as it flips. */
    public static Switch toggleSwitch(String text, boolean on) {
        return new Switch(text, on);
    }

    /** Marks the listener {@link #mixed} adds once per box. */
    private interface MixedReset {
    }

    /**
     * A switch: a check box drawn as a track and thumb ({@link TokenSwitchIcon}). Assistive technology hears a toggle
     * button that is pressed while on.
     */
    public static final class Switch extends JCheckBox {
        public Switch(String text, boolean on) {
            super(text, on);
        }

        /** Rebuilds the icon, which reads its colors from the current look and feel. */
        @Override
        public void updateUI() {
            super.updateUI();
            Icon icon = new TokenSwitchIcon();
            setIcon(icon);
            setSelectedIcon(icon);
            setPressedIcon(icon);
            setRolloverIcon(icon);
            setRolloverSelectedIcon(icon);
            setDisabledIcon(icon);
            setDisabledSelectedIcon(icon);
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJCheckBox() {
                    @Override
                    public AccessibleRole getAccessibleRole() {
                        return AccessibleRole.TOGGLE_BUTTON;
                    }

                    @Override
                    public AccessibleStateSet getAccessibleStateSet() {
                        AccessibleStateSet states = super.getAccessibleStateSet();
                        if (isSelected()) {
                            states.add(AccessibleState.PRESSED);
                        }
                        return states;
                    }
                };
            }
            return accessibleContext;
        }
    }
}
