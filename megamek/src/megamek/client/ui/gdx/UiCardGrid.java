/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;

/**
 * Equal cards in a fixed number of columns that fill the grid's width, scrolling vertically inside a
 * {@link ScrollPane}. Only the visible rows and one row on each side are shown; a card is created when it first
 * scrolls into view and hidden, not removed, when it leaves, so its preview is not built again when it comes back
 * soon. Cards more than {@link #KEEP_ROWS} rows away from the shown ones are removed from the stage, which releases
 * their previews; they are created again if they return. {@link #items} replaces all cards. Nothing is created or laid
 * out again while the visible rows stay the same.
 */
public final class UiCardGrid extends WidgetGroup {
    /** Rows kept, hidden, on each side of the shown ones. */
    static final int KEEP_ROWS = 12;
    private final int columns;
    private final float cardHeight, gap;
    private final Map<Integer, Actor> cards = new HashMap<>();
    private IntFunction<Actor> create;
    private int count;
    /** The created index range, first inclusive and last exclusive; -1 until the next {@link #show}. */
    private int first = -1, last = -1;

    public UiCardGrid(int columns, float cardHeight, float gap) {
        this.columns = columns; this.cardHeight = cardHeight; this.gap = gap;
        setTransform(false); setTouchable(Touchable.childrenOnly);
    }

    /** Replaces the cards: {@code create} makes card {@code index} when it first scrolls into view. */
    public void items(int count, IntFunction<Actor> create) {
        clearChildren(); cards.clear(); this.count = count; this.create = create; first = last = -1; invalidateHierarchy();
    }

    /** Follows the enclosing scroll pane: creates the rows that came into view, once per change of the visible rows. */
    @Override public void act(float delta) {
        super.act(delta);
        if (getParent() instanceof ScrollPane scroll) { show(scroll.getVisualScrollY(), scroll.getScrollHeight()); }
    }

    /** Shows the rows a viewport of {@code viewportHeight} at {@code scrollY} from the top meets, plus one each side. */
    public void show(float scrollY, float viewportHeight) {
        float row = cardHeight + gap;
        int from = Math.max(0, ((int) (scrollY / row) - 1) * columns);
        int to = Math.min(count, ((int) Math.ceil((scrollY + viewportHeight) / row) + 1) * columns);
        if (from == first && to == last) { return; }
        first = from; last = to;
        int keepFrom = from - KEEP_ROWS * columns, keepTo = to + KEEP_ROWS * columns;
        cards.entrySet().removeIf(card -> {
            int index = card.getKey();
            if (index < keepFrom || index >= keepTo) { card.getValue().remove(); return true; }
            card.getValue().setVisible(index >= from && index < to); return false;
        });
        for (int index = from; index < to; index++) {
            if (!cards.containsKey(index)) { Actor actor = create.apply(index); cards.put(index, actor); addActor(actor); }
        }
        invalidate();
    }

    private int rows() { return (count + columns - 1) / columns; }

    @Override public float getPrefWidth() { return 0; }
    @Override public float getPrefHeight() { return Math.max(0, rows() * (cardHeight + gap) - gap); }
    @Override public float getMinWidth() { return 0; }
    @Override public float getMinHeight() { return 0; }

    @Override public void layout() {
        float cardWidth = Math.max(0, (getWidth() - (columns - 1) * gap) / columns);
        cards.forEach((index, actor) -> {
            int row = index / columns, column = index % columns;
            actor.setBounds(column * (cardWidth + gap), getHeight() - row * (cardHeight + gap) - cardHeight, cardWidth, cardHeight);
            if (actor instanceof Layout layout) { layout.validate(); }
        });
    }
}
