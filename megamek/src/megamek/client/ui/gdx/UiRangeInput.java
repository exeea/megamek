/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import megamek.client.ui.design.DesignTokens;

/**
 * A whole-number span: two compact token inputs, the lowest and the highest, with an en dash between. Each takes
 * digits only and shows its placeholder (such as "Min" and "Max") while empty; an empty end is open. Typing in either
 * fires one ChangeEvent from the span; {@link #set} fires none. Made by {@link UiKit#rangeInput(String, String)}.
 */
public final class UiRangeInput extends Table {
    /** The most digits an end takes. */
    public static final int MAX_DIGITS = 6;

    private final UiTextField low;
    private final UiTextField high;

    UiRangeInput(UiKit kit, String minText, String maxText) {
        DesignTokens tokens = DesignTokens.shipped();
        float width = tokens.size().get("control.lg") * 2;
        low = end(kit, minText);
        high = end(kit, maxText);
        left();
        // Each end keeps its width alone, and the two share the width of a form column that fills
        add(low).prefWidth(width).minWidth(width / 2).growX().uniformX();
        add(kit.typeLabel("\u2013", "body", "text.muted")).padLeft(tokens.space().get("xs"))
              .padRight(tokens.space().get("xs"));
        add(high).prefWidth(width).minWidth(width / 2).growX().uniformX();
    }

    private UiTextField end(UiKit kit, String placeholder) {
        UiTextField field = kit.input("", true);
        field.setMessageText(placeholder);
        field.setMaxLength(MAX_DIGITS);
        field.setTextFieldFilter(new TextField.TextFieldFilter.DigitsOnlyFilter());
        field.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (actor == field) {
                    event.stop();
                    fire(new ChangeEvent());
                }
            }
        });
        return field;
    }

    /** The lowest value, or null while that end is empty. */
    public Integer min() {
        return parse(low.getText());
    }

    /** The highest value, or null while that end is empty. */
    public Integer max() {
        return parse(high.getText());
    }

    /** Shows {@code min} and {@code max}, null for an empty end, without a ChangeEvent. */
    public UiRangeInput set(Integer min, Integer max) {
        low.setText(min == null ? "" : String.valueOf(min));
        high.setText(max == null ? "" : String.valueOf(max));
        return this;
    }

    /** The lowest end's field; a test types in it. */
    public UiTextField minField() {
        return low;
    }

    /** The highest end's field. */
    public UiTextField maxField() {
        return high;
    }

    /** {@code text} as a whole number, or null when it is empty or not one. */
    public static Integer parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
