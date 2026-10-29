/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyMenuEntry;
import megamek.client.lobby.LobbyReadiness;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiVirtualList;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.loaders.MekSummary;
import megamek.logging.MMLogger;

/**
 * The native lobby's screen ({@link GpuLobbyWindow}), the briefing room in the game presentation on the plain
 * surface.base: the top bar ({@link LobbyTopBar}) over three columns ({@link LobbyColumns}),
 * the players with the chat docked under them on the left, the forces in the centre and the battlefield on the right.
 * In a narrow window the battlefield folds into a rail that opens it in the centre, in the forces' place, as a view with
 * Back ({@link LobbyCentre}). It draws only the {@link LobbySnapshot}s its window sends and hands every action back to
 * the window; it never reads the game.
 */
final class GpuLobbyScreen extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyScreen.class);
    private static final int FRAMES_PER_SECOND = 30;
    /** The centre view that shows the folded battlefield. */
    static final String BATTLEFIELD = "battlefield";
    /** The centre view that shows the Requisition, the unit selector. */
    static final String REQUISITION = "requisition";
    /** How many catalogue sprites the Requisition keeps as textures; the least recently shown go first. */
    private static final int REQUISITION_SPRITES = 200;
    /** When the developer switch's clicks run, in seconds after the lobby shows. */
    static final double PROBE_CLICK_AT = 1.5;

    private final GpuLobbyWindow window;
    private final GUIPreferences preferences;
    private final DesignTokens tokens = DesignTokens.shipped();
    private final java.awt.Color base = awtColor("surface.base");

    private GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    private UiKit kit;
    private Stage stage;
    private float scale = 1;
    private int bw;
    private int bh;

    private LobbyTopBar topBar;
    private LobbyPlayersPanel players;
    private LobbyForcesPanel forces;
    private LobbyBattlefieldPanel battlefield;
    private LobbyCentre centre;
    private LobbyRequisitionPanel requisition;
    private ExecutorService searchThread;
    private RequisitionSearch search;
    /** The Requisition's sprites by catalogue unit, the least recently shown first. */
    private final LinkedHashMap<String, TextureRegionDrawable> requisitionSprites = new LinkedHashMap<>(64, .75f,
          true);
    private LobbyColumns columns;
    private Button rail;
    private boolean folded;
    private boolean railPending;
    private boolean menuPending;
    private boolean unitMenuPending;
    /** The developer switch's clicks and pointer, run through the stage once their time comes. */
    private boolean clickCompactPending;
    private int filterClicksPending;
    private String hoverPending;
    /** The units' sprites by their window's key, and each shown unit's. */
    private final Map<String, Texture> spriteTextures = new HashMap<>();
    private final Map<Integer, Drawable> spriteDrawables = new HashMap<>();
    private Texture mapTexture;
    private LobbySnapshot shown;
    private long start;
    private boolean shotSaved;

    GpuLobbyScreen(GpuLobbyWindow window, GUIPreferences preferences) {
        this.window = window;
        this.preferences = preferences;
    }

    private java.awt.Color awtColor(String name) {
        return new java.awt.Color(tokens.color(name, Mode.DARK), true);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** GPU thread: ignores the mouse and keyboard while a modal Swing dialog is open, as a disabled window would. */
    void setInputBlocked(boolean blocked) {
        if (stage == null) {
            return;
        }
        if (blocked) {
            stage.cancelTouchFocus();
        }
        Gdx.input.setInputProcessor(blocked ? null : stage);
    }

    @Override
    public void create() {
        boardSkin = new GpuBoardSkin();
        hud = new GpuHudKit(boardSkin.skin);
        kit = hud.ui;
        kit.presentation(Presentation.GAME);
        stage = new Stage(new ScreenViewport());
        build();
        map(window.lastMap(), true);
        setInputBlocked(window.modalOpen());
        Gdx.graphics.setForegroundFPS(FRAMES_PER_SECOND);
        show(window.snapshot());
        start = System.nanoTime();
        // Sizing the window draws a frame at once on some platforms
        sizeForProbe();
    }

    /** Gives the window the developer switch's size, keeping the saved size as it was. */
    private void sizeForProbe() {
        GpuLobbyProbe probe = window.probe();
        if (probe == null) {
            return;
        }
        railPending = probe.options().contains("rail");
        menuPending = probe.options().contains("menu");
        unitMenuPending = probe.options().contains("unitmenu");
        clickCompactPending = probe.options().contains("clickcompact");
        filterClicksPending = probe.options().contains("closefilters") ? 2 : probe.options().contains("clickfilters")
              ? 1 : 0;
        hoverPending = probe.options().contains("hoverbar") ? "bar" : probe.options().contains("hoverrow") ? "row"
              : null;
        if (probe.options().contains("compact")) {
            forces.compact(true);
        }
        if (probe.options().contains("formations")) {
            forces.showFormations();
        }
        if (probe.options().contains("autoresolve")) {
            battlefield.openAutoResolve();
        }
        if (probe.options().contains("requisition")) {
            openRequisition();
            if (probe.options().contains("atlas")) {
                requisition.probeSearch("Atlas");
            }
            if (probe.options().contains("filters")) {
                requisition.probeFilters();
            }
            if (probe.options().contains("pick")) {
                requisition.probePick();
            }
        }
        if (!probe.sized()) {
            return;
        }
        GpuHost.keepSavedBounds();
        if (Gdx.graphics instanceof Lwjgl3Graphics graphics && graphics.getWindow() != null) {
            graphics.getWindow().restoreWindow();
        }
        Gdx.graphics.setWindowedMode(probe.width(), probe.height());
    }

    // ---- Layout ----

    private void build() {
        boolean motion = !preferences.getReduceMotion();
        topBar = new LobbyTopBar(kit, window::leave, window::ready, this::goTo);
        topBar.shortcut(KeyCommandBind.getDesc(KeyCommandBind.DONE));
        players = new LobbyPlayersPanel(kit, playerActions());
        forces = new LobbyForcesPanel(kit, forceActions(), spriteDrawables::get);
        battlefield = new LobbyBattlefieldPanel(kit, battlefieldActions());
        battlefield.map(null, LobbyBattlefieldPanel.MAP_ASPECT, true);
        centre = new LobbyCentre(kit, forces.panel(), motion);
        searchThread = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "Requisition search");
            thread.setDaemon(true);
            return thread;
        });
        search = new RequisitionSearch(RequisitionSearch::fromCache, searchThread);
        requisition = new LobbyRequisitionPanel(kit, requisitionActions(), requisitionSprites::get, motion);
        rail = kit.rail(Messages.getString("Lobby.battlefield"), "map");
        rail.setProgrammaticChangeEvents(false);
        kit.tip(rail, true).getActor().setText(Messages.getString("Lobby.railTip"));
        UiKit.onChange(rail, () -> {
            if (rail.isChecked()) {
                centre.show(BATTLEFIELD, Messages.getString("Lobby.battlefield"), battlefield.body());
            } else {
                centre.back();
            }
        });
        centre.onChange(this::centreChanged);
        columns = new LobbyColumns(players.panel(), centre, battlefield.panel(), rail, space("lg"));

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        stage.addActor(root);
        root.add(topBar).growX().pad(space("md"), space("lg"), space("md"), space("lg")).row();
        root.add(columns).grow().pad(0, space("lg"), space("lg"), space("lg"));
        players.attach(stage.getRoot());
        forces.attach(stage.getRoot());
        battlefield.attach(stage.getRoot());
        topBar.attach(stage.getRoot());
        stage.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode != Keys.ESCAPE) {
                    return readyKey(keycode);
                }
                // Escape closes a menu first, then leaves a text field, so typing never reaches the lobby's keys
                if (topBar.back() || players.back() || forces.back() || battlefield.back()) {
                    return true;
                }
                if (stage.getKeyboardFocus() instanceof TextField) {
                    stage.setKeyboardFocus(null);
                    return true;
                }
                if (REQUISITION.equals(centre.showing()) && requisition.back()) {
                    return true;
                }
                if (centre.showing() != null) {
                    centre.back();
                    return true;
                }
                return false;
            }
        });
    }

    /** The Ready key (KeyCommandBind.DONE, Ctrl+Enter by default) presses Ready, unless a text field has the keys. */
    private boolean readyKey(int keycode) {
        if (stage.getKeyboardFocus() instanceof TextField) {
            return false;
        }
        KeyCommandBind bind = KeyCommandBind.DONE;
        if (GpuBattleView.awtKey(keycode) != bind.key || GpuBattleView.modifiers() != bind.modifiers) {
            return false;
        }
        window.ready();
        return true;
    }

    /**
     * Goes to a warning's cause: its first player's card, its first unit's card in the forces, or the map setup; a
     * folded window first brings the forces back to the centre for a unit.
     */
    private void goTo(LobbyReadiness.Warning warning) {
        switch (warning.kind().target()) {
            case PLAYER -> {
                if (!warning.players().isEmpty()) {
                    players.reveal(warning.players().get(0));
                }
            }
            case UNIT -> {
                if (!warning.units().isEmpty()) {
                    if (centre.showing() != null) {
                        centre.back();
                    }
                    forces.reveal(warning.units().get(0));
                }
            }
            case MAP -> window.battlefieldCommand(LobbyBattlefieldController.Command.MAP_SETUP);
        }
    }

    /** GPU thread: lights the red warnings, as Ready was pressed while they stand. */
    void pulseWarnings() {
        if (topBar != null) {
            topBar.pulse();
        }
    }

    /** The player column's actions, which the window runs on the Swing thread. */
    private LobbyPlayersPanel.Actions playerActions() {
        return new LobbyPlayersPanel.Actions() {
            @Override
            public void chat(String text) {
                window.chat(text);
            }

            @Override
            public void ready() {
                window.ready();
            }

            @Override
            public void playerSettings(int id) {
                window.playerSettings(id);
            }

            @Override
            public void camouflage(int id) {
                window.camouflage(id);
            }

            @Override
            public void changeTeam(int id, int team) {
                window.changeTeam(id, team);
            }

            @Override
            public void changeDeployment(int id, int position) {
                window.changeDeployment(id, position);
            }

            @Override
            public void toggleGameMaster() {
                window.toggleGameMaster();
            }

            @Override
            public void botSettings(int id) {
                window.botSettings(id);
            }

            @Override
            public void removeBot(int id) {
                window.removeBot(id);
            }

            @Override
            public void replaceWithBot(int id) {
                window.replaceWithBot(id);
            }

            @Override
            public void addBot(int team) {
                window.addBot(team);
            }
        };
    }

    /** The forces column's actions, which the window runs on the Swing thread. */
    private LobbyForcesPanel.Actions forceActions() {
        return new LobbyForcesPanel.Actions() {
            @Override
            public void command(LobbyForcesPanel.Command command) {
                if (command == LobbyForcesPanel.Command.ADD_UNIT) {
                    openRequisition();
                } else {
                    window.forcesCommand(command);
                }
            }

            @Override
            public void sort(int index) {
                window.sortUnits(index);
            }

            @Override
            public void configure(int unitId) {
                window.configureUnit(unitId);
            }

            @Override
            public void drop(List<Integer> unitIds, List<Integer> formationIds, LobbyForcesController.Target target) {
                window.dropForces(unitIds, formationIds, target);
            }

            @Override
            public void menu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView) {
                window.unitMenu(unitIds, formationIds, formationsView);
            }

            @Override
            public void choose(List<Integer> path) {
                window.chooseMenu(path);
            }
        };
    }

    /** The Requisition's actions: searches on their own thread, the rest through the window on the Swing thread. */
    private LobbyRequisitionPanel.Actions requisitionActions() {
        return new LobbyRequisitionPanel.Actions() {
            @Override
            public void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done) {
                search.search(query, result -> GpuHost.post(() -> {
                    if (stage != null) {
                        done.accept(result);
                    }
                }));
            }

            @Override
            public void add(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills) {
                window.requisitionAdd(unit, quantity, ownerId, skills);
            }

            @Override
            public void remove(int id) {
                window.requisitionRemove(id);
            }

            @Override
            public void sprites(List<MekSummary> units) {
                window.requisitionSprites(units);
            }

            @Override
            public void source(LobbyForcesPanel.Command command) {
                window.forcesCommand(command);
            }
        };
    }

    /** Opens the Requisition in the forces' place, afresh unless it shows already. */
    private void openRequisition() {
        if (REQUISITION.equals(centre.showing())) {
            return;
        }
        centre.show(REQUISITION, Messages.getString("Lobby.requisition.title"), requisition.body());
        requisition.opened(shown == null ? LobbyForces.EMPTY : shown.forces());
    }

    /** GPU thread: takes the sprite the window drew of the catalogue unit {@code key}. */
    void requisitionSprite(String key, BufferedImage image) {
        if (stage == null) {
            return;
        }
        Texture texture = GpuStartScreen.texture(image, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        TextureRegionDrawable old = requisitionSprites.put(key, new TextureRegionDrawable(texture));
        if (old != null) {
            old.getRegion().getTexture().dispose();
        }
        Iterator<Map.Entry<String, TextureRegionDrawable>> eldest = requisitionSprites.entrySet().iterator();
        while (requisitionSprites.size() > REQUISITION_SPRITES && eldest.hasNext()) {
            Map.Entry<String, TextureRegionDrawable> entry = eldest.next();
            eldest.remove();
            entry.getValue().getRegion().getTexture().dispose();
            requisition.spriteForgotten(entry.getKey());
        }
        requisition.spriteArrived(key);
    }

    /** The battlefield column's actions, which the window runs on the Swing thread. */
    private LobbyBattlefieldPanel.Actions battlefieldActions() {
        return new LobbyBattlefieldPanel.Actions() {
            @Override
            public void command(LobbyBattlefieldController.Command command) {
                window.battlefieldCommand(command);
            }

            @Override
            public void autoResolve(boolean chances, int runs, int threads) {
                window.autoResolve(chances, runs, threads);
            }
        };
    }

    /**
     * GPU thread: shows the map the window drew, or, without one, that it is being drawn ({@code drawing}) or that
     * there is none.
     */
    void map(@Nullable BufferedImage image, boolean drawing) {
        if (stage == null) {
            return;
        }
        if (mapTexture != null) {
            mapTexture.dispose();
            mapTexture = null;
        }
        if (image == null) {
            battlefield.map(null, LobbyBattlefieldPanel.MAP_ASPECT, drawing);
            return;
        }
        mapTexture = GpuStartScreen.texture(image, true);
        mapTexture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        float aspect = Math.clamp((float) image.getHeight() / image.getWidth(), .4f, 1f);
        battlefield.map(new TextureRegionDrawable(mapTexture), aspect, false);
    }

    /** GPU thread: opens the unit menu the window read for the forces column. */
    void unitMenu(List<LobbyMenuEntry> entries) {
        if (forces != null) {
            forces.showMenu(entries);
        }
    }

    /** GPU thread: takes the images of new sprite keys and each shown unit's key, then draws the cards again. */
    void sprites(Map<Integer, String> keys, Map<String, BufferedImage> images) {
        if (stage == null) {
            return;
        }
        images.forEach((key, image) -> {
            // Mipmapped and linear, as the cards draw the sprites smaller than the tiles
            Texture texture = GpuStartScreen.texture(image, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            Texture old = spriteTextures.put(key, texture);
            if (old != null) {
                old.dispose();
            }
        });
        spriteDrawables.clear();
        keys.forEach((id, key) -> {
            Texture texture = spriteTextures.get(key);
            if (texture != null) {
                spriteDrawables.put(id, new TextureRegionDrawable(texture));
            }
        });
        forces.redraw();
    }

    /** The battlefield's body goes back to its column whenever another view, or the forces, take the centre. */
    private void centreChanged(String showing) {
        boolean battlefieldShown = BATTLEFIELD.equals(showing);
        rail.setChecked(battlefieldShown);
        if (!battlefieldShown && battlefield.body().getParent() != battlefield.panel()) {
            battlefield.dock();
        }
    }

    /** Folds the battlefield into its rail below the fold width, and unfolds it, closing its centre view, above. */
    private void applyFold() {
        boolean fold = LobbyLayout.folds(stage.getWidth());
        if (fold == folded) {
            return;
        }
        folded = fold;
        topBar.narrow(fold);
        if (!fold && BATTLEFIELD.equals(centre.showing())) {
            centre.back();
        }
        columns.fold(fold);
    }

    /** GPU thread: shows {@code snapshot}, the lobby as its window last read it. */
    void show(LobbySnapshot snapshot) {
        if (stage == null || snapshot.equals(shown)) {
            return;
        }
        LobbySnapshot before = shown;
        shown = snapshot;
        topBar.show(snapshot);
        players.show(snapshot, before);
        if (before == null || !before.forces().equals(snapshot.forces())) {
            forces.show(snapshot.forces());
        }
        requisition.show(snapshot.requisition(), snapshot.forces());
        battlefield.show(snapshot);
    }

    // ---- Size and scale ----

    @Override
    public void resize(int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        scale = DisplayScale.read(preferences.getGUIScale(), new GpuDisplayScale().contentScale());
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
        bw = Gdx.graphics.getBackBufferWidth();
        bh = Gdx.graphics.getBackBufferHeight();
        applyFold();
    }

    // ---- Each frame ----

    @Override
    public void render() {
        Gdx.gl.glClearColor(base.getRed() / 255f, base.getGreen() / 255f, base.getBlue() / 255f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (railPending && folded) {
            railPending = false;
            rail.setChecked(true);
            centre.show(BATTLEFIELD, Messages.getString("Lobby.battlefield"), battlefield.body());
        }
        stage.act(Math.min(0.1f, Gdx.graphics.getDeltaTime()));
        // Once the bots have joined: a second before the shot
        if (menuPending && (System.nanoTime() - start) / 1e9 > Math.max(1, window.probe().shotAt() - 1)
              && players.openFirstMenu()) {
            menuPending = false;
        }
        if (unitMenuPending && (System.nanoTime() - start) / 1e9 > Math.max(1, window.probe().shotAt() - 1)
              && forces.openFirstUnitMenu()) {
            unitMenuPending = false;
        }
        probeInput();
        hud.update(Set.of());
        stage.draw();
        takeShot();
    }

    /**
     * The developer switch's clicks, through the stage as the Commander's would go: the compact rows toggle and the
     * Filters button {@value #PROBE_CLICK_AT} s in, Filters again a second later to shut the drawer, and half a second
     * before the shot the pointer on the second result row beside the scroll bar, or on the bar.
     */
    private void probeInput() {
        GpuLobbyProbe probe = window.probe();
        if (probe == null) {
            return;
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        if (clickCompactPending && elapsed > PROBE_CLICK_AT) {
            clickCompactPending = false;
            GpuLobbyProbeInput.click(stage, forces.compactToggle());
            LOGGER.info("The lobby probe's compact click: compact rows {}", forces.compact() ? "on" : "off");
        }
        if (filterClicksPending > 0 && REQUISITION.equals(centre.showing())
              && elapsed > PROBE_CLICK_AT + (filterClicksPending == 1 && probe.options().contains("closefilters")
              ? 1 : 0)) {
            filterClicksPending--;
            GpuLobbyProbeInput.click(stage, requisition.filtersToggle());
            LOGGER.info("The lobby probe's Filters click: the drawer is {}", requisition.drawerOpen() ? "open"
                  : "shut");
        }
        if (hoverPending != null && elapsed > probe.shotAt() - .5 && !requisition.resultsList().items().isEmpty()) {
            ScrollPane pane = requisition.resultsPane();
            UiVirtualList<?> list = requisition.resultsList();
            float rowHeight = list.getPrefHeight() / list.items().size();
            int[] at = "bar".equals(hoverPending)
                  ? GpuLobbyProbeInput.hover(stage, pane, pane.getWidth() - pane.getScrollBarWidth() / 2,
                  pane.getHeight() / 2)
                  : GpuLobbyProbeInput.hover(stage, list, list.getWidth() - 1,
                  list.getHeight() - pane.getScrollY() - rowHeight * 1.5f);
            LOGGER.info("The lobby probe's pointer is on the results' {} at {}", hoverPending,
                  at == null ? "nothing" : at[0] + "," + at[1]);
            hoverPending = null;
            requisition.probeMeasure();
        }
    }

    /** Saves the developer switch's PNG once its time has come, then quits when it asks to. */
    private void takeShot() {
        GpuLobbyProbe probe = window.probe();
        if (shotSaved || probe == null || probe.shot() == null || (System.nanoTime() - start) / 1e9 < probe.shotAt()) {
            return;
        }
        shotSaved = true;
        if (probe.dialog() != null) {
            // The frame buffer has no Swing dialogs, so the screen is photographed instead
            SwingUtilities.invokeLater(window::probeDialog);
            return;
        }
        GpuLobbyProbe.reportWindows();
        if (GpuStartScreen.saveFrame(probe.shot(), bw, bh)) {
            LOGGER.info("Lobby shot saved to {}", probe.shot().getAbsoluteFile());
        }
        if (probe.quit()) {
            // After the window report, which waits its turn on the same thread
            SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> System.exit(0)));
        }
    }

    @Override
    public void dispose() {
        window.disposing();
        spriteTextures.values().forEach(Texture::dispose);
        spriteTextures.clear();
        requisitionSprites.values().forEach(sprite -> sprite.getRegion().getTexture().dispose());
        requisitionSprites.clear();
        if (searchThread != null) {
            searchThread.shutdownNow();
        }
        if (mapTexture != null) {
            mapTexture.dispose();
            mapTexture = null;
        }
        for (Object disposable : new Object[] { stage }) {
            if (disposable instanceof com.badlogic.gdx.utils.Disposable resource) {
                resource.dispose();
            }
        }
        if (hud != null) {
            hud.dispose();
        }
        if (boardSkin != null) {
            boardSkin.dispose();
        }
    }
}
