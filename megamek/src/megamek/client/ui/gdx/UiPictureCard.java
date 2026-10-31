/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import megamek.client.ui.design.DesignTokens;

/**
 * A card that shows a picture and opens something when clicked, such as the lobby's map: the token card
 * ({@link UiTheme#TOKEN_PICTURE_CARD}) whose edge takes border.strong under the pointer. The picture fits inside it,
 * and the card is as high as its width times the picture's aspect (height over width), so it keeps the picture's shape
 * at any column width. Without a picture it shows a muted line instead, such as "Drawing the map…".
 */
public class UiPictureCard extends Button {
    private final Image picture = new Image();
    private final Label placeholder;
    private float aspect;
    private float maxHeight = Float.MAX_VALUE;
    private float laidOutWidth = -1;

    /**
     * @param text   the line shown without a picture
     * @param aspect the card's height over its width without a picture
     */
    public UiPictureCard(UiKit kit, String text, float aspect) {
        super(kit.skin, UiTheme.TOKEN_PICTURE_CARD);
        this.aspect = aspect;
        picture.setScaling(Scaling.fit);
        picture.setAlign(Align.center);
        placeholder = kit.typeLabel(text, "small", "text.muted");
        placeholder.setAlignment(Align.center);
        placeholder.setWrap(true);
        Stack stack = new Stack(picture, placeholder);
        float inset = DesignTokens.shipped().space().get("xs");
        add(stack).grow().pad(inset);
    }

    /**
     * Shows {@code drawable} at {@code aspect} (its height over its width), or {@code text} without one; a null
     * {@code text} keeps the line.
     */
    public void picture(Drawable drawable, float aspect, String text) {
        picture.setDrawable(drawable);
        placeholder.setVisible(drawable == null);
        if (text != null) {
            placeholder.setText(text);
        }
        if (aspect != this.aspect) {
            this.aspect = aspect;
            invalidateHierarchy();
        }
    }

    /** Keeps the card no higher than {@code height}, the picture fitting inside, so a wide card stays in proportion. */
    public UiPictureCard maxHeight(float height) {
        maxHeight = height;
        invalidateHierarchy();
        return this;
    }

    /** The card's height over its width. */
    public float aspect() {
        return aspect;
    }

    @Override
    public float getPrefWidth() {
        return 0;
    }

    @Override
    public float getPrefHeight() {
        return getWidth() > 0 ? Math.min(getWidth() * aspect, maxHeight) : super.getPrefHeight();
    }

    @Override
    public void layout() {
        super.layout();
        if (getWidth() != laidOutWidth) {
            // The height follows the width, which the parent gives only as it lays this card out
            laidOutWidth = getWidth();
            invalidateHierarchy();
        }
    }
}
