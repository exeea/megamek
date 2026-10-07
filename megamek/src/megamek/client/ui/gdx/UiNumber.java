/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;

/** Caption scrubbing, a click-open slider, and direct typing with the same commit boundary. */
public final class UiNumber extends Table {
    private final UiKit ui;
    private final Cell<UiKit.Checkbox> checkboxCell;
    private final TextField field;
    private final UiPopover popup;
    private final Slider slider;
    private final Label readout;
    private final Label caption;
    private final String label;
    private UiAngleDial dial;
    private final BiConsumer<String, Boolean> change;
    private final double step;
    private double value;
    private boolean held;
    private Input capturedInput;

    public UiNumber(UiKit ui, Stage stage, String name, double initial, double min, double max, double step,
          BiConsumer<String, Boolean> change) {
        this.ui = ui;
        this.change = change;
        this.step = step;
        label = name;
        value = initial;
        checkboxCell = add((UiKit.Checkbox) null);
        caption = ui.label(name + "  ↔", "hud-small", 12, UiTheme.TEXT);
        caption.setName("editor-scrub-" + name);
        caption.setEllipsis(true);
        add(caption).growX().minWidth(0).padRight(8);
        field = new TextField(format(initial), ui.skin, "hud");
        field.setName("editor-" + name);
        add(field).width(84).height(29);
        slider = new Slider((float) Math.min(min, initial), (float) Math.max(max, initial), (float) step, false, ui.sliderStyle());
        slider.setName("editor-slider-" + name);
        slider.setValue((float) initial);
        readout = ui.label(format(initial), "hud-small", 14, UiTheme.TEXT);
        readout.setAlignment(Align.right);
        readout.setName("editor-slider-value-" + name);
        popup = new UiPopover(ui).header(name, "Drag to adjust · type a value for a wider range");
        popup.setName("editor-number-popup");
        Table content = new Table(); content.pad(10, 14, 10, 14);
        content.add(slider).width(188).padRight(12);
        content.add(readout).minWidth(60).right();
        popup.content(content);
        stage.addActor(popup);
        UiKit.onChange(slider, () -> preview(slider.getValue()));
        slider.addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                return button == Input.Buttons.LEFT;
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                finish(); popup.cancel();
            }
        });
        caption.addListener(new InputListener() {
            float origin;
            float originY;
            double start;
            boolean dragged;
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT || pointer != 0 || held) { return false; }
                stage.setKeyboardFocus(null);
                origin = event.getStageX(); originY = event.getStageY(); start = value; dragged = false; held = true;
                syncSlider(value);
                popup.showAbove(caption, 0);
                if (dial != null) { popup.validate(); dial.begin(event.getStageX(), event.getStageY(), value); }
                // The desktop backend supplies unbounded virtual coordinates while keeping the cursor hidden
                // and anchored, then restores its visible position on release. Do not take over another capture.
                if (!Gdx.input.isCursorCatched()) {
                    capturedInput = Gdx.input;
                    capturedInput.setCursorCatched(true);
                }
                return true;
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (!held) { return; }
                float distance = event.getStageX() - origin;
                if (Math.hypot(distance, event.getStageY() - originY) > 3) { dragged = true; }
                if (dragged) {
                    if (dial != null) { dial.drag(event.getStageX(), event.getStageY()); return; }
                    double next = Math.round((start + distance * step / 4) / step) * step;
                    next = MathUtils.clamp((float) next, slider.getMinValue(), slider.getMaxValue());
                    syncSlider(next); preview(next);
                }
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                if (!held) { return; }
                held = false;
                if (capturedInput != null) {
                    capturedInput.setCursorCatched(false);
                    capturedInput = null;
                }
                if (dragged) { finish(); popup.cancel(); }
                else if (event.isTouchFocusCancel()) { popup.cancel(); }
            }
        });
        field.addListener(new FocusListener() {
            @Override public void keyboardFocusChanged(FocusEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor, boolean focused) {
                if (!focused) { typed(); }
            }
        });
        field.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int key) {
                if (key == Input.Keys.ENTER || key == Input.Keys.NUMPAD_ENTER) { stage.setKeyboardFocus(null); return true; }
                if (key == Input.Keys.ESCAPE) { field.setText(format(value)); stage.setKeyboardFocus(null); return true; }
                return false;
            }
        });
    }

    private void preview(double next) {
        next = normalized(next);
        if (Math.abs(next - value) < step * .001) { return; }
        value = next; showValue(); change.accept(format(next), false);
    }
    private void finish() { change.accept(format(value), true); }
    private void typed() {
        double previous = value;
        try {
            double next = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(next) || step >= 1 && next != Math.rint(next)) { throw new NumberFormatException(); }
            next = normalized(next);
            if (next != value) { value = next; finish(); }
        } catch (IllegalArgumentException invalid) { value = previous; }
        showValue();
    }
    private void showValue() {
        String shown = format(value);
        field.setText(shown);
        readout.setText(shown);
        if (dial != null) { dial.value(value); }
    }
    /** Silence only our own updates: libGDX uses setValue for pointer drags too. */
    private void syncSlider(double next) {
        slider.setProgrammaticChangeEvents(false);
        try {
            slider.setRange((float) Math.min(slider.getMinValue(), next), (float) Math.max(slider.getMaxValue(), next));
            slider.setValue((float) next);
        } finally { slider.setProgrammaticChangeEvents(true); }
    }
    public boolean editing() { return held || popup.isVisible() || field.hasKeyboardFocus(); }
    /** An angle wraps at +/-180 degrees and uses the kit's circular dial, including caption dragging. */
    public UiNumber angle() {
        dial = new UiAngleDial(ui, next -> preview(Math.round(next / step) * step), () -> { finish(); popup.cancel(); });
        dial.setName("editor-dial-" + label);
        caption.setText(label + "  ↻");
        Table content = new Table(); content.pad(4, 12, 4, 12);
        content.add(dial).size(132); content.add(readout).minWidth(62).padLeft(8);
        popup.header(label + " rotation", "Drag around the dial · angles wrap").content(content);
        value = normalized(value); showValue();
        return this;
    }
    private double normalized(double next) {
        return dial == null ? next : next - 360 * Math.floor((next + 180) / 360);
    }
    /** Optional application toggle; typing and caption scrubbing remain available while it is unchecked. */
    public UiNumber withCheckbox(boolean checked, Consumer<Boolean> changed) {
        UiKit.Checkbox checkbox = ui.checkbox("", checked);
        checkbox.setName(getName() == null ? "number-enabled" : getName() + "-enabled");
        UiKit.onChange(checkbox, () -> changed.accept(checkbox.isTicked()));
        checkboxCell.setActor(checkbox).width(26);
        invalidateHierarchy();
        return this;
    }
    /** Size the value field for the space available; caption gestures and the slider stay the same. */
    public UiNumber fieldSize(float width, float height) {
        getCell(field).size(width, height);
        return this;
    }
    /** Axis triplets share their rotation heading, so each caption can be just X, Y or Z. */
    public UiNumber compactCaption() {
        caption.setText(label); getCell(caption).padRight(4); return this;
    }
    /** Refresh from an authoritative snapshot without interrupting typing or a drag, or firing a command. */
    public void value(double next) {
        if (editing() || value == next) { return; }
        value = normalized(next);
        showValue();
        syncSlider(next);
    }

    /** Give reusable controls stable names in their owner's namespace. */
    public UiNumber names(String id) {
        setName(id);
        caption.setName(id + "-caption");
        field.setName(id + "-field");
        slider.setName(id + "-slider");
        readout.setName(id + "-slider-value");
        popup.setName(id + "-popup");
        if (dial != null) { dial.setName(id + "-dial"); }
        return this;
    }
    public void dismiss() {
        if (held && getStage() != null) { getStage().cancelTouchFocus(caption); }
        popup.cancel();
    }
    @Override protected void setStage(Stage stage) {
        if (getStage() != null && getStage() != stage) { dismiss(); }
        super.setStage(stage);
    }
    public void close() { dismiss(); popup.remove(); }
    public static String format(double value) {
        return java.math.BigDecimal.valueOf(Math.round(value * 10000) / 10000.0).stripTrailingZeros().toPlainString();
    }
}
