/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

/**
 * The sizing of the dossier's AS card picture: MegaMek's own card drawing, rendered at the pixels it takes on screen so
 * it shows crisp, and drawn again only when that size changes.
 */
public final class RequisitionCardImage {
    /** The widest card drawn, three times the card's own 1050 pixels. */
    public static final int MAX_PIXELS = 3150;

    private RequisitionCardImage() { }

    /**
     * The pixels a card {@code width} stage units wide takes at {@code pixelScale} pixels per unit; 0 when it takes
     * none, at most {@link #MAX_PIXELS}.
     */
    public static int pixels(float width, float pixelScale) {
        if (!(width > 0) || !(pixelScale > 0)) {
            return 0;
        }
        return (int) Math.min(MAX_PIXELS, Math.max(1, Math.round(width * pixelScale)));
    }

    /**
     * Whether a card {@code wanted} pixels wide should be drawn when one {@code drawn} pixels wide shows and one
     * {@code asked} pixels wide is being drawn (0 for none).
     */
    public static boolean redraw(int drawn, int asked, int wanted) {
        return (wanted > 0) && (wanted != drawn) && (wanted != asked);
    }
}
