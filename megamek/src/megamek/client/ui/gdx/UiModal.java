/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

/**
 * Window-sized modal layer. The owner positions its dialogs inside this group and calls {@link #open} when shown.
 * Empty space dims and blocks the background; a left click there dismisses it without reaching underlying widgets.
 * Child controls receive input first. Unhandled keys and scrolling stay here, and Escape dismisses the layer.
 * Uses the kit's existing texture and owns no rendering resources.
 */
public final class UiModal extends Group {
    private static final Color SCRIM = UiTheme.rgba(13, 17, 18, .64f);
    private final UiKit ui;

    public UiModal(UiKit ui, Runnable dismiss) {
        this.ui = ui;
        setTransform(false);
        setTouchable(Touchable.enabled);
        setVisible(false);
        addListener(new ClickListener(Input.Buttons.LEFT) {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (event.getTarget() == UiModal.this && hit(x, y, true) == UiModal.this) {
                    dismiss.run();
                }
            }
        });
        addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                return true;
            }

            @Override
            public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
                return true;
            }

            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (!event.isHandled() && keycode == Input.Keys.ESCAPE) {
                    dismiss.run();
                }
                return true;
            }

            @Override
            public boolean keyTyped(InputEvent event, char character) {
                return true;
            }
        });
    }

    /** Shows or hides the layer, clearing focus left inside it on close. Call {@link #focus} after updating children. */
    public void open(boolean open) {
        Stage stage = getStage();
        if (open && !isVisible() && stage != null) {
            stage.cancelTouchFocus();
        }
        setVisible(open);
        if (!open && stage != null) {
            if (inside(stage.getKeyboardFocus())) { stage.setKeyboardFocus(null); }
            if (inside(stage.getScrollFocus())) { stage.setScrollFocus(null); }
        }
    }

    /** Keeps keyboard and wheel input inside the visible modal, preserving focus on its own controls. */
    public void focus() {
        Stage stage = getStage();
        if (isVisible() && stage != null) {
            Actor focused = stage.getKeyboardFocus();
            if (!inside(focused) || !focused.ascendantsVisible()) { stage.setKeyboardFocus(this); }
            if (!inside(stage.getScrollFocus())) { stage.setScrollFocus(this); }
        }
    }

    private boolean inside(Actor actor) {
        return actor != null && actor.isDescendantOf(this);
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        float color = batch.getPackedColor();
        ui.fill(batch, SCRIM, parentAlpha * getColor().a, getX(), getY(), getWidth(), getHeight());
        batch.setPackedColor(color);
        super.draw(batch, parentAlpha);
    }
}
