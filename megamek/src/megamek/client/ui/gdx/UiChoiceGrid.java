/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;

/**
 * Compact, equal-size choice cards. The host supplies the usable viewport through {@link #fit}; the grid chooses
 * its columns from both that space and its content. Cards can grow by up to 50% when room permits. Small sets use a
 * compact rectangle; a short viewport gains columns before needing vertical scrolling. Resizing never recreates
 * or reparents cards, so selection, focus and asynchronously loaded previews remain intact.
 */
public final class UiChoiceGrid extends WidgetGroup {
    private static final float MAX_SCALE = 1.5f;
    private final float cardWidth, gap;
    private float availableWidth, availableHeight = Float.MAX_VALUE;

    public UiChoiceGrid(float cardWidth, float gap) {
        if (cardWidth <= 0 || gap < 0) { throw new IllegalArgumentException("Invalid choice card dimensions"); }
        this.cardWidth = cardWidth; this.gap = gap; availableWidth = cardWidth;
        setTransform(false); setTouchable(Touchable.childrenOnly);
    }

    /** Available space excludes the host's header, footer and padding. Put the grid in a scroll pane for overflow. */
    public UiChoiceGrid fit(float width, float height) {
        width = Math.max(1, width); height = Math.max(1, height);
        if (width != availableWidth || height != availableHeight) {
            availableWidth = width; availableHeight = height; invalidateHierarchy();
        }
        return this;
    }

    private float cardHeight() {
        float height = 1;
        for (Actor actor : getChildren()) {
            height = Math.max(height, actor instanceof Layout layout ? layout.getPrefHeight() : actor.getHeight());
        }
        return height;
    }

    private int columns() {
        int count = getChildren().size;
        if (count == 0) { return 1; }
        int across = Math.max(1, (int) ((availableWidth + gap) / (cardWidth + gap)));
        int down = Math.max(1, (int) Math.min(count, (availableHeight + gap) / (cardHeight() + gap)));
        int compact = count <= 3 ? count : (int) Math.ceil(Math.sqrt(count));
        int toFitHeight = (count + down - 1) / down;
        return Math.min(count, Math.min(across, Math.max(compact, toFitHeight)));
    }

    private float scale() {
        int count = getChildren().size;
        if (count == 0) { return 1; }
        // A plain Actor has no preferred size independent of its last layout; keep such cards at their base size.
        for (Actor actor : getChildren()) { if (!(actor instanceof Layout)) { return 1; } }
        int columns = columns(), rows = (count + columns - 1) / columns;
        float across = (availableWidth - (columns - 1) * gap) / columns / cardWidth;
        float down = (availableHeight - (rows - 1) * gap) / rows / cardHeight();
        return Math.max(1, Math.min(MAX_SCALE, Math.min(across, down)));
    }

    @Override public float getPrefWidth() {
        return getChildren().isEmpty() ? 0 : columns() * Math.min(cardWidth, availableWidth) * scale() + (columns() - 1) * gap;
    }

    @Override public float getPrefHeight() {
        int rows = (getChildren().size + columns() - 1) / columns();
        return rows == 0 ? 0 : rows * cardHeight() * scale() + (rows - 1) * gap;
    }

    @Override public float getMinWidth() { return Math.min(cardWidth, availableWidth); }
    @Override public float getMinHeight() { return 0; }

    @Override public void layout() {
        int columns = columns();
        float scale = scale();
        float width = Math.min(cardWidth, availableWidth) * scale, height = cardHeight() * scale;
        float left = Math.max(0, (getWidth() - getPrefWidth()) / 2);
        for (int i = 0; i < getChildren().size; i++) {
            Actor actor = getChildren().get(i);
            actor.setBounds(left + i % columns * (width + gap), getHeight() - height - i / columns * (height + gap), width, height);
            if (actor instanceof Layout layout) { layout.validate(); }
        }
    }
}
