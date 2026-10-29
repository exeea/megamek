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
 * A compact whole-number stepper: − the value + ({@code button-icon} compact buttons round the value in body.strong),
 * for small counts such as a pilot's skill or how many copies to add. Its buttons disable at the bounds; the arrow keys
 * step it while it has the keyboard focus. It fires a ChangeEvent on each step. Made by
 * {@link UiKit#stepper(int, int, int)}.
 */
public final class UiStepper extends Table {
    private final int min;
    private final int max;
    private final UiButton down;
    private final UiButton up;
    private final Label shown;
    private int value;

    UiStepper(UiKit kit, int value, int min, int max) {
        this.min = min;
        this.max = Math.max(min, max);
        float control = DesignTokens.shipped().size().get("control.sm");
        down = kit.button("button-icon" + UiTheme.COMPACT, "remove", null, null);
        up = kit.button("button-icon" + UiTheme.COMPACT, "add", null, null);
        shown = kit.typeLabel("", "body.strong", "text.primary");
        shown.setAlignment(Align.center);
        add(down).size(control);
        // Wide enough for two digits, so the buttons stay put
        add(shown).width(control).height(control);
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
