/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Cullable;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;

/**
 * A list of any length whose rows all have one height, made for a {@link ScrollPane}: only the rows in view exist, a
 * few actors reused as the list scrolls, so thousands of items cost no more than a screenful. Rows wear the token row's
 * hover and selected washes ({@link UiTheme#TOKEN_ROW}). A click selects a row and a double click, or Enter, activates
 * it; with the keyboard focus, Up, Down, Home, End, Page Up and Page Down move the selection, and the scroll pane
 * follows it.
 *
 * @param <T> the items
 */
public final class UiVirtualList<T> extends WidgetGroup implements Cullable {

    /** The rows from {@code first} to {@code last}, both included; empty when {@code last} is below {@code first}. */
    public record Range(int first, int last) {
        public static final Range NONE = new Range(0, -1);

        public int count() {
            return Math.max(0, last - first + 1);
        }

        public boolean contains(int index) {
            return (index >= first) && (index <= last);
        }
    }

    private final Supplier<Actor> rowMaker;
    private final BiConsumer<Actor, T> binder;
    private final float rowHeight;
    private final Drawable hover;
    private final Drawable selectedWash;
    private final List<Actor> pool = new ArrayList<>();
    private List<T> items = List.of();
    private Rectangle cullingArea;
    private Range bound = Range.NONE;
    private int selected = -1;
    private int hovered = -1;
    private BiConsumer<T, Integer> onSelect = (item, index) -> { };
    private BiConsumer<T, Integer> onActivate = (item, index) -> { };

    /**
     * @param kit       the kit whose skin holds the token row
     * @param rowHeight each row's height
     * @param rowMaker  makes an empty row; called for as many rows as fit in view, and a few more
     * @param binder    shows an item in a row made by {@code rowMaker}, setting all it shows, as rows are reused
     */
    public UiVirtualList(UiKit kit, float rowHeight, Supplier<Actor> rowMaker, BiConsumer<Actor, T> binder) {
        this.rowMaker = rowMaker;
        this.binder = binder;
        this.rowHeight = rowHeight;
        TextButton.TextButtonStyle row = kit.skin.get(UiTheme.TOKEN_ROW, TextButton.TextButtonStyle.class);
        hover = row.over;
        selectedWash = row.checked;
        setTransform(false);
        addListener(new ClickListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                Stage stage = getStage();
                if (stage != null) {
                    stage.setKeyboardFocus(UiVirtualList.this);
                }
                return super.touchDown(event, x, y, pointer, button);
            }

            @Override
            public void clicked(InputEvent event, float x, float y) {
                int index = indexAt(y);
                if (index < 0) {
                    return;
                }
                select(index, true);
                if (getTapCount() >= 2) {
                    onActivate.accept(items.get(index), index);
                }
            }

            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                hovered = indexAt(y);
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                super.exit(event, x, y, pointer, toActor);
                if (toActor == null || !toActor.isDescendantOf(UiVirtualList.this)) {
                    hovered = -1;
                }
            }
        });
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return key(keycode);
            }
        });
    }

    /**
     * The rows of a list {@code height} high, with {@code count} rows {@code rowHeight} high from its top, that show in
     * the band from {@code bottom} to {@code top}, measured up from the list's bottom.
     */
    public static Range visible(float height, float rowHeight, int count, float bottom, float top) {
        if ((count <= 0) || (rowHeight <= 0) || (top <= bottom)) {
            return Range.NONE;
        }
        int first = Math.max(0, (int) Math.floor((height - top) / rowHeight));
        int last = Math.min(count - 1, (int) Math.ceil((height - bottom) / rowHeight) - 1);
        return (last < first) ? Range.NONE : new Range(first, last);
    }

    /** Where the selection goes from {@code current} with {@code keycode}, on a list of {@code count} rows. */
    public static int step(int current, int count, int keycode, int page) {
        if (count <= 0) {
            return -1;
        }
        int next = switch (keycode) {
            case Input.Keys.UP -> (current < 0) ? count - 1 : current - 1;
            case Input.Keys.DOWN -> current + 1;
            case Input.Keys.HOME -> 0;
            case Input.Keys.END -> count - 1;
            case Input.Keys.PAGE_UP -> current - Math.max(1, page);
            case Input.Keys.PAGE_DOWN -> Math.max(0, current) + Math.max(1, page);
            default -> current;
        };
        return Math.max(0, Math.min(count - 1, next));
    }

    /** Runs {@code listener} with the item and its index as the Commander selects a row. */
    public void onSelect(BiConsumer<T, Integer> listener) {
        onSelect = listener;
    }

    /** Runs {@code listener} with the item and its index on a double click or Enter. */
    public void onActivate(BiConsumer<T, Integer> listener) {
        onActivate = listener;
    }

    /**
     * Shows {@code next}. The selection stays on its item when {@code next} still holds it (by equality), else there
     * is none; the scroll position is the scroll pane's to keep or reset.
     */
    public void setItems(List<T> next) {
        T was = selectedItem();
        items = List.copyOf(next);
        selected = (was == null) ? -1 : items.indexOf(was);
        hovered = -1;
        bound = Range.NONE;
        invalidateHierarchy();
    }

    public List<T> items() {
        return items;
    }

    /** Shows the rows in view again, as after what they draw has changed, such as an image that arrived. */
    public void rebind() {
        bound = Range.NONE;
    }

    /** The items whose rows are in view now. */
    public List<T> inView() {
        return bound.count() == 0 ? List.of() : items.subList(bound.first(), bound.last() + 1);
    }

    public int selectedIndex() {
        return selected;
    }

    /** The selected item, or null when none is. */
    public T selectedItem() {
        return (selected >= 0) && (selected < items.size()) ? items.get(selected) : null;
    }

    /** Selects row {@code index}, or none for -1, and scrolls it into view; tells the listener when {@code notify}. */
    public void select(int index, boolean notify) {
        selected = (index >= 0) && (index < items.size()) ? index : -1;
        if (selected >= 0) {
            scrollToSelected();
            if (notify) {
                onSelect.accept(items.get(selected), selected);
            }
        }
    }

    /** Scrolls the enclosing scroll pane so the selected row shows. */
    public void scrollToSelected() {
        if ((selected >= 0) && (getParent() instanceof ScrollPane pane)) {
            pane.layout();
            pane.scrollTo(0, getHeight() - (selected + 1) * rowHeight, getWidth(), rowHeight);
        }
    }

    private boolean key(int keycode) {
        if (items.isEmpty()) {
            return false;
        }
        if ((keycode == Input.Keys.ENTER) || (keycode == Input.Keys.NUMPAD_ENTER)) {
            if (selected >= 0) {
                onActivate.accept(items.get(selected), selected);
                return true;
            }
            return false;
        }
        int page = (int) Math.max(1, Math.floor(viewHeight() / rowHeight) - 1);
        int next = step(selected, items.size(), keycode, page);
        if ((next == selected) && (keycode != Input.Keys.HOME) && (keycode != Input.Keys.END)) {
            return isNavigation(keycode);
        }
        select(next, true);
        return true;
    }

    private static boolean isNavigation(int keycode) {
        return switch (keycode) {
            case Input.Keys.UP, Input.Keys.DOWN, Input.Keys.HOME, Input.Keys.END, Input.Keys.PAGE_UP,
                 Input.Keys.PAGE_DOWN -> true;
            default -> false;
        };
    }

    private float viewHeight() {
        return (cullingArea != null) ? cullingArea.height : getHeight();
    }

    private int indexAt(float y) {
        int index = (int) Math.floor((getHeight() - y) / rowHeight);
        return (index >= 0) && (index < items.size()) ? index : -1;
    }

    @Override
    public void setCullingArea(Rectangle area) {
        cullingArea = area;
    }

    @Override
    public float getPrefWidth() {
        return 0;
    }

    @Override
    public float getPrefHeight() {
        return items.size() * rowHeight;
    }

    @Override
    public void layout() {
        bound = Range.NONE;
        sync();
    }

    /** Binds the rows in view to their items, reusing the row actors, when the rows in view have changed. */
    private void sync() {
        Range range = (cullingArea == null) ? visible(getHeight(), rowHeight, items.size(), 0, getHeight())
              : visible(getHeight(), rowHeight, items.size(), cullingArea.y, cullingArea.y + cullingArea.height);
        if (range.equals(bound)) {
            return;
        }
        bound = range;
        while (pool.size() < range.count()) {
            Actor row = rowMaker.get();
            row.setTouchable(Touchable.disabled);
            pool.add(row);
            addActor(row);
        }
        for (int slot = 0; slot < pool.size(); slot++) {
            Actor row = pool.get(slot);
            int index = range.first() + slot;
            boolean shown = range.contains(index);
            row.setVisible(shown);
            if (shown) {
                binder.accept(row, items.get(index));
                row.setBounds(0, getHeight() - (index + 1) * rowHeight, getWidth(), rowHeight);
                if (row instanceof Layout layout) {
                    layout.invalidate();
                    layout.validate();
                }
            }
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        sync();
        float alpha = getColor().a * parentAlpha;
        batch.setColor(1, 1, 1, alpha);
        if (bound.contains(hovered) && (hovered != selected)) {
            hover.draw(batch, getX(), getY() + getHeight() - (hovered + 1) * rowHeight, getWidth(), rowHeight);
        }
        if (bound.contains(selected)) {
            selectedWash.draw(batch, getX(), getY() + getHeight() - (selected + 1) * rowHeight, getWidth(), rowHeight);
        }
        super.draw(batch, parentAlpha);
    }
}
