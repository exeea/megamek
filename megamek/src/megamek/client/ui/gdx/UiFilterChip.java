/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;

/**
 * A filter chip ({@link UiTheme#TOKEN_FILTER_CHIP}): a chip that toggles a filter on and off, control.sm high. At rest it is the
 * neutral chip with text.secondary; on, it fills in the accent's pressed tint, its text turns text.primary and a check
 * shows in the room the chip always keeps for it, so turning it on never resizes it. {@link #sameWidth(List)} gives a
 * group one width, so the chips line up. Made by {@link UiKit#filterChip(String)}.
 */
public final class UiFilterChip extends Button {
    private final UiKit.Icon check;
    private final Label text;
    private final Color on;
    private final Color off;
    private float width;
    private boolean shownChecked;

    /**
     * The chip pads {@code vertical} above and below, {@code side} before the check and after the text, and
     * {@code iconGap} between them.
     */
    UiFilterChip(Skin skin, UiKit.Icon check, Label text, float iconSize, float vertical, float side, float iconGap,
          float textGap) {
        super(skin, UiTheme.TOKEN_FILTER_CHIP);
        this.check = check;
        this.text = text;
        on = UiTheme.tokenColor("text.primary");
        off = UiTheme.tokenColor("text.secondary");
        setProgrammaticChangeEvents(false);
        pad(vertical, side, vertical, textGap);
        add(check).size(iconSize).padRight(iconGap);
        add(text).height(iconSize).expandX().left();
        shownChecked = !isChecked();
        show();
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
