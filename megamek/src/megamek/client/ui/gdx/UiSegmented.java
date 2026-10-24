/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.design.DesignTokens;

/**
 * The design-system segmented control (docs/design-system/tokens.md, Segmented control): two to five short, mutually
 * exclusive options for switching a view or a mode in place. The segments share the width equally and stand apart, xs
 * between them, each a raised box with its own edge; the selected one has a mint wash and edge, like a selected toggle.
 */
public class UiSegmented extends UiOptionBar {
    private final Drawable item;
    private final Drawable itemDisabled;
    private final Drawable on;
    private final Drawable onDisabled;
    private final Drawable over;
    private final Drawable down;
    private final Drawable focus;

    UiSegmented(UiKit kit, boolean compact, String... labels) {
        super(kit, compact, labels);
        item = kit.skin.getDrawable("seg-item");
        itemDisabled = kit.skin.getDrawable("seg-item-disabled");
        on = kit.skin.getDrawable("seg-on");
        onDisabled = kit.skin.getDrawable("seg-on-disabled");
        over = kit.skin.getDrawable("seg-over");
        down = kit.skin.getDrawable("seg-down");
        focus = kit.skin.getDrawable("seg-focus");
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
                face = disabled ? onDisabled : on;
            } else if (disabled) {
                face = itemDisabled;
            } else if (option.isPressed()) {
                face = down;
            } else {
                face = option.isOver() ? over : item;
            }
            face.draw(batch, ox, oy, width, height);
            if (ringed(i)) {
                focus.draw(batch, ox, oy, width, height);
            }
        }
    }
}
