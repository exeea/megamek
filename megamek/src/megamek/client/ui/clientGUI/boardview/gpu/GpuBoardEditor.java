/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardHud.GAP;
import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoiceGrid;
import megamek.client.ui.gdx.UiChoiceStrip;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiNumber;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiMenuList;
import megamek.common.Configuration;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;

/** Native UIKit workspace. All document changes are commands to the EDT-owned editor session. */
final class GpuBoardEditor implements com.badlogic.gdx.utils.Disposable {
    static final float LIBRARY_WIDTH = 180, INSPECTOR_WIDTH = 280, TOOLBAR_HEIGHT = 34;
    private static final int PAGE_SIZE = 18;
    private final BoardSource source;
    private final UiKit ui;
    private final Stage stage;
    private final Supplier<GpuTerrain> terrain;
    private final BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
    private final Table toolbar, library, inspector, detail = new Table(), categoryRows = new Table(), stripPanel;
    private final Label status, documentName;
    private final Table documentStatus = new Table();
    private final Table inspectorBody = new Table();
    private final ScrollPane inspectorScroll, libraryScroll;
    private final UiKit.SearchField librarySearch, themeFind;
    private TextField searchField;
    private final AssetManager thumbnails = new AssetManager();
    private final GpuEditorModelPreviews models = new GpuEditorModelPreviews();
    private final GpuEditorTerrainPreviews samples = new GpuEditorTerrainPreviews();
    private record Preview(Image image, String file) { }
    private record Entry(String key, String label, String thumbnail, String type, String owner) { }
    private final List<Preview> previews = new ArrayList<>();
    private final List<UiButton> tools = new ArrayList<>();
    private final List<UiButton> categories = new ArrayList<>();
    private final UiButton undo, redo;
    private final Label stripTitle;
    private final Table contents = new Table(), sectionPanel, settingsLayer = new Table(), settingsBody = new Table();
    private final Table themeLayer = new Table(), themeGallery;
    private final UiChoiceGrid themeCards;
    private final UiChoiceStrip libraryStrip = new UiChoiceStrip(124, 116, 6);
    private final ScrollPane themeScroll;
    private final TextField themeSearch;
    private Consumer<String> themeCommit;
    private String selectedTheme = "", themeQuery = "", libraryTheme = "";
    private final List<UiButton> libraryCards = new ArrayList<>();
    private final Label hexTitle;
    private final UiPopover choices;
    private final GpuHexSection section;
    private final List<UiNumber> numbers = new ArrayList<>(), settingsNumbers = new ArrayList<>();
    private int newWidth = 16, newHeight = 17;
    private final Table brushPanel = new Table();
    private final List<UiNumber> brushNumbers = new ArrayList<>();
    private final List<List<Actor>> brushRows = new ArrayList<>();
    private float brushLayoutWidth = -1;
    private String brushState = "", libraryGroup = "";
    private final Table libraryNavigation = new Table();
    private final Table designLayer = new Table(), designDialog;
    private final UiChoiceGrid designCards;
    private final ScrollPane designScroll;
    private final UiKit.SearchField designSearch;
    private final Table designPages = new Table();
    private String designOwner = "", designQuery = "";
    private int designPage;
    private float width, height, utilityWidth, utilityHeight;
    private String category = "Terrain", shownSelection = "";
    private BoardEditorSession.Snapshot snapshot;
    private long generation;
    private String libraryOwner = "ground", query = "";
    private boolean objects;
    private String detailKey = "";
    private Actor revealContent;
    /** One render-thread snapshot of installed placement geometry, shared by the section and its labels. */
    private List<GpuTerrain.EditorObject> installedObjects = List.of();

    GpuBoardEditor(BoardSource source, UiKit ui, Stage stage, Supplier<GpuTerrain> terrain) {
        this.source = source; this.ui = ui; this.stage = stage; this.terrain = terrain;
        themeCards = ui.choiceGrid(148, 6);
        choices = new UiPopover(ui); stage.addActor(choices);
        thumbnails.setErrorListener((asset, failure) -> previews.stream().filter(preview -> preview.file().equals(asset.fileName))
              .forEach(preview -> preview.image().setUserObject("No preview")));
        toolbar = ui.panel(); toolbar.setName("editor-toolbar"); toolbar.pad(4);
        for (BoardEditorSession.Tool tool : BoardEditorSession.Tool.values()) {
            UiButton button = button(tool.name(), () -> send(Action.TOOL, tool.name()));
            toolbar.add(button).padRight(3); tools.add(button);
        }
        undo = button("Undo", () -> send(Action.UNDO)); redo = button("Redo", () -> send(Action.REDO));
        toolbar.add(undo).padLeft(8); toolbar.add(redo).padLeft(3);
        toolbar.add(button("Open", () -> send(Action.OPEN))).padLeft(8);
        toolbar.add(button("Save", () -> send(Action.SAVE))).padLeft(3);
        toolbar.add(button("Save as", () -> send(Action.SAVE_AS))).padLeft(3);
        stage.addActor(toolbar);

        library = ui.panel(); library.setName("editor-library"); library.defaults().growX().minWidth(0);
        library.add(ui.header("Assets", null)).row();
        categoryRows.top(); categoryRows.defaults().growX().minWidth(0).pad(2, 6, 2, 6);
        library.add(ui.scrollList(categoryRows)).grow().minSize(0); stage.addActor(library);
        stripPanel = ui.panel(); stripPanel.setName("editor-asset-strip"); stripPanel.pad(6); stripPanel.defaults().growX().minWidth(0);
        stripTitle = ui.label("Ground", "hud-title", 14, UiTheme.TEXT); stripTitle.setEllipsis(true);
        Table stripHeader = new Table(); stripHeader.add(stripTitle).growX().minWidth(0).left();
        librarySearch = ui.search("Find a choice…"); searchField = librarySearch.field; searchField.setName("editor-search");
        onChange(searchField, () -> { query = searchField.getText().toLowerCase(Locale.ROOT); showLibrary(); });
        stripHeader.add(librarySearch).width(210).minWidth(0); stripPanel.add(stripHeader).row();
        ui.panel(brushPanel); brushPanel.pad(4); brushPanel.setName("editor-brush"); stage.addActor(brushPanel);
        stripPanel.add(libraryNavigation).row();
        Table stripViewport = libraryStrip.viewport(ui);
        libraryScroll = libraryStrip.scroll(); libraryScroll.setName("editor-choice-scroll");
        stripPanel.add(stripViewport).height(126).minWidth(0).row();
        stage.addActor(stripPanel);
        showCategories();

        inspector = ui.panel(); inspector.setName("editor-inspector");
        inspectorBody.top(); inspectorBody.defaults().growX().minWidth(0);
        hexTitle = ui.label("HEX INSPECTOR", "hud-title", 16, UiTheme.TEXT);
        inspectorBody.add(hexTitle).pad(12, 12, 6, 12).row();
        Table contentsHead = new Table(); contentsHead.add(ui.caption("Contents")).growX().left();
        contentsHead.add(button("Copy", () -> send(Action.COPY))); contentsHead.add(button("Paste", () -> send(Action.PASTE))).padLeft(3);
        inspectorBody.add(contentsHead).pad(4, 10, 4, 10).row();
        contents.setName("editor-contents");
        inspectorBody.add(contents).pad(0, 6, 6, 6).row();
        inspectorBody.add(new Image(ui.skin.getDrawable("rule"))).height(1).row();
        inspectorBody.add(detail).row();
        inspectorScroll = ui.scrollList(inspectorBody); inspectorScroll.setName("editor-inspector-scroll");
        inspector.add(inspectorScroll).grow().minSize(0);
        documentName = ui.label("", "hud-small", 12, UiTheme.TEXT); documentName.setEllipsis(true);
        documentName.setName("editor-document-name");
        status = ui.label("", "hud-small", 10, UiTheme.MUTED); status.setEllipsis(true);
        documentStatus.setName("editor-document-status"); documentStatus.setTouchable(Touchable.disabled);
        documentStatus.pad(3, 0, 3, 0);
        documentStatus.add(documentName).growX().minWidth(0).left().row();
        documentStatus.add(status).growX().minWidth(0).left();
        stage.addActor(inspector);
        stage.addActor(documentStatus);
        section = new GpuHexSection(source, terrain, ui);
        sectionPanel = new Table(); sectionPanel.setName("editor-section-panel");
        UiButton reveal = button("Reveal selected", () -> { });
        reveal.setName("editor-section-reveal");
        onChange(reveal, () -> { reveal.pressed(!reveal.isChecked()); section.reveal(reveal.isChecked()); });
        UiButton side = button("From east", () -> { });
        onChange(side, () -> { side.pressed(!side.isChecked()); section.east(!side.isChecked()); side.setText(side.isChecked() ? "FROM SOUTH" : "FROM EAST"); });
        sectionPanel.add(side).growX().minWidth(0); sectionPanel.add(reveal).growX().minWidth(0).row();
        sectionPanel.add(section).colspan(2).growX().minWidth(0).height(180).row();
        Label hint = ui.label("Drag a surface or its mint height line", "hud-small", 10.5f, UiTheme.MUTED);
        hint.setEllipsis(true); sectionPanel.add(hint).colspan(2).growX().minWidth(0).pad(3, 4, 8, 4).row();
        inspectorBody.add(ui.caption("Hex section")).pad(8).row();
        inspectorBody.add(sectionPanel).pad(0, 6, 6, 6).row();
        designLayer.setFillParent(true); designLayer.setName("editor-design-gallery");
        designLayer.setBackground(ui.skin.newDrawable("white", UiTheme.alpha(UiTheme.PANEL, .85f)));
        designLayer.setTouchable(Touchable.enabled); designLayer.setVisible(false);
        designDialog = ui.dialog("Change selected terrain design", this::closeDesigns);
        designDialog.setBackground(ui.skin.getDrawable("panel-pop"));
        designSearch = ui.search("Find a design…");
        onChange(designSearch.field, () -> { designQuery = designSearch.field.getText().toLowerCase(Locale.ROOT); designPage = 0; showDesigns(); });
        designDialog.add(designSearch).growX().row();
        designCards = ui.choiceGrid(140, 6); designScroll = ui.scrollList(designCards);
        designDialog.add(designScroll).growX().row(); designDialog.add(designPages).growX().row();
        designLayer.add(designDialog); stage.addActor(designLayer);
        settingsLayer.setFillParent(true); settingsLayer.setName("editor-settings");
        settingsLayer.setBackground(ui.skin.newDrawable("white", UiTheme.alpha(UiTheme.PANEL, .75f)));
        settingsLayer.setTouchable(Touchable.enabled); settingsLayer.setVisible(false);
        Table dialog = ui.dialog("Board settings", this::closeSettings); dialog.setBackground(ui.skin.getDrawable("panel-pop"));
        dialog.add(ui.scrollList(settingsBody)).growX().maxHeight(480);
        settingsLayer.add(dialog).width(480); stage.addActor(settingsLayer);
        themeLayer.setFillParent(true); themeLayer.setName("editor-theme-gallery");
        themeLayer.setBackground(ui.skin.newDrawable("white", UiTheme.alpha(UiTheme.PANEL, .85f)));
        themeLayer.setTouchable(Touchable.enabled); themeLayer.setVisible(false);
        themeGallery = ui.dialog("Choose a terrain theme", this::closeThemes);
        themeGallery.setBackground(ui.skin.getDrawable("panel-pop"));
        themeFind = ui.search("Find a theme…"); themeSearch = themeFind.field; themeSearch.setName("editor-theme-search");
        onChange(themeSearch, () -> { themeQuery = themeSearch.getText().toLowerCase(Locale.ROOT); showThemes(); });
        themeGallery.add(themeFind).growX().row();
        themeScroll = ui.scrollList(themeCards); themeScroll.setName("editor-theme-scroll");
        themeGallery.add(themeScroll).growX().pad(0, 6, 0, 6).row();
        themeLayer.add(themeGallery); stage.addActor(themeLayer);
        showLibrary();
    }

    private UiButton button(String label, Runnable action) {
        UiButton button = ui.button("hud-mini", null, label, null);
        onChange(button, () -> { stage.setKeyboardFocus(null); action.run(); }); return button;
    }
    private UiButton removeButton(String label, Runnable action) {
        UiButton button = ui.button(label.isEmpty() ? "hud-delete-icon" : "hud-danger", label.isEmpty() ? "close" : null, UiTheme.upper(label), null);
        if (label.isEmpty()) { button.pad(2); }
        onChange(button, () -> { stage.setKeyboardFocus(null); action.run(); });
        button.addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int mouseButton) {
                event.stop(); return false;
            }
        });
        button.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) { event.stop(); }
        });
        return button;
    }

    private void contentRow(UiButton select, int height, boolean indent, Runnable remove) {
        if (remove != null) {
            select.trailingAction(removeButton("", remove), 26);
        }
        contents.add(select).growX().minWidth(0).height(height).pad(2, indent ? 18 : 2, 2, 2).row();
    }
    private void send(Action action) { send(action, "", ""); }
    private void send(Action action, String value) { send(action, "", value); }
    private void send(Action action, String target, String value) { source.editorCommand(new Command(action, target, value), generation); }

    void contextTools(UiMenuList menu, Runnable close) {
        for (var tool : BoardEditorSession.Tool.values()) {
            menu.item(tool.name(), "", snapshot != null && snapshot.tool() == tool, false, true,
                  () -> { close.run(); send(Action.TOOL, tool.name()); }).setName("editor-context-" + tool.name().toLowerCase(Locale.ROOT));
        }
        menu.separator();
    }

    void contextActions(UiMenuList menu, Runnable close, Runnable settings) {
        menu.separator();
        menu.item("Undo", "Ctrl + Z", null, false, snapshot != null && snapshot.canUndo(),
              () -> { close.run(); send(Action.UNDO); }).setName("editor-context-undo");
        menu.item("Redo", "Ctrl + Y", null, false, snapshot != null && snapshot.canRedo(),
              () -> { close.run(); send(Action.REDO); }).setName("editor-context-redo");
        menu.separator();
        menu.item("Board settings", "", null, false, true,
              () -> { close.run(); settings.run(); }).setName("editor-context-settings");
    }

    void layout(float width, float height, float utilityWidth, float utilityHeight) {
        this.width = width; this.height = height; this.utilityWidth = utilityWidth; this.utilityHeight = utilityHeight;
        float toolbarWidth = toolbar.getPrefWidth();
        boolean twoRows = width < toolbarWidth + utilityWidth + 3 * GAP;
        float toolbarTop = twoRows ? utilityHeight + 2 * GAP : GAP;
        float top = twoRows ? toolbarTop + TOOLBAR_HEIGHT + GAP : Math.max(TOOLBAR_HEIGHT, utilityHeight) + 2 * GAP;
        toolbar.setBounds(GAP, height - toolbarTop - TOOLBAR_HEIGHT, toolbarWidth, TOOLBAR_HEIGHT);
        float statusLeft = toolbar.getRight() + GAP;
        float statusRight = twoRows ? width - GAP : width - utilityWidth - 2 * GAP;
        documentStatus.setBounds(statusLeft, toolbar.getY(), Math.max(0, statusRight - statusLeft), TOOLBAR_HEIGHT);
        library.setBounds(GAP, GAP, LIBRARY_WIDTH, Math.max(120, height - top - GAP));
        inspector.setBounds(width - INSPECTOR_WIDTH - GAP, GAP, INSPECTOR_WIDTH, Math.max(120, height - top - GAP));
        layoutLibrary();
        layoutStrip();
        if (designLayer.isVisible()) { layoutDesigns(); }
        layoutThemes();
    }
    private void layoutStrip() {
        float left = library.getRight() + GAP, available = Math.max(200, inspector.getX() - GAP - left);
        stripPanel.setWidth(available); stripPanel.invalidate(); stripPanel.validate();
        stripPanel.setBounds(left, GAP, available, stripPanel.getPrefHeight()); stripPanel.validate();
        layoutBrush(available);
        brushPanel.setBounds(left, stripPanel.getTop() + GAP, Math.min(available, brushPanel.getPrefWidth()), brushPanel.getPrefHeight());
        brushPanel.validate();
    }

    /** Related controls share a row, not column widths with unrelated rows. Wrap whole controls on small viewports. */
    private void layoutBrush(float available) {
        if (brushLayoutWidth == available) { return; }
        brushLayoutWidth = available;
        brushPanel.clearChildren(); brushPanel.left(); brushPanel.defaults().left();
        brushPanel.setVisible(!brushRows.isEmpty());
        for (List<Actor> controls : brushRows) {
            Table row = new Table(); row.left();
            float used = 0;
            for (Actor control : controls) {
                float preferred = control instanceof com.badlogic.gdx.scenes.scene2d.utils.Layout layout
                      ? layout.getPrefWidth() : control.getWidth();
                float cellWidth = Math.min(preferred, available - 12);
                if (used > 0 && used + 8 + cellWidth > available - 12) {
                    brushPanel.add(row).pad(2).row(); row = new Table(); row.left(); used = 0;
                }
                row.add(control).width(cellWidth).padLeft(used == 0 ? 0 : 8);
                used += cellWidth + (used == 0 ? 0 : 8);
            }
            brushPanel.add(row).pad(2).row();
        }
    }

    private void brushRow(Actor... controls) { brushRows.add(List.of(controls)); }
    private void layoutLibrary() {
        if (stripPanel.getWidth() <= 0) { return; }
        var header = (Table) librarySearch.getParent();
        boolean search = !query.isEmpty() || libraryStrip.getPrefWidth() > stripPanel.getWidth() - 12;
        librarySearch.setVisible(search); header.getCell(librarySearch).width(search ? 210 : 0);
        stripPanel.validate(); libraryScroll.validate();
        libraryStrip.show(libraryScroll.getVisualScrollX(), libraryScroll.getScrollWidth());
    }
    private void layoutThemes() {
        ui.fitSearch(themeGallery.getCell(themeFind), false);
        ui.fitChoices(themeGallery, themeScroll, themeCards, width - UiKit.DIALOG_MARGIN, height - UiKit.DIALOG_MARGIN);
        if (!themeFind.isVisible() && themeCards.getPrefHeight() > themeGallery.getCell(themeScroll).getPrefHeight() + .5f) {
            ui.fitSearch(themeGallery.getCell(themeFind), true);
            ui.fitChoices(themeGallery, themeScroll, themeCards, width - UiKit.DIALOG_MARGIN, height - UiKit.DIALOG_MARGIN);
        }
    }
    float bottomInset() { return (brushPanel.isVisible() ? brushPanel.getTop() : stripPanel.getTop()) + GAP; }
    float topInset() { return height - library.getTop(); }

    void update(long generation) {
        this.generation = generation;
        // Wait for the rebuilt inspector's layout before scrolling; its row coordinates are provisional until draw.
        if (revealContent != null) {
            inspector.validate(); inspectorScroll.validate();
            var point = revealContent.localToAscendantCoordinates(inspectorBody, new com.badlogic.gdx.math.Vector2());
            inspectorScroll.scrollTo(point.x, point.y, revealContent.getWidth(), revealContent.getHeight(), false, false);
            inspectorScroll.updateVisualScroll(); revealContent = null;
        }
        var next = source.editorState();
        if (next == null) { return; }
        snapshot = next;
        if (!libraryTheme.equals(next.activeBrush().theme())) { libraryTheme = next.activeBrush().theme(); showLibrary(); }
        String brushKey = next.activeBrush() + ":" + next.brush() + ":" + next.tool();
        if (!brushKey.equals(brushState) && !(stage.getKeyboardFocus() instanceof TextField)
              && brushNumbers.stream().noneMatch(UiNumber::editing)) {
            brushState = brushKey; showBrush();
        }
        installedObjects = terrain.get() == null ? List.of() : terrain.get().editorObjects(next.selected());
        section.update(next, generation, installedObjects);
        for (int i = 0; i < tools.size(); i++) { tools.get(i).pressed(i == next.tool().ordinal()); }
        undo.setDisabled(!next.canUndo()); redo.setDisabled(!next.canRedo());
        String coords = next.selected() == null ? "" : (next.selected().getX() + 1) + ", " + (next.selected().getY() + 1);
        documentName.setText(next.title() + (coords.isEmpty() ? "" : " (" + coords + ")"));
        String feedback = next.message().equals("Hex " + coords) || next.message().equals("Select a hex to edit it.") ? "" : next.message();
        status.setText(feedback); status.setVisible(!feedback.isEmpty());
        documentStatus.getCell(status).height(feedback.isEmpty() ? 0 : status.getPrefHeight());
        // Keep an in-progress numeric edit intact; a completed command also refreshes rejected values.
        String key = next.revision() + ":" + next.selected() + ":" + next.component() + ":" + next.properties() + ":" + next.appearance()
              + ":" + next.objects() + ":" + next.object() + ":" + next.elevation() + ":" + next.theme()
              + ":" + installedObjects.stream().map(object -> object.id() + "=" + object.anchorLevel()).toList();
        if (!key.equals(detailKey) && !(stage.getKeyboardFocus() instanceof TextField)
              && numbers.stream().noneMatch(UiNumber::editing) && !section.dragging() && !choices.isVisible() && !themeLayer.isVisible() && !designLayer.isVisible()) {
            detailKey = key; showContents(); showDetails();
        }
        previews.removeIf(preview -> preview.image().getStage() == null);
        for (String file : thumbnails.getAssetNames()) {
            if (previews.stream().noneMatch(preview -> preview.file().equals(file))) { thumbnails.unload(file); }
        }
        layoutStrip(); layoutLibrary();
        libraryCards.removeIf(card -> card.getStage() == null);
        thumbnails.update(2);
        models.update();
        samples.update();
        stage.getViewport().apply();
        for (UiButton card : libraryCards) {
            String keyId = (String) card.getUserObject();
            card.pressed(keyId.equals(next.activeBrush().key()));
        }
        for (Preview preview : previews) {
            if (thumbnails.isLoaded(preview.file(), Texture.class)) {
                preview.image().setDrawable(new TextureRegionDrawable(new TextureRegion(thumbnails.get(preview.file(), Texture.class))));
                preview.image().setUserObject("Ready");
            }
        }
    }

    private List<Entry> libraryEntries() {
        List<Entry> entries = new ArrayList<>();
        if (objects) {
            var assets = blueprint.assets().stream().filter(BoardEditorBlueprint.Asset::palette)
                  .filter(a -> a.kind().equals(category.equals("Props") ? "prop" : "decal")).toList();
            if (libraryOwner.isEmpty()) {
                assets.stream().map(BoardEditorBlueprint.Asset::group).distinct().sorted().forEach(group ->
                      entries.add(new Entry(group, group, "", "group", assets.stream().filter(a -> a.group().equals(group)).findFirst().orElseThrow().id())));
            } else {
                assets.stream().filter(a -> a.group().equals(libraryOwner)).forEach(a -> entries.add(new Entry(a.id(), a.label(), a.thumbnail(), "asset", "")));
            }
        } else if (libraryOwner.isEmpty()) {
            blueprint.components().stream().filter(c -> c.category().equals(category) && c.palette())
                  .forEach(c -> entries.add(new Entry(c.id(), c.label(), "", "category", c.id())));
        } else {
            var definition = blueprint.component(libraryOwner);
            if (libraryGroup.isEmpty()) {
                definition.presets().forEach(p -> entries.add(new Entry(p.id(), p.label(), "", "preset", definition.id())));
            }
            designVariants(libraryOwner).stream().filter(v -> libraryGroup.isEmpty() || libraryGroup.equals(v.group()))
                  .forEach(v -> entries.add(new Entry(v.id(), v.label(), v.thumbnail(), "variant", v.owner())));
        }
        return entries;
    }

    private void showCategories() {
        categoryRows.clearChildren(); categories.clear();
        for (String section : List.of("Terrain", "Features", "Structures", "Props", "Decals")) {
            categoryRows.add(ui.caption(section)).left().padTop(9).row();
            boolean assets = section.equals("Props") || section.equals("Decals");
            List<Option> entries = assets ? blueprint.assets().stream().filter(BoardEditorBlueprint.Asset::palette)
                  .filter(a -> a.kind().equals(section.equals("Props") ? "prop" : "decal"))
                  .map(BoardEditorBlueprint.Asset::group).distinct().sorted().map(g -> new Option(g, g)).toList()
                  : blueprint.components().stream().filter(c -> c.palette() && c.category().equals(section)).map(c -> new Option(c.label(), c.id())).toList();
            for (Option entry : entries) {
                UiButton button = button(entry.label(), () -> { category = section; objects = assets; browse(entry.id()); });
                button.getLabel().setEllipsis(true); button.getLabelCell().growX().minWidth(0).left();
                button.setName("editor-library-" + entry.id()); button.setUserObject(entry.id()); categories.add(button);
                categoryRows.add(button).height(28).row();
            }
        }
    }

    private void showLibrary() {
        libraryCards.clear(); models.clear();
        for (UiButton choice : categories) { choice.pressed(choice.getUserObject().equals(libraryOwner)); }
        libraryNavigation.clearChildren();
        var groups = objects ? List.<String>of() : designVariants(libraryOwner).stream().map(BoardEditorBlueprint.Variant::group).distinct().sorted().toList();
        if (groups.size() > 1) {
            List<Option> filters = new ArrayList<>(); filters.add(new Option("All designs", "")); groups.forEach(g -> filters.add(new Option(g, g)));
            libraryNavigation.add(choice("", libraryGroup.isEmpty() ? "All designs" : libraryGroup, filters,
                  value -> { libraryGroup = value; showLibrary(); })).growX().minWidth(0).row();
        }
        var matching = libraryEntries().stream().filter(e -> (e.label() + " " + e.key()).toLowerCase(Locale.ROOT).contains(query)).toList();
        stripTitle.setText((objects ? libraryOwner : blueprint.component(libraryOwner).label()) + " · " + matching.size());
        libraryStrip.items(matching.size(), index -> {
            Entry entry = matching.get(index);
            Image preview = new Image(); preview(entry, preview);
            UiButton choose = visualCard(entry.label(), preview, 70, () -> {
                if (entry.type().equals("asset")) { send(Action.ASSET, entry.key()); }
                else { send(Action.CHOOSE_BRUSH, entry.owner(), entry.key()); }
            });
            String key = entry.type().equals("preset") ? entry.owner() + "/" + entry.key() : entry.key();
            choose.setName("editor-library-" + key); choose.setUserObject(key); libraryCards.add(choose); return choose;
        });
        libraryScroll.setScrollX(0); libraryScroll.updateVisualScroll(); layoutLibrary();
    }

    private void preview(Entry entry, Image image) {
        if (!entry.thumbnail().isEmpty()) { thumbnail(entry.thumbnail(), image); return; }
        switch (entry.type()) {
            case "category" -> samples.add(libraryTheme, entry.owner(), "", image);
            case "preset" -> samples.add(libraryTheme, entry.owner(), "preset:" + entry.key(), image);
            case "variant" -> {
                var variant = blueprint.variant(entry.key());
                if (variant != null && !variant.asset().isEmpty()) { models.add(variant.asset(), image); }
                else { samples.add(libraryTheme, entry.owner(), entry.key(), image); }
            }
            default -> {
                String key = entry.type().equals("group") ? entry.owner() : entry.key();
                var asset = blueprint.asset(key);
                if (asset.kind().equals("prop")) {
                    if (BoardSceneryLayouts.hasLayout(key)) { samples.addObject(libraryTheme, key, image); }
                    else { models.add(key, image); }
                } else { thumbnail(asset.image(), image); }
            }
        }
    }

    private void thumbnail(String relative, Image image) {
        String file = new java.io.File(Configuration.dataDir(), relative).getAbsolutePath();
        if (!relative.isEmpty() && new java.io.File(file).isFile()) {
            if (!thumbnails.contains(file)) { thumbnails.load(file, Texture.class); }
            previews.add(new Preview(image, file));
        } else { image.setUserObject("No preview"); }
    }

    private void showBrush() {
        brushNumbers.forEach(UiNumber::close); brushNumbers.clear(); brushRows.clear(); brushLayoutWidth = -1;
        var brush = snapshot.activeBrush();
        boolean belongs = objects ? brush.object() != null && blueprint.asset(brush.object().asset()) != null
              && blueprint.asset(brush.object().asset()).group().equals(libraryOwner) : brush.component().equals(libraryOwner);
        if (!belongs) { layoutStrip(); return; }
        if (brush.object() != null) {
            var object = brush.object();
            var precision = ui.checkbox("Precision mode", brush.precision()); precision.setName("editor-precision");
            onChange(precision, () -> send(Action.BRUSH_VALUE, "precision", Boolean.toString(precision.isTicked())));
            ui.tip(precision).getActor().setText("Place at the exact pointer position; otherwise use the hex centre. Both follow the clicked surface.");
            brushRow(precision);
            brushRow(brushNumber("Rotation °", object.rotation(), -180, 180, 1, Action.BRUSH_VALUE, "object:rotation"),
                  brushNumber("Scale", object.scale(), .05, 8, .05, Action.BRUSH_VALUE, "object:scale"));
            var mirror = ui.checkbox("Mirror", object.mirror());
            onChange(mirror, () -> send(Action.BRUSH_VALUE, "object:mirror", Boolean.toString(mirror.isTicked())));
            if (object.kind().equals("prop")) {
                brushRow(brushNumber("Height offset", object.placement().offset() == null ? 0 : object.placement().offset(), -10, 30, .1, Action.BRUSH_VALUE, "object:offset"), mirror);
            } else {
                var span = ui.checkbox("Span hexes", !object.clipToHex());
                onChange(span, () -> send(Action.BRUSH_VALUE, "object:clipToHex", Boolean.toString(!span.isTicked())));
                brushRow(span, mirror);
            }
            var asset = blueprint.asset(object.asset());
            if (asset != null && asset.snap() != null) {
                var magnetic = ui.checkbox("Magnetic snap", brush.magnetic()); magnetic.setName("editor-magnetic");
                onChange(magnetic, () -> send(Action.BRUSH_VALUE, "magnetic", Boolean.toString(magnetic.isTicked())));
                ui.tip(magnetic).getActor().setText("Join compatible connectors at the same height and scale, with opposing orientations.");
                brushRow(magnetic);
            }
            if (object.kind().equals("decal")) {
                brushRow(brushNumber("Paint order", object.drawOrder(), -20, 20, 1, Action.BRUSH_VALUE, "object:order"));
            }
        } else {
            List<Actor> controls = new ArrayList<>();
            controls.add(brushNumber("Radius", snapshot.brush(), 1, 20, 1, Action.BRUSH, ""));
            var definition = blueprint.component(brush.component());
            if (definition.id().equals("ground")) {
                controls.add(brushNumber("Level", brush.elevation(), -10, 30, 1, Action.BRUSH_VALUE, "elevation")
                      .withCheckbox(brush.elevationEnabled(), checked -> send(Action.BRUSH_VALUE, "applyElevation", Boolean.toString(checked))));
                var theme = ui.checkbox("Theme", brush.themeEnabled());
                onChange(theme, () -> send(Action.BRUSH_VALUE, "applyTheme", Boolean.toString(theme.isTicked())));
                Table themeControl = new Table(); themeControl.add(theme).padRight(4);
                themeControl.add(choice("", themeLabel(brush.theme()), themeOptions(), value -> send(Action.BRUSH_VALUE, "theme", value)))
                      .width(125).height(27); controls.add(themeControl);
            } else {
                for (var field : definition.fields()) {
                    if (!field.brush()) { continue; }
                    var value = brush.properties().stream().filter(p -> p.terrain().equals(field.terrain())).findFirst().orElse(null);
                    if (value == null) { continue; }
                    controls.add(brushNumber(field.label(), value.value(), field.min(), field.max(), field.step(), Action.BRUSH_VALUE, "terrain:" + field.terrain()));
                }
            }
            brushRows.add(controls);
        }
        layoutStrip();
    }

    private UiNumber brushNumber(String label, double value, double min, double max, double step, Action action, String target) {
        long editGeneration = generation;
        UiNumber number = new UiNumber(ui, stage, label, value, min, max, step,
              (next, finished) -> source.editorValue(new Command(action, target, next), finished, editGeneration)).fieldSize(56, 27);
        number.setName("editor-brush-" + target); brushNumbers.add(number); return number;
    }

    private void openDesigns(String owner) {
        stage.setKeyboardFocus(null); choices.cancel(); numbers.forEach(UiNumber::dismiss);
        designOwner = owner; designPage = 0; designQuery = ""; designSearch.field.setText("");
        showDesigns(); designLayer.setVisible(true); designLayer.toFront();
    }

    private List<BoardEditorBlueprint.Variant> designVariants(String owner) {
        boolean linkedMaterial = blueprint.component(owner).fields().stream().flatMap(f -> f.choices().stream()).anyMatch(c -> !c.material().isEmpty());
        return blueprint.variants(owner).stream().filter(v -> !linkedMaterial || v.material().isEmpty()).toList();
    }

    private void showDesigns() {
        if (designOwner.isEmpty()) { return; }
        designCards.clearChildren(); designPages.clearChildren();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("", "Default", "", "category", designOwner));
        designVariants(designOwner).forEach(v -> entries.add(new Entry(v.id(), v.label(), v.thumbnail(), "variant", v.owner())));
        var matching = entries.stream().filter(e -> (e.label() + " " + e.key()).toLowerCase(Locale.ROOT).contains(designQuery)).toList();
        int last = Math.max(0, (matching.size() - 1) / PAGE_SIZE); designPage = Math.min(designPage, last);
        for (Entry entry : matching.subList(Math.min(matching.size(), designPage * PAGE_SIZE), Math.min(matching.size(), (designPage + 1) * PAGE_SIZE))) {
            Image image = new Image(); preview(entry, image);
            UiButton card = visualCard(entry.label(), image, 80, () -> { send(Action.VARIANT, designOwner, entry.key()); closeDesigns(); });
            designCards.addActor(card);
        }
        if (last > 0) {
            UiButton previous = button("Previous", () -> { designPage--; showDesigns(); }); previous.setDisabled(designPage == 0);
            UiButton next = button("Next", () -> { designPage++; showDesigns(); }); next.setDisabled(designPage == last);
            designPages.add(previous); designPages.add(ui.caption((designPage + 1) + " / " + (last + 1))).growX(); designPages.add(next);
        }
        designDialog.getCell(designPages).height(last > 0 ? designPages.getPrefHeight() : 0);
        ui.fitSearch(designDialog.getCell(designSearch), last > 0); layoutDesigns();
    }

    private void layoutDesigns() {
        ui.fitChoices(designDialog, designScroll, designCards, width - UiKit.DIALOG_MARGIN, height - UiKit.DIALOG_MARGIN);
    }

    private void closeDesigns() { stage.setKeyboardFocus(null); designLayer.setVisible(false); designCards.clearChildren(); }

    private UiButton visualCard(String title, Image preview, float imageHeight, Runnable select) {
        UiButton card = ui.button("hud-plain", null, title, null); card.clearChildren(); card.pad(4);
        if (preview.getName() == null) { preview.setName("editor-card-preview"); }
        preview.setScaling(com.badlogic.gdx.utils.Scaling.fit); preview.setTouchable(Touchable.disabled);
        Label loading = ui.label("Preview…", "hud-small", 11, UiTheme.MUTED);
        loading.setAlignment(com.badlogic.gdx.utils.Align.center); loading.setTouchable(Touchable.disabled);
        loading.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.forever(com.badlogic.gdx.scenes.scene2d.actions.Actions.run(() -> {
            loading.setVisible(preview.getDrawable() == null);
            if (preview.getUserObject() != null) { loading.setText(preview.getUserObject().toString()); }
        })));
        Table backdrop = new Table(); backdrop.setBackground(ui.skin.newDrawable("white", UiTheme.POP));
        card.add(new Stack(backdrop, preview, loading)).grow().minWidth(0).minHeight(imageHeight).prefHeight(imageHeight).row();
        card.getLabel().setEllipsis(true); card.getLabel().setAlignment(com.badlogic.gdx.utils.Align.center);
        card.add(card.getLabel()).growX().minWidth(0).height(22).padTop(2);
        onChange(card, () -> { stage.setKeyboardFocus(null); select.run(); });
        return card;
    }

    private String assetLabel(String key) { var asset = blueprint.asset(key); return asset == null ? key : asset.label(); }
    private String objectLabel(BoardDecoration object) { return object.name() == null ? assetLabel(object.asset()) : object.name(); }
    private UiButton rowButton(String title, String sub, Runnable select) {
        return rowButton(title, sub, "hex", select);
    }
    private UiButton rowButton(String title, String sub, String icon, Runnable select) {
        UiButton button = ui.button("hud-plain", icon, title, null);
        button.getLabel().setAlignment(com.badlogic.gdx.utils.Align.left); button.getLabel().setEllipsis(true);
        button.getLabelCell().growX().minWidth(0);
        if (sub != null) {
            button.row(); Label subtitle = ui.label(sub, "hud-small", 10.5f, UiTheme.MUTED); subtitle.setEllipsis(true);
            button.add(subtitle).colspan(2).growX().minWidth(0).left().padTop(3);
        }
        onChange(button, () -> { stage.setKeyboardFocus(null); select.run(); }); return button;
    }
    private void browse(String owner) {
        libraryOwner = owner; libraryGroup = ""; query = ""; searchField.setText(""); showLibrary(); if (snapshot != null) { showBrush(); }
    }
    private void showContents() {
        contents.clearChildren(); contents.top();
        if (snapshot.selected() == null) { hexTitle.setText("HEX INSPECTOR"); return; }
        hexTitle.setText("HEX " + (snapshot.selected().getX() + 1) + ", " + (snapshot.selected().getY() + 1));
        for (var component : blueprint.components()) {
            if (!component.id().equals("ground") && (component.present().isEmpty()
                  || !component.isPresent(name -> snapshot.property(name) != null))) { continue; }
            UiButton row = rowButton(component.label(), component.id().equals("ground") ? "Level " + snapshot.elevation() : null,
                  component.category().equals("Structures") ? "layers" : "hex", () -> send(Action.COMPONENT, component.id()));
            row.pressed(snapshot.object().isEmpty() && component.id().equals(snapshot.component()));
            contentRow(row, component.id().equals("ground") ? 40 : 30, false, component.present().isEmpty() ? null
                  : () -> send(Action.REMOVE_COMPONENT, component.id()));
            for (var object : snapshot.objects()) {
                if (object.placement().receiver() != null && component.receiver().equals(object.placement().receiver().terrain())) { contentObject(object, true); }
            }
        }
        for (var object : snapshot.objects()) {
            if (object.placement().receiver() == null || blueprint.components().stream().noneMatch(c -> c.receiver().equals(object.placement().receiver().terrain())
                  && c.isPresent(name -> snapshot.property(name) != null))) { contentObject(object, false); }
        }
    }
    private void contentObject(BoardDecoration object, boolean attached) {
        String level = height(object);
        if (terrain.get() != null) {
            var placed = installedObjects.stream().filter(o -> o.id().equals(object.id())).findFirst();
            if (placed.isPresent()) { level += " · L" + UiNumber.format(placed.get().anchorLevel()); }
        }
        UiButton row = rowButton(objectLabel(object), level, object.kind().equals("prop") ? "unit" : "layers",
              () -> send(Action.SELECT_OBJECT, object.id()));
        row.setName("editor-content-" + object.id()); row.pressed(object.id().equals(snapshot.object()));
        contentRow(row, 40, attached, () -> send(Action.REMOVE_OBJECT, object.id(), ""));
    }
    private void showDetails() {
        String selection = snapshot.selected() + ":" + snapshot.object() + ":" + snapshot.component();
        boolean selectionChanged = !selection.equals(shownSelection);
        float scroll = selectionChanged ? 0 : inspectorScroll.getScrollY(); shownSelection = selection;
        if (selectionChanged) { revealContent = contents.findActor("editor-content-" + snapshot.object()); }
        numbers.forEach(UiNumber::close); numbers.clear();
        detail.clearChildren(); detail.top(); detail.defaults().growX().minWidth(0).pad(3, 12, 3, 12);
        if (snapshot.selected() == null) { text("Select a hex to inspect its terrain and objects."); return; }
        if (snapshot.selection().size() > 1) {
            heading(snapshot.selection().size() + " selected");
            text("Drag a selected item to move the group. Shift + click adds or removes items.");
            Table group = new Table(); group.add(button("Clear selection", () -> send(Action.CLEAR_SELECTION))).growX();
            group.add(button("Delete group", () -> send(Action.DELETE_SELECTION))).growX(); detail.add(group).row();
        }
        var object = snapshot.objects().stream().filter(d -> d.id().equals(snapshot.object())).findFirst();
        if (object.isPresent()) { object(object.get()); }
        else { component(); }
        inspector.validate(); inspectorScroll.validate(); inspectorScroll.setScrollY(scroll);
    }
    private void component() {
        var definition = blueprint.component(snapshot.component());
        heading(definition.label());
        boolean present = definition.isPresent(name -> snapshot.property(name) != null);
        if (!present) {
            text("This component is not in the selected hex.");
            detail.add(button("+ Add " + definition.label(), () -> send(Action.ADD_COMPONENT, definition.id()))).row(); return;
        }
        if (definition.id().equals("ground")) {
            number("Ground level", snapshot.elevation(), -10, 30, 1, Action.ELEVATION, "");
        }
        Table appearance = new Table(); appearance.defaults().growX().minWidth(0).uniformX().top().pad(0, 2, 0, 2);
        if (definition.id().equals("ground")) { appearance.add(themeChoice("Hex theme", snapshot.theme(), v -> send(Action.THEME, v))); }
        if (!designVariants(definition.id()).isEmpty()) {
            var style = snapshot.appearance().get(definition.id());
            var variant = style == null ? null : blueprint.variant(style.variant());
            String designId = variant == null ? blueprint.variants(definition.id()).stream()
                  .filter(v -> style != null && !v.material().isEmpty() && v.material().equals(style.material()))
                  .map(BoardEditorBlueprint.Variant::id).findFirst().orElse("") : variant.id();
            Image design = new Image(); samples.add(snapshot.theme(), definition.id(), designId, design);
            Table visual = new Table(); visual.defaults().growX().minWidth(0);
            visual.add(ui.caption("Visual design")).left().padBottom(5).row();
            visual.add(visualCard(variant == null || !designVariants(definition.id()).contains(variant) ? "Default" : variant.label(), design, 64, () -> openDesigns(definition.id())));
            appearance.add(visual);
            detail.add(appearance).row();
            if (variant != null && variant.blend()) { number("Blend strength", style.strength() == null ? .5 : style.strength(), 0, 1, .05, Action.BLEND, ""); }
        } else if (appearance.hasChildren()) { detail.add(appearance).row(); }
        for (var field : definition.fields()) { if (!field.optional() || snapshot.property(field.terrain()) != null) { field(field); } }
        var optional = definition.fields().stream().filter(f -> f.optional() && !f.terrain().contains("fluff") && !f.terrain().equals("cliff_top")
              && snapshot.property(f.terrain()) == null).toList();
        if (!optional.isEmpty()) {
            choose("Add property", "Choose…", optional.stream().map(f -> new Option(f.label(), f.terrain())).toList(), key -> {
                var field = optional.stream().filter(f -> f.terrain().equals(key)).findFirst().orElseThrow();
                send(Action.TERRAIN, key, Integer.toString(field.choices().isEmpty() ? 1 : field.choices().getFirst().value()));
            });
        }
        if (definition.id().equals("ground") || definition.id().equals("cliff")) { heading("Cliff edges"); edges("cliff_top"); }
        detail.add(button("Use as brush", () -> send(Action.SAMPLE))).padTop(8).row();
        if (!definition.present().isEmpty()) { detail.add(removeButton("Remove component", () -> send(Action.REMOVE_COMPONENT, definition.id()))).row(); }
    }

    private void field(BoardEditorBlueprint.Field field) {
        var value = snapshot.property(field.terrain());
        if (field.terrain().contains("fluff") || field.terrain().equals("cliff_top")) { return; }
        if (value == null) {
            if (field.optional()) {
                int initial = field.choices().isEmpty() ? 1 : field.choices().getFirst().value();
                detail.add(button("+ " + field.label(), () -> send(Action.TERRAIN, field.terrain(), Integer.toString(initial)))).row();
            }
            return;
        }
        Table row = new Table(); row.defaults().minWidth(0);
        if (field.choices().stream().anyMatch(c -> !c.material().isEmpty())) {
            UiChoiceGrid cards = ui.choiceGrid(120, 4).fit(INSPECTOR_WIDTH - 24, Float.MAX_VALUE);
            for (var choice : field.choices()) {
                Image image = new Image();
                var preset = blueprint.component(snapshot.component()).presets().stream()
                      .filter(p -> new megamek.common.Hex(0, p.terrain(), "").terrainLevel(megamek.common.units.Terrains.getType(field.terrain())) == choice.value())
                      .findFirst();
                samples.add(snapshot.theme(), snapshot.component(), preset.map(p -> "preset:" + p.id()).orElse(""), image);
                UiButton card = visualCard(choice.label(), image, 48, () -> send(Action.TERRAIN, field.terrain(), Integer.toString(choice.value())));
                card.setName("editor-surface-" + choice.value()); card.pressed(value.value() == choice.value()); cards.addActor(card);
            }
            row.add(cards).growX();
        } else if (field.choices().isEmpty()) {
            row.add(numeric(field.label(), value.value(), field.min(), field.max(), field.step(), Action.TERRAIN, field.terrain())).growX();
        } else if (field.choices().size() == 1 && field.choices().getFirst().value() == value.value()) {
            row.add(ui.label(field.label(), "hud-small", 12, UiTheme.TEXT)).growX().left();
        } else {
            String current = field.choices().stream().filter(c -> c.value() == value.value()).map(BoardEditorBlueprint.Choice::label)
                  .findFirst().orElse("Value " + value.value());
            row.add(choice(field.label(), current, field.choices().stream().map(c -> new Option(c.label(), Integer.toString(c.value()))).toList(),
                  chosen -> send(Action.TERRAIN, field.terrain(), chosen))).growX();
        }
        if (field.optional()) { row.add(removeButton("", () -> send(Action.REMOVE_TERRAIN, field.terrain()))).width(26).padLeft(4); }
        detail.add(row).minHeight(28).row();
        if (field.edges()) { edges(field.terrain()); }
        if (field.terrain().equals("solaris_elevator")) {
            heading("Move on these die rolls");
            Table rolls = new Table();
            for (int side = 0; side < 6; side++) {
                int roll = side;
                UiButton button = button(Integer.toString(side + 1), () -> send(Action.EDGE, field.terrain(), Integer.toString(roll)));
                button.pressed((value.exits() & (1 << side)) != 0); rolls.add(button).growX();
            }
            detail.add(rolls).row();
        } else if (field.terrain().equals("industrial_elevator")) {
            int top = (value.exits() >> megamek.common.IndustrialElevator.SHAFT_TOP_SHIFT) & megamek.common.IndustrialElevator.CAPACITY_MASK;
            int capacity = (value.exits() & megamek.common.IndustrialElevator.CAPACITY_MASK) * megamek.common.IndustrialElevator.CAPACITY_MULTIPLIER;
            number("Shaft top level", top, 0, 255, 1, Action.ELEVATOR, "top");
            number("Capacity (tons)", capacity, 0, 2550, 10, Action.ELEVATOR, "capacity");
        }
    }

    /** Six directional buttons in the same orientation as the shared flat-top hex geometry. */
    private void edges(String terrain) {
        var value = snapshot.property(terrain);
        if (value != null && (value.exits() > 63 || value.exits() < 0)) {
            text("Legacy artwork selector " + value.exits() + ". Connections are preserved; this is not an edge mask."); return;
        }
        String[] labels = { "N", "NE", "SE", "S", "SW", "NW" };
        Table edges = new Table();
        int[][] rows = { { -1, 0, -1 }, { 5, -1, 1 }, { 4, -1, 2 }, { -1, 3, -1 } };
        for (int[] row : rows) {
            for (int side : row) {
                if (side < 0) { edges.add().width(48); }
                else {
                    UiButton button = button(labels[side], () -> send(Action.EDGE, terrain, Integer.toString(side)));
                    button.pressed(value != null && (value.exits() & (1 << side)) != 0); edges.add(button).width(48);
                }
            }
            edges.row();
        }
        detail.add(edges).row();
        if (!terrain.equals("cliff_top") && value != null) {
            detail.add(button(value.explicit() ? "Use automatic connections" : "Automatic connections", () -> send(Action.AUTO_EDGES, terrain))).row();
        }
    }

    private static String height(BoardDecoration object) {
        var p = object.placement();
        return p.mode().equals("absolute") ? "Absolute L" + UiNumber.format(p.level())
              : p.receiver().terrain() + (p.offset() == 0 ? "" : " + " + UiNumber.format(p.offset()));
    }
    private void object(BoardDecoration object) {
        heading(objectLabel(object)); text(object.kind().equals("prop") ? "Visual prop" : "Surface decal");
        var placement = object.placement();
        String support = placement.receiver() == null ? "Fixed height (from file)" : switch (placement.receiver().terrain()) {
            case "bridge" -> "Follows bridge deck"; case "building" -> "Follows building roof";
            case "industrial" -> "Follows industry top"; default -> "Follows ground";
        };
        text(support);
        if (object.kind().equals("prop")) {
            double offset = placement.offset() == null ? placement.level() - snapshot.elevation() : placement.offset();
            number("Height above surface", offset, -10, 30, .1, Action.OBJECT_VALUE, "offset");
        } else { number("Paint order", object.drawOrder(), -20, 20, 1, Action.OBJECT_VALUE, "order"); }
        if (object.kind().equals("prop") && terrain.get() != null) {
            var placed = installedObjects.stream().filter(o -> o.id().equals(object.id())).findFirst();
            text(placed.isEmpty() ? "Waiting for geometry, or the chosen surface is missing under this anchor."
                  : "Resolved anchor · L" + UiNumber.format(placed.get().anchorLevel()));
        }
        heading("Transform");
        number("East / hex width", object.x(), -4, 4, .01, Action.OBJECT_VALUE, "x");
        number("North / hex height", object.y(), -4, 4, .01, Action.OBJECT_VALUE, "y");
        number("Rotation °", object.rotation(), -180, 180, 1, Action.OBJECT_VALUE, "rotation");
        number("Scale", object.scale(), .05, 8, .05, Action.OBJECT_VALUE, "scale");
        if (object.kind().equals("decal")) {
            UiButton overflow = button("Span neighbouring hexes", () -> send(Action.OBJECT_VALUE, "clipToHex", Boolean.toString(!object.clipToHex())));
            overflow.pressed(!object.clipToHex()); detail.add(overflow).row();
        }
        UiButton mirror = button("Mirror", () -> send(Action.OBJECT_VALUE, "mirror", Boolean.toString(!object.mirror())));
        mirror.pressed(object.mirror()); detail.add(mirror).row();
        input("Name", object.name() == null ? "" : object.name(), v -> send(Action.OBJECT_VALUE, "name", v));
        detail.add(button("Use as brush", () -> send(Action.SAMPLE))).row();
        Table commands = new Table(); commands.add(button("Duplicate", () -> send(Action.DUPLICATE_OBJECT))).growX();
        commands.add(removeButton("Remove", () -> send(Action.REMOVE_OBJECT))).growX().padLeft(4); detail.add(commands).padTop(12).row();
    }
    private record Option(String label, String id) { }
    private void choose(String label, String selected, List<Option> options, Consumer<String> commit) {
        detail.add(choice(label, selected, options, commit)).row();
    }
    private Table choice(String label, String selected, List<Option> options, Consumer<String> commit) {
        Table row = new Table(); row.add(ui.label(label, "hud-small", 12, UiTheme.MUTED)).left().padRight(8);
        UiButton face = ui.select(selected, false); row.add(face).growX().minWidth(0);
        face.setName("editor-choice-" + label);
        onChange(face, () -> {
            stage.setKeyboardFocus(null); UiMenuList list = new UiMenuList(ui);
            for (Option option : options) {
                list.item(option.label(), null, null, false, true, () -> { choices.cancel(); face.setText(option.label()); commit.accept(option.id()); });
            }
            choices.header(label, null).content(list); choices.showAbove(face, 0);
        });
        return row;
    }
    private String themeLabel(String value) {
        if (value.isEmpty()) { return "Grass"; }
        String words = value.replace('_', ' ').replace('-', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }
    private List<Option> themeOptions() {
        List<Option> themes = new ArrayList<>();
        java.util.stream.Stream.concat(java.util.stream.Stream.of("grass"), source.editorThemes().stream())
              .filter(value -> !value.isEmpty()).distinct().sorted()
              .forEach(value -> themes.add(new Option(themeLabel(value), value)));
        return themes;
    }
    private Table themeChoice(String label, String selected, Consumer<String> commit) {
        Table row = new Table(); row.defaults().growX();
        row.add(ui.caption(label)).left().padBottom(5).row();
        Image preview = new Image(); samples.add(selected, "", "", preview);
        String[] value = { selected };
        UiButton face = visualCard(themeLabel(selected), preview, 64, () -> { });
        face.setName("editor-choice-" + label);
        onChange(face, () -> openThemes(value[0], next -> {
            value[0] = next; face.setText(themeLabel(next));
            preview.setDrawable(null); preview.setUserObject(null); samples.add(next, "", "", preview); commit.accept(next);
        }));
        row.add(face).minWidth(0); return row;
    }
    private void openThemes(String selected, Consumer<String> commit) {
        stage.cancelTouchFocus(); stage.setKeyboardFocus(null); choices.cancel();
        numbers.forEach(UiNumber::dismiss); settingsNumbers.forEach(UiNumber::dismiss);
        selectedTheme = selected.isEmpty() ? "grass" : selected; themeCommit = commit; themeQuery = ""; themeSearch.setText("");
        showThemes(); themeLayer.setVisible(true); themeLayer.toFront();
    }
    private void showThemes() {
        themeCards.clearChildren();
        var themes = themeOptions().stream().filter(option -> option.label().toLowerCase(Locale.ROOT).contains(themeQuery)).toList();
        for (var theme : themes) {
            Image preview = new Image(); samples.add(theme.id(), "", "", preview);
            preview.setName("editor-theme-preview-" + theme.id());
            UiButton card = visualCard(theme.label() + (theme.id().equals(selectedTheme) ? "  ✓" : ""), preview, 84, () -> {
                Consumer<String> commit = themeCommit; closeThemes(); if (commit != null) { commit.accept(theme.id()); }
            });
            card.setName("editor-theme-" + theme.id()); card.pressed(theme.id().equals(selectedTheme));
            themeCards.addActor(card);
        }
        if (themes.isEmpty()) { themeCards.addActor(ui.label("No matching themes", "hud-small", 12, UiTheme.MUTED)); }
        layoutThemes();
    }
    private void closeThemes() {
        stage.setKeyboardFocus(null); themeLayer.setVisible(false); themeCards.clearChildren(); themeCommit = null;
    }
    private void number(String label, double value, double min, double max, double step, Action action, String target) {
        detail.add(numeric(label, value, min, max, step, action, target)).row();
    }
    private UiNumber numeric(String label, double value, double min, double max, double step, Action action, String target) {
        long editGeneration = generation;
        UiNumber number = new UiNumber(ui, stage, label, value, min, max, step,
              (next, finished) -> source.editorValue(new Command(action, target, next), finished, editGeneration));
        numbers.add(number); return number;
    }
    private TextField textField(String label, String value, Consumer<String> commit) {
        TextField field = new TextField(value, ui.skin, "hud"); field.setName("editor-" + label);
        final String[] previous = { value };
        field.addListener(new FocusListener() {
            @Override public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (!focused && !field.getText().trim().equals(previous[0])) { previous[0] = field.getText().trim(); commit.accept(previous[0]); }
            }
        });
        field.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int key) {
                if (key == Input.Keys.ESCAPE) { field.setText(previous[0]); stage.setKeyboardFocus(null); return true; }
                if (key == Input.Keys.ENTER || key == Input.Keys.NUMPAD_ENTER) { stage.setKeyboardFocus(null); return true; }
                return false;
            }
        });
        return field;
    }
    private void input(String label, String value, Consumer<String> commit) {
        Table row = new Table(); row.add(ui.label(label, "hud-small", 12, UiTheme.MUTED)).left().padRight(8);
        row.add(textField(label, value, commit)).growX().minWidth(0).height(29); detail.add(row).row();
    }
    void openSettings() {
        if (snapshot == null) { return; }
        stage.cancelTouchFocus(); numbers.forEach(UiNumber::dismiss); choices.cancel();
        stage.setKeyboardFocus(null); settingsNumbers.forEach(UiNumber::close); settingsNumbers.clear();
        settingsBody.clearChildren(); settingsBody.defaults().growX().pad(5, 6, 5, 6);
        settingsBody.add(ui.caption("Map · " + snapshot.width() + " × " + snapshot.height())).row();
        Label description = ui.label("Apply a theme across the map. Undo restores the previous themes.", "hud-small", 12, UiTheme.MUTED);
        description.setWrap(true); settingsBody.add(description).row();
        String[] theme = { snapshot.theme() };
        long[] awaiting = { Long.MAX_VALUE };
        settingsBody.add(themeChoice("Global theme", theme[0], value -> theme[0] = value)).row();
        settingsBody.add(button("Apply theme to all hexes", () -> { awaiting[0] = snapshot.revision(); send(Action.MAP_THEME, theme[0]); })).row();
        settingsBody.add(button("Validate board", () -> { awaiting[0] = snapshot.revision(); send(Action.VALIDATE); })).row();
        Label result = ui.label("", "hud-small", 11.5f, UiTheme.MUTED); result.setWrap(true);
        result.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.forever(com.badlogic.gdx.scenes.scene2d.actions.Actions.run(() -> {
            if (snapshot.revision() > awaiting[0]) { result.setText(snapshot.message()); awaiting[0] = Long.MAX_VALUE; }
        })));
        settingsBody.add(result).row();
        settingsBody.add(ui.caption("New board dimensions")).padTop(20).row();
        newWidth = snapshot.width(); newHeight = snapshot.height();
        UiNumber w = new UiNumber(ui, stage, "Width", newWidth, 1, 100, 1, (v, done) -> newWidth = Integer.parseInt(v));
        UiNumber h = new UiNumber(ui, stage, "Height", newHeight, 1, 100, 1, (v, done) -> newHeight = Integer.parseInt(v));
        settingsNumbers.add(w); settingsNumbers.add(h); settingsBody.add(w).row(); settingsBody.add(h).row();
        settingsBody.add(button("Create new board", () -> { send(Action.NEW, newWidth + "x" + newHeight); closeSettings(); })).row();
        settingsLayer.setVisible(true); settingsLayer.toFront();
    }
    void closeSettings() {
        closeThemes(); stage.setKeyboardFocus(null); choices.cancel(); settingsNumbers.forEach(UiNumber::close); settingsNumbers.clear(); settingsLayer.setVisible(false);
    }
    boolean escape() {
        if (themeLayer.isVisible()) { closeThemes(); return true; }
        if (designLayer.isVisible()) { closeDesigns(); return true; }
        if (choices.cancel()) { return true; }
        if (settingsLayer.isVisible()) { closeSettings(); return true; }
        return false;
    }
    private void heading(String text) { Label label = ui.caption(text); label.setWrap(true); detail.add(label).growX().minWidth(0).padTop(12).row(); }
    private void text(String text) {
        Label label = ui.label(text, "hud-small", 11.5f, UiTheme.MUTED); label.setWrap(true); detail.add(label).row();
    }
    @Override public void dispose() {
        numbers.forEach(UiNumber::close); settingsNumbers.forEach(UiNumber::close); choices.cancel();
        brushNumbers.forEach(UiNumber::close);
        thumbnails.dispose(); models.dispose(); samples.dispose(); section.dispose();
    }
}
