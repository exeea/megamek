/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardHud.GAP;
import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCardGrid;
import megamek.client.ui.gdx.UiChoiceGrid;
import megamek.client.ui.gdx.UiHexSides;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiList;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiNumber;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiMenuList;
import megamek.common.Configuration;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;

/** Native UIKit workspace. All document changes are commands to the EDT-owned editor session. */
final class GpuBoardEditor implements com.badlogic.gdx.utils.Disposable {
    static final float LIBRARY_WIDTH = 260, INSPECTOR_WIDTH = 280, TOOLBAR_HEIGHT = 34;
    /**
     * The corner panels over the board area: the Brush panel at most this wide (its controls wrap inside it), the side
     * view this wide when open, and its one-line bar at most this wide when collapsed. When the board area is narrower
     * than an open side view beside a full Brush panel, the side view starts collapsed.
     */
    private static final float BRUSH_WIDTH = 340, SIDE_WIDTH = 380, SIDE_BAR_WIDTH = 300;
    /** The smallest Layers or Edit region of the right column. */
    private static final float SPLIT_MIN = 60;
    /** Layers take their natural height up to this share of the right column until the divider is dragged. */
    private static final float SPLIT_NATURAL = .45f;
    private static final int PAGE_SIZE = 18;
    /** Layers type icons, one table: components by id, objects by palette group (see {@link #layerIcon}). */
    private static final Map<String, String> LAYER_ICONS = Map.ofEntries(
          Map.entry("ground", "hex"), Map.entry("pavement", "hex"), Map.entry("water", "water"), Map.entry("vegetation", "tree"),
          Map.entry("road", "road"), Map.entry("building", "building"), Map.entry("bridge", "bridge"),
          Map.entry("fuelTank", "fuel-tank"), Map.entry("industry", "factory"),
          Map.entry("Trees and plants", "tree"), Map.entry("Alien plants", "tree"), Map.entry("Maglev and wagons", "train"),
          Map.entry("Vehicles", "car"), Map.entry("Animals", "animal"));
    /** A tool's bare key (positional, US-named) and icon; the session's Tool stays a plain enum. */
    private record ToolStyle(int key, String icon) {
        String keyText() { return Input.Keys.toString(key); }
    }
    private static final Map<BoardEditorSession.Tool, ToolStyle> TOOLS = new EnumMap<>(Map.of(
          BoardEditorSession.Tool.SELECT, new ToolStyle(Input.Keys.Z, "tool-select"),
          BoardEditorSession.Tool.SCULPT, new ToolStyle(Input.Keys.X, "tool-sculpt"),
          BoardEditorSession.Tool.PAINT, new ToolStyle(Input.Keys.C, "tool-paint"),
          BoardEditorSession.Tool.ERASE, new ToolStyle(Input.Keys.V, "tool-erase")));
    /** The issues triangle's plain red: the delete icon's hover red. */
    private static final com.badlogic.gdx.graphics.Color ISSUE_RED = com.badlogic.gdx.graphics.Color.valueOf("FF554C");
    private static final float ISSUE_ICON = 24;
    /** Issue rows the dropdown lists; a longer list ends with a count of the rest. */
    private static final int ISSUE_ROWS = 200;
    /** The Select hint shared by the tooltip and the inspector. */
    private static final String SELECT_HINT = "Drag selects in a box (Shift adds, Ctrl removes); drag the selection to move it";
    private final BoardSource source;
    private final UiKit ui;
    private final Stage stage;
    private final Supplier<GpuTerrain> terrain;
    private final BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
    private final Table toolbar, library, inspector, detail = new Table();
    /** The Assets panel's category: one compact drop-down listing every category under its section. */
    private final UiButton categoryFace;
    /** The right column: Layers above Edit, each with its own scroll, and a draggable divider between them. */
    private final SplitPane split;
    /** The divider's share as the user last dragged it, or negative while Layers take their natural height. */
    private float userSplit = GUIPreferences.getInstance().getBoardEditorLayersSplit();
    /** The side view in the board area's bottom-right corner: a header that collapses it, over the hex section. */
    private final Table sideView;
    private final Label sideSummary;
    private final UiButton sideToggle;
    private final com.badlogic.gdx.scenes.scene2d.ui.Cell<Table> sideBody;
    /** The section's Reveal toggle, in the header while the side view is open. */
    private final UiButton sectionReveal;
    private final com.badlogic.gdx.scenes.scene2d.ui.Cell<UiButton> sideRevealCell;
    /** The direction the open header names ("looking N"); it follows the camera's yaw. */
    private String sideLooking = "";
    /**
     * The item the pointer is over in Layers, and on the board in the inspected hex: a {@link BoardEditorSession.Level}
     * key. With the side view's own hover it is highlighted in all three places; see {@link #hovered()}.
     */
    private String layersHover = "", boardHover = "", shownHover;
    /** The items of the shown hover (a shared pill stands for several), and those of them the 3D view outlines. */
    private List<String> shownHovers = List.of(), outlined = List.of();
    private boolean sideOpen = GUIPreferences.getInstance().getBoardEditorSideView(), sideViewPlaced;
    /** Shown only while the document has issues; a click lists them, and an issue frames and selects its hex. */
    private final UiKit.Icon issues;
    private final com.badlogic.gdx.scenes.scene2d.ui.TextTooltip issuesTip;
    /** The open issue list, kept current while it is shown. */
    private UiMenuList issueMenu;
    private final Consumer<megamek.common.board.Coords> frame;
    /** An issue's hex waiting to be framed until its selection has applied, and when that wait ends (nanoTime). */
    private megamek.common.board.Coords pendingFrame;
    private long pendingFrameUntil;
    private List<BoardEditorSession.Issue> shownIssues;
    private final Label status, documentName;
    private final Table documentStatus = new Table();
    private final ScrollPane inspectorScroll, layersScroll, libraryScroll;
    private final UiKit.SearchField librarySearch, themeFind;
    private TextField searchField;
    private final AssetManager thumbnails = new AssetManager();
    private final GpuEditorModelPreviews models = new GpuEditorModelPreviews();
    private final GpuEditorTerrainPreviews samples = new GpuEditorTerrainPreviews();
    private record Preview(Image image, String file) { }
    private record Entry(String key, String label, String thumbnail, String type, String owner) { }
    /** A card's "Preview…" label, shown until its preview image has a drawable. */
    private record Loading(Image image, Label label) { }
    private final List<Preview> previews = new ArrayList<>();
    private final List<Loading> loading = new ArrayList<>();
    private final List<UiButton> tools = new ArrayList<>();
    private final List<UiList> contentLists = new ArrayList<>();
    private final UiButton undo, redo;
    private final Label stripTitle;
    private final Table contents = new Table(), sectionPanel, settingsLayer = new Table(), settingsBody = new Table();
    private final Table themeLayer = new Table(), themeGallery;
    private final UiChoiceGrid themeCards;
    private final UiCardGrid libraryGrid = new UiCardGrid(2, 116, 6);
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
    /** The Settings dialog's line for the global theme's result, shown once the revision after Apply arrives. */
    private Label themeResult;
    private long awaitingTheme = Long.MAX_VALUE;
    private final Table brushPanel = new Table();
    private final List<UiNumber> brushNumbers = new ArrayList<>();
    private final List<List<Actor>> brushRows = new ArrayList<>();
    private float brushLayoutWidth = -1;
    /** What the Brush panel shows: the tool, its radius and the active brush, compared by value on a new snapshot. */
    private record BrushState(BoardEditorSession.Tool tool, int radius, BoardEditorSession.Brush brush) { }
    private BrushState brushState, nextBrush;
    private String libraryGroup = "";
    /** The Sculpt tray's hovered-hex hint; null while another tool's tray is shown. */
    private Label sculptHint;
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
    /**
     * What Layers and Edit show, compared by value when a new snapshot or a newly installed anchor arrives; they are
     * rebuilt only when it changed and no edit, list drag, open choice or board drag is in progress.
     */
    private record DetailState(long revision, megamek.common.board.Coords selected, String component,
          List<BoardEditorSession.Property> properties, Map<String, ?> appearance, List<BoardDecoration> objects,
          String object, int elevation, String theme, List<Anchor> anchors) { }
    private record Anchor(String id, float level) { }
    private DetailState detailState, nextDetail;
    /** A press on the board until its button is released: Layers and Edit wait for its end. */
    private boolean boardGesture;
    private Actor revealContent;
    /** One render-thread snapshot of installed placement geometry, shared by the section and its labels. */
    private List<GpuTerrain.EditorObject> installedObjects = List.of();
    /** The terrain {@link #installedObjects} were read from, and whether it was building last frame. */
    private GpuTerrain installedFrom;
    private boolean terrainBuilding;
    /** The terrain and the outlined objects that {@link GpuTerrain#keepShown} last received. */
    private GpuTerrain pinnedBoard;
    private List<String> pinnedOutlined;
    /** Reads of the installed objects so far; an idle editor makes none. */
    int installedReads;

    /** {@code frame} turns the camera to a hex, as an issue click does; the side view looks along {@code azimuth}. */
    GpuBoardEditor(BoardSource source, UiKit ui, Stage stage, Supplier<GpuTerrain> terrain,
          Consumer<megamek.common.board.Coords> frame, java.util.function.DoubleSupplier azimuth) {
        this.source = source; this.ui = ui; this.stage = stage; this.terrain = terrain; this.frame = frame;
        themeCards = ui.choiceGrid(148, 6);
        choices = new UiPopover(ui); stage.addActor(choices);
        thumbnails.setErrorListener((asset, failure) -> previews.stream().filter(preview -> preview.file().equals(asset.fileName))
              .forEach(preview -> preview.image().setUserObject("No preview")));
        toolbar = ui.panel(); toolbar.setName("editor-toolbar"); toolbar.pad(4);
        for (BoardEditorSession.Tool tool : BoardEditorSession.Tool.values()) {
            ToolStyle style = TOOLS.get(tool);
            String label = tool.name().charAt(0) + tool.name().substring(1).toLowerCase(Locale.ROOT);
            UiButton button = button(style.icon(), label, () -> send(Action.TOOL, tool.name()));
            Label key = ui.label(style.keyText(), "hud-small", 10, UiTheme.MUTED);
            button.details.add(key); button.add(key).padLeft(5);
            ui.tip(button).getActor().setText(label + " (" + style.keyText() + ")"
                  + (tool == BoardEditorSession.Tool.SELECT ? " · " + SELECT_HINT : ""));
            button.setName("editor-tool-" + tool.name().toLowerCase(Locale.ROOT));
            toolbar.add(button).padRight(3); tools.add(button);
        }
        undo = button("Undo", () -> send(Action.UNDO)); redo = button("Redo", () -> send(Action.REDO));
        toolbar.add(undo).padLeft(8); toolbar.add(redo).padLeft(3);
        toolbar.add(button("Open", () -> send(Action.OPEN))).padLeft(8);
        toolbar.add(button("Save", () -> send(Action.SAVE))).padLeft(3);
        toolbar.add(button("Save as", () -> send(Action.SAVE_AS))).padLeft(3);
        stage.addActor(toolbar);

        // Assets: a full-height column with a compact category drop-down, search and a vertical two-column card grid.
        library = ui.panel(); library.setName("editor-library"); library.defaults().growX().minWidth(0);
        library.add(ui.header("Assets", null)).row();
        categoryFace = ui.select("Ground", false); categoryFace.setName("editor-category");
        onChange(categoryFace, this::showCategories);
        library.add(categoryFace).height(30).pad(0, 10, 6, 10).row();
        library.add(libraryNavigation).pad(0, 10, 0, 10).row();
        librarySearch = ui.search("Find a choice…"); searchField = librarySearch.field; searchField.setName("editor-search");
        onChange(searchField, () -> { query = searchField.getText().toLowerCase(Locale.ROOT); showLibrary(); });
        library.add(librarySearch).pad(0, 10, 6, 10).row();
        stripTitle = ui.label("Ground", "hud-small", 11.5f, UiTheme.MUTED); stripTitle.setEllipsis(true);
        library.add(stripTitle).pad(0, 12, 4, 12).row();
        libraryScroll = ui.scrollList(libraryGrid); libraryScroll.setName("editor-choice-scroll");
        libraryScroll.setFadeScrollBars(false);
        library.add(libraryScroll).grow().minSize(0).pad(0, 8, 8, 4);
        stage.addActor(library);
        // Brush: the bottom-left corner of the board area, for the tools with options (Paint, Sculpt, Erase).
        ui.panel(brushPanel); brushPanel.pad(6, 8, 6, 8); brushPanel.setName("editor-brush"); stage.addActor(brushPanel);

        // The right column: the hex title and Contents tools over Layers and Edit, split by a draggable divider.
        inspector = ui.panel(); inspector.setName("editor-inspector"); inspector.defaults().growX().minWidth(0);
        hexTitle = ui.label("HEX INSPECTOR", "hud-title", 16, UiTheme.TEXT);
        inspector.add(hexTitle).pad(12, 12, 6, 12).row();
        Table contentsHead = new Table(); contentsHead.add(ui.caption("Layers")).growX().left();
        contentsHead.add(button("Copy", () -> send(Action.COPY))); contentsHead.add(button("Paste", () -> send(Action.PASTE))).padLeft(3);
        inspector.add(contentsHead).pad(4, 10, 4, 10).row();
        contents.setName("editor-contents"); contents.top();
        Table layers = new Table(); layers.top(); layers.add(contents).growX().minWidth(0).pad(0, 6, 6, 6);
        layersScroll = ui.scrollList(layers); layersScroll.setName("editor-layers-scroll");
        detail.setName("editor-details");
        inspectorScroll = ui.scrollList(detail); inspectorScroll.setName("editor-inspector-scroll");
        split = new SplitPane(layersScroll, inspectorScroll, true, ui.skin, "hud-split"); split.setName("editor-split");
        split.addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                // The divider lies between Edit's top and Layers' bottom; follow only a drag that starts there.
                return event.getTarget() == split && y >= inspectorScroll.getTop() - 1 && y <= layersScroll.getY() + 1;
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                // Remember where the user left the divider.
                userSplit = split.getSplitAmount(); float value = userSplit;
                javax.swing.SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardEditorLayersSplit(value));
            }
        });
        inspector.add(split).grow().minSize(0);
        documentName = ui.label("", "hud-small", 12, UiTheme.TEXT); documentName.setEllipsis(true);
        documentName.setName("editor-document-name");
        status = ui.label("", "hud-small", 10, UiTheme.MUTED); status.setEllipsis(true);
        documentStatus.setName("editor-document-status"); documentStatus.setTouchable(Touchable.disabled);
        documentStatus.pad(3, 0, 3, 0);
        documentStatus.add(documentName).growX().minWidth(0).left().row();
        documentStatus.add(status).growX().minWidth(0).left();
        stage.addActor(inspector);
        stage.addActor(documentStatus);
        issues = ui.icon("warn", ISSUE_ICON, ISSUE_RED); issues.setName("editor-issues"); issues.setVisible(false);
        issues.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { showIssues(); }
        });
        issuesTip = ui.tip(issues);
        stage.addActor(issues);
        section = new GpuHexSection(source, terrain, ui, azimuth, this::objectLabel);
        sectionPanel = new Table(); sectionPanel.setName("editor-section-panel");
        sectionPanel.add(section).growX().minWidth(0).height(section.getPrefHeight());
        sectionReveal = button("Reveal", () -> { });
        sectionReveal.setName("editor-section-reveal");
        onChange(sectionReveal, () -> { sectionReveal.pressed(!sectionReveal.isChecked()); section.reveal(sectionReveal.isChecked()); });
        ui.tip(sectionReveal).getActor().setText("Reveal selected · show only the selected object, fitted");
        // Side view: the bottom-right corner of the board area; its header collapses it to a one-line summary.
        sideView = ui.panel(); sideView.setName("editor-side-view"); sideView.pad(4, 8, 6, 8); sideView.defaults().growX().minWidth(0);
        sideToggle = ui.button("hud-mini", sideOpen ? "chevron-down" : "chevron-up", "", null); sideToggle.setName("editor-side-view-toggle");
        sideToggle.clearChildren(); sideToggle.pad(0); sideToggle.icons.forEach(icon -> sideToggle.add(icon).size(18));
        onChange(sideToggle, () -> { stage.setKeyboardFocus(null); openSideView(!sideOpen); });
        ui.tip(sideToggle).getActor().setText("Collapse or expand the side view");
        sideSummary = ui.label("", "hud-small", 11.5f, UiTheme.MUTED); sideSummary.setEllipsis(true);
        sideSummary.setName("editor-side-view-summary");
        Table sideHeader = new Table(); sideHeader.add(ui.caption("Hex section")).left().padRight(8);
        sideHeader.add(sideSummary).growX().minWidth(0).left();
        sideRevealCell = sideHeader.add(sectionReveal).height(22).padLeft(6);
        if (!sideOpen) { sideRevealCell.setActor(null); }
        sideHeader.add(sideToggle).size(24).padLeft(6);
        sideView.add(sideHeader).height(26).row();
        sideBody = sideView.add(sectionPanel);
        stage.addActor(sideView);
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

    private UiButton button(String label, Runnable action) { return button(null, label, action); }
    private UiButton button(String icon, String label, Runnable action) {
        UiButton button = ui.button("hud-mini", icon, label, null);
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
            menu.item(tool.name(), TOOLS.get(tool).keyText(), snapshot != null && snapshot.tool() == tool, false, true,
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
        // The right-click does not change the selection, so both act on what is selected.
        boolean group = snapshot != null && snapshot.group().isEmpty()
              && snapshot.selection().stream().filter(item -> !item.object().isEmpty()).count() > 1;
        menu.item("Group", "Ctrl + G", null, false, group,
              () -> { close.run(); send(Action.GROUP); }).setName("editor-context-group");
        menu.item("Ungroup", "Ctrl + Shift + G", null, false, snapshot != null && snapshot.groups().stream().anyMatch(g -> !g.isEmpty()),
              () -> { close.run(); send(Action.UNGROUP); }).setName("editor-context-ungroup");
        menu.separator();
        menu.item("Side view", "", sideOpen, false, true,
              () -> { close.run(); openSideView(!sideOpen); }).setName("editor-context-side-view");
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
        issues.setBounds(statusRight - ISSUE_ICON, toolbar.getY() + (TOOLBAR_HEIGHT - ISSUE_ICON) / 2, ISSUE_ICON, ISSUE_ICON);
        statusRight -= ISSUE_ICON + GAP;
        documentStatus.setBounds(statusLeft, toolbar.getY(), Math.max(0, statusRight - statusLeft), TOOLBAR_HEIGHT);
        library.setBounds(GAP, GAP, LIBRARY_WIDTH, Math.max(120, height - top - GAP));
        inspector.setBounds(width - INSPECTOR_WIDTH - GAP, GAP, INSPECTOR_WIDTH, Math.max(120, height - top - GAP));
        inspector.validate();
        if (!sideViewPlaced) {
            // The first layout decides: a board area too narrow for both corner panels starts with the side view collapsed.
            sideViewPlaced = true;
            if (boardAreaWidth() < BRUSH_WIDTH + GAP + SIDE_WIDTH) { openSideView(false, false); }
        }
        fitSplit();
        layoutCorners();
        if (designLayer.isVisible()) { layoutDesigns(); }
        layoutThemes();
    }

    private float boardAreaWidth() { return inspector.getX() - library.getRight() - 2 * GAP; }

    /**
     * The corner panels sized to their content: the side view right-aligned at its fixed width (its bar shrinks for the
     * Brush panel), the Brush panel left-aligned in what remains, wrapping its controls there, or above the side view
     * when what remains is too narrow.
     */
    private void layoutCorners() {
        float area = boardAreaWidth();
        float sideWidth = sideOpen ? Math.min(SIDE_WIDTH, area) : Math.min(SIDE_BAR_WIDTH, Math.max(160, area - BRUSH_WIDTH - GAP));
        sideView.setWidth(sideWidth); sideView.invalidate(); sideView.validate();
        sideView.setBounds(inspector.getX() - GAP - sideWidth, GAP, sideWidth, sideView.getPrefHeight());
        sideView.validate();
        // Too narrow for the Brush panel beside the side view: it stands above the side view instead of under it.
        float beside = area - sideWidth - GAP;
        if (beside < 160) { layoutBrush(Math.min(BRUSH_WIDTH, area), sideView.getTop() + GAP); }
        else { layoutBrush(Math.min(BRUSH_WIDTH, beside), GAP); }
    }

    /** Related controls share a row, not column widths with unrelated rows. Wrap whole controls on small viewports. */
    private void layoutBrush(float available, float bottom) {
        brushPanel.setVisible(!brushRows.isEmpty());
        if (brushLayoutWidth != available) {
            brushLayoutWidth = available;
            float inner = available - 16;
            brushPanel.clearChildren(); brushPanel.left(); brushPanel.defaults().left();
            for (List<Actor> controls : brushRows) {
                Table row = new Table(); row.left();
                float used = 0;
                for (Actor control : controls) {
                    if (control instanceof Label label && label.getWrap()) {
                        // A wrapped hint takes the panel's width on a row of its own.
                        if (used > 0) { brushPanel.add(row).pad(2).row(); row = new Table(); row.left(); used = 0; }
                        // Its wrapped height follows from its width, which must be known before the panel is measured.
                        label.setWidth(inner - 4); label.invalidate();
                        row.add(control).width(inner - 4); used = inner;
                        continue;
                    }
                    float preferred = control instanceof com.badlogic.gdx.scenes.scene2d.utils.Layout layout
                          ? layout.getPrefWidth() : control.getWidth();
                    float cellWidth = Math.min(preferred, inner - 4);
                    if (used > 0 && used + 8 + cellWidth > inner - 4) {
                        brushPanel.add(row).pad(2).row(); row = new Table(); row.left(); used = 0;
                    }
                    row.add(control).width(cellWidth).padLeft(used == 0 ? 0 : 8);
                    used += cellWidth + (used == 0 ? 0 : 8);
                }
                brushPanel.add(row).pad(2).row();
            }
        }
        brushPanel.invalidate();
        brushPanel.setBounds(library.getRight() + GAP, bottom, Math.min(available, brushPanel.getPrefWidth()), brushPanel.getPrefHeight());
        brushPanel.validate();
    }

    private void brushRow(Actor... controls) { brushRows.add(List.of(controls)); }

    /** Opens or collapses the side view, remembering the choice when the user made it. */
    private void openSideView(boolean open) { openSideView(open, true); }
    private void openSideView(boolean open, boolean remember) {
        sideOpen = open;
        sideBody.setActor(open ? sectionPanel : null);
        sideRevealCell.setActor(open ? sectionReveal : null);
        if (snapshot != null) { sideSummary.setText(sideSummary(snapshot)); }
        ((UiKit.Icon) sideToggle.icons.getFirst()).setDrawable(ui.skin.getDrawable(open ? "icon-chevron-down" : "icon-chevron-up"));
        if (remember) { javax.swing.SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardEditorSideView(open)); }
        if (width > 0) { layoutCorners(); }
    }

    /** The divider where the user left it, or Layers at their natural height up to their share of the column. */
    private void fitSplit() {
        float available = split.getHeight() - split.getStyle().handle.getMinHeight();
        if (available <= 2 * SPLIT_MIN) { return; }
        float least = SPLIT_MIN / available;
        split.setMinSplitAmount(least); split.setMaxSplitAmount(1 - least);
        float natural = (contents.getPrefHeight() + 6) / available;
        split.setSplitAmount(MathUtils.clamp(userSplit >= 0 ? userSplit : Math.min(natural, SPLIT_NATURAL), least, 1 - least));
    }

    private void layoutThemes() {
        ui.fitSearch(themeGallery.getCell(themeFind), false);
        ui.fitChoices(themeGallery, themeScroll, themeCards, width - UiKit.DIALOG_MARGIN, height - UiKit.DIALOG_MARGIN);
        if (!themeFind.isVisible() && themeCards.getPrefHeight() > themeGallery.getCell(themeScroll).getPrefHeight() + .5f) {
            ui.fitSearch(themeGallery.getCell(themeFind), true);
            ui.fitChoices(themeGallery, themeScroll, themeCards, width - UiKit.DIALOG_MARGIN, height - UiKit.DIALOG_MARGIN);
        }
    }
    /** The board area's clearance above the corner panels, which explicit framing keeps its target above. */
    float bottomInset() { return Math.max(brushPanel.isVisible() ? brushPanel.getTop() : 0, sideView.getTop()) + GAP; }
    float topInset() { return height - library.getTop(); }

    /** A press on the board: Layers and Edit keep their rows until its button is released. */
    void boardPress() { boardGesture = true; }

    /**
     * One frame. Work is driven by changes: a new snapshot (compared by identity, then by value), a newly installed
     * anchor, a finished preview or a released board drag. Nothing is rebuilt, laid out or keyed while they stay the same.
     */
    void update(long generation) {
        this.generation = generation;
        if (boardGesture && !com.badlogic.gdx.Gdx.input.isButtonPressed(Input.Buttons.LEFT)) { boardGesture = false; }
        // Wait for the rebuilt Layers' layout before scrolling; its row coordinates are provisional until draw.
        if (revealContent != null) {
            layersScroll.validate();
            var point = revealContent.localToAscendantCoordinates(layersScroll.getActor(), new com.badlogic.gdx.math.Vector2());
            layersScroll.scrollTo(point.x, point.y, revealContent.getWidth(), revealContent.getHeight(), false, false);
            layersScroll.updateVisualScroll(); revealContent = null;
        }
        var next = source.editorState();
        if (next == null) { return; }
        boolean changed = next != snapshot;
        snapshot = next;
        if (changed) { refresh(next); }
        if (sculptHint != null && !sculptHint.textEquals(source.editorHint())) { sculptHint.setText(source.editorHint()); }
        // Installed objects change only with a new snapshot or while the terrain builds; the frame after a build reads its result.
        GpuTerrain board = terrain.get();
        boolean building = board != null && board.busy();
        var installed = board == null ? List.<GpuTerrain.EditorObject>of() : installedObjects;
        if (board != null && (changed || building || terrainBuilding || board != installedFrom)) {
            installed = board.editorObjects(next.selected()); installedReads++;
        }
        terrainBuilding = building; installedFrom = board;
        boolean placed = !sameAnchors(installed, installedObjects);
        installedObjects = installed;
        section.update(next, generation, installedObjects, placed);
        // The open header follows the camera's yaw; the hovered item is highlighted in Layers, the side view and 3D.
        if (sideOpen && next.selected() != null && !section.looking().equals(sideLooking)) { sideSummary.setText(sideSummary(next)); }
        String hover = hovered();
        boolean side = sideOpen && !section.hovered().isEmpty();
        // The side view's keys keep their identity until its hover changes.
        if (!hover.equals(shownHover) || side && section.hoveredKeys() != shownHovers) {
            shownHovers.forEach(key -> hoverRow(key, false));
            shownHovers = side ? section.hoveredKeys() : hover.isEmpty() ? List.of() : List.of(hover);
            // The board's own hover is already under the pointer there; Layers and the side view outline theirs.
            outlined = !side && layersHover.isEmpty() ? List.of() : shownHovers.stream()
                  .filter(key -> next.objects().stream().anyMatch(object -> object.id().equals(key))).toList();
            shownHovers.forEach(key -> hoverRow(key, true)); section.highlight(hover); shownHover = hover;
        }
        // The selected objects and those hovered in Layers or the side view draw and pick at every zoom.
        if (board != null && (changed || board != pinnedBoard || outlined != pinnedOutlined)) {
            java.util.Set<String> ids = new java.util.HashSet<>(outlined);
            for (var item : next.selection()) { if (!item.object().isEmpty()) { ids.add(item.object()); } }
            board.keepShown(ids);
            pinnedBoard = board;
            pinnedOutlined = outlined;
        }
        if (changed || placed) {
            nextDetail = new DetailState(next.revision(), next.selected(), next.component(), next.properties(), next.appearance(),
                  next.objects(), next.object(), next.elevation(), next.theme(),
                  installedObjects.stream().map(object -> new Anchor(object.id(), object.anchorLevel())).toList());
        }
        boolean editing = stage.getKeyboardFocus() instanceof TextField;
        // Keep an in-progress numeric edit intact; a completed command also refreshes rejected values.
        if (nextBrush != null && !nextBrush.equals(brushState) && !editing && brushNumbers.stream().noneMatch(UiNumber::editing)) {
            brushState = nextBrush; showBrush();
        }
        // An issue's hex is framed after its selection switched to Select, so the insets are those of Select's panels.
        if (pendingFrame != null && (System.nanoTime() > pendingFrameUntil || pendingFrame.equals(next.selected())
              && next.tool() == BoardEditorSession.Tool.SELECT && java.util.Objects.equals(nextBrush, brushState))) {
            if (System.nanoTime() <= pendingFrameUntil) { frame.accept(pendingFrame); }
            pendingFrame = null;
        }
        if (nextDetail != null && !nextDetail.equals(detailState) && !editing && !boardGesture
              && numbers.stream().noneMatch(UiNumber::editing) && contentLists.stream().noneMatch(UiList::busy)
              && !section.dragging() && !choices.isVisible() && !themeLayer.isVisible() && !designLayer.isVisible()) {
            detailState = nextDetail; showContents(); showDetails();
            if (userSplit < 0) { fitSplit(); }
        }
        if (previews.removeIf(preview -> preview.image().getStage() == null)) {
            for (String file : thumbnails.getAssetNames()) {
                if (previews.stream().noneMatch(preview -> preview.file().equals(file))) { thumbnails.unload(file); }
            }
        }
        // Give document edits the GL upload budget before constructing more palette previews.
        if (terrain.get() == null || !terrain.get().busy()) {
            thumbnails.update(2);
            models.update();
            samples.update(terrain.get());
        }
        stage.getViewport().apply();
        for (Preview preview : previews) {
            if (!"Ready".equals(preview.image().getUserObject()) && thumbnails.isLoaded(preview.file(), Texture.class)) {
                preview.image().setDrawable(new TextureRegionDrawable(new TextureRegion(thumbnails.get(preview.file(), Texture.class))));
                preview.image().setUserObject("Ready");
            }
        }
        loading.removeIf(card -> {
            if (card.label().getStage() == null || card.image().getDrawable() != null) { card.label().setVisible(false); return true; }
            Object note = card.image().getUserObject();
            if (note != null && !card.label().textEquals(note.toString())) { card.label().setText(note.toString()); }
            return false;
        });
    }

    /** The hovered item as a level key of the inspected hex: the side view's, else a Layers row's, else the board's. */
    private String hovered() {
        String own = sideOpen ? section.hovered() : "";
        return !own.isEmpty() ? own : !layersHover.isEmpty() ? layersHover : boardHover;
    }

    /** The objects hovered in the side view (all of a shared pill's) or Layers, which the 3D view outlines. */
    List<String> hoveredObjects() { return outlined; }

    /** The object under the board pointer in the inspected hex, or "". */
    void boardHover(String object) { boardHover = object; }

    /** Shows the hover look on the Layers row of a level key: an object's row, or a component's. */
    private void hoverRow(String key, boolean on) {
        if (key.isEmpty()) { return; }
        Actor row = contents.findActor("editor-content-" + key);
        if (row == null) { row = contents.findActor("editor-component-" + key); }
        if (row instanceof UiButton button) { button.hovered(on); }
    }

    /** Hovering a Layers row highlights its item in the side view and the 3D view. */
    private void hoverable(Actor row, String key) {
        row.addListener(new InputListener() {
            @Override public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (pointer == -1) { layersHover = key; }
            }
            @Override public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(row)) && layersHover.equals(key)) { layersHover = ""; }
            }
        });
    }

    /** Whether two installed-object lists resolve the same objects at the same anchor levels. */
    private static boolean sameAnchors(List<GpuTerrain.EditorObject> a, List<GpuTerrain.EditorObject> b) {
        if (a.size() != b.size()) { return false; }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id().equals(b.get(i).id()) || a.get(i).anchorLevel() != b.get(i).anchorLevel()) { return false; }
        }
        return true;
    }

    /** What a new snapshot changes outside Layers and Edit: toolbar, document line, issues, side view and cards. */
    private void refresh(BoardEditorSession.Snapshot next) {
        if (!libraryTheme.equals(next.activeBrush().theme())) { libraryTheme = next.activeBrush().theme(); showLibrary(); }
        nextBrush = new BrushState(next.tool(), next.brush(), next.activeBrush());
        for (int i = 0; i < tools.size(); i++) { tools.get(i).pressed(i == next.tool().ordinal()); }
        undo.setDisabled(!next.canUndo()); redo.setDisabled(!next.canRedo());
        if (next.issues() != shownIssues) {
            // The session publishes a new list only when validation changed it.
            shownIssues = next.issues();
            issues.setVisible(!shownIssues.isEmpty());
            issuesTip.getActor().setText(shownIssues.size() + (shownIssues.size() == 1 ? " issue" : " issues") + " · click to list them");
            if (issueMenu != null && choices.isVisible() && issueMenu.isDescendantOf(choices)) {
                if (shownIssues.isEmpty()) { choices.cancel(); } else { showIssues(); }
            }
        }
        // Hexes are written by their board number everywhere in the editor: "0807".
        String coords = next.selected() == null ? "" : next.selected().getBoardNum();
        documentName.setText(next.title() + (coords.isEmpty() ? "" : " (" + coords + ")"));
        String feedback = next.message().equals("Hex " + coords) || next.message().equals("Select a hex to edit it.") ? "" : next.message();
        if (!status.textEquals(feedback)) {
            status.setText(feedback); status.setVisible(!feedback.isEmpty());
            documentStatus.getCell(status).height(feedback.isEmpty() ? 0 : status.getPrefHeight());
        }
        sideSummary.setText(sideSummary(next));
        if (themeResult != null && next.revision() > awaitingTheme) { themeResult.setText(next.message()); awaitingTheme = Long.MAX_VALUE; }
        libraryCards.removeIf(card -> card.getStage() == null);
        for (UiButton card : libraryCards) { card.pressed(card.getUserObject().equals(next.activeBrush().key())); }
    }

    /**
     * The side view's header line: while open the hex and the direction the section looks along ("0807 · looking N"),
     * collapsed the hex, its ground level and what stands on it.
     */
    private String sideSummary(BoardEditorSession.Snapshot next) {
        if (next.selected() == null) { return "No hex selected"; }
        sideLooking = section.looking();
        if (sideOpen) { return next.selected().getBoardNum() + " · " + sideLooking; }
        int objects = next.objects().size();
        return next.selected().getBoardNum() + " · Ground L" + next.elevation()
              + (objects == 0 ? "" : " · " + objects + (objects == 1 ? " object" : " objects"));
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

    /** Opens the category list under the Assets drop-down: every category, grouped under its section. */
    private void showCategories() {
        stage.setKeyboardFocus(null); numbers.forEach(UiNumber::dismiss);
        UiMenuList list = new UiMenuList(ui); list.setName("editor-category-list");
        for (String section : List.of("Terrain", "Features", "Structures", "Props", "Decals")) {
            list.add(ui.caption(section)).left().pad(8, 14, 2, 14).row();
            boolean assets = section.equals("Props") || section.equals("Decals");
            List<Option> entries = assets ? blueprint.assets().stream().filter(BoardEditorBlueprint.Asset::palette)
                  .filter(a -> a.kind().equals(section.equals("Props") ? "prop" : "decal"))
                  .map(BoardEditorBlueprint.Asset::group).distinct().sorted().map(g -> new Option(g, g)).toList()
                  : blueprint.components().stream().filter(c -> c.palette() && c.category().equals(section)).map(c -> new Option(c.label(), c.id())).toList();
            for (Option entry : entries) {
                list.item(entry.label(), null, entry.id().equals(libraryOwner), false, true, () -> {
                    choices.cancel(); category = section; objects = assets; browse(entry.id());
                }).setName("editor-library-" + entry.id());
            }
        }
        choices.header(null, null).content(list);
        var corner = categoryFace.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
        choices.showAt(corner.x, corner.y - 2);
    }

    private void showLibrary() {
        libraryCards.clear(); models.clear();
        String label = objects ? libraryOwner : blueprint.component(libraryOwner).label();
        categoryFace.setText(category + " · " + label);
        libraryNavigation.clearChildren();
        var groups = objects ? List.<String>of() : designVariants(libraryOwner).stream().map(BoardEditorBlueprint.Variant::group).distinct().sorted().toList();
        if (groups.size() > 1) {
            List<Option> filters = new ArrayList<>(); filters.add(new Option("All designs", "")); groups.forEach(g -> filters.add(new Option(g, g)));
            libraryNavigation.add(choice("", libraryGroup.isEmpty() ? "All designs" : libraryGroup, filters,
                  value -> { libraryGroup = value; showLibrary(); })).growX().minWidth(0).padBottom(6).row();
        }
        var matching = libraryEntries().stream().filter(e -> (e.label() + " " + e.key()).toLowerCase(Locale.ROOT).contains(query)).toList();
        stripTitle.setText(matching.size() + (matching.size() == 1 ? " choice" : " choices"));
        libraryGrid.items(matching.size(), index -> {
            Entry entry = matching.get(index);
            Image preview = new Image(); preview(entry, preview);
            UiButton choose = visualCard(entry.label(), preview, 70, () -> {
                if (entry.type().equals("asset")) { send(Action.ASSET, entry.key()); }
                else { send(Action.CHOOSE_BRUSH, entry.owner(), entry.key()); }
            });
            String key = entry.type().equals("preset") ? entry.owner() + "/" + entry.key() : entry.key();
            choose.setName("editor-library-" + key); choose.setUserObject(key);
            if (snapshot != null) { choose.pressed(key.equals(snapshot.activeBrush().key())); }
            libraryCards.removeIf(card -> card.getStage() == null); libraryCards.add(choose); return choose;
        });
        libraryScroll.setScrollY(0); libraryScroll.updateVisualScroll();
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
                if (asset.kind().equals("prop")) { models.add(asset.model(), asset.colours(), image); }
                else { thumbnail(megamek.common.board.BoardDecalArt.image(asset.id()), image); }
            }
        }
    }

    private void thumbnail(String relative, Image image) {
        thumbnail(new java.io.File(Configuration.dataDir(), relative), image);
    }

    private void thumbnail(java.io.File source, Image image) {
        String file = source.getAbsolutePath();
        if (source.isFile()) {
            if (!thumbnails.contains(file)) { thumbnails.load(file, Texture.class); }
            previews.add(new Preview(image, file));
        } else { image.setUserObject("No preview"); }
    }

    /**
     * The Brush panel follows the active tool: Paint shows the active brush's options, Sculpt its mode, Erase the radius;
     * each ends with the tool's gesture hint. Select has no brush options, and the panel hides.
     */
    private void showBrush() {
        brushNumbers.forEach(UiNumber::close); brushNumbers.clear(); brushRows.clear(); brushLayoutWidth = -1;
        var brush = snapshot.activeBrush();
        var tool = snapshot.tool();
        sculptHint = null;
        if (tool != BoardEditorSession.Tool.SELECT) {
            String name = tool.name().charAt(0) + tool.name().substring(1).toLowerCase(Locale.ROOT);
            Label title = ui.label(tool == BoardEditorSession.Tool.SCULPT ? name : name + " · " + brush.label(), "hud-title", 12, UiTheme.TEXT);
            title.setName("editor-brush-title"); title.setEllipsis(true); brushRow(title);
            boolean route = brush.object() != null && megamek.common.board.MaglevRoute.isRoute(brush.object());
            // A stamp brush keeps only its id: it has no options, and each click places its layout as one new group.
            boolean stamp = brush.object() == null && brush.component().isEmpty();
            if (tool == BoardEditorSession.Tool.SCULPT) { showSculpt(brush); }
            else if (stamp) { brushRow(hint("Click a hex to stamp this layout as one group · select the group to edit or delete it")); }
            else if (tool == BoardEditorSession.Tool.ERASE) {
                if (!route) { brushRow(brushNumber("Radius", snapshot.brush(), 1, 20, 1, Action.BRUSH, "")); }
                brushRow(hint(brush.sampledHex() != null ? "Click or drag: clear whole hexes" : brush.object() == null ? "Click or drag: remove this from the hexes" : "Click or drag: remove these objects"
                      + (route ? "" : " within the radius")));
            } else if (brush.object() != null) { objectControls(brush.object()); }
            else {
                if (brush.sampledHex() != null) {
                    Image preview = new Image(); preview.setName("editor-hex-stamp-preview"); samples.add(brush, preview);
                    Table face = new Table(); face.add(previewImage(preview)).growX().minWidth(0).prefWidth(256).height(160);
                    brushRow(face);
                }
                componentControls(brush);
            }
        }
        if (width > 0) { layoutCorners(); }
    }

    /** A terrain brush's radius and the options its component offers for painting. */
    private void componentControls(BoardEditorSession.Brush brush) {
        List<Actor> controls = new ArrayList<>();
        controls.add(brushNumber("Radius", snapshot.brush(), 1, 20, 1, Action.BRUSH, ""));
        var definition = blueprint.component(brush.component());
        if (brush.sampledHex() != null || definition.id().equals("ground")) {
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
        brushRow(hint(brush.sampledHex() != null ? "Click or drag to replace hexes with this stamp, including all terrain and objects"
              : "Click or drag to paint · Ctrl+wheel changes the hovered hex's level"));
    }

    /** A gesture hint: muted, wrapped to the Brush panel's width on a row of its own. */
    private Label hint(String text) {
        Label hint = ui.label(text, "hud-small", 11.5f, UiTheme.MUTED); hint.setWrap(true); hint.setName("editor-brush-hint");
        return hint;
    }

    private BoardDecoration selectedObject() {
        return snapshot.objects().stream().filter(object -> object.id().equals(snapshot.object())).findFirst().orElse(null);
    }

    /** The paint template of an object brush; the inspector edits a placed instance with the same values. */
    private void objectControls(BoardDecoration object) {
        Action action = Action.BRUSH_VALUE;
        String prefix = "object:";
        if (megamek.common.board.MaglevRoute.isRoute(object)) {
            brushRow(routeHeight(object, true, action));
            brushRow(hint("Drag across hexes · automatic turns and slopes"));
            return;
        }
        var brush = snapshot.activeBrush();
        var precision = ui.checkbox("Precision mode", brush.precision()); precision.setName("editor-precision");
        onChange(precision, () -> send(Action.BRUSH_VALUE, "precision", Boolean.toString(precision.isTicked())));
        ui.tip(precision).getActor().setText("Place at the exact pointer position; otherwise use the hex centre. Both follow the clicked surface.");
        brushRow(precision);
        brushRow(rotations(object, true, action), brushNumber("Scale", object.scale(), .05, 8, .05, action, prefix + "scale"));
        // A brush sampled from a stretched object places stretched copies: it shows the stretch, which can be reset there.
        if (!object.stretch().equals(BoardDecoration.Stretch.NONE)) { brushRow(stretch(object, true, action)); }
        var mirror = ui.checkbox("Mirror", object.mirror()); mirror.setName("editor-tray-mirror");
        onChange(mirror, () -> send(action, prefix + "mirror", Boolean.toString(mirror.isTicked())));
        if (object.kind().equals("prop")) {
            brushRow(brushNumber("Height offset", heightOffset(object), -10, 30, .1, action, prefix + "offset"), mirror);
        } else {
            var span = ui.checkbox("Span hexes", !object.clipToHex()); span.setName("editor-tray-span");
            onChange(span, () -> send(action, prefix + "clipToHex", Boolean.toString(!span.isTicked())));
            brushRow(span, mirror, brushNumber("Paint order", object.drawOrder(), -20, 20, 1, action, prefix + "order"));
        }
        var asset = blueprint.asset(object.asset());
        if (asset != null && asset.snap() != null) {
            var magnetic = ui.checkbox("Magnetic snap", brush.magnetic()); magnetic.setName("editor-magnetic");
            onChange(magnetic, () -> send(Action.BRUSH_VALUE, "magnetic", Boolean.toString(magnetic.isTicked())));
            ui.tip(magnetic).getActor().setText("Join compatible connectors at the same height and scale, with opposing orientations.");
            brushRow(magnetic);
        }
        brushRow(hint("Click a hex to place one · each click places another"));
    }

    private double heightOffset(BoardDecoration object) {
        var placement = object.placement();
        return placement.offset() == null ? placement.level() - snapshot.elevation() : placement.offset();
    }

    /** Sculpt's options share the tray: mode, Slope and the common radius, plus the hovered hex's hint. */
    private void showSculpt(BoardEditorSession.Brush brush) {
        var modes = megamek.client.ui.boardeditor.LevelSculpt.Mode.values();
        UiKit.Segmented mode = ui.segmented("hud-seg", false, "Raise", "Lower", "Level");
        for (int i = 0; i < modes.length; i++) {
            var value = modes[i];
            mode.buttons.get(i).setName("editor-sculpt-" + value.name().toLowerCase(Locale.ROOT));
            onChange(mode.buttons.get(i), () -> send(Action.BRUSH_VALUE, "sculpt", value.name()));
        }
        mode.select(brush.sculpt().ordinal());
        var slope = ui.checkbox("Slope", brush.slope()); slope.setName("editor-sculpt-slope");
        onChange(slope, () -> send(Action.BRUSH_VALUE, "slope", Boolean.toString(slope.isTicked())));
        ui.tip(slope).getActor().setText("Step neighbours so no edge differs by more than one level; existing cliffs stay. Off raises plateaus with cliffs.");
        brushRow(brushNumber("Radius", snapshot.brush(), 1, 20, 1, Action.BRUSH, ""), mode, slope);
        sculptHint = ui.label(source.editorHint(), "hud-small", 12, UiTheme.TEXT); sculptHint.setName("editor-sculpt-hint");
        Table hint = new Table(); hint.add(sculptHint).width(130).left();
        String gesture = switch (brush.sculpt()) {
            case RAISE -> "Click or drag: raise · Ctrl: lower";
            case LOWER -> "Click or drag: lower · Ctrl: raise";
            case LEVEL -> "Drag: level to the first hex";
        };
        brushRow(hint);
        brushRow(hint(gesture + " · Ctrl+wheel works in every tool"));
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
        card.add(previewImage(preview)).grow().minWidth(0).minHeight(imageHeight).prefHeight(imageHeight).row();
        card.getLabel().setEllipsis(true); card.getLabel().setAlignment(com.badlogic.gdx.utils.Align.center);
        card.add(card.getLabel()).growX().minWidth(0).height(22).padTop(2);
        onChange(card, () -> { stage.setKeyboardFocus(null); select.run(); });
        return card;
    }

    private Stack previewImage(Image preview) {
        if (preview.getName() == null) { preview.setName("editor-card-preview"); }
        preview.setScaling(com.badlogic.gdx.utils.Scaling.fit); preview.setTouchable(Touchable.disabled);
        Label waiting = ui.label("Preview…", "hud-small", 11, UiTheme.MUTED);
        waiting.setAlignment(com.badlogic.gdx.utils.Align.center); waiting.setTouchable(Touchable.disabled);
        // update() hides it once the preview has an image, or shows why there is none.
        if (preview.getDrawable() == null) { loading.add(new Loading(preview, waiting)); } else { waiting.setVisible(false); }
        Table backdrop = new Table(); backdrop.setBackground(ui.skin.newDrawable("white", UiTheme.POP));
        return new Stack(backdrop, preview, waiting);
    }

    private String assetLabel(String key) { var asset = blueprint.asset(key); return asset == null ? key : asset.label(); }
    /** A named object's name, else its asset's label with its first replaced colour, e.g. "Car · Blue". */
    private String objectLabel(BoardDecoration object) {
        if (object.name() != null) { return object.name(); }
        var slots = colourSlots(object.asset());
        for (int index = 0; index < Math.min(slots.size(), object.colours().slots().size()); index++) {
            String colour = object.colours().slot(index);
            if (colour == null) { continue; }
            String preset = slots.get(index).presets().stream().filter(p -> p.colour().equalsIgnoreCase(colour))
                  .map(RigidGlb.ColourSlot.Preset::label).findFirst().orElse(colour);
            return assetLabel(object.asset()) + " · " + preset;
        }
        return assetLabel(object.asset());
    }
    /** The type icon of a component's Layers row; the Ground row keeps the hexagon, other rules terrain shows "rules". */
    private static String layerIcon(BoardEditorBlueprint.Component component) { return LAYER_ICONS.getOrDefault(component.id(), "rules"); }
    /** The type icon of an object's Layers row: decals, then by palette group; decoration without rules shows a star. */
    private String layerIcon(BoardDecoration object) {
        if (object.kind().equals("decal")) { return "decal"; }
        var asset = blueprint.asset(object.asset());
        return asset == null ? "star" : LAYER_ICONS.getOrDefault(asset.group(), "star");
    }
    /** A Layers row; a reorderable one leads with the drag handle, {@code icons.getFirst()}, before its type icon. */
    private UiButton rowButton(String title, String sub, String icon, boolean grip, Runnable select) {
        UiButton button = ui.button("hud-plain", icon, title, null);
        if (grip) {
            Actor type = button.icons.getFirst();
            UiKit.Icon handle = ui.icon("grip", ((UiKit.Icon) type).getPrefWidth(), com.badlogic.gdx.graphics.Color.WHITE);
            Table pair = new Table(); button.getCell(type).setActor(pair);
            pair.add(handle); pair.add(type).padLeft(2);
            button.icons.addFirst(handle);
        }
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
        contents.clearChildren(); contentLists.clear(); contents.top();
        // New rows: the hover is shown on them again next frame.
        layersHover = ""; shownHover = null;
        if (snapshot.selected() == null) {
            hexTitle.setText("HEX INSPECTOR");
            // Layers keeps its region; say why it is empty.
            Label none = ui.label("No hex selected · click a hex to see its layers", "hud-small", 11.5f, UiTheme.MUTED);
            none.setWrap(true); contents.add(none).growX().minWidth(0).left().pad(6);
            return;
        }
        hexTitle.setText("HEX " + snapshot.selected().getBoardNum());
        for (var component : blueprint.components()) {
            if (!component.id().equals("ground") && (component.present().isEmpty()
                  || !component.isPresent(name -> snapshot.property(name) != null))) { continue; }
            UiButton row = rowButton(component.label(), component.id().equals("ground") ? "Level " + snapshot.elevation() : null,
                  layerIcon(component), false, () -> send(Action.COMPONENT, component.id()));
            row.pressed(snapshot.object().isEmpty() && component.id().equals(snapshot.component()));
            row.setName("editor-component-" + component.id()); hoverable(row, component.id());
            contentRow(row, component.id().equals("ground") ? 40 : 30, false, component.present().isEmpty() ? null
                  : () -> send(Action.REMOVE_COMPONENT, component.id()));
            contentObjects(snapshot.objects().stream().filter(object -> !snapshot.grouped(object) && object.placement().receiver() != null
                  && component.receiver().equals(object.placement().receiver().terrain())).toList(), true);
        }
        var detached = snapshot.objects().stream().filter(object -> !snapshot.grouped(object) && (object.placement().receiver() == null
              || blueprint.components().stream().noneMatch(c -> c.receiver().equals(object.placement().receiver().terrain())
                    && c.isPresent(name -> snapshot.property(name) != null)))).toList();
        detached.stream().map(d -> d.placement().receiver()).distinct().forEach(receiver ->
              contentObjects(detached.stream().filter(d -> java.util.Objects.equals(receiver, d.placement().receiver())).toList(), false));
        // Grouped members stay out of the reorderable surface stacks: one header per group, its members here nested below.
        // While the group or one of its members is selected, only that selection's row is listed, keeping the details near.
        var grouped = snapshot.objects().stream().filter(snapshot::grouped).toList();
        grouped.stream().map(BoardDecoration::group).distinct().forEach(group -> {
            var members = grouped.stream().filter(object -> object.group().equals(group)).toList();
            int size = snapshot.groupSizes().get(group);
            UiButton header = rowButton("Group · " + size + " objects" + (members.size() < size ? " · " + members.size() + " here" : ""),
                  null, "stack", false, () -> send(Action.SELECT_OBJECT, group, ""));
            header.setName("editor-group-" + group); header.pressed(group.equals(snapshot.group()));
            UiButton delete = removeButton("", () -> { send(Action.SELECT_OBJECT, group, ""); send(Action.DELETE_SELECTION); });
            ui.tip(delete).getActor().setText("Delete the whole group (" + size + " objects)");
            header.trailingAction(delete, 26);
            contentRow(header, 30, false, null);
            boolean focused = members.stream().anyMatch(member -> member.id().equals(snapshot.object()));
            for (BoardDecoration member : members) {
                if (focused && (group.equals(snapshot.group()) || !member.id().equals(snapshot.object()))) { continue; }
                UiButton row = contentObject(member, false);
                row.pressed(snapshot.group().isEmpty() && member.id().equals(snapshot.object()));
                contentRow(row, 40, true, null);
            }
        });
    }

    private void contentObjects(List<BoardDecoration> objects, boolean attached) {
        if (objects.isEmpty()) { return; }
        UiList stack = new UiList(ui); contentLists.add(stack);
        stack.setName("editor-stack-" + (objects.getFirst().placement().receiver() == null ? "absolute" : objects.getFirst().placement().receiver().terrain()));
        for (BoardDecoration object : objects) {
            UiButton row = contentObject(object, objects.size() > 1);
            Actor handle = objects.size() > 1 ? row.icons.getFirst() : null;
            if (handle != null) {
                handle.setTouchable(Touchable.enabled); handle.setName("editor-reorder-" + object.id());
                ui.tip(handle).getActor().setText("Drag to reorder. Higher decals paint over lower decals on this surface.");
            }
            Table wrapper = new Table(); wrapper.add(row).growX().minWidth(0).height(40).pad(2);
            stack.add(wrapper, handle);
        }
        if (objects.size() > 1) {
            stack.reorderable((from, to) -> send(Action.REORDER_OBJECT, objects.get(from).id(),
                  (to < from ? "before:" : "after:") + objects.get(to).id()));
        }
        contents.add(stack).growX().minWidth(0).padLeft(attached ? 16 : 0).row();
    }

    private UiButton contentObject(BoardDecoration object, boolean reorderable) {
        String level = height(object);
        if (terrain.get() != null) {
            var placed = installedObjects.stream().filter(o -> o.id().equals(object.id())).findFirst();
            if (placed.isPresent()) { level += " · L" + UiNumber.format(placed.get().anchorLevel()); }
        }
        UiButton row = rowButton(objectLabel(object), level, layerIcon(object), reorderable, () -> send(Action.SELECT_OBJECT, object.id()));
        row.setName("editor-content-" + object.id()); row.pressed(object.id().equals(snapshot.object())); hoverable(row, object.id());
        UiButton remove = removeButton("", () -> send(Action.REMOVE_OBJECT, object.id(), ""));
        remove.setName("editor-remove-" + object.id()); row.trailingAction(remove, 26);
        return row;
    }
    private void showDetails() {
        String selection = snapshot.selected() + ":" + snapshot.object() + ":" + snapshot.component();
        boolean selectionChanged = !selection.equals(shownSelection);
        float scroll = selectionChanged ? 0 : inspectorScroll.getScrollY(); shownSelection = selection;
        // A newly selected object's row scrolls into view in Layers; Edit starts at its top, and neither moves the other.
        if (selectionChanged) { revealContent = contents.findActor("editor-content-" + snapshot.object()); }
        numbers.forEach(UiNumber::close); numbers.clear();
        detail.clearChildren(); detail.top(); detail.defaults().growX().minWidth(0).pad(3, 12, 3, 12);
        if (snapshot.selected() == null) {
            // The toolbar's status line is ellipsized; long document messages such as an import summary are readable here.
            if (!snapshot.message().isEmpty() && !snapshot.message().equals("Select a hex to edit it.")) { text(snapshot.message()); }
            text("Select a hex to inspect its terrain and objects. " + SELECT_HINT + "."); return;
        }
        var object = snapshot.objects().stream().filter(d -> d.id().equals(snapshot.object())).findFirst();
        if (!snapshot.group().isEmpty() && object.isPresent()) {
            heading("Group · " + snapshot.selection().size() + " objects");
            text("Drag a member to move the group. Click a member again to edit it alone.");
            Table group = new Table(); group.add(button("Ungroup", () -> send(Action.UNGROUP))).growX();
            group.add(button("Duplicate", () -> send(Action.DUPLICATE_OBJECT))).growX().padLeft(4);
            group.add(removeButton("Delete", () -> send(Action.DELETE_SELECTION))).growX().padLeft(4); detail.add(group).row();
            groupTransform();
            inspectorScroll.validate(); inspectorScroll.setScrollY(scroll); return;
        }
        if (snapshot.selection().size() > 1) {
            heading(snapshot.selection().size() + " selected");
            text("Drag a selected item to move them all. Shift+click toggles an item, Ctrl+click removes it. " + SELECT_HINT + ".");
            Table group = new Table(); group.add(button("Clear selection", () -> send(Action.CLEAR_SELECTION))).growX();
            group.add(button("Delete selected", () -> send(Action.DELETE_SELECTION))).growX(); detail.add(group).row();
        }
        if (object.isPresent()) { object(object.get()); }
        else { component(); }
        inspectorScroll.validate(); inspectorScroll.setScrollY(scroll);
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
        // A bridge's designs are its type: the Built and Natural buttons below.
        if (!designVariants(definition.id()).isEmpty() && !definition.id().equals("bridge")) {
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
        if (definition.id().equals("bridge")) {
            // This hex's state; a click sets the whole connected bridge.
            Boolean built = megamek.common.board.HexAppearance.bridgeBuilt(snapshot.appearance());
            Table type = new Table(); type.defaults().growX().uniformX().minWidth(0).pad(0, 2, 0, 2);
            for (boolean choice : new boolean[] { true, false }) {
                UiButton button = button(choice ? "Built" : "Natural", () -> send(Action.BRIDGE_TYPE, choice ? "built" : "natural"));
                button.setName(choice ? "editor-bridge-built" : "editor-bridge-natural");
                button.pressed(Boolean.valueOf(choice).equals(built));
                ui.tip(button).getActor().setText(choice ? "An artificial bridge: a deck with kerbs, plain asphalt or the"
                      + " surface of its road approach. Applies to the whole connected bridge." : "A natural rock arch, without piers. Applies to the whole connected bridge.");
                type.add(button);
            }
            detail.add(type).growX().padTop(8).row();
            // Only a built bridge has piers.
            if (Boolean.TRUE.equals(built)) {
                boolean on = megamek.common.board.HexAppearance.pillars(snapshot.appearance());
                UiButton pillars = button("Pillars", () -> send(Action.PILLARS, Boolean.toString(!on)));
                pillars.setName("editor-bridge-pillars"); pillars.pressed(on);
                ui.tip(pillars).getActor().setText("Concrete piers under the joints between this bridge's hexes, never at a"
                      + " hex centre. Applies to the whole connected bridge. A one-hex bridge, or a deck less than a level"
                      + " above the ground, has none.");
                detail.add(pillars).padTop(8).row();
            }
        }
        UiButton sample = button("Use as brush", () -> send(Action.SAMPLE)); sample.setName("editor-use-hex-brush");
        ui.tip(sample).getActor().setText("Capture this whole hex as a stamp, including its theme, level, terrain, appearance and objects.");
        detail.add(sample).padTop(8).row();
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

    /** The kit's north-up edge diagram shares the document's clockwise N..NW mask. */
    private void edges(String terrain) {
        var value = snapshot.property(terrain);
        if (value != null && (value.exits() > 63 || value.exits() < 0)) {
            text("Legacy artwork selector " + value.exits() + ". Connections are preserved; this is not an edge mask."); return;
        }
        UiHexSides edges = new UiHexSides(ui, value == null ? 0 : value.exits(),
              side -> send(Action.EDGE, terrain, Integer.toString(side)));
        edges.setName("editor-edges-" + terrain);
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
        if (megamek.common.board.MaglevRoute.isRoute(object)) {
            text("Maglev route · paint or erase hexes, or toggle the sides it leaves by. Visual only; not a walkable bridge.");
            detail.add(routeHeight(object, false, Action.OBJECT_VALUE)).row();
            // A side joins the neighbouring route only while both have it; toggling sets both.
            UiHexSides sides = new UiHexSides(ui, object.connections(), side -> send(Action.OBJECT_VALUE, "side", Integer.toString(side)));
            sides.setName("editor-maglev-sides");
            detail.add(sides).row();
            detail.add(button("Use as brush", () -> send(Action.SAMPLE))).row();
            detail.add(removeButton("Remove", () -> send(Action.REMOVE_OBJECT))).row();
            return;
        }
        var placement = object.placement();
        String support = placement.receiver() == null ? "Fixed height (from file)" : switch (placement.receiver().terrain()) {
            case "bridge" -> "Follows bridge deck"; case "building" -> "Follows building roof";
            case "industrial" -> "Follows industry top"; case "fuelTank" -> "Follows fuel tank top";
            case "ice" -> "Follows ice"; default -> "Follows ground";
        };
        text(support);
        if (object.kind().equals("prop")) {
            number("Height above surface", heightOffset(object), -10, 30, .1, Action.OBJECT_VALUE, "offset");
        } else { number("Paint order", object.drawOrder(), -20, 20, 1, Action.OBJECT_VALUE, "order"); }
        if (object.kind().equals("prop") && terrain.get() != null) {
            var placed = installedObjects.stream().filter(o -> o.id().equals(object.id())).findFirst();
            text(placed.isEmpty() ? "Waiting for geometry, or the chosen surface is missing under this anchor."
                  : "Resolved anchor · L" + UiNumber.format(placed.get().anchorLevel()));
        }
        heading("Transform");
        number("East / hex width", object.x(), -4, 4, .01, Action.OBJECT_VALUE, "x");
        number("North / hex height", object.y(), -4, 4, .01, Action.OBJECT_VALUE, "y");
        detail.add(rotations(object, false, Action.OBJECT_VALUE)).row();
        number("Scale", object.scale(), .05, 8, .05, Action.OBJECT_VALUE, "scale");
        detail.add(stretch(object, false, Action.OBJECT_VALUE)).row();
        if (object.kind().equals("decal")) {
            UiButton overflow = button("Span neighbouring hexes", () -> send(Action.OBJECT_VALUE, "clipToHex", Boolean.toString(!object.clipToHex())));
            overflow.setName("editor-object-span"); overflow.pressed(!object.clipToHex()); detail.add(overflow).row();
        }
        UiButton mirror = button("Mirror", () -> send(Action.OBJECT_VALUE, "mirror", Boolean.toString(!object.mirror())));
        mirror.setName("editor-object-mirror"); mirror.pressed(object.mirror()); detail.add(mirror).row();
        if (object.kind().equals("prop")) { colours(object); }
        if (BoardFeatures.hasSnowForm(object.asset())) {
            // On by default: the tree takes its winter form on a snow surface; off keeps this tree bare there.
            UiButton snow = button("Snow", () -> send(Action.OBJECT_VALUE, "bare", Boolean.toString(!object.bare())));
            snow.setName("editor-object-snow"); snow.pressed(!object.bare());
            ui.tip(snow).getActor().setText("Snow-covered on snowy ground. Off keeps this tree bare.");
            detail.add(snow).row();
        }
        input("Name", object.name() == null ? "" : object.name(), v -> send(Action.OBJECT_VALUE, "name", v));
        detail.add(button("Use as brush", () -> send(Action.SAMPLE))).row();
        Table commands = new Table(); commands.add(button("Duplicate", () -> send(Action.DUPLICATE_OBJECT))).growX();
        commands.add(removeButton("Remove", () -> send(Action.REMOVE_OBJECT))).growX().padLeft(4); detail.add(commands).padTop(12).row();
        text("Shift+click toggles objects, Ctrl+click removes them. " + SELECT_HINT + ".");
    }
    private record Option(String label, String id) { }

    /** Each model's colour slots, read from its file once. */
    private final Map<String, List<RigidGlb.ColourSlot>> colourSlots = new java.util.HashMap<>();

    /**
     * One row per colour slot of the object's model: its preset swatches, then its colour as #rrggbb to type any other.
     * Each choice is the session's object value; the model's own colour stores nothing.
     */
    private void colours(BoardDecoration object) {
        var slots = colourSlots(object.asset());
        if (slots.isEmpty()) { return; }
        heading("Colours");
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            String key = "colour" + index, current = object.colours().slot(index) == null ? slot.colour() : object.colours().slot(index);
            Consumer<String> choose = value -> send(Action.OBJECT_VALUE, key, value.equalsIgnoreCase(slot.colour()) ? "" : value);
            Table swatches = new Table();
            swatches.left();
            for (var preset : slot.presets()) {
                if (swatches.getCells().size % 10 == 0 && swatches.getCells().size > 0) { swatches.row(); }
                Actor swatch = swatch(preset, preset.colour().equals(current), choose);
                swatch.setName("editor-colour-" + index + "-" + swatches.getCells().size);
                swatches.add(swatch).left();
            }
            detail.add(swatches).left().row();
            input(slot.name(), current, choose);
        }
    }

    private List<RigidGlb.ColourSlot> colourSlots(String model) {
        return colourSlots.computeIfAbsent(model, asset -> {
            var source = RigidGlb.source(new java.io.File(Configuration.dataDir(), "models/board"), asset);
            try { return source.file().isFile() ? RigidGlb.colourSlots(source.file()) : List.of(); }
            catch (IllegalArgumentException unreadable) { return List.of(); }
        });
    }

    private Actor swatch(RigidGlb.ColourSlot.Preset preset, boolean chosen, Consumer<String> choose) {
        Table frame = new Table();
        frame.setBackground(ui.skin.newDrawable("white", chosen ? UiTheme.MINT : com.badlogic.gdx.graphics.Color.CLEAR));
        frame.add(new Image(ui.skin.newDrawable("white", com.badlogic.gdx.graphics.Color.valueOf(preset.colour())))).size(16).pad(2);
        frame.setTouchable(Touchable.enabled);
        frame.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { stage.setKeyboardFocus(null); choose.accept(preset.colour()); }
        });
        ui.tip(frame).getActor().setText(preset.label() + " · " + preset.colour());
        return frame;
    }

    /**
     * A whole group's transform, relative to the group as it is: moves from 0, a turn from 0, a scale from 1 and a
     * mirror that flips it. Each commit starts again from these values; offsets and tilt stay per member.
     */
    private void groupTransform() {
        heading("Group transform");
        text("Changes move, turn, scale or mirror the whole group from where it is now; turns, scales and mirrors pivot about its centre.");
        number("Move east / hex width", 0, -4, 4, .01, Action.GROUP_VALUE, "x");
        number("Move north / hex height", 0, -4, 4, .01, Action.GROUP_VALUE, "y");
        detail.add(angle("Turn °", 0, false, Action.GROUP_VALUE, "rotation")).row();
        number("Scale ×", 1, .05, 8, .05, Action.GROUP_VALUE, "scale");
        detail.add(button("Mirror", () -> send(Action.GROUP_VALUE, "mirror", "true"))).row();
    }

    private Table routeHeight(BoardDecoration object, boolean compact, Action action) {
        var placement = object.placement();
        boolean absolute = placement.mode().equals("absolute");
        String prefix = action == Action.BRUSH_VALUE ? "object:" : "";
        var fixed = ui.checkbox("Fixed elevation", absolute);
        fixed.setName("editor-maglev-fixed-height");
        onChange(fixed, () -> send(action, prefix + "receiver", fixed.isTicked() ? "absolute" : "ground/top"));
        String label = absolute ? "Level" : "Height above ground";
        String target = prefix + (absolute ? "level" : "offset");
        double value = absolute ? placement.level() : placement.offset();
        UiNumber number = compact ? brushNumber(label, value, -10, 30, .25, action, target)
              : numeric(label, value, -10, 30, .25, action, target);
        Table row = new Table(); row.add(fixed).left().row(); row.add(number).growX().minWidth(0); return row;
    }
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
    private Table rotations(BoardDecoration object, boolean compact, Action action) {
        Table row = new Table();
        row.add(ui.label("Rotation °", "hud-small", 12, UiTheme.TEXT)).colspan(3).left().padBottom(3).row();
        String[] axes = {"X", "Y", "Z"}, keys = {"rotationX", "rotationY", "rotation"};
        String[] descriptions = {"Pitch · rotate about the east–west X axis", "Roll · rotate about the north–south Y axis",
              "Yaw · turn about the vertical Z axis"};
        double[] values = {object.rotationX(), object.rotationY(), object.rotation()};
        String prefix = action == Action.BRUSH_VALUE ? "object:" : "";
        for (int i = 0; i < 3; i++) {
            UiNumber number = angle(axes[i], values[i], compact, action, prefix + keys[i]).compactCaption().fieldSize(48, 27);
            ui.tip(number).getActor().setText(descriptions[i]);
            row.add(number).growX().minWidth(0).padRight(i == 2 ? 0 : 7);
        }
        return row;
    }
    /**
     * Stretch along the object's own width, length and height, on top of its scale (a decal has no height). Each is the
     * session's object value, so a slider drag is one undo step.
     */
    private Table stretch(BoardDecoration object, boolean compact, Action action) {
        Table row = new Table();
        boolean prop = object.kind().equals("prop");
        row.add(ui.label("Stretch ×", "hud-small", 12, UiTheme.TEXT)).colspan(3).left().padBottom(3).row();
        String[] labels = {"W", "L", "H"}, keys = {"stretchX", "stretchY", "stretchZ"};
        String[] descriptions = {"Width · stretch along the object's own X axis", "Length · stretch along the object's own Y axis",
              "Height · stretch along the object's own Z axis"};
        double[] values = {object.stretch().x(), object.stretch().y(), object.stretch().z()};
        String prefix = action == Action.BRUSH_VALUE ? "object:" : "";
        for (int i = 0; i < (prop ? 3 : 2); i++) {
            UiNumber number = (compact ? brushNumber(labels[i], values[i], .1, 8, .05, action, prefix + keys[i])
                  : numeric(labels[i], values[i], .1, 8, .05, action, prefix + keys[i])).compactCaption().fieldSize(48, 27);
            ui.tip(number).getActor().setText(descriptions[i]);
            row.add(number).growX().minWidth(0).padRight(i == 2 ? 0 : 7);
        }
        // A decal has no height: an empty third column keeps W and L in the rotation row's columns.
        if (!prop) { row.add().growX(); }
        return row;
    }
    /**
     * An input for one of the board's rotations. The board turns counter-clockwise (right-handed, seen from the axis's
     * positive end: Z from above), the dial clockwise like a compass, so a drag to the right turns the needle and the
     * object the same way. The input shows the negated value and sends the negation of what it shows.
     */
    private UiNumber angle(String label, double degrees, boolean compact, Action action, String target) {
        long editGeneration = generation;
        UiNumber number = new UiNumber(ui, stage, label, -degrees, -180, 180, 1, (next, finished) -> source.editorValue(
              new Command(action, target, UiNumber.format(-Double.parseDouble(next))), finished, editGeneration)).angle();
        if (compact) { number.setName("editor-brush-" + target); brushNumbers.add(number); } else { numbers.add(number); }
        return number;
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
        awaitingTheme = Long.MAX_VALUE;
        settingsBody.add(themeChoice("Global theme", theme[0], value -> theme[0] = value)).row();
        settingsBody.add(button("Apply theme to all hexes", () -> { awaitingTheme = snapshot.revision(); send(Action.MAP_THEME, theme[0]); })).row();
        themeResult = ui.label("", "hud-small", 11.5f, UiTheme.MUTED); themeResult.setWrap(true);
        settingsBody.add(themeResult).row();
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
    /** Lists the issues under the triangle; choosing one frames its hex and selects it, and its object if it has one. */
    private void showIssues() {
        if (shownIssues == null || shownIssues.isEmpty()) { return; }
        stage.setKeyboardFocus(null); numbers.forEach(UiNumber::dismiss);
        issueMenu = new UiMenuList(ui); issueMenu.setName("editor-issue-list");
        for (var issue : shownIssues.subList(0, Math.min(ISSUE_ROWS, shownIssues.size()))) {
            var at = issue.coords();
            issueMenu.item(at.getBoardNum() + " · " + issue.text(), null, null, false, true, () -> {
                choices.cancel();
                // Framed once the switch to Select has laid out its corner panels; see update.
                pendingFrame = at; pendingFrameUntil = System.nanoTime() + 3_000_000_000L;
                send(Action.SELECT_AT, at.getX() + "," + at.getY(), issue.object());
            }).setName("editor-issue-" + at.getBoardNum());
        }
        if (shownIssues.size() > ISSUE_ROWS) {
            issueMenu.item("… " + (shownIssues.size() - ISSUE_ROWS) + " more; fix these to see them", null, null, false, false, () -> { });
        }
        choices.header(shownIssues.size() + (shownIssues.size() == 1 ? " issue" : " issues"), "Saving is not blocked").content(issueMenu);
        var corner = issues.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
        choices.showAt(corner.x + issues.getWidth(), corner.y - 4);
    }

    /** A bare tool key switches tools; it is consumed, but does nothing, while a gallery, dialog or choice list is open. */
    boolean toolKey(int key) {
        var tool = TOOLS.entrySet().stream().filter(entry -> entry.getValue().key() == key).map(Map.Entry::getKey).findFirst();
        if (tool.isEmpty()) { return false; }
        if (!themeLayer.isVisible() && !designLayer.isVisible() && !settingsLayer.isVisible() && !choices.isVisible()) {
            send(Action.TOOL, tool.get().name());
        }
        return true;
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
