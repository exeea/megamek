/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static megamek.client.ui.design.ColorMath.hex;
import static megamek.client.ui.design.ColorMath.over;

import java.util.LinkedHashMap;
import java.util.Map;

import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;

/**
 * Turns {@link DesignTokens} into FlatLaf properties, so the MegaMek look and feels take every color, the font and the
 * shapes from the token file instead of keeping a second copy. FlatLaf derives most of its keys from a few variables
 * ({@code @background}, {@code @foreground}, {@code @accentColor} and so on); this sets those and then the keys whose
 * derived values would not match the tokens.
 * <p>
 * Translucent tokens (hover and selection washes) are composited over the surface they sit on, because opaque Swing
 * components must paint opaque colors.
 */
public final class TokenLafDefaults {
    private final DesignTokens tokens;
    private final Mode mode;
    private final Map<String, String> values = new LinkedHashMap<>();

    private TokenLafDefaults(DesignTokens tokens, Mode mode) {
        this.tokens = tokens;
        this.mode = mode;
    }

    /** The FlatLaf properties for the given mode of the shipped tokens. */
    public static Map<String, String> of(Mode mode) {
        return of(DesignTokens.shipped(), mode);
    }

    /** The FlatLaf properties for the given mode of the given tokens. */
    public static Map<String, String> of(DesignTokens tokens, Mode mode) {
        return new TokenLafDefaults(tokens, mode).build();
    }

    private Map<String, String> build() {
        int base = color("surface.base");
        int panel = color("surface.panel");
        int raised = color("surface.raised");
        int popover = color("surface.popover");
        int input = color("surface.input");
        int hover = color("surface.hover");
        int pressed = ColorMath.withAlpha(hover, Math.min(255, ColorMath.alpha(hover) * 2));
        int selected = color("selected.background");

        // Variables that FlatLaf derives most other keys from
        put("@background", base);
        put("@foreground", color("text.primary"));
        put("@disabledForeground", color("text.disabled"));
        put("@componentBackground", input);
        put("@buttonBackground", raised);
        put("@menuBackground", popover);
        put("@accentColor", color("accent.primary"));
        // Unfocused selections keep full strength: tables are often read without focus, and the focus ring
        // already shows which control has the keyboard.
        put("@selectionBackground", over(selected, input));
        put("@selectionForeground", color("text.primary"));
        put("@selectionInactiveBackground", over(selected, input));
        put("@selectionInactiveForeground", color("text.primary"));
        put("@menuSelectionBackground", over(selected, popover));
        put("@menuHoverBackground", over(hover, popover));
        put("@cellFocusColor", color("focus.ring"));
        put("@icon", color("text.secondary"));
        put("@buttonArrowColor", color("text.secondary"));
        put("@buttonDisabledArrowColor", color("text.disabled"));
        put("@buttonHoverArrowColor", color("text.primary"));
        put("@buttonPressedArrowColor", color("text.primary"));

        // Shape and focus: arcs are diameters; the ring is drawn inside the edge so no control changes size
        String arc = number(tokens.radius().get("md") * 2);
        values.put("Component.arc", arc);
        values.put("Button.arc", arc);
        values.put("TextComponent.arc", arc);
        values.put("CheckBox.arc", number(tokens.radius().get("sm") * 2));
        values.put("Component.focusWidth", "0");
        values.put("Component.innerFocusWidth", number(tokens.size().get("focus.width") / 2));
        values.put("Button.innerFocusWidth", number(tokens.size().get("focus.width") / 2));
        values.put("Component.borderWidth", number(tokens.size().get("border")));

        put("Component.borderColor", color("border.control"));
        put("Component.disabledBorderColor", color("border.subtle"));
        put("Component.focusColor", color("focus.ring"));
        put("Component.focusedBorderColor", color("focus.ring"));
        put("Component.linkColor", color("accent.primary"));
        put("Component.error.borderColor", color("status.danger"));
        put("Component.error.focusedBorderColor", color("status.danger"));
        put("Component.warning.borderColor", color("status.warning"));
        put("Component.warning.focusedBorderColor", color("status.warning"));
        put("Component.success.borderColor", color("status.success"));
        put("Component.success.focusedBorderColor", color("status.success"));

        // Secondary (default) button: raised face, control edge, hover wash and a strong edge on hover
        put("Button.background", raised);
        put("Button.foreground", color("text.primary"));
        put("Button.hoverBackground", over(hover, raised));
        put("Button.pressedBackground", over(pressed, raised));
        put("Button.borderColor", color("border.control"));
        put("Button.disabledBorderColor", color("border.subtle"));
        put("Button.hoverBorderColor", color("border.strong"));
        put("Button.focusedBorderColor", color("focus.ring"));
        put("Button.disabledText", color("text.disabled"));
        put("Button.selectedBackground", over(selected, raised));
        put("Button.selectedForeground", color("text.primary"));
        put("Button.toolbar.hoverBackground", over(hover, base));
        put("Button.toolbar.pressedBackground", over(pressed, base));
        put("Button.toolbar.selectedBackground", over(selected, base));

        // Primary (default) button: accent fill; its focus shows as an inner line in the on-accent color
        int accent = color("accent.primary");
        put("Button.default.background", accent);
        put("Button.default.foreground", color("text.onAccent"));
        put("Button.default.hoverBackground", over(hover, accent));
        put("Button.default.pressedBackground", over(pressed, accent));
        put("Button.default.borderColor", accent);
        put("Button.default.hoverBorderColor", color("border.strong"));
        put("Button.default.focusedBorderColor", color("text.primary"));
        put("Button.default.focusColor", color("text.onAccent"));
        values.put("Button.default.boldText", "false");
        values.put("[style]Button.primary", style(
              "background", accent,
              "foreground", color("text.onAccent"),
              "hoverBackground", over(hover, accent),
              "pressedBackground", over(pressed, accent),
              "borderColor", accent,
              "hoverBorderColor", color("border.strong"),
              "focusedBorderColor", color("text.primary"),
              "focusColor", color("text.onAccent")));

        // Toggle: selection is violet, never only a lighter face
        put("ToggleButton.background", raised);
        put("ToggleButton.selectedBackground", over(selected, raised));
        put("ToggleButton.selectedForeground", color("text.primary"));
        put("ToggleButton.disabledSelectedBackground",
              over(ColorMath.withAlpha(selected, ColorMath.alpha(selected) / 2), raised));
        put("ToggleButton.toolbar.selectedBackground", over(selected, base));

        // Check boxes and radio buttons: accent fill when selected
        put("CheckBox.icon.borderColor", color("border.control"));
        put("CheckBox.icon.background", input);
        put("CheckBox.icon.selectedBorderColor", accent);
        put("CheckBox.icon.selectedBackground", accent);
        put("CheckBox.icon.checkmarkColor", color("text.onAccent"));
        put("CheckBox.icon.disabledBorderColor", color("border.subtle"));
        put("CheckBox.icon.disabledCheckmarkColor", color("text.disabled"));
        put("CheckBox.icon.focusedBorderColor", color("focus.ring"));
        put("CheckBox.icon.hoverBorderColor", color("border.strong"));
        put("CheckBox.icon.hoverBackground", over(hover, input));
        put("CheckBox.icon.pressedBackground", over(pressed, input));
        put("CheckBox.icon.hoverSelectedBackground", over(hover, accent));
        put("CheckBox.icon.pressedSelectedBackground", over(pressed, accent));

        // Text and lists
        put("TextField.placeholderForeground", color("text.muted"));
        put("Label.disabledForeground", color("text.disabled"));
        put("Separator.foreground", color("border.subtle"));
        put("Table.gridColor", color("border.subtle"));
        put("TableHeader.background", raised);
        put("TableHeader.separatorColor", color("border.subtle"));
        put("TableHeader.bottomSeparatorColor", color("border.control"));
        put("TableHeader.hoverBackground", over(hover, raised));
        put("TableHeader.pressedBackground", over(pressed, raised));

        // Containers and popups
        put("Panel.background", base);
        put("TabbedPane.background", base);
        put("TabbedPane.hoverColor", over(hover, base));
        put("TabbedPane.focusColor", over(hover, base));
        put("TabbedPane.contentAreaColor", color("border.subtle"));
        put("TabbedPane.inactiveUnderlineColor", color("border.control"));
        put("TabbedPane.disabledUnderlineColor", color("border.subtle"));
        put("PopupMenu.borderColor", color("border.subtle"));
        put("MenuBar.borderColor", color("border.subtle"));
        put("ToolTip.background", popover);
        put("ToolTip.foreground", color("text.primary"));
        put("TitlePane.background", raised);
        put("TitlePane.inactiveBackground", base);
        put("TitlePane.foreground", color("text.primary"));
        put("TitlePane.inactiveForeground", color("text.muted"));
        put("RootPane.activeBorderColor", color("border.subtle"));
        put("RootPane.inactiveBorderColor", color("border.subtle"));

        // Indicators
        put("ProgressBar.background", color("border.subtle"));
        put("ProgressBar.foreground", accent);
        put("ProgressBar.selectionForeground", color("text.onAccent"));
        put("ProgressBar.selectionBackground", color("text.primary"));
        put("Slider.trackColor", color("border.control"));
        put("Slider.trackValueColor", accent);
        put("Slider.thumbColor", accent);
        put("Slider.disabledTrackColor", color("border.subtle"));
        put("Slider.disabledThumbColor", color("text.disabled"));
        put("ScrollBar.track", base);
        put("ScrollBar.thumb", color("border.control"));
        put("ScrollBar.hoverTrackColor", over(hover, base));
        put("ScrollBar.hoverThumbColor", color("text.muted"));
        put("ScrollBar.pressedThumbColor", color("text.secondary"));

        // Type: Roboto everywhere; Java2D cannot pick weights from the variable font, so medium is its own family
        float body = tokens.typeStyle("body").size();
        values.put("defaultFont", number(body) + " \"" + tokens.family() + "\"");
        String medium = "\"" + tokens.family() + " Medium\"";
        for (String platform : new String[] { "", "[win]", "[mac]", "[linux]" }) {
            values.put(platform + "semibold.font", medium);
            values.put(platform + "light.font", "+0");
        }
        return values;
    }

    private int color(String role) {
        return tokens.color(role, mode);
    }

    private void put(String key, int argb) {
        values.put(key, hex(argb));
    }

    /** A FlatLaf style string from alternating property names and colors. */
    private static String style(Object... pairs) {
        StringBuilder style = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            style.append(pairs[i]).append(": ").append(hex((Integer) pairs[i + 1])).append("; ");
        }
        return style.toString().trim();
    }

    private static String number(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }
}
