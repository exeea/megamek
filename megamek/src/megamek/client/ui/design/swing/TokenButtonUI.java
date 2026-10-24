/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Paint;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatButtonBorder;
import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatToggleButtonUI;
import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * Buttons of the MegaMek look and feels (docs/design-system/tokens.md, Motion): FlatLaf's buttons, with their face,
 * text and edge easing between states through {@link TokenMotion} instead of switching at once. A press shows at once;
 * hover comes in quickly and lingers on the way out.
 */
public class TokenButtonUI extends FlatButtonUI {
    private static final String BACKGROUND = "button.background";
    private static final String FOREGROUND = "button.foreground";
    private static final String EDGE = "button.edge";

    public static ComponentUI createUI(JComponent c) {
        return FlatUIUtils.canUseSharedUI(c)
              ? FlatUIUtils.createSharedUI(TokenButtonUI.class, () -> new TokenButtonUI(true))
              : new TokenButtonUI(false);
    }

    protected TokenButtonUI(boolean shared) {
        super(shared);
    }

    @Override
    protected Color getBackground(JComponent c) {
        return background(c, super.getBackground(c), getBackgroundBase(c, isDefault(c)));
    }

    @Override
    protected Color getForeground(JComponent c) {
        return foreground(c, super.getForeground(c));
    }

    static Color background(JComponent c, Color target, Color base) {
        // resolve FlatLaf's derived colors before mixing, as paintBackground would
        Color resolved = target == null ? null : FlatUIUtils.deriveColor(target, base);
        return TokenMotion.color(c, BACKGROUND, resolved, TokenMotion.buttonState((AbstractButton) c));
    }

    static Color foreground(JComponent c, Color target) {
        return TokenMotion.color(c, FOREGROUND, target, TokenMotion.buttonState((AbstractButton) c));
    }

    private static boolean isDefault(Component c) {
        return c instanceof JButton && ((JButton) c).isDefaultButton();
    }

    /** Toggle buttons, easing the same way. */
    public static class Toggle extends FlatToggleButtonUI {
        public static ComponentUI createUI(JComponent c) {
            return FlatUIUtils.canUseSharedUI(c)
                  ? FlatUIUtils.createSharedUI(Toggle.class, () -> new Toggle(true))
                  : new Toggle(false);
        }

        protected Toggle(boolean shared) {
            super(shared);
        }

        @Override
        protected Color getBackground(JComponent c) {
            return background(c, super.getBackground(c), getBackgroundBase(c, false));
        }

        @Override
        protected Color getForeground(JComponent c) {
            return foreground(c, super.getForeground(c));
        }
    }

    /** The button edge, easing with the face. */
    public static class Border extends FlatButtonBorder {
        @Override
        protected Paint getBorderColor(Component c) {
            Paint target = super.getBorderColor(c);
            if (!(c instanceof AbstractButton) || !(target instanceof Color || target == null)) {
                return target;
            }
            return TokenMotion.color((JComponent) c, EDGE, (Color) target,
                  TokenMotion.buttonState((AbstractButton) c));
        }
    }
}
