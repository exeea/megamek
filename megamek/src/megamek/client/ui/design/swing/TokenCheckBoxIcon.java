/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;

import com.formdev.flatlaf.icons.FlatCheckBoxIcon;
import megamek.client.ui.design.DesignTokens;

/**
 * The check box with the design-system focus ring: a {@code focus.width} ring a {@code focus.gap} away from the box,
 * so the box keeps its checked or clear look while focused. FlatLaf's own ring touches the box and merges with a
 * checked box of the same color. {@code CheckBox.icon.focusWidth} must be the ring plus the gap.
 */
public class TokenCheckBoxIcon extends FlatCheckBoxIcon {
    @Override
    protected void paintFocusBorder(Component c, Graphics2D g) {
        float gap = gap();
        // The box is 14 square at (1, 0), see FlatCheckBoxIcon.paintBorder
        Area ring = new Area(new RoundRectangle2D.Float(1 - focusWidth, -focusWidth, 14 + focusWidth * 2,
              14 + focusWidth * 2, arc + focusWidth * 2, arc + focusWidth * 2));
        ring.subtract(new Area(new RoundRectangle2D.Float(1 - gap, -gap, 14 + gap * 2, 14 + gap * 2, arc + gap * 2,
              arc + gap * 2)));
        g.fill(ring);
    }

    /** The space between the indicator and its focus ring, in unscaled icon units. */
    static float gap() {
        return DesignTokens.shipped().size().get("focus.gap");
    }
}
