/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;

import com.formdev.flatlaf.icons.FlatRadioButtonIcon;

/** The radio button with the design-system focus ring, a gap away from the circle; see {@link TokenCheckBoxIcon}. */
public class TokenRadioButtonIcon extends FlatRadioButtonIcon {
    /** The circle's diameter, FlatCheckBoxIcon's package-private ICON_SIZE. */
    private static final float SIZE = 15;

    @Override
    protected void paintFocusBorder(Component c, Graphics2D g) {
        float gap = TokenCheckBoxIcon.gap();
        // The circle is 15 across at (0, 0), see FlatRadioButtonIcon.paintBorder
        Area ring = new Area(new Ellipse2D.Float(-focusWidth, -focusWidth, SIZE + focusWidth * 2,
              SIZE + focusWidth * 2));
        ring.subtract(new Area(new Ellipse2D.Float(-gap, -gap, SIZE + gap * 2, SIZE + gap * 2)));
        g.fill(ring);
    }
}
