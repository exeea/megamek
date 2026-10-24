/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;

import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.design.DesignTokens.TypeStyle;
import megamek.client.ui.util.UIUtil;

/** The design system's text styles as Swing fonts, scaled for the GUI. */
public final class TokenFonts {
    private TokenFonts() {
    }

    /**
     * The font of the text style {@code styleName}, such as "label" or "small", at the current GUI scale; a style that
     * is upper case even in app mode (overline) is tracked for it.
     */
    public static Font font(String styleName) {
        DesignTokens tokens = DesignTokens.shipped();
        return font(styleName, tokens.textRoles().containsKey(styleName) && tokens.upperCase(styleName, Presentation.APP));
    }

    /**
     * The font of the text style {@code styleName} at the current GUI scale, tracked for upper case when
     * {@code upperCase}; only styles with caps tracking can be upper case.
     */
    public static Font font(String styleName, boolean upperCase) {
        DesignTokens tokens = DesignTokens.shipped();
        TypeStyle style = tokens.typeStyle(styleName);
        Map<TextAttribute, Object> attributes = new HashMap<>();
        // Java2D only reaches the variable font's default (regular) instance, so medium comes from the static
        // "Roboto Medium" face generated from it in mm-data.
        boolean medium = style.weight() >= 500 && style.weight() < 600;
        attributes.put(TextAttribute.FAMILY, medium ? tokens.family() + " Medium" : tokens.family());
        attributes.put(TextAttribute.SIZE, UIUtil.scaleForGUI(style.size()));
        attributes.put(TextAttribute.TRACKING, style.tracking(upperCase));
        attributes.put(TextAttribute.WEIGHT,
              style.weight() >= 600 ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR);
        return Font.getFont(attributes);
    }
}
