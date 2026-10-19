/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;

/** Click focus and arrow-key steps for a slider, including held keys and blur on an outside press. */
public final class UiSliderInput extends InputListener {
    private static final float REPEAT_DELAY = .3f;
    private static final float REPEAT_INTERVAL = .05f;
    private final Slider slider;
    private final Runnable finished;
    private Stage focusedStage;
    private Slider.SliderStyle normal;
    private int heldKey = -1;
    private float repeatIn;
    private boolean changed;
    private final InputListener outside = new InputListener() {
        @Override
        public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
            // Another capture listener (such as a closing popover) may already have released our focus.
            if (event.getStage().getKeyboardFocus() == slider && !event.getTarget().isDescendantOf(slider)) {
                event.getStage().setKeyboardFocus(null);
            }
            return false;
        }
    };
    /** Runs only while focused; hidden or disabled controls must release the keyboard too. */
    private final Action focused = new Action() {
        @Override
        public boolean act(float delta) {
            if (slider.isDisabled() || !slider.ascendantsVisible()) {
                focusedStage.setKeyboardFocus(null);
                return true;
            }
            if (heldKey != -1) {
                if (!Gdx.input.isKeyPressed(heldKey)) {
                    stopRepeating();
                } else {
                    repeatIn -= delta;
                    if (repeatIn <= 0) {
                        repeatIn = REPEAT_INTERVAL;
                        step();
                    }
                }
            }
            return false;
        }
    };

    /** Adds keyboard interaction without changing the slider's value or its change listeners. */
    public UiSliderInput(Slider slider) {
        this(slider, () -> { });
    }

    /** Runs {@code finished} once after a keyboard edit ends, on key release or focus loss. */
    public UiSliderInput(Slider slider, Runnable finished) {
        this.slider = slider;
        this.finished = finished;
        slider.addListener(this);
        slider.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean hasFocus) {
                if (hasFocus) {
                    normal = slider.getStyle();
                    Slider.SliderStyle highlighted = new Slider.SliderStyle(normal);
                    if (normal.knobOver != null) { highlighted.knob = normal.knobOver; }
                    slider.setStyle(highlighted);
                    focusedStage = slider.getStage();
                    focusedStage.addCaptureListener(outside);
                    slider.addAction(focused);
                } else {
                    focusedStage.removeCaptureListener(outside);
                    focusedStage = null;
                    slider.removeAction(focused);
                    slider.setStyle(normal);
                    stopRepeating();
                }
            }
        });
    }

    @Override
    public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
        if (pointer == 0 && button == Input.Buttons.LEFT && !slider.isDisabled()) {
            slider.getStage().setKeyboardFocus(slider);
        }
        return false;
    }

    @Override
    public boolean keyDown(InputEvent event, int key) {
        if (!slider.hasKeyboardFocus() || slider.isDisabled() || !slider.ascendantsVisible()
              || direction(key) == 0) { return false; }
        if (heldKey != key) {
            stopRepeating();
            heldKey = key;
            repeatIn = REPEAT_DELAY;
            step();
        }
        return true;
    }

    @Override
    public boolean keyUp(InputEvent event, int key) {
        if (key != heldKey) { return false; }
        stopRepeating();
        return true;
    }

    private void step() {
        changed |= slider.setValue(slider.getValue() + direction(heldKey) * slider.getStepSize());
    }

    private void stopRepeating() {
        heldKey = -1;
        if (changed) {
            changed = false;
            finished.run();
        }
    }

    private static int direction(int key) {
        return switch (key) {
            case Input.Keys.LEFT, Input.Keys.DOWN -> -1;
            case Input.Keys.RIGHT, Input.Keys.UP -> 1;
            default -> 0;
        };
    }
}
