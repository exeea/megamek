/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.ui.design.DesignTokens;

/**
 * Picks from a long list: a compact select showing its placeholder (such as "Add…"), and the picked items as chips that
 * wrap below it, in the order they were picked. Choosing an item in the select adds it as must-have; a click on a
 * chip's close glyph takes it out. When the list {@code excludes}, a click on the chip itself switches it between
 * must-have (the neutral acting chip) and must-not (the danger chip with its error icon); otherwise a click anywhere on
 * the chip takes it out. Each change by the user fires one ChangeEvent from the list; {@link #set} fires none. Made by
 * {@link UiKit#pickList(String, List, boolean)}.
 */
public final class UiPickList extends Table {
    private final UiKit kit;
    private final List<String> items;
    private final boolean excludes;
    private final UiSelect select;
    private final HorizontalGroup chips = new HorizontalGroup();
    private final Map<Integer, Boolean> picked = new LinkedHashMap<>();
    private String chipTip;

    UiPickList(UiKit kit, String placeholder, List<String> items, boolean excludes) {
        this.kit = kit;
        this.items = List.copyOf(items);
        this.excludes = excludes;
        DesignTokens tokens = DesignTokens.shipped();
        select = kit.tokenSelect(true, items.toArray(String[]::new)).placeholder(placeholder);
        select.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (actor != select) {
                    return;
                }
                event.stop();
                int index = select.getSelectedIndex();
                select.select(-1);
                if (index >= 0 && !picked.containsKey(index)) {
                    picked.put(index, Boolean.TRUE);
                    UiPickList.this.changed();
                }
            }
        });
        chips.wrap(true).space(tokens.space().get("sm")).wrapSpace(tokens.space().get("xs")).rowLeft();
        top().left();
        add(select).left().minWidth(tokens.size().get("dialog.sm") / 3).row();
        add(chips).growX().minWidth(0).left().padTop(tokens.space().get("xs"));
        chips.setVisible(false);
    }

    /** The select that adds picks. */
    public UiSelect select() {
        return select;
    }

    /** The picks, by item index: TRUE for must-have, FALSE for must-not, in the order they were picked. */
    public Map<Integer, Boolean> picks() {
        return Collections.unmodifiableMap(picked);
    }

    /** Shows {@code next}'s picks, by item index, without a ChangeEvent. */
    public UiPickList set(Map<Integer, Boolean> next) {
        picked.clear();
        next.forEach((index, state) -> {
            if (index >= 0 && index < items.size() && state != null) {
                picked.put(index, excludes ? state : Boolean.TRUE);
            }
        });
        refresh();
        return this;
    }

    /** Puts {@code text} as the tooltip of every chip. */
    public UiPickList chipTip(String text) {
        chipTip = text;
        refresh();
        return this;
    }

    /** The chips, in order; a test clicks them. */
    public List<Button> chips() {
        List<Button> shown = new ArrayList<>();
        for (Actor chip : chips.getChildren()) {
            shown.add((Button) chip);
        }
        return shown;
    }

    private void changed() {
        refresh();
        fire(new ChangeListener.ChangeEvent());
    }

    private void refresh() {
        chips.clearChildren();
        float icon = DesignTokens.shipped().size().get("icon.sm");
        picked.forEach((index, state) -> {
            Button chip = kit.actingChip(items.get(index), state ? UiKit.Tone.NORMAL : UiKit.Tone.BAD);
            UiKit.Icon close = kit.icon("close", icon, UiTheme.tokenColor("text.secondary"));
            close.setTouchable(Touchable.enabled);
            close.addListener(new ClickListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    boolean handled = super.touchDown(event, x, y, pointer, button);
                    // The chip under the glyph must not switch as well
                    event.stop();
                    return handled;
                }

                @Override
                public void clicked(InputEvent event, float x, float y) {
                    picked.remove(index);
                    changed();
                }
            });
            chip.add(close).size(icon).padLeft(DesignTokens.shipped().space().get("xs"));
            chip.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (actor != chip) {
                        return;
                    }
                    event.stop();
                    if (excludes) {
                        picked.put(index, !picked.get(index));
                    } else {
                        picked.remove(index);
                    }
                    UiPickList.this.changed();
                }
            });
            if (chipTip != null) {
                kit.tip(chip, true).getActor().setText(chipTip);
            }
            chips.addActor(chip);
        });
        chips.setVisible(!picked.isEmpty());
        getCell(chips).padTop(picked.isEmpty() ? 0 : DesignTokens.shipped().space().get("xs"));
        invalidateHierarchy();
    }
}
