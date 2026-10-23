/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;

/**
 * The hovered hex's card, shown as a tooltip: once the pointer rests on a hex of the board for {@link #DELAY} seconds,
 * the hex and the lines its menu's header shows ({@link GpuContextMenu#hexLines}), in small text on a faint plate
 * beside the pointer. It goes when the pointer leaves that hex or the board, at a press, and while a menu or a dialog
 * is open. It never takes input.
 */
final class GpuHexTip implements GpuHud.Component {
    /** Seconds the pointer rests on a hex before its card shows: Swing's default tooltip delay. */
    static final float DELAY = .75f;
    /** How far, in stage units, the pointer may drift while it rests. */
    private static final float DRIFT = 3;
    /** The card's corner right of and below the pointer, clear of the cursor. */
    private static final float OFFSET_X = 14, OFFSET_Y = 20;
    /** Lines longer than this wrap, as the HUD's widget tooltips do. */
    private static final float WRAP = 340;

    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final Table root = new Table();
    private final Table card = new Table();
    private final Label head;
    private final Label body;
    private final Cell<Label> headCell;
    private final Cell<Label> bodyCell;
    private final Vector2 pointer = new Vector2();
    private final Vector2 rest = new Vector2();
    /** The hex the pointer rests on, or null. */
    private Coords restHex;
    private float rested;
    private String shown = "";

    GpuHexTip(GpuHudKit kit, GpuHudState state, GpuContextMenu menu) {
        this.state = state;
        this.menu = menu;
        UiKit ui = kit.ui;
        root.setName("hex-tip");
        root.setTouchable(Touchable.disabled);
        card.setBackground(ui.skin.getDrawable("tip"));
        card.setVisible(false);
        head = ui.label("", "hud-small", 11.5f, UiTheme.TEXT);
        body = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        headCell = card.add(head).left();
        card.row();
        bodyCell = card.add(body).left();
        root.addActor(card);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        Coords hex = inputs.view().hovered();
        Stage stage = root.getStage();
        if (hex == null || stage == null || Gdx.input.isTouched() || menu.isOpen() || inputs.dialog() != null
              || state.dialog != GpuHudState.Dialog.NONE) {
            restHex = null;
            hide();
            return;
        }
        stage.screenToStageCoordinates(pointer.set(Gdx.input.getX(), Gdx.input.getY()));
        if (!hex.equals(restHex) || !card.isVisible() && pointer.dst(rest) > DRIFT) {
            // A new rest: on another hex, or the pointer still moving before the card showed.
            if (!hex.equals(restHex)) {
                hide();
            }
            restHex = hex;
            rest.set(pointer);
            rested = 0;
            return;
        }
        rested += Gdx.graphics.getDeltaTime();
        String lines = rested < DELAY ? null : GpuContextMenu.hexLines(inputs.frame(), hex);
        if (lines != null) {
            show(text("GpuBoard.hud.context.hex", hex.getBoardNum()) + GpuContextMenu.SEPARATOR + lines, stage);
        }
    }

    /** Shows the card's text, its first line in the head, at the resting point; laid out again only when it changes. */
    private void show(String text, Stage stage) {
        if (!text.equals(shown)) {
            shown = text;
            int newline = text.indexOf('\n');
            head.setText(newline < 0 ? text : text.substring(0, newline));
            body.setText(newline < 0 ? "" : text.substring(newline + 1));
            bodyCell.setActor(newline < 0 ? null : body);
            float width = Math.min(WRAP, Math.max(natural(head), newline < 0 ? 0 : natural(body)));
            fit(headCell, head, width);
            fit(bodyCell, body, width);
            card.pack();
        }
        float x = rest.x + OFFSET_X;
        float y = rest.y - OFFSET_Y - card.getHeight();
        if (x + card.getWidth() > stage.getWidth()) { x = rest.x - OFFSET_X - card.getWidth(); }
        if (y < 0) { y = rest.y + OFFSET_Y; }
        card.setPosition(Math.max(0, x), Math.min(y, stage.getHeight() - card.getHeight()));
        card.setVisible(true);
    }

    private void hide() {
        card.setVisible(false);
    }

    /** A label's width on one line. */
    private static float natural(Label label) {
        label.setWrap(false);
        return label.getPrefWidth();
    }

    /** A label wrapped at {@code width}, so a long line breaks instead of widening the card. */
    private static void fit(Cell<Label> cell, Label label, float width) {
        label.setWrap(true);
        label.setWidth(width);
        cell.width(width);
    }
}
