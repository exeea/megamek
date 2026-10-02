/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import com.badlogic.gdx.utils.Pools;

/**
 * A menu's items and separators (.pop .it, .pop .sep), in a popover or a panel. Up and Down (wrapping), Home and End
 * move a keyboard highlight over the enabled items, and Enter chooses the highlighted item as a click does; the
 * highlight is the item's pressed state. The list takes these keys while it holds the stage's keyboard focus.
 */
public final class UiMenuList extends Table {
    /** A one-line item's height: the padding of 8 units around one line of the 13-unit body at its 1.35 line height. */
    private static final float ITEM_HEIGHT = 8 + 13 * 1.35f + 8;
    private final UiKit kit;
    private final List<UiButton> items = new ArrayList<>();
    private UiButton highlighted;

    /** An empty list of the kit's menu rows. */
    public UiMenuList(UiKit kit) {
        this.kit = kit;
        top();
        // Items keep their fractional height, so a long list stays on the prototype's 33.55-unit rhythm.
        setRound(false);
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return UiMenuList.this.keyDown(keycode);
            }
        });
    }

    /**
     * Adds an item ({@link UiKit#menuRow}: an optional check mark, null for no toggle; the text; a detail such as a
     * shortcut; a chevron when it opens a group) that runs {@code action} when it is clicked or chosen with Enter. A
     * disabled item is dim and skipped by the keyboard. An item is one line high; a text of several lines makes it
     * taller. Returns the item's row; its view may change its enabled state.
     */
    public UiButton item(String text, String detail, Boolean checked, boolean group, boolean enabled, Runnable action) {
        UiButton row = kit.menuRow(text, detail, checked, group);
        row.setDisabled(!enabled);
        UiKit.onChange(row, action);
        items.add(row);
        add(row).growX().minHeight(ITEM_HEIGHT).row();
        return row;
    }

    /** A hairline between two groups of items. */
    public void separator() {
        add(new Image(kit.skin.getDrawable("rule"))).growX().height(1).pad(4, 0, 4, 0).row();
    }

    /**
     * The menu keys: Up and Down move the highlight to the previous or next enabled item, wrapping around (from none,
     * Down starts at the first and Up at the last), Home and End to the first and last, and Enter chooses the
     * highlighted item. Returns false for other keys, for Enter without a highlight and when no item is enabled, so
     * those keys stay with the view's other handlers.
     */
    public boolean keyDown(int key) {
        List<UiButton> enabled = items.stream().filter(row -> !row.isDisabled()).toList();
        if (enabled.isEmpty()) {
            return false;
        }
        int size = enabled.size();
        int index = enabled.indexOf(highlighted);
        switch (key) {
            case Input.Keys.DOWN -> index = (index + 1) % size;
            case Input.Keys.UP -> index = index < 0 ? size - 1 : (index + size - 1) % size;
            case Input.Keys.HOME -> index = 0;
            case Input.Keys.END -> index = size - 1;
            case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> {
                if (index < 0) {
                    return false;
                }
                ChangeEvent event = Pools.obtain(ChangeEvent.class);
                enabled.get(index).fire(event);
                Pools.free(event);
                return true;
            }
            default -> {
                return false;
            }
        }
        highlight(enabled.get(index));
        return true;
    }

    /**
     * Highlights {@code row}, an enabled item of this list, as the keys do, and keeps it in view when the list scrolls.
     * A view that rebuilds its list keeps the keyboard's place this way; any other actor is ignored.
     */
    public void highlight(UiButton row) {
        if (!items.contains(row) || row.isDisabled()) {
            return;
        }
        if (highlighted != null) {
            highlighted.pressed(false);
        }
        highlighted = row.pressed(true);
        if (getParent() instanceof ScrollPane scroll) {
            validate();
            // ScrollPane.scrollTo measures the rectangle's y at its top edge (libGDX 1.14.2).
            scroll.scrollTo(row.getX(), row.getTop(), row.getWidth(), row.getHeight());
        }
    }
}
