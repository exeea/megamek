/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.util;

import java.awt.Rectangle;
import java.util.List;

/**
 * Brings a restored window back onto a monitor the user can see. Works on plain rectangles, so Swing windows (in AWT
 * coordinates) and the GPU board window (in GLFW screen coordinates) share the same rule.
 * <p>
 * A window is left where it is while enough of its title bar lies on some monitor's work area to grab and drag it.
 * Otherwise it moves onto the monitor it overlaps most, or the nearest one when it overlaps none, and shrinks to fit
 * that monitor. A window larger than the monitor it sits on also shrinks to fit.
 * </p>
 */
public final class ScreenFit {
    /** Height of the strip along the top of a window treated as its title bar. */
    static final int TITLE_BAR = 30;
    /** How much of the title bar must be on screen, across, for the window to count as reachable. */
    static final int GRAB_WIDTH = 100;

    private ScreenFit() { }

    /**
     * @param window    the window's saved bounds
     * @param workAreas each monitor's usable area, the taskbar and docks excluded
     *
     * @return bounds that are reachable on one of the work areas; the window itself when {@code workAreas} is empty
     */
    public static Rectangle fit(Rectangle window, List<Rectangle> workAreas) {
        if (workAreas.isEmpty()) {
            return new Rectangle(window);
        }
        Rectangle titleBar = new Rectangle(window.x, window.y, window.width, Math.min(TITLE_BAR, window.height));
        int grab = Math.min(GRAB_WIDTH, window.width);
        Rectangle home = null;
        for (Rectangle area : workAreas) {
            Rectangle shown = area.intersection(titleBar);
            if (!shown.isEmpty() && shown.width >= grab) {
                home = area;
                break;
            }
        }
        if (home != null && window.width <= home.width && window.height <= home.height) {
            return new Rectangle(window);
        }
        Rectangle target = home != null ? home : closest(window, workAreas);
        int width = Math.min(window.width, target.width);
        int height = Math.min(window.height, target.height);
        int x = Math.max(target.x, Math.min(window.x, target.x + target.width - width));
        int y = Math.max(target.y, Math.min(window.y, target.y + target.height - height));
        return new Rectangle(x, y, width, height);
    }

    /** The work area the window overlaps most, or, when it overlaps none, the one whose centre is nearest. */
    private static Rectangle closest(Rectangle window, List<Rectangle> workAreas) {
        Rectangle best = null;
        long bestOverlap = 0;
        for (Rectangle area : workAreas) {
            Rectangle overlap = area.intersection(window);
            long size = overlap.isEmpty() ? 0 : (long) overlap.width * overlap.height;
            if (size > bestOverlap) {
                bestOverlap = size;
                best = area;
            }
        }
        if (best != null) {
            return best;
        }
        double nearest = Double.MAX_VALUE;
        for (Rectangle area : workAreas) {
            double distance = Math.hypot(area.getCenterX() - window.getCenterX(), area.getCenterY() - window.getCenterY());
            if (distance < nearest) {
                nearest = distance;
                best = area;
            }
        }
        return best;
    }
}
