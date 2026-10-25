/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;
import javax.swing.UIManager;

import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * The switch's track and thumb. It shares the check box's colors: the track is a check box's box stretched into a pill,
 * and the thumb, on the right while on, is drawn in the check mark color. Off, the thumb takes
 * {@code CheckBox.icon.switchThumbColor}, so the side it sits on and its color both show the state. Disabled and on, the
 * track keeps a faint inverse fill and the thumb its on color.
 */
public class TokenSwitchIcon extends TokenCheckBoxIcon {
    /** The track's length, as a multiple of its height. */
    static final float LENGTH = 1.75f;
    /** The space between the track's edge and the thumb. */
    static final float INSET = 2;

    private final Color thumbColor = UIManager.getColor("CheckBox.icon.switchThumbColor");

    public TokenSwitchIcon() {
        setScale(1);
    }

    @Override
    public int getIconWidth() {
        return scale(UIScale.scale(Math.round(height() * LENGTH)));
    }

    @Override
    public int getIconHeight() {
        return scale(UIScale.scale(Math.round(height())));
    }

    @Override
    protected void paintIcon(Component c, Graphics2D g) {
        boolean on = isSelected(c);
        float h = height();
        float w = Math.round(h * LENGTH);
        if (FlatUIUtils.isPermanentFocusOwner(c) && focusWidth > 0 && FlatButtonUI.isFocusPainted(c)) {
            g.setColor(getFocusColor(c));
            float gap = gap();
            Area ring = new Area(pill(-focusWidth, w + focusWidth * 2, h + focusWidth * 2));
            ring.subtract(new Area(pill(-gap, w + gap * 2, h + gap * 2)));
            g.fill(ring);
        }
        g.setColor(getBorderColor(c, on, false));
        g.fill(pill(0, w, h));
        g.setColor(getBackground(c, on, false));
        g.fill(pill(borderWidth, w - borderWidth * 2, h - borderWidth * 2));

        float thumb = h - INSET * 2;
        float travel = c instanceof JComponent component
              ? TokenMotion.value(component, MARK, on, TokenMotion.fast()) : on ? 1 : 0;
        Color color = !c.isEnabled() ? getCheckmarkColor(c, false)
              : TokenMotion.mix(thumbColor, getCheckmarkColor(c, false), travel);
        g.setColor(color);
        float left = INSET;
        float right = w - INSET - thumb;
        g.fill(new Ellipse2D.Float(left + (right - left) * travel, INSET, thumb, thumb));
    }

    private static float height() {
        return size();
    }

    /** A pill {@code width} by {@code height} whose top-left corner is {@code inset} into the icon. */
    private static RoundRectangle2D pill(float inset, float width, float height) {
        return new RoundRectangle2D.Float(inset, inset, width, height, height, height);
    }
}
