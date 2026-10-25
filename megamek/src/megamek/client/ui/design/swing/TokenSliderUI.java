/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
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
import megamek.client.ui.design.DesignTokens.Presentation;

/**
 * Sliders of the MegaMek look and feels (docs/design-system/tokens.md, Sliders): an accent fill up to the thumb on a
 * pill track, with or without ticks and labels. The thumb follows the screen's presentation mode
 * ({@link TokenPresentation}): a round disc in app screens, a thin light bar across the track in game screens. The
 * pointer anywhere over the slider gives the thumb its hover look, because a click on the track moves the thumb there,
 * and grows it a pixel each side; keyboard focus rings the thumb a gap away, as on check boxes.
 */
public class TokenSliderUI extends FlatSliderUI {
    /** Client property freezing a slider in {@link #HOVER} or {@link #PRESSED}, for the design gallery. */
    static final String FROZEN_STATE = "MegaMek.slider.frozenState";
    static final String HOVER = "hover";
    static final String PRESSED = "pressed";

    /** How much wider the thumb grows under the pointer, before scaling: a pixel on each side. */
    private static final float GROWTH = 2;
    private static final String FACE = "slider.face";
    private static final String EDGE = "slider.edge";
    private static final String GROW = "slider.grow";

    private Color hoverThumbBorderColor;
    private Color disabledTickColor;
    private int ringWidth;
    private int tickLength;
    private int tickWidth;
    private Color barColor;
    private Color barActiveColor;
    private int barWidth;
    private int barLength;
    private int barArc;
    private int barFocusGap;

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
        barColor = UIManager.getColor("Slider.barThumbColor");
        barActiveColor = UIManager.getColor("Slider.barThumbActiveColor");
        Dimension bar = UIManager.getDimension("Slider.barThumbSize");
        barWidth = bar == null ? 4 : bar.width;
        barLength = bar == null ? 16 : bar.height;
        barArc = UIManager.getInt("Slider.barThumbArc");
        barFocusGap = UIManager.getInt("Slider.barThumbFocusGap");
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
        // Press is instant; hover eases in fast and lingers out, and the thumb grows a little into its focus room
        int duration = pressed ? 0 : TokenMotion.hover(hover);
        Color easedFace = TokenMotion.color(slider, FACE, face, duration);
        Color easedEdge = edge == null ? null : TokenMotion.color(slider, EDGE, edge, duration);
        float grow = TokenMotion.value(slider, GROW, hover || pressed, duration);
        boolean focused = FlatUIUtils.isPermanentFocusOwner(slider);
        if (TokenPresentation.mode(slider) == Presentation.GAME) {
            paintBar(g, enabled, pressed, hover, duration, focused, grow);
            return;
        }
        // At fractional scales the disc is painted at 1x, as FlatLaf does, so its right and bottom edges aren't clipped
        Graphics2D copy = (Graphics2D) g.create();
        try {
            HiDPIUtils.paintAtScale1x(copy, thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height,
                  (g2, x, y, width, height, scale) -> paintDisc(g2, x, y, width, height, scale, easedFace, easedEdge,
                        focused, grow));
        } finally {
            copy.dispose();
        }
    }

    /**
     * The thumb centred in its rectangle, the focus room around it holding the ring when focused. {@code grow}, 0 to 1,
     * widens the disc by up to {@link #GROWTH} into that room.
     */
    private void paintDisc(Graphics2D g, int x, int y, int width, int height, double scale, Color face, Color edge,
          boolean focused, float grow) {
        float room = (float) (UIScale.scale((float) focusWidth) * scale);
        float outer = Math.min(width, height);
        float diameter = outer - 2 * room + (float) (UIScale.scale(GROWTH) * scale) * grow;
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

    /**
     * The game presentation's thumb: hud-v3's flat bar across the track, xs wide and icon.sm long, in the inverse
     * color; under the pointer or dragged it leans toward the inverse ink and widens a pixel each side. The
     * whole thumb rectangle stays its hit area, and focus rings the bar a gap away.
     */
    private void paintBar(Graphics g, boolean enabled, boolean pressed, boolean hover, int duration,
          boolean focused, float grow) {
        Color face = !enabled ? disabledThumbColor : pressed || hover ? barActiveColor : barColor;
        Color easedFace = TokenMotion.color(slider, FACE, face, duration);
        boolean horizontal = slider.getOrientation() == JSlider.HORIZONTAL;
        Graphics2D copy = (Graphics2D) g.create();
        try {
            HiDPIUtils.paintAtScale1x(copy, thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height,
                  (g2, x, y, width, height, scale) -> {
                      float thickness = (float) ((UIScale.scale(barWidth) + UIScale.scale(GROWTH) * grow) * scale);
                      float length = (float) (UIScale.scale(barLength) * scale);
                      float arc = (float) (UIScale.scale(barArc) * scale) * 2;
                      float barWidthNow = horizontal ? thickness : length;
                      float barHeightNow = horizontal ? length : thickness;
                      float left = x + (width - barWidthNow) / 2f;
                      float top = y + (height - barHeightNow) / 2f;
                      if (focused) {
                          float ring = (float) (UIScale.scale((float) ringWidth) * scale);
                          float gap = (float) (UIScale.scale(barFocusGap) * scale);
                          float out = gap + ring / 2f;
                          g2.setColor(focusedColor);
                          g2.setStroke(new BasicStroke(ring));
                          g2.draw(new RoundRectangle2D.Float(left - out, top - out, barWidthNow + 2 * out,
                                barHeightNow + 2 * out, arc + 2 * out, arc + 2 * out));
                      }
                      g2.setColor(easedFace);
                      g2.fill(new RoundRectangle2D.Float(left, top, barWidthNow, barHeightNow, arc, arc));
                  });
        } finally {
            copy.dispose();
        }
    }
}
