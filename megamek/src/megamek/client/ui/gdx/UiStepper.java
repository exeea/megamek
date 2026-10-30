/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.design.DesignTokens;

/**
 * A compact whole-number stepper: an optional caption, then − the value + ({@code button-icon} compact buttons round the
 * value in body.strong), for small counts such as a pilot's skill or how many copies to add. The caption, the buttons
 * and the value stand {@link #GAP} ({@code xs}) apart, every gap the same, so a row of steppers reads as groups when
 * they stand a larger space apart; the value's cell is as wide as the widest value, so the buttons never move. Its
 * buttons disable at the bounds; the arrow keys step it while it has the keyboard focus. It fires a ChangeEvent on each
 * step. Made by {@link UiKit#stepper(int, int, int)} and {@link UiKit#stepper(String, int, int, int)}.
 */
public final class UiStepper extends Table {
    private final int min;
    private final int max;
    private final UiButton down;
    private final UiButton up;
    private final Label shown;
    private final Label caption;
    private int value;

    UiStepper(UiKit kit, String caption, int value, int min, int max) {
        this.min = min;
        this.max = Math.max(min, max);
        float control = DesignTokens.shipped().size().get("control.sm");
        float gap = gap();
        down = kit.button("button-icon" + UiTheme.COMPACT, "remove", null, null);
        up = kit.button("button-icon" + UiTheme.COMPACT, "add", null, null);
        shown = kit.typeLabel("", "body.strong", "text.primary");
        shown.setAlignment(Align.center);
        if (caption != null) {
            this.caption = kit.typeLabel(caption, "small", "text.secondary");
            add(this.caption).padRight(gap);
        } else {
            this.caption = null;
        }
        add(down).size(control);
        add(shown).width(valueWidth(this.min, this.max)).height(control).padLeft(gap).padRight(gap);
        add(up).size(control);
        down.addListener(stepping(-1));
        up.addListener(stepping(1));
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return switch (keycode) {
                    case Input.Keys.LEFT, Input.Keys.DOWN, Input.Keys.MINUS -> step(-1);
                    case Input.Keys.RIGHT, Input.Keys.UP, Input.Keys.PLUS, Input.Keys.EQUALS -> step(1);
                    default -> false;
                };
            }
        });
        value(value);
    }

    /** The space between the caption, the buttons and the value: {@code xs}. */
    public static float gap() {
        return DesignTokens.shipped().space().get("xs");
    }

    /** The value's cell: as wide as the widest of the values from {@code min} to {@code max}. */
    private float valueWidth(int min, int max) {
        String saved = shown.getText().toString();
        float widest = 0;
        for (String text : new String[] { String.valueOf(min), String.valueOf(max),
              "0".repeat(String.valueOf(max).length()) }) {
            shown.setText(text);
            widest = Math.max(widest, shown.getPrefWidth());
        }
        shown.setText(saved);
        return (float) Math.ceil(widest);
    }

    /** The caption before the buttons, or null. */
    public Label caption() {
        return caption;
    }

    /** The − button, to click through the stage. */
    public UiButton down() {
        return down;
    }

    /** The + button, to click through the stage. */
    public UiButton up() {
        return up;
    }

    /** The value's label. */
    public Label shown() {
        return shown;
    }

    /** The value, within the bounds. */
    public int value() {
        return value;
    }

    /** Sets the value, brought within the bounds, without a ChangeEvent. */
    public void value(int next) {
        value = clamp(next, min, max);
        shown.setText(String.valueOf(value));
        down.setDisabled(value <= min);
        up.setDisabled(value >= max);
    }

    /** {@code value} brought within {@code min} and {@code max}. */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Steps by {@code delta} on a button's change, which goes no further: the stepper fires its own. */
    private ChangeListener stepping(int delta) {
        return new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                event.stop();
                step(delta);
            }
        };
    }

    private boolean step(int delta) {
        int next = clamp(value + delta, min, max);
        if (next == value) {
            return false;
        }
        value(next);
        fire(new ChangeListener.ChangeEvent());
        return true;
    }
}
