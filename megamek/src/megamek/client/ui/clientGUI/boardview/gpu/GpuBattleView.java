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
    /** Hide all declared firing arrows while combat is playing, regardless of selection. */
    static final boolean HIDE_TARGET_ARROWS_DURING_ATTACKS = true;
    /** hud-v3's unitRect (view3d.js): in 3D a unit's screen rectangle is .55 of a hex wide. */
    private static final float UNIT_RECT_WIDTH = .55f;
    /** Floating units are tied to their hex with this faint solid stem; solid, so it needs no blending state. */
    private static final Color TETHER_COLOR = Color.valueOf("A9B8B8");
    private static final float TILT_DEGREES_PER_SECOND = 60;
    /** A held CAMERA_ROTATE bind turns the 3D camera at this rate (rebuild plan A.17 Q9). */
    private static final float ROTATE_DEGREES_PER_SECOND = 70;
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
    private int editorToolsModifiers;
    private final List<Hover> hover = new ArrayList<>();
    private GpuTerrain terrain;
    private GpuFireControl fireControl;
    /** The hud-v3 board overlay (G7): envelopes, the route and its ghost, rings and glows, bands and arcs. */
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
    /** The hud-v3 HUD (C.4); its skin also holds the hex labels' font. */
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
    /** Height of the latest pointer's terrain/object hit, owned by the render thread like hovered. */
    private float hoverZ = Float.NaN;
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
              : new GpuMapHud(source, theme.skin, annotationBatch, boardCamera, tuning, history);
        Gdx.input.setInputProcessor(new InputMultiplexer(ui.stage(), boardInput) {
            // The HUD's Stage takes the presses on its widgets; every key goes through BoardInput, which asks the
            // HUD first (C.4), so no key reaches the Stage twice.
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
        // Wait for Swing's measured tools inset before the editor's first camera fit.
        if (source.isEditor() && source.toolsInset() < 0) {
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
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
        // The HUD's state before the camera frames: it opens and closes the columns the board area lies between.
        if (ui instanceof GpuHud hud) { hud.updateState(frame, playbackBusy(), dialog()); }
        playback.gravityOverride = tuning.gravityOverride();
        boardCamera.viewableArea(ui.cameraLeft(), ui.cameraWidth());
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
        terrain.update(scene, boardCamera.camera);
        boolean detailChanged = terrain.refine(boardCamera.camera);
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
        scene = terrain.presentation(scene);
        boolean changedTiles = previousTiles != scene.tiles();
        if (changedTiles || cameraTerrainRevision != BoardGeometry.revision()) {
            boardCamera.terrainChanged();
            cameraTerrainRevision = BoardGeometry.revision();
        }
        fireControl.update(scene, HIDE_TARGET_ARROWS_DURING_ATTACKS && !playback.attacks().isEmpty(), frame.status(),
              frame.panels().fire());
        tactical.update(scene, detailChanged, hovered, !frame.panels().move().route().isEmpty());
        fieldOfView.update(scene.fieldOfView());
        fieldOfView.configure(tuning.fovStyle(), tuning.fovDarkness(), tuning.sensorStyle(), tuning.sensorDarkness());
        attackEffects.setWind(atmosphereSettings.effects());
        terrain.setNormalMaps(tuning.normalMaps());
        terrain.setGrass(tuning.grass());
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
        if (ui.isTextEditing()) {
            // The camera keys work with every panel open and pause only while a text field takes the keys (Q9).
            cameraKeys.clear();
        } else if (boardCamera.firstPerson()) {
            advanceFirstPerson(Gdx.graphics.getDeltaTime());
        } else {
            float distance = 500 * Gdx.graphics.getDeltaTime();
            float inclination = TILT_DEGREES_PER_SECOND * Gdx.graphics.getDeltaTime();
            float rotation = ROTATE_DEGREES_PER_SECOND * Gdx.graphics.getDeltaTime();
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
        for (var attack : playback.attacks()) { attack.landscape = ray -> terrain.hit(scene, ray); }
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
        attackEffects.update(playback.attacks(), unitModels, unitInstances);
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
        if (!unitIcons.active()) { renderTethers(); }
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
        atmosphere.end(boardCamera.camera, terrain, scene, 0, fieldOfView);
        if (wireframeView) { wireframe.lines(boardCamera.camera, terrain); }
        renderStage("weather particles");
        atmosphere.renderWeather(boardCamera.camera, scene);
        renderStage("unit outlines");
        unitVisibility.render(boardCamera.camera, outlined, atmosphere.depthTexture(), 0, seeThrough, layoutScale,
              unitBounds, terrain.tacticalView() ? null : terrainEffects.opacityTexture());
        renderStage("tactical overlays");
        terrain.render(boardCamera.camera, true);
        // The board overlay over the terrain's marks, its unit marks where the units stand this frame first; in 3D
        // after the route's ghost, whose depth hides the marks under it. In the Tactical View the overlay lies under
        // the icons and the ghost icon over them.
        if (!unitIcons.active()) { overlay.renderGhost(boardCamera.camera, shownUnit); }
        overlay.renderMarks(boardCamera.camera, unitFootprints::get);
        overlay.render(boardCamera.camera);
        renderHoverRings();
        fireControl.render(boardCamera.camera, Gdx.graphics.getDeltaTime());
        tactical.render(boardCamera.camera, Gdx.graphics.getDeltaTime(), unitIcons.active());
        hexGrid.render(boardCamera, scene, terrain.tacticalView());
        renderHexText();
        unitIcons.render(boardCamera.camera);
        if (unitIcons.active()) { overlay.renderGhost(boardCamera.camera, shownUnit); }
        fireControl.renderLabels(boardCamera.camera);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        tactical.renderLabels(boardCamera.camera);
        renderStage("annotations and UI");
        renderAnnotations();
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
     * joined by its corners to a faint outline on the hex.
     */
    private void renderHoverRings() {
        float top = hoverTop();
        if (hovered == null || scene.tile(hovered) == null || ui.hit(Gdx.input.getX(), Gdx.input.getY())) {
            return;
        }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        lines.setProjectionMatrix(boardCamera.camera.combined);
        lines.begin(ShapeRenderer.ShapeType.Line);
        lines.setColor(Color.WHITE);
        if (source.isEditor() && editorModifiers() == InputEvent.CTRL_DOWN_MASK) {
            for (Coords coords : source.editorBrush(hovered, boardGeneration)) {
                if (scene.tile(coords) != null) {
                    ring(coords, BoardTacticalGeometry.floatingZ(scene, coords));
                }
            }
        } else {
            float base = BoardTacticalGeometry.floatingZ(scene, hovered);
            ring(hovered, Float.isNaN(top) ? base : top);
            if (!Float.isNaN(top)) {
                lines.setColor(1, 1, 1, .25f);
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
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** A hex's outline, inset by {@link #HOVER_HEX_INSET}, at height {@code z}. */
    private void ring(Coords coords, float z) {
        Vector3 center = BoardGeometry.center(coords, 0);
        for (int edge = 0; edge < 6; edge++) {
            Vector3 from = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, HOVER_HEX_INSET);
            Vector3 to = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge + 1), center, HOVER_HEX_INSET);
            lines.line(from.x, from.y, z, to.x, to.y, z);
        }
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
                float loss = tuning.damageOverride() >= 0 ? tuning.damageOverride() : appearance == null ? 0 : appearance.bodyLoss();
                var bodyDamage = UnitDamageDisplay.preview(BoardScene.LocationDamage.NONE, false, loss);
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
            boardCamera.frameMovement(state.movement(), motions.get(state.activeEntityId()), state.present(scene), cameraWidth());
        } else if (state.attack() != null && state.attack().shot()) {
            boardCamera.frameAttacks(state.attacks(), cameraWidth());
        } else {
            return true;
        }
        return !boardCamera.isFraming();
    }

    private float cameraWidth() { return ui == null ? boardCamera.camera.viewportWidth : ui.cameraWidth(); }

    /** Frame the presented action; selection changes during playback take effect after its final hold. */
    void updateCameraFocus(BoardScene scene, BoardFocus request) {
        updateCameraFocus(scene, request, null);
    }

    void updateCameraFocus(BoardScene scene, BoardFocus request, BoardScene.Animation instantAction) {
        if (!fitted) {
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
                boardCamera.frameMovement(playback.movement(), motions.get(playback.activeEntityId()), scene, cameraWidth());
                return;
            }
            cameraFollowingPlayback = true;
            if (firing) {
                boardCamera.frameAttacks(playback.attacks(), cameraWidth());
                return;
            }
            var active = scene.units().stream().filter(unit -> unit.id() == playback.activeEntityId())
                  .findFirst().orElse(null);
            if (active != null) {
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
            boardCamera.frameSelection(selection, cameraWidth());
        } else if (instantAction instanceof BoardScene.Combat combat && combat.result().kind() == ResolvedAttack.Kind.SHOT) {
            boardCamera.frameAttacks(List.of(new UnitAttack(combat)), cameraWidth());
        } else if (instantAction != null) {
            scene.units().stream().filter(unit -> unit.id() == instantAction.entityId()).findFirst()
                  .ifPresent(unit -> boardCamera.frameSelection(unit, cameraWidth()));
        } else if (center != null && scene.tile(center) != null) {
            boardCamera.frameLocation(BoardGeometry.center(center, scene.tile(center).elevation()), cameraWidth());
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
     * covers its hex, and a moving token is between hexes, so neither gets a stem.
     */
    private void renderTethers() {
        Vector3 center = new Vector3();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        lines.setProjectionMatrix(boardCamera.camera.combined);
        lines.begin(ShapeRenderer.ShapeType.Line);
        lines.setColor(TETHER_COLOR);
        for (BoardScene.Unit unit : scene.units()) {
            BoardScene.Tile tile = scene.tile(unit.location().coords());
            ModelInstance instance = unitInstances.get(unit.id() + ":" + unit.part());
            UnitMotion motion = motions.get(unit.id());
            if (tile == null || instance == null) {
                continue;
            }
            instance.transform.getTranslation(center);
            float ground = tetherGround(unit, tile.elevation(), center, motion != null && motion.isMoving());
            if (!Float.isNaN(ground)) {
                lines.line(center.x, center.y, center.z, center.x, center.y, ground);
            }
        }
        lines.end();
        Gdx.gl.glDepthMask(true);
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

    /** The level floor under the pointer's terrain or object hit, NaN without one. */
    private float hoverFloorZ() {
        return Float.isNaN(hoverZ) ? Float.NaN : MathUtils.floor(hoverZ / BoardGeometry.level() + .0001f) * BoardGeometry.level();
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
     * get the bold white frame (hud-v3 flat.js).
     */
    private boolean marked(BoardScene.Unit unit) {
        return unit.id() == scene.selectedId() || unit.id() == overlay.focus();
    }

    /** Held camera binds share the existing focus and release routing; free flight uses wall-clock movement. */
    private void advanceFirstPerson(float seconds) {
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
        float elapsed = MathUtils.clamp(seconds, 0, .1f);
        if (pitch != 0) { boardCamera.look(0, pitch * TILT_DEGREES_PER_SECOND * elapsed); }
        float speed = (modifiers() & InputEvent.SHIFT_DOWN_MASK) != 0 ? 4 : 1;
        boardCamera.fly(forward, sideways, vertical, BoardGeometry.height() * 3 * speed * elapsed);
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
        private int gestureButton;
        private int gestureModifiers;
        private int startX;
        private int startY;
        private Coords elevationScrollHex;
        private float elevationScroll;
        private record KeyPress(int code, int modifiers) { }
        private final Map<Integer, KeyPress> pressedKeys = new HashMap<>();

        private record Pick(Coords coords, int entityId, float surfaceZ) { }

        private Coords pick(int x, int y) {
            return pickSelection(x, y).coords();
        }

        private Pick pickSelection(int x, int y) {
            if (scene == null || reloadingAssets || assetReloadFailed) {
                return new Pick(null, Entity.NONE, Float.NaN);
            }
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0,
                  boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            var ground = terrain.selectionHit(scene, ray);
            float nearest = ground == null ? Float.POSITIVE_INFINITY : ground.distance();
            Coords coords = ground == null ? null : ground.coords();
            int entityId = Entity.NONE;
            float nearestUnit = unitIcons.active() ? Float.POSITIVE_INFINITY : nearest;
            for (BoardScene.Unit unit : scene.units()) {
                var instance = unitIcons.active() ? unitIcons.instance(unit) : unitInstances.get(unit.id() + ":" + unit.part());
                if (instance != null) {
                    float distance = unitPicking.distance(instance, ray);
                    if (distance < nearestUnit) {
                        nearestUnit = distance;
                        nearest = distance;
                        entityId = unit.id();
                        coords = unit.location().coords();
                    }
                }
            }
            for (var entry : markers.locatedInstances().entrySet()) {
                float distance = unitPicking.distance(entry.getValue(), ray);
                if (distance < nearest) {
                    nearest = distance;
                    coords = entry.getKey().coords();
                    entityId = Entity.NONE;
                }
            }
            if (entityId == Entity.NONE && coords != null) {
                for (BoardScene.Unit unit : scene.units()) {
                    if (unit.footprint().contains(coords)) {
                        entityId = unit.id();
                        break;
                    }
                }
            }
            float surfaceZ = ground != null && nearest == ground.distance()
                  ? ray.getEndPoint(new Vector3(), (float) Math.sqrt(nearest)).z : Float.NaN;
            return new Pick(coords, entityId, surfaceZ);
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
            gestureBoardGeneration = boardGeneration;
            dragged = false;
            panning = button == Input.Buttons.RIGHT || button == Input.Buttons.MIDDLE;
            boolean shiftDown = (gestureModifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
            // Right drags pan and middle drags orbit; Shift at the press swaps them (C.4). Free Flight looks around
            // with the right drag instead.
            int orbitButton = boardCamera.firstPerson() ? Input.Buttons.RIGHT : Input.Buttons.MIDDLE;
            orbiting = panning && (button == orbitButton) != shiftDown;
            ui.boardPress();
            if (button == Input.Buttons.LEFT && source.isEditor()) {
                // The board editor paints from the press on; a left drag goes on painting.
                source.paintEditor(pick(x, y), gestureModifiers, gestureBoardGeneration);
            }
            return true;
        }

        @Override
        public boolean touchDragged(int x, int y, int pointer) {
            if (!boardGesture) {
                return false;
            }
            if (source.isEditor() && gestureButton == Input.Buttons.LEFT) {
                // The board editor's left drag paints every hex it crosses, from the press on.
                if (!ui.hit(x, y)) {
                    source.paintEditor(pick(x, y), modifiers(), gestureBoardGeneration);
                }
                return true;
            }
            if (!dragged && Math.abs(x - startX) + Math.abs(y - startY) < DRAG_THRESHOLD * layoutScale) {
                return true;
            }
            // Past the threshold a gesture is a drag: a left one does nothing more and cancels its click (C.4).
            dragged = true;
            if (orbiting) {
                if (boardCamera.firstPerson()) {
                    boardCamera.look((x - dragX) * ORBIT_DEGREES / layoutScale,
                          (dragY - y) * ORBIT_DEGREES / layoutScale);
                } else {
                    boardCamera.orbit((x - dragX) * ORBIT_DEGREES / layoutScale,
                          (y - dragY) * ORBIT_DEGREES / layoutScale);
                }
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
            if (source.isEditor()) {
                if (button == Input.Buttons.LEFT) {
                    source.endEditorStroke();
                }
                reset();
                return true;
            }
            // A short left or right click on the board of the press goes to the HUD: the tool click or the menu. A
            // map preview's right click inspects the hex, whose card then shows; its left click measures.
            if (!dragged && button != Input.Buttons.MIDDLE && !ui.hit(x, y)
                  && gestureBoardGeneration == boardGeneration) {
                Pick picked = pickSelection(x, y);
                // The height the pointer shows, which a measurement takes.
                float pointedZ = picked.surfaceZ();
                if (ui instanceof GpuHud hud) {
                    // The shared phase tool handles a press; releasing Shift first must not turn it into placement.
                    hud.boardClick(picked.coords(), picked.entityId(), button, gestureModifiers, x, y, pointedZ);
                } else if (button == Input.Buttons.RIGHT) {
                    source.inspect(picked.coords());
                } else {
                    source.measure(picked.coords(), gestureModifiers, pointedZ);
                }
            }
            reset();
            return true;
        }

        void reset() {
            panning = false;
            orbiting = false;
            dragged = false;
            boardGesture = false;
            clearElevationScroll();
        }

        private void clearElevationScroll() {
            elevationScrollHex = null;
            elevationScroll = 0;
        }

        @Override
        public boolean mouseMoved(int x, int y) {
            boolean overHud = ui.hit(x, y);
            Pick picked = overHud ? new Pick(null, Entity.NONE, Float.NaN) : pickSelection(x, y);
            hovered = picked.coords();
            hoveredUnit = picked.entityId();
            hoverZ = picked.surfaceZ();
            source.setHover(hoverHeld ? null : hovered);
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
                if (source.isEditor() && editorModifiers() == InputEvent.CTRL_DOWN_MASK) {
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
            // The HUD first (C.4): a pending dialog, the Esc chain, a focused field or list, the hotkeys.
            if (ui.keyDown(key, awt, modifiers)) {
                return true;
            }
            if (reloadingAssets || assetReloadFailed) { return true; }
            // The binds the client captured on the Swing thread; the bind fields are Swing's (rebuild plan D).
            List<GpuBoardSource.Bind> captured = source.uiPreferences().binds();
            Set<KeyCommandBind> binds = GpuHud.binds(captured, awt, modifiers);
            if (boardCamera.firstPerson() && modifiers == InputEvent.SHIFT_DOWN_MASK) {
                // Shift accelerates flight, including when held before pressing a movement key.
                for (KeyCommandBind command : GpuHud.binds(captured, awt, 0)) {
                    if (heldCameraCommand(command)) {
                        cameraKeys.put(key, command);
                        return true;
                    }
                }
            }
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
                case CAMERA_FIT_BOARD -> boardCamera.fit(scene);
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

    /** The EDT finishes the undo entry before a palette action; reset its fractional wheel input on the GL thread. */
    void editorToolsInput(int modifiers, boolean finishStroke) {
        editorToolsModifiers = modifiers;
        if (finishStroke) {
            boardInput.clearElevationScroll();
        }
    }

    private int editorModifiers() {
        return modifiers() | editorToolsModifiers;
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
