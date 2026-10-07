/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.Align;

/**
 * A hud-v3 button (.b and its variants; {@link UiKit#button} builds them). It shows the pressed state its view sets and
 * never toggles itself, so a click only fires a ChangeEvent. Icons take the text color of the state; sub-labels and
 * details are muted, darker on a filled face. A top-right utility can carry a count badge and an unread dot.
 */
public class UiButton extends TextButton {
    /** A sub-label on a disabled button (.b:disabled small). */
    private static final Color DISABLED_DETAIL = Color.valueOf("4A5452");
    /** The icons, drawn in the text color of the button's state. */
    public final List<Actor> icons = new ArrayList<>();
    /** The sub-labels and details, muted, darker on a filled face. */
    public final List<Label> details = new ArrayList<>();
    private final UiKit kit;
    private boolean pressed;
    private Label badge;
    private Image dot;
    private Actor trailing;
    private float trailingSize;

    /** An empty button in the hud style {@code style} of the kit's skin; {@link UiKit} adds its parts. */
    public UiButton(UiKit kit, String style) {
        super("", kit.skin, style);
        this.kit = kit;
        clearChildren();
    }

    /** Scene2D's click toggle still flips Button's own field; the look follows only {@link #pressed}. */
    @Override
    public boolean isChecked() {
        return pressed;
    }

    /** Shows the button pressed or released, without an event. */
    public UiButton pressed(boolean value) {
        pressed = value;
        return this;
    }

    /** The same as {@link #pressed}: a view's own change fires no event. */
    @Override
    public void setChecked(boolean value) {
        pressed(value);
    }

    /** The count badge at the top-right corner (.b.util .badge); null hides it. */
    public UiButton badge(String text) {
        if (badge == null && text != null) {
            badge = kit.label(text, "hud-name", 10, Color.valueOf("1A1A1A"));
            Label.LabelStyle style = badge.getStyle();
            style.background = kit.skin.getDrawable("badge");
            badge.setStyle(style);
            badge.setAlignment(Align.center);
            addActor(badge);
        }
        if (badge != null) {
            badge.setVisible(text != null);
            badge.setText(text);
            invalidate();
        }
        return this;
    }

    /** The unread dot (.b.util .dotb). */
    public UiButton dot(boolean shown) {
        if (dot == null && shown) {
            dot = new Image(kit.skin.getDrawable("dot"));
            addActor(dot);
        }
        if (dot != null) {
            dot.setVisible(shown);
        }
        return this;
    }

    /** An independent action inside the row's frame, without changing its label or selection hit target. */
    public UiButton trailingAction(Actor action, float size) {
        if (trailing != null) { trailing.remove(); }
        trailing = action; trailingSize = size;
        padRight(size + 6); addActor(action); invalidate();
        return this;
    }

    @Override
    public void layout() {
        super.layout();
        if (trailing != null) { trailing.setBounds(getWidth() - trailingSize - 3, (getHeight() - trailingSize) / 2, trailingSize, trailingSize); }
        if (badge != null) {
            badge.setSize(Math.max(17, badge.getPrefWidth()), 17);
            badge.setPosition(getWidth() + 6 - badge.getWidth(), getHeight() + 6 - badge.getHeight());
        }
        if (dot != null) {
            dot.setBounds(getWidth() - 13, getHeight() - 12, 7, 7);
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        Color text = getFontColor();
        if (text != null) {
            icons.forEach(icon -> icon.setColor(text));
        }
        Color detail = isDisabled() ? DISABLED_DETAIL
              : UiTheme.FILL_INK.equals(text) ? UiTheme.FILL_MUTED : UiTheme.MUTED;
        details.forEach(label -> label.setColor(detail));
        super.draw(batch, parentAlpha);
    }
}
