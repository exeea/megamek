/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.Align;

/**
 * A filter chip ({@link UiTheme#TOKEN_FILTER_CHIP}): a chip that toggles a filter on and off, control.sm high. At rest it
 * is the neutral chip with text.secondary; on, it fills in the accent's pressed tint, its text turns text.primary and a
 * check shows in its leading padding. The chip keeps the check's room at both ends, so its text stands centred on the
 * whole chip, checked or not, and turning it on never resizes it or moves the text. {@link #sameWidth(List)} gives a
 * group the width of its widest text plus both reserves, so the chips line up with every text centred. Made by
 * {@link UiKit#filterChip(String)}.
 */
public final class UiFilterChip extends Button {
    private final UiKit.Icon check;
    private final Label text;
    private final Color on;
    private final Color off;
    private final float iconSize;
    private final float side;
    private float width;
    private boolean shownChecked;

    /**
     * The chip pads {@code vertical} above and below; at each end it keeps {@link #reserve} ({@code side}, the check's
     * {@code iconSize} and {@code iconGap}), the check standing {@code side} in from the leading edge.
     */
    UiFilterChip(Skin skin, UiKit.Icon check, Label text, float iconSize, float vertical, float side, float iconGap) {
        super(skin, UiTheme.TOKEN_FILTER_CHIP);
        this.check = check;
        this.text = text;
        this.iconSize = iconSize;
        this.side = side;
        on = UiTheme.tokenColor("text.primary");
        off = UiTheme.tokenColor("text.secondary");
        setProgrammaticChangeEvents(false);
        float reserve = reserve(iconSize, side, iconGap);
        pad(vertical, reserve, vertical, reserve);
        text.setAlignment(Align.center);
        add(text).height(iconSize).expandX().fillX();
        // The check is no cell: it stands in the leading reserve, so the text keeps the chip's centre
        addActor(check);
        check.setSize(iconSize, iconSize);
        shownChecked = !isChecked();
        show();
    }

    /** What a chip keeps at each end: the check's room and its spaces, {@code side + iconSize + iconGap}. */
    static float reserve(float iconSize, float side, float iconGap) {
        return side + iconSize + iconGap;
    }

    @Override
    public void layout() {
        super.layout();
        check.setBounds(side, Math.round((getHeight() - iconSize) / 2), iconSize, iconSize);
    }

    /** The chip's text label, for tests. */
    Label label() {
        return text;
    }

    /** The check, for tests. */
    Actor checkMark() {
        return check;
    }

    /** Gives {@code chips} the width of the widest, so a group lines up in columns. */
    public static void sameWidth(List<UiFilterChip> chips) {
        float widest = 0;
        for (UiFilterChip chip : chips) {
            chip.width = 0;
            widest = Math.max(widest, chip.getPrefWidth());
        }
        for (UiFilterChip chip : chips) {
            chip.width = widest;
            chip.invalidateHierarchy();
        }
    }

    /** The chip's text. */
    public String text() {
        return text.getText().toString();
    }

    @Override
    public float getPrefWidth() {
        return Math.max(width, super.getPrefWidth());
    }

    @Override
    public float getMinWidth() {
        return Math.max(width, super.getMinWidth());
    }

    private void show() {
        if (shownChecked == isChecked()) {
            return;
        }
        shownChecked = isChecked();
        check.setVisible(shownChecked);
        Label.LabelStyle style = text.getStyle();
        style.fontColor = shownChecked ? on : off;
        text.setStyle(style);
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        show();
        super.draw(batch, parentAlpha);
    }
}
