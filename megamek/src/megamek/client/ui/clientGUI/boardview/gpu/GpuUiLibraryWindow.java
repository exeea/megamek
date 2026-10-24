/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.ColorToken;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiAngleDial;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCardGrid;
import megamek.client.ui.gdx.UiChoiceGrid;
import megamek.client.ui.gdx.UiConfirmation;
import megamek.client.ui.gdx.UiFlow;
import megamek.client.ui.gdx.UiHexSides;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiList;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiNumber;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTree;
import megamek.common.Configuration;
import megamek.logging.MMLogger;

/**
 * The developer gallery of the native (libGDX) side of the design system, opened from Help in the lobby. It shows
 * every hud-v3 toolkit element as the game draws it today, one tab per group, each beside the column where its
 * migrated (Token v0) look will go, so the team can discuss the migration element by element. Only one native window
 * may run at a time (GLFW is process-wide), so it frees a map preview first, refuses beside a board or editor, and
 * closes for a board that opens.
 */
public final class GpuUiLibraryWindow extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuUiLibraryWindow.class);
    private static final String[] TABS = { "colors", "buttons", "selection", "inputs", "lists", "containers",
                                           "status", "hud" };
    /** Button styles whose own tab shows them in their control; the Buttons tab names them only. */
    private static final Set<String> SHOWN_ELSEWHERE = Set.of("hud-seg", "hud-pill", "hud-tab", "hud-tab-caps",
          "hud-select", "hud-select-inline", "hud-menu-row", "hud-row");
    private static final float NAME_WIDTH = 140;
    private static final float TODAY_WIDTH = 420;
    private static final float MIGRATED_WIDTH = 400;
    private static final float COLUMN_GAP = 14;
    private static final int TODAY_SWATCHES_PER_ROW = 3;
    private static final int MIGRATED_SWATCHES_PER_ROW = 2;
    private static final String SAMPLE_SPRITE = "defaults/default_heavy.png";
    /** The token button style each hud-v3 button style migrates to (tokens.md, "Migrating hud-v3 styles"). */
    private static final Map<String, String> MIGRATED_BUTTONS = Map.ofEntries(
          Map.entry("hud", "button-secondary"), Map.entry("hud-plain", "button-secondary"),
          Map.entry("hud-main", "button-primary"), Map.entry("hud-danger", "button-danger"),
          Map.entry("hud-icon", "button-icon"), Map.entry("hud-delete-icon", "button-icon"),
          Map.entry("hud-mini", "button-secondary" + UiTheme.COMPACT),
          Map.entry("hud-utility-small", "button-secondary" + UiTheme.COMPACT),
          Map.entry("hud-utility", "button-special"), Map.entry("hud-utility-narrow", "button-special"),
          Map.entry("hud-utility-row", "button-special"));

    /** The open gallery; read and written on the EDT. */
    private static GpuUiLibraryWindow open;

    private final float preference;
    private final DesignTokens tokens = DesignTokens.shipped();
    private final GpuDisplayScale displayScale = new GpuDisplayScale();
    private final Table[] pages = new Table[TABS.length];
    private volatile Lwjgl3Application application;
    private volatile boolean closeRequested;
    /** What runs on the EDT once the native window is gone: a board window waiting for it. */
    private Runnable afterClose;
    private GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    private UiKit kit;
    private Stage stage;
    private UiKit.Segmented tabs;
    private ScrollPane scroll;
    private UiModal modal;
    private UiConfirmation confirmation;
    private BoardScene.Pixels sprite;

    private GpuUiLibraryWindow(float preference) {
        this.preference = preference;
    }

    /** EDT: opens the gallery, or brings the open one to the front. */
    public static void showLibrary(Window owner) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showLibrary(owner));
            return;
        }
        if (open != null) {
            open.focus();
            return;
        }
        if (!GpuBoardWindow.yieldNativeWindow(owner, () -> showLibrary(owner))) {
            return;
        }
        GpuUiLibraryWindow window = new GpuUiLibraryWindow(GUIPreferences.getInstance().getGUIScale());
        open = window;
        Thread thread = new Thread(window::run, "MegaMek-UI-library");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * EDT: closes the open gallery and runs {@code then} on the EDT once its native window is gone, so a board window
     * can take GLFW. Returns false, and runs nothing, when no gallery is open.
     */
    static boolean closeThen(Runnable then) {
        if (open == null) {
            return false;
        }
        open.afterClose = then;
        open.requestClose();
        return true;
    }

    private void run() {
        try {
            Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(true);
            configuration.setTitle(Messages.getString("UiLibrary.gdx.title"));
            configuration.setWindowedMode(1660, 1000);
            new Lwjgl3Application(this, configuration);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error(failure, "The native UI library window failed");
        } finally {
            application = null;
            SwingUtilities.invokeLater(this::finished);
        }
    }

    private void finished() {
        if (open == this) {
            open = null;
        }
        Runnable then = afterClose;
        afterClose = null;
        if (then != null) {
            then.run();
        }
    }

    private void requestClose() {
        closeRequested = true;
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(app::exit);
        }
    }

    private void focus() {
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(() -> {
                var window = ((Lwjgl3Graphics) app.getGraphics()).getWindow();
                window.restoreWindow();
                window.focusWindow();
            });
        }
    }

    @Override
    public void create() {
        application = (Lwjgl3Application) Gdx.app;
        boardSkin = new GpuBoardSkin();
        hud = new GpuHudKit(boardSkin.skin);
        kit = hud.ui;
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);
        sprite = loadSprite();

        Table root = new Table();
        root.setFillParent(true);
        root.top().left();
        Table head = new Table();
        head.left().pad(20, 24, 10, 24);
        head.defaults().left();
        head.add(kit.label(text("UiLibrary.gdx.title"), "hud-heading", 20, UiTheme.TEXT)).row();
        Label note = kit.label(text("UiLibrary.gdx.note"), "hud-small", 11.5f, UiTheme.MUTED);
        note.setWrap(true);
        head.add(note).width(NAME_WIDTH + TODAY_WIDTH + MIGRATED_WIDTH + 2 * COLUMN_GAP).padTop(4);
        root.add(head).growX().row();
        String[] labels = new String[TABS.length];
        for (int index = 0; index < TABS.length; index++) {
            labels[index] = text("UiLibrary.gdx.tab." + TABS[index]);
        }
        tabs = kit.segmented("hud-tab", false, labels);
        tabs.left().pad(2, 24, 0, 24);
        for (int index = 0; index < TABS.length; index++) {
            int tab = index;
            UiKit.onChange(tabs.buttons.get(index), () -> selectTab(tab));
        }
        root.add(tabs).growX().row();
        scroll = kit.scrollList(null);
        root.add(scroll).grow();
        stage.addActor(root);

        modal = modal();
        stage.addActor(modal);
        confirmation = new UiConfirmation(kit, "library-confirm", () -> { });
        confirmation.text(text("UiLibrary.gdx.confirmHeading"), text("UiLibrary.gdx.confirmDetail"),
              text("UiLibrary.gdx.confirmYes"), text("UiLibrary.gdx.confirmNo"));
        stage.addActor(confirmation.actor());
        selectTab(0);
        if (closeRequested) {
            Gdx.app.exit();
        }
    }

    @Override
    public void resize(int width, int height) {
        float scale = DisplayScale.read(preference, displayScale.contentScale());
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
        confirmation.resize(stage.getWidth(), stage.getHeight());
    }

    @Override
    public void render() {
        ScreenUtils.clear(color(tokens.color("surface.base", Mode.DARK)));
        stage.act(Gdx.graphics.getDeltaTime());
        hud.update(sprite == null ? Set.of() : Set.of(sprite));
        stage.draw();
    }

    @Override
    public void dispose() {
        if (stage != null) {
            stage.dispose();
        }
        if (hud != null) {
            hud.dispose();
        }
        if (boardSkin != null) {
            boardSkin.dispose();
        }
    }

    /** Shows tab {@code index}; each page is built when first shown and keeps its state afterwards. */
    private void selectTab(int index) {
        tabs.select(index);
        if (pages[index] == null) {
            pages[index] = page(index);
        }
        scroll.setActor(pages[index]);
        scroll.setScrollY(0);
        stage.setScrollFocus(scroll);
    }

    /** A unit sprite for the unit rows and toasts; without mm-data's image they show a sensor contact's "?". */
    private static BoardScene.Pixels loadSprite() {
        try {
            var image = ImageIO.read(new File(Configuration.unitImagesDir(), SAMPLE_SPRITE));
            return image == null ? null : new BoardScene.Pixels(image);
        } catch (IOException failure) {
            LOGGER.warn(failure, "The UI library's sample sprite failed to load");
            return null;
        }
    }

    // ---- Pages

    private Table page(int index) {
        Table page = new Table();
        page.top().left().pad(16, 24, 40, 24);
        page.defaults().left().top();
        page.add(columnTitle("UiLibrary.gdx.element")).width(NAME_WIDTH).padRight(COLUMN_GAP);
        page.add(columnTitle("UiLibrary.gdx.today")).width(TODAY_WIDTH).padRight(COLUMN_GAP);
        page.add(columnTitle("UiLibrary.gdx.migrated")).width(MIGRATED_WIDTH).row();
        switch (TABS[index]) {
            case "colors" -> colors(page);
            case "buttons" -> buttons(page);
            case "selection" -> selection(page);
            case "inputs" -> inputs(page);
            case "lists" -> lists(page);
            case "containers" -> containers(page);
            case "status" -> status(page);
            default -> hudParts(page);
        }
        return page;
    }

    private void colors(Table page) {
        heading(page, "UiLibrary.gdx.colors");
        compare(page, "UiTheme", currentSwatches(), tokenSwatches());
        heading(page, "UiLibrary.gdx.fonts");
        compare(page, "UiTheme.HUD_FONTS", fonts(), null);
    }

    private void buttons(Table page) {
        heading(page, "UiLibrary.gdx.buttonStyles");
        page.add();
        String[] states = {text("UiLibrary.gdx.rest"), text("UiLibrary.gdx.hover"), text("UiLibrary.gdx.held"),
              text("UiLibrary.gdx.pressed"), text("UiLibrary.gdx.disabled")};
        page.add(stateRow(76, 6, states)).left().padRight(COLUMN_GAP);
        page.add(stateRow(70, 6, states)).left().row();
        List<String> styles = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        kit.skin.getAll(TextButton.TextButtonStyle.class).keys().forEach(style -> {
            if (style.startsWith("hud")) {
                (SHOWN_ELSEWHERE.contains(style) ? elsewhere : styles).add(style);
            }
        });
        styles.sort(String::compareTo);
        elsewhere.sort(String::compareTo);
        for (String style : styles) {
            compare(page, style, buttonStates(() -> sampleButton(style), 76, 6), migratedButton(style));
        }
        Label note = kit.label(text("UiLibrary.gdx.shownElsewhere", String.join(", ", elsewhere)), "hud-small",
              11.5f, UiTheme.MUTED);
        note.setWrap(true);
        page.add(note).colspan(3).width(NAME_WIDTH + TODAY_WIDTH + MIGRATED_WIDTH).padTop(4).row();

        heading(page, "UiLibrary.gdx.buttonParts");
        Table badges = new Table();
        badges.defaults().padRight(12);
        badges.add(kit.button("hud-utility", "report", text("UiLibrary.gdx.sampleLog"), null).badge("3"));
        badges.add(kit.button("hud-icon", "chat", null, null).badge("12"));
        compare(page, "UiButton.badge", badges, null);
        Table dots = new Table();
        dots.defaults().padRight(12);
        dots.add(kit.button("hud-utility", "settings", text("UiLibrary.gdx.sampleSettings"), null).dot(true));
        dots.add(kit.button("hud-icon", "chat", null, null).dot(true));
        compare(page, "UiButton.dot", dots, null);
        compare(page, "UiKit.closeButton", kit.closeButton(() -> { }), null);
    }

    private void selection(Table page) {
        heading(page, "UiLibrary.gdx.segmented");
        compare(page, "hud-seg", kit.segmented("hud-seg", false, text("UiLibrary.gdx.segAll"),
              text("UiLibrary.gdx.segMine"), text("UiLibrary.gdx.segCritical")).select(0), null);
        compare(page, "hud-pill", kit.segmented("hud-pill", false, text("UiLibrary.gdx.segAll"),
              text("UiLibrary.gdx.segMine"), text("UiLibrary.gdx.segCritical")).select(1), null);
        compare(page, "hud-tab", kit.segmented("hud-tab", false, text("UiLibrary.gdx.tabFriendly"),
              text("UiLibrary.gdx.tabContacts")).select(0), null);
        compare(page, "hud-tab-caps", kit.segmented("hud-tab-caps", false, text("UiLibrary.gdx.segAll"),
              text("UiLibrary.gdx.tabCombat"), text("UiLibrary.gdx.tabChat")).select(0), null);
        heading(page, "UiLibrary.gdx.selects");
        compare(page, "hud-select", new Container<>(kit.select(text("UiLibrary.gdx.selectValue"), false)).width(220),
              null);
        compare(page, "hud-select-inline", kit.select(text("UiLibrary.gdx.selectValue"), true), null);
        Table checks = new Table();
        checks.defaults().padRight(20);
        checks.add(kit.checkbox(text("UiLibrary.gdx.ticked"), true));
        checks.add(kit.checkbox(text("UiLibrary.gdx.clear"), false));
        compare(page, "UiKit.checkbox", checks, null);
        heading(page, "UiLibrary.gdx.menus");
        Table rows = new Table();
        rows.defaults().growX().width(280);
        rows.add(kit.menuRow(text("UiLibrary.gdx.menuShortcut"), "Ctrl+K", null, false)).row();
        rows.add(kit.menuRow(text("UiLibrary.gdx.menuChecked"), null, true, false)).row();
        rows.add(kit.menuRow(text("UiLibrary.gdx.menuGroup"), null, null, true)).row();
        rows.add(kit.menuRow(text("UiLibrary.gdx.menuHighlighted"), null, false, false).pressed(true));
        compare(page, "hud-menu-row", rows, null);
        compare(page, "UiMenuList", new Container<>(menuPanel()).width(280), null);
    }

    private void inputs(Table page) {
        heading(page, "UiLibrary.gdx.fields");
        compare(page, "hud-search", new Container<>(kit.search(text("UiLibrary.gdx.search"))).width(320), null);
        compare(page, "hud (TextField)",
              new Container<>(new TextField(text("UiLibrary.gdx.fieldValue"), kit.skin, "hud")).width(220).height(29),
              null);
        compare(page, "UiNumber", new Container<>(new UiNumber(kit, stage, text("UiLibrary.gdx.elevation"), 2, 0,
              10, 1, (value, done) -> { })).width(340), null);
        Slider slider = new Slider(0, 100, 1, false, kit.sliderStyle());
        slider.setValue(40);
        slider.addListener(new UiSliderInput(slider));
        compare(page, "UiKit.sliderStyle", new Container<>(slider).width(300), null);
        heading(page, "UiLibrary.gdx.pickers");
        UiAngleDial[] dial = new UiAngleDial[1];
        dial[0] = new UiAngleDial(kit, value -> dial[0].value(value), () -> { });
        dial[0].value(60);
        compare(page, "UiAngleDial", new Container<>(dial[0]).size(132), null);
        UiHexSides[] sides = new UiHexSides[1];
        sides[0] = new UiHexSides(kit, 0b000011, side -> sides[0].selected(sides[0].selected() ^ (1 << side)));
        compare(page, "UiHexSides", new Container<>(sides[0]).size(150), null);
    }

    private void lists(Table page) {
        heading(page, "UiLibrary.gdx.lists");
        UiList list = new UiList(kit);
        for (int index = 0; index < 3; index++) {
            UiButton row = kit.button("hud-row", null,
                  text(index == 1 ? "UiLibrary.sample.selectedRow" : "UiLibrary.sample.row"), null);
            row.getLabel().setAlignment(Align.left);
            row.getCell(row.getLabel()).growX();
            row.pressed(index == 1);
            list.add(row, row);
        }
        list.reorderable((from, to) -> { });
        compare(page, "UiList + hud-row", new Container<>(list).width(320), null);
        compare(page, "UiTree", new Container<>(tree()).width(320), null);
        Table items = new Table();
        items.top().left();
        for (int index = 1; index <= 12; index++) {
            items.add(kit.label(text("UiLibrary.gdx.listItem", index), "hud-body", 12.5f, UiTheme.TEXT)).left()
                  .pad(3, 10, 3, 10).row();
        }
        compare(page, "hud-list (ScrollPane)", new Container<>(kit.scrollList(items)).size(300, 110), null);
        Table empty = kit.panel();
        kit.empty(empty, text("UiLibrary.gdx.empty"));
        compare(page, "UiKit.empty", new Container<>(empty).width(320), null);
        heading(page, "UiLibrary.gdx.grids");
        compare(page, "UiCardGrid", new Container<>(cardGrid()).size(420, 330), null);
        UiChoiceGrid choices = kit.choiceGrid(120, 8);
        for (int index = 1; index <= 5; index++) {
            choices.addActor(kit.button("hud", null, text("UiLibrary.gdx.choice", index), null));
        }
        choices.fit(420, 200);
        compare(page, "UiChoiceGrid", new Container<>(choices).width(420), null);
        Table top = new Table();
        top.add(kit.label(text("UiLibrary.gdx.splitTop"), "hud-body", 12.5f, UiTheme.TEXT));
        Table bottom = new Table();
        bottom.add(kit.label(text("UiLibrary.gdx.splitBottom"), "hud-body", 12.5f, UiTheme.TEXT));
        compare(page, "hud-split", new Container<>(new SplitPane(top, bottom, true, kit.skin, "hud-split"))
              .size(320, 120), null);
    }

    private void containers(Table page) {
        heading(page, "UiLibrary.gdx.panels");
        compare(page, "panel + header + footer", new Container<>(listPanel()).width(320), null);
        Table foe = kit.panel();
        foe.setBackground(kit.skin.getDrawable("panel-foe"));
        foe.add(kit.header(text("UiLibrary.gdx.foePanel"), null)).growX().row();
        foe.add(kit.label(text("UiLibrary.sample.row"), "hud-body", 12.5f, UiTheme.TEXT)).left().pad(0, 14, 12, 14);
        compare(page, "panel-foe", new Container<>(foe).width(320), null);
        Table rails = new Table();
        rails.setBackground(kit.skin.getDrawable("panel-rails"));
        rails.add(kit.label(text("UiLibrary.gdx.tacticalView"), "hud-medium", 12, UiTheme.TEXT));
        compare(page, "panel-rails", rails, null);
        compare(page, "rule", new Container<>(new Image(kit.skin.getDrawable("rule"))).size(320, 1), null);
        heading(page, "UiLibrary.gdx.overlays");
        compare(page, "UiPopover (panel-pop)", popover(), null);
        Table dialog = kit.dialog(text("UiLibrary.gdx.dialogTitle"), () -> { });
        dialog.add(dialogBody()).growX();
        compare(page, "UiKit.dialog", new Container<>(dialog).width(400), null);
        UiButton openModal = kit.button("hud", null, text("UiLibrary.gdx.openModal"), null);
        UiKit.onChange(openModal, () -> {
            modal.open(true);
            modal.focus();
        });
        compare(page, "UiModal", openModal, null);
        UiButton ask = kit.button("hud", null, text("UiLibrary.gdx.askConfirm"), null);
        UiKit.onChange(ask, () -> confirmation.toggle(ask, () -> { }));
        compare(page, "UiConfirmation", ask, null);
        UiButton tipped = kit.button("hud", "info", text("UiLibrary.gdx.hoverTip"), null);
        kit.tip(tipped).getActor().setText(text("UiLibrary.gdx.tipText"));
        compare(page, "UiKit.tip", tipped, null);
        heading(page, "UiLibrary.gdx.text");
        UiFlow flow = new UiFlow(kit, 12.5f, 17);
        flow.run(text("UiLibrary.gdx.flowLead"), "hud-body", UiTheme.MUTED)
              .run(text("UiLibrary.gdx.flowValue"), "hud-button", UiTheme.TEXT)
              .run(text("UiLibrary.gdx.flowRest"), "hud-body", UiTheme.MUTED);
        compare(page, "UiFlow", new Container<>(flow).width(360), null);
    }

    private void status(Table page) {
        heading(page, "UiLibrary.gdx.chipsBadges");
        Table chips = new Table();
        chips.defaults().padRight(8);
        chips.add(kit.chip(text("UiLibrary.status.info"), Tone.NORMAL));
        chips.add(kit.chip(text("UiLibrary.status.success"), Tone.OK));
        chips.add(kit.chip(text("UiLibrary.status.warning"), Tone.WARN));
        chips.add(kit.chip(text("UiLibrary.status.danger"), Tone.BAD));
        compare(page, "UiKit.chip", chips, null);
        Table marks = new Table();
        marks.defaults().padRight(12);
        marks.add(new Container<>(kit.label("3", "hud-caption", 10, UiTheme.FILL_INK))
              .background(kit.skin.getDrawable("badge")));
        marks.add(new Image(kit.skin.getDrawable("dot")));
        compare(page, "badge, dot", marks, null);
        heading(page, "UiLibrary.gdx.meters");
        Table meters = kit.panel();
        meters.pad(14, 16, 14, 16);
        meters.defaults().growX().uniformX().padRight(12);
        meters.add(kit.meter(text("UiLibrary.gdx.armor"), false).set("91%", .91f, UiTheme.MINT));
        meters.add(kit.meter(text("UiLibrary.gdx.structure"), false).set("40%", .4f, UiTheme.AMBER));
        meters.add(kit.meter(text("UiLibrary.gdx.heat"), false).set("12", .4f, UiTheme.CORAL));
        compare(page, "UiKit.meter", new Container<>(meters).width(420), null);
        Table compact = kit.panel();
        compact.pad(10, 14, 10, 14);
        compact.defaults().growX().uniformX().padRight(12);
        compact.add(kit.meter(text("UiLibrary.gdx.armor"), true).set("91%", .91f, UiTheme.MINT));
        compact.add(kit.meter(text("UiLibrary.gdx.structure"), true).set("40%", .4f, UiTheme.AMBER));
        compare(page, "UiKit.meter (compact)", new Container<>(compact).width(320), null);
        Table heat = new Table();
        heat.defaults().left().width(300).height(8).padBottom(12);
        heat.add(hud.heatBar(false).set(12, 18, 30, List.of(8, 13, 17, 24))).row();
        heat.add(hud.heatBar(true).set(12, 18, 30, List.of(8, 13, 17, 24)));
        compare(page, "GpuHudKit.heatBar", heat, null);
        heading(page, "UiLibrary.gdx.toasts");
        GpuToastStack stack = new GpuToastStack(hud, null, null);
        Table toasts = new Table();
        toasts.defaults().left().padBottom(6);
        long id = 0;
        for (ToastLevel level : ToastLevel.values()) {
            String key = "UiLibrary.gdx.toast." + level.name().toLowerCase(Locale.ROOT);
            Actor toast = stack.toast(new GpuToasts.Toast(id++, level, text(key),
                  level == ToastLevel.SUCCESS ? sprite : null, 1000));
            toast.clearActions();
            toast.getColor().a = 1;
            toasts.add(toast).row();
        }
        compare(page, "GpuToastStack", toasts, null);
    }

    private void hudParts(Table page) {
        Label note = kit.label(text("UiLibrary.gdx.hudNote"), "hud-small", 11.5f, UiTheme.MUTED);
        note.setWrap(true);
        page.add(note).colspan(3).width(NAME_WIDTH + TODAY_WIDTH + MIGRATED_WIDTH).padTop(10).row();
        heading(page, "UiLibrary.gdx.targets");
        Table letters = new Table();
        letters.defaults().padRight(8);
        for (char letter = 'A'; letter <= 'F'; letter++) {
            letters.add(hud.letter(letter, false));
        }
        for (char letter = 'A'; letter <= 'C'; letter++) {
            letters.add(hud.letter(letter, true));
        }
        compare(page, "GpuHudKit.letter", letters, null);
        Table pills = new Table();
        pills.defaults().padRight(8);
        pills.add(pill('A', "pill", UiTheme.TEXT));
        pills.add(pill('B', "pill-on", UiTheme.FILL_INK));
        compare(page, "pill, pill-on", pills, null);
        heading(page, "UiLibrary.gdx.units");
        Table rows = new Table();
        rows.defaults().width(340).padBottom(6);
        rows.add(hud.unitRow(40, 34).set(sprite, GpuHudKit.FRIEND_SPRITE, text("UiLibrary.gdx.unitModel"),
              text("UiLibrary.gdx.unitName"), text("UiLibrary.gdx.unitStatus"), Tone.NORMAL)).row();
        rows.add(hud.unitRow(40, 34).set(sprite, UiTheme.CORAL, text("UiLibrary.gdx.foeModel"),
              text("UiLibrary.gdx.foeName"), text("UiLibrary.gdx.foeStatus"), Tone.BAD).inspected(true)).row();
        rows.add(hud.unitRow(40, 34).set(null, UiTheme.BLIP, text("GpuBoard.hud.common.unidentified"),
              text("GpuBoard.hud.common.sensorContact"), text("UiLibrary.gdx.contactStatus"), Tone.WARN));
        compare(page, "GpuHudKit.unitRow", rows, null);
        Table damage = new Table();
        damage.defaults().left().padBottom(4);
        damage.add(damageLabel(UiTheme.MINT, "UiLibrary.gdx.damageLight")).row();
        damage.add(damageLabel(UiTheme.AMBER, "UiLibrary.gdx.damageHeavy")).row();
        damage.add(damageLabel(Color.BLACK, "UiLibrary.gdx.damageCrippled"));
        compare(page, "GpuHudKit.damageTile", damage, null);
        heading(page, "UiLibrary.gdx.hudBoxes");
        Table discs = new Table();
        discs.defaults().padRight(8);
        discs.add(disc("disc", "3", UiTheme.MINT));
        discs.add(disc("disc-foe", "4", UiTheme.CORAL));
        compare(page, "disc, disc-foe", discs, null);
        Container<Label> waiting = new Container<>(kit.label(text("UiLibrary.gdx.waiting"), "hud-small", 11.5f,
              UiTheme.MUTED));
        waiting.setBackground(kit.skin.getDrawable("waiting"));
        waiting.pad(0, 14, 0, 14);
        compare(page, "waiting", waiting, null);
    }

    // ---- Samples

    private UiButton sampleButton(String style) {
        return switch (style) {
            case "hud-icon" -> kit.button(style, "search", null, null);
            case "hud-delete-icon" -> kit.button(style, "close", null, null);
            case "hud-utility", "hud-utility-row" -> kit.button(style, "map", text("UiLibrary.gdx.sampleMap"), null);
            default -> kit.button(style, null, text("UiLibrary.gdx.sampleAction"), null);
        };
    }

    /** A button's five states side by side: at rest, hovered, held, pressed and disabled. */
    private static Table buttonStates(Supplier<UiButton> sample, float width, float gap) {
        return buttonStates(sample, sample, width, gap);
    }

    /** As {@link #buttonStates(Supplier, float, float)}, with the pressed state drawn by its own sample. */
    private static Table buttonStates(Supplier<UiButton> sample, Supplier<UiButton> pressedSample, float width,
          float gap) {
        Table states = new Table();
        states.defaults().left().minWidth(width).padRight(gap);
        for (int state = 0; state < 5; state++) {
            UiButton button = state == 3 ? pressedSample.get() : sample.get();
            switch (state) {
                case 1 -> button.hovered(true);
                case 2 -> button.held(true);
                case 3 -> button.pressed(true);
                case 4 -> button.setDisabled(true);
                default -> { }
            }
            states.add(new Container<>(button).left());
        }
        return states;
    }

    /** The token button a hud-v3 style migrates to, in its five states, over the token style's name. */
    private Table migratedButton(String style) {
        String token = MIGRATED_BUTTONS.get(style);
        if (token == null) {
            return null;
        }
        Table migrated = new Table();
        migrated.left().top();
        // Secondary has no selected look: a button the game holds pressed becomes a toggle.
        String pressed = token.replace("button-secondary", "button-toggle");
        migrated.add(buttonStates(() -> tokenButton(style, token), () -> tokenButton(style, pressed), 70, 6))
              .left().row();
        String caption = pressed.equals(token) ? token : text("UiLibrary.gdx.heldPressed", token, pressed);
        migrated.add(kit.label(caption, "hud-small", 11, UiTheme.MUTED)).left().padTop(6);
        return migrated;
    }

    private UiButton tokenButton(String style, String token) {
        return switch (style) {
            case "hud-icon" -> kit.button(token, "search", null, null);
            case "hud-delete-icon" -> kit.button(token, "close", null, null);
            case "hud-utility-row" -> kit.button(token, "map", text("UiLibrary.gdx.sampleMap"), null);
            case "hud-utility" -> {
                // The icon-above-label layout stays a HUD composition on top of the special kind.
                UiButton button = kit.button(token, "map", null, null);
                button.row();
                button.setText(text("UiLibrary.gdx.sampleMap"));
                button.add(button.getLabel()).padTop(4);
                yield button;
            }
            default -> kit.button(token, null, text("UiLibrary.gdx.sampleAction"), null);
        };
    }

    private Table menuPanel() {
        Table popover = new Table();
        popover.setBackground(kit.skin.getDrawable("panel-pop"));
        popover.pad(8, 2, 8, 2);
        popover.add(menuList()).growX();
        return popover;
    }

    private UiMenuList menuList() {
        UiMenuList list = new UiMenuList(kit);
        list.item(text("UiLibrary.gdx.menuShortcut"), "Ctrl+K", false, false, true, () -> { });
        list.item(text("UiLibrary.gdx.menuChecked"), null, true, false, true, () -> { });
        list.separator();
        list.item(text("UiLibrary.gdx.menuGroup"), null, false, true, true, () -> { });
        list.item(text("UiLibrary.gdx.menuDisabled"), null, false, false, false, () -> { });
        return list;
    }

    private UiPopover popover() {
        UiPopover popover = new UiPopover(kit);
        popover.header(text("UiLibrary.gdx.popoverTitle"), text("UiLibrary.gdx.popoverDetail"));
        popover.content(menuList());
        popover.setVisible(true);
        return popover;
    }

    private UiTree tree() {
        UiTree tree = new UiTree(kit);
        tree.nodes(List.of(
              new UiTree.Node("display", text("UiLibrary.gdx.treeDisplay")).icon("tune").children(List.of(
                    new UiTree.Node("display.labels", text("UiLibrary.gdx.treeLabels")),
                    new UiTree.Node("display.shadows", text("UiLibrary.gdx.treeShadows"))
                          .detail(text("UiLibrary.gdx.treeShadowsDetail")))),
              new UiTree.Node("board", text("UiLibrary.gdx.treeBoard")).icon("map").children(List.of(
                    new UiTree.Node("board.terrain", text("UiLibrary.gdx.treeTerrain")))),
              new UiTree.Node("units", text("UiLibrary.gdx.treeUnits")).icon("unit")));
        tree.expand("display", true);
        tree.select("display.labels");
        return tree;
    }

    private ScrollPane cardGrid() {
        UiCardGrid grid = new UiCardGrid(3, 56, 24, 8);
        grid.items(List.of(new UiCardGrid.Section(kit.caption(text("UiLibrary.gdx.cardsLight")), 4),
              new UiCardGrid.Section(kit.caption(text("UiLibrary.gdx.cardsHeavy")), 5)), index -> {
                  Table card = kit.panel();
                  card.add(kit.label(text("UiLibrary.gdx.card", index + 1), "hud-medium", 12, UiTheme.TEXT))
                        .expand();
                  return card;
              });
        return kit.scrollList(grid);
    }

    private Table listPanel() {
        Table panel = kit.panel();
        panel.add(kit.header(text("UiLibrary.gdx.panel"), "3", kit.closeButton(() -> { }))).growX().row();
        Table list = new Table();
        list.top();
        for (int index = 0; index < 3; index++) {
            UiButton row = kit.button("hud-row", null,
                  text(index == 1 ? "UiLibrary.sample.selectedRow" : "UiLibrary.sample.row"), null);
            row.getLabel().setAlignment(Align.left);
            row.getCell(row.getLabel()).growX();
            row.pressed(index == 1);
            list.add(row).growX().pad(0, 10, 6, 10).row();
        }
        panel.add(list).growX().row();
        Table foot = kit.footer(panel);
        foot.add(kit.label(text("UiLibrary.sample.detail"), "hud-small", 11.5f, UiTheme.MUTED)).growX().left();
        foot.add(kit.button("hud-mini", null, text("UiLibrary.gdx.sampleAction"), null));
        return panel;
    }

    private Table dialogBody() {
        Table body = new Table();
        Label message = kit.label(text("UiLibrary.gdx.dialogBody"), "hud-body", 12.5f, UiTheme.TEXT);
        message.setWrap(true);
        body.add(message).growX().colspan(3).padBottom(14).row();
        body.add().expandX();
        body.add(kit.button("hud-mini", null, text("UiLibrary.gdx.confirmNo"), null)).padRight(8);
        body.add(kit.button("hud-main", null, text("UiLibrary.gdx.confirmYes"), null));
        return body;
    }

    private UiModal modal() {
        UiModal layer = new UiModal(kit, () -> modal.open(false));
        layer.setFillParent(true);
        Table dialog = kit.dialog(text("UiLibrary.gdx.dialogTitle"), () -> modal.open(false));
        dialog.add(dialogBody()).growX();
        layer.add(dialog).width(400);
        return layer;
    }

    private Table pill(char letter, String background, Color ink) {
        Table pill = new Table();
        pill.setBackground(kit.skin.getDrawable(background));
        pill.add(hud.letter(letter, false)).padRight(6);
        pill.add(kit.label(text("UiLibrary.gdx.foeName"), "hud-medium", 12, ink));
        return pill;
    }

    private Label damageLabel(Color tile, String key) {
        Label label = kit.label(text(key), "hud-small", 11.5f, UiTheme.TEXT);
        Label.LabelStyle style = new Label.LabelStyle(label.getStyle());
        style.background = hud.damageTile(tile);
        label.setStyle(style);
        return label;
    }

    private Container<Label> disc(String background, String number, Color ink) {
        Label label = kit.label(number, "hud-medium", 12, ink);
        label.setAlignment(Align.center);
        Container<Label> disc = new Container<>(label).size(28);
        disc.setBackground(kit.skin.getDrawable(background));
        return disc;
    }

    // ---- Layout

    private Label columnTitle(String key) {
        return kit.label(UiTheme.upper(text(key)), "hud-caption", 10.5f, UiTheme.MUTED);
    }

    private void heading(Table page, String key) {
        page.add(kit.label(text(key), "hud-title", 15, UiTheme.TEXT)).colspan(3).padTop(22).padBottom(8).row();
    }

    /** One element: its code name, today's look and its migrated look, or a placeholder while it has none. */
    private void compare(Table page, String name, Actor today, Actor migrated) {
        page.add(new Image(kit.skin.getDrawable("rule"))).colspan(3).growX().height(1).padBottom(10).row();
        Label label = kit.label(name, "hud-medium", 12, UiTheme.ACCENT);
        label.setWrap(true);
        page.add(label).width(NAME_WIDTH).padRight(COLUMN_GAP).padBottom(12);
        page.add(topLeft(today)).width(TODAY_WIDTH).padRight(COLUMN_GAP).padBottom(12);
        page.add(topLeft(migrated != null ? migrated : kit.label(text("UiLibrary.gdx.notMigrated"), "hud-small", 11.5f,
              UiTheme.DISABLED))).width(MIGRATED_WIDTH).padBottom(12).row();
    }

    /** Holds a sample at the top left of its column; a filled cell would center the sample's own content. */
    private static Table topLeft(Actor sample) {
        Table holder = new Table();
        holder.left().top();
        holder.add(sample);
        return holder;
    }

    private Table stateRow(float width, float gap, String... states) {
        Table row = new Table();
        row.defaults().left().width(width).padRight(gap);
        for (String state : states) {
            row.add(kit.label(state, "hud-small", 11, UiTheme.MUTED));
        }
        return row;
    }

    private Table tokenSwatches() {
        List<Table> cells = new ArrayList<>();
        int panel = tokens.color("surface.panel", Mode.DARK);
        for (ColorToken token : tokens.colors().values()) {
            int value = token.overlay() != null ? token.overlay() : token.dark();
            // Translucent roles show as they sit on a panel.
            int shown = ColorMath.alpha(value) == 0xFF ? value : ColorMath.over(value, panel);
            String hex = ColorMath.hex(value) + (token.overlay() != null ? "  " + text("UiLibrary.gdx.overlay") : "");
            cells.add(swatch(token.name(), hex, color(shown), 160));
        }
        return grid(cells, MIGRATED_SWATCHES_PER_ROW);
    }

    private Table currentSwatches() {
        Map<String, Color> current = new LinkedHashMap<>();
        current.put("TEXT", UiTheme.TEXT);
        current.put("ACCENT", UiTheme.ACCENT);
        current.put("MUTED", UiTheme.MUTED);
        current.put("DISABLED", UiTheme.DISABLED);
        current.put("PANEL", UiTheme.PANEL);
        current.put("PANEL2", UiTheme.PANEL2);
        current.put("POP", UiTheme.POP);
        current.put("LINE", UiTheme.LINE);
        current.put("RAIL", UiTheme.RAIL);
        current.put("QUIET", UiTheme.QUIET);
        current.put("TICK", UiTheme.TICK);
        current.put("CORNER", UiTheme.CORNER);
        current.put("MINT", UiTheme.MINT);
        current.put("CORAL", UiTheme.CORAL);
        current.put("AMBER", UiTheme.AMBER);
        current.put("VIOLET", UiTheme.VIOLET);
        current.put("BLIP", UiTheme.BLIP);
        current.put("FILL", UiTheme.FILL);
        current.put("FILL_INK", UiTheme.FILL_INK);
        current.put("FILL_MUTED", UiTheme.FILL_MUTED);
        current.put("MAIN", UiTheme.MAIN);
        current.put("HOVER", UiTheme.HOVER);
        current.put("TRACK", UiTheme.TRACK);
        List<Table> cells = new ArrayList<>();
        current.forEach((name, value) -> cells.add(swatch(name, "#" + value.toString().toUpperCase(Locale.ROOT),
              value, 124)));
        return grid(cells, TODAY_SWATCHES_PER_ROW);
    }

    private Table swatch(String name, String detail, Color fill, float width) {
        Table cell = new Table();
        cell.defaults().left();
        Image chip = new Image(kit.skin.newDrawable("white", fill));
        // A subtle outline keeps colors that sit close to the background visible.
        Table outline = new Table();
        outline.setBackground(kit.skin.newDrawable("white", color(tokens.color("border.subtle", Mode.DARK))));
        outline.add(chip).size(width, 28);
        outline.pad(1);
        cell.add(outline).row();
        cell.add(kit.label(name, "hud-medium", 12, UiTheme.TEXT)).padTop(4).row();
        cell.add(kit.label(detail, "hud-small", 10.5f, UiTheme.MUTED)).row();
        return cell;
    }

    private static Table grid(List<Table> cells, int perRow) {
        Table grid = new Table();
        grid.top().left();
        grid.defaults().left().top().padRight(12).padBottom(10);
        for (int index = 0; index < cells.size(); index++) {
            grid.add(cells.get(index));
            if ((index + 1) % perRow == 0) {
                grid.row();
            }
        }
        return grid;
    }

    private Table fonts() {
        Table fonts = new Table();
        fonts.defaults().left().padBottom(6);
        for (UiTheme.HudFont font : UiTheme.HUD_FONTS) {
            fonts.add(kit.label(String.format(Locale.ROOT, "%s  %s / %s", font.name(), trim(font.size()),
                  weight(font.instance())), "hud-small", 11, UiTheme.MUTED)).width(200);
            Label sample = new Label(text("UiLibrary.type.sample"), kit.skin, font.name());
            sample.setEllipsis(true);
            fonts.add(sample).width(TODAY_WIDTH - 200).row();
        }
        return fonts;
    }

    // ---- Helpers

    private static String text(String key, Object... arguments) {
        return arguments.length == 0 ? Messages.getString(key) : Messages.getString(key, arguments);
    }

    /** A libGDX color from a design token's ARGB value. */
    private static Color color(int argb) {
        return new Color((argb << 8) | (argb >>> 24));
    }

    private static String weight(int instance) {
        return switch (instance) {
            case UiTheme.REGULAR -> "400";
            case UiTheme.MEDIUM -> "500";
            case UiTheme.CONDENSED_MEDIUM -> "condensed 500";
            case UiTheme.CONDENSED_SEMIBOLD -> "condensed 600";
            case UiTheme.CONDENSED_BOLD -> "condensed 700";
            default -> "instance " + instance;
        };
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }
}
