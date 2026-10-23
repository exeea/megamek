/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import javax.swing.JComponent;

/**
 * The design system's tooltips for Swing (docs/design-system/tokens.md, Tooltips). The MegaMek look and feels draw
 * every plain-text tooltip as a small card on the popup surface and wrap long text; this adds the shortcut key chip and
 * the info card, a title over a secondary detail text. HTML tooltips keep their own layout inside the same card.
 */
public final class TokenTooltips {
    /** Client property holding the {@link Card} a component's tooltip draws; set through {@link #card}. */
    static final String CARD = "MegaMek.toolTip.card";
    static final String MAX_WIDTH = "MegaMek.toolTip.maxWidth";
    static final String DETAIL_FOREGROUND = "MegaMek.toolTip.detailForeground";
    static final String KEY_FOREGROUND = "MegaMek.toolTip.keyForeground";
    static final String KEY_BORDER_COLOR = "MegaMek.toolTip.keyBorderColor";

    private TokenTooltips() {
    }

    /** Gives {@code component} the tooltip {@code text} with the shortcut {@code key}, such as "Ctrl+S", in a chip. */
    public static <T extends JComponent> T withKey(T component, String text, String key) {
        return card(component, null, text, key);
    }

    /**
     * Gives {@code component} an info card: {@code title} in strong text over the secondary {@code detail}, with an
     * optional shortcut {@code key}. Either text may be null, but not both. Screen readers get the title and the
     * detail as the tooltip text.
     */
    public static <T extends JComponent> T card(T component, String title, String detail, String key) {
        Card card = new Card(blankToNull(title), blankToNull(detail), blankToNull(key));
        if (card.title() == null && card.detail() == null) {
            throw new IllegalArgumentException("A tooltip needs a title or a detail text");
        }
        component.putClientProperty(CARD, card);
        component.setToolTipText(card.text());
        return component;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** What a tooltip card shows; {@link #text} is the plain tooltip text the card stands for. */
    record Card(String title, String detail, String key) {
        String text() {
            if (title == null) {
                return detail;
            }
            return detail == null ? title : title + "\n" + detail;
        }
    }
}
