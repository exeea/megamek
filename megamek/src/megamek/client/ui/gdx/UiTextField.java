/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextArea;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/**
 * A token text input ({@link UiKit#input} builds it): the {@code input} styles of {@link UiTheme}, with the strong edge
 * under the pointer that scene2d's TextField lacks, a status edge and icon, and a read-only look. Focus wins over hover,
 * and a status edge over both hover and rest; the focus ring replaces every edge, as in Swing.
 */
public class UiTextField extends TextField {
    /** A field's status: its edge color and trailing icon, with the words in its form field's message. */
    public enum Status {
        NONE(""), ERROR("-error"), WARNING("-warning");

        private final String variant;

        Status(String variant) {
            this.variant = variant;
        }
    }

    private final Skin skin;
    private final Look look;
    private Status status = Status.NONE;
    private boolean readOnly;

    /** A field in the token input style; compact is control.sm high, otherwise control.md. */
    public UiTextField(String text, Skin skin, boolean compact) {
        this(text, skin, "", compact);
    }

    UiTextField(String text, Skin skin, String variant, boolean compact) {
        super(text, skin, "input" + variant + (compact ? UiTheme.COMPACT : ""));
        this.skin = skin;
        look = new Look(this, variant, compact);
    }

    /** Shows the hover look without a pointer, as a gallery does. */
    public UiTextField hovered(boolean value) {
        look.frozenHover = value;
        return this;
    }

    /** Shows the focus ring without the keyboard focus, as a gallery does. */
    public UiTextField focused(boolean value) {
        look.frozenFocus = value;
        return this;
    }

    /** Colors the edge and adds the status icon; a read-only field shows no status. */
    public UiTextField status(Status value) {
        status = value;
        restyle();
        return this;
    }

    public Status status() {
        return status;
    }

    /**
     * Shows the text without a fill, behind a subtle edge, and refuses edits. scene2d has no read-only field, so this
     * disables it: its text can not be selected either.
     */
    public UiTextField readOnly(boolean value) {
        readOnly = value;
        setDisabled(value);
        restyle();
        return this;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /** Keeps the hover while the pointer is over {@code group}, a stack that draws parts over this field. */
    void hoverRoot(Actor group) {
        look.hoverRoot(group);
    }

    private void restyle() {
        String state = readOnly ? "-readonly" : status.variant;
        setStyle(skin.get("input" + look.variant + state + (look.compact ? UiTheme.COMPACT : ""),
              TextFieldStyle.class));
    }

    @Override
    protected Drawable getBackgroundDrawable() {
        return look.background(super.getBackgroundDrawable());
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        super.draw(batch, parentAlpha);
        if (!isDisabled() && getStyle() instanceof UiTheme.TokenFieldStyle style && style.icon != null) {
            // The icon leads the space its style reserves at the trailing edge, after the text's own gap
            float size = UiTheme.FIELD_ICON;
            float x = getX() + getWidth() - getBackgroundDrawable().getRightWidth() + UiTheme.FIELD_ICON_GAP;
            Color previous = batch.getColor().cpy();
            batch.setColor(style.iconColor.r, style.iconColor.g, style.iconColor.b, getColor().a * parentAlpha);
            style.icon.draw(batch, x, getY() + (getHeight() - size) / 2, size, size);
            batch.setColor(previous);
        }
    }

    /**
     * A token text area: the {@code input} style at its wrapped height, with the same hover and read-only looks as a
     * field; a status shows in its form field's message.
     */
    public static class Area extends TextArea {
        private final Skin skin;
        private final Look look;

        public Area(String text, Skin skin) {
            super(text, skin, "input");
            this.skin = skin;
            look = new Look(this, "", false);
        }

        /** Shows the hover look without a pointer, as a gallery does. */
        public Area hovered(boolean value) {
            look.frozenHover = value;
            return this;
        }

        /** As {@link UiTextField#readOnly}. */
        public Area readOnly(boolean value) {
            setDisabled(value);
            setStyle(skin.get(value ? "input-readonly" : "input", TextFieldStyle.class));
            return this;
        }

        @Override
        protected Drawable getBackgroundDrawable() {
            return look.background(super.getBackgroundDrawable());
        }
    }

    /** The pointer and frozen states a token input adds to scene2d's background choice. */
    private static final class Look {
        private final TextField field;
        private final String variant;
        private final boolean compact;
        private final InputListener listener;
        private Actor root;
        private boolean pointer;
        private boolean frozenHover;
        private boolean frozenFocus;

        private Look(TextField field, String variant, boolean compact) {
            this.field = field;
            this.variant = variant;
            this.compact = compact;
            root = field;
            listener = new InputListener() {
                @Override
                public void enter(InputEvent event, float x, float y, int pointerIndex, Actor fromActor) {
                    if (pointerIndex == -1) {
                        pointer = true;
                    }
                }

                @Override
                public void exit(InputEvent event, float x, float y, int pointerIndex, Actor toActor) {
                    if (pointerIndex == -1 && (toActor == null || !toActor.isDescendantOf(root))) {
                        pointer = false;
                    }
                }
            };
            field.addListener(listener);
        }

        /** Tracks the pointer over {@code group}, so the parts drawn over the field keep its hover. */
        private void hoverRoot(Actor group) {
            root.removeListener(listener);
            root = group;
            group.addListener(listener);
        }

        /** Scene2d's choice (disabled, focused or rest), with the frozen focus and the hover edge added. */
        private Drawable background(Drawable chosen) {
            if (field.isDisabled() || !(field.getStyle() instanceof UiTheme.TokenFieldStyle style)) {
                return chosen;
            }
            if (frozenFocus || field.hasKeyboardFocus()) {
                return style.focusedBackground;
            }
            return (pointer || frozenHover) && style.over != null ? style.over : chosen;
        }
    }
}
