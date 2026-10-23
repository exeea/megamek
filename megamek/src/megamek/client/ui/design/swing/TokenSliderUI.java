/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.UIResource;

import com.formdev.flatlaf.ui.FlatSliderUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.HiDPIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * Sliders of the MegaMek look and feels (docs/design-system/tokens.md, Sliders): an accent fill up to a round thumb on
 * a pill track, with or without ticks and labels. The pointer anywhere over the slider gives the thumb its hover look,
 * because a click on the track moves the thumb there; keyboard focus rings the thumb a gap away, as on check boxes.
 */
public class TokenSliderUI extends FlatSliderUI {
    /** Client property freezing a slider in {@link #HOVER} or {@link #PRESSED}, for the design gallery. */
    static final String FROZEN_STATE = "MegaMek.slider.frozenState";
    static final String HOVER = "hover";
    static final String PRESSED = "pressed";

    private Color hoverThumbBorderColor;
    private Color disabledTickColor;
    private int ringWidth;
    private int tickLength;
    private int tickWidth;

    public static ComponentUI createUI(JComponent c) {
        return new TokenSliderUI();
    }

    @Override
    protected void installDefaults(JSlider slider) {
        super.installDefaults(slider);
        // Labels take the slider's font: small type, which FlatLaf's font syntax can't give (it takes whole sizes)
        if (slider.getFont() == null || slider.getFont() instanceof UIResource) {
            slider.setFont(new FontUIResource(TokenFonts.font("small")));
        }
        hoverThumbBorderColor = UIManager.getColor("Slider.hoverThumbBorderColor");
        disabledTickColor = UIManager.getColor("Slider.disabledTickColor");
        ringWidth = UIManager.getInt("Slider.focusRingWidth");
        tickLength = UIManager.getInt("Slider.tickLength");
        tickWidth = UIManager.getInt("Slider.tickWidth");
    }

    @Override
    protected TrackListener createTrackListener(JSlider slider) {
        return new FlatTrackListener() {
            @Override
            protected boolean isOverThumb(MouseEvent e) {
                return e != null && TokenSliderUI.this.slider.isEnabled()
                      && TokenSliderUI.this.slider.contains(e.getPoint());
            }
        };
    }

    /** Ticks and labels keep the round thumb and the value fill, so every slider reads the same way. */
    @Override
    protected boolean isRoundThumb() {
        return true;
    }

    @Override
    protected int getTickLength() {
        return tickLength > 0 ? UIScale.scale(tickLength) : super.getTickLength();
    }

    @Override
    public void paintTicks(Graphics g) {
        Color enabledTickColor = tickColor;
        if (!slider.isEnabled() && disabledTickColor != null) {
            tickColor = disabledTickColor;
        }
        try {
            super.paintTicks(g);
        } finally {
            tickColor = enabledTickColor;
        }
    }

    @Override
    protected void paintMinorTickForHorizSlider(Graphics g, Rectangle tickBounds, int x) {
        tick(g, x, false, true);
    }

    @Override
    protected void paintMajorTickForHorizSlider(Graphics g, Rectangle tickBounds, int x) {
        tick(g, x, true, true);
    }

    @Override
    protected void paintMinorTickForVertSlider(Graphics g, Rectangle tickBounds, int y) {
        tick(g, y, false, false);
    }

    @Override
    protected void paintMajorTickForVertSlider(Graphics g, Rectangle tickBounds, int y) {
        tick(g, y, true, false);
    }

    /** A pill tick at {@code at} along the track; minor ticks are half as long. */
    private void tick(Graphics g, int at, boolean major, boolean horizontal) {
        float width = UIScale.scale((float) Math.max(1, tickWidth));
        float length = getTickLength() * (major ? 1f : 0.5f);
        ((Graphics2D) g).fill(horizontal
              ? new RoundRectangle2D.Float(at - width / 2f, 0, width, length, width, width)
              : new RoundRectangle2D.Float(0, at - width / 2f, length, width, width, width));
    }

    @Override
    public void paintThumb(Graphics g) {
        Object frozen = slider.getClientProperty(FROZEN_STATE);
        boolean enabled = slider.isEnabled();
        boolean pressed = enabled && (thumbPressed || PRESSED.equals(frozen));
        boolean hover = enabled && (thumbHover || frozen != null);
        Color face = !enabled ? disabledThumbColor
              : pressed ? pressedThumbColor : hover ? hoverThumbColor : thumbColor;
        Color edge = !enabled ? disabledThumbBorderColor
              : (pressed || hover) && hoverThumbBorderColor != null ? hoverThumbBorderColor : thumbBorderColor;
        boolean focused = FlatUIUtils.isPermanentFocusOwner(slider);
        // At fractional scales the disc is painted at 1x, as FlatLaf does, so its right and bottom edges aren't clipped
        Graphics2D copy = (Graphics2D) g.create();
        try {
            HiDPIUtils.paintAtScale1x(copy, thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height,
                  (g2, x, y, width, height, scale) -> paintDisc(g2, x, y, width, height, scale, face, edge, focused));
        } finally {
            copy.dispose();
        }
    }

    /** The thumb centred in its rectangle, the focus room around it holding the ring when focused. */
    private void paintDisc(Graphics2D g, int x, int y, int width, int height, double scale, Color face, Color edge,
          boolean focused) {
        float room = (float) (UIScale.scale((float) focusWidth) * scale);
        float outer = Math.min(width, height);
        float diameter = outer - 2 * room;
        float centerX = x + width / 2f;
        float centerY = y + height / 2f;
        if (focused) {
            float ring = (float) (UIScale.scale((float) ringWidth) * scale);
            float ringDiameter = outer - ring;
            g.setColor(focusedColor);
            g.setStroke(new BasicStroke(ring));
            g.draw(disc(centerX, centerY, ringDiameter));
        }
        g.setColor(edge != null ? edge : face);
        g.fill(disc(centerX, centerY, diameter));
        float border = (float) (UIScale.scale(thumbBorderWidth) * scale);
        g.setColor(face);
        g.fill(disc(centerX, centerY, diameter - 2 * border));
    }

    private static Ellipse2D disc(float centerX, float centerY, float diameter) {
        return new Ellipse2D.Float(centerX - diameter / 2f, centerY - diameter / 2f, diameter, diameter);
    }
}
