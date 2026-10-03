/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The HUD over the board editor's 3D view and the lobby's map preview, whose sources have no game (M1 row 13). At the
 * top right the battle HUD's utilities that apply to a map: the Tactical View, the wireframe view, in the editor its
 * menu bar, Tools and 2D Editor, and the developer tuning panel. At the bottom the hovered or inspected hex's card
 * over a hint line: the editor's title or what the preview's measurement waits for, and the keys of the editor, of
 * Free Flight or of the preview's line of sight.
 */
final class GpuMapHud implements GpuBoardHud {
    /** The hint line's widest text, in stage units. */
    private static final float HINT_WIDTH = 760;
    /** The hex card's width, in stage units. */
    private static final float CARD_WIDTH = 320;
    /** The space between the hex card and the hint line. */
    private static final float CARD_GAP = 8;

    private final Stage stage;
    private final BoardSource source;
    private final BoardCamera camera;
    private final GpuBoardTuning tuningModel;
    private final GpuHudKit kit;
    private final GpuHudState state;
    private final GpuTuningPanel tuning;
    private final Table top = new Table();
    private final Table utilityRow = new Table();
    private final UiButton tactical;
    private final UiButton wireframe;
    private final UiButton menu;
    private final List<UiButton> utilities = new ArrayList<>();
    private final TextButton.TextButtonStyle wide;
    private final TextButton.TextButtonStyle narrow;
    private final Container<Table> menuSlot;
    private final GpuCommandTree tree;
    private final Table bottom = new Table();
    private final Table hint;
    private final List<Label> lines;
    /** The hovered or inspected hex's card (GpuMapSource.hexCard), over the hint line. */
    private final Table hexCard;
    /** The card's text as last laid out. */
    private String shownCard = "";
    private GpuHud.Metrics metrics;
    private float scale = 1;
    private boolean narrowShown;
    /** The hint's texts as last laid out. */
    private List<String> shownHint = List.of();

    /** {@code tuningModel} is the model the board view reads; {@code history} the view's (no game plays here). */
    GpuMapHud(BoardSource source, Skin skin, Batch batch, BoardCamera camera, GpuBoardTuning tuningModel,
          GpuPlaybackHistory history) {
        this.source = source;
        this.camera = camera;
        this.tuningModel = tuningModel;
        kit = new GpuHudKit(skin);
        UiKit ui = kit.ui;
        state = new GpuHudState(history);
        stage = new Stage(new ScreenViewport(), batch);

        tactical = utility(GpuUtilityBar.utility(ui, "tactical", "GpuBoard.hud.util.tactical", "utility-tactical"));
        onChange(tactical, () -> camera.setTactical(!camera.tactical(), null));
        wireframe = utility(GpuUtilityBar.utility(ui, "wireframe", "GpuBoard.hud.util.wireframe",
              "utility-wireframe"));
        onChange(wireframe, () -> tuningModel.setWireframe(!tuningModel.wireframe()));
        UiButton menuButton = null;
        if (source.isEditor()) {
            // Only the editor has menus (its menu bar), Tools and a 2D editor; a map preview has none of them.
            menuButton = utility(GpuUtilityBar.utility(ui, "menu", "GpuBoard.hud.menu.title", "utility-menu"));
            onChange(menuButton, () -> state.toggle(Dialog.MENU));
            UiButton tools = utility(GpuUtilityBar.utility(ui, "settings", "BoardEditor.tools", "editor-tools"));
            onChange(tools, source::showEditorTools);
            UiButton classic = utility(GpuUtilityBar.utility(ui, "map", "BoardEditor.edit2D", "editor-2d"));
            onChange(classic, source::showClassicEditor);
        }
        menu = menuButton;
        tuning = new GpuTuningPanel(kit, source, state, camera, tuningModel);
        utility((UiButton) tuning.button());
        wide = ui.skin.get("hud-utility", TextButton.TextButtonStyle.class);
        narrow = ui.skin.get("hud-utility-narrow", TextButton.TextButtonStyle.class);

        top.setFillParent(true);
        top.setTouchable(Touchable.childrenOnly);
        top.top().right().add(utilityRow);
        stage.addActor(top);
        Container<Actor> tuningSlot = new Container<>(tuning.actor()).fill();
        tuningSlot.setFillParent(true);
        tuningSlot.setTouchable(Touchable.childrenOnly);
        stage.addActor(tuningSlot);

        // Presses pass through the hint line to the board, as through the battle HUD's.
        hint = ui.panel();
        hint.setName("map-hint");
        hint.pad(8, 14, 9, 14);
        lines = List.of(hintLine(ui, "hud-medium", 12.5f, UiTheme.TEXT),
              hintLine(ui, "hud-small", 11.5f, UiTheme.MUTED));
        hexCard = ui.panel();
        hexCard.setName("map-hex");
        bottom.setFillParent(true);
        bottom.setTouchable(Touchable.disabled);
        bottom.bottom();
        stage.addActor(bottom);

        Table dialog = ui.dialog(Messages.getString("GpuBoard.hud.menu.title"), () -> state.dialog = Dialog.NONE);
        dialog.setName("menu-panel");
        dialog.setTouchable(Touchable.enabled);
        tree = new GpuCommandTree(ui, dialog);
        dialog.add(tree.body).grow().minHeight(0);
        menuSlot = new Container<>(dialog).fill();
        stage.addActor(menuSlot);
        metrics = GpuHud.Metrics.of(stage.getWidth(), stage.getHeight());
    }

    private UiButton utility(UiButton button) {
        utilityRow.add(button).padLeft(utilities.isEmpty() ? 0 : GpuUtilityBar.GAP);
        utilities.add(button);
        return button;
    }

    private static Label hintLine(UiKit ui, String font, float size, Color color) {
        Label line = ui.label("", font, size, color);
        line.setWrap(true);
        line.setAlignment(Align.center);
        return line;
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public void resize(int width, int height, float displayScale) {
        scale = displayScale;
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / displayScale);
        stage.getViewport().update(width, height, true);
        metrics = GpuHud.Metrics.of(stage.getWidth(), stage.getHeight());
        top.pad(metrics.gap(), 0, 0, metrics.gap());
        bottom.padBottom(metrics.gap());
        shownHint = List.of();
        shownCard = "";
    }

    @Override
    public void update(BoardSource.Frame frame, GpuHud.HudView view, GpuBoardWindow.DialogRequest dialog,
          BoardSource.UiPreferences preferences) {
        if (narrowShown != metrics.narrow()) {
            narrowShown = metrics.narrow();
            utilities.forEach(utility -> utility.setStyle(narrowShown ? narrow : wide));
        }
        tactical.pressed(camera.tactical());
        wireframe.pressed(tuningModel.wireframe()).setDisabled(!GpuWireframe.supported());
        if (menu != null) { menu.pressed(state.dialog == Dialog.MENU); }
        tuning.update(new GpuHud.Inputs(frame, view, dialog, preferences, metrics, List.of()));
        showMenu(frame.globalCommands());
        String keys = camera.firstPerson() ? Messages.getString("GpuBoard.firstPersonHelp")
              : Messages.getString(source.isEditor() ? "BoardEditor.edit3DHelp" : "GpuBoard.preview.measureHelp");
        showCard(frame.tooltip());
        showHint(List.of(source.phaseStatus().text(), keys));
    }

    /** The map's menus as the battle HUD's Menu panel lists a game's; choosing an item closes the panel. */
    private void showMenu(List<BoardScene.Command> commands) {
        boolean open = state.dialog == Dialog.MENU && !commands.isEmpty();
        tree.open(open);
        menuSlot.setVisible(open);
        if (!open) {
            return;
        }
        List<Object> rows = new ArrayList<>();
        tree.rows(commands, "", 0, rows);
        tree.show(rows, null, command -> {
            state.dialog = Dialog.NONE;
            command.action().run();
        });
        float width = Math.min(UiKit.DIALOG_WIDTH, metrics.width() - 2 * metrics.gap());
        float height = Math.min(menuSlot.getActor().getPrefHeight(), metrics.height() - UiKit.DIALOG_MARGIN);
        menuSlot.setBounds((metrics.width() - width) / 2, (metrics.height() - height) / 2, width, height);
    }

    /** The hint line's non-empty texts, one row each; laid out again only when a text changes. */
    private void showHint(List<String> texts) {
        if (texts.equals(shownHint)) {
            return;
        }
        shownHint = texts;
        hint.clearChildren();
        float width = Math.min(HINT_WIDTH, metrics.width() - 2 * metrics.gap()) - 28;
        for (int index = 0; index < texts.size(); index++) {
            if (!texts.get(index).isEmpty()) {
                lines.get(index).setText(texts.get(index));
                hint.add(lines.get(index)).width(width).row();
            }
        }
        layoutBottom();
    }

    /**
     * The hex card (the user's decision of 2026-10-03): the hex with its level and theme as a panel's header, a rule,
     * then each row's name with its detail on the right; laid out again only when its text changes.
     */
    private void showCard(String card) {
        if (card.equals(shownCard)) {
            return;
        }
        shownCard = card;
        hexCard.clearChildren();
        if (!card.isEmpty()) {
            UiKit ui = kit.ui;
            List<String> rows = List.of(card.split("\n"));
            String[] head = cells(rows.getFirst());
            hexCard.add(ui.header(head[0], head[1].isEmpty() ? null : head[1])).growX().row();
            hexCard.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
            Table list = new Table();
            list.pad(6, 14, 10, 14);
            for (String row : rows.subList(1, rows.size())) {
                String[] cells = cells(row);
                Label name = ui.label(cells[0], "hud-small", 12, UiTheme.TEXT);
                name.setWrap(true);
                list.add(name).growX().left();
                list.add(ui.label(cells[1], "hud-small", 11.5f, UiTheme.MUTED)).right().top().padLeft(12).row();
            }
            hexCard.add(list).growX();
        }
        layoutBottom();
    }

    /** A card row's name and detail, split at GpuMapSource's column; a row without one has an empty detail. */
    private static String[] cells(String row) {
        int column = row.indexOf(GpuMapSource.COLUMN);
        return column < 0 ? new String[] { row, "" }
              : new String[] { row.substring(0, column), row.substring(column + 1) };
    }

    /** The bottom stack: the hex card over the hint line, each only while it shows something. */
    private void layoutBottom() {
        bottom.clearChildren();
        if (hexCard.hasChildren()) {
            bottom.add(hexCard).width(Math.min(CARD_WIDTH, metrics.width() - 2 * metrics.gap())).padBottom(CARD_GAP)
                  .row();
        }
        if (hint.hasChildren()) {
            bottom.add(hint);
        }
    }

    @Override
    public void draw() {
        kit.update(Set.of());
        stage.getViewport().apply();
        stage.act(Math.min(Gdx.graphics.getDeltaTime(), .1f));
        stage.draw();
    }

    @Override
    public boolean hit(int x, int y) {
        Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
        return stage.hit(point.x, point.y, true) != null;
    }

    @Override
    public boolean dragsCamera(int x, int y) {
        return false;
    }

    @Override
    public boolean isTextEditing() {
        return stage.getKeyboardFocus() instanceof TextField;
    }

    /** Esc closes the open menu or tuning panel; the open menu takes its list keys. */
    @Override
    public boolean keyDown(int key, int awt, int modifiers) {
        if (state.dialog == Dialog.NONE) {
            return false;
        }
        if (key == Input.Keys.ESCAPE) {
            state.dialog = Dialog.NONE;
            return true;
        }
        return stage.keyDown(key);
    }

    @Override
    public boolean keyUp(int key, int awt) {
        return state.dialog != Dialog.NONE && stage.keyUp(key);
    }

    @Override
    public boolean keyTyped(char character) {
        return stage.keyTyped(character) || isTextEditing();
    }

    @Override
    public void focusLost() {
    }

    @Override
    public void boardPress() {
        stage.setKeyboardFocus(null);
    }

    @Override
    public float cameraLeft() {
        return 0;
    }

    /** The window's width beside the editor's Swing tools, which cover its right edge. */
    @Override
    public float cameraWidth() {
        return Math.max(1, metrics.width() * scale * (1 - Math.max(0, source.toolsInset())));
    }

    @Override
    public void dispose() {
        tuning.dispose();
        stage.dispose();
        kit.dispose();
    }
}
