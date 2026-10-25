/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
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
 * again a little faster. A selected one, set with {@link #setSelected}, is on: the inverse face and frame, the label
 * in {@code text.onInverse}, hover and press leaning toward it like the primary button; disabled, a faint inverse face.
 * A click never selects it. Keyboard focus rings a selected one from outside ({@link FocusHalo}), or with two lines
 * inside where that has no room. It paints itself from the tokens of the current mode.
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
        int inverse = tokens.color("surface.inverse", mode);
        int onInverse = tokens.color("text.onInverse", mode);
        int face = !selected ? raised
              : enabled ? inverse : ColorMath.over(tokens.color("surface.inverseDisabled", mode), raised);
        if (selected && (pressed || hovered)) {
            int tint = tokens.alpha(pressed ? "tint.pressed" : "tint.hover");
            face = ColorMath.over(ColorMath.withAlpha(onInverse, tint), inverse);
        } else if (pressed || hovered) {
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
            int edge = selected && !hovered && !pressed ? face : tokens.color("border.strong", mode);
            Color full = TokenMotion.color(this, EDGE, new Color(edge, true), TokenMotion.fast());
            g.setColor(TokenMotion.mix(rest, full, reveal));
            if (reveal >= 1) {
                frame(g, 0, 0, width, height, line);
            } else {
                int tick = Math.min(UIUtil.scaleForGUI(Math.round(tokens.size().get("tick.control"))),
                      Math.min(width, height) / 2);
                ticks(g, width, height, line, Math.round(tick + (width / 2f - tick) * reveal),
                      Math.round(tick + (height / 2f - tick) * reveal));
            }
            boolean focused = enabled && FlatUIUtils.isPermanentFocusOwner(this);
            if (focused && selected) {
                if (!FocusHalo.around(this, new RoundRectangle2D.Float(0, 0, width, height, 0, 0))) {
                    // No room outside: the label color inside the strong edge, as on the primary button
                    g.setColor(new Color(tokens.color("text.primary", mode), true));
                    frame(g, 0, 0, width, height, 1);
                    g.setColor(new Color(onInverse, true));
                    frame(g, 1, 1, width - 2, height - 2, 1);
                }
            } else {
                FocusHalo.clear(this);
                if (focused) {
                    g.setColor(new Color(tokens.color("focus.ring", mode), true));
                    int ring = Math.max(1, UIUtil.scaleForGUI(Math.round(tokens.size().get("focus.width"))));
                    for (int inset = 0; inset < ring; inset++) {
                        frame(g, inset, inset, width - 2 * inset, height - 2 * inset, 1);
                    }
                }
            }

            int text = selected ? onInverse : tokens.color(!enabled ? "text.disabled"
                  : hovered || pressed ? "text.primary" : "text.secondary", mode);
            g.setColor(TokenMotion.color(this, TEXT, new Color(text, true), TokenMotion.buttonState(this)));
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            String label = getText();
            int x = (width - metrics.stringWidth(label)) / 2;
            int y = (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(label, x, y);
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
