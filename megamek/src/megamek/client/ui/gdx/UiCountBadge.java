/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;

/**
 * A count badge that stands on its own beside a title, rather than on a button's corner ({@link UiButton#badge}):
 * Token v0's {@link UiTheme#TOKEN_BADGE}, {@code text.onAccent} digits in the label's medium face at caption size on
 * {@code accent.primary}, {@code icon.sm} high and at least as wide, in a ring of {@code surface.panel}. Above
 * {@value #MAX_SHOWN} it reads "99+". A count of zero hides it and gives back its width, but it keeps its height, so the
 * row it sits in never jumps. Made by {@link UiKit#countBadge(int)}.
 */
public final class UiCountBadge extends Label {
    /** The most a count shows before it reads "99+". */
    public static final int MAX_SHOWN = 99;

    private int count = -1;

    UiCountBadge(Skin skin, int count) {
        super("", tokenStyle(skin));
        setAlignment(Align.center);
        count(count);
    }

    private static LabelStyle tokenStyle(Skin skin) {
        LabelStyle style = new LabelStyle(skin.get(UiTheme.TOKEN_BADGE, LabelStyle.class));
        style.background = skin.getDrawable(UiTheme.TOKEN_BADGE);
        style.fontColor = UiTheme.tokenColor("text.onAccent");
        return style;
    }

    /** Shows {@code value}, or hides the badge at zero. */
    public UiCountBadge count(int value) {
        int next = Math.max(0, value);
        if (next == count) {
            return this;
        }
        count = next;
        setText(count > MAX_SHOWN ? MAX_SHOWN + "+" : String.valueOf(count));
        setVisible(count > 0);
        invalidateHierarchy();
        return this;
    }

    public int count() {
        return count;
    }

    @Override
    public float getPrefWidth() {
        return count > 0 ? Math.max(getPrefHeight(), super.getPrefWidth()) : 0;
    }

    @Override
    public float getPrefHeight() {
        Drawable background = getStyle().background;
        return background == null ? super.getPrefHeight() : background.getMinHeight();
    }
}
