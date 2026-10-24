/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Graphics2D;

import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.util.UIUtil;

/**
 * Paints the frame that dialog cards, status cards and panels share (docs/design-system/tokens.md, Panels): a surface,
 * a 1px edge and corner ticks, the edge along the top and bottom only when there are ticks (a paper roll). Every part is
 * optional, so the plain frame and the focused panel use the same code.
 */
final class TokenFrame {
    private TokenFrame() {
    }

    /** The {@code border} token, scaled; at least 1 pixel. */
    static int line() {
        return scaled("border");
    }

    /** The {@code tick.width} token, scaled; at least 1 pixel. */
    static int tickWidth() {
        return scaled("tick.width");
    }

    /** The {@code tick.panel} token, scaled. */
    static int tickLength() {
        return UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().size().get("tick.panel")));
    }

    private static int scaled(String size) {
        return Math.max(1, UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().size().get(size))));
    }

    /** The card frame of the active theme: the panel surface, a subtle edge and strong ticks. */
    static void paintCard(Graphics2D g, int w, int h) {
        paint(g, w, h, TokenDialogPane.token("surface.panel"), TokenDialogPane.token("border.subtle"), line(),
              TokenDialogPane.token("border.strong"));
    }

    /**
     * Paints a frame over {@code 0, 0, w, h}: {@code fill}, then an {@code edgeWidth} wide edge inside the bounds, then
     * the ticks at the four corners. A null color skips its part. A ticked frame is a paper roll: its edge runs along
     * the top and bottom only, and the ticks close the sides.
     */
    static void paint(Graphics2D g, int w, int h, Color fill, Color edge, int edgeWidth, Color ticks) {
        if (fill != null) {
            g.setColor(fill);
            g.fillRect(0, 0, w, h);
        }
        edge(g, w, h, edge, edgeWidth, ticks == null);
        if (ticks != null) {
            int stroke = tickWidth();
            int length = Math.min(tickLength(), Math.min(w, h) / 2);
            g.setColor(ticks);
            g.fillRect(0, 0, length, stroke);
            g.fillRect(0, 0, stroke, length);
            g.fillRect(w - length, 0, length, stroke);
            g.fillRect(w - stroke, 0, stroke, length);
            g.fillRect(0, h - stroke, length, stroke);
            g.fillRect(0, h - length, stroke, length);
            g.fillRect(w - length, h - stroke, length, stroke);
            g.fillRect(w - stroke, h - length, stroke, length);
        }
    }

    /** An {@code width} wide edge inside {@code 0, 0, w, h}: top and bottom, and the sides unless it's a paper roll. */
    static void edge(Graphics2D g, int w, int h, Color edge, int width, boolean sides) {
        if (edge == null) {
            return;
        }
        g.setColor(edge);
        g.fillRect(0, 0, w, width);
        g.fillRect(0, h - width, w, width);
        if (sides) {
            g.fillRect(0, width, width, h - 2 * width);
            g.fillRect(w - width, width, width, h - 2 * width);
        }
    }
}
