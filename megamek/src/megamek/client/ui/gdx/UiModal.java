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
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

/**
 * Window-sized modal layer. The owner positions its dialogs inside this group and calls {@link #open} when shown.
 * Empty space dims and blocks the background; a left click there dismisses it without reaching underlying widgets.
 * Child controls receive input first. Unhandled keys and scrolling stay here, and Escape dismisses the layer.
 * All modal layers on a stage share one dimming pass, drawn immediately behind the highest visible modal. Lower
 * dialogs stay visible beneath it without drawing another backdrop. The scene graph supplies the stacking order,
 * including nested layers, so hiding, removing or reordering a dialog automatically moves the backdrop.
 * Uses the kit's existing texture and owns no rendering resources.
 */
public final class UiModal extends Table {
    private static final Color SCRIM = UiTheme.rgba(13, 17, 18, .64f);
    private final UiKit ui;

    public UiModal(UiKit ui, Runnable dismiss) {
        this(ui, dismiss, true);
    }

    /** A modal may require an explicit answer instead of dismissing when its backdrop is clicked. */
    public UiModal(UiKit ui, Runnable dismiss, boolean dismissOnBackdrop) {
        this.ui = ui;
        setTransform(false);
        setTouchable(Touchable.enabled);
        setVisible(false);
        addListener(new ClickListener(Input.Buttons.LEFT) {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (dismissOnBackdrop && event.getTarget() == UiModal.this && hit(x, y, true) == UiModal.this) {
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
        if (stage != null && topmost(stage.getRoot()) == this) {
            Actor focused = stage.getKeyboardFocus();
            if (!inside(focused) || !focused.ascendantsVisible()) { stage.setKeyboardFocus(this); }
            if (!inside(stage.getScrollFocus())) { stage.setScrollFocus(this); }
        }
    }

    private boolean inside(Actor actor) {
        return actor != null && actor.isDescendantOf(this);
    }

    /** Reverse draw order, skipping hidden branches; no separate modal stack can drift from the displayed one. */
    private static UiModal topmost(Actor actor) {
        if (!actor.isVisible()) {
            return null;
        }
        if (actor instanceof Group group) {
            for (int index = group.getChildren().size - 1; index >= 0; index--) {
                UiModal modal = topmost(group.getChildren().get(index));
                if (modal != null) {
                    return modal;
                }
            }
        }
        return actor instanceof UiModal modal ? modal : null;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (getStage() != null && topmost(getStage().getRoot()) == this) {
            float color = batch.getPackedColor();
            ui.fill(batch, SCRIM, parentAlpha * getColor().a, getX(), getY(), getWidth(), getHeight());
            batch.setPackedColor(color);
        }
        super.draw(batch, parentAlpha);
    }
}
