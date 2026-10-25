/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Paint;
import java.awt.geom.RoundRectangle2D;
import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatButtonBorder;
import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatToggleButtonUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * Buttons of the MegaMek look and feels (docs/design-system/tokens.md, Motion): FlatLaf's buttons, with their face,
 * text and edge easing between states through {@link TokenMotion} instead of switching at once. A press shows at once;
 * hover comes in quickly and lingers on the way out.
 * <p>
 * A selected button and the primary (default) button show the cream face; their hover and press lean toward
 * {@code text.onInverse} ({@code selectedHoverBackground} and {@code selectedPressedBackground}, which FlatLaf leaves
 * out for a selected button). Keyboard focus on a cream face rings it from outside through {@link FocusHalo}, or with
 * the two inside lines where that ring has no room.
 */
public class TokenButtonUI extends FlatButtonUI {
    private static final String BACKGROUND = "button.background";
    private static final String FOREGROUND = "button.foreground";
    private static final String EDGE = "button.edge";
    private static final String HALOED = "TokenButtonUI.haloed";
    /** The inner focus line of a cream face whose outside ring has no room. */
    static final String INVERSE_FOCUS_COLOR = "Button.inverseFocusColor";

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
        return background(c, selected(c, "Button.", "Background", super.getBackground(c)),
              getBackgroundBase(c, isDefault(c)));
    }

    @Override
    protected Color getForeground(JComponent c) {
        return foreground(c, selected(c, "Button.", "Foreground", super.getForeground(c)));
    }

    /**
     * The {@code selectedHover} or {@code selectedPressed} color of a hovered or pressed selected button, or
     * {@code target} when there is none.
     */
    static Color selected(JComponent c, String prefix, String part, Color target) {
        if (!(c instanceof AbstractButton button) || !button.isSelected() || !button.isEnabled() || isTab(c)) {
            return target;
        }
        ButtonModel model = button.getModel();
        String state = model.isPressed() ? "selectedPressed" : model.isRollover() ? "selectedHover" : null;
        Color color = state == null ? null : UIManager.getColor(prefix + state + part);
        return color == null ? target : color;
    }

    /** Whether {@code c} shows the cream face: the primary button, or a selected button that is not a tab. */
    static boolean cream(Component c) {
        if (!(c instanceof AbstractButton button) || !button.isEnabled() || !button.isContentAreaFilled()
              || isTab(c)) {
            return false;
        }
        return button.isSelected() || isDefault(c) || primary(button);
    }

    private static boolean primary(AbstractButton button) {
        Object style = button.getClientProperty(FlatClientProperties.STYLE_CLASS);
        if (style instanceof String text) {
            for (String word : text.split(" ")) {
                if ("primary".equals(word)) {
                    return true;
                }
            }
        } else if (style instanceof String[] words) {
            for (String word : words) {
                if ("primary".equals(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTab(Component c) {
        return c instanceof JToggleButton toggle
              && FlatClientProperties.BUTTON_TYPE_TAB.equals(toggle.getClientProperty(FlatClientProperties.BUTTON_TYPE));
    }

    private static boolean isToolBar(Component c) {
        return c.getParent() instanceof JToolBar || (c instanceof JComponent component
              && FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON.equals(
              component.getClientProperty(FlatClientProperties.BUTTON_TYPE)));
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
            return background(c, selected(c, "ToggleButton.", "Background", super.getBackground(c)),
                  getBackgroundBase(c, false));
        }

        @Override
        protected Color getForeground(JComponent c) {
            return foreground(c, selected(c, "ToggleButton.", "Foreground", super.getForeground(c)));
        }
    }

    /**
     * The button edge, easing with the face. A focused cream face asks {@link FocusHalo} for the outside ring and then
     * paints its edge unfocused; where the ring has no room it keeps FlatLaf's inside lines, the inner one in
     * {@link #INVERSE_FOCUS_COLOR}.
     */
    public static class Border extends FlatButtonBorder {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (c instanceof JComponent component) {
                boolean ringed = cream(c) && FlatButtonUI.isFocusPainted(c) && FlatUIUtils.isPermanentFocusOwner(c)
                      && FocusHalo.around(component, face(component));
                component.putClientProperty(HALOED, ringed ? Boolean.TRUE : null);
                if (!ringed) {
                    FocusHalo.clear(component);
                }
            }
            super.paintBorder(c, g, x, y, width, height);
        }

        /** The painted face of {@code c}: its bounds, less the spacing of a toolbar button. */
        private RoundRectangle2D face(JComponent c) {
            float x = 0;
            float y = 0;
            float width = c.getWidth();
            float height = c.getHeight();
            if (isToolBar(c) && toolbarSpacingInsets != null) {
                Insets spacing = UIScale.scale(toolbarSpacingInsets);
                x += spacing.left;
                y += spacing.top;
                width -= spacing.left + spacing.right;
                height -= spacing.top + spacing.bottom;
            }
            float arc = Math.min(UIScale.scale((float) getArc(c)), Math.min(width, height));
            return new RoundRectangle2D.Float(x, y, width, height, arc, arc);
        }

        private static boolean haloed(Component c) {
            return c instanceof JComponent component && component.getClientProperty(HALOED) != null;
        }

        @Override
        protected boolean isFocused(Component c) {
            return super.isFocused(c) && !haloed(c);
        }

        @Override
        protected Color getFocusColor(Component c) {
            Color inverse = UIManager.getColor(INVERSE_FOCUS_COLOR);
            return cream(c) && inverse != null ? inverse : super.getFocusColor(c);
        }

        @Override
        protected Paint getBorderColor(Component c) {
            Paint target = haloed(c) ? unfocusedBorderColor(c) : super.getBorderColor(c);
            if (!(c instanceof AbstractButton) || !(target instanceof Color || target == null)) {
                return target;
            }
            return TokenMotion.color((JComponent) c, EDGE, (Color) target,
                  TokenMotion.buttonState((AbstractButton) c));
        }

        /** The edge {@link FlatButtonBorder#getBorderColor} gives the same button without focus. */
        private Color unfocusedBorderColor(Component c) {
            boolean def = isDefault(c);
            boolean selected = c instanceof AbstractButton button && button.isSelected();
            return FlatButtonUI.buttonStateColor(c,
                  def ? defaultBorderColor : selected && selectedBorderColor != null ? selectedBorderColor : borderColor,
                  selected && disabledSelectedBorderColor != null ? disabledSelectedBorderColor : disabledBorderColor,
                  null,
                  def ? defaultHoverBorderColor
                        : selected && hoverSelectedBorderColor != null ? hoverSelectedBorderColor : hoverBorderColor,
                  def ? defaultPressedBorderColor
                        : selected && pressedSelectedBorderColor != null ? pressedSelectedBorderColor
                        : pressedBorderColor);
        }
    }
}
