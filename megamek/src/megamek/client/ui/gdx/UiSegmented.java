/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.design.DesignTokens;

/**
 * The design-system segmented control (docs/design-system/tokens.md, Segmented control): two to five short, mutually
 * exclusive options for switching a view or a mode in place. The segments share the width equally and stand apart, xs
 * between them, each a raised box with its own edge; the selected one has the cream face of a checked toggle, faint
 * but still cream while disabled, and its focus ring sits a gap outside it, or two lines inside where it would be
 * clipped.
 */
public class UiSegmented extends UiOptionBar {
    private final Drawable item;
    private final Drawable itemDisabled;
    private final Drawable on;
    private final Drawable onOver;
    private final Drawable onDown;
    private final Drawable onDisabled;
    private final Drawable over;
    private final Drawable down;
    private final Drawable focus;
    private final Drawable onFocus;
    private final Drawable onFocusOuter;
    private final Drawable onFocusInner;

    UiSegmented(UiKit kit, boolean compact, String... labels) {
        super(kit, compact, labels);
        item = kit.skin.getDrawable("seg-item");
        itemDisabled = kit.skin.getDrawable("seg-item-disabled");
        on = kit.skin.getDrawable("seg-on");
        onOver = kit.skin.getDrawable("seg-on-over");
        onDown = kit.skin.getDrawable("seg-on-down");
        onDisabled = kit.skin.getDrawable("seg-on-disabled");
        over = kit.skin.getDrawable("seg-over");
        down = kit.skin.getDrawable("seg-down");
        focus = kit.skin.getDrawable("seg-focus");
        onFocus = kit.skin.getDrawable("seg-on-focus");
        onFocusOuter = kit.skin.getDrawable("seg-on-focus-outer");
        onFocusInner = kit.skin.getDrawable("seg-on-focus-inner");
        defaults().space(DesignTokens.shipped().space().get("xs"));
        for (Option option : options()) {
            add(option).uniformX().fillX().height(barHeight());
        }
    }

    @Override
    protected void drawBackground(Batch batch, float parentAlpha, float x, float y) {
        Color tint = getColor();
        batch.setColor(tint.r, tint.g, tint.b, tint.a * parentAlpha);
        int selected = getSelectedIndex();
        boolean disabled = isDisabled();
        for (int i = 0; i < count(); i++) {
            Option option = option(i);
            float ox = x + option.getX();
            float oy = y + option.getY();
            float width = option.getWidth();
            float height = option.getHeight();
            Drawable face;
            if (i == selected) {
                face = disabled ? onDisabled : option.isPressed() ? onDown : option.isOver() ? onOver : on;
            } else if (disabled) {
                face = itemDisabled;
            } else if (option.isPressed()) {
                face = down;
            } else {
                face = option.isOver() ? over : item;
            }
            face.draw(batch, ox, oy, width, height);
            if (ringed(i) && i != selected) {
                focus.draw(batch, ox, oy, width, height);
            }
        }
        // After every face, so a neighbour never covers the part of the ring outside the selected segment
        if (selected >= 0 && selected < count() && ringed(selected)) {
            Option option = option(selected);
            float ox = x + option.getX();
            float oy = y + option.getY();
            float room = UiTheme.CHOICE_RING;
            if (UiButton.ringFits(option, room)) {
                onFocus.draw(batch, ox - room, oy - room, option.getWidth() + 2 * room, option.getHeight() + 2 * room);
            } else {
                onFocusOuter.draw(batch, ox, oy, option.getWidth(), option.getHeight());
                onFocusInner.draw(batch, ox + border, oy + border, option.getWidth() - 2 * border,
                      option.getHeight() - 2 * border);
            }
        }
    }

    @Override
    Color selectedText() {
        return colors.onText;
    }
}
