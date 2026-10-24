/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.font.TextLayout;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;
import java.math.RoundingMode;
import java.text.NumberFormat;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.UIResource;

import com.formdev.flatlaf.ui.FlatProgressBarUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import megamek.client.ui.design.DesignTokens;

/**
 * Progress bars of the MegaMek look and feels (docs/design-system/tokens.md, Progress bars): a thin accent pill on the
 * slider's track, with the bar's text above it in small secondary type rather than inside, so long status messages
 * stay readable. With {@link #SHOW_PERCENT} set, a determinate bar also shows its share done at the trailing end of the
 * text line, in primary type. An indeterminate bar sends a segment along the track from start to end, over and over.
 * Vertical bars keep FlatLaf's drawing.
 */
public class TokenProgressBarUI extends FlatProgressBarUI {
    static final String TEXT_FOREGROUND = "MegaMek.progressBar.textForeground";
    static final String DISABLED_TRACK = "MegaMek.progressBar.disabledTrack";
    static final String TEXT_GAP = "MegaMek.progressBar.textGap";
    static final String VALUE_FOREGROUND = "MegaMek.progressBar.valueForeground";
    /**
     * Client property: {@code Boolean.TRUE} shows the share done as a percentage at the trailing end of the text line
     * while the bar is determinate. The line keeps its height while the bar is indeterminate, so nothing moves.
     */
    public static final String SHOW_PERCENT = "MegaMek.progressBar.showPercent";
    /** The indeterminate segment's share of the track. */
    private static final float SEGMENT = 0.3f;

    private Color textForeground;
    private Color disabledTrack;
    private int textGap;
    private Color valueForeground;
    private PropertyChangeListener percentListener;

    public static ComponentUI createUI(JComponent c) {
        return new TokenProgressBarUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        // FlatLaf's font syntax takes whole sizes only, and small is 11.5
        if (progressBar.getFont() == null || progressBar.getFont() instanceof UIResource) {
            progressBar.setFont(new FontUIResource(TokenFonts.font("small")));
        }
        textForeground = UIManager.getColor(TEXT_FOREGROUND);
        disabledTrack = UIManager.getColor(DISABLED_TRACK);
        textGap = UIManager.getInt(TEXT_GAP);
        valueForeground = UIManager.getColor(VALUE_FOREGROUND);
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        percentListener = event -> {
            if (SHOW_PERCENT.equals(event.getPropertyName())) {
                progressBar.revalidate();
                progressBar.repaint();
            }
        };
        progressBar.addPropertyChangeListener(percentListener);
    }

    @Override
    protected void uninstallListeners() {
        progressBar.removePropertyChangeListener(percentListener);
        percentListener = null;
        super.uninstallListeners();
    }

    private boolean percentLine() {
        return Boolean.TRUE.equals(progressBar.getClientProperty(SHOW_PERCENT));
    }

    /** The percentage to show, or null: only for a determinate bar with {@link #SHOW_PERCENT}. Rounds down. */
    String percentText() {
        if (!percentLine() || progressBar.isIndeterminate()) {
            return null;
        }
        NumberFormat format = NumberFormat.getPercentInstance(progressBar.getLocale());
        format.setRoundingMode(RoundingMode.FLOOR);
        format.setMaximumFractionDigits(0);
        return format.format(progressBar.getPercentComplete());
    }

    /** The widest percentage, so the text before it does not move as it counts. */
    private float percentWidth(FontMetrics metrics) {
        if (percentText() == null) {
            return 0;
        }
        NumberFormat format = NumberFormat.getPercentInstance(progressBar.getLocale());
        return advance(format.format(1), metrics);
    }

    private boolean horizontal() {
        return progressBar.getOrientation() == JProgressBar.HORIZONTAL;
    }

    private int trackHeight() {
        return UIScale.scale(horizontalSize.height);
    }

    /** The height of the text line above the track and its gap, 0 without text. */
    private int textBlock(FontMetrics metrics) {
        return progressBar.isStringPainted() || percentLine() ? metrics.getHeight() + UIScale.scale(textGap) : 0;
    }

    private int valueGap() {
        return UIScale.scale(Math.round(DesignTokens.shipped().space().get("md")));
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        if (!horizontal()) {
            return super.getPreferredSize(c);
        }
        Insets insets = progressBar.getInsets();
        FontMetrics metrics = progressBar.getFontMetrics(progressBar.getFont());
        int width = UIScale.scale(horizontalSize.width);
        float line = progressBar.isStringPainted() ? advance(progressBar.getString(), metrics) : 0;
        float percent = percentWidth(metrics);
        if (percent > 0) {
            line += (line > 0 ? valueGap() : 0) + percent;
        }
        width = Math.max(width, (int) Math.ceil(line));
        return new Dimension(width + insets.left + insets.right,
              textBlock(metrics) + trackHeight() + insets.top + insets.bottom);
    }

    @Override
    public Dimension getMinimumSize(JComponent c) {
        if (!horizontal()) {
            return super.getMinimumSize(c);
        }
        Dimension size = getPreferredSize(c);
        Insets insets = progressBar.getInsets();
        size.width = UIScale.scale(10) + insets.left + insets.right;
        return size;
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        if (!horizontal()) {
            super.paint(g, c);
            return;
        }
        Insets insets = progressBar.getInsets();
        int x = insets.left;
        int width = progressBar.getWidth() - insets.left - insets.right;
        int height = progressBar.getHeight() - insets.top - insets.bottom;
        if (width <= 0 || height <= 0) {
            return;
        }
        FontMetrics metrics = progressBar.getFontMetrics(progressBar.getFont());
        int track = trackHeight();
        int block = textBlock(metrics) + track;
        int top = insets.top + Math.max(0, (height - block) / 2);
        int trackY = top + block - track;
        boolean enabled = progressBar.isEnabled();

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            FlatUIUtils.setRenderingHints(g2);
            Color trackColor = !enabled && disabledTrack != null ? disabledTrack : progressBar.getBackground();
            Color fill = enabled ? progressBar.getForeground() : UIManager.getColor("Label.disabledForeground");
            g2.setColor(trackColor);
            g2.fill(pill(x, trackY, width, track));
            g2.setColor(fill);
            boolean leftToRight = progressBar.getComponentOrientation().isLeftToRight();
            if (progressBar.isIndeterminate()) {
                paintSegment(g2, x, trackY, width, track, leftToRight);
            } else {
                float amount = (float) (width * progressBar.getPercentComplete());
                if (amount > 0) {
                    float start = leftToRight ? x : x + width - amount;
                    g2.fill(pill(start, trackY, Math.max(amount, track), track));
                }
            }
            String percent = percentText();
            int textWidth = width;
            if (percent != null) {
                float percentWidth = advance(percent, metrics);
                Color color = enabled ? valueForeground : UIManager.getColor("Label.disabledForeground");
                g2.setColor(color != null ? color : progressBar.getForeground());
                g2.setFont(progressBar.getFont());
                int percentX = leftToRight ? x + width - (int) Math.ceil(percentWidth) : x;
                FlatUIUtils.drawString(progressBar, g2, percent, percentX, top + metrics.getAscent());
                textWidth = Math.max(0, width - (int) Math.ceil(percentWidth(metrics)) - valueGap());
            }
            if (progressBar.isStringPainted()) {
                int textX = leftToRight ? x : x + width - textWidth;
                paintText(g2, metrics, textX, top, textWidth, leftToRight, enabled);
            }
        } finally {
            g2.dispose();
        }
    }

    /** The indeterminate segment: it enters at the start, crosses the track and leaves at the end, clipped to it. */
    private void paintSegment(Graphics2D g, int x, int y, int width, int height, boolean leftToRight) {
        int frames = getFrameCount();
        float fraction = frames > 0 ? (float) getAnimationIndex() / frames : 0;
        float segment = Math.max(width * SEGMENT, height);
        float from = -segment + fraction * (width + segment);
        float start = Math.max(0, from);
        float end = Math.min(width, from + segment);
        if (end - start <= 0) {
            return;
        }
        float left = leftToRight ? x + start : x + width - end;
        g.fill(pill(left, y, Math.max(end - start, height), height));
    }

    private void paintText(Graphics2D g, FontMetrics metrics, int x, int top, int width, boolean leftToRight,
          boolean enabled) {
        String text = progressBar.getString();
        if (text == null || text.isEmpty()) {
            return;
        }
        Color color = enabled ? textForeground : UIManager.getColor("Label.disabledForeground");
        g.setColor(color != null ? color : progressBar.getForeground());
        g.setFont(progressBar.getFont());
        int textX = leftToRight ? x : x + width - (int) Math.ceil(advance(text, metrics));
        g.clipRect(x, top, width, metrics.getHeight());
        FlatUIUtils.drawString(progressBar, g, text, textX, top + metrics.getAscent());
    }

    /** The text's width including the font's tracking, which {@link FontMetrics#stringWidth} leaves out. */
    private static float advance(String text, FontMetrics metrics) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return new TextLayout(text, metrics.getFont(), metrics.getFontRenderContext()).getAdvance();
    }

    private static RoundRectangle2D pill(float x, float y, float width, float height) {
        return new RoundRectangle2D.Float(x, y, width, height, height, height);
    }

    /** The segment moves across the whole bar, not inside FlatLaf's box, so every frame repaints all of it. */
    @Override
    protected void setAnimationIndex(int newValue) {
        super.setAnimationIndex(newValue);
        if (progressBar != null) {
            progressBar.repaint();
        }
    }
}
