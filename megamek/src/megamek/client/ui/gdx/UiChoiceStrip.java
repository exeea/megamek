/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;

/** One horizontal row, creating only visible cards and one overscan card on each side. */
public final class UiChoiceStrip extends WidgetGroup {
    private final float cardWidth, cardHeight, gap;
    private final Map<Integer, Actor> cards = new HashMap<>();
    private IntFunction<Actor> create;
    private int count;
    private ScrollPane scroll;

    public UiChoiceStrip(float cardWidth, float cardHeight, float gap) {
        this.cardWidth = cardWidth; this.cardHeight = cardHeight; this.gap = gap;
        setTransform(false); setTouchable(Touchable.childrenOnly);
    }

    public void items(int count, IntFunction<Actor> create) {
        clearChildren(); cards.clear(); this.count = count; this.create = create; invalidateHierarchy();
    }

    /** A reusable viewport with a persistent scrollbar and page arrows only when choices overflow. */
    public Table viewport(UiKit ui) {
        scroll = ui.scrollStrip(this);
        UiButton previous = ui.button("hud-mini", "chevron-left", "", null);
        UiButton next = ui.button("hud-mini", "chevron-right", "", null);
        previous.setName("choices-previous"); next.setName("choices-next");
        for (UiButton button : java.util.List.of(previous, next)) {
            button.clearChildren(); button.pad(0); button.icons.forEach(icon -> button.add(icon).size(26));
        }
        UiKit.onChange(previous, () -> page(-1)); UiKit.onChange(next, () -> page(1));
        Table viewport = new Table() {
            private boolean overflow;
            @Override public void act(float delta) {
                super.act(delta);
                boolean more = getPrefWidth() > 0 && UiChoiceStrip.this.getPrefWidth() > getWidth();
                if (more != overflow) {
                    overflow = more;
                    getCell(previous).width(more ? 30 : 0); getCell(next).width(more ? 30 : 0);
                    invalidate(); validate();
                }
                previous.setVisible(more); next.setVisible(more);
                scroll.validate();
                previous.setDisabled(scroll.getScrollX() <= .5f);
                next.setDisabled(scroll.getScrollX() >= scroll.getMaxX() - .5f);
                show(scroll.getVisualScrollX(), scroll.getScrollWidth());
            }
        };
        viewport.add(previous).width(0).growY();
        viewport.add(scroll).grow().minSize(0);
        viewport.add(next).width(0).growY();
        return viewport;
    }

    public ScrollPane scroll() { return scroll; }

    private void page(int direction) {
        int visible = Math.max(1, (int) (scroll.getScrollWidth() / (cardWidth + gap)));
        scroll.setScrollX(Math.max(0, Math.min(scroll.getMaxX(), scroll.getScrollX() + direction * visible * (cardWidth + gap))));
        scroll.updateVisualScroll();
    }

    /** The containing ScrollPane supplies its viewport, in the strip's own coordinates. */
    public void show(float scrollX, float viewportWidth) {
        int first = Math.max(0, (int) (scrollX / (cardWidth + gap)) - 1);
        int last = Math.min(count, (int) Math.ceil((scrollX + viewportWidth) / (cardWidth + gap)) + 1);
        cards.entrySet().removeIf(entry -> {
            if (entry.getKey() >= first && entry.getKey() < last) { return false; }
            entry.getValue().remove(); return true;
        });
        for (int index = first; index < last; index++) {
            if (!cards.containsKey(index)) { Actor actor = create.apply(index); cards.put(index, actor); addActor(actor); }
        }
        invalidate(); validate();
    }

    @Override public float getPrefWidth() { return Math.max(0, count * (cardWidth + gap) - gap); }
    @Override public float getPrefHeight() { return cardHeight; }
    @Override public float getMinWidth() { return 0; }
    @Override public float getMinHeight() { return cardHeight; }

    @Override public void layout() {
        cards.forEach((index, actor) -> {
            actor.setBounds(index * (cardWidth + gap), 0, cardWidth, cardHeight);
            if (actor instanceof Layout layout) { layout.validate(); }
        });
    }
}
