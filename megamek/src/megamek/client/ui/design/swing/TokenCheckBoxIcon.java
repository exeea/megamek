/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;

import com.formdev.flatlaf.icons.FlatCheckBoxIcon;
import megamek.client.ui.design.DesignTokens;

/**
 * The check box at the design-system size ({@code icon.sm}), with the design-system focus ring: a {@code focus.width}
 * ring a {@code focus.gap} away from the box, so the box keeps its checked or clear look while focused. FlatLaf's own
 * ring touches the box and merges with a checked box of the same color. {@code CheckBox.icon.focusWidth} must be the
 * ring plus the gap.
 */
public class TokenCheckBoxIcon extends FlatCheckBoxIcon {
    /** FlatCheckBoxIcon's box edge, in its own units. */
    private static final float BOX = 14;

    public TokenCheckBoxIcon() {
        setScale(size() / BOX);
    }

    @Override
    protected void paintFocusBorder(Component c, Graphics2D g) {
        // The graphics are scaled to the box; the ring and gap stay their token width
        float width = focusWidth / getScale();
        float gap = gap() / getScale();
        // The box is 14 square at (1, 0), see FlatCheckBoxIcon.paintBorder
        Area ring = new Area(new RoundRectangle2D.Float(1 - width, -width, BOX + width * 2, BOX + width * 2,
              arc + width * 2, arc + width * 2));
        ring.subtract(new Area(new RoundRectangle2D.Float(1 - gap, -gap, BOX + gap * 2, BOX + gap * 2, arc + gap * 2,
              arc + gap * 2)));
        g.fill(ring);
    }

    /** The space between the indicator and its focus ring, in unscaled icon units. */
    static float gap() {
        return DesignTokens.shipped().size().get("focus.gap");
    }

    /** The edge of a check box or radio button and the height of a switch, {@code icon.sm}. */
    static float size() {
        return DesignTokens.shipped().size().get("icon.sm");
    }
}
