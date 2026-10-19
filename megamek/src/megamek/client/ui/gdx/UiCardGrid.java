/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;

/**
 * Equal cards in a fixed number of columns that fill the grid's width, scrolling vertically inside a
 * {@link ScrollPane}, in sections: a titled section starts with its header, a full-width row of its own height, and
 * every section starts a new row. Only the visible rows and one row on each side are shown; a card is created when it
 * first scrolls into view and hidden, not removed, when it leaves, so its preview is not built again when it comes back
 * soon. Cards more than {@link #KEEP_ROWS} rows away from the shown ones are removed from the stage, which releases
 * their previews; they are created again if they return. Headers stay. {@link #items} replaces all sections. Nothing is
 * created or laid out again while the visible rows stay the same.
 */
public final class UiCardGrid extends WidgetGroup {
    /** Rows kept, hidden, on each side of the shown ones. */
    static final int KEEP_ROWS = 12;

    /** A section of {@code count} cards under {@code header}, a full-width row; null for none. */
    public record Section(Actor header, int count) { }

    private final int columns;
    private final float cardHeight, headerHeight, gap;
    private final Map<Integer, Actor> cards = new HashMap<>();
    private IntFunction<Actor> create;
    private int count;
    /**
     * The rows, top to bottom: each one's top below the grid's top, its height, its header (null for a row of cards)
     * and the index of its first card, or of the next row's for a header; {@code rowCards} ends with the card count.
     */
    private float[] rowTop = new float[0], rowHeight = new float[0];
    private Actor[] rowHeader = new Actor[0];
    private int[] rowCards = { 0 };
    /** Each card's row and column. */
    private int[] cardRow = new int[0], cardColumn = new int[0];
    private float height;
    /** The created index range, first inclusive and last exclusive; -1 until the next {@link #show}. */
    private int first = -1, last = -1;

    public UiCardGrid(int columns, float cardHeight, float headerHeight, float gap) {
        this.columns = columns; this.cardHeight = cardHeight; this.headerHeight = headerHeight; this.gap = gap;
        setTransform(false); setTouchable(Touchable.childrenOnly);
    }

    /**
     * Replaces the sections: {@code create} makes card {@code index}, counted over all sections' cards in order, when
     * it first scrolls into view.
     */
    public void items(List<Section> sections, IntFunction<Actor> create) {
        clearChildren(); cards.clear(); this.create = create; first = last = -1;
        List<Float> tops = new ArrayList<>(), heights = new ArrayList<>();
        List<Actor> headers = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        count = sections.stream().mapToInt(Section::count).sum();
        cardRow = new int[count]; cardColumn = new int[count];
        float top = 0;
        int card = 0;
        for (Section section : sections) {
            if (section.header() != null) {
                tops.add(top); heights.add(headerHeight); headers.add(section.header()); starts.add(card);
                addActor(section.header());
                top += headerHeight + gap;
            }
            for (int index = 0; index < section.count(); index++, card++) {
                if (index % columns == 0) {
                    tops.add(top); heights.add(cardHeight); headers.add(null); starts.add(card);
                    top += cardHeight + gap;
                }
                cardRow[card] = tops.size() - 1; cardColumn[card] = index % columns;
            }
        }
        int rows = tops.size();
        rowTop = new float[rows]; rowHeight = new float[rows]; rowHeader = headers.toArray(new Actor[0]); rowCards = new int[rows + 1];
        for (int row = 0; row < rows; row++) { rowTop[row] = tops.get(row); rowHeight[row] = heights.get(row); rowCards[row] = starts.get(row); }
        rowCards[rows] = count;
        height = Math.max(0, top - gap);
        invalidateHierarchy();
    }

    /** Follows the enclosing scroll pane: creates the rows that came into view, once per change of the visible rows. */
    @Override public void act(float delta) {
        super.act(delta);
        if (getParent() instanceof ScrollPane scroll) { show(scroll.getVisualScrollY(), scroll.getScrollHeight()); }
    }

    /** Shows the rows a viewport of {@code viewportHeight} at {@code scrollY} from the top meets, plus one each side. */
    public void show(float scrollY, float viewportHeight) {
        int rows = rowTop.length;
        // The first row reaching below the viewport's top, and the first starting below its bottom.
        int top = Math.max(0, firstRow(scrollY, true) - 1), bottom = Math.min(rows, firstRow(scrollY + viewportHeight, false) + 1);
        int from = rowCards[top], to = rowCards[Math.max(top, bottom)];
        if (from == first && to == last) { return; }
        first = from; last = to;
        int keepFrom = rowCards[Math.max(0, top - KEEP_ROWS)], keepTo = rowCards[Math.min(rows, bottom + KEEP_ROWS)];
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

    /** By binary search: the first row whose bottom ({@code bottoms}) or top lies below {@code y}; the row count if none. */
    private int firstRow(float y, boolean bottoms) {
        int low = 0, high = rowTop.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            float edge = rowTop[middle] + (bottoms ? rowHeight[middle] : 0);
            if (edge > y) { high = middle; } else { low = middle + 1; }
        }
        return low;
    }

    @Override public float getPrefWidth() { return 0; }
    @Override public float getPrefHeight() { return height; }
    @Override public float getMinWidth() { return 0; }
    @Override public float getMinHeight() { return 0; }

    @Override public void layout() {
        float cardWidth = Math.max(0, (getWidth() - (columns - 1) * gap) / columns);
        for (int row = 0; row < rowHeader.length; row++) {
            Actor header = rowHeader[row];
            if (header == null) { continue; }
            header.setBounds(0, getHeight() - rowTop[row] - rowHeight[row], getWidth(), rowHeight[row]);
            if (header instanceof Layout layout) { layout.validate(); }
        }
        cards.forEach((index, actor) -> {
            int row = cardRow[index];
            actor.setBounds(cardColumn[index] * (cardWidth + gap), getHeight() - rowTop[row] - cardHeight, cardWidth, cardHeight);
            if (actor instanceof Layout layout) { layout.validate(); }
        });
    }
}
