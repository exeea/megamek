/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIDefaults;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatStylingSupport;
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
