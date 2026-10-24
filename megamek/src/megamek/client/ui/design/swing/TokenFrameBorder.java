/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.border.AbstractBorder;

/**
 * The panel frame as a border, for an existing container that keeps its own layout (docs/design-system/tokens.md,
 * Panels): the panel surface, a 1px edge and corner ticks, with the panel's padding as insets. A plain frame has the
 * edge only.
 * <p>
 * A border paints after its component, so the frame's surface would cover anything the component paints itself.
 * {@link #apply} makes the container transparent and lets the border paint the surface under its children; use it
 * only on containers that paint nothing of their own. {@link TokenPanel} adds the header, status and selection.
 */
public final class TokenFrameBorder extends AbstractBorder {
    private final TokenPanel.Padding padding;
    private final boolean ticked;

    private TokenFrameBorder(TokenPanel.Padding padding, boolean ticked) {
        this.padding = padding == null ? TokenPanel.Padding.REGULAR : padding;
        this.ticked = ticked;
    }

    /** The panel frame: surface, edge and ticks. */
    public static TokenFrameBorder panel(TokenPanel.Padding padding) {
        return new TokenFrameBorder(padding, true);
    }

    /** The plain frame: the edge alone, for a group inside a panel. */
    public static TokenFrameBorder plain(TokenPanel.Padding padding) {
        return new TokenFrameBorder(padding, false);
    }

    /** Frames {@code container} with {@code border}, making it transparent so the border's surface shows. */
    public static <T extends JComponent> T apply(T container, TokenFrameBorder border) {
        container.setOpaque(false);
        container.setBorder(border);
        return container;
    }

    @Override
    public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            boolean enabled = component.isEnabled();
            TokenFrame.paint(g, width, height, ticked ? TokenDialogPane.token("surface.panel") : null,
                  TokenDialogPane.token("border.subtle"), TokenFrame.line(),
                  ticked ? TokenDialogPane.token(enabled ? "border.strong" : "border.subtle") : null);
        } finally {
            g.dispose();
        }
    }

    @Override
    public Insets getBorderInsets(Component component, Insets insets) {
        int space = padding.pixels();
        insets.set(space, space, space, space);
        return insets;
    }

    @Override
    public boolean isBorderOpaque() {
        return ticked;
    }
}
