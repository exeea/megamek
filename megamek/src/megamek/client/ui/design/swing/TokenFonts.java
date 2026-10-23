/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;

import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.TypeStyle;
import megamek.client.ui.util.UIUtil;

/** The design system's text styles as Swing fonts, scaled for the GUI. */
public final class TokenFonts {
    private TokenFonts() {
    }

    /** The font of the text style {@code styleName}, such as "label" or "small", at the current GUI scale. */
    public static Font font(String styleName) {
        DesignTokens tokens = DesignTokens.shipped();
        TypeStyle style = tokens.typeStyle(styleName);
        Map<TextAttribute, Object> attributes = new HashMap<>();
        // Java2D only reaches the variable font's default (regular) instance, so medium comes from the static
        // "Roboto Medium" face generated from it in mm-data.
        boolean medium = style.weight() >= 500 && style.weight() < 600;
        attributes.put(TextAttribute.FAMILY, medium ? tokens.family() + " Medium" : tokens.family());
        attributes.put(TextAttribute.SIZE, UIUtil.scaleForGUI(style.size()));
        attributes.put(TextAttribute.TRACKING, style.tracking());
        attributes.put(TextAttribute.WEIGHT,
              style.weight() >= 600 ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR);
        return Font.getFont(attributes);
    }
}
