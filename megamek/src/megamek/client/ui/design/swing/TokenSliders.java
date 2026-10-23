/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.IntFunction;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;

import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.util.UIUtil;

/**
 * The design-system slider additions. Plain {@code JSlider}s get the token look from the MegaMek themes, with or
 * without ticks and labels; this class adds the readout that shows a slider's value beside it.
 */
public final class TokenSliders {
    private TokenSliders() {
    }

    /**
     * {@code slider} with its value on the right in body type, as {@code format} writes it, following the thumb. The
     * readout is as wide as the longer of the minimum and the maximum, so the track doesn't shift as the value changes.
     * The readout lines up with the track, also when the slider has ticks and labels.
     */
    public static JComponent withReadout(JSlider slider, IntFunction<String> format) {
        JLabel readout = new JLabel(format.apply(slider.getValue()), SwingConstants.TRAILING) {
            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                FontMetrics metrics = getFontMetrics(getFont());
                Insets insets = getInsets();
                int widest = Math.max(metrics.stringWidth(format.apply(slider.getMinimum())),
                      metrics.stringWidth(format.apply(slider.getMaximum())));
                size.width = Math.max(size.width, widest + insets.left + insets.right);
                return size;
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        readout.setFont(TokenFonts.font("body"));
        slider.addChangeListener(event -> readout.setText(format.apply(slider.getValue())));

        JPanel row = new JPanel(new GridBagLayout()) {
            // The slider's baseline, so a label beside the row lines up with the track and the readout
            @Override
            public int getBaseline(int width, int height) {
                Dimension sliderSize = slider.getPreferredSize();
                Dimension readoutSize = readout.getPreferredSize();
                int sliderBaseline = slider.getBaseline(sliderSize.width, sliderSize.height);
                int readoutBaseline = readout.getBaseline(readoutSize.width, readoutSize.height);
                if (sliderBaseline < 0 || readoutBaseline < 0) {
                    return -1;
                }
                int above = Math.max(sliderBaseline, readoutBaseline);
                int below = Math.max(sliderSize.height - sliderBaseline, readoutSize.height - readoutBaseline);
                Insets insets = getInsets();
                return insets.top + (height - insets.top - insets.bottom - above - below) / 2 + above;
            }

            @Override
            public BaselineResizeBehavior getBaselineResizeBehavior() {
                return BaselineResizeBehavior.CENTER_OFFSET;
            }
        };
        row.setOpaque(false);
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.BASELINE;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.weightx = 1;
        row.add(slider, cell);
        cell.fill = GridBagConstraints.NONE;
        cell.weightx = 0;
        cell.insets = new Insets(0, UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space().get("md"))), 0, 0);
        row.add(readout, cell);
        return row;
    }
}
