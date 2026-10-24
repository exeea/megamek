/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.LookAndFeel;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatScrollBarUI;

/**
 * Scroll bars of the MegaMek look and feels (docs/design-system/tokens.md, Scrollbars): a thin pill thumb and no track
 * of their own, so a bar shows the color of the content it scrolls instead of a stripe. The pointer anywhere over the
 * bar eases in its rail and brightens the thumb.
 */
public class TokenScrollBarUI extends FlatScrollBarUI {
    /** Client property freezing a bar in {@link #HOVER} or {@link #PRESSED}, for the design gallery. */
    static final String FROZEN_STATE = "MegaMek.scrollBar.frozenState";
    static final String HOVER = "hover";
    static final String PRESSED = "pressed";

    private static final Color NO_TRACK = new Color(0, true);
    private static final String RAIL = "scrollBar.rail";
    private static final String THUMB = "scrollBar.thumb";

    public static ComponentUI createUI(JComponent c) {
        return new TokenScrollBarUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        // The scroll pane behind paints the content color, so the bar needs no background of its own
        LookAndFeel.installProperty(scrollbar, "opaque", false);
    }

    @Override
    public void update(Graphics g, JComponent c) {
        if (c.isOpaque()) {
            g.setColor(contentBackground(c));
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
        }
        paint(g, c);
    }

    @Override
    protected Color getTrackColor(JComponent c, boolean hover, boolean pressed) {
        boolean shown = hover || c.getClientProperty(FROZEN_STATE) != null;
        Color rail = TokenMotion.color(c, RAIL, shown ? hoverTrackColor : null, TokenMotion.hover(shown));
        return rail != null ? rail : NO_TRACK;
    }

    @Override
    protected Color getThumbColor(JComponent c, boolean hover, boolean pressed) {
        Object frozen = c.getClientProperty(FROZEN_STATE);
        boolean down = pressed || PRESSED.equals(frozen);
        boolean over = hover || frozen != null;
        Color target = super.getThumbColor(c, over, down);
        return TokenMotion.color(c, THUMB, target, down ? 0 : TokenMotion.hover(over));
    }

    /**
     * The color behind a bar: the scrolled view's when the bar belongs to a scroll pane and the view paints its own
     * background, otherwise the viewport's or the bar's own.
     */
    static Color contentBackground(JComponent bar) {
        Color content = bar.getParent() instanceof JScrollPane scroll ? contentBackground(scroll) : null;
        return content != null ? content : bar.getBackground();
    }

    /** The color of what {@code scroll} shows, or null when neither the view nor the viewport paints a background. */
    static Color contentBackground(JScrollPane scroll) {
        JViewport viewport = scroll.getViewport();
        if (viewport == null) {
            return null;
        }
        Component view = viewport.getView();
        if (view != null && view.isOpaque()) {
            return view.getBackground();
        }
        return viewport.isOpaque() ? viewport.getBackground() : null;
    }
}
