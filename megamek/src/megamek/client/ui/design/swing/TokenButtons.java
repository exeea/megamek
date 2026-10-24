/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Dimension;
import java.awt.Font;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JToggleButton;

import com.formdev.flatlaf.FlatClientProperties;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;

/**
 * The design system's button set for Swing (docs/design-system/tokens.md, Buttons): seven kinds in two sizes, every one
 * styled by the MegaMek look and feels through FlatLaf style classes, so the colors stay in the token file. Under
 * another look and feel the buttons are plain Swing buttons.
 */
public final class TokenButtons {
    /** The button kinds; each maps to a FlatLaf style class, or none for the default secondary look. */
    public enum Kind {
        /** The one main action of a view: accent face. */
        PRIMARY("primary"),
        /** Every other action: raised face, control edge. */
        SECONDARY(null),
        /** Low-emphasis actions in toolbars and rows: no face or edge until hovered. */
        QUIET("quiet"),
        /** Destructive actions: danger text and edge. */
        DANGER("danger"),
        /** An on/off choice: a secondary button that shows selection with a violet wash and edge. */
        TOGGLE(null),
        /** A square, quiet button with a Material Symbols glyph; it needs a tooltip. */
        ICON("icon"),
        /** Corner ticks that close into a frame on hover, for special moments; see {@link SpecialButton}. */
        SPECIAL(null);

        private final String styleClass;

        Kind(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    /** The two heights: control.md for dialogs and forms, control.sm for dense tables and toolbars. */
    public enum Size {
        REGULAR("regular", "control.md", "icon.md"),
        COMPACT("compact", "control.sm", "icon.sm");

        private final String styleClass;
        private final String height;
        private final String icon;

        Size(String styleClass, String height, String icon) {
            this.styleClass = styleClass;
            this.height = height;
            this.icon = icon;
        }

        /** The control height in unscaled pixels. */
        public int height() {
            return Math.round(DesignTokens.shipped().size().get(height));
        }

        /** The icon size in unscaled pixels. */
        public int iconSize() {
            return Math.round(DesignTokens.shipped().size().get(icon));
        }
    }

    private TokenButtons() {
    }

    /** A button of {@code kind} other than TOGGLE, ICON and SPECIAL, which have their own factories. */
    public static JButton button(Kind kind, String text, Size size) {
        if (kind == Kind.TOGGLE || kind == Kind.ICON || kind == Kind.SPECIAL) {
            throw new IllegalArgumentException(kind + " buttons have their own factory");
        }
        return style(new SizedButton(text, size, false), kind, size);
    }

    public static JButton primary(String text) {
        return button(Kind.PRIMARY, text, Size.REGULAR);
    }

    public static JButton secondary(String text) {
        return button(Kind.SECONDARY, text, Size.REGULAR);
    }

    public static JButton quiet(String text) {
        return button(Kind.QUIET, text, Size.REGULAR);
    }

    public static JButton danger(String text) {
        return button(Kind.DANGER, text, Size.REGULAR);
    }

    public static JToggleButton toggle(String text, Size size) {
        return style(new SizedToggleButton(text, size, false), Kind.TOGGLE, size);
    }

    /**
     * A square icon button showing the Material Symbols glyph {@code codePoint}; {@code tooltip} names the action,
     * since the glyph alone does not. A toggle shows selection with a violet wash and glyph.
     */
    public static AbstractButton icon(int codePoint, String tooltip, boolean toggle, Size size) {
        String glyph = new String(Character.toChars(codePoint));
        AbstractButton button = toggle ? new SizedToggleButton(glyph, size, true) : new SizedButton(glyph, size, true);
        button.setFont(FontHandler.symbolFont().deriveFont(Font.PLAIN, UIUtil.scaleForGUI((float) size.iconSize())));
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        return style(button, Kind.ICON, size);
    }

    public static SpecialButton special(String text, Size size) {
        SpecialButton button = new SpecialButton(text, size);
        button.putClientProperty(FlatClientProperties.STYLE_CLASS, size.styleClass);
        return TokenTextCase.role(button, "button");
    }

    /**
     * Gives an existing button the look and padding of {@code kind} at {@code size}, for migrating a view. The height
     * is not enforced: buttons from the factories grow to the size's height, restyled ones keep their natural height.
     * Labelled buttons follow their screen's text case mode ({@link TokenTextCase}); icon buttons show a glyph.
     */
    public static <T extends AbstractButton> T style(T button, Kind kind, Size size) {
        String classes = kind.styleClass == null ? size.styleClass : kind.styleClass + " " + size.styleClass;
        button.putClientProperty(FlatClientProperties.STYLE_CLASS, classes);
        return kind == Kind.ICON ? button : TokenTextCase.role(button, "button");
    }

    /**
     * Grows {@code preferred} to the scaled height of {@code size}, and to a square when {@code square}. FlatLaf's own
     * minimumHeight only applies to buttons with the default margin, which the size classes replace.
     */
    static Dimension sized(Dimension preferred, Size size, boolean square) {
        int height = Math.max(preferred.height, UIUtil.scaleForGUI(size.height()));
        return new Dimension(square ? Math.max(preferred.width, height) : preferred.width, height);
    }

    private static final class SizedButton extends JButton {
        private final Size size;
        private final boolean square;

        SizedButton(String text, Size size, boolean square) {
            super(text);
            this.size = size;
            this.square = square;
        }

        @Override
        public Dimension getPreferredSize() {
            return isPreferredSizeSet() ? super.getPreferredSize() : sized(super.getPreferredSize(), size, square);
        }
    }

    private static final class SizedToggleButton extends JToggleButton {
        private final Size size;
        private final boolean square;

        SizedToggleButton(String text, Size size, boolean square) {
            super(text);
            this.size = size;
            this.square = square;
        }

        @Override
        public Dimension getPreferredSize() {
            return isPreferredSizeSet() ? super.getPreferredSize() : sized(super.getPreferredSize(), size, square);
        }
    }
}
