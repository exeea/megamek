/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.ui.design.DesignTokens;

/**
 * The popup list of a {@link UiSelect} (docs/design-system/tokens.md, Select): a popover frame with a control edge and
 * one row per item, a check before the current one. The row under the pointer or the keyboard gets the hover wash;
 * Up and Down move it, Home and End jump, Enter or Space chooses it and Escape closes. It scrolls after
 * {@link #MAX_ROWS} rows. Opened with {@link #show}, it sits on the stage's root below its face, or above it when
 * there is no room below, at least as wide as the face, and closes on a press outside it. A view can also place it
 * in a layout as a static sample.
 */
public final class UiListPopup extends Table {
    /** The most rows shown before the list scrolls. */
    public static final int MAX_ROWS = 10;
    /** The longest pause between type-ahead keys that still adds to the prefix. */
    public static final long TYPE_AHEAD_MS = 1000;

    private final UiTheme.SelectStyle style;
    private final List<Row> rows = new ArrayList<>();
    private final ScrollPane scroll;
    private final float rowHeight;
    private final float inset;
    private final float gap;
    private int current = -1;
    private int active = -1;
    private IntConsumer chosen = index -> { };
    private Runnable closed = () -> { };
    /** The type-ahead prefix and the time of its last key. */
    private final StringBuilder typed = new StringBuilder();
    private long typedAt;
    /** The control that opened it, or null. */
    private Actor face;
    /** The actor that gets the keyboard focus back when it closes, or null. */
    private Actor returnFocus;

    /** Closes the list on a press outside it; a press on its face goes no further, so it does not open it again. */
    private final InputListener outside = new InputListener() {
        @Override
        public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
            Actor target = event.getTarget();
            if (target == null || target.isDescendantOf(UiListPopup.this)) {
                return false;
            }
            boolean onFace = face != null && target.isDescendantOf(face);
            close();
            if (onFace) {
                event.stop();
            }
            return onFace;
        }
    };

    UiListPopup(UiKit kit, String... items) {
        style = kit.skin.get(UiTheme.SelectStyle.class);
        DesignTokens tokens = DesignTokens.shipped();
        rowHeight = tokens.size().get("control.sm");
        gap = tokens.space().get("xs");
        inset = tokens.size().get("border") + gap;
        Table list = new Table();
        list.top().left();
        for (int i = 0; i < items.length; i++) {
            Row row = new Row(kit, tokens, i, items[i]);
            rows.add(row);
            list.add(row).growX().height(rowHeight).row();
        }
        scroll = kit.scrollList(list);
        setBackground(style.popup);
        pad(inset);
        add(scroll).grow();
        setTouchable(Touchable.enabled);
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return key(keycode);
            }

            @Override
            public boolean keyTyped(InputEvent event, char character) {
                return typeAhead(character, System.currentTimeMillis());
            }
        });
    }

    /** Calls {@code listener} with the index of a row the user chooses, whether or not it is the current one. */
    public UiListPopup onChoose(IntConsumer listener) {
        chosen = listener;
        return this;
    }

    /** Calls {@code listener} whenever the shown list closes. */
    public UiListPopup onClose(Runnable listener) {
        closed = listener;
        return this;
    }

    public int count() {
        return rows.size();
    }

    /** The index of the checked row, or -1 for none. */
    public int current() {
        return current;
    }

    /** Checks the row at {@code index}, or none for -1. */
    public UiListPopup current(int index) {
        current = index;
        return this;
    }

    /** The index of the row at the pointer or the keyboard, or -1 for none. */
    public int active() {
        return active;
    }

    /** Puts the hover wash on the row at {@code index}, or none for -1, and scrolls it into view. */
    public UiListPopup active(int index) {
        active = rows.isEmpty() ? -1 : Math.max(-1, Math.min(rows.size() - 1, index));
        if (active >= 0) {
            Row row = rows.get(active);
            scroll.layout();
            scroll.scrollTo(row.getX(), row.getY(), row.getWidth(), row.getHeight());
        }
        return this;
    }

    /** The label of the row at {@code index}. */
    public Label label(int index) {
        return rows.get(index).label;
    }

    /** Whether it is shown from a face: on a stage, and not as a static sample. */
    public boolean isOpen() {
        return face != null && getStage() != null;
    }

    /**
     * Shows the list on {@code face}'s stage below it, or above it when there is no room below, with the row at
     * {@code current} checked and active, and takes the keyboard focus; {@code focusAfter} gets it back on close.
     */
    public void show(Actor face, int current, Actor focusAfter) {
        Stage stage = face.getStage();
        if (stage == null || rows.isEmpty()) {
            return;
        }
        close();
        this.face = face;
        returnFocus = focusAfter;
        this.current = current;
        setSize(Math.max(getPrefWidth(), face.getWidth()), getPrefHeight());
        Vector2 corner = face.localToStageCoordinates(new Vector2(0, 0));
        float below = corner.y - gap - getHeight();
        float above = corner.y + face.getHeight() + gap;
        float y = below >= 0 || above + getHeight() > stage.getHeight() ? below : above;
        float x = Math.max(0, Math.min(corner.x, stage.getWidth() - getWidth()));
        setPosition(x, Math.max(0, y));
        stage.addActor(this);
        validate();
        active(Math.max(0, current));
        stage.removeCaptureListener(outside);
        stage.addCaptureListener(outside);
        stage.setKeyboardFocus(this);
    }

    /** Closes the shown list, if it is shown, without a choice. */
    public void close() {
        if (face == null) {
            return;
        }
        Stage stage = getStage();
        face = null;
        if (stage != null) {
            stage.removeCaptureListener(outside);
            Actor focus = stage.getKeyboardFocus();
            boolean focusInside = focus == null || focus.isDescendantOf(this);
            if (stage.getScrollFocus() != null && stage.getScrollFocus().isDescendantOf(this)) {
                stage.setScrollFocus(null);
            }
            remove();
            if (focusInside) {
                stage.setKeyboardFocus(returnFocus != null && returnFocus.getStage() == stage ? returnFocus : null);
            }
        }
        returnFocus = null;
        closed.run();
    }

    @Override
    public float getPrefHeight() {
        return Math.min(rows.size(), MAX_ROWS) * rowHeight + 2 * inset;
    }

    private boolean key(int keycode) {
        if (rows.isEmpty()) {
            return false;
        }
        switch (keycode) {
            case Input.Keys.UP -> active(active <= 0 ? 0 : active - 1);
            case Input.Keys.DOWN -> active(active + 1);
            case Input.Keys.HOME, Input.Keys.PAGE_UP -> active(0);
            case Input.Keys.END, Input.Keys.PAGE_DOWN -> active(rows.size() - 1);
            case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER, Input.Keys.SPACE -> {
                if (active >= 0) {
                    choose(active);
                }
            }
            case Input.Keys.ESCAPE, Input.Keys.TAB -> close();
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Type-ahead: a printable key adds to a prefix, kept while keys come less than {@link #TYPE_AHEAD_MS} apart, and
     * makes the first row starting with it, ignoring case, active. Returns whether the key was used.
     */
    boolean typeAhead(char character, long now) {
        if (rows.isEmpty() || Character.isISOControl(character)) {
            return false;
        }
        if (now - typedAt > TYPE_AHEAD_MS) {
            typed.setLength(0);
        }
        if (character == ' ' && typed.isEmpty()) {
            return false;
        }
        typedAt = now;
        typed.append(Character.toLowerCase(character));
        String prefix = typed.toString();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).label.getText().toString().toLowerCase().startsWith(prefix)) {
                active(i);
                return true;
            }
        }
        return true;
    }

    private void choose(int index) {
        close();
        chosen.accept(index);
    }

    /** One item: a check slot, then its text, with the hover wash when it is active. */
    private final class Row extends Table {
        private final int index;
        private final UiKit.Icon check;
        private final Label label;

        Row(UiKit kit, DesignTokens tokens, int index, String text) {
            this.index = index;
            float icon = tokens.size().get("icon.sm");
            float side = tokens.space().get("md");
            check = kit.icon("check", icon, style.check);
            // White style color: the label's own color is then the token color exactly
            label = new Label(text, new Label.LabelStyle(kit.skin.getFont("hud-body"), Color.WHITE));
            label.setColor(style.text);
            label.setEllipsis(true);
            add(check).size(icon).padLeft(side - gap);
            add(label).left().growX().minWidth(label.getPrefWidth()).padLeft(tokens.space().get("sm"))
                  .padRight(side - gap);
            setTouchable(Touchable.enabled);
            addListener(new ClickListener() {
                @Override
                public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                    super.enter(event, x, y, pointer, fromActor);
                    if (pointer == -1) {
                        active = Row.this.index;
                    }
                }

                @Override
                public boolean mouseMoved(InputEvent event, float x, float y) {
                    active = Row.this.index;
                    return false;
                }

                @Override
                public void clicked(InputEvent event, float x, float y) {
                    choose(Row.this.index);
                }
            });
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            if (index == active) {
                Color tint = getColor();
                batch.setColor(tint.r, tint.g, tint.b, tint.a * parentAlpha);
                style.rowOver.draw(batch, getX(), getY(), getWidth(), getHeight());
            }
            check.setVisible(index == current);
            super.draw(batch, parentAlpha);
        }
    }
}
