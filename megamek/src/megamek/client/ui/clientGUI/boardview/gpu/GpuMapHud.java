/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
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
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;

/**
 * Native map workspace: the standalone editor uses UIKit panels; previews use a corner hex card and native LOS.
 * Both presentations share the camera utilities and developer tuning panel.
 */
final class GpuMapHud implements GpuBoardHud {
    /** The hex card's width, in stage units. */
    private static final float CARD_WIDTH = 320;

    private final Stage stage;
    private final GpuBoardEditor editor;
    private final BoardSource source;
    private final BoardCamera camera;
    private final GpuBoardTuning tuningModel;
    private final GpuHudKit kit;
    private final GpuHudState state;
    private final GpuTuningPanel tuning;
    private final Container<Actor> tuningSlot;
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
    private final GpuLosPanel losPanel;
    private final UiPopover contextMenu;
    private long boardGeneration;
    /** The hovered or inspected hex's card in the bottom-right corner. */
    private final Table hexCard;
    /** The card's text as last laid out. */
    private String shownCard = "";
    private GpuHud.Metrics metrics;
    private float scale = 1;
    private BoardScene scene;
    private boolean narrowShown;

    /** {@code tuningModel} is the model the board view reads; {@code history} the view's (no game plays here). */
    GpuMapHud(BoardSource source, Skin skin, Batch batch, BoardCamera camera, GpuBoardTuning tuningModel,
          GpuPlaybackHistory history, java.util.function.Supplier<GpuTerrain> terrain) {
        this.source = source;
        this.camera = camera;
        this.tuningModel = tuningModel;
        kit = new GpuHudKit(skin);
        UiKit ui = kit.ui;
        state = new GpuHudState(history);
        stage = new Stage(new ScreenViewport(), batch);

        cameraButton("top", "hex", "Top view", () -> camera.setIsometric(false));
        cameraButton("isometric", "layers", "Isometric view", () -> camera.setIsometric(true));
        cameraButton("fit", "expand", "Fit board", () -> {
            if (scene != null) { camera.fit(scene, framingLeft(), framingWidth(), framingBottom(), framingTop()); }
        });
        tactical = utility(GpuUtilityBar.utility(ui, "tactical", "GpuBoard.hud.util.tactical", "utility-tactical"));
        onChange(tactical, () -> camera.setTactical(!camera.tactical(), scene));
        wireframe = utility(GpuUtilityBar.utility(ui, "wireframe", "GpuBoard.hud.util.wireframe",
              "utility-wireframe"));
        onChange(wireframe, () -> tuningModel.setWireframe(!tuningModel.wireframe()));
        UiButton menuButton = null;
        menu = menuButton;
        tuning = new GpuTuningPanel(kit, source, state, camera, tuningModel);
        utility((UiButton) tuning.button());
        wide = ui.skin.get(source.isEditor() ? "hud-utility-small" : "hud-utility", TextButton.TextButtonStyle.class);
        narrow = ui.skin.get(source.isEditor() ? "hud-utility-small" : "hud-utility-narrow", TextButton.TextButtonStyle.class);

        top.setFillParent(true);
        utilityRow.setName("map-utilities");
        top.setTouchable(Touchable.childrenOnly);
        top.top().right().add(utilityRow);
        stage.addActor(top);
        tuningSlot = new Container<>(tuning.actor()).fill();
        tuningSlot.setFillParent(true);
        tuningSlot.setTouchable(Touchable.childrenOnly);
        stage.addActor(tuningSlot);

        hexCard = ui.panel();
        hexCard.setName("map-hex");
        bottom.setFillParent(true);
        bottom.setTouchable(Touchable.disabled);
        bottom.bottom().right();
        stage.addActor(bottom);
        losPanel = new GpuLosPanel(ui, stage, source, camera);
        stage.addActor(losPanel.actor());
        contextMenu = new UiPopover(ui);
        contextMenu.setName("map-context-menu");
        stage.addActor(contextMenu);

        Table dialog = ui.dialog(Messages.getString("GpuBoard.hud.menu.title"), () -> state.dialog = Dialog.NONE);
        dialog.setName("menu-panel");
        dialog.setTouchable(Touchable.enabled);
        tree = new GpuCommandTree(ui, dialog);
        dialog.add(tree.body).grow().minHeight(0);
        menuSlot = new Container<>(dialog).fill();
        stage.addActor(menuSlot);
        metrics = GpuHud.Metrics.of(stage.getWidth(), stage.getHeight());
        editor = source.isEditor() ? new GpuBoardEditor(source, ui, stage, terrain) : null;
        if (editor != null) {
            UiButton settings = utility(ui.button("hud-utility", "settings", "Settings", null));
            settings.setName("editor-settings-button");
            onChange(settings, () -> { state.dialog = Dialog.NONE; editor.openSettings(); });
            onChange(tuning.button(), editor::closeSettings);
        }
        top.toFront(); tuningSlot.toFront();
    }

    private UiButton utility(UiButton button) {
        if (source.isEditor()) {
            button.setStyle(kit.ui.skin.get("hud-utility-small", TextButton.TextButtonStyle.class));
            button.clearChildren();
            button.icons.forEach(icon -> button.add(icon).size(14));
            button.add(button.getLabel()).padLeft(5);
        }
        float gap = utilityRow.getCells().isEmpty() ? 0 : GpuUtilityBar.GAP;
        var cell = utilityRow.add(button).padLeft(gap);
        if (source.isEditor()) { cell.height(GpuBoardEditor.TOOLBAR_HEIGHT); }
        utilities.add(button);
        return button;
    }

    private void cameraButton(String name, String icon, String caption, Runnable action) {
        UiButton button = kit.ui.button("hud-utility-small", icon, "", null);
        button.setName("map-view-" + name);
        button.clearChildren(); button.pad(0);
        button.icons.forEach(image -> button.add(image).size(19));
        float gap = utilityRow.getCells().isEmpty() ? 0 : GpuUtilityBar.GAP;
        utilityRow.add(button).size(GpuBoardEditor.TOOLBAR_HEIGHT).padLeft(gap);
        kit.ui.tip(button).getActor().setText(caption);
        onChange(button, () -> { stage.setKeyboardFocus(null); action.run(); });
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public void resize(int width, int height, float displayScale) {
        // Keep the complete editing workspace usable when a large desktop UI scale meets a smaller window.
        scale = editor == null ? displayScale : Math.min(displayScale, Math.min(width / 1280f, height / 800f));
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
        metrics = GpuHud.Metrics.of(stage.getWidth(), stage.getHeight());
        top.pad(metrics.gap(), 0, 0, metrics.gap());
        bottom.padBottom(metrics.gap()).padRight(metrics.gap());
        losPanel.actor().setSize(metrics.width(), metrics.height());
        shownCard = "";
        layoutEditor();
    }

    private void layoutEditor() {
        if (editor != null) { editor.layout(metrics.width(), metrics.height(), utilityRow.getPrefWidth(), utilityRow.getPrefHeight()); }
    }

    @Override
    public void update(BoardSource.Frame frame, GpuHud.HudView view, GpuBoardWindow.DialogRequest dialog,
          BoardSource.UiPreferences preferences) {
        scene = frame.scene();
        if (boardGeneration != frame.boardGeneration()) { contextMenu.cancel(); }
        boardGeneration = frame.boardGeneration();
        // Only an open menu pins the hex card; every dismissal path returns it to the latest hover.
        if (!contextMenu.isVisible() && frame.context() != null) { source.inspect(null); }
        if (narrowShown != metrics.narrow()) {
            narrowShown = metrics.narrow();
            utilities.forEach(utility -> utility.setStyle(narrowShown ? narrow : wide));
            layoutEditor();
        }
        tactical.pressed(camera.tactical());
        wireframe.pressed(tuningModel.wireframe()).setDisabled(!GpuWireframe.supported());
        if (menu != null) { menu.pressed(state.dialog == Dialog.MENU); }
        tuning.update(new GpuHud.Inputs(frame, view, dialog, preferences, metrics, List.of()));
        if (state.dialog == Dialog.TUNING) { tuningSlot.toFront(); }
        showMenu(frame.globalCommands());
        List<com.badlogic.gdx.math.Rectangle> occupied = new ArrayList<>();
        if (editor == null) {
            showCard(frame.tooltip());
            bottom.validate();
            top.validate();
            var bounds = hexCard.localToStageCoordinates(new Vector2());
            if (hexCard.hasChildren()) {
                occupied.add(new com.badlogic.gdx.math.Rectangle(bounds.x, bounds.y, hexCard.getWidth(), hexCard.getHeight()));
            }
        } else {
            editor.update(frame.boardGeneration());
            // Keep the shared ruler in the editor's board area, clear of its tools, inspector and hex section.
            float left = GpuBoardEditor.LIBRARY_WIDTH + 2 * GAP;
            float right = metrics.width() - GpuBoardEditor.INSPECTOR_WIDTH - 2 * GAP;
            occupied.add(new com.badlogic.gdx.math.Rectangle(0, 0, left, metrics.height()));
            occupied.add(new com.badlogic.gdx.math.Rectangle(right, 0, metrics.width() - right, metrics.height()));
            occupied.add(new com.badlogic.gdx.math.Rectangle(left, 0, right - left, editor.bottomInset()));
            occupied.add(new com.badlogic.gdx.math.Rectangle(left, metrics.height() - editor.topInset(),
                  right - left, editor.topInset()));
        }
        top.validate();
        var bounds = utilityRow.localToStageCoordinates(new Vector2());
        occupied.add(new com.badlogic.gdx.math.Rectangle(bounds.x, bounds.y, utilityRow.getWidth(), utilityRow.getHeight()));
        losPanel.update(new GpuHud.Inputs(frame, view, dialog, preferences, metrics, occupied));
    }

    boolean measuring() { return losPanel.pending(); }

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

    /** Keep the inspected hex in the bottom-right corner, clear of the board centre. */
    private void layoutBottom() {
        bottom.clearChildren();
        if (hexCard.hasChildren()) {
            bottom.add(hexCard).width(Math.min(CARD_WIDTH, metrics.width() - 2 * metrics.gap())).row();
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
        if (isTextEditing()) { return stage.keyDown(key); }
        if (contextMenu.isVisible()) {
            if (key == Input.Keys.ESCAPE) { contextMenu.cancel(); return true; }
            return stage.keyDown(key);
        }
        if (key == Input.Keys.ESCAPE && editor != null && editor.escape()) { return true; }
        if (state.dialog == Dialog.NONE) {
            return key == Input.Keys.ESCAPE && losPanel.cancel();
        }
        if (key == Input.Keys.ESCAPE) {
            state.dialog = Dialog.NONE;
            return true;
        }
        return stage.keyDown(key);
    }

    @Override
    public boolean keyUp(int key, int awt) {
        return (isTextEditing() || contextMenu.isVisible() || state.dialog != Dialog.NONE) && stage.keyUp(key);
    }

    @Override
    public boolean keyTyped(char character) {
        return stage.keyTyped(character) || isTextEditing();
    }

    @Override
    public void focusLost() {
        stage.cancelTouchFocus();
        losPanel.dismiss();
        contextMenu.cancel();
        stage.setKeyboardFocus(null);
    }

    @Override
    public void boardPress() {
        stage.setKeyboardFocus(null);
        contextMenu.cancel();
    }

    /** Map actions are anchored to the clicked hex; preview and editor share view controls and the native ruler. */
    void boardMenu(Coords coords, float pointedZ, int x, int y) {
        if (scene == null || coords == null || scene.tile(coords) == null) { return; }
        source.inspect(coords);
        UiMenuList list = new UiMenuList(kit.ui);
        if (editor != null) { editor.contextTools(list, contextMenu::cancel); }
        long generation = boardGeneration;
        Integer height = Float.isNaN(pointedZ) ? null : GpuLosResult.pointedHeight(scene.tile(coords).elevation(), pointedZ);
        list.item("Line of Sight", "Alt + click", null, false, true, () -> {
            contextMenu.cancel();
            source.changeRuler(generation, model -> { model.clear(); model.addPoint(coords, height); });
        }).setName("map-line-of-sight");
        if (editor != null) {
            editor.contextActions(list, contextMenu::cancel, () -> { state.dialog = Dialog.NONE; editor.openSettings(); });
        }
        contextMenu.header("Hex " + coords.getBoardNum(), null).content(list);
        Vector2 at = stage.screenToStageCoordinates(new Vector2(x, y));
        contextMenu.showAt(at.x, at.y);
    }

    @Override
    public float framingLeft() { return editor == null ? 0 : (GpuBoardEditor.LIBRARY_WIDTH + 2 * GAP) * scale; }

    @Override
    public float framingWidth() {
        return Math.max(1, metrics.width() * scale - framingLeft()
              - (editor == null ? 0 : (GpuBoardEditor.INSPECTOR_WIDTH + 2 * GAP) * scale));
    }

    @Override public float framingBottom() { return editor == null ? 0 : editor.bottomInset() * scale; }
    @Override public float framingTop() { return editor == null ? 0 : editor.topInset() * scale; }

    @Override
    public void dispose() {
        if (editor != null) { editor.dispose(); }
        tuning.dispose();
        losPanel.dispose();
        contextMenu.cancel(); contextMenu.remove();
        stage.dispose();
        kit.dispose();
    }
}
