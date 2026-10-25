/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.UIManager;

import com.formdev.flatlaf.icons.FlatCheckBoxIcon;
import com.formdev.flatlaf.ui.FlatUIUtils;
import megamek.client.ui.design.DesignTokens;

/**
 * The check box at the design-system size ({@code icon.sm}), with the design-system focus ring: a {@code focus.width}
 * ring a {@code focus.gap} away from the box, so the box keeps its checked or clear look while focused. FlatLaf's own
 * ring touches the box and merges with a checked box of the same color. {@code CheckBox.icon.focusWidth} must be the
 * ring plus the gap. The box eases between states and the mark draws itself in ({@link TokenMotion}). A disabled box
 * that is checked keeps its mark in {@link #DISABLED_SELECTED_CHECKMARK_COLOR} on the faint inverse fill, so it still
 * reads as on; FlatLaf has one disabled mark color for both.
 */
public class TokenCheckBoxIcon extends FlatCheckBoxIcon {
    /** FlatCheckBoxIcon's box edge, in its own units. */
    private static final float BOX = 14;
    static final String EDGE = "check.edge";
    static final String FILL = "check.fill";
    static final String MARK = "check.mark";
    /** The mark of a disabled check box, radio button or switch that is on. */
    static final String DISABLED_SELECTED_CHECKMARK_COLOR = "CheckBox.icon.disabledSelectedCheckmarkColor";
    // FlatCheckBoxIcon.paintCheckmark's path
    private static final float[] MARK_X = { 4.5f, 6.6f, 11.25f };
    private static final float[] MARK_Y = { 7.5f, 10f, 3.5f };
    private static final Path2D CHECKMARK = drawn(1);

    public TokenCheckBoxIcon() {
        setScale(size() / BOX);
    }

    @Override
    protected Color getBorderColor(Component c, boolean selected, boolean indeterminate) {
        return ease(c, EDGE, super.getBorderColor(c, selected, indeterminate));
    }

    @Override
    protected Color getBackground(Component c, boolean selected, boolean indeterminate) {
        Color base = selected ? selectedBackground : background;
        Color target = super.getBackground(c, selected, indeterminate);
        return ease(c, FILL, target == null || base == null ? target : FlatUIUtils.deriveColor(target, base));
    }

    @Override
    protected Color getCheckmarkColor(Component c, boolean indeterminate) {
        return disabledSelected(c, indeterminate || isSelected(c), super.getCheckmarkColor(c, indeterminate));
    }

    /** The mark of a disabled indicator that is on, or {@code color}. */
    static Color disabledSelected(Component c, boolean on, Color color) {
        Color mark = on && !c.isEnabled() ? UIManager.getColor(DISABLED_SELECTED_CHECKMARK_COLOR) : null;
        return mark == null ? color : mark;
    }

    @Override
    protected void paintIcon(Component c, Graphics2D g) {
        super.paintIcon(c, g);
        // A cleared box fades its mark out; a checked one draws it in paintCheckmark
        if (!isSelected(c) && !isIndeterminate(c) && c instanceof JComponent component) {
            float mark = TokenMotion.value(component, MARK, false, TokenMotion.fast());
            if (mark > 0) {
                Color color = getCheckmarkColor(c, false);
                g.setColor(TokenMotion.mix(new Color(color.getRed(), color.getGreen(), color.getBlue(), 0), color,
                      mark));
                g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(CHECKMARK);
            }
        }
    }

    @Override
    protected void paintCheckmark(Component c, Graphics2D g) {
        float mark = c instanceof JComponent component ? TokenMotion.value(component, MARK, true, TokenMotion.fast())
              : 1;
        g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(mark >= 1 ? CHECKMARK : drawn(mark));
    }

    /** The check mark drawn {@code part} of the way along, from its short stroke to the tip of the long one. */
    private static Path2D drawn(float part) {
        float first = (float) Point2D.distance(MARK_X[0], MARK_Y[0], MARK_X[1], MARK_Y[1]);
        float second = (float) Point2D.distance(MARK_X[1], MARK_Y[1], MARK_X[2], MARK_Y[2]);
        float length = Math.max(0.01f, part) * (first + second);
        Path2D.Float path = new Path2D.Float();
        path.moveTo(MARK_X[0], MARK_Y[0]);
        if (length <= first) {
            float t = length / first;
            path.lineTo(MARK_X[0] + (MARK_X[1] - MARK_X[0]) * t, MARK_Y[0] + (MARK_Y[1] - MARK_Y[0]) * t);
        } else {
            float t = (length - first) / second;
            path.lineTo(MARK_X[1], MARK_Y[1]);
            path.lineTo(MARK_X[1] + (MARK_X[2] - MARK_X[1]) * t, MARK_Y[1] + (MARK_Y[2] - MARK_Y[1]) * t);
        }
        return path;
    }

    /** Eases a state color of the indicator ({@link TokenMotion}): a press shows at once, the rest fades. */
    static Color ease(Component c, String key, Color target) {
        if (!(c instanceof AbstractButton button)) {
            return target;
        }
        return TokenMotion.color(button, key, target, TokenMotion.buttonState(button));
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
