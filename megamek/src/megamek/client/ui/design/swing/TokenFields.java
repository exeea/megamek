/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatClientProperties;
import megamek.client.ui.design.swing.TokenButtons.Size;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;

/**
 * The design system's text inputs for Swing (docs/design-system/tokens.md, Inputs): text, password, search, number and
 * multi-line text, in the two button sizes so a field and a button side by side line up. The MegaMek look and feels
 * draw them; plain Swing inputs get the compact height and the same edges without going through here. Wrap an input in
 * a {@link TokenFormField} to give it a label above and a helper or status message below.
 */
public final class TokenFields {
    /** Material Symbols code points of the field glyphs. */
    static final int ICON_SEARCH = 0xE8B6;
    static final int ICON_ERROR = 0xE000;
    static final int ICON_WARNING = 0xE002;

    /** A status shown by the edge and an icon; the icon stays when the focus ring replaces the edge. */
    public enum Status {
        NONE(null, "Component.borderColor", 0),
        ERROR(FlatClientProperties.OUTLINE_ERROR, "Component.error.borderColor", ICON_ERROR),
        WARNING(FlatClientProperties.OUTLINE_WARNING, "Component.warning.borderColor", ICON_WARNING);

        private final String outline;
        private final String colorKey;
        private final int glyph;

        Status(String outline, String colorKey, int glyph) {
            this.outline = outline;
            this.colorKey = colorKey;
            this.glyph = glyph;
        }

        /** The status color of the active look and feel, also used for its message text. */
        public Color color() {
            return UIManager.getColor(colorKey);
        }

        /** The status glyph at the small icon size, or null for NONE. */
        public Icon icon() {
            return glyph == 0 ? null : FontHandler.symbolIcon(glyph, UIUtil.scaleForGUI(Size.COMPACT.iconSize()),
                  color());
        }
    }

    private TokenFields() {
    }

    public static JTextField text(Size size) {
        return size(new JTextField(), size);
    }

    public static JPasswordField password(Size size) {
        return size(new JPasswordField(), size);
    }

    /** A search field: a search glyph before the text, {@code placeholder} while empty and a clear button after it. */
    public static JTextField search(String placeholder, Size size) {
        JTextField field = text(size);
        Color glyph = UIManager.getColor("TextField.placeholderForeground");
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
              FontHandler.symbolIcon(ICON_SEARCH, UIUtil.scaleForGUI(Size.COMPACT.iconSize()),
                    glyph == null ? field.getForeground() : glyph));
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        field.getAccessibleContext().setAccessibleName(placeholder);
        return field;
    }

    /** A number field with step buttons. */
    public static JSpinner number(SpinnerNumberModel model, Size size) {
        return size(new JSpinner(model), size);
    }

    /**
     * Multi-line text in a scroll pane, which draws the edge. Text areas have no fixed height; give the area its rows.
     */
    public static JScrollPane area(JTextArea area) {
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return new JScrollPane(area);
    }

    /** Gives an input the height of {@code size}; inputs that are not sized here are compact. */
    public static <T extends JComponent> T size(T input, Size size) {
        input.putClientProperty(FlatClientProperties.MINIMUM_HEIGHT, size.height());
        return input;
    }

    /**
     * Shows {@code status} on a standalone input: the status edge, and on a text field the status icon after the text.
     * Inside a {@link TokenFormField} use its status instead, which puts the icon in the message.
     */
    public static <T extends JComponent> T status(T input, Status status) {
        input.putClientProperty(FlatClientProperties.OUTLINE, status.outline);
        if (input instanceof JTextField) {
            input.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_ICON, status.icon());
        }
        return input;
    }

    static void outline(JComponent input, Status status) {
        input.putClientProperty(FlatClientProperties.OUTLINE, status.outline);
    }
}
