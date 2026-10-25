/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.design.DesignTokens;

/**
 * A hud-v3 button (.b and its variants; {@link UiKit#button} builds them). It shows the pressed state its view sets and
 * never toggles itself, so a click only fires a ChangeEvent. Icons take the text color of the state; sub-labels and
 * details are muted, darker on a filled face. A top-right utility can carry a count badge and an unread dot.
 */
public class UiButton extends TextButton {
    /** A sub-label on a disabled button (.b:disabled small). */
    private static final Color DISABLED_DETAIL = Color.valueOf("4A5452");
    /** Scratch corners for {@link #ringFits}, which runs on the render thread only. */
    private static final Vector2 LOW = new Vector2();
    private static final Vector2 HIGH = new Vector2();
    /** The icons, drawn in the text color of the button's state. */
    public final List<Actor> icons = new ArrayList<>();
    /** The sub-labels and details, muted, darker on a filled face. */
    public final List<Label> details = new ArrayList<>();
    /** Icons in a color of their own rather than the text's; a token style's disabled color while disabled. */
    public final Map<Actor, Color> iconColors = new HashMap<>();
    private final UiKit kit;
    /** A Token v0 button ({@code button-*} styles), whose badge and dot take the token look. */
    private final boolean token;
    private boolean pressed, hovered, held, focused;
    private Label badge;
    private Image dot;
    private Actor trailing;
    private float trailingSize, trailingWidth;

    /** An empty button in the hud style {@code style} of the kit's skin; {@link UiKit} adds its parts. */
    public UiButton(UiKit kit, String style) {
        super("", kit.skin, style);
        this.kit = kit;
        token = style.startsWith("button-");
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

    /** Shows the hover look while another view hovers the same item, without an event. */
    public UiButton hovered(boolean value) {
        hovered = value;
        return this;
    }

    @Override
    public boolean isOver() {
        return hovered || super.isOver();
    }

    /** Shows the held look of a pointer pressing the button, without input, as a gallery does. */
    public UiButton held(boolean value) {
        held = value;
        return this;
    }

    @Override
    public boolean isPressed() {
        return held || super.isPressed();
    }

    /**
     * Shows the focus ring while a view moves its own keyboard selection here; a button with the stage's keyboard
     * focus shows it anyway. Only the token button styles have a ring.
     */
    public UiButton focused(boolean value) {
        focused = value;
        return this;
    }

    /** The same as {@link #pressed}: a view's own change fires no event. */
    @Override
    public void setChecked(boolean value) {
        pressed(value);
    }

    /**
     * The count badge at the top-right corner (.b.util .badge); null hides it. A token button's badge is Token v0's:
     * text.onAccent digits on accent.primary in a ring of the panel's surface, its centre xs inside the corner, and
     * counts past 99 read 99+.
     */
    public UiButton badge(String text) {
        if (badge == null && text != null) {
            badge = token ? new Label(text, new Label.LabelStyle(kit.skin.get(UiTheme.TOKEN_BADGE,
                  Label.LabelStyle.class))) : kit.label(text, "hud-name", 10, Color.valueOf("1A1A1A"));
            Label.LabelStyle style = badge.getStyle();
            style.background = kit.skin.getDrawable(token ? UiTheme.TOKEN_BADGE : "badge");
            if (token) {
                style.fontColor = UiTheme.tokenColor("text.onAccent");
            }
            badge.setStyle(style);
            badge.setAlignment(Align.center);
            addActor(badge);
        }
        if (badge != null) {
            badge.setVisible(text != null);
            badge.setText(token && text != null && text.matches("\\d{3,}") ? "99+" : text);
            invalidate();
        }
        return this;
    }

    /** The unread dot (.b.util .dotb); a token button's is the md accent.primary dot. */
    public UiButton dot(boolean shown) {
        if (dot == null && shown) {
            dot = new Image(kit.skin.getDrawable(token ? "token-dot" : "dot"));
            addActor(dot);
        }
        if (dot != null) {
            dot.setVisible(shown);
        }
        return this;
    }

    /**
     * An independent action inside the row's frame, without changing its label or selection hit target: {@code size}
     * high and as wide as an action that lays itself out prefers, at least {@code size} (a row of several buttons).
     */
    public UiButton trailingAction(Actor action, float size) {
        if (trailing != null) { trailing.remove(); }
        trailing = action; trailingSize = size;
        trailingWidth = action instanceof Layout layout ? Math.max(size, layout.getPrefWidth()) : size;
        padRight(trailingWidth + 6); addActor(action); invalidate();
        return this;
    }

    @Override
    public void layout() {
        super.layout();
        if (trailing != null) { trailing.setBounds(getWidth() - trailingWidth - 3, (getHeight() - trailingSize) / 2, trailingWidth, trailingSize); }
        if (badge != null && token) {
            // The badge's box holds its ring, so its centre is the ring's centre
            float inset = DesignTokens.shipped().space().get("xs");
            float height = badge.getStyle().background.getMinHeight();
            badge.setSize(Math.max(height, badge.getPrefWidth()), height);
            badge.setPosition(getWidth() - inset - badge.getWidth() / 2, getHeight() - inset - height / 2);
        } else if (badge != null) {
            badge.setSize(Math.max(17, badge.getPrefWidth()), 17);
            badge.setPosition(getWidth() + 6 - badge.getWidth(), getHeight() + 6 - badge.getHeight());
        }
        if (dot != null && token) {
            float inset = DesignTokens.shipped().space().get("xs");
            float size = dot.getDrawable().getMinWidth();
            dot.setBounds(getWidth() - inset - size / 2, getHeight() - inset - size / 2, size, size);
        } else if (dot != null) {
            dot.setBounds(getWidth() - 13, getHeight() - 12, 7, 7);
        }
    }

    /** A token style's checked face while disabled; Scene2D's disabled face would hide that the button is on. */
    @Override
    protected Drawable getBackgroundDrawable() {
        if (isDisabled() && isChecked() && getStyle() instanceof UiTheme.TokenButtonStyle style
              && style.checkedDisabled != null) {
            return style.checkedDisabled;
        }
        return super.getBackgroundDrawable();
    }

    @Override
    protected Color getFontColor() {
        if (isDisabled() && isChecked() && getStyle() instanceof UiTheme.TokenButtonStyle style
              && style.checkedDisabledFontColor != null) {
            return style.checkedDisabledFontColor;
        }
        return super.getFontColor();
    }

    /**
     * Whether a ring {@code room} outside {@code actor}'s bounds shows whole: no clipping ancestor (a scroll pane or a
     * clipping table) cuts it off.
     */
    public static boolean ringFits(Actor actor, float room) {
        for (Group parent = actor.getParent(); parent != null; parent = parent.getParent()) {
            if (!(parent instanceof ScrollPane) && !(parent instanceof Table table && table.getClip())) {
                continue;
            }
            actor.localToAscendantCoordinates(parent, LOW.set(-room, -room));
            actor.localToAscendantCoordinates(parent, HIGH.set(actor.getWidth() + room, actor.getHeight() + room));
            if (LOW.x < 0 || LOW.y < 0 || HIGH.x > parent.getWidth() || HIGH.y > parent.getHeight()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        Color text = getFontColor();
        if (text != null) {
            icons.forEach(icon -> icon.setColor(text));
        }
        UiTheme.TokenButtonStyle token = getStyle() instanceof UiTheme.TokenButtonStyle style ? style : null;
        for (Map.Entry<Actor, Color> entry : iconColors.entrySet()) {
            entry.getKey().setColor(isDisabled() && token != null ? token.disabledFontColor : entry.getValue());
        }
        Color detail;
        if (token != null && token.detailFontColor != null) {
            detail = isDisabled() ? token.disabledFontColor : token.detailFontColor;
        } else {
            detail = isDisabled() ? DISABLED_DETAIL
                  : UiTheme.FILL_INK.equals(text) ? UiTheme.FILL_MUTED : UiTheme.MUTED;
        }
        details.forEach(label -> label.setColor(detail));
        super.draw(batch, parentAlpha);
        if (!isDisabled() && (focused || hasKeyboardFocus()) && token != null && token.focusOuter != null) {
            Color color = getColor();
            batch.setColor(color.r, color.g, color.b, color.a * parentAlpha);
            // A cream face takes the ring a gap outside it; where that would be clipped, two lines inside
            boolean cream = token.creamUp || (token.creamChecked && isChecked());
            float room = UiTheme.CHOICE_RING;
            if (cream && token.focusRing != null && ringFits(this, room)) {
                token.focusRing.draw(batch, getX() - room, getY() - room, getWidth() + 2 * room,
                      getHeight() + 2 * room);
            } else {
                boolean inverse = cream && token.creamFocusOuter != null;
                Drawable outer = inverse ? token.creamFocusOuter : token.focusOuter;
                Drawable inner = inverse ? token.creamFocusInner : token.focusInner;
                outer.draw(batch, getX(), getY(), getWidth(), getHeight());
                if (inner != null) {
                    inner.draw(batch, getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2);
                }
            }
        }
    }
}
