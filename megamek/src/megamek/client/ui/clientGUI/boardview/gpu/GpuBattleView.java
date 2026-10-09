/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiCursorCapture;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/** GPU board and Scene2D controls. The source remains the sole bridge to the existing client. */
class GpuBattleView extends ApplicationAdapter {
    /** Airborne units hover: one full wave cycle lasts this long. */
    static final float HOVER_PERIOD_SECONDS = UnitAnimator.HOVER_PERIOD_SECONDS;
    /** Height of that wave in terrain levels, so a floating token drifts off its flight height. */
    static final float HOVER_LEVELS = UnitAnimator.HOVER_LEVELS;
    /** Hover and editor-brush outline inset as a fraction of the hex radius: 0 is the border, .1 keeps 90%. */
    static final float HOVER_HEX_INSET = .1f;
    /** Hidden hover and editor-brush outlines remain visible through terrain and objects at half opacity. */
    static final float HOVER_OCCLUDED_ALPHA = .5f;
    /** Hide all declared firing arrows while combat is playing, regardless of selection. */
    static final boolean HIDE_TARGET_ARROWS_DURING_ATTACKS = true;
    /** unit's screen rectangle is .55 of a hex wide. */
    private static final float UNIT_RECT_WIDTH = .55f;
    /** Floating units are tied to their hex with this faint solid stem; solid, so it needs no blending state. */
    private static final Color TETHER_COLOR = Color.valueOf("A9B8B8");
    private static final float PAN_PIXELS_PER_SECOND = 750;
    private static final float FLIGHT_HEXES_PER_SECOND = 1.5f;
    private static final float CAMERA_SPEED_BOOST = 4;
    private static final float TILT_DEGREES_PER_SECOND = 60;
    /** A held CAMERA_ROTATE bind turns the 3D camera at this rate (rebuild plan A.17 Q9). */
    private static final float ROTATE_DEGREES_PER_SECOND = 90;
    /** The zoom factor of one ZOOM_IN or ZOOM_OUT bind; the Menu's View zoom items use it as well. */
    static final float ZOOM_STEP = 1.2f;
    /** A pointer that moved this far, times the layout scale, drags: the camera moves and no click follows (C.4). */
    private static final float DRAG_THRESHOLD = 6;
    /** Degrees a middle drag orbits the camera per layout unit, on the board and on the minimap (C.4). */
    static final float ORBIT_DEGREES = .3f;
    private static final MMLogger LOGGER = MMLogger.create(GpuBattleView.class);
    void zoomEditor(int direction) { boardCamera.zoom(direction < 0 ? 1 / 1.2f : 1.2f); }

    private BoardSource source;
    private Stage loadingStage;
    private GpuBoardSkin loadingTheme;
    private Label loadingLabel;
    private Label loadingDetails;
    private GpuLoadingGrid loadingGrid;
    private Label loadingWaiting, loadingActive, loadingReady;
    private volatile String loadingMessage = Messages.getString("ClientGUI.waitingOnTheServer");
    private final GpuDisplayScale displayScale = new GpuDisplayScale();
    final BoardCamera boardCamera = new BoardCamera();
    private final UnitPlayback playback = new UnitPlayback(this::completeMovement);
    /** The round's playback history (A.10): it feeds and reviews the playback; the HUD steers it. */
    private final GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
    private final BoardSurface.Cache groundSurfaces = new BoardSurface.Cache();
    private final Map<Integer, UnitMotion> motions = playback.motions;
    private final GpuAttackEffects attackEffects = new GpuAttackEffects();
    private final GpuTerrainEffects terrainEffects = new GpuTerrainEffects();
    private final GpuEffectDepth effectDepth = new GpuEffectDepth();
    private final GpuWaterImpacts waterImpacts = new GpuWaterImpacts();
    private record UnitSprite(BoardScene.Pixels pixels, boolean meeple) { }
    private final Map<UnitSprite, GpuUnitModel> spriteModels = new HashMap<>();
    private final GpuUnitModels unitModels = GpuUnitModels.ENABLED ? new GpuUnitModels() : null;
    private final UnitDamageDisplay damageDisplay = new UnitDamageDisplay();
    private final GpuJumpJets jumpJets = new GpuJumpJets();
    private final GpuUnitCamouflage camouflage = new GpuUnitCamouflage();
    private final GpuUnitIcons unitIcons = new GpuUnitIcons();
    private final Map<BoardScene.Unit, Vector3> unitAnchors = new HashMap<>();
    private final Map<BoardScene.Unit, UnitFootprint.Pose> unitFootprints = new HashMap<>();
    private final Map<String, ModelInstance> unitInstances = new HashMap<>();
    /** The instance the view shows for a scene unit: its icon in the Tactical View, else its 3D model. */
    private final Function<BoardScene.Unit, ModelInstance> shownUnit = unit -> unitIcons.active()
          ? unitIcons.instance(unit) : unitInstances.get(unit.id() + ":" + unit.part());
    private final UnitBounds.Frame unitBounds = new UnitBounds.Frame();
    private final UnitPicking unitPicking = new UnitPicking();
    private final Map<String, UnitModelState.Appearance> equipmentAppearance = new HashMap<>();
    private final Map<String, BoardScene.Pixels> unitTints = new HashMap<>();
    private final Map<String, UpperBodyTurn> upperBodyTurns = new HashMap<>();
    private final Map<String, ArmFlip> armFlips = new HashMap<>();
    private final Map<String, UnitAnimator> animators = new HashMap<>();
    private final Map<String, BoardScene.LocationDamage> unitDamage = new HashMap<>();
    private final Map<Integer, KeyCommandBind> cameraKeys = new HashMap<>();
    private final BoardInput boardInput = new BoardInput();
    /** Render-thread snapshot of modifiers delivered to the separate Swing tools window. */
    private final List<Hover> hover = new ArrayList<>();
    private GpuTerrain terrain;
    private GpuFireControl fireControl;
    /** The board overlay: envelopes, the route and its ghost, rings and glows, bands and arcs. */
    private GpuBoardOverlay overlay;
    private GpuTactical tactical;
    private final GpuFieldOfView fieldOfView;
    private GpuAtmosphere atmosphere;
    private GpuUnitVisibility unitVisibility;
    private GpuWireframe wireframe;
    private GpuMarkers markers;
    private GpuTextures<BoardScene.Pixels> unitTextures;
    private ModelBatch unitBatch;
    private SpriteBatch annotationBatch;
    private ShapeRenderer lines;
    /** The HUD; its skin also holds the hex labels' font. */
    private GpuBoardHud ui;
    private GpuBoardSkin theme;
    /** The presentation settings the board reads; the HUD's developer tuning panel edits them. */
    private GpuBoardTuning tuning;
    private BoardScene scene;
    private GpuHexText hexText;
    private GpuHexGrid hexGrid;
    private Coords hovered;
    private int hoveredUnit = Entity.NONE;
    /** While a move animates the hovered hex is not published, so no route is previewed (rebuild plan A.17 Q5). */
    private boolean hoverHeld;
    /** Height of the walkable surface under the latest pointer in the hovered hex, owned by the render thread like hovered. */
    private float hoverZ = Float.NaN;
    /** {@link #frameObjects}' hexes, read during frame {@link #frameObjectsFrame}. */
    private final Map<Coords, List<GpuTerrain.EditorObject>> frameObjects = new HashMap<>();
    private long frameObjectsFrame = -1;
    private SystemCursor cursor = SystemCursor.Arrow;
    private boolean fitted;
    private long frames;
    private float hoverClock;
    private final UnitAttachments attachments = new UnitAttachments(groundSurfaces);
    private long boardGeneration;
    private long centerSequence = -1;
    private boolean entrancePending;
    private boolean entranceStarting;
    private int cameraSelection = -1;
    private boolean cameraFollowingPlayback;
    private int layoutWidth;
    private int layoutHeight;
    private int layoutPixelWidth;
    private int layoutPixelHeight;
    private float layoutScale;
    private float layoutPreference;
    private long hoverCameraRevision;
    private int cameraTerrainRevision = -1;
    private boolean reloadingAssets;
    private boolean waitingForAssetCapture;
    private boolean assetReloadFailed;
    private boolean disposed;
    private final GpuShaderManager shaderEdits = new GpuShaderManager();

    GpuBattleView(BoardSource source) {
        this.source = source;
        fieldOfView = new GpuFieldOfView();
    }

    void setLoadingMessage(String message) {
        loadingMessage = message;
    }

    /** Attach the first map after the native loading window is already visible. Runs on the render thread. */
    void attachSource(BoardSource next) {
        source = next;
        shaderEdits.run(this::createBoard);
    }

    void prepareEntrance() {
        entrancePending = true;
    }

    void startEntrance() {
        entrancePending = false;
        entranceStarting = true;
        if (scene != null) {
            boardCamera.enter(scene);
        }
    }

    /**
     * GL thread. Enters the Tactical View's top view, whose next frame shows unit icons and flat terrain sprites, or
     * returns to the exact 3D pose it replaced. The {@code TOGGLE_ISO} key (T by default) calls this.
     */
    void setTacticalView(boolean enabled) {
        boardCamera.setTactical(enabled, scene);
    }

    @Override
    public void create() {
        shaderEdits.run(this::createView);
    }

    private void createView() {
        if (source == null) {
            createLoadingStage();
            Gdx.input.setInputProcessor(new InputMultiplexer(loadingStage));
            return;
        }
        createBoard();
    }

    private void createLoadingStage() {
        if (loadingStage != null) { return; }
        if (theme == null && loadingTheme == null) { loadingTheme = new GpuBoardSkin(); }
        var skin = theme != null ? theme.skin : loadingTheme.skin;
        loadingStage = new Stage(new ScreenViewport());
        Table content = new Table();
        content.setFillParent(true);
        content.top().padTop(Value.percentHeight(.06f, content));
        loadingLabel = new Label(loadingMessage, skin, "hud-phase");
        loadingLabel.setName("board-loading-message");
        loadingLabel.setWrap(true);
        loadingLabel.setAlignment(Align.center);
        loadingDetails = new Label("", skin, "hud-title");
        loadingDetails.setName("board-loading-details");
        loadingDetails.setWrap(true);
        loadingDetails.setAlignment(Align.top | Align.center);
        loadingGrid = new GpuLoadingGrid(skin);
        Table legend = new Table();
        loadingWaiting = new Label("", skin);
        loadingActive = new Label("", skin);
        loadingReady = new Label("", skin);
        loadingWaiting.setName("board-loading-waiting");
        loadingActive.setName("board-loading-active");
        loadingReady.setName("board-loading-ready");
        loadingWaiting.setColor(UiTheme.MUTED);
        loadingActive.setColor(GpuLoadingGrid.LOADING);
        loadingReady.setColor(GpuLoadingGrid.READY);
        for (Label entry : List.of(loadingWaiting, loadingActive, loadingReady)) {
            legend.add(entry).padLeft(12).padRight(12);
        }
        content.add(new Label("MEGAMEK", skin, "hud-small")).padBottom(12).row();
        content.add(loadingLabel).width(Value.percentWidth(.9f, content)).maxWidth(900)
              .height(loadingLabel.getStyle().font.getLineHeight() * 2).row();
        content.add(loadingGrid).width(Value.percentWidth(.90f, content)).maxWidth(960)
              .height(Value.percentHeight(.42f, content)).maxHeight(360).padTop(14).row();
        content.add(legend).padTop(10).row();
        content.add(loadingDetails).width(Value.percentWidth(.9f, content)).maxWidth(900).padTop(14);
        loadingStage.addActor(content);
    }

    private void createBoard() {
        boardCamera.setIsometric(true);
        createSceneRenderers();
        boardCamera.flightCollision = (eye, movement) -> {
            if (terrain != null) { terrain.moveCamera(eye, movement, boardCamera.collisionRadius()); }
        };
        boardCamera.terrainHit = ray -> scene == null || terrain == null ? null : terrain.selectionHit(scene, ray);
        markers = new GpuMarkers();
        if (source.isGameplay()) { markers.prepareModels(); }
        unitTextures = new GpuTextures<>();
        overlay = new GpuBoardOverlay();
        // One skin for the window: the loading screen's, when it showed, becomes the HUD's (M1 row 1). The loading
        // screen borrows it again while the terrain builds.
        theme = loadingTheme == null ? new GpuBoardSkin() : loadingTheme;
        loadingTheme = null;
        tuning = new GpuBoardTuning(theme.skin, source, boardCamera);
        // The HUD draws with the view's batch, which it does not own: the battle HUD over a game, the map tools over
        // the board editor and the map preview. The batch outlives an asset reload, which recreates the renderers.
        annotationBatch = new SpriteBatch();
        ui = source instanceof GpuBoardSource game
              ? new GpuHud(game, theme.skin, annotationBatch, boardCamera, tuning, history)
              : new GpuMapHud(source, theme.skin, annotationBatch, boardCamera, tuning, history, () -> terrain);
        Gdx.input.setInputProcessor(new InputMultiplexer(ui.stage(), boardInput) {
            // The HUD's Stage takes the presses on its widgets; every key goes through BoardInput, which asks the
            // HUD first (C.4), so no key reaches the Stage twice.
            @Override
            public boolean scrolled(float amountX, float amountY) {
                int x = Gdx.input.getX(), y = Gdx.input.getY();
                megamek.client.ui.gdx.UiKit.focusScrollAt(ui.stage(), x, y);
                // A board wheel gesture leaves the edited field, just as a board press does.
                if (source.isEditor() && !ui.hit(x, y)) { ui.stage().setKeyboardFocus(null); }
                return super.scrolled(amountX, amountY);
            }

            @Override
            public boolean touchDown(int x, int y, int pointer, int button) {
                // Finish wheel edits before a toolbar or menu action can consume the click.
                boardInput.finishElevationScroll();
                return super.touchDown(x, y, pointer, button);
            }

            @Override
            public boolean keyDown(int key) {
                boardInput.finishElevationScroll();
                return boardInput.keyDown(key);
            }

            @Override
            public boolean keyUp(int key) {
                return boardInput.keyUp(key);
            }

            @Override
            public boolean keyTyped(char character) {
                return boardInput.keyTyped(character);
            }
        });
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    /** Recreate every board shader owner; lazy effect programs compile again when their effects are drawn. */
    private void createSceneRenderers() {
        terrain = new GpuTerrain(unitModels, unitBounds);
        fireControl = new GpuFireControl();
        tactical = new GpuTactical(terrain::tacticalSurface);
        atmosphere = new GpuAtmosphere();
        unitVisibility = new GpuUnitVisibility();
        wireframe = new GpuWireframe();
        unitBatch = new ModelBatch(GpuShaderManager.provider("Units", GpuUnitShader::provider), new GpuOpaqueSorter());
        hexText = new GpuHexText();
        hexGrid = new GpuHexGrid();
        lines = new ShapeRenderer();
    }

    /** Render-thread only. Null released owners so a failed shader compile can be retried safely. */
    private void disposeSceneRenderers() {
        // Join the terrain worker before releasing the model buffers it borrows.
        if (terrain != null) { terrain.dispose(); terrain = null; }
        if (fireControl != null) { fireControl.dispose(); fireControl = null; }
        if (tactical != null) { tactical.dispose(); tactical = null; }
        if (atmosphere != null) { atmosphere.dispose(); atmosphere = null; }
        if (unitVisibility != null) { unitVisibility.dispose(); unitVisibility = null; }
        if (wireframe != null) { wireframe.dispose(); wireframe = null; }
        if (unitBatch != null) { unitBatch.dispose(); unitBatch = null; }
        if (hexText != null) { hexText.dispose(); hexText = null; }
        if (hexGrid != null) { hexGrid.dispose(); hexGrid = null; }
        if (lines != null) { lines.dispose(); lines = null; }
        attackEffects.dispose();
        terrainEffects.dispose();
        jumpJets.dispose();
        waterImpacts.dispose();
        effectDepth.dispose();
    }

    @Override
    public void resize(int width, int height) {
        if (loadingStage != null && width > 0 && height > 0) {
            loadingStage.getViewport().update(width, height, true);
        }
        if (ui == null || width <= 0 || height <= 0) {
            return;
        }
        float preference = source.uiPreferences().scale();
        float scale = DisplayScale.read(preference, displayScale.contentScale());
        int pixelWidth = Gdx.graphics.getBackBufferWidth();
        int pixelHeight = Gdx.graphics.getBackBufferHeight();
        if (width == layoutWidth && height == layoutHeight && scale == layoutScale && preference == layoutPreference
              && pixelWidth == layoutPixelWidth && pixelHeight == layoutPixelHeight) {
            return;
        }
        layoutWidth = width;
        layoutHeight = height;
        layoutPixelWidth = pixelWidth;
        layoutPixelHeight = pixelHeight;
        layoutScale = scale;
        layoutPreference = preference;
        ui.resize(width, height, scale);
        // The board fills the window; the HUD's panels float over it (rebuild plan A.1 A1).
        boardCamera.resize(width, height, scene, scale);
        // In Swing's units (the monitor's scale without the GUI scale): the board editor measures its tools by it.
        float swingScale = scale / preference;
        source.setViewport(Math.round(width / swingScale), Math.round(height / swingScale), pixelWidth, pixelHeight);
    }

    @Override
    public void render() {
        shaderEdits.run(() -> {
            renderBoard();
            shaderEdits.renderPreview();
        });
    }

    private void renderBoard() {
        if (!waitingForAssetCapture && shaderEdits.update() && terrain != null) { terrain.shadersChanged(); }
        if (tuning != null && tuning.takeAssetReloadRequest()) { reloadAssets(); }
        if (waitingForAssetCapture || assetReloadFailed) {
            if (source.isClosed()) { Gdx.app.exit(); return; }
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
            resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            ui.draw();
            return;
        }
        try (TerrainSettings.Scope settings = TerrainSettings.use(terrain == null ? null : terrain.settings())) {
            renderFrame(settings);
        } catch (RuntimeException failure) {
            if (!reloadingAssets) { throw failure; }
            assetReloadFailed(failure);
        }
    }

    /** The EDT rereads artwork first; only its completion hands GPU disposal back to the render thread. */
    private void reloadAssets() {
        pause();
        reloadingAssets = true;
        waitingForAssetCapture = true;
        assetReloadFailed = false;
        var application = Gdx.app;
        SwingUtilities.invokeLater(() -> {
            try {
                source.reloadAssets();
                application.postRunnable(() -> shaderEdits.run(() -> {
                    if (disposed || source.isClosed()) { return; }
                    waitingForAssetCapture = false;
                    try {
                        disposeSceneRenderers();
                        clearUnitInstances();
                        spriteModels.values().forEach(GpuUnitModel::dispose);
                        spriteModels.clear();
                        if (unitModels != null) { unitModels.dispose(); }
                        camouflage.dispose();
                        damageDisplay.dispose();
                        unitTextures.dispose();
                        unitIcons.dispose();
                        groundSurfaces.clear();
                        attachments.clear();
                        BoardRocks.reload();
                        BoardRough.reload();
                        BoardFungus.reload();
                        BoardScatter.reload();
                        BoardObstacles.reload();
                        BoardDecals.reload();
                        BoardBridgeFooting.reload();
                        BoardBridgeSlope.reload();
                        createSceneRenderers();
                        cameraTerrainRevision = -1;
                    } catch (RuntimeException failure) {
                        assetReloadFailed(failure);
                    }
                }));
            } catch (java.io.IOException | RuntimeException failure) {
                application.postRunnable(() -> {
                    if (!disposed && !source.isClosed()) { assetReloadFailed(failure); }
                });
            }
        });
    }

    private void assetReloadFailed(Exception failure) {
        LOGGER.error("Cannot reload GPU board assets", failure);
        // Lazy programs can fail inside a scene/shadow pass. Keep the retry panel on the window framebuffer.
        FrameBuffer.unbind();
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        reloadingAssets = false;
        waitingForAssetCapture = false;
        assetReloadFailed = true;
        tuning.assetReloadFinished(false);
    }

    private void renderFrame(TerrainSettings.Scope settings) {
        if (source == null) {
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
            loadingLabel.setText(loadingMessage);
            loadingStage.act(Math.min(Gdx.graphics.getDeltaTime(), .1f));
            loadingStage.draw();
            return;
        }
        if (source.isClosed()) {
            Gdx.app.exit();
            return;
        }
        if (Gdx.graphics.getWidth() <= 0 || Gdx.graphics.getHeight() <= 0) {
            return;
        }
        // Moving to another monitor can change DPI without changing the window's dimensions.
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        BoardSource.Frame frame = source.takeFrame();
        if (frame.scene() == null) {
            // Without a board the HUD still draws: a dialog asked now holds the EDT until it is answered here.
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
            updateHud(frame);
            ui.draw();
            return;
        }
        renderStage("scene update");
        if (scene != null && (boardGeneration != frame.boardGeneration() || scene.boardId() != frame.scene().boardId() || scene.width() != frame.scene().width()
              || scene.height() != frame.scene().height())) {
            terrain.boardChanged();
            playback.clear();
            history.clear();
            animators.clear();
            groundSurfaces.clear();
            attachments.clear();
            jumpJets.clear();
            waterImpacts.clear();
            unitPicking.clear();
            hovered = null;
            boardInput.reset();
            fitted = false;
            boardCamera.boardChanged();
        }
        tuning.useScenario(frame.scenarioAtmosphere(), scene == null || boardGeneration != frame.boardGeneration()
              || scene.boardId() != frame.scene().boardId());
        List<BoardScene.Tile> previousTiles = scene == null ? null : scene.tiles();
        scene = frame.scene();
        if (unitModels != null) { unitModels.setDisplayMode(tuning.unitDisplayMode()); }
        history.accept(frame.timeline(), scene, frame.reports(), this::hasInfantryTransports);
        scene = history.live(scene);
        // Apply HUD state independently of camera framing; panels overlay the full board viewport.
        if (ui instanceof GpuHud hud) { hud.updateState(frame, playbackBusy(), dialog()); }
        playback.gravityOverride = tuning.gravityOverride();
        if (!fitted) { updateCameraFocus(scene, frame.centerRequest()); }
        var instantAction = history.speed() == UnitMotion.Speed.INSTANT ? playback.lastAction() : null;
        history.advance(Gdx.graphics.getDeltaTime(), state -> preparePlaybackCamera(state, scene));
        source.playbackState(frame, playbackBusy());
        scene = playback.present(scene);
        camouflage.retain(scene.units());
        if (unitModels != null) {
            unitModels.retainAssemblies(scene.units().stream().filter(unit -> !unit.sensorContact())
                  .map(BoardScene.Unit::id).collect(Collectors.toSet()));
        }
        boardGeneration = frame.boardGeneration();
        var atmosphereSettings = tuning.atmosphere();
        atmosphere.configure(atmosphereSettings);
        atmosphere.setOptions(tuning.atmosphereOptions());
        // Material readiness includes live lighting/cloud flags, before any visible terrain is submitted.
        atmosphere.updateLight(boardCamera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        atmosphere.configureClouds(terrain, scene);
        terrain.setGravity(atmosphereSettings.gravity());
        terrain.editableObjects(source.isEditor());
        terrain.update(scene, boardCamera.camera);
        boolean detailChanged = terrain.refine(boardCamera.camera, source.isEditor());
        settings.set(terrain.settings());
        tuning.terrainProgress(terrain.buildProgress());
        if (!terrain.ready(scene)) {
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
            createLoadingStage();
            loadingLabel.setText(Messages.getString("GpuBoard.loadingOverall", Math.max(0, terrain.buildProgress())));
            var sections = terrain.buildSections();
            loadingGrid.update(sections);
            loadingWaiting.setText(Messages.getString("GpuBoard.loadingLegend.waiting",
                  Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.WAITING)));
            loadingActive.setText(Messages.getString("GpuBoard.loadingLegend.active",
                  Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.LOADING)));
            loadingReady.setText(Messages.getString("GpuBoard.loadingLegend.ready",
                  Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.READY), sections.states().size()));
            loadingDetails.setText(terrain.buildDetails().stream().map(TerrainLoadProgress.Status::text)
                  .collect(Collectors.joining("\n")));
            loadingStage.act(Math.min(Gdx.graphics.getDeltaTime(), .1f));
            loadingStage.draw();
            renderStage(null);
            return;
        }
        if (loadingStage != null) {
            loadingStage.dispose();
            loadingStage = null;
            if (loadingTheme != null) { loadingTheme.dispose(); }
            loadingTheme = null;
        }
        if (source.isEditor()) { terrain.previewEditorObjects(scene); }
        scene = terrain.presentation(scene);
        boolean changedTiles = previousTiles != scene.tiles();
        if (changedTiles || cameraTerrainRevision != BoardGeometry.revision()) {
            boardCamera.terrainChanged(scene);
            cameraTerrainRevision = BoardGeometry.revision();
        }
        fireControl.update(scene, HIDE_TARGET_ARROWS_DURING_ATTACKS && !playback.attacks().isEmpty(), frame.status(),
              frame.panels().fire());
        tactical.update(scene, detailChanged, hovered, !frame.panels().move().route().isEmpty());
        fieldOfView.update(scene.fieldOfView());
        fieldOfView.configure(tuning.fovStyle(), tuning.fovDarkness(), tuning.sensorStyle(), tuning.sensorDarkness());
        attackEffects.setWind(atmosphereSettings.effects());
        terrain.setNormalMaps(tuning.normalMaps());
        terrain.setTerrainWear(tuning.terrainWear());
        terrain.setGrass(tuning.grass());
        terrain.setWaterEffects(tuning.waterEffects());
        terrain.setSmallObjects(tuning.objectLod());
        if (unitTextures.update(scene.units().stream().filter(unit -> !unit.sensorContact()
              && (unitModels == null || unitModels.get(unit.model(), unit.id()) == null)).map(BoardScene.Unit::image).distinct()
              .collect(Collectors.toMap(pixels -> pixels, pixels -> pixels)))) {
            spriteModels.values().forEach(GpuUnitModel::dispose);
            spriteModels.clear();
            unitPicking.clear();
            attachments.geometryChanged();
        }
        Set<BoardScene.Pixels> images = scene.units().stream().map(BoardScene.Unit::image).collect(Collectors.toSet());
        boolean removedSprites = spriteModels.entrySet().removeIf(entry -> {
            if (images.contains(entry.getKey().pixels())) {
                return false;
            }
            entry.getValue().dispose();
            return true;
        });
        if (removedSprites) {
            unitPicking.clear();
            attachments.geometryChanged();
        }
        updateCameraFocus(scene, frame.centerRequest(), instantAction);
        // The first visible frame's delta may still include the hidden window's loading time.
        boardCamera.advance(entranceStarting ? 0 : Gdx.graphics.getDeltaTime());
        entranceStarting = false;
        float cameraSpeed = (modifiers() & InputEvent.SHIFT_DOWN_MASK) != 0 ? CAMERA_SPEED_BOOST : 1;
        if (ui.isTextEditing() || ui.isModal()) {
            // Text editing and modal dialogs suspend held camera movement.
            cameraKeys.clear();
        } else if (boardCamera.firstPerson()) {
            advanceFirstPerson(Gdx.graphics.getDeltaTime(), cameraSpeed);
        } else {
            float elapsed = Gdx.graphics.getDeltaTime() * cameraSpeed;
            float distance = PAN_PIXELS_PER_SECOND * elapsed;
            float inclination = TILT_DEGREES_PER_SECOND * elapsed;
            float rotation = ROTATE_DEGREES_PER_SECOND * elapsed;
            for (KeyCommandBind command : cameraKeys.values()) {
                switch (command) {
                    case SCROLL_NORTH -> boardCamera.pan(0, distance);
                    case SCROLL_SOUTH -> boardCamera.pan(0, -distance);
                    case SCROLL_EAST -> boardCamera.pan(-distance, 0);
                    case SCROLL_WEST -> boardCamera.pan(distance, 0);
                    case CAMERA_TILT_UP -> boardCamera.tilt(-inclination);
                    case CAMERA_TILT_DOWN -> boardCamera.tilt(inclination);
                    case CAMERA_ROTATE_LEFT -> boardCamera.orbit(-rotation, 0);
                    case CAMERA_ROTATE_RIGHT -> boardCamera.orbit(rotation, 0);
                    default -> { }
                }
            }
        }
        if (changedTiles || hoverCameraRevision != boardCamera.revision()) {
            source.setVisibleArea(boardCamera.visibleArea(scene));
        }
        if (hoverCameraRevision != boardCamera.revision() || hoverHeld != (playback.movement() != null)) {
            hoverCameraRevision = boardCamera.revision();
            hoverHeld = playback.movement() != null;
            boardInput.mouseMoved(Gdx.input.getX(), Gdx.input.getY());
        }
        hoverClock += animationSeconds();
        renderStage("unit poses");
        markers.beginFrame(scene.markers(), Gdx.graphics.getDeltaTime());
        prepareUnits();
        applyHover();
        for (var unit : scene.units()) {
            var instance = unitInstances.get(unit.id() + ":" + unit.part());
            if (MeepleVisual.isMeeple(instance)) {
                MeepleAnimator.attacks(unit, instance, playback.attacks(), unitInstances);
                var visual = ((GpuUnitInstance) instance).visual();
                unitAnchors.put(unit, visual.anchor(instance, boardCamera.camera));
            }
        }
        var completedAttacks = playback.takeCompletedAttacks();
        for (var attacks : List.of(playback.attacks(), completedAttacks)) {
            for (var attack : attacks) {
                attack.landscape = ray -> terrain.hit(scene, ray);
                attack.landscapeSegment = (ray, length) -> terrain.hit(scene, ray, length);
            }
        }
        aimAttack();
        attachments.place(scene, unitModels, unitInstances, animators, playback.attachment(), boardCamera.camera, unitAnchors, hoverClock);
        for (var unit : scene.units()) {
            if (unit.attachment() == null) { continue; }
            scene.units().stream().filter(host -> host.id() == unit.attachment().carrierId()).findFirst().ifPresent(host -> {
                var pose = unitFootprints.get(host);
                if (pose != null) { unitFootprints.put(unit, new UnitFootprint.Pose(unit, pose.position(), pose.facing())); }
            });
        }
        updateEquipmentDetail();
        // Icons and the tileset columns are the Tactical View; the 3D view keeps its meshes at every angle and zoom.
        if (unitIcons.update(boardCamera.tactical(), boardCamera.camera, scene, frame.status(),
              unitFootprints, unitAnchors)) { unitPicking.clear(); }
        terrain.setTacticalView(boardCamera.tactical());
        markers.update(unitIcons.active() ? unitIcons.instances() : unitInstances.values(), boardCamera.camera);
        updateJumpJets();
        attackEffects.update(playback.attacks(), completedAttacks, unitModels, unitInstances);
        if (!terrain.tacticalView()) {
            attackEffects.groundImpacts(completedAttacks, impact -> terrain.impact(scene, impact));
        }
        unitBounds.begin();
        List<ModelInstance> units = new ArrayList<>(unitIcons.active() ? unitIcons.instances() : unitInstances.values());
        List<ModelInstance> outlined = scene.units().stream()
              .filter(unit -> !unitIcons.active())
              .filter(unit -> !unit.sensorContact() || GpuMarkers.outlineEnabled(BoardMarker.Kind.SENSOR_CONTACT))
              .map(unit -> unitInstances.get(unit.id() + ":" + unit.part()))
              .collect(Collectors.toCollection(ArrayList::new));
        outlined.addAll(markers.outlinedInstances());
        outlined.removeIf(instance -> instance == null || !boardCamera.camera.frustum.boundsInFrustum(unitBounds.get(instance)));
        float seeThrough = tuning.seeThrough();
        boolean wireframeView = tuning.wireframe() && GpuWireframe.supported();
        // The units' poses and anchors, the icons and the camera are final for this frame. The tuning model follows
        // the camera, which can leave Free Flight by itself (its keys, the Tactical View).
        renderStage("board-space HUD");
        tuning.syncCamera();
        updateHud(frame);
        renderStage("cutaways and light");
        atmosphere.updateLight(boardCamera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        terrain.animate(Gdx.graphics.getDeltaTime(), units, tuning.buildingOpacity(),
              ui.hit(Gdx.input.getX(), Gdx.input.getY()) ? null : hovered, hoverFloorZ());
        renderStage("geometry shadows");
        terrain.renderShadows(boardCamera.camera, unitIcons.active() ? List.of() : units);
        renderStage("cloud transmission");
        atmosphere.prepareClouds(terrain, scene, Gdx.graphics.getDeltaTime());
        renderStage("opaque terrain");
        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
        atmosphere.begin((int) boardCamera.camera.viewportWidth, (int) boardCamera.camera.viewportHeight,
              Gdx.graphics.getDeltaTime());
        if (wireframeView) {
            wireframe.fill(boardCamera.camera, terrain);
        } else {
            terrain.render(boardCamera.camera, false);
        }
        renderStage("units");
        renderUnits(wireframeView);
        renderStage("transparent effects");
        renderTethers();
        if (!wireframeView) { terrain.renderTransparent(boardCamera.camera); }
        Color smokeLight = atmosphere.particleLight();
        terrainEffects.update(scene, groundSurfaces, atmosphereSettings.effects(), animationSeconds());
        effectDepth.begin();
        // The Tactical View's tileset art already shows fire and smoke.
        if (!terrain.tacticalView()) {
            terrainEffects.render(boardCamera.camera, effectDepth, smokeLight, atmosphere.lighting().direction());
        }
        jumpJets.setSmokeLight(smokeLight);
        attackEffects.setSmokeLight(smokeLight);
        attackEffects.setLightDirection(atmosphere.lighting().direction());
        if (!unitIcons.active()) { jumpJets.render(boardCamera.camera); }
        attackEffects.render(boardCamera.camera, effectDepth);
        if (!terrain.waterVisible()) { waterImpacts.clear(); }
        else if (!unitIcons.active()) {
            waterImpacts.render(boardCamera.camera, scene, motions, unitInstances, smokeLight, playback.attachment());
        }
        renderStage("atmosphere composite");
        atmosphere.end(boardCamera.camera, terrain, scene, 0, fieldOfView, !wireframeView);
        if (wireframeView) { wireframe.lines(boardCamera.camera, terrain); }
        renderStage("weather particles");
        atmosphere.renderWeather(boardCamera.camera, scene);
        renderStage("unit outlines");
        unitVisibility.render(boardCamera.camera, outlined, atmosphere.depthTexture(), 0, seeThrough, layoutScale,
              unitBounds, terrain.tacticalView() ? null : terrainEffects.opacityTexture(), terrain);
        renderStage("tactical overlays");
        terrain.render(boardCamera.camera, true);
        // Movement annotations show through scenery; draw the destination ghost after the route and unit icons.
        overlay.renderMarks(boardCamera.camera, unitFootprints::get);
        overlay.render(boardCamera.camera);
        renderHoverRings();
        fireControl.render(boardCamera.camera, Gdx.graphics.getDeltaTime());
        tactical.render(boardCamera.camera, Gdx.graphics.getDeltaTime(), unitIcons.active());
        hexGrid.render(boardCamera, scene, terrain.tacticalView());
        renderHexText();
        unitIcons.render(boardCamera.camera);
        overlay.renderGhost(boardCamera.camera, shownUnit);
        fireControl.renderLabels(boardCamera.camera);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        tactical.renderLabels(boardCamera.camera);
        renderStage("annotations and UI");
        renderAnnotations();
        renderMarquee();
        renderEntrance();
        try (TerrainSettings.Scope ignored = TerrainSettings.use(null)) { ui.draw(); }
        renderStage(null);
        if (reloadingAssets) {
            reloadingAssets = false;
            tuning.assetReloadFinished(true);
        }
        frames++;
    }

    /**
     * Hands the HUD this frame: the board view's facts, the pending dialog and the preferences. The board overlay
     * then shows the frame with the scene the playback presents.
     */
    private void updateHud(GpuBoardSource.Frame frame) {
        GpuHud.HudView view = hudView(frame.scene() != null, frame.panels().fire());
        // The HUD shows the tuning controls' own values, not the settings of the terrain on display.
        try (TerrainSettings.Scope ignored = TerrainSettings.use(null)) {
            ui.update(frame, view, dialog(), source.uiPreferences());
        }
        if (ui.isModal()) {
            cameraKeys.clear();
            if (!boardInput.pressedKeys.isEmpty()) {
                boardInput.pressedKeys.clear();
                source.stopKeys();
            }
            if (boardInput.boardGesture) { boardInput.reset(); }
        }
        if (ui instanceof GpuHud hud && frame.scene() != null) {
            overlay.update(frame.withScene(scene), view, source.uiPreferences(), hud.state);
        }
    }

    /** The pending native dialog of a game's source; a map has none. */
    private GpuBoardWindow.DialogRequest dialog() {
        return source instanceof GpuBoardSource game ? game.dialog() : null;
    }

    /**
     * The hover rings the view draws itself every frame, inset by {@link #HOVER_HEX_INSET}:
     * the hovered hex's outline, or with Ctrl in the editor the brush's hexes, at the height a unit would stand there;
     * and a building floor under the pointer column: its outline half a hex above the floor's level,
     * joined by its corners to a faint outline on the hex. Hidden sections draw at half opacity.
     */
    private void renderHoverRings() {
        float top = hoverTop();
        if ((hovered == null || scene.tile(hovered) == null || ui.hit(Gdx.input.getX(), Gdx.input.getY()))
              && (!source.isEditor() || source.editorState() == null || source.editorState().selection().isEmpty())
              && ui.cliffEdgeAt(Gdx.input.getX(), Gdx.input.getY()) < 0) {
            return;
        }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        // Both passes compare against scene depth without letting the outline occlude itself or later overlays.
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        lines.setProjectionMatrix(boardCamera.camera.combined);
        try {
            Gdx.gl.glDepthFunc(GL20.GL_GREATER);
            drawHoverRings(top, HOVER_OCCLUDED_ALPHA);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            drawHoverRings(top, 1);
        } finally {
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    private void drawHoverRings(float top, float alpha) {
        lines.begin(ShapeRenderer.ShapeType.Line);
        if (source.isEditor() && source.editorState() != null) {
            var state = source.editorState();
            lines.setColor(.45f, 1, .8f, alpha);
            int selectionIndex = 0;
            for (var item : state.selection()) {
                Coords destination = state.movePreview().size() == state.selection().size()
                      ? state.movePreview().get(selectionIndex) : item.coords();
                selectionIndex++;
                if (scene.tile(item.coords()) == null) { continue; }
                if (item.object().isEmpty()) {
                    float z = BoardTacticalGeometry.floatingZ(scene, item.coords());
                    ring(item.coords(), z);
                    var cliff = state.property("cliff_top");
                    if (item.coords().equals(state.selected()) && cliff != null) {
                        for (int edge = 0; edge < 6; edge++) {
                            if ((cliff.exits() & (1 << BoardGeometry.edgeDirection(edge))) != 0) {
                                ringEdge(item.coords(), z, edge, HOVER_HEX_INSET + .05f);
                            }
                        }
                    }
                }
                else {
                    var object = frameObjects(item.coords()).stream()
                          .filter(o -> o.id().equals(item.object())).findFirst();
                    if (object.isPresent()) {
                        var bounds = object.get().bounds();
                        // The outline is the object's own box; only a held drag adds the ground projection of its drop.
                        lines.box(bounds.min.x, bounds.min.y, bounds.max.z, bounds.getWidth(), bounds.getHeight(), bounds.getDepth());
                        if (boardInput.dragged && boardInput.editorGrab != null) {
                            editorProjection(destination, object.get().anchorLevel() * BoardGeometry.level());
                        }
                    } else {
                        ring(destination, BoardTacticalGeometry.floatingZ(scene, destination));
                    }
                }
            }
            // The objects hovered in the side view (every one of a shared pill) or Layers.
            List<String> hoveredObjects = ui instanceof GpuMapHud map ? map.editorHoverObjects() : List.of();
            if (!hoveredObjects.isEmpty() && state.selected() != null) {
                lines.setColor(1, 1, 1, alpha);
                frameObjects(state.selected()).stream().filter(o -> hoveredObjects.contains(o.id())).forEach(object -> {
                    var bounds = object.bounds();
                    lines.box(bounds.min.x, bounds.min.y, bounds.max.z, bounds.getWidth(), bounds.getHeight(), bounds.getDepth());
                });
            }
            for (Coords coords : state.movePreview()) {
                boolean valid = scene.tile(coords) != null;
                lines.setColor(valid ? .45f : 1, valid ? 1 : .3f, valid ? .8f : .3f, alpha);
                if (valid && boardInput.editorHexGrab != null) { editorProjection(coords, boardInput.editorPlaneZ); }
                else { ring(coords, valid ? BoardTacticalGeometry.floatingZ(scene, coords) : BoardGeometry.level()); }
            }
        }
        lines.end();
        drawCliffEdgeHover(alpha);
        if (hovered == null || scene.tile(hovered) == null || ui.hit(Gdx.input.getX(), Gdx.input.getY())) { return; }
        lines.begin(ShapeRenderer.ShapeType.Line);
        lines.setColor(1, 1, 1, alpha);
        if (source.isEditor() && (modifiers() == InputEvent.CTRL_DOWN_MASK
              || source.editorState() != null && source.editorState().tool() != megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT)) {
            for (Coords coords : source.editorBrush(hovered, boardGeneration)) {
                if (scene.tile(coords) != null) {
                    ring(coords, BoardTacticalGeometry.floatingZ(scene, coords));
                }
            }
        } else {
            float base = BoardTacticalGeometry.floatingZ(scene, hovered);
            ring(hovered, Float.isNaN(top) ? base : top);
            if (!Float.isNaN(top)) {
                lines.setColor(1, 1, 1, alpha * .25f);
                ring(hovered, base);
                Vector3 center = BoardGeometry.center(hovered, 0);
                for (int edge = 0; edge < 6; edge++) {
                    Vector3 corner = BoardGeometry.inset(BoardGeometry.corner(hovered, 0, edge), center,
                          HOVER_HEX_INSET);
                    lines.line(corner.x, corner.y, base, corner.x, corner.y, top);
                }
            }
        }
        lines.end();
    }

    /** A hex's outline, inset by {@link #HOVER_HEX_INSET}, at height {@code z}. */
    private void ring(Coords coords, float z) {
        for (int edge = 0; edge < 6; edge++) {
            ringEdge(coords, z, edge, HOVER_HEX_INSET);
        }
    }

    private void ringEdge(Coords coords, float z, int edge, float inset) {
        Vector3 center = BoardGeometry.center(coords, 0);
        Vector3 from = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, inset);
        Vector3 to = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge + 1), center, inset);
        lines.line(from.x, from.y, z, to.x, to.y, z);
    }

    /** A filled ribbon keeps the hovered side visible without depending on native wide-line support. */
    private void drawCliffEdgeHover(float alpha) {
        int direction = ui.cliffEdgeAt(Gdx.input.getX(), Gdx.input.getY());
        var state = source.editorState();
        if (direction < 0 || state == null || state.selected() == null || scene.tile(state.selected()) == null) { return; }
        Coords coords = state.selected();
        int edge = Math.floorMod(1 - direction, 6);
        float z = BoardTacticalGeometry.floatingZ(scene, coords);
        Vector3 center = BoardGeometry.center(coords, 0);
        Vector3 a = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, HOVER_HEX_INSET);
        Vector3 b = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge + 1), center, HOVER_HEX_INSET);
        lines.translate(0, 0, z);
        lines.begin(ShapeRenderer.ShapeType.Filled);
        lines.setColor(1, .85f, .35f, alpha);
        lines.rectLine(a.x, a.y, b.x, b.y, BoardGeometry.hexScale());
        lines.end();
        lines.identity();
    }

    /**
     * The board view's facts for the HUD: the view mode, the hovered hex and unit, a hex's width on the screen and,
     * while a board is drawn, each drawn unit's head (its label anchor) and screen rectangle in stage units, y up, and
     * its animated board position. The rectangle runs from the unit's ground up to its head, .55 of a hex wide, in 3D
     * and is the icon's square in the Tactical View; a unit whose head lies behind the camera has neither. A unit
     * drawn in parts is placed by its centre part. A fire target that is no unit is anchored on its hex's centre, on
     * the plane of the hex annotations.
     */
    private GpuHud.HudView hudView(boolean drawn, GpuFireOrders.Snapshot fire) {
        Map<Integer, Rectangle> rects = new HashMap<>();
        Map<Integer, Vector2> heads = new HashMap<>();
        Map<Integer, Vector2> positions = new HashMap<>();
        float hexPixels = UnitScreenScale.hexPixels(boardCamera.camera.zoom, layoutScale);
        float width = hexPixels * (unitIcons.active()
              ? GpuUnitIcons.SIZE_IN_HEXES * BoardGeometry.HEIGHT / BoardGeometry.WIDTH : UNIT_RECT_WIDTH);
        // Stage units per viewport pixel: the HUD's stage spans the board camera's viewport.
        float stage = ui.stage().getWidth() / boardCamera.camera.viewportWidth;
        for (Map.Entry<BoardScene.Unit, Vector3> entry : unitAnchors.entrySet()) {
            UnitFootprint.Pose pose = unitFootprints.get(entry.getKey());
            if (!drawn || entry.getKey().part() > 0 || pose == null) {
                continue;
            }
            int id = entry.getKey().id();
            positions.put(id, new Vector2(pose.position().x, pose.position().y));
            Vector2 head = GpuNameplates.project(boardCamera.camera, entry.getValue());
            Vector2 ground = GpuNameplates.project(boardCamera.camera, pose.position());
            if (head == null || ground == null) {
                continue;
            }
            heads.put(id, head.scl(stage));
            ground.scl(stage);
            rects.put(id, unitIcons.active() ? new Rectangle(ground.x - width / 2, ground.y - width / 2, width, width)
                  : new Rectangle(Math.min(ground.x, head.x) - width / 2, Math.min(ground.y, head.y),
                        Math.abs(head.x - ground.x) + width, Math.abs(head.y - ground.y)));
        }
        Map<TargetKey, Vector2> targets = new HashMap<>();
        fire.hexes().forEach((target, hex) -> {
            Vector2 head = drawn && scene != null && scene.tile(hex) != null ? GpuNameplates.project(boardCamera.camera,
                  new Vector3(BoardGeometry.centerX(hex), BoardGeometry.centerY(hex),
                        BoardTacticalGeometry.floatingZ(scene, hex))) : null;
            if (head != null) {
                targets.put(target, head.scl(stage));
            }
        });
        return new GpuHud.HudView(boardCamera.tactical(), playbackBusy(), rects, heads, positions, hovered, hoveredUnit,
              hexPixels, targets);
    }

    /**
     * Whether live events are still to be presented, queued in the playback or held back while a review runs: the
     * HUD then presents the units of the last idle capture (C.6), and the client knows that the board animates.
     */
    private boolean playbackBusy() {
        return history.running();
    }

    /** Benchmark boundary only. Normal gameplay does not allocate queries, read clocks or wait for the GPU. */
    void renderStage(String stage) { }

    /** The editor's selection box, drawn in window pixels like {@link #renderEntrance}; coral while Ctrl removes. */
    private void renderMarquee() {
        if (!boardInput.marquee || !boardInput.dragged) { return; }
        Color tone = (boardInput.gestureModifiers & InputEvent.CTRL_DOWN_MASK) != 0 ? UiTheme.CORAL : UiTheme.MINT;
        float height = Gdx.graphics.getHeight();
        float x = Math.min(boardInput.startX, boardInput.dragX), y = height - Math.max(boardInput.startY, boardInput.dragY);
        float width = Math.abs(boardInput.dragX - boardInput.startX), boxHeight = Math.abs(boardInput.dragY - boardInput.startY);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        lines.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
              boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
        lines.begin(ShapeRenderer.ShapeType.Filled);
        lines.setColor(tone.r, tone.g, tone.b, .12f);
        lines.rect(x, y, width, boxHeight);
        lines.end();
        lines.begin(ShapeRenderer.ShapeType.Line);
        lines.setColor(tone.r, tone.g, tone.b, .9f);
        lines.rect(x, y, width, boxHeight);
        lines.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderEntrance() {
        float opacity = entrancePending ? 0 : boardCamera.entranceOpacity();
        if (opacity >= 1) { return; }
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        lines.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
              boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
        lines.begin(ShapeRenderer.ShapeType.Filled);
        lines.setColor(0.035f, 0.055f, 0.075f, 1 - opacity);
        lines.rect(0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
        lines.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void updateJumpJets() {
        jumpJets.beginFrame();
        if (unitModels != null) {
            for (var unit : scene.units()) {
                var motion = motions.get(unit.id());
                if (unit.sensorContact() || motion == null) {
                    continue;
                }
                var sample = motion.sample();
                if (sample.jets() == null && sample.group() == null) {
                    continue;
                }
                String key = unit.id() + ":" + unit.part();
                var instance = unitInstances.get(key);
                var model = instance instanceof GpuUnitInstance displayed ? displayed.visual() : null;
                if (instance != null && model != null) {
                    jumpJets.update(key, model, instance, unit, sample);
                }
            }
        }
        jumpJets.endFrame();
    }

    private void prepareUnits() {
        hover.clear();
        unitAnchors.clear();
        unitFootprints.clear();
        // Zoomed out, the 3D view's units grow to stay visible; the Tactical View's icons keep their size in the hex.
        float growth = boardCamera.tactical() ? 1
              : UnitScreenScale.factor(UnitScreenScale.hexPixels(boardCamera.camera.zoom, layoutScale));
        unitInstances.keySet().retainAll(scene.units().stream().map(unit -> unit.id() + ":" + unit.part())
              .collect(Collectors.toSet()));
        unitTints.keySet().retainAll(unitInstances.keySet());
        upperBodyTurns.keySet().retainAll(unitInstances.keySet());
        armFlips.keySet().retainAll(unitInstances.keySet());
        animators.keySet().retainAll(unitInstances.keySet());
        unitDamage.keySet().retainAll(unitInstances.keySet());
        equipmentAppearance.keySet().retainAll(unitInstances.keySet());
        for (BoardScene.Unit unit : scene.units()) {
            Vector3 position = BoardGeometry.center(unit.location().coords(), unit.location().elevation());
            playback.placeDisplacement(unit, position);
            float facing = unit.location().facing() * 60;
            UnitMotion motion = unit.sensorContact() ? null : motions.get(unit.id());
            UnitMotion.Sample sample = motion == null ? UnitMotion.Sample.STILL : motion.sample();
            BoardScene.Unit placement = sample.placement(unit);
            boolean airborne = sample.airborne(unit);
            if (motion != null && motion.isMoving()) {
                position.sub(motion.destination()).add(airborne ? motion.position() : motion.surfacePosition(scene));
                if (placement.footprint().size() > 1) {
                    // The path already carries its own absolute elevation; destination support is not a constant lift.
                    position.set(airborne ? motion.position() : motion.surfacePosition(scene));
                }
                facing = motion.facing();
                UnitFootprint.clearTerrain(scene, placement, position, facing, airborne);
            }
            BoardScene.Tile tile = scene.tile(unit.location().coords());
            if (tile != null && tile.waterDepth() == 0 && !tile.frozen() && !airborne
                  && MathUtils.isEqual(position.z, tile.elevation() * BoardGeometry.level())) {
                position.z = BoardGeometry.groundZ(tile);
            }
            float footprintFacing = facing;
            GpuUnitModel visual = unit.sensorContact() ? markers.model(BoardMarker.Kind.SENSOR_CONTACT)
                  : unitModels == null ? null : unitModels.get(unit.model(), unit.id());
            boolean authored = !unit.sensorContact() && visual != null;
            boolean dead = !unit.sensorContact() && unit.model() != null && unit.model().state() != null
                  && unit.model().state().pose().dead();
            var death = playback.attacks().stream().filter(attack -> attack.death() && attack.event.entityId() == unit.id())
                  .findFirst().orElse(null);
            float collapse = death == null ? dead ? 1 : 0 : death.deathProgress();
            if (airborne && collapse > 0) {
                float ground = UnitLandingSupports.ground(scene, position.x, position.y, groundSurfaces);
                if (!Float.isFinite(ground) && tile != null) { ground = BoardGeometry.surfaceZ(tile); }
                if (Float.isFinite(ground)) { position.z = MathUtils.lerp(position.z, ground, collapse); }
            }
            if (visual == null) {
                visual = spriteModels.computeIfAbsent(new UnitSprite(unit.image(), tuning.unitDisplayMode().meeple(unit.model())),
                      sprite -> sprite.meeple() ? GpuUnitModel.meeple(sprite.pixels(), unitTextures.region(sprite.pixels()))
                            : GpuUnitModel.sprite(sprite.pixels(), unitTextures.region(sprite.pixels())));
            }
            String key = unit.id() + ":" + unit.part();
            ModelInstance instance = unitInstances.get(key);
            if (instance == null || instance.model != visual.instance.model) {
                animators.remove(key);
                instance = newUnitInstance(key, visual);
            }
            BoardScene.LocationDamage shownDamage = unitDamage.getOrDefault(key, BoardScene.LocationDamage.NONE);
            boolean mek = authored && (unit.model().state() == null ? visual.turnsUpperBody()
                  : unit.model().state().structure().anatomy() != null);
            BoardScene.LocationDamage damage = authored ? visual.infantry() ? unit.model().damage()
                  : UnitDamageDisplay.preview(unit.model().damage(), mek, tuning.damageOverride(),
                        visual.damageLocation(tuning.damageLocation()))
                  : BoardScene.LocationDamage.NONE;
            UnitModelState.Appearance appearance = !unit.sensorContact() && unit.model() != null && unit.model().state() != null
                  ? unit.model().state().appearance() : null;
            if (visual.meeple()) {
                // Fresh materials make repair/camouflage changes reversible; mesh buffers remain shared.
                var bodyDamage = tuning.damageOverride() >= 0
                      ? UnitDamageDisplay.preview(BoardScene.LocationDamage.NONE, false, tuning.damageOverride())
                      : UnitDamageDisplay.body(appearance == null ? null : appearance.bodyStage());
                if (!bodyDamage.equals(shownDamage) || !java.util.Objects.equals(appearance, equipmentAppearance.get(key))) {
                    instance = newUnitInstance(key, visual);
                    MeepleVisual.appearance(instance, visual, appearance, tuning.damageOverride(), unit.id(), camouflage, damageDisplay);
                    unitDamage.put(key, bodyDamage);
                    equipmentAppearance.put(key, appearance);
                }
            }
            if (authored && (!damage.equals(shownDamage)
                  || !java.util.Objects.equals(appearance, equipmentAppearance.get(key)))) {
                UpperBodyTurn previousTurn = upperBodyTurns.get(key);
                instance = showDamage(key, visual, unit, damage);
                if (appearance != null) {
                    visual.showEquipment(instance, appearance);
                    camouflage.apply(instance, visual.instance, appearance);
                    equipmentAppearance.put(key, appearance);
                }
                damageDisplay.applyTexture(instance, damage, unit.id());
                if (previousTurn != null) {
                    upperBodyTurns.put(key, previousTurn);
                    visual.turnUpperBody(instance, previousTurn.degrees());
                }
            }
            if (dead && authored) { damageDisplay.wreck(instance, visual, unit.id()); }
            // Presentation-only color for the see-through pass; the normal model materials retain their artwork.
            if (!(instance.userData instanceof Color)) {
                instance.userData = new Color();
            }
            Color.rgb888ToColor((Color) instance.userData, unit.outlineRgb());
            // The hex the unit stands in right now, also while it walks through other hexes.
            BoardScene.Tile standing = BoardGeometry.tile(scene, position.x, position.y);
            ((Color) instance.userData).a = standing == null ? 0 : GpuUnitVisibility.ownHex(standing);
            if (authored && appearance == null && !unit.image().equals(unitTints.get(key))) {
                Color tint = GpuCutout.averageColor(unit.image());
                float brightest = Math.max(tint.r, Math.max(tint.g, tint.b));
                if (brightest > 0.01f) {
                    tint.mul(1 / brightest);
                    tint.a = 1;
                }
                for (var material : instance.materials) {
                    if ("paint".equals(material.id)) {
                        material.set(ColorAttribute.createDiffuse(tint));
                    }
                }
                unitTints.put(key, unit.image());
            }
            if (authored && visual.turnsUpperBody()) {
                // A movement replay already follows the legs, so only a unit standing still shows its twist.
                boolean isMoving = (motion != null) && motion.isMoving();
                facing -= turnUpperBody(visual, instance, key, unit, isMoving ? 0 : unit.model().twist());
            }
            if (authored && unit.model().state() != null && !visual.rigs().isEmpty()) {
                if (visual.rigs().stream().allMatch(rig -> rig.trooper() || "infantry-transport".equals(rig.family()))) {
                    facing = 0; // Troops and their transports have cosmetic member headings, no gameplay facing.
                }
                var turn = upperBodyTurns.get(key);
                animators.computeIfAbsent(key, ignored -> new UnitAnimator(groundSurfaces, () -> scene)).apply(visual, instance, unit,
                      sample, hoverClock, animationSeconds(),
                      history.speed() == UnitMotion.Speed.INSTANT, turn == null ? 0 : turn.degrees(),
                      UnitScreenScale.growth(placement, growth));
                animators.get(key).attacks(visual, unit, playback.attacks());
                animators.get(key).conversion(playback.conversion(), unit);
                if (visual.infantry()) { animators.get(key).previewCasualties(tuning.damageOverride()); }
            }
            // After the animator, never before it: the animator resets every joint to its rest pose
            // each frame and then poses leftArm and rightArm itself, which are the same nodes a flip
            // turns. Setting the flip first meant it was overwritten before anything was drawn.
            if (authored && visual.flipsArms()) {
                flipArms(visual, instance, key, unit);
            }
            Vector3 anchor = unit.sensorContact()
                  ? markers.placeSensor(unit.location().coords(), instance, boardCamera.camera, position)
                  : visual.meeple() ? MeepleAnimator.place(visual, instance, placement, sample, boardCamera.camera, position, facing,
                        playback.conversion(), boardCamera.isTopDown())
                  : visual.place(instance, boardCamera.camera, position, facing, placement);
            anchor = UnitScreenScale.grow(placement, instance, anchor, growth);
            UnitAnimator animator = animators.get(key);
            if (authored && animator != null && unit.attachment() == null && animator.groundSupports(scene, placement, sample)) {
                anchor = visual.anchor(instance, boardCamera.camera);
            }
            if (airborne && collapse == 0) {
                float offset = hoverOffset(hoverClock, unit.id(), unit.part());
                hover.add(new Hover(instance, offset));
                anchor.add(0, 0, offset);
                position.z += offset;
            }
            unitFootprints.put(unit, new UnitFootprint.Pose(placement, position, footprintFacing));
            unitAnchors.put(unit, anchor);
        }
    }

    /** Frame an action before its clock advances, including when one large frame reaches several queued actions. */
    boolean preparePlaybackCamera(UnitPlayback state, BoardScene scene) {
        if (state.movement() != null) {
            boardCamera.frameMovement(state.movement(), motions.get(state.activeEntityId()), state.present(scene), playbackCameraWidth());
        } else if (state.attack() != null && state.attack().shot()) {
            boardCamera.frameAttacks(state.attacks(), playbackCameraWidth());
        } else {
            return true;
        }
        return !boardCamera.isFraming();
    }

    private float cameraWidth() { return boardCamera.camera.viewportWidth; }

    /** Only replay framing consumes HUD clearance; all ordinary navigation uses the full viewport. */
    private float playbackCameraWidth() {
        if (boardCamera.firstPerson()) { return cameraWidth(); }
        float left = ui == null ? 0 : ui.framingLeft();
        float width = ui == null ? cameraWidth() : ui.framingWidth();
        boardCamera.viewableArea(left, width);
        return width;
    }

    private void fitBoard() {
        if (ui == null) { boardCamera.fit(scene); }
        else { boardCamera.fit(scene, ui.framingLeft(), ui.framingWidth(), ui.framingBottom(), ui.framingTop()); }
    }

    /** Frame the presented action; selection changes during playback take effect after its final hold. */
    void updateCameraFocus(BoardScene scene, BoardFocus request) {
        updateCameraFocus(scene, request, null);
    }

    void updateCameraFocus(BoardScene scene, BoardFocus request, BoardScene.Animation instantAction) {
        if (!fitted) {
            boardCamera.viewableArea(0, cameraWidth());
            boardCamera.fit(scene);
            fitted = true;
            cameraSelection = scene.selectedId();
            cameraFollowingPlayback = false;
            // Start with the whole map, ignoring any earlier classic-board centering. Later board switches
            // can follow a unit-list click, whose pending focus request must still be applied.
            centerSequence = centerSequence < 0 ? request.sequence() : 0;
        }
        if (boardCamera.firstPerson()) {
            // Consume automatic requests during free flight so leaving it restores the user's tactical view.
            centerSequence = request.sequence();
            cameraSelection = scene.selectedId();
            cameraFollowingPlayback = false;
            return;
        }
        boolean firing = playback.attack() != null && playback.attack().shot();
        boolean instant = history.speed() == UnitMotion.Speed.INSTANT;
        if (!instant && !firing && playback.movement() == null) { boardCamera.clearPlaybackFrame(); }
        // Keep the last idle selection until the entire playback, including its completion hold, ends; a reviewed
        // attack is framed as a live one.
        if (playback.busy() || playback.reviewing()) {
            if (playback.movement() != null) {
                // The complete route is already framed. Do not chase the unit or recenter it on arrival.
                cameraFollowingPlayback = false;
                // The classic board auto-centers each move's start; do not replay that request after arrival.
                if (request.entityId() == Entity.NONE
                      && playback.movement().path().getFirst().coords().equals(request.coords())) {
                    centerSequence = request.sequence();
                }
                boardCamera.frameMovement(playback.movement(), motions.get(playback.activeEntityId()), scene, playbackCameraWidth());
                return;
            }
            cameraFollowingPlayback = true;
            if (firing) {
                boardCamera.frameAttacks(playback.attacks(), playbackCameraWidth());
                return;
            }
            var active = scene.units().stream().filter(unit -> unit.id() == playback.activeEntityId())
                  .findFirst().orElse(null);
            if (active != null) {
                playbackCameraWidth();
                var motion = motions.get(active.id());
                Vector3 position = motion != null && motion.isMoving() ? motion.surfacePosition(scene).cpy()
                      : BoardGeometry.center(active.location().coords(), active.location().elevation());
                playback.placeDisplacement(active, position);
                if (!boardCamera.focus.epsilonEquals(position, .001f)) {
                    boardCamera.center(position);
                }
            }
            return;
        }
        boolean requested = centerSequence != request.sequence();
        boolean changedSelection = cameraSelection != scene.selectedId()
              || cameraFollowingPlayback && instantAction == null;
        var selection = changedSelection && scene.selectedId() != Entity.NONE
              ? scene.units().stream().filter(unit -> unit.id() == scene.selectedId()).findFirst().orElse(null) : null;
        if (selection == null && requested && request.entityId() != Entity.NONE) {
            selection = scene.units().stream().filter(unit -> unit.id() == request.entityId()).findFirst().orElse(null);
        }
        Coords center = selection == null && requested && request.entityId() == Entity.NONE ? request.coords() : null;
        centerSequence = request.sequence();
        cameraSelection = scene.selectedId();
        cameraFollowingPlayback = false;
        if (selection != null) {
            boardCamera.viewableArea(0, cameraWidth());
            boardCamera.frameSelection(selection, cameraWidth());
        } else if (instantAction instanceof BoardScene.Combat combat && combat.result().kind() == ResolvedAttack.Kind.SHOT) {
            boardCamera.frameAttacks(List.of(new UnitAttack(combat)), playbackCameraWidth());
        } else if (instantAction != null) {
            scene.units().stream().filter(unit -> unit.id() == instantAction.entityId()).findFirst()
                  .ifPresent(unit -> boardCamera.frameSelection(unit, playbackCameraWidth()));
        } else if (center != null && scene.tile(center) != null) {
            boardCamera.viewableArea(0, cameraWidth());
            boardCamera.frameLocation(BoardGeometry.center(center, scene.tile(center).elevation()), cameraWidth());
        } else {
            // Rebase the orbit pivot without moving the displayed board when replay control ends.
            boardCamera.viewableArea(0, cameraWidth());
        }
        if (instant) {
            // Replace any old transition with the final requested view before snapping it, never visiting each event.
            if (boardCamera.isFraming()) { boardCamera.advance(BoardCamera.CAMERA_FRAMING_SECONDS); }
            boardCamera.clearPlaybackFrame();
        }
    }

    private void aimAttack() {
        if (unitModels == null) { return; }
        Set<Integer> aimed = new java.util.HashSet<>();
        for (var attack : playback.attacks()) {
            if (!attack.shot() && attack.aimWeight() <= 0) { continue; }
            var unit = attack.event.attacker();
            if (!aimed.add(unit.id())) { continue; }
            var key = unit.id() + ":" + unit.part();
            var animator = animators.get(key);
            if (animator == null || !unitInstances.containsKey(key) || unit.model() == null) { continue; }
            var model = unitModels.loaded(unit.model(), unit.id());
            if (model == null) { continue; }
            var target = attack.event.target();
            var targetInstance = target == null ? null : unitInstances.get(target.id() + ":" + target.part());
            var origin = UnitAttack.center(unitInstances.get(key), unit.location(), new Vector3());
            var endpoint = attack.shot() ? attack.endpoint(targetInstance, origin, new Vector3())
                  : attack.contact(targetInstance, origin, unitPicking, new Vector3());
            if (attack.shot()) {
                animator.aimShots(model, unit, playback.attacks(), shot -> shot.event.target() == null ? null
                      : unitInstances.get(shot.event.target().id() + ":" + shot.event.target().part()));
            } else { animator.aim(model, unit, attack, endpoint, targetInstance); }
            for (var displayed : scene.units()) {
                if (displayed.id() == unit.id()) {
                    unitAnchors.put(displayed, model.anchor(unitInstances.get(key), boardCamera.camera));
                    break;
                }
            }
        }
    }

    /** One floating visual's drift for the current frame: a draw-time offset, never part of the game state. */
    private record Hover(ModelInstance instance, float offset) { }

    /** Apply the same hover transform before drawing, feature fading, and shadow capture. */
    private void applyHover() {
        for (Hover drifting : hover) {
            Vector3 center = drifting.instance().transform.getTranslation(new Vector3());
            drifting.instance().transform.setTranslation(center.add(0, 0, drifting.offset()));
        }
    }

    /**
     * Vertical hover offset in world units for an airborne visual at the current animation time: a slow sine wave that
     * each unit starts at a different phase, so the floating tokens drift as a loose wave instead of rising and
     * falling together. A grounded unit (elevation or altitude 0) never gets an offset. This only moves the rendered
     * token; no game state changes.
     */
    static float hoverOffset(float seconds, int id, int part) {
        return UnitAnimator.hoverOffset(seconds, id, part);
    }

    /** The animation clock's step: none while the playback is paused, unless a review plays its attack meanwhile. */
    private float animationSeconds() {
        return playback.paused() && !playback.reviewing() ? 0
              : (float) (Gdx.graphics.getDeltaTime() * history.speed().rate);
    }

    private boolean hasInfantryTransports(BoardScene.Unit unit) {
        if (unitModels == null || unit == null || unit.sensorContact()) {
            return false;
        }
        var model = unitModels.get(unit.model(), unit.id());
        return model != null && model.rigs().stream().anyMatch(UnitRig::transport);
    }

    /** Evaluate the final captured formation even when several queued moves are skipped in one frame. */
    private void completeMovement(BoardScene.Movement movement) {
        var unit = movement.unit();
        if (unitModels == null || unit == null || unit.sensorContact() || unit.model() == null || unit.model().state() == null) {
            return;
        }
        var model = unitModels.get(unit.model(), unit.id());
        if (model == null || model.rigs().isEmpty()) {
            return;
        }
        String key = unit.id() + ":" + unit.part();
        var instance = unitInstances.get(key);
        if (instance == null || instance.model != model.instance.model) {
            instance = newUnitInstance(key, model);
        }
        animators.computeIfAbsent(key, ignored -> new UnitAnimator(groundSurfaces, () -> scene)).apply(model, instance, unit,
              motions.get(unit.id()).sample(), hoverClock, 0, true, unit.model().twist());
    }

    /** Level a floating visual's stem ends at, or NaN when no stem is drawn for this token at this time. */
    static float tetherGround(BoardScene.Unit unit, int tileElevation, Vector3 center, boolean moving) {
        float ground = tileElevation * BoardGeometry.level();
        return unit.airborne() && !moving && center.z > ground ? ground : Float.NaN;
    }

    /**
     * Faint stems from each floating visual's center down to the center of the hex it occupies, so an airborne token
     * beside a hill or another raised tile still reads as belonging to the hex under it. A grounded token already
     * covers its hex, and a moving token is between hexes, so neither gets a stem. Elevated LOS endpoints use the
     * same stems in every native board mode.
     */
    private void renderTethers() {
        Vector3 center = new Vector3();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        lines.setProjectionMatrix(boardCamera.camera.combined);
        lines.begin(ShapeRenderer.ShapeType.Line);
        lines.setColor(TETHER_COLOR);
        if (!unitIcons.active()) {
            for (BoardScene.Unit unit : scene.units()) {
                BoardScene.Tile tile = scene.tile(unit.location().coords());
                ModelInstance instance = unitInstances.get(unit.id() + ":" + unit.part());
                UnitMotion motion = motions.get(unit.id());
                if (tile == null || instance == null) {
                    continue;
                }
                instance.transform.getTranslation(center);
                renderTether(center, tetherGround(unit, tile.elevation(), center, motion != null && motion.isMoving()));
            }
        }
        var ruler = scene.tactical().ruler();
        if (ruler != null) {
            renderRulerTether(ruler.start(), ruler.startHeight());
            if (ruler.end() != null) { renderRulerTether(ruler.end(), ruler.endHeight()); }
        }
        if (source.isEditor() && source.editorState() != null && terrain != null) { renderObjectTethers(source.editorState()); }
        lines.end();
        Gdx.gl.glDepthMask(true);
    }

    /**
     * The same stems for the editor's selected objects that do not rest on their support, during drags too: from the
     * drawn anchor down by a positive offset to the support (the ground where the support is missing), or from a fixed
     * level down to the owning hex's level.
     */
    private void renderObjectTethers(megamek.client.ui.boardeditor.BoardEditorSession.Snapshot state) {
        for (var item : state.selection()) {
            if (item.object().isEmpty() || scene.tile(item.coords()) == null) { continue; }
            var drawn = frameObjects(item.coords()).stream()
                  .filter(value -> value.id().equals(item.object())).findFirst().orElse(null);
            if (drawn == null) { continue; }
            var placement = drawn.placement();
            renderTether(drawn.anchor(), placement.mode().equals("absolute") ? BoardTacticalGeometry.floatingZ(scene, item.coords())
                  : drawn.anchor().z - (float) (placement.offset() * BoardGeometry.level()));
        }
    }

    /**
     * The installed objects of a hex whose selection outlines, hover boxes or tethers this frame draws: each hex is
     * listed once per frame for both outline passes and the tethers.
     */
    private List<GpuTerrain.EditorObject> frameObjects(Coords coords) {
        if (frameObjectsFrame != Gdx.graphics.getFrameId()) {
            frameObjects.clear();
            frameObjectsFrame = Gdx.graphics.getFrameId();
        }
        return frameObjects.computeIfAbsent(coords, terrain::editorObjects);
    }

    /** Keep the elevated anchor and its drop footprint visible as one column throughout the drag. */
    private void editorProjection(Coords coords, float top) {
        if (scene.tile(coords) == null) { return; }
        float ground = BoardTacticalGeometry.floatingZ(scene, coords);
        ring(coords, ground);
        if (top <= ground + BoardGeometry.hexScale()) { return; }
        ring(coords, top);
        Vector3 center = BoardGeometry.center(coords, 0);
        for (int edge = 0; edge < 6; edge++) {
            Vector3 point = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, HOVER_HEX_INSET);
            lines.line(point.x, point.y, ground, point.x, point.y, top);
        }
    }

    private void renderRulerTether(Coords coords, int height) {
        BoardScene.Tile tile = scene.tile(coords);
        if (tile != null) { renderTether(BoardGeometry.center(coords, height), tile.elevation() * BoardGeometry.level()); }
    }

    private void renderTether(Vector3 center, float ground) {
        if (center.z > ground) { lines.line(center.x, center.y, center.z, center.x, center.y, ground); }
    }

    /** One render-only selection shared by color, depth, outlines and shadows. */
    void updateEquipmentDetail() {
        Set<Integer> detailedUnits = new java.util.HashSet<>();
        detailedUnits.add(scene.selectedId());
        for (var attack : playback.attacks()) {
            detailedUnits.add(attack.event.entityId());
            if (attack.event.target() != null) { detailedUnits.add(attack.event.target().id()); }
        }
        for (var unit : scene.units()) {
            if (unitInstances.get(unit.id() + ":" + unit.part()) instanceof GpuUnitInstance instance) {
                instance.equipmentDetail(boardCamera.camera, detailedUnits.contains(unit.id()));
            }
        }
    }

    /** A fresh instance shows no tint, twist or damage yet, so everything remembered about the old one is dropped. */
    private ModelInstance newUnitInstance(String key, GpuUnitModel visual) {
        ModelInstance instance = new GpuUnitInstance(visual);
        unitInstances.put(key, instance);
        unitTints.remove(key);
        upperBodyTurns.remove(key);
        armFlips.remove(key);
        unitDamage.remove(key);
        equipmentAppearance.remove(key);
        return instance;
    }

    /**
     * Takes lost arms off the unit's model and burns out its other lost locations. Starts from a fresh instance
     * instead of undoing the old damage, which also covers a location that a game master has repaired.
     */
    private ModelInstance showDamage(String key, GpuUnitModel visual, BoardScene.Unit unit, BoardScene.LocationDamage damage) {
        ModelInstance instance = newUnitInstance(key, visual);
        List<String> missing = UnitDamageDisplay.show(instance, damage);
        unitDamage.put(key, damage);
        LOGGER.debug("[GpuDamage] {}: taken off {}, burnt out {}, no part in the model for {}",
              unit.name(), damage.removed(), damage.wrecked(), missing);
        return instance;
    }

    /**
     * Shows a torso twist on a model whose upper body turns on its own. The scene gives the unit's torso facing, as
     * the classic sprite does, so the legs are that facing less the twist.
     *
     * @param twist hexsides the torso is turned clockwise from the legs
     *
     * @return the degrees to take off the displayed facing to get the facing of the legs
     */
    /**
     * Swings one unit's arms toward the pose its firing arc calls for, a step at a time, and poses the model again
     * when the shown angle moves. The game state holds the arc; this only shows it.
     */
    private void flipArms(GpuUnitModel visual, ModelInstance instance, String key, BoardScene.Unit unit) {
        var state = unit.model().state();
        boolean wanted = (state != null) && (state.pose() != null) && state.pose().armsFlipped();
        ArmFlip flip = armFlips.computeIfAbsent(key, ignored -> new ArmFlip());
        boolean previous = flip.flipped();
        if (flip.advance(wanted, history.speed() == UnitMotion.Speed.INSTANT ? Float.MAX_VALUE : animationSeconds())) {
            visual.flipArms(instance, flip.degrees());
        }
        if (previous != wanted) {
            LOGGER.debug("[GpuArmFlip] {}: arms now {}", unit.name(), wanted ? "flipped to the rear" : "forward");
        }
    }

    private float turnUpperBody(GpuUnitModel visual, ModelInstance instance, String key, BoardScene.Unit unit, int twist) {
        UpperBodyTurn turn = upperBodyTurns.get(key);
        if (turn == null) {
            turn = new UpperBodyTurn();
            upperBodyTurns.put(key, turn);
        }
        int previousTwist = turn.hexsides();
        if (turn.advance(twist, history.speed() == UnitMotion.Speed.INSTANT ? Float.MAX_VALUE : animationSeconds())) {
            visual.turnUpperBody(instance, turn.degrees());
        }
        if (previousTwist != twist) {
            LOGGER.debug("[GpuTwist] {}: upper body now {} hexside(s) clockwise of the legs, was {}",
                  unit.name(), twist, previousTwist);
        }
        return turn.targetDegrees();
    }

    private void renderUnits(boolean thermal) {
        unitBatch.begin(boardCamera.camera);
        if (!unitIcons.active()) {
            for (BoardScene.Unit unit : unitAnchors.keySet()) {
                ModelInstance instance = unitInstances.get(unit.id() + ":" + unit.part());
                if (boardCamera.camera.frustum.boundsInFrustum(unitBounds.get(instance))) {
                    if (unit.sensorContact()) {
                        unitBatch.render(instance);
                    } else if (thermal) {
                        wireframe.units.add(instance, unit, unitBounds.get(instance));
                    } else {
                        unitBatch.render(instance, terrain.environment());
                    }
                }
            }
        }
        // Every marker writes normal scene depth, independently of its optional see-through outline.
        markers.instances().forEach(unitBatch::render);
        unitBatch.end();
        if (thermal) { wireframe.units.render(boardCamera.camera); }
    }

    /**
     * The board markers' labels in screen space, clear of the markers. The units' labels are the HUD's nameplates;
     * MegaMek's unit annotations are not drawn here (I3 X3).
     */
    private void renderAnnotations() {
        annotationBatch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
              boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
        annotationBatch.begin();
        markers.renderLabels(annotationBatch, boardCamera.camera, layoutScale,
              markers.labelObstacles(boardCamera.camera));
        annotationBatch.end();
    }

    static Rectangle spreadAnnotation(Rectangle preferred, List<Rectangle> occupied,
          float viewportWidth, float viewportHeight, float gap) {
        float maxX = Math.max(0, viewportWidth - preferred.width);
        float maxY = Math.max(0, viewportHeight - preferred.height);
        Rectangle origin = new Rectangle(MathUtils.clamp(preferred.x, 0, maxX),
              MathUtils.clamp(preferred.y, 0, maxY), preferred.width, preferred.height);
        Rectangle result = new Rectangle(origin);
        float nearest = Float.POSITIVE_INFINITY;
        List<Float> columns = new ArrayList<>(List.of(origin.x, 0f, maxX));
        for (Rectangle bounds : occupied) {
            columns.add(MathUtils.clamp(bounds.x - gap - origin.width, 0, maxX));
            columns.add(MathUtils.clamp(bounds.x + bounds.width + gap, 0, maxX));
        }
        var sorted = occupied.stream().sorted(Comparator.comparingDouble(bounds -> bounds.y)).toList();
        for (float left : columns) {
            float bottom = 0;
            for (int index = 0; index <= sorted.size(); index++) {
                Rectangle obstacle = index < sorted.size() ? sorted.get(index) : null;
                if (obstacle != null && (left + origin.width + gap <= obstacle.x
                      || left >= obstacle.x + obstacle.width + gap)) {
                    continue;
                }
                float top = obstacle == null ? maxY : Math.min(maxY, obstacle.y - gap - origin.height);
                if (bottom <= top) {
                    float candidateY = MathUtils.clamp(origin.y, bottom, top);
                    float distance = (left - origin.x) * (left - origin.x)
                          + (candidateY - origin.y) * (candidateY - origin.y);
                    if (distance < nearest) {
                        nearest = distance;
                        result.setPosition(left, candidateY);
                    }
                }
                if (obstacle != null) {
                    bottom = Math.max(bottom, obstacle.y + obstacle.height + gap);
                }
            }
        }
        return result;
    }

    private void renderHexText() {
        hexText.update(scene, theme.skin.getFont("default-font"), terrain::roofBounds);
        hexText.render(annotationBatch, boardCamera, atmosphere.depthTexture(), unitVisibility.depthTexture(), 0);
    }

    /** The camera zoom of {@code notches} of the mouse wheel, on the board and on the minimap. */
    static float wheelZoom(float notches) {
        return (float) Math.pow(1.12, notches);
    }

    /** The level of the walkable surface under the pointer ({@link GpuTerrain#walkableHit}), NaN without one. */
    private float hoverFloorZ() {
        return Float.isNaN(hoverZ) ? Float.NaN : GpuLosResult.pointedLevel(hoverZ) * BoardGeometry.level();
    }

    /**
     * The height of the hovered hex's outline on a building floor under the pointer (hover column): half
     * a hex above the floor's level when that is more than a hex above the hex's own ground, else NaN.
     */
    private float hoverTop() {
        if (hovered == null || scene == null || scene.tile(hovered) == null || Float.isNaN(hoverZ)) {
            return Float.NaN;
        }
        float base = BoardTacticalGeometry.floatingZ(scene, hovered);
        float top = Math.max(base, hoverFloorZ() + .5f * BoardGeometry.hexScale());
        return top > base + BoardGeometry.hexScale() ? top : Float.NaN;
    }

    private boolean hovers(BoardScene.Unit unit) {
        return hovered != null && unit.footprint().contains(hovered);
    }

    /**
     * The selected unit and the enemy the board overlay focuses (as of its last update); their Tactical View icons
     * get the bold white frame
     */
    private boolean marked(BoardScene.Unit unit) {
        return unit.id() == scene.selectedId() || unit.id() == overlay.focus();
    }

    /** Held camera binds share the existing focus and release routing; free flight uses wall-clock movement. */
    private void advanceFirstPerson(float seconds, float speed) {
        float forward = 0, sideways = 0, vertical = 0, pitch = 0;
        for (KeyCommandBind command : cameraKeys.values()) {
            switch (command) {
                case SCROLL_NORTH -> forward += 1;
                case SCROLL_SOUTH -> forward -= 1;
                case SCROLL_EAST -> sideways += 1;
                case SCROLL_WEST -> sideways -= 1;
                case CAMERA_ROTATE_LEFT -> vertical -= 1;
                case CAMERA_ROTATE_RIGHT -> vertical += 1;
                case CAMERA_TILT_UP -> pitch += 1;
                case CAMERA_TILT_DOWN -> pitch -= 1;
                default -> { }
            }
        }
        float elapsed = MathUtils.clamp(seconds, 0, .1f) * speed;
        if (pitch != 0) { boardCamera.look(0, pitch * TILT_DEGREES_PER_SECOND * elapsed); }
        boardCamera.fly(forward, sideways, vertical, BoardGeometry.height() * FLIGHT_HEXES_PER_SECOND * elapsed);
    }

    Vector3 screenPosition(Coords coords) {
        Vector3 point = boardCamera.camera.project(BoardGeometry.center(coords, scene.tile(coords).elevation()),
              0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
        point.y = Gdx.graphics.getHeight() - point.y;
        return point;
    }

    private final class BoardInput extends InputAdapter {
        private long gestureBoardGeneration;
        private int dragX;
        private int dragY;
        private boolean panning;
        private boolean orbiting;
        private boolean dragged;
        private boolean boardGesture;
        /** Every end of a gesture (release, focus loss, a modal, a new board) goes through {@link #reset()}. */
        private final UiCursorCapture cursorCapture = new UiCursorCapture();
        /** Frozen at the press: an editor LOS gesture must never paint, even if Alt is released first. */
        private boolean measurementGesture;
        /**
         * A Select press that grabbed no selected item: its drag draws a selection box (Shift adds, Ctrl removes) and a
         * release without a drag is a click. Only a press on the selection, or inside a selected object's outline,
         * moves it.
         */
        private boolean marquee;
        private int gestureButton;
        private GpuTerrain.DecorationHit editorGrab;
        private Coords editorHexGrab;
        private float editorPlaneZ;
        private float editorGrabX, editorGrabY;
        private int gestureModifiers;
        private int startX;
        private int startY;
        private Coords elevationScrollHex;
        private float elevationScroll;
        private record KeyPress(int code, int modifiers) { }
        private final Map<Integer, KeyPress> pressedKeys = new HashMap<>();

        /**
         * {@code floorZ}: the height of the walkable surface under the pointer in the picked hex ({@link
         * GpuTerrain#walkableHit}), which the hover ring marks; NaN without one. {@code onTerrain}: no unit or marker
         * takes priority over the terrain hit.
         */
        private record Pick(Coords coords, int entityId, float floorZ, boolean onTerrain) {
            /** The height a click measures to: the ring's, or NaN (the unit's own) when a unit or marker takes it. */
            float pointedZ() { return onTerrain ? floorZ : Float.NaN; }
        }

        private void editorPointer(int x, int y, boolean drag) {
            if (scene == null || terrain == null) { return; }
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0,
                  boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            var state = source.editorState();
            boolean additive = (gestureModifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
            if (!drag || additive) {
                editorHexGrab = null;
                editorGrab = state != null && state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT
                      ? objectPress(ray, state) : null;
            }
            if (drag && !additive && editorHexGrab != null && Math.abs(ray.direction.z) > .00001f) {
                Vector3 point = ray.getEndPoint(new Vector3(), (editorPlaneZ - ray.origin.z) / ray.direction.z);
                var target = BoardGeometry.tile(scene, point.x - editorGrabX, point.y - editorGrabY);
                if (target != null) {
                    hovered = target.coords(); hoverZ = Float.NaN; source.setHover(hovered);
                    source.editorPointer(target.coords(), 0, 0, true, null, false, gestureBoardGeneration);
                }
                return;
            }
            if (editorGrab != null && Math.abs(ray.direction.z) > .00001f) {
                Vector3 point = ray.getEndPoint(new Vector3(), (editorGrab.anchorZ() - ray.origin.z) / ray.direction.z);
                Coords at = editorGrab.coords();
                if (!drag || additive) {
                    var object = scene.tile(at).features().stream().map(BoardScene.Feature::decoration)
                          .filter(java.util.Objects::nonNull).filter(d -> d.id().equals(editorGrab.id())).findFirst().orElse(null);
                    if (object == null) { return; }
                    editorGrabX = point.x - BoardGeometry.centerX(at) - (float) object.x() * BoardGeometry.width();
                    editorGrabY = point.y - BoardGeometry.centerY(at) - (float) object.y() * BoardGeometry.height();
                }
                var target = BoardGeometry.tile(scene, point.x - editorGrabX, point.y - editorGrabY);
                if (target == null) { return; }
                hovered = target.coords(); hoverZ = Float.NaN; source.setHover(hovered);
                source.editorPointer(at, (point.x - editorGrabX - BoardGeometry.centerX(at)) / BoardGeometry.width(),
                      (point.y - editorGrabY - BoardGeometry.centerY(at)) / BoardGeometry.height(), drag, editorGrab.id(), additive, gestureBoardGeneration);
                return;
            }
            var hit = state != null && state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.PAINT && state.activeBrush().object() != null
                  ? terrain.placementHit(scene, ray) : terrain.selectionHit(scene, ray);
            if (hit == null) { return; }
            Vector3 point = ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance()));
            if (!drag && !additive && state != null && state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT) {
                editorHexGrab = hit.coords(); editorPlaneZ = point.z;
                editorGrabX = point.x - BoardGeometry.centerX(hit.coords());
                editorGrabY = point.y - BoardGeometry.centerY(hit.coords());
            }
            source.editorPointer(hit.coords(), (point.x - BoardGeometry.centerX(hit.coords())) / BoardGeometry.width(),
                  (point.y - BoardGeometry.centerY(hit.coords())) / BoardGeometry.height(), drag, null, additive, hit.receiver(),
                  (gestureModifiers & InputEvent.CTRL_DOWN_MASK) != 0, gestureBoardGeneration);
        }

        /**
         * The object a Select press picks: over a hex selection, a press on a selected hex picks that hex (null); a press
         * inside a selected object's outline (its installed bounds) picks that object even where its mesh is open;
         * otherwise the nearest object hit, or null for the ground.
         */
        private GpuTerrain.DecorationHit objectPress(com.badlogic.gdx.math.collision.Ray ray,
              megamek.client.ui.boardeditor.BoardEditorSession.Snapshot state) {
            var selection = state.selection();
            if (!selection.isEmpty() && selection.getFirst().object().isEmpty()) {
                var ground = terrain.selectionHit(scene, ray);
                if (ground != null && selection.contains(new megamek.client.ui.boardeditor.BoardEditorSession.Selection(ground.coords(), ""))) {
                    return null;
                }
            }
            var hit = terrain.decorationHit(scene, ray);
            if (hit != null && selection.contains(new megamek.client.ui.boardeditor.BoardEditorSession.Selection(hit.coords(), hit.id()))) {
                return hit;
            }
            for (var item : selection) {
                if (item.object().isEmpty()) { continue; }
                for (var object : terrain.editorObjects(item.coords())) {
                    if (object.id().equals(item.object()) && com.badlogic.gdx.math.Intersector.intersectRayBoundsFast(ray, object.bounds())) {
                        return new GpuTerrain.DecorationHit(item.coords(), object.id(), 0, object.anchorLevel() * BoardGeometry.level());
                    }
                }
            }
            return hit;
        }

        /** The item a Select press at x, y picks: the object {@link #objectPress} picks, else the ground's hex, else null. */
        private megamek.client.ui.boardeditor.BoardEditorSession.Selection pressedItem(int x, int y,
              megamek.client.ui.boardeditor.BoardEditorSession.Snapshot state) {
            if (scene == null || terrain == null) { return null; }
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            var object = objectPress(ray, state);
            if (object != null) { return new megamek.client.ui.boardeditor.BoardEditorSession.Selection(object.coords(), object.id()); }
            var ground = terrain.selectionHit(scene, ray);
            return ground == null ? null : new megamek.client.ui.boardeditor.BoardEditorSession.Selection(ground.coords(), "");
        }

        private Coords pick(int x, int y) {
            return pickSelection(x, y).coords();
        }

        /** The nearest rendered unit under the pointer, including units outlined through scenery. */
        private BoardScene.Unit pickUnit(float x, float y) {
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0,
                  boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            float nearest = Float.POSITIVE_INFINITY;
            BoardScene.Unit picked = null;
            for (BoardScene.Unit unit : scene.units()) {
                var instance = unitIcons.active() ? unitIcons.instance(unit) : unitInstances.get(unit.id() + ":" + unit.part());
                if (instance == null) { continue; }
                float distance = unitPicking.distance(instance, ray);
                if (distance < nearest) {
                    nearest = distance;
                    picked = unit;
                }
            }
            return picked;
        }

        private Pick pickSelection(int x, int y) {
            if (scene == null || reloadingAssets || assetReloadFailed) {
                return new Pick(null, Entity.NONE, Float.NaN, false);
            }
            var pickedUnit = pickUnit(x, y);
            if (pickedUnit == null && !unitIcons.active() && tuning.seeThrough() > 0) {
                // The outline extends slightly beyond the mesh. Direct model hits always take priority.
                float step = GpuUnitVisibility.OUTLINE_STEP * layoutScale;
                for (int dx = -1; dx <= 1 && pickedUnit == null; dx++) {
                    for (int dy = -1; dy <= 1 && pickedUnit == null; dy++) {
                        if (dx != 0 || dy != 0) { pickedUnit = pickUnit(x + dx * step, y + dy * step); }
                    }
                }
            }
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0,
                  boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            var ground = terrain.selectionHit(scene, ray);
            float nearest = ground == null ? Float.POSITIVE_INFINITY : ground.distance();
            Coords coords = ground == null ? null : ground.coords();
            int entityId = Entity.NONE;
            boolean onTerrain = ground != null;
            if (pickedUnit != null) {
                coords = pickedUnit.location().coords();
                entityId = pickedUnit.id();
                onTerrain = false;
            } else {
                for (var entry : markers.locatedInstances().entrySet()) {
                    float distance = unitPicking.distance(entry.getValue(), ray);
                    if (distance < nearest) {
                        nearest = distance;
                        coords = entry.getKey().coords();
                        onTerrain = false;
                    }
                }
            }
            var floor = coords == null ? null : terrain.walkableHit(scene, ray);
            float floorZ = floor != null && floor.coords().equals(coords)
                  ? ray.getEndPoint(new Vector3(), (float) Math.sqrt(floor.distance())).z : Float.NaN;
            if (entityId == Entity.NONE && coords != null) {
                for (BoardScene.Unit unit : scene.units()) {
                    // A unit's hex footprint must not intercept a click on another floor of its building.
                    if (unit.footprint().contains(coords) && (!scene.tile(coords).building() || Float.isNaN(floorZ)
                          || GpuLosResult.pointedLevel(floorZ) == Math.round(unit.location().elevation()))) {
                        entityId = unit.id();
                        break;
                    }
                }
            }
            return new Pick(coords, entityId, floorZ, onTerrain);
        }

        @Override
        public boolean touchDown(int x, int y, int pointer, int button) {
            if (reloadingAssets || assetReloadFailed) { return true; }
            if (boardGesture || ui.hit(x, y)) {
                return false;
            }
            dragX = x;
            dragY = y;
            startX = x;
            startY = y;
            boardGesture = true;
            gestureButton = button;
            gestureModifiers = modifiers();
            measurementGesture = button == Input.Buttons.LEFT && (GpuBoardSource.isMeasurement(gestureModifiers)
                  || ui instanceof GpuMapHud map && map.measuring());
            gestureBoardGeneration = boardGeneration;
            dragged = false;
            panning = button == Input.Buttons.RIGHT || button == Input.Buttons.MIDDLE;
            boolean shiftDown = (gestureModifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
            // Right drags pan and middle drags orbit or look around; Shift at the press swaps them in every mode.
            orbiting = panning && (button == Input.Buttons.MIDDLE) != shiftDown;
            ui.boardPress();
            if (button == Input.Buttons.LEFT && source.isEditor() && !measurementGesture) {
                var state = source.editorState();
                // A plain press on the selection grabs it to move; any other Select press (also one that hits nothing,
                // such as the sky around the board) waits for a box or a click.
                var item = state == null || state.selection().isEmpty() ? null : pressedItem(x, y, state);
                marquee = state != null && state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT
                      && ((gestureModifiers & (InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK)) != 0
                      || item == null || !state.selection().contains(item));
                // The board editor paints from the press on; a left drag goes on painting.
                if (!marquee) { editorPointer(x, y, false); }
            }
            return true;
        }

        @Override
        public boolean touchDragged(int x, int y, int pointer) {
            if (!boardGesture) {
                return false;
            }
            if (source.isEditor() && gestureButton == Input.Buttons.LEFT && !measurementGesture) {
                if (!dragged && Math.abs(x - startX) + Math.abs(y - startY) < DRAG_THRESHOLD * layoutScale) { return true; }
                dragged = true;
                if (marquee) { dragX = x; dragY = y; return true; }
                // The board editor's left drag paints every hex it crosses, from the press on.
                if (!ui.hit(x, y)) {
                    editorPointer(x, y, true);
                }
                return true;
            }
            if (!dragged && Math.abs(x - startX) + Math.abs(y - startY) < DRAG_THRESHOLD * layoutScale) {
                return true;
            }
            // Past the threshold a gesture is a drag: a left one does nothing more and cancels its click (C.4). An orbit
            // holds the cursor still, so it never stops at the screen's edge; a pan keeps the board under the pointer.
            if (!dragged && orbiting) { cursorCapture.capture(x, y); }
            dragged = true;
            if (orbiting) {
                // Without the frame after the capture, whose first poll can report a stale jump (UiCursorCapture).
                Vector2 at = cursorCapture.trusted(x, y);
                x = (int) at.x;
                y = (int) at.y;
                boardCamera.orbit((x - dragX) * ORBIT_DEGREES / layoutScale,
                      (y - dragY) * ORBIT_DEGREES / layoutScale);
            } else if (panning) {
                boardCamera.pan(x - dragX, y - dragY);
            }
            dragX = x;
            dragY = y;
            return true;
        }

        @Override
        public boolean touchUp(int x, int y, int pointer, int button) {
            if (!boardGesture || button != gestureButton) {
                return false;
            }
            if (source.isEditor() && !measurementGesture && button == Input.Buttons.LEFT) {
                if (marquee && dragged) { selectBox(x, y); }
                // A click: Ctrl removes the clicked item; otherwise it selects it, or with Shift toggles it.
                else if (marquee && (gestureModifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
                    var state = source.editorState();
                    var item = state == null ? null : pressedItem(startX, startY, state);
                    if (item != null) {
                        source.editorSelect(List.of(item), megamek.client.ui.boardeditor.BoardEditorSession.SelectMode.REMOVE,
                              gestureBoardGeneration);
                    }
                } else if (marquee) { editorPointer(startX, startY, false); }
                else if (!dragged && editorHexGrab != null) {
                    // A click on a placed object standing on a selected hex selects the object; only a drag moves hexes.
                    var object = terrain == null ? null : terrain.decorationHit(scene, boardCamera.camera.getPickRay(startX, startY,
                          0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
                    if (object != null) { source.editorPointer(object.coords(), 0, 0, false, object.id(), false, gestureBoardGeneration); }
                }
                source.endEditorStroke();
                reset();
                return true;
            }
            // A short left or right click on the board of the press goes to the HUD: the tool click or the menu. A
            // map preview follows hover unless its menu is open; Alt or a pending ruler consumes a left click for LOS.
            if (!dragged && button != Input.Buttons.MIDDLE && !ui.hit(x, y)
                  && gestureBoardGeneration == boardGeneration) {
                Pick picked = pickSelection(x, y);
                // The height the pointer shows, which a measurement takes.
                float pointedZ = picked.pointedZ();
                if (ui instanceof GpuHud hud) {
                    // The shared phase tool handles a press; releasing Shift first must not turn it into placement.
                    hud.boardClick(picked.coords(), picked.entityId(), button, gestureModifiers, x, y, pointedZ);
                } else if (button == Input.Buttons.RIGHT) {
                    ((GpuMapHud) ui).boardMenu(picked.coords(), pointedZ, x, y);
                } else if (measurementGesture) {
                    source.measure(picked.coords(), gestureModifiers, pointedZ);
                } else {
                    source.setHover(picked.coords());
                }
            }
            reset();
            return true;
        }

        void reset() {
            cursorCapture.release();
            panning = false;
            orbiting = false;
            dragged = false;
            boardGesture = false;
            measurementGesture = false;
            marquee = false;
            editorGrab = null; editorHexGrab = null;
            clearElevationScroll();
        }

        /**
         * One box selection of the objects whose anchors, and the hexes whose centres, in the scene the frame drew,
         * project inside the rectangle; the session takes the objects, or the hexes when the box holds no object.
         */
        private void selectBox(int x, int y) {
            if (scene == null) { return; }
            int left = Math.min(startX, x), right = Math.max(startX, x), top = Math.min(startY, y), bottom = Math.max(startY, y);
            Set<megamek.client.ui.boardeditor.BoardEditorSession.Selection> hits = new java.util.LinkedHashSet<>();
            for (BoardScene.Tile tile : scene.tiles()) {
                Vector3 centre = boardCamera.camera.project(BoardGeometry.center(tile.coords(), tile.elevation()),
                      0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                float centreY = Gdx.graphics.getHeight() - centre.y;
                if (centre.z >= 0 && centre.z <= 1 && centre.x >= left && centre.x <= right && centreY >= top && centreY <= bottom) {
                    hits.add(new megamek.client.ui.boardeditor.BoardEditorSession.Selection(tile.coords(), ""));
                }
                if (tile.features().stream().allMatch(feature -> feature.decoration() == null)) { continue; }
                // The installed anchors, the same placement the selection outlines use: roofs, decks and offsets.
                Map<String, Float> anchors = new HashMap<>();
                // What the zoom hides cannot be box selected, as it cannot be clicked: the box takes the hexes there.
                Set<String> hidden = new java.util.HashSet<>();
                if (terrain != null) {
                    for (var installed : terrain.editorObjects(tile.coords())) {
                        anchors.put(installed.id(), installed.anchorLevel());
                        if (!installed.shown()) { hidden.add(installed.id()); }
                    }
                }
                for (BoardScene.Feature feature : tile.features()) {
                    var object = feature.decoration();
                    if (object == null || hidden.contains(object.id())) { continue; }
                    Vector3 point = boardCamera.camera.project(new Vector3(
                                BoardGeometry.centerX(tile.coords()) + (float) object.x() * BoardGeometry.width(),
                                BoardGeometry.centerY(tile.coords()) + (float) object.y() * BoardGeometry.height(),
                                anchors.getOrDefault(object.id(), (float) tile.elevation()) * BoardGeometry.level()),
                          0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                    float screenY = Gdx.graphics.getHeight() - point.y;
                    if (point.z >= 0 && point.z <= 1 && point.x >= left && point.x <= right && screenY >= top && screenY <= bottom) {
                        hits.add(new megamek.client.ui.boardeditor.BoardEditorSession.Selection(tile.coords(), object.id()));
                    }
                }
            }
            var mode = (gestureModifiers & InputEvent.CTRL_DOWN_MASK) != 0 ? megamek.client.ui.boardeditor.BoardEditorSession.SelectMode.REMOVE
                  : (gestureModifiers & InputEvent.SHIFT_DOWN_MASK) != 0 ? megamek.client.ui.boardeditor.BoardEditorSession.SelectMode.ADD
                  : megamek.client.ui.boardeditor.BoardEditorSession.SelectMode.REPLACE;
            source.editorSelect(List.copyOf(hits), mode, gestureBoardGeneration);
        }

        private void clearElevationScroll() {
            elevationScrollHex = null;
            elevationScroll = 0;
        }

        @Override
        public boolean mouseMoved(int x, int y) {
            // Camera refreshes must not replace an editor drag's projected destination with a new cursor hit.
            if (source.isEditor() && boardGesture && gestureButton == Input.Buttons.LEFT && !measurementGesture
                  && (editorGrab != null || editorHexGrab != null)) { return true; }
            boolean overHud = ui.hit(x, y);
            Pick picked = overHud ? new Pick(null, Entity.NONE, Float.NaN, false) : pickSelection(x, y);
            hovered = picked.coords();
            hoveredUnit = picked.entityId();
            hoverZ = picked.floorZ();
            source.setHover(hoverHeld ? null : hovered, picked.pointedZ());
            // An object under the pointer in the editor's inspected hex is highlighted in its side view and Layers.
            if (ui instanceof GpuMapHud map && source.editorState() != null) {
                var hit = hovered != null && hovered.equals(source.editorState().selected()) ? terrain.decorationHit(scene,
                      boardCamera.camera.getPickRay(x, y, 0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight)) : null;
                map.editorBoardHover(hit != null && hit.coords().equals(hovered) ? hit.id() : "");
            }
            // Desktop pointers: a hand over units, a move cursor over the minimap, which drags the camera (Q5).
            cursor(overHud ? ui.dragsCamera(x, y) ? SystemCursor.AllResize : SystemCursor.Arrow
                  : hoveredUnit == Entity.NONE ? SystemCursor.Arrow : SystemCursor.Hand);
            return false;
        }

        private void finishElevationScroll() {
            if (elevationScrollHex != null) {
                source.endEditorStroke();
                clearElevationScroll();
            }
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            if (reloadingAssets || assetReloadFailed) { return true; }
            if (!ui.hit(Gdx.input.getX(), Gdx.input.getY())) {
                if (source.isEditor() && modifiers() == InputEvent.CTRL_DOWN_MASK) {
                    if (!boardGesture && !ui.isTextEditing()) {
                        Coords coords = pick(Gdx.input.getX(), Gdx.input.getY());
                        if (coords != null) {
                            if (!coords.equals(elevationScrollHex)) {
                                elevationScroll = 0;
                                elevationScrollHex = coords;
                            }
                            elevationScroll -= amountY;
                            int levels = (int) elevationScroll;
                            elevationScroll -= levels;
                            if (levels != 0) {
                                source.adjustEditorElevation(coords, levels, boardGeneration);
                            }
                        }
                    }
                    return true;
                }
                finishElevationScroll();
                boardCamera.zoomAt(wheelZoom(amountY), Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
                return true;
            }
            return false;
        }

        @Override
        public boolean keyDown(int key) {
            if (pressedKeys.containsKey(key) || cameraKeys.containsKey(key)) {
                return true;
            }
            int awt = awtKey(key);
            int modifiers = modifiers();
            // Resolve Shift as camera acceleration before HUD hotkeys can turn or twist a unit with Shift+A/D.
            List<GpuBoardSource.Bind> captured = source.uiPreferences().binds();
            int bindModifiers = modifiers;
            if (modifiers == InputEvent.SHIFT_DOWN_MASK
                  && GpuHud.binds(captured, awt, 0).stream().anyMatch(GpuBattleView::heldCameraCommand)) {
                bindModifiers = 0;
            }
            // The HUD first (C.4): a pending dialog, the Esc chain, a focused field or list, the hotkeys.
            if (ui.keyDown(key, awt, bindModifiers)) {
                return true;
            }
            if (reloadingAssets || assetReloadFailed) { return true; }
            // The binds the client captured on the Swing thread; the bind fields are Swing's (rebuild plan D).
            Set<KeyCommandBind> binds = GpuHud.binds(captured, awt, bindModifiers);
            for (KeyCommandBind command : binds) {
                if (cameraCommand(key, command)) {
                    return true;
                }
            }
            if (isMoving() && binds.contains(KeyCommandBind.CENTER_ON_SELECTED)) {
                playback.finish();
            }
            if (awt != KeyEvent.VK_UNDEFINED) {
                pressedKeys.put(key, new KeyPress(awt, modifiers));
                source.key(awt, true, modifiers);
            }
            return awt != KeyEvent.VK_UNDEFINED;
        }

        /**
         * Applies a bind that moves the camera. Held binds are only noted here and applied every frame while the key
         * stays down.
         *
         * @return {@code false} for a bind that is not a camera command, which is then left to the Swing key dispatcher
         */
        private boolean cameraCommand(int key, KeyCommandBind command) {
            switch (command) {
                case SCROLL_NORTH, SCROLL_SOUTH, SCROLL_EAST, SCROLL_WEST, CAMERA_TILT_UP, CAMERA_TILT_DOWN,
                     CAMERA_ROTATE_LEFT, CAMERA_ROTATE_RIGHT -> cameraKeys.put(key, command);
                case TOGGLE_ISO -> setTacticalView(!boardCamera.tactical());
                case ZOOM_IN -> boardCamera.zoom(1 / ZOOM_STEP);
                case ZOOM_OUT -> boardCamera.zoom(ZOOM_STEP);
                case CAMERA_RESET, CAMERA_FIT_BOARD, ZOOM_OVERVIEW_TOGGLE -> frameBoard(command);
                default -> {
                    return false;
                }
            }
            LOGGER.debug("[GpuCamera] {} handled by the GPU camera", command);
            return true;
        }

        /** These camera moves measure the board, so they wait until the first frame has delivered one. */
        private void frameBoard(KeyCommandBind command) {
            if (scene == null) {
                LOGGER.debug("[GpuCamera] {} ignored: no board has been drawn yet", command);
                return;
            }
            switch (command) {
                case CAMERA_RESET -> boardCamera.reset(scene);
                case CAMERA_FIT_BOARD -> fitBoard();
                case ZOOM_OVERVIEW_TOGGLE -> boardCamera.toggleOverview(scene);
                default -> { }
            }
        }

        @Override
        public boolean keyUp(int key) {
            if (key == Input.Keys.CONTROL_LEFT || key == Input.Keys.CONTROL_RIGHT) {
                finishElevationScroll();
            }
            // Every release reaches the HUD; one whose press it consumed ends there.
            if (ui.keyUp(key, awtKey(key))) {
                return true;
            }
            if (cameraKeys.remove(key) != null) {
                return true;
            }
            KeyPress pressed = pressedKeys.remove(key);
            if (pressed != null) {
                source.key(pressed.code(), false, pressed.modifiers());
            }
            return pressed != null;
        }

        @Override
        public boolean keyTyped(char character) {
            return ui.keyTyped(character);
        }
    }

    private static boolean heldCameraCommand(KeyCommandBind command) {
        return switch (command) {
            case SCROLL_NORTH, SCROLL_SOUTH, SCROLL_EAST, SCROLL_WEST,
                 CAMERA_ROTATE_LEFT, CAMERA_ROTATE_RIGHT, CAMERA_TILT_UP, CAMERA_TILT_DOWN -> true;
            default -> false;
        };
    }

    /** Shows a system pointer on desktop platforms; mobile platforms have none to change (user correction 4). */
    private void cursor(SystemCursor next) {
        if (next != cursor && Gdx.app.getType() == Application.ApplicationType.Desktop) {
            cursor = next;
            Gdx.graphics.setSystemCursor(next);
        }
    }

    static int awtKey(int key) {
        boolean numLock = true;
        if (key >= Input.Keys.NUMPAD_0 && key <= Input.Keys.NUMPAD_DOT) {
            try {
                numLock = Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_NUM_LOCK);
            } catch (UnsupportedOperationException ignored) {
                // Some window systems do not expose lock state; keep the numeric keypad usable there.
            }
        }
        return awtKey(key, numLock);
    }

    static int awtKey(int key, boolean numLock) {
        if (!numLock) {
            int navigation = switch (key) {
                case Input.Keys.NUMPAD_0 -> KeyEvent.VK_INSERT;
                case Input.Keys.NUMPAD_1 -> KeyEvent.VK_END;
                case Input.Keys.NUMPAD_2 -> KeyEvent.VK_KP_DOWN;
                case Input.Keys.NUMPAD_3 -> KeyEvent.VK_PAGE_DOWN;
                case Input.Keys.NUMPAD_4 -> KeyEvent.VK_KP_LEFT;
                case Input.Keys.NUMPAD_5 -> KeyEvent.VK_CLEAR;
                case Input.Keys.NUMPAD_6 -> KeyEvent.VK_KP_RIGHT;
                case Input.Keys.NUMPAD_7 -> KeyEvent.VK_HOME;
                case Input.Keys.NUMPAD_8 -> KeyEvent.VK_KP_UP;
                case Input.Keys.NUMPAD_9 -> KeyEvent.VK_PAGE_UP;
                case Input.Keys.NUMPAD_DOT -> KeyEvent.VK_DELETE;
                default -> KeyEvent.VK_UNDEFINED;
            };
            if (navigation != KeyEvent.VK_UNDEFINED) {
                return navigation;
            }
        }
        if (key >= Input.Keys.A && key <= Input.Keys.Z) {
            return KeyEvent.VK_A + key - Input.Keys.A;
        }
        if (key >= Input.Keys.NUM_0 && key <= Input.Keys.NUM_9) {
            return KeyEvent.VK_0 + key - Input.Keys.NUM_0;
        }
        if (key >= Input.Keys.F1 && key <= Input.Keys.F12) {
            return KeyEvent.VK_F1 + key - Input.Keys.F1;
        }
        if (key >= Input.Keys.F13 && key <= Input.Keys.F24) {
            return KeyEvent.VK_F13 + key - Input.Keys.F13;
        }
        if (key >= Input.Keys.NUMPAD_0 && key <= Input.Keys.NUMPAD_9) {
            return KeyEvent.VK_NUMPAD0 + key - Input.Keys.NUMPAD_0;
        }
        return switch (key) {
            case Input.Keys.UP -> KeyEvent.VK_UP;
            case Input.Keys.DOWN -> KeyEvent.VK_DOWN;
            case Input.Keys.LEFT -> KeyEvent.VK_LEFT;
            case Input.Keys.RIGHT -> KeyEvent.VK_RIGHT;
            case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> KeyEvent.VK_ENTER;
            case Input.Keys.TAB -> KeyEvent.VK_TAB;
            case Input.Keys.ESCAPE -> KeyEvent.VK_ESCAPE;
            case Input.Keys.SPACE -> KeyEvent.VK_SPACE;
            case Input.Keys.BACKSPACE -> KeyEvent.VK_BACK_SPACE;
            case Input.Keys.FORWARD_DEL -> KeyEvent.VK_DELETE;
            case Input.Keys.INSERT -> KeyEvent.VK_INSERT;
            case Input.Keys.HOME -> KeyEvent.VK_HOME;
            case Input.Keys.END -> KeyEvent.VK_END;
            case Input.Keys.PAGE_UP -> KeyEvent.VK_PAGE_UP;
            case Input.Keys.PAGE_DOWN -> KeyEvent.VK_PAGE_DOWN;
            case Input.Keys.MINUS -> KeyEvent.VK_MINUS;
            case Input.Keys.EQUALS, Input.Keys.NUMPAD_EQUALS -> KeyEvent.VK_EQUALS;
            case Input.Keys.COMMA -> KeyEvent.VK_COMMA;
            case Input.Keys.PERIOD -> KeyEvent.VK_PERIOD;
            case Input.Keys.SLASH -> KeyEvent.VK_SLASH;
            case Input.Keys.BACKSLASH -> KeyEvent.VK_BACK_SLASH;
            case Input.Keys.SEMICOLON -> KeyEvent.VK_SEMICOLON;
            case Input.Keys.APOSTROPHE -> KeyEvent.VK_QUOTE;
            case Input.Keys.GRAVE -> KeyEvent.VK_BACK_QUOTE;
            case Input.Keys.LEFT_BRACKET -> KeyEvent.VK_OPEN_BRACKET;
            case Input.Keys.RIGHT_BRACKET -> KeyEvent.VK_CLOSE_BRACKET;
            case Input.Keys.PLUS -> KeyEvent.VK_PLUS;
            case Input.Keys.NUMPAD_ADD -> KeyEvent.VK_ADD;
            case Input.Keys.NUMPAD_SUBTRACT -> KeyEvent.VK_SUBTRACT;
            case Input.Keys.NUMPAD_MULTIPLY -> KeyEvent.VK_MULTIPLY;
            case Input.Keys.NUMPAD_DIVIDE -> KeyEvent.VK_DIVIDE;
            case Input.Keys.NUMPAD_DOT -> KeyEvent.VK_DECIMAL;
            case Input.Keys.NUMPAD_COMMA -> KeyEvent.VK_SEPARATOR;
            case Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT -> KeyEvent.VK_SHIFT;
            case Input.Keys.CONTROL_LEFT, Input.Keys.CONTROL_RIGHT -> KeyEvent.VK_CONTROL;
            case Input.Keys.ALT_LEFT, Input.Keys.ALT_RIGHT -> KeyEvent.VK_ALT;
            case Input.Keys.SYM -> KeyEvent.VK_META;
            case Input.Keys.CAPS_LOCK -> KeyEvent.VK_CAPS_LOCK;
            case Input.Keys.NUM_LOCK -> KeyEvent.VK_NUM_LOCK;
            case Input.Keys.SCROLL_LOCK -> KeyEvent.VK_SCROLL_LOCK;
            case Input.Keys.PAUSE -> KeyEvent.VK_PAUSE;
            case Input.Keys.PRINT_SCREEN -> KeyEvent.VK_PRINTSCREEN;
            case Input.Keys.MENU -> KeyEvent.VK_CONTEXT_MENU;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    @Override
    public void pause() {
        cameraKeys.clear();
        boardInput.pressedKeys.clear();
        boardInput.reset();
        if (ui != null) {
            ui.focusLost();
        }
        if (source != null) {
            source.stopKeys();
        }
    }

    static int modifiers() {
        int result = 0;
        if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) {
            result |= InputEvent.SHIFT_DOWN_MASK;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)) {
            result |= InputEvent.CTRL_DOWN_MASK;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)) {
            result |= InputEvent.ALT_DOWN_MASK;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.SYM)) {
            result |= InputEvent.META_DOWN_MASK;
        }
        return result;
    }

    long frames() {
        return frames;
    }

    boolean isMoving() {
        return playback.busy();
    }

    @Override
    public void dispose() {
        try { shaderEdits.run(this::disposeView); }
        finally { shaderEdits.close(); }
    }

    private void disposeView() {
        disposed = true;
        if (loadingStage != null) {
            loadingStage.dispose();
            if (loadingTheme != null) { loadingTheme.dispose(); }
        }
        playback.clear();
        groundSurfaces.clear();
        clearUnitInstances();
        disposeSceneRenderers();
        if (ui != null) {
            ui.dispose();
        }
        if (theme != null) {
            theme.dispose();
        }
        if (source != null) {
            source.stopKeys();
        }
        spriteModels.values().forEach(GpuUnitModel::dispose);
        spriteModels.clear();
        if (unitModels != null) {
            unitModels.dispose();
        }
        camouflage.dispose();
        damageDisplay.dispose();
        unitIcons.dispose();
        if (annotationBatch != null) {
            annotationBatch.dispose();
        }
        if (unitTextures != null) {
            unitTextures.dispose();
        }
        if (markers != null) {
            markers.dispose();
        }
        fieldOfView.dispose();
        if (overlay != null) {
            overlay.dispose();
        }
        if (source != null) {
            source.close();
        }
    }

    /** Instances borrow model buffers and textures, so drop them before either cache is disposed. */
    private void clearUnitInstances() {
        animators.clear();
        unitInstances.clear();
        unitBounds.begin();
        unitAnchors.clear();
        unitTints.clear();
        unitDamage.clear();
        equipmentAppearance.clear();
        upperBodyTurns.clear();
        armFlips.clear();
        unitFootprints.clear();
        hover.clear();
        unitPicking.clear();
    }
    void cameraCommand(KeyCommandBind command) { boardInput.cameraCommand(-1, command); }

}
