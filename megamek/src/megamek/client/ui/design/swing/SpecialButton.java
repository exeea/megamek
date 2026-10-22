/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.ButtonModel;
import javax.swing.JButton;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatUIUtils;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.util.UIUtil;

/**
 * The special button (docs/design-system/tokens.md, Buttons): a raised face with corner ticks at rest that close into a
 * full frame under the pointer, the HUD utility look. A selected one, set with {@link #setSelected}, has a violet wash
 * and frame; a click never selects it. It paints itself from the tokens of the current mode; the reveal animation comes
 * with the motion work.
 */
public class SpecialButton extends JButton {
    private final TokenButtons.Size size;

    SpecialButton(String text, TokenButtons.Size size) {
        super(text);
        this.size = size;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize()
              : TokenButtons.sized(super.getPreferredSize(), size, false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = FlatLaf.isLafDark() ? Mode.DARK : Mode.LIGHT;
        ButtonModel model = getModel();
        boolean enabled = model.isEnabled();
        boolean pressed = enabled && model.isArmed() && model.isPressed();
        boolean hovered = enabled && model.isRollover();
        boolean selected = model.isSelected();
        int raised = tokens.color("surface.raised", mode);
        int face = selected ? ColorMath.over(tokens.color("selected.background", mode), raised) : raised;
        if (pressed || hovered) {
            int wash = tokens.color("surface.hover", mode);
            if (pressed) {
                wash = ColorMath.withAlpha(wash, Math.min(255, ColorMath.alpha(wash) * 2));
            }
            face = ColorMath.over(wash, face);
        }

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            int line = Math.max(1, UIUtil.scaleForGUI(Math.round(tokens.size().get("tick.width"))));
            g.setColor(new Color(face, true));
            g.fillRect(0, 0, width, height);

            if (selected || (hovered || pressed)) {
                g.setColor(new Color(tokens.color(selected ? "selected.primary" : "border.strong", mode), true));
                frame(g, 0, 0, width, height, line);
            } else {
                g.setColor(new Color(tokens.color(enabled ? "border.control" : "border.subtle", mode), true));
                int tick = UIUtil.scaleForGUI(Math.round(tokens.size().get("tick.control")));
                ticks(g, width, height, line, Math.min(tick, Math.min(width, height) / 2));
            }
            if (enabled && FlatUIUtils.isPermanentFocusOwner(this)) {
                g.setColor(new Color(tokens.color("focus.ring", mode), true));
                int ring = Math.max(1, UIUtil.scaleForGUI(Math.round(tokens.size().get("focus.width"))));
                for (int inset = 0; inset < ring; inset++) {
                    frame(g, inset, inset, width - 2 * inset, height - 2 * inset, 1);
                }
            }

            String role = !enabled ? "text.disabled" : selected || hovered || pressed ? "text.primary" : "text.secondary";
            g.setColor(new Color(tokens.color(role, mode), true));
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            String text = getText();
            int x = (width - metrics.stringWidth(text)) / 2;
            int y = (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
    }

    private static void frame(Graphics2D g, int x, int y, int width, int height, int line) {
        g.fillRect(x, y, width, line);
        g.fillRect(x, y + height - line, width, line);
        g.fillRect(x, y + line, line, height - 2 * line);
        g.fillRect(x + width - line, y + line, line, height - 2 * line);
    }

    private static void ticks(Graphics2D g, int width, int height, int line, int length) {
        int right = width - line;
        int bottom = height - line;
        g.fillRect(0, 0, length, line);
        g.fillRect(0, 0, line, length);
        g.fillRect(width - length, 0, length, line);
        g.fillRect(right, 0, line, length);
        g.fillRect(0, bottom, length, line);
        g.fillRect(0, height - length, line, length);
        g.fillRect(width - length, bottom, length, line);
        g.fillRect(right, height - length, line, length);
    }
}
