/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.swing.JComponent;

/**
 * The surface wash under the pointer in a list, tree or table, eased per row ({@link TokenMotion}): the row the pointer
 * enters fades in fast while the one it left fades out more slowly, so a sweep across rows leaves a short trail.
 */
final class RowWash {
    private static final String KEY = "row.wash.";

    private final JComponent owner;
    private final Set<Integer> fading = new HashSet<>();
    private int row = -1;

    RowWash(JComponent owner) {
        this.owner = owner;
    }

    /** Moves the wash to {@code next}, or off every row for -1. */
    void moveTo(int next) {
        if (next == row) {
            return;
        }
        settle();
        if (row >= 0) {
            fading.add(row);
            TokenMotion.color(owner, KEY + row, null, TokenMotion.hover(false));
        }
        row = next;
        if (row >= 0) {
            fading.remove(row);
            // Seeds a row the pointer has not been over yet as clear, so it fades in instead of appearing
            TokenMotion.color(owner, KEY + row, null, 0);
        }
    }

    /** Forgets every row, as when the UI is uninstalled. */
    void clear() {
        for (int faded : fading) {
            TokenMotion.forget(owner, KEY + faded);
        }
        fading.clear();
        if (row >= 0) {
            TokenMotion.forget(owner, KEY + row);
        }
        row = -1;
    }

    /**
     * The wash to paint on {@code index}, or null for none. The gallery's {@code frozen} row, or -1, shows the full
     * {@code color} at once.
     */
    Color color(int index, int frozen, Color color) {
        if (index == frozen) {
            return color;
        }
        if (index == row) {
            return TokenMotion.color(owner, KEY + index, color, TokenMotion.hover(true));
        }
        if (fading.contains(index)) {
            return TokenMotion.color(owner, KEY + index, null, TokenMotion.hover(false));
        }
        return null;
    }

    /** Drops the rows that have finished fading, including any scrolled out of sight before they finished. */
    private void settle() {
        Iterator<Integer> rows = fading.iterator();
        while (rows.hasNext()) {
            String key = KEY + rows.next();
            if (!TokenMotion.isMoving(owner, key)) {
                TokenMotion.forget(owner, key);
                rows.remove();
            }
        }
    }
}
