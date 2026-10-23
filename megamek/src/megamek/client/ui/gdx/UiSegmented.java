/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/**
 * The design-system segmented control (docs/design-system/tokens.md, Segmented control): two to five short, mutually
 * exclusive options in one raised frame, for switching a view or a mode in place. The sections share the width
 * equally; the selected one has a violet wash and edge, like a selected toggle, and the dividers beside it give way.
 */
public class UiSegmented extends UiOptionBar {
    private final Drawable frame;
    private final Drawable frameDisabled;
    private final Drawable on;
    private final Drawable onDisabled;
    private final Drawable over;
    private final Drawable down;
    private final Drawable focus;

    UiSegmented(UiKit kit, boolean compact, String... labels) {
        super(kit, compact, labels);
        frame = kit.skin.getDrawable("seg-frame");
        frameDisabled = kit.skin.getDrawable("seg-frame-disabled");
        on = kit.skin.getDrawable("seg-on");
        onDisabled = kit.skin.getDrawable("seg-on-disabled");
        over = kit.skin.getDrawable("seg-over");
        down = kit.skin.getDrawable("seg-down");
        focus = kit.skin.getDrawable("seg-focus");
        // The frame edge is the bar's padding and a divider's width separates the sections
        pad(border);
        defaults().space(border);
        for (Option option : options()) {
            add(option).uniformX().fillX().height(barHeight() - 2 * border);
        }
    }

    @Override
    protected void drawBackground(Batch batch, float parentAlpha, float x, float y) {
        Color tint = getColor();
        batch.setColor(tint.r, tint.g, tint.b, tint.a * parentAlpha);
        (isDisabled() ? frameDisabled : frame).draw(batch, x, y, getWidth(), getHeight());
        int selected = getSelectedIndex();
        for (int i = 1; i < count(); i++) {
            if (i != selected && i - 1 != selected) {
                Option option = option(i);
                line(batch, colors.hairline, parentAlpha, x + option.getX() - border, y + option.getY(), border,
                      option.getHeight());
            }
        }
        for (int i = 0; i < count(); i++) {
            Option option = option(i);
            float ox = x + option.getX();
            float oy = y + option.getY();
            float width = option.getWidth();
            float height = option.getHeight();
            // The selected face covers the frame edge and the dividers around the section
            if (i == selected) {
                (isDisabled() ? onDisabled : on).draw(batch, ox - border, oy - border, width + 2 * border,
                      height + 2 * border);
            }
            if (!isDisabled() && (option.isOver() || option.isPressed())) {
                (option.isPressed() ? down : over).draw(batch, ox, oy, width, height);
            }
            if (ringed(i)) {
                focus.draw(batch, ox - border, oy - border, width + 2 * border, height + 2 * border);
            }
        }
    }
}
