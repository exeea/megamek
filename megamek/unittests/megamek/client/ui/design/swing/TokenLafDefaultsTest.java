/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatStylingSupport;
import com.formdev.flatlaf.util.UIScale;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.util.UIUtil;
import org.junit.jupiter.api.Test;

class TokenLafDefaultsTest {
    /** Keys FlatLaf styles through the component's own setters rather than the UI delegate. */
    private static final Set<String> COMPONENT_KEYS = Set.of("background", "foreground", "margin", "font");

    private final DesignTokens tokens = DesignTokens.shipped();

    @Test
    void darkLookAndFeelUsesTheTokens() {
        assertUsesTokens(new MegaMekDarkLaf(), Mode.DARK);
    }

    @Test
    void lightLookAndFeelUsesTheTokens() {
        assertUsesTokens(new MegaMekLightLaf(), Mode.LIGHT);
    }

    @Test
    void everyColorValueIsOpaque() {
        for (Mode mode : Mode.values()) {
            for (Map.Entry<String, String> entry : TokenLafDefaults.of(mode).entrySet()) {
                if (entry.getValue().startsWith("#")) {
                    assertEquals(7, entry.getValue().length(), entry.getKey() + " must be opaque");
                }
            }
        }
    }

    @Test
    void buttonStyleClassesParseAndApply() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                for (AbstractButton button : List.of(new JButton(), new JToggleButton())) {
                    Set<String> styleable = ((FlatButtonUI) button.getUI()).getStyleableInfos(button).keySet();
                    String type = button instanceof JToggleButton ? "ToggleButton" : "Button";
                    for (String styleClass : List.of("primary", "danger", "quiet", "icon", "regular", "compact")) {
                        Object style = UIManager.get("[style]" + type + "." + styleClass);
                        if (style == null && button instanceof JToggleButton) {
                            continue;
                        }
                        assertNotNull(style, type + "." + styleClass);
                        for (String key : FlatStylingSupport.parse((String) style).keySet()) {
                            assertTrue(styleable.contains(key) || COMPONENT_KEYS.contains(key), type + "." + key);
                        }
                    }
                }
                JButton compact = TokenButtons.button(TokenButtons.Kind.DANGER, "Delete", TokenButtons.Size.COMPACT);
                int side = Math.round(tokens.space().get("md"));
                assertEquals(new Insets(0, side, 0, side), compact.getMargin());
                assertTrue(compact.getPreferredSize().height >= UIUtil.scaleForGUI(TokenButtons.Size.COMPACT.height()));
                JToggleButton regular = TokenButtons.toggle("Show arcs", TokenButtons.Size.REGULAR);
                assertTrue(regular.getPreferredSize().height >= UIUtil.scaleForGUI(TokenButtons.Size.REGULAR.height()));
                Dimension icon = TokenButtons.icon(0xE8B6, "Search", false, TokenButtons.Size.REGULAR)
                      .getPreferredSize();
                assertEquals(icon.width, icon.height);
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void inputsMatchButtonHeightsAndShowHoverAndReadOnlyEdges() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                int compact = UIUtil.scaleForGUI(TokenButtons.Size.COMPACT.height());
                int regular = UIUtil.scaleForGUI(TokenButtons.Size.REGULAR.height());
                // Plain Swing inputs are compact; the factory makes regular ones
                assertTrue(new JTextField().getPreferredSize().height >= compact, "plain text field");
                assertTrue(new JPasswordField().getPreferredSize().height >= compact, "plain password field");
                assertTrue(new JSpinner().getPreferredSize().height >= compact, "plain spinner");
                assertTrue(new JComboBox<>(new String[] { "A" }).getPreferredSize().height >= compact, "plain combo");
                assertEquals(regular, TokenFields.text(TokenButtons.Size.REGULAR).getPreferredSize().height);
                assertEquals(regular, TokenFields.number(new SpinnerNumberModel(4, 0, 8, 1),
                      TokenButtons.Size.REGULAR).getPreferredSize().height);
                // The spinner's own editor keeps its natural height
                JSpinner spinner = TokenFields.number(new SpinnerNumberModel(4, 0, 8, 1), TokenButtons.Size.REGULAR);
                JTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
                assertTrue(editor.getPreferredSize().height < regular, "spinner editor");

                Color control = new Color(tokens.color("border.control", mode));
                JTextField field = new JTextField();
                assertEquals(control, FieldEdge.color(field, control, true, false));
                field.putClientProperty(FieldEdge.FROZEN_HOVER, true);
                assertEquals(new Color(tokens.color("border.strong", mode)),
                      new Color(((Color) FieldEdge.color(field, control, true, false)).getRGB()));
                assertEquals(control, FieldEdge.color(field, control, true, true), "focus wins over hover");
                assertEquals(control, FieldEdge.color(field, control, false, false), "disabled");
                field.setEditable(false);
                assertEquals(new Color(tokens.color("border.subtle", mode)),
                      new Color(((Color) FieldEdge.color(field, control, true, false)).getRGB()), "read-only");
                // A scroll pane only gets input edges around text
                JScrollPane table = new JScrollPane(new JTable());
                table.putClientProperty(FieldEdge.FROZEN_HOVER, true);
                assertEquals(control, FieldEdge.color(table, control, true, false));
                assertEquals(spinner, FieldEdge.edgeOf(editor));
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void choicesAreIconSizedAndShowMixedAndSwitchStates() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                int size = Math.round(tokens.size().get("icon.sm"));
                // The 14-unit box sits in a 15-unit icon; scaled to the token, the box is icon.sm
                assertEquals(Math.round(UIScale.scale(15) * size / 14f), new TokenCheckBoxIcon().getIconWidth());
                assertEquals(Math.round(UIScale.scale(15) * size / 15f), new TokenRadioButtonIcon().getIconWidth());
                TokenSwitchIcon track = new TokenSwitchIcon();
                assertEquals(UIScale.scale(size), track.getIconHeight());
                assertEquals(UIScale.scale(Math.round(size * TokenSwitchIcon.LENGTH)), track.getIconWidth());
                assertColor(lookAndFeel.getDefaults(), "CheckBox.icon.switchThumbColor", "text.secondary", mode);
                assertColor(lookAndFeel.getDefaults(), "CheckBox.icon.hoverSelectedBorderColor", "border.strong", mode);

                // A mixed box reads as unchecked, and a click checks it and drops the mixed look
                JCheckBox all = TokenChoices.mixed(TokenChoices.check("All", true), true);
                assertTrue(TokenChoices.isMixed(all));
                assertFalse(all.isSelected());
                all.doClick();
                assertTrue(all.isSelected());
                assertFalse(TokenChoices.isMixed(all));

                TokenChoices.Switch autosave = TokenChoices.toggleSwitch("Autosave", false);
                assertTrue(autosave.getIcon() instanceof TokenSwitchIcon);
                assertEquals(AccessibleRole.TOGGLE_BUTTON, autosave.getAccessibleContext().getAccessibleRole());
                autosave.doClick();
                assertTrue(autosave.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.PRESSED));
                // Switching the theme rebuilds the icon with the new colors
                Icon before = autosave.getIcon();
                SwingUtilities.updateComponentTreeUI(autosave);
                assertNotSame(before, autosave.getIcon());

                JRadioButton first = TokenChoices.radio("A", true);
                JRadioButton second = TokenChoices.radio("B", false);
                TokenChoices.group(first, second);
                second.doClick();
                assertFalse(first.isSelected());
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    @Test
    void tabsAndSegmentedControlsFollowTheTokens() throws Exception {
        String previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        try {
            for (FlatLaf lookAndFeel : List.of(new MegaMekDarkLaf(), new MegaMekLightLaf())) {
                UIManager.setLookAndFeel(lookAndFeel);
                Mode mode = lookAndFeel instanceof MegaMekDarkLaf ? Mode.DARK : Mode.LIGHT;
                UIDefaults defaults = lookAndFeel.getDefaults();
                // The underline stays violet when the strip loses focus
                assertColor(defaults, "TabbedPane.underlineColor", "selected.primary", mode);
                assertColor(defaults, "TabbedPane.inactiveUnderlineColor", "selected.primary", mode);
                assertColor(defaults, "TabbedPane.disabledUnderlineColor", "border.subtle", mode);
                assertColor(defaults, "TabbedPane.foreground", "text.secondary", mode);
                assertColor(defaults, "TabbedPane.hoverForeground", "text.primary", mode);
                assertColor(defaults, "TabbedPane.selectedForeground", "text.primary", mode);
                assertColor(defaults, "TabbedPane.disabledForeground", "text.disabled", mode);
                assertEquals(Math.round(tokens.size().get("selection.width")),
                      defaults.getInt("TabbedPane.tabSelectionHeight"));
                assertEquals(TokenButtons.Size.COMPACT.height(), defaults.getInt("TabbedPane.tabHeight"));
                JTabbedPane regular = TokenTabs.tabs(TokenButtons.Size.REGULAR);
                assertEquals(TokenButtons.Size.REGULAR.height(),
                      regular.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT));
                int side = Math.round(tokens.space().get("lg"));
                assertEquals(new Insets(0, side, 0, side),
                      regular.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_INSETS));

                for (TokenButtons.Size size : TokenButtons.Size.values()) {
                    TokenSegmented segmented = new TokenSegmented(size, "Map", "List", "Cards");
                    Dimension preferred = segmented.getPreferredSize();
                    assertEquals(UIUtil.scaleForGUI(size.height()), preferred.height, size.name());
                    segmented.setSize(preferred);
                    segmented.doLayout();
                    assertEquals(segmented.segment(0).getWidth(), segmented.segment(2).getWidth(), "equal widths");
                }

                TokenSegmented segmented = new TokenSegmented(TokenButtons.Size.COMPACT, "Map", "List", "Cards");
                assertEquals(AccessibleRole.RADIO_BUTTON,
                      segmented.segment(1).getAccessibleContext().getAccessibleRole());
                assertTrue(segmented.isFocusTraversalPolicyProvider(), "one Tab stop, the selected section");
                assertEquals(0, segmented.getSelectedIndex());
                List<String> heard = new ArrayList<>();
                segmented.addActionListener(event -> heard.add(event.getActionCommand()));
                segmented.setSelectedIndex(2);
                assertEquals(2, segmented.getSelectedIndex());
                assertTrue(heard.isEmpty(), "setSelectedIndex is silent");
                segmented.segment(0).doClick();
                assertEquals(List.of("Map"), heard);
                segmented.segment(0).doClick();
                assertEquals(1, heard.size(), "clicking the selected section changes nothing");
                assertTrue(segmented.segment(0).isSelected(), "the selection can't be cleared");

                // Left and Right move the selection; there is nothing past either end
                JToggleButton first = segmented.segment(0);
                assertEquals("nextSegment", first.getInputMap(JComponent.WHEN_FOCUSED)
                      .get(KeyStroke.getKeyStroke("RIGHT")));
                press(first, "previousSegment");
                assertEquals(0, segmented.getSelectedIndex());
                press(first, "nextSegment");
                assertEquals(1, segmented.getSelectedIndex());
                assertEquals(List.of("Map", "List"), heard);
                segmented.setEnabled(false);
                assertFalse(segmented.segment(2).isEnabled());
                press(segmented.segment(1), "nextSegment");
                assertEquals(1, segmented.getSelectedIndex(), "a disabled control ignores keys");
            }
        } finally {
            if (previous != null) {
                UIManager.setLookAndFeel(previous);
            }
        }
    }

    private static void press(JComponent component, String action) {
        component.getActionMap().get(action).actionPerformed(new ActionEvent(component,
              ActionEvent.ACTION_PERFORMED, action));
    }

    private void assertUsesTokens(FlatLaf lookAndFeel, Mode mode) {
        UIDefaults defaults = lookAndFeel.getDefaults();
        assertColor(defaults, "Panel.background", "surface.base", mode);
        assertColor(defaults, "Label.foreground", "text.primary", mode);
        assertColor(defaults, "TextField.background", "surface.input", mode);
        assertColor(defaults, "Component.borderColor", "border.control", mode);
        assertColor(defaults, "Component.focusColor", "focus.ring", mode);
        assertColor(defaults, "Button.default.background", "accent.primary", mode);
        assertColor(defaults, "Button.default.foreground", "text.onAccent", mode);
        assertColor(defaults, "ProgressBar.foreground", "accent.primary", mode);
        assertColor(defaults, "Component.error.borderColor", "status.danger", mode);
        // Focus wins: one ring for every state, while a focused checked box keeps its checked look
        assertColor(defaults, "Component.error.focusedBorderColor", "focus.ring", mode);
        assertColor(defaults, "Component.warning.focusedBorderColor", "focus.ring", mode);
        assertColor(defaults, "CheckBox.icon.focusColor", "focus.ring", mode);
        assertColor(defaults, "CheckBox.icon.focusedSelectedBackground", "accent.primary", mode);
        assertColor(defaults, "CheckBox.icon.focusedBackground", "surface.input", mode);
        assertColor(defaults, "Slider.focusedColor", "focus.ring", mode);
        assertTrue(defaults.getIcon("CheckBox.icon") instanceof TokenCheckBoxIcon, "CheckBox.icon");
        assertTrue(defaults.getIcon("RadioButton.icon") instanceof TokenRadioButtonIcon, "RadioButton.icon");
        assertEquals(TokenTabbedPaneUI.class.getName(), defaults.getString("TabbedPaneUI"));
        // Inputs: control.sm height by default, a strong edge on hover, no fill and a subtle edge when read-only
        assertEquals(Math.round(tokens.size().get("control.sm")), defaults.getInt(FieldEdge.HEIGHT));
        assertColor(defaults, FieldEdge.HOVER_COLOR, "border.strong", mode);
        assertColor(defaults, FieldEdge.READ_ONLY_COLOR, "border.subtle", mode);
        assertColor(defaults, "TextField.inactiveBackground", "surface.base", mode);
        assertColor(defaults, "TextArea.inactiveBackground", "surface.base", mode);
        assertColor(defaults, "TextField.placeholderForeground", "text.muted", mode);
        assertColor(defaults, "PasswordField.placeholderForeground", "text.muted", mode);
        assertColor(defaults, TokenFormField.LABEL_COLOR, "text.secondary", mode);
        assertColor(defaults, TokenFormField.HELPER_COLOR, "text.muted", mode);
        assertEquals(FieldUIs.TextField.class.getName(), defaults.getString("TextFieldUI"));
        assertEquals(FieldUIs.Spinner.class.getName(), defaults.getString("SpinnerUI"));
        assertEquals(FieldUIs.ComboBox.class.getName(), defaults.getString("ComboBoxUI"));
        int selection = ColorMath.over(tokens.color("selected.background", mode), tokens.color("surface.input", mode));
        assertEquals(new Color(selection), new Color(defaults.getColor("Table.selectionBackground").getRGB()));
        assertNotNull(defaults.get("[style]Button.primary"), "primary button style class");
        assertColor(defaults, "Button.selectedBorderColor", "selected.primary", mode);
        assertColor(defaults, "Button.foreground", "text.secondary", mode);
        assertColor(defaults, "Button.hoverForeground", "text.primary", mode);
        assertColor(defaults, "Button.default.focusedBackground", "accent.primary", mode);
        int label = tokens.color("text.onAccent", mode);
        for (String key : List.of("Button.default.hoverBackground", "Button.default.pressedBackground")) {
            double contrast = ColorMath.contrast(label, defaults.getColor(key).getRGB());
            assertTrue(contrast >= 4.5, key + " label contrast " + contrast);
        }
    }

    private void assertColor(UIDefaults defaults, String key, String role, Mode mode) {
        Color actual = defaults.getColor(key);
        assertNotNull(actual, key);
        assertEquals(new Color(tokens.color(role, mode), true), new Color(actual.getRGB(), true), key);
    }
}
