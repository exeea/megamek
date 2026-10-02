/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;

/**
 * A paragraph of inline text runs, each in its own hud font and color, that wraps between words as a browser wraps
 * inline text (a line with bold values, such as #solution .m b, or with a colored lead, such as .assign .arm). Its view
 * appends the runs in reading order with {@link #run} and empties it with {@code clearChildren()}.
 */
public final class UiFlow extends HorizontalGroup {
    private final UiKit kit;
    private final float size;

    /**
     * An empty paragraph of {@code size}-unit text whose lines are {@code lineHeight} units apart (CSS line-height),
     * with words spaced as the hud body face spaces them.
     */
    public UiFlow(UiKit kit, float size, float lineHeight) {
        this.kit = kit;
        this.size = size;
        // A label measures its ink, so the gap between two words' ink in running text is the space plus a letter's
        // side bearings: those of a round letter, about the average of a word's first and last.
        Label probe = kit.label("o o", "hud-body", size, Color.WHITE);
        float spaced = probe.getPrefWidth();
        probe.setText("o");
        space(spaced - 2 * probe.getPrefWidth());
        // A line's half-leading above and below it, as a line box adds it.
        float leading = Math.max(0, lineHeight - probe.getPrefHeight());
        wrap();
        rowLeft();
        wrapSpace(leading);
        pad(leading / 2, 0, leading / 2, 0);
    }

    /** Appends {@code text} in hud font {@code font} and {@code color}, one label per word; returns this paragraph. */
    public UiFlow run(String text, String font, Color color) {
        for (String word : text.split(" ")) {
            if (!word.isEmpty()) {
                addActor(kit.label(word, font, size, color));
            }
        }
        return this;
    }
}
