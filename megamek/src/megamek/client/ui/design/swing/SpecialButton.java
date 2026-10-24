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
 * The special button (docs/design-system/tokens.md, Buttons): a raised face with corner ticks at rest that grow along
 * the edges into a full frame under the pointer, the HUD utility look. The ticks close slowly ({@code slow}) and open
 * again a little faster. A selected one, set with {@link #setSelected}, has a violet wash and frame; a click never
 * selects it. It paints itself from the tokens of the current mode.
 */
public class SpecialButton extends JButton {
    private static final String FACE = "special.face";
    private static final String EDGE = "special.edge";
    private static final String TEXT = "special.text";
    private static final String REVEAL = "special.reveal";
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
            g.setColor(TokenMotion.color(this, FACE, new Color(face, true), TokenMotion.buttonState(this)));
            g.fillRect(0, 0, width, height);

            // The ticks grow along the edges until they close into the frame: slowly in, lingering out
            boolean active = selected || hovered || pressed;
            float reveal = TokenMotion.value(this, REVEAL, active,
                  pressed ? 0 : active ? TokenMotion.slow() : TokenMotion.medium());
            Color rest = new Color(tokens.color(enabled ? "border.control" : "border.subtle", mode), true);
            Color full = TokenMotion.color(this, EDGE,
                  new Color(tokens.color(selected ? "selected.primary" : "border.strong", mode), true),
                  TokenMotion.fast());
            g.setColor(TokenMotion.mix(rest, full, reveal));
            if (reveal >= 1) {
                frame(g, 0, 0, width, height, line);
            } else {
                int tick = Math.min(UIUtil.scaleForGUI(Math.round(tokens.size().get("tick.control"))),
                      Math.min(width, height) / 2);
                ticks(g, width, height, line, Math.round(tick + (width / 2f - tick) * reveal),
                      Math.round(tick + (height / 2f - tick) * reveal));
            }
            if (enabled && FlatUIUtils.isPermanentFocusOwner(this)) {
                g.setColor(new Color(tokens.color("focus.ring", mode), true));
                int ring = Math.max(1, UIUtil.scaleForGUI(Math.round(tokens.size().get("focus.width"))));
                for (int inset = 0; inset < ring; inset++) {
                    frame(g, inset, inset, width - 2 * inset, height - 2 * inset, 1);
                }
            }

            String role = !enabled ? "text.disabled" : selected || hovered || pressed ? "text.primary" : "text.secondary";
            g.setColor(TokenMotion.color(this, TEXT, new Color(tokens.color(role, mode), true),
                  TokenMotion.buttonState(this)));
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

    /** Corner ticks {@code across} long on the top and bottom edges and {@code down} long on the sides. */
    private static void ticks(Graphics2D g, int width, int height, int line, int across, int down) {
        int right = width - line;
        int bottom = height - line;
        g.fillRect(0, 0, across, line);
        g.fillRect(0, 0, line, down);
        g.fillRect(width - across, 0, across, line);
        g.fillRect(right, 0, line, down);
        g.fillRect(0, bottom, across, line);
        g.fillRect(0, height - down, line, down);
        g.fillRect(width - across, bottom, across, line);
        g.fillRect(right, height - down, line, down);
    }
}
