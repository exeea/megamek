/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static megamek.client.ui.design.ColorMath.hex;
import static megamek.client.ui.design.ColorMath.over;

import java.util.LinkedHashMap;
import java.util.List;
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
    /** Menu row padding above and below the text, so a menu row is control.sm (28) tall like the select's rows. */
    private static final int MENU_ROW_MARGIN = 5;
    /** Menu bar item padding above and below the text. */
    private static final int MENU_BAR_MARGIN = 4;
    /** The widest a tooltip's text runs before it wraps, about 45 characters of small text. */
    private static final int TOOLTIP_MAX_WIDTH = 280;

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
        put("@menuSelectionBackground", over(hover, popover));
        put("@menuHoverBackground", over(hover, popover));
        put("@menuCheckBackground", over(selected, popover));
        put("@menuAcceleratorForeground", color("text.muted"));
        put("@menuAcceleratorSelectionForeground", color("text.secondary"));
        put("@cellFocusColor", color("focus.ring"));
        put("@icon", color("text.secondary"));
        put("@buttonArrowColor", color("text.secondary"));
        put("@buttonDisabledArrowColor", color("text.disabled"));
        put("@buttonHoverArrowColor", color("text.primary"));
        put("@buttonPressedArrowColor", color("text.primary"));

        // Shape and focus: arcs are diameters. The focus ring is drawn inside the edge so no control changes size;
        // small indicators (check box, radio, slider thumb) get it around them, a gap away. Status edges give way to
        // the ring while focused: the field's icon or message keeps the status.
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
        put("Component.error.focusedBorderColor", color("focus.ring"));
        put("Component.warning.borderColor", color("status.warning"));
        put("Component.warning.focusedBorderColor", color("focus.ring"));
        put("Component.success.borderColor", color("status.success"));
        put("Component.success.focusedBorderColor", color("focus.ring"));

        // Inputs line up with buttons: control.sm by default, control.md through TokenFields. The edge turns strong
        // under the pointer; a read-only input has no fill and a subtle edge, but keeps primary text.
        values.put(FieldEdge.HEIGHT, number(tokens.size().get("control.sm")));
        put(FieldEdge.HOVER_COLOR, color("border.strong"));
        put(FieldEdge.READ_ONLY_COLOR, color("border.subtle"));
        put(TokenFormField.LABEL_COLOR, color("text.secondary"));
        put(TokenFormField.HELPER_COLOR, color("text.muted"));
        int padY = Math.round(tokens.space().get("xxs"));
        int padX = Math.round(tokens.space().get("md"));
        values.put("@componentMargin", padY + "," + padX + "," + padY + "," + padX);
        values.put("TextFieldUI", FieldUIs.TextField.class.getName());
        values.put("PasswordFieldUI", FieldUIs.PasswordField.class.getName());
        values.put("FormattedTextFieldUI", FieldUIs.FormattedTextField.class.getName());
        values.put("SpinnerUI", FieldUIs.Spinner.class.getName());
        values.put("ComboBoxUI", FieldUIs.ComboBox.class.getName());
        for (String prefix : new String[] { "TextField", "PasswordField", "FormattedTextField" }) {
            values.put(prefix + ".border", FieldBorders.Text.class.getName());
        }
        values.put("Spinner.border", FieldBorders.Round.class.getName());
        values.put("ComboBox.border", FieldBorders.Round.class.getName());
        values.put("ScrollPane.border", FieldBorders.ScrollPane.class.getName());
        for (String prefix : new String[] { "TextField", "PasswordField", "FormattedTextField", "TextArea", "TextPane",
                                            "EditorPane" }) {
            put(prefix + ".inactiveBackground", base);
            put(prefix + ".placeholderForeground", color("text.muted"));
        }

        // Secondary (default) button: raised face, control edge; hover adds a wash, a strong edge and brighter text.
        // Every kind is disabled the same way: raised face, subtle edge, disabled text.
        for (String prefix : new String[] { "Button.", "ToggleButton." }) {
            put(prefix + "background", raised);
            put(prefix + "foreground", color("text.secondary"));
            put(prefix + "hoverForeground", color("text.primary"));
            put(prefix + "pressedForeground", color("text.primary"));
            put(prefix + "hoverBackground", over(hover, raised));
            put(prefix + "pressedBackground", over(pressed, raised));
            put(prefix + "focusedBackground", raised);
            put(prefix + "disabledBackground", raised);
            put(prefix + "disabledText", color("text.disabled"));
            put(prefix + "selectedBackground", over(selected, raised));
            put(prefix + "selectedForeground", color("text.primary"));
            put(prefix + "toolbar.hoverBackground", over(hover, base));
            put(prefix + "toolbar.hoverForeground", color("text.primary"));
            put(prefix + "toolbar.pressedBackground", over(pressed, base));
            put(prefix + "toolbar.pressedForeground", color("text.primary"));
            put(prefix + "toolbar.selectedBackground", over(selected, base));
            put(prefix + "toolbar.selectedForeground", color("selected.primary"));
        }
        // Edges are shared by plain and toggle buttons; a selected toggle keeps a violet edge in every state
        put("Button.borderColor", color("border.control"));
        put("Button.disabledBorderColor", color("border.subtle"));
        put("Button.hoverBorderColor", color("border.strong"));
        put("Button.pressedBorderColor", color("border.strong"));
        put("Button.focusedBorderColor", color("focus.ring"));
        put("Button.selectedBorderColor", color("selected.primary"));
        put("Button.hoverSelectedBorderColor", color("selected.primary"));
        put("Button.pressedSelectedBorderColor", color("selected.primary"));
        put("Button.focusedSelectedBorderColor", color("focus.ring"));
        put("Button.disabledSelectedBorderColor", color("border.subtle"));

        // Primary (default) button: accent fill; its focus shows as an inner line in the on-accent color. A wash of the
        // surface color is invisible on the accent, so hover leans toward the label color and press away from it.
        int accent = color("accent.primary");
        int accentHover = over(ColorMath.withAlpha(color("text.onAccent"), tokens.alpha("tint.hover")), accent);
        int accentPressed = over(ColorMath.withAlpha(color("text.primary"), tokens.alpha("tint.pressed")), accent);
        put("Button.default.background", accent);
        put("Button.default.foreground", color("text.onAccent"));
        put("Button.default.hoverForeground", color("text.onAccent"));
        put("Button.default.pressedForeground", color("text.onAccent"));
        put("Button.default.hoverBackground", accentHover);
        put("Button.default.pressedBackground", accentPressed);
        put("Button.default.focusedBackground", accent);
        put("Button.default.borderColor", accent);
        put("Button.default.hoverBorderColor", color("border.strong"));
        put("Button.default.focusedBorderColor", color("text.primary"));
        put("Button.default.focusColor", color("text.onAccent"));
        values.put("Button.default.boldText", "false");
        values.put("[style]Button.primary", style(
              "background", accent,
              "foreground", color("text.onAccent"),
              "hoverForeground", color("text.onAccent"),
              "pressedForeground", color("text.onAccent"),
              "hoverBackground", accentHover,
              "pressedBackground", accentPressed,
              "focusedBackground", accent,
              "borderColor", accent,
              "hoverBorderColor", color("border.strong"),
              "pressedBorderColor", color("border.strong"),
              "focusedBorderColor", color("text.primary"),
              "focusColor", color("text.onAccent")));

        // Danger: the danger color on text and edge; hover and press tint the face with it
        int danger = color("status.danger");
        values.put("[style]Button.danger", style(
              "foreground", danger,
              "hoverForeground", danger,
              "pressedForeground", danger,
              "borderColor", danger,
              "hoverBorderColor", danger,
              "pressedBorderColor", danger,
              "hoverBackground", over(ColorMath.withAlpha(danger, tokens.alpha("tint.hover")), raised),
              "pressedBackground", over(ColorMath.withAlpha(danger, tokens.alpha("tint.pressed")), raised)));

        // Quiet and icon buttons have no face or edge until hovered; a selected icon toggle is a violet wash and glyph
        for (String type : new String[] { "Button", "ToggleButton" }) {
            values.put("[style]" + type + ".quiet", "buttonType: borderless");
            values.put("[style]" + type + ".icon", "buttonType: borderless; squareSize: true");
            values.put("[style]" + type + ".regular", padding("lg"));
            values.put("[style]" + type + ".compact", padding("md"));
        }

        // Toggle: selection is a violet wash and edge, never only a lighter face
        put("ToggleButton.disabledSelectedBackground",
              over(ColorMath.withAlpha(selected, ColorMath.alpha(selected) / 2), raised));

        // Check boxes and radio buttons: accent fill when selected. Focus never changes the box, it adds a ring around
        // it a gap away, so a checked box still looks checked.
        float ringAround = tokens.size().get("focus.width") + tokens.size().get("focus.gap");
        values.put("CheckBox.icon", TokenCheckBoxIcon.class.getName());
        values.put("RadioButton.icon", TokenRadioButtonIcon.class.getName());
        values.put("CheckBox.icon.focusWidth", number(ringAround));
        // The ring sits around the icon, so the label moves out of its way
        values.put("CheckBox.iconTextGap", number(4 + ringAround));
        values.put("RadioButton.iconTextGap", number(4 + ringAround));
        put("CheckBox.icon.focusColor", color("focus.ring"));
        put("CheckBox.icon.borderColor", color("border.control"));
        put("CheckBox.icon.background", input);
        put("CheckBox.icon.selectedBorderColor", accent);
        put("CheckBox.icon.selectedBackground", accent);
        put("CheckBox.icon.checkmarkColor", color("text.onAccent"));
        put("CheckBox.icon.disabledBorderColor", color("border.subtle"));
        put("CheckBox.icon.disabledCheckmarkColor", color("text.disabled"));
        put("CheckBox.icon.focusedBorderColor", color("border.control"));
        put("CheckBox.icon.focusedBackground", input);
        put("CheckBox.icon.focusedSelectedBorderColor", accent);
        put("CheckBox.icon.focusedSelectedBackground", accent);
        put("CheckBox.icon.focusedCheckmarkColor", color("text.onAccent"));
        put("CheckBox.icon.hoverBorderColor", color("border.strong"));
        put("CheckBox.icon.pressedBorderColor", color("border.strong"));
        put("CheckBox.icon.hoverBackground", over(hover, input));
        put("CheckBox.icon.pressedBackground", over(pressed, input));
        // A checked box hovers and presses like a primary button: a surface wash is invisible on the accent
        put("CheckBox.icon.hoverSelectedBorderColor", color("border.strong"));
        put("CheckBox.icon.pressedSelectedBorderColor", color("border.strong"));
        put("CheckBox.icon.hoverSelectedBackground", accentHover);
        put("CheckBox.icon.pressedSelectedBackground", accentPressed);
        // An off switch's thumb (TokenSwitchIcon); on, it takes the check mark color
        put("CheckBox.icon.switchThumbColor", color("text.secondary"));

        // Text and lists
        put("Label.disabledForeground", color("text.disabled"));
        put("Separator.foreground", color("border.subtle"));
        put("Table.gridColor", color("border.subtle"));
        put("TableHeader.background", raised);
        put("TableHeader.separatorColor", color("border.subtle"));
        put("TableHeader.bottomSeparatorColor", color("border.control"));
        put("TableHeader.hoverBackground", over(hover, raised));
        put("TableHeader.pressedBackground", over(pressed, raised));
        // Lists and trees (TokenListUI, TokenTreeUI): rows at least control.sm high, inset and rounded like the popup
        // list. The pointer's row gets the hover wash; a selected row keeps the violet wash, focused or not, and adds a
        // violet marker at its leading edge, so selection never relies on color alone. Text starts past the marker.
        values.put("ListUI", TokenListUI.class.getName());
        values.put("TreeUI", TokenTreeUI.class.getName());
        String rowArc = number(tokens.radius().get("sm") * 2);
        String rowInsets = "0," + Math.round(tokens.space().get("xs")) + ",0," + Math.round(tokens.space().get("xs"));
        int markerSpace = Math.round(tokens.space().get("xs") + tokens.size().get("selection.width")
              + tokens.space().get("sm"));
        int textSide = Math.round(tokens.space().get("xs") + tokens.space().get("sm"));
        values.put("List.selectionArc", rowArc);
        values.put("List.selectionInsets", rowInsets);
        values.put(TokenListUI.CELL_MARGINS, "1," + markerSpace + ",1," + textSide);
        values.put(TokenLists.ROW_HEIGHT, number(tokens.size().get("control.sm")));
        values.put(TokenLists.TWO_LINE_HEIGHT, number(tokens.size().get("control.lg")));
        put(TokenLists.HOVER_BACKGROUND, over(hover, input));
        put(TokenLists.MARKER_COLOR, color("selected.primary"));
        put(TokenLists.EMPTY_FOREGROUND, color("text.muted"));
        put(TokenLists.DETAIL_FOREGROUND, color("text.secondary"));
        values.put("Tree.selectionArc", rowArc);
        values.put("Tree.selectionInsets", rowInsets);
        values.put("Tree.rowHeight", number(tokens.size().get("control.sm")));
        // Each level indents 16, as the libGDX tree does; the leading border keeps the chevrons clear of the marker
        int halfIndent = Math.round(tokens.space().get("md"));
        values.put("Tree.leftChildIndent", Integer.toString(halfIndent));
        values.put("Tree.rightChildIndent", Integer.toString(halfIndent));
        values.put("Tree.border", "1," + markerSpace + ",1," + Math.round(tokens.space().get("xs")));
        values.put("Tree.paintLines", "false");
        values.put("Tree.showDefaultIcons", "false");

        // Containers and popups
        put("Panel.background", base);
        put("TabbedPane.background", base);
        put("TabbedPane.hoverColor", over(hover, base));
        // Focus is a ring inside the selected tab (TokenTabbedPaneUI), not a tint that reads like hover
        put("TabbedPane.focusColor", base);
        values.put("TabbedPaneUI", TokenTabbedPaneUI.class.getName());
        // Tabs: secondary labels that brighten under the pointer; the selected tab has a violet underline whether or
        // not the strip has focus. Plain tab panes are compact; TokenTabs makes regular ones.
        put("TabbedPane.contentAreaColor", color("border.subtle"));
        put("TabbedPane.foreground", color("text.secondary"));
        put("TabbedPane.hoverForeground", color("text.primary"));
        put("TabbedPane.selectedForeground", color("text.primary"));
        put("TabbedPane.disabledForeground", color("text.disabled"));
        put("TabbedPane.underlineColor", color("selected.primary"));
        put("TabbedPane.inactiveUnderlineColor", color("selected.primary"));
        put("TabbedPane.disabledUnderlineColor", color("border.subtle"));
        values.put("TabbedPane.tabSelectionHeight", number(tokens.size().get("selection.width")));
        values.put("TabbedPane.contentSeparatorHeight", number(tokens.size().get("border")));
        values.put("TabbedPane.tabHeight", number(tokens.size().get("control.sm")));
        int tabSide = Math.round(tokens.space().get("md"));
        values.put("TabbedPane.tabInsets", "0," + tabSide + ",0," + tabSide);
        put("PopupMenu.borderColor", color("border.control"));
        // Select (TokenSelects): the shared popup list. Rows under the pointer or the keyboard get the hover wash, not
        // the selection violet; the current value has a violet check instead.
        put("ComboBox.popupBackground", popover);
        put("ComboBox.selectionBackground", over(hover, popover));
        put("ComboBox.selectionForeground", color("text.primary"));
        values.put("ComboBox.selectionArc", number(tokens.radius().get("sm") * 2));
        int rowInset = Math.round(tokens.space().get("xs"));
        values.put("ComboBox.selectionInsets", "0," + rowInset + ",0," + rowInset);
        // The popup pads its top and bottom as the rows inset their sides, so the highlight sits evenly inside
        values.put("ComboBox.popupInsets", rowInset + ",0," + rowInset + ",0");
        values.put("ComboBox.maximumRowCount", "10");
        values.put(TokenSelects.ROW_HEIGHT, number(tokens.size().get("control.sm")));
        put(TokenSelects.CHECK_COLOR, color("selected.primary"));
        put(TokenSelects.INLINE_FOREGROUND, color("text.secondary"));
        put(TokenSelects.INLINE_HOVER_FOREGROUND, color("text.primary"));
        put(TokenSelects.PLACEHOLDER_FOREGROUND, color("text.muted"));
        // Menus: the select's popup and rows. The row under the pointer or the keyboard gets the hover wash (the
        // @menu* variables above), checks and radios are violet, and accelerators are muted.
        int rowSide = Math.round(tokens.space().get("xs") + tokens.space().get("md"));
        int border = Math.round(tokens.size().get("border"));
        values.put("Popup.borderCornerRadius", number(tokens.radius().get("md")));
        values.put("PopupMenu.borderInsets", (rowInset + border) + "," + border + "," + (rowInset + border) + ","
              + border);
        values.put("MenuItem.selectionInsets", "0," + rowInset + ",0," + rowInset);
        values.put("MenuItem.selectionArc", number(tokens.radius().get("sm") * 2));
        values.put("@menuItemMargin", MENU_ROW_MARGIN + "," + rowSide + "," + MENU_ROW_MARGIN + "," + rowSide);
        values.put("MenuBar.itemMargins", MENU_BAR_MARGIN + "," + rowSide + "," + MENU_BAR_MARGIN + "," + rowSide);
        put("CheckBoxMenuItem.icon.checkmarkColor", color("selected.primary"));
        put("CheckBoxMenuItem.icon.disabledCheckmarkColor", color("text.disabled"));
        put("Menu.icon.arrowColor", color("text.secondary"));
        put("Menu.icon.disabledArrowColor", color("text.disabled"));
        for (String item : List.of("Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem")) {
            put(item + ".selectionBackground", over(hover, popover));
            put(item + ".selectionForeground", color("text.primary"));
        }
        put("MenuBar.borderColor", color("border.subtle"));
        // Tooltips (TokenToolTipUI): a card on the popup surface with the popup's edge and corner. Plain text wraps
        // at the maximum width; a card's detail text and its shortcut chip are secondary.
        put("ToolTip.background", popover);
        put("ToolTip.foreground", color("text.primary"));
        values.put("ToolTipUI", TokenToolTipUI.class.getName());
        int tipY = Math.round(tokens.space().get("sm")) + border;
        int tipX = Math.round(tokens.space().get("md")) + border;
        values.put("ToolTip.border", tipY + "," + tipX + "," + tipY + "," + tipX + "," + hex(color("border.control"))
              + "," + border + "," + number(tokens.radius().get("md") * 2));
        values.put(TokenTooltips.MAX_WIDTH, number(TOOLTIP_MAX_WIDTH));
        put(TokenTooltips.DETAIL_FOREGROUND, color("text.secondary"));
        put(TokenTooltips.KEY_FOREGROUND, color("text.secondary"));
        put(TokenTooltips.KEY_BORDER_COLOR, color("border.control"));
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
        // Sliders (TokenSliderUI): an accent fill up to a round icon.sm thumb on an xs pill track, the same with ticks
        // and labels. Hover and drag lean the thumb as on a checked box and give it a strong edge; focus rings it a gap
        // away. Ticks are tick.width pills under the track, labels small and muted.
        values.put("SliderUI", TokenSliderUI.class.getName());
        values.put("Slider.trackWidth", number(tokens.space().get("xs")));
        int thumb = Math.round(tokens.size().get("icon.sm"));
        values.put("Slider.thumbSize", thumb + "," + thumb);
        values.put("Slider.focusWidth", number(ringAround));
        values.put("Slider.focusRingWidth", number(tokens.size().get("focus.width")));
        values.put("Slider.thumbBorderWidth", number(tokens.size().get("border")));
        values.put("Slider.tickLength", number(tokens.space().get("sm")));
        values.put("Slider.tickWidth", number(tokens.size().get("tick.width")));
        put("Slider.trackColor", color("border.control"));
        put("Slider.trackValueColor", accent);
        put("Slider.thumbColor", accent);
        put("Slider.thumbBorderColor", accent);
        put("Slider.hoverThumbColor", accentHover);
        put("Slider.pressedThumbColor", accentPressed);
        put("Slider.hoverThumbBorderColor", color("border.strong"));
        put("Slider.focusedColor", color("focus.ring"));
        put("Slider.tickColor", color("border.control"));
        put("Slider.disabledTrackColor", color("border.subtle"));
        put("Slider.disabledThumbColor", color("text.disabled"));
        put("Slider.disabledThumbBorderColor", color("text.disabled"));
        put("Slider.disabledTickColor", color("border.subtle"));
        put("Slider.foreground", color("text.muted"));
        // Scrollbars (TokenScrollBarUI, TokenScrollPaneUI): no track or arrows of their own, so a bar shows the content
        // it scrolls; an sm pill thumb xxs from the bar's sides, at least control.sm long. The pointer anywhere on the
        // bar shows the rail as a translucent wash over that content and brightens the thumb.
        values.put("ScrollBarUI", TokenScrollBarUI.class.getName());
        values.put("ScrollPaneUI", TokenScrollPaneUI.class.getName());
        int thumbInset = Math.round(tokens.space().get("xxs"));
        int thumbWidth = Math.round(tokens.space().get("sm"));
        values.put("ScrollBar.width", number(thumbWidth + 2 * thumbInset));
        values.put("ScrollBar.thumbInsets", thumbInset + "," + thumbInset + "," + thumbInset + "," + thumbInset);
        values.put("ScrollBar.minimumThumbSize",
              thumbWidth + "," + number(tokens.size().get("control.sm") - 2 * thumbInset));
        values.put("ScrollBar.thumbArc", "999");
        values.put("ScrollBar.trackArc", "999");
        values.put("ScrollBar.showButtons", "false");
        values.put("ScrollBar.hoverThumbWithTrack", "true");
        put("ScrollBar.track", base);
        put("ScrollBar.thumb", color("border.control"));
        put("ScrollBar.hoverTrackColor", hover);
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
        // Button captions use the label style's medium weight
        values.put("Button.font", medium);
        values.put("ToggleButton.font", medium);
        values.put("TabbedPane.font", medium);
        return values;
    }

    private int color(String role) {
        return tokens.color(role, mode);
    }

    private void put(String key, int argb) {
        values.put(key, hex(argb));
    }

    /**
     * A size style class: the side padding, a space token. The height comes from {@link TokenButtons}, because FlatLaf
     * ignores minimumHeight on buttons whose margin is not the default one.
     */
    private String padding(String side) {
        int padding = Math.round(tokens.space().get(side));
        return "margin: 0," + padding + ",0," + padding;
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
