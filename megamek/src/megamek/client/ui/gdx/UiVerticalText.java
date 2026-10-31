/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;

/**
 * A label turned a quarter clockwise, read from the top down with its glyphs rising to the right, such as a rail's
 * title. It sits at the top of its area, centred across it, and lets clicks through to its parent.
 */
public final class UiVerticalText extends WidgetGroup {
    private final Label label;
    private final Group turned = new Group();

    public UiVerticalText(Label label) {
        this.label = label;
        turned.setTransform(true);
        turned.setRotation(-90);
        turned.addActor(label);
        addActor(turned);
        setTouchable(Touchable.disabled);
    }

    public Label label() {
        return label;
    }

    @Override
    public float getPrefWidth() {
        return label.getPrefHeight();
    }

    @Override
    public float getPrefHeight() {
        return label.getPrefWidth();
    }

    @Override
    public void layout() {
        label.setSize(label.getPrefWidth(), label.getPrefHeight());
        label.validate();
        // Turned about its origin, the label's top edge lands on the right and its start at the top
        turned.setPosition(Math.round((getWidth() - label.getHeight()) / 2), getHeight());
    }
}
