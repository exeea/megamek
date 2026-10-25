/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import javax.swing.JComponent;

import com.formdev.flatlaf.icons.FlatRadioButtonIcon;
import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * The radio button at the design-system size, with the design-system focus ring a gap away from the circle; see
 * {@link TokenCheckBoxIcon}. The circle eases between states and its dot grows in.
 */
public class TokenRadioButtonIcon extends FlatRadioButtonIcon {
    /** The circle's diameter, FlatCheckBoxIcon's package-private ICON_SIZE. */
    private static final float SIZE = 15;

    public TokenRadioButtonIcon() {
        setScale(TokenCheckBoxIcon.size() / SIZE);
    }

    @Override
    protected Color getBorderColor(Component c, boolean selected, boolean indeterminate) {
        return TokenCheckBoxIcon.ease(c, TokenCheckBoxIcon.EDGE, super.getBorderColor(c, selected, indeterminate));
    }

    @Override
    protected Color getBackground(Component c, boolean selected, boolean indeterminate) {
        Color base = selected ? selectedBackground : background;
        Color target = super.getBackground(c, selected, indeterminate);
        return TokenCheckBoxIcon.ease(c, TokenCheckBoxIcon.FILL,
              target == null || base == null ? target : FlatUIUtils.deriveColor(target, base));
    }

    @Override
    protected Color getCheckmarkColor(Component c, boolean indeterminate) {
        return TokenCheckBoxIcon.disabledSelected(c, isSelected(c), super.getCheckmarkColor(c, indeterminate));
    }

    @Override
    protected void paintIcon(Component c, Graphics2D g) {
        super.paintIcon(c, g);
        // A cleared radio button shrinks its dot away; a selected one grows it in paintCheckmark
        if (!isSelected(c) && c instanceof JComponent component) {
            float dot = TokenMotion.value(component, TokenCheckBoxIcon.MARK, false, TokenMotion.fast());
            if (dot > 0) {
                g.setColor(getCheckmarkColor(c, false));
                dot(g, dot);
            }
        }
    }

    @Override
    protected void paintCheckmark(Component c, Graphics2D g) {
        dot(g, c instanceof JComponent component
              ? TokenMotion.value(component, TokenCheckBoxIcon.MARK, true, TokenMotion.fast()) : 1);
    }

    private void dot(Graphics2D g, float part) {
        float diameter = centerDiameter * part;
        float xy = (SIZE - diameter) / 2f;
        g.fill(new Ellipse2D.Float(xy, xy, diameter, diameter));
    }

    @Override
    protected void paintFocusBorder(Component c, Graphics2D g) {
        float width = focusWidth / getScale();
        float gap = TokenCheckBoxIcon.gap() / getScale();
        // The circle is 15 across at (0, 0), see FlatRadioButtonIcon.paintBorder
        Area ring = new Area(new Ellipse2D.Float(-width, -width, SIZE + width * 2, SIZE + width * 2));
        ring.subtract(new Area(new Ellipse2D.Float(-gap, -gap, SIZE + gap * 2, SIZE + gap * 2)));
        g.fill(ring);
    }
}
