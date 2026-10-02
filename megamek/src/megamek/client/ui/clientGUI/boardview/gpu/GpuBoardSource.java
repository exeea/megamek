/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Color;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.event.BoardViewEvent;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.sprite.FieldOfFireSprite;
import megamek.client.ui.clientGUI.boardview.sprite.FiringSolutionSprite;
import megamek.client.ui.clientGUI.boardview.sprite.MovementEnvelopeSprite;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.client.ui.dialogs.RoundsInAirDialog;
import megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.UIUtil;
import megamek.common.Hex;
import megamek.common.RangeType;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.BoardListenerAdapter;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.game.GameTurn;
import megamek.common.moves.MovePath;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Aero;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.common.units.Terrains;
import megamek.common.units.UnitLocation;

/** Thin Swing adapter. Reuses MegaMek's tileset, visibility checks, movement path, and actual phase buttons. */
final class GpuBoardSource implements AutoCloseable {
    /** One key binding with its display text ({@code KeyCommandBind.getDesc}), copied on the Swing thread. */
    public record Bind(KeyCommandBind command, int keyCode, int modifiers, String text) { }

    /**
     * Immutable preference snapshot published to the render thread: the existing preferences the native views read,
     * every key binding, the minimum and extreme range colours of the field-of-fire display and the movement
     * envelope's sprint colour.
     */
    public record UiPreferences(float scale, String reportKeywords, String reportFilterKeywords,
          boolean minimapEnabled, boolean moveEnvelope, boolean conditionsVisible, boolean turnDetails,
          List<Bind> binds, int minRangeRgb, int extremeRangeRgb, int moveSprintRgb) {
        public UiPreferences {
            binds = List.copyOf(binds);
        }

        static UiPreferences capture() {
            var preferences = PreferenceManager.getClientPreferences();
            GUIPreferences gui = GUIPreferences.getInstance();
            return new UiPreferences(gui.getGUIScale(), preferences.getReportKeywords(),
                  preferences.getReportFilterKeywords(), gui.getMinimapEnabled(), gui.getMoveEnvelope(),
                  gui.getShowPlanetaryConditionsOverlay(), gui.getTurnDetailsOverlay(),
                  Stream.of(KeyCommandBind.values()).map(bind -> new Bind(bind, bind.key, bind.modifiers,
                        KeyCommandBind.getDesc(bind))).toList(),
                  FieldOfFireSprite.getFieldOfFireColor(RangeType.RANGE_MINIMUM).getRGB(),
                  FieldOfFireSprite.getFieldOfFireColor(RangeType.RANGE_EXTREME).getRGB(),
                  gui.getMoveSprintColor().getRGB());
        }
    }
    /**
     * One published snapshot. {@code status} and {@code panels} are always the newest capture; {@code panels} bundles
     * the HUD services' snapshots.
     */
    public record Frame(BoardScene scene, List<BoardScene.Animation> timeline, BoardScene.Context context,
          List<BoardScene.Command> globalCommands, String tooltip,
          BoardFocus centerRequest, long boardGeneration, String actorName,
          BoardAtmosphere.Settings scenarioAtmosphere, GpuReportLog.Snapshot reports,
          GpuBattleStatus.Snapshot status, GpuHudData panels) {
        /** This frame with the animation events queued since the previous frame was taken. */
        Frame withTimeline(List<BoardScene.Animation> events) {
            return new Frame(scene, events, context, globalCommands, tooltip, centerRequest, boardGeneration,
                  actorName, scenarioAtmosphere, reports, status, panels);
        }

        /** This frame showing {@code shown}, the scene the playback presents; this frame when it is its own. */
        Frame withScene(BoardScene shown) {
            return shown == scene ? this : new Frame(shown, timeline, context, globalCommands, tooltip,
                  centerRequest, boardGeneration, actorName, scenarioAtmosphere, reports, status, panels);
        }

        List<BoardScene.Movement> movements() {
            return timeline.stream().filter(BoardScene.Movement.class::isInstance).map(BoardScene.Movement.class::cast).toList();
        }

        List<BoardScene.Animation> animations() {
            return timeline.stream().filter(event -> !(event instanceof BoardScene.SceneUpdate)
                  && !(event instanceof BoardScene.Concealed)).toList();
        }
    }

    private final java.util.LinkedHashSet<java.util.UUID> receivedAttacks = new java.util.LinkedHashSet<>();

    private volatile BoardClientState view;
    private final Supplier<JComponent> phasePanel;
    private GpuBoardActions actions;
    volatile UiPreferences uiPreferences;
    private final Map<Image, BoardScene.Pixels> unitImages = new IdentityHashMap<>();
    private final UnitCamouflage camouflage = new UnitCamouflage();
    private final GpuReportLog reports = new GpuReportLog();
    private final GpuBattleStatus battleStatus = new GpuBattleStatus();
    // The HUD services. Each reads currentView() per call, so a board swap needs no rebinding.
    private final GpuMovePlan moves = new GpuMovePlan(this);
    private final GpuFireOrders fire = new GpuFireOrders(this);
    private final GpuPhysicalOptions physical = new GpuPhysicalOptions(this);
    private final GpuUnitRecord unitRecord = new GpuUnitRecord(this);
    private final GpuFirePreview preview;
    private final GpuChat chat;
    private final GpuToasts toasts = new GpuToasts(this);
    private final GpuLosResult los = new GpuLosResult(this);
    private final GpuPlayers players = new GpuPlayers(this);
    /** EDT: the last published panel bundle, kept while every part is equal. */
    private GpuHudData panels = GpuHudData.EMPTY;
    /** Set by the GL thread; each capture uses them only while the unit is still identified or owned. */
    private volatile int cardUnit = Entity.NONE;
    private volatile int focusUnit = Entity.NONE;
    /** Local presentation only: refreshes, board changes and modal previews all retain this time choice. */
    private final double atmosphereTimeSample = ThreadLocalRandom.current().nextDouble();
    private final BoardScene.PixelPool terrainImages = new BoardScene.PixelPool();
    private final List<BoardScene.Animation> pendingEvents = new ArrayList<>();
    private final Timer timer;
    private final GameListenerAdapter gameListener;
    private final BoardListenerAdapter boardListener;
    private final IPreferenceChangeListener preferenceListener = event -> {
        if (ClientPreferences.MAP_TILESET.equals(event.getName())) {
            dirtyTerrain();
        } else {
            refreshPreferences();
        }
    };
    private Board board;
    private List<BoardScene.Tile> tiles = List.of();
    private BoardFieldOfView fieldOfView = BoardFieldOfView.EMPTY;
    private boolean terrainDirty = true;
    private volatile boolean closed;
    /** EDT-confined stack of native modal dialogs; only the newest is shown, so they are answered LIFO. */
    private final Deque<PendingDialog> pendingDialogs = new ArrayDeque<>();
    /** Its newest unanswered dialog for the GL thread, read without any monitor and never part of a {@link Frame}. */
    private volatile GpuBoardWindow.DialogRequest dialog;
    private long dialogSequence;
    private PlanetaryConditionsDialog conditionsDialog;
    /** Swing-owned visual selection for reopening the editor; never written to the game. */
    private PlanetaryConditions previewConditions;
    private double previewTimeSample = atmosphereTimeSample;
    private Frame frame;
    private Coords contextCoords;
    private volatile Coords hoverCoords;
    private long boardGeneration;
    private volatile Rectangle visibleArea = new Rectangle(0, 0, 16, 16);
    private Rectangle capturedArea;
    private long capturedRevision = -1;
    private GamePhase capturedPhase;

    public void setVisibleArea(Rectangle area) {
        visibleArea = new Rectangle(area);
    }

    public void setHover(Coords coords) {
        hoverCoords = coords;
    }

    BoardClientState currentView() {
        return view;
    }

    public GpuBoardSource(BoardClientState view, Supplier<JComponent> phasePanel) {
        requireSwingThread();
        this.view = view;
        this.phasePanel = phasePanel;
        GUIPreferences preferences = GUIPreferences.getInstance();
        uiPreferences = UiPreferences.capture();
        actions = new GpuBoardActions(view, phasePanel, () -> !acceptsInput() || this.view != view, this::refresh);
        boardListener = new BoardListenerAdapter() {
            @Override
            public void boardNewBoard(BoardEvent event) {
                dirtyTerrain();
            }

            @Override
            public void boardChangedHex(BoardEvent event) {
                dirtyTerrain();
            }

            @Override
            public void boardChangedAllHexes(BoardEvent event) {
                dirtyTerrain();
            }
        };
        gameListener = new GameListenerAdapter() {
            @Override
            public void gameReport(megamek.common.event.GameReportEvent event) {
                onSwing(() -> {
                    if (!closed) {
                        captureReports();
                        reports.live(event.getReport(), view.game.getRoundCount(), view.game.getPhase());
                        refresh();
                    }
                });
            }

            @Override
            public void gameAttackResolved(megamek.common.event.GameAttackResolvedEvent event) {
                BoardClientState eventView = GpuBoardSource.this.view;
                onSwing(() -> {
                    if (isCurrentView(eventView)) {
                        captureCombat(event);
                    }
                });
            }

            @Override
            public void gameEntityChange(GameEntityChangeEvent event) {
                BoardClientState eventView = GpuBoardSource.this.view;
                // Snapshot the packet's path before leaving its callback; playback owns the immutable copy.
                List<UnitLocation> path = event.getMovePath() == null ? List.of() : List.copyOf(event.getMovePath());
                int entityId = event.getEntity().getId();
                EntityMovementType type = event.getEntity().moved;
                int moveMP = movementMP(event.getEntity(), type);
                Entity old = event.getOldEntity();
                UnitLocation start = old == null || old.getPosition() == null ? null
                      : new UnitLocation(old.getId(), old.getPosition(), old.getFacing(), old.getElevation(),
                            old.getBoardId(), old.getProneCause(), UnitLocation.Form.capture(old), old.getFallSide(), old.isHullDown());
                int startAltitude = old == null ? 0 : old.getAltitude();
                Entity takeoff = old == null ? event.getEntity() : old;
                int jumpMP = type == EntityMovementType.MOVE_JUMP ? takeoff.getAnyTypeMaxJumpMP() : 0;
                onSwing(() -> {
                    if (isCurrentView(eventView)) {
                        if (path.isEmpty()) {
                            refresh();
                        } else {
                            captureMovement(entityId, start, path, type, jumpMP, startAltitude, moveMP);
                        }
                    }
                });
            }

            @Override
            public void gameEntityNew(megamek.common.event.entity.GameEntityNewEvent event) {
                onSwing(GpuBoardSource.this::refresh);
            }

            @Override
            public void gameEntityRemove(megamek.common.event.entity.GameEntityRemoveEvent event) {
                BoardClientState eventView = GpuBoardSource.this.view;
                int condition = event.getEntity().getRemovalCondition();
                int id = event.getEntity().getId(), boardId = event.getEntity().getBoardId();
                onSwing(() -> {
                    if (!isCurrentView(eventView)) { return; }
                    if (condition == megamek.common.interfaces.IEntityRemovalConditions.REMOVE_UNKNOWN) {
                        queueAnimation(new BoardScene.Concealed(id, boardId));
                    }
                    refresh();
                });
            }
        };
        // Everything that registers a listener comes last, next to the failure cleanup below.
        preview = new GpuFirePreview(view.game, this::refresh, this::acceptsInput, GpuFirePreview.SLICE_NANOS);
        chat = new GpuChat(this);
        view.game.addGameListener(gameListener);
        PreferenceManager.getClientPreferences().addPreferenceChangeListener(preferenceListener);
        preferences.addPreferenceChangeListener(preferenceListener);
        timer = new Timer(100, event -> {
            this.view.advanceOverlays(100);
            refresh();
        });
        timer.setCoalesce(true);
        try {
            refresh();
            timer.start();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    static void requireSwingThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("GPU board game access must run on the Swing event thread");
        }
    }

    /** Packet handlers already run on Swing. Capturing here preserves each event inside a batched packet. */
    private static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private void dirtyTerrain() {
        if (SwingUtilities.isEventDispatchThread()) {
            terrainDirty = true;
            // Changed hexes also change what the fire preview predicts.
            preview.invalidate();
        } else {
            SwingUtilities.invokeLater(this::dirtyTerrain);
        }
    }

    /** Replaces the published preferences only when they changed, so the render thread can compare by identity. */
    private void refreshPreferences() {
        UiPreferences next = UiPreferences.capture();
        if (!next.equals(uiPreferences)) {
            uiPreferences = next;
        }
    }

    /** Selection is authoritative immediately, even before the next source timer publishes the new board. */
    private boolean isCurrentView(BoardClientState expected) {
        return !closed && !expected.isClosed() && view == expected && board != null && board == expected.getBoard()
              && (expected.getClientgui() == null
                    || expected.getClientgui().getCurrentBoardState().orElse(null) == expected);
    }

    private void captureMovement(int entityId, UnitLocation start, List<UnitLocation> path, EntityMovementType type,
            int jumpMP, int startAltitude, int movementMP) {
        if (closed || path.isEmpty()) {
            return;
        }
        Entity entity = view.game.getEntity(entityId);
        BoardClientState movingView = view;
        if (entity == null || !visible(entity) || sensorContact(entity)
              || path.stream().anyMatch(p -> p.boardId() != view.getBoardId() || p.coords() == null)) {
            refresh();
            return;
        }
        reports.moved(entity, start, path, type, view.game.getRoundCount());
        List<BoardScene.Waypoint> points = new ArrayList<>();
        // An airborne visual plays at the altitude the unit has now, and at the altitude it started the move at for
        // its first point and when it has just landed, so flying stays on the board through climbs and landings.
        int flightAltitude = entity.getAltitude() > 0 ? entity.getAltitude() : startAltitude;
        if (start != null && start.boardId() == view.getBoardId()) {
            points.add(pathWaypoint(entity, start.coords(), start.elevation(), start.facing(),
                  startAltitude > 0 ? startAltitude : flightAltitude, start.form()).withProneCause(start.proneCause())
                  .withFallSide(start.fallSide()).withHullDown(start.hullDown()));
        }
        for (UnitLocation location : path) {
            var observedForm = location.form() != null ? location.form() : points.isEmpty() ? null : points.getLast().form();
            BoardScene.Waypoint point = pathWaypoint(entity, location.coords(), location.elevation(),
                  location.facing(), flightAltitude, observedForm).withProneCause(location.proneCause() != null ? location.proneCause()
                        : points.isEmpty() ? null : points.getLast().proneCause())
                  .withFallSide(location.proneCause() != null ? location.fallSide()
                        : points.isEmpty() ? null : points.getLast().fallSide())
                  .withHullDown(location.hullDown() != null ? location.hullDown()
                        : points.isEmpty() ? null : points.getLast().hullDown());
            if (points.isEmpty() || !points.getLast().equals(point)) {
                points.add(point);
            }
        }
        // Older paths carry no conversion observations. Show their known final conversion at arrival.
        var finalForm = UnitLocation.Form.capture(entity);
        if (!points.isEmpty() && finalForm != null && path.stream().allMatch(location -> location.form() == null)) {
            var last = path.getLast();
            points.add(pathWaypoint(entity, last.coords(), last.elevation(), last.facing(), flightAltitude, finalForm)
                  .withProneCause(points.getLast().proneCause()).withFallSide(points.getLast().fallSide())
                  .withHullDown(points.getLast().hullDown()));
        }
        // Publish the final visible state and its movement together, so a frame cannot jump to the end first.
        Frame next = capture();
        if (!points.isEmpty()) {
            points.set(0, supportedEndpoint(points.getFirst(), frame, entityId));
            points.set(points.size() - 1, supportedEndpoint(points.getLast(), next, entityId));
        }
        synchronized (this) {
            if (view == movingView) {
                var captured = next.scene().units().stream()
                      .filter(unit -> unit.id() == entityId && !unit.sensorContact()).findFirst().orElse(null);
                queueMovement(entity, captured, points, type, jumpMP, movementMP);
            }
            publishScene(next, false);
        }
    }

    /** Split only at observed conversion steps; normal travel keeps its one continuous acceleration interval. */
    private void queueMovement(Entity entity, BoardScene.Unit captured, List<BoardScene.Waypoint> points,
          EntityMovementType type, int jumpMP, int movementMP) {
        float gravity = view.game.getPlanetaryConditions().getGravity();
        if (captured == null || captured.model() == null || points.stream().noneMatch(point -> point.form() != null)) {
            queueAnimation(new BoardScene.Movement(entity.getId(), view.getBoardId(), points, type, jumpMP, movementMP, captured, gravity));
            return;
        }
        List<BoardScene.Waypoint> leg = new ArrayList<>();
        var form = points.getFirst().form();
        for (var point : points) {
            if (form != null && point.form() != null && form.mode() != point.form().mode()) {
                var before = inForm(captured, entity, leg.getLast(), form);
                var legStart = leg.getFirst();
                if (leg.stream().anyMatch(step -> !step.samePose(legStart))) {
                    queueAnimation(new BoardScene.Movement(entity.getId(), view.getBoardId(), leg, type, jumpMP, movementMP, before, gravity));
                }
                var after = inForm(captured, entity, point, point.form());
                queueAnimation(new BoardScene.Conversion(view.getBoardId(), before, after));
                leg = new ArrayList<>();
                form = point.form();
            }
            leg.add(point);
            if (form == null) { form = point.form(); }
        }
        if (leg.size() > 1) {
            queueAnimation(new BoardScene.Movement(entity.getId(), view.getBoardId(), leg, type, jumpMP, movementMP,
                  inForm(captured, entity, leg.getLast(), form), gravity));
        }
    }

    private BoardScene.Unit inForm(BoardScene.Unit unit, Entity entity, BoardScene.Waypoint location, UnitLocation.Form form) {
        var model = UnitModelSelection.inForm(unit.model(), entity, unit.part(), MMStaticDirectoryManager.getMekTileset(), form);
        return new BoardScene.Unit(unit.id(), unit.part(), unit.name(), location, unit.image(), false, unit.annotations(),
              unit.height(), form == null ? unit.airborne() : form.airborne(), model, unit.outlineRgb(), unit.footprint());
    }

    /** Copy authorized, then-visible appearance once; no Entity reaches the GL thread. */
    private void captureCombat(megamek.common.event.GameAttackResolvedEvent event) {
        requireSwingThread();
        var result = event.result();
        Entity attacker = event.attacker();
        Entity target = event.target() instanceof Entity entity ? entity : null;
        if (closed || result.attacker().boardId() != view.getBoardId() || attacker == null
              || !visible(attacker) || sensorContact(attacker)
              || (result.targetType() == Targetable.TYPE_ENTITY && target == null)
              || (target != null && (!visible(target) || sensorContact(target)))
              || !receivedAttacks.add(result.id())) {
            return;
        }
        if (receivedAttacks.size() > 2048) {
            receivedAttacks.removeFirst();
        }
        reports.combat(result, attacker, event.target(), view.game.getRoundCount(), view.game.getPhase());
        var usedImages = new IdentityHashMap<Image, Boolean>();
        var firing = unit(attacker, -1, result.attacker().coords(), false, usedImages);
        if (result.shot() != null && result.shot().launch() != null) {
            firing = inForm(firing, attacker, waypoint(result.attacker().coords(), result.attacker().elevation(), result.attacker().facing()),
                  result.attacker().form());
        }
        var receiving = target == null ? null : unit(target, -1, result.target().coords(), false, usedImages);
        var destination = receiving == null
              ? waypoint(result.target().coords(), result.target().elevation(), result.target().facing())
              : receiving.location();
        // A resolved attack packet precedes its damage packets. This checkpoint closes the preceding action.
        Frame before = capture();
        synchronized (this) {
            publishScene(before, true);
            queueAnimation(new BoardScene.Combat(result, firing, receiving, destination));
        }
    }

    private synchronized void queueAnimation(BoardScene.Animation animation) {
        if (animation instanceof BoardScene.SceneUpdate
              && !pendingEvents.isEmpty() && pendingEvents.getLast() instanceof BoardScene.SceneUpdate) {
            pendingEvents.removeLast();
        }
        if (pendingEvents.size() >= UnitPlayback.MAX_PENDING_EVENTS) {
            pendingEvents.clear();
        }
        pendingEvents.add(animation);
        if (!(animation instanceof BoardScene.SceneUpdate) && !(animation instanceof BoardScene.Concealed)) {
            animationsQueued++;
            view.setMovingUnits(true);
        }
    }

    /** Consecutive captures share one checkpoint; animation boundaries are never coalesced. Swing owns capture. */
    private synchronized void publishScene(Frame next, boolean detectGear) {
        if (next.scene() == null) { pendingEvents.clear(); frame = next; return; }
        if (frame != null && frame.scene() == null) { frame = null; }
        if (detectGear && frame != null && frame.boardGeneration() == next.boardGeneration()) {
            Map<Integer, BoardScene.Unit> previous = new HashMap<>();
            frame.scene().units().stream().filter(unit -> !unit.sensorContact())
                  .forEach(unit -> previous.put(unit.id(), unit));
            for (var old : previous.values()) {
                Entity entity = view.game.getEntity(old.id());
                if (entity != null && (!visible(entity) || sensorContact(entity))) {
                    queueAnimation(new BoardScene.Concealed(old.id(), next.scene().boardId()));
                }
            }
            for (var unit : next.scene().units()) {
                var old = previous.get(unit.id());
                if (UnitConversion.changes(old, unit)) {
                    queueAnimation(new BoardScene.Conversion(next.scene().boardId(), old, unit));
                }
                if (old != null && !unit.sensorContact() && UnitMotion.changesGear(old.location(), unit.location())) {
                    Entity entity = view.game.getEntity(unit.id());
                    queueAnimation(new BoardScene.Movement(unit.id(), next.scene().boardId(),
                          List.of(old.location(), unit.location()), EntityMovementType.MOVE_SAFE_THRUST, 0,
                          entity == null ? 0 : movementMP(entity, EntityMovementType.MOVE_SAFE_THRUST), unit));
                }
            }
        }
        if (frame == null || frame.boardGeneration() != next.boardGeneration() || !next.scene().samePlaybackState(frame.scene())
              || (!pendingEvents.isEmpty() && !(pendingEvents.getLast() instanceof BoardScene.SceneUpdate))) {
            queueAnimation(new BoardScene.SceneUpdate(next.scene()));
        }
        frame = next;
    }

    /** Copy the game's current capability once at the event boundary; the renderer never recalculates MP rules. */
    static int movementMP(Entity entity, EntityMovementType type) {
        if (entity instanceof Aero aero && aero.isAirborne()) {
            return Math.max(1, aero.getCurrentVelocity());
        }
        return switch (type) {
            case MOVE_JUMP -> entity.getJumpMP();
            case MOVE_SPRINT, MOVE_VTOL_SPRINT -> entity.getSprintMP();
            case MOVE_RUN, MOVE_VTOL_RUN, MOVE_SUBMARINE_RUN, MOVE_OVER_THRUST, MOVE_SKID -> entity.getRunMP();
            default -> entity.getWalkMP();
        };
    }

    /** A landed path endpoint uses the same whole-footprint support as its captured hull, not just its centre hex. */
    private static BoardScene.Waypoint supportedEndpoint(BoardScene.Waypoint point, Frame frame, int id) {
        if (frame == null || frame.scene() == null || point.aeroState() != BoardScene.AeroState.LANDED) {
            return point;
        }
        return frame.scene().units().stream().filter(unit -> unit.id() == id && !unit.sensorContact()
                    && unit.footprint().size() > 1 && unit.location().coords().equals(point.coords())
                    && unit.location().aeroState() == BoardScene.AeroState.LANDED)
              .map(unit -> new BoardScene.Waypoint(point.coords(), unit.location().elevation(), point.facing(),
                    point.proneCause(), point.aeroState(), unit.footprint(), point.form(), point.fallSide(), point.hullDown())).findFirst().orElse(point);
    }

    public void refresh() {
        requireSwingThread();
        if (!closed) {
            Frame next = capture();
            synchronized (this) {
                publishScene(next, true);
            }
        }
    }

    public synchronized Frame takeFrame() {
        Frame result = frame.withTimeline(List.copyOf(pendingEvents));
        pendingEvents.clear();
        animationsTaken = animationsQueued;
        return result;
    }

    private Frame capture() {
        refreshPreferences();
        boolean changedState = false;
        if (view.getClientgui() != null) {
            BoardClientState selectedView = view.getClientgui().getCurrentBoardState().orElse(view);
            if (selectedView != view) {
                changedState = true;
                view.setMovingUnits(false);
                view.setVisibleArea(() -> new double[] { 0, 0, 1, 1 });
                view.releasePlanarCapture();
                view = selectedView;
                contextCoords = null;
                unitImages.clear();
                terrainDirty = true;
                synchronized (this) {
                    pendingEvents.clear();
                }
            }
        }
        Board current = view.isClosed() ? null : view.getBoard();
        if (changedState || current != board) {
            if (board != null) { board.removeBoardListener(boardListener); }
            board = current;
            boardGeneration++;
            BoardClientState owner = view;
            long generation = boardGeneration;
            // Commands captured for an older board or state go stale, also when the same state gets a new board.
            actions = new GpuBoardActions(owner, phasePanel,
                  () -> !acceptsInput() || !isCurrentView(owner) || generation != boardGeneration, this::refresh);
            view.setMovingUnits(false);
            previewConditions = null;
            previewTimeSample = atmosphereTimeSample;
            synchronized (this) {
                pendingEvents.clear();
                receivedAttacks.clear();
            }
            if (board != null) { board.addBoardListener(boardListener); }
            terrainDirty = true;
        }
        if (board == null) {
            tiles = List.of();
            terrainImages.clear();
            unitImages.clear();
            fieldOfView = BoardFieldOfView.EMPTY;
            return new Frame(null, List.of(), null, List.of(), "",
                  new BoardFocus(0, null), boardGeneration, "", BoardAtmosphere.DEFAULTS,
                  GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, GpuHudData.EMPTY);
        }
        view.setVisibleArea(() -> {
            Rectangle area = visibleArea;
            Board currentBoard = view.getBoard();
            return area == null ? new double[] { 0, 0, 1, 1 } : new double[] {
                  area.x / (double) currentBoard.getWidth(), area.y / (double) currentBoard.getHeight(),
                  (area.x + area.width) / (double) currentBoard.getWidth(),
                  (area.y + area.height) / (double) currentBoard.getHeight() };
        });
        boolean changedTerrain = terrainDirty;
        if (terrainDirty) {
            List<BoardScene.Tile> nextTiles = new ArrayList<>(Collections.nCopies(board.getWidth() * board.getHeight(), null));
            view.capturePlanarHexes(new Rectangle(0, 0, board.getWidth(), board.getHeight()), false,
                  hex -> nextTiles.set(hex.coords().getX() * board.getHeight() + hex.coords().getY(), tile(hex, null)));
            tiles = List.copyOf(nextTiles);
            terrainDirty = false;
        }
        Rectangle area = visibleArea;
        long revision = view.getPlanarRevision();
        if (changedTerrain || revision != capturedRevision || view.game.getPhase() != capturedPhase || !area.equals(capturedArea)) {
            List<BoardScene.Tile> painted = new ArrayList<>(tiles);
            for (int index = 0; index < tiles.size(); index++) {
                BoardScene.Tile old = tiles.get(index);
                if (old.tactical() != null && !area.contains(old.coords().getX(), old.coords().getY())) {
                    painted.set(index, new BoardScene.Tile(old.coords(), old.elevation(), old.waterDepth(), old.frozen(),
                          old.roadExits(), old.surface(), old.ground(), old.normals(), old.decals(), old.decalsWithoutLimbs(),
                          null, old.features(), old.text(), old.liquid(), old.foliage()));
                }
            }
            view.capturePlanarTactical(area, hex -> {
                int index = hex.coords().getX() * board.getHeight() + hex.coords().getY();
                BoardScene.Tile old = tiles.get(index);
                BoardScene.Tile next = new BoardScene.Tile(old.coords(), old.elevation(), old.waterDepth(), old.frozen(),
                      old.roadExits(), old.surface(), old.ground(), old.normals(), old.decals(), old.decalsWithoutLimbs(),
                      terrainImages.capture(hex.tactical(), old.tactical()), old.features(), hex.text(), old.liquid(), old.foliage());
                if (!next.equals(old)) {
                    painted.set(index, next);
                }
            });
            if (!painted.equals(tiles)) {
                tiles = List.copyOf(painted);
            }
            terrainImages.retain(tiles);
            fieldOfView = view.captureFieldOfView(area);
            capturedArea = area;
            capturedRevision = revision;
            capturedPhase = view.game.getPhase();
        }
        List<BoardScene.Unit> units = new ArrayList<>();
        camouflage.begin();
        Map<Image, Boolean> usedImages = new IdentityHashMap<>();
        for (Entity entity : view.game.getEntitiesVector()) {
            if (!visible(entity)) {
                continue;
            }
            boolean sensor = sensorContact(entity);
            boolean wholeModel = !sensor && GpuUnitModels.ENABLED
                  && MMStaticDirectoryManager.getMekTileset().modelFor(entity, -1) != null;
            if (entity.getSecondaryPositions().isEmpty() || sensor || wholeModel) {
                units.add(unit(entity, -1, entity.getPosition(), sensor, usedImages));
            } else {
                entity.getSecondaryPositions().forEach((part, coords) ->
                      units.add(unit(entity, part, coords, false, usedImages)));
            }
        }
        if (GpuUnitModels.ENABLED && GUIPreferences.getInstance().getShowWrecks()) {
            // BoardClientState already owns which removed units leave wrecks, including infantry/CVEP exceptions.
            var live = units.stream().map(BoardScene.Unit::id).collect(Collectors.toSet());
            view.getWrecks().stream()
                  .filter(entity -> !live.contains(entity.getId()) && visible(entity) && !sensorContact(entity))
                  .forEach(entity -> units.add(wreck(entity, usedImages)));
        }
        JComponent panel = phasePanel.get();
        MovePath path = panel instanceof MovementDisplay movement ? movement.getPlannedMovement() : null;
        int actorId = actions.actorId();
        GameTurn activeTurn = activeTurn();
        GpuBattleStatus.Snapshot status = battleStatus.capture(view.game, view.getLocalPlayer(), activeTurn,
              actorId, this::visible, this::identified,
              entity -> unitImage(entity, -1, !identified(entity), usedImages));
        unitImages.keySet().retainAll(usedImages.keySet());
        camouflage.retain();
        List<BoardScene.Command> commands = actions.phaseCommands();
        BoardScene.Context nextContext = contextCoords == null ? null : new BoardScene.Context(contextCoords,
              actions.contextCommands(contextCoords));
        List<BoardScene.Command> nextGlobal = new ArrayList<>(actions.globalCommands());
        if (view.getClientgui() != null) {
            var gui = view.getClientgui();
            List<BoardScene.Command> boards = gui.boardStates().stream().map(boardView -> new BoardScene.Command(
                  "Map " + boardView.getBoardId(), true, () -> SwingUtilities.invokeLater(() -> {
                      if (acceptsInput()) {
                          gui.showBoardView(boardView.getBoardId());
                          refresh();
                      }
                  }))).toList();
            nextGlobal.add(new BoardScene.Command("boards", "Maps", "", true, false, boards, () -> { }));
        }
        String nextTooltip = GpuBoardActions.plainText(view.getHexTooltip(contextCoords == null ? hoverCoords : contextCoords));
        // The movement plan before the tactical capture, which leaves out what the plan draws (G4).
        Coords hover = hoverCoords;
        GpuMovePlan.Snapshot move = moves.capture(panel, hover);
        Point light = view.getTerrainLightDirection();
        BoardScene scene = new BoardScene(view.getBoardId(), board.getWidth(), board.getHeight(), tiles, units,
              List.of(), actorId, view.game.getPhase().localizedName(), commands,
              light == null || light.x == 0 && light.y == 0 ? null : new BoardScene.Light(light.x, -light.y),
              firingLines(), view.getWeaponRangeSprites().stream().map(sprite -> new BoardScene.RangeBorder(
                    sprite.getPosition(), sprite.getBorders(),
                    FieldOfFireSprite.getFieldOfFireColor(sprite.getRangeBracket()).getRGB(),
                    FieldOfFireSprite.getRangeText(sprite.getRangeBracket()))).toList(),
              view.getBoardMarkers(), tacticalGeometry(move.planner()),
              view.getWeaponRangeTextSprites().stream().map(sprite -> new BoardScene.RangeLabel(sprite.getPosition(),
                    FieldOfFireSprite.getFieldOfFireColor(sprite.getRangeBracket()).getRGB(),
                    FieldOfFireSprite.getRangeText(sprite.getRangeBracket()))).toList(), fieldOfView,
              fire.rangeBands(panel));
        // The report log before the chat, whose round lines read it with the status (plan O6).
        GpuReportLog.Snapshot log = captureReports();
        chat.roundLines(status, log);
        // The other HUD panels, in this order. The fire preview follows the own focus unit, else the acting unit; the
        // fire orders show its draft read-only on another player's turn.
        int focus = checked(focusUnit, this::owned);
        GpuHudData nextPanels = new GpuHudData(actions.phaseInfo(commands), move,
              fire.capture(panel, hover, focus), physical.capture(panel),
              unitRecord.capture(checked(cardUnit, this::identified)),
              preview.capture(view.getLocalPlayer(), activeTurn, focus == Entity.NONE ? actorId : focus, path,
                    this::visible, this::sensorContact),
              chat.capture(), toasts.capture(), los.capture(), players.capture());
        if (!nextPanels.equals(panels)) {
            panels = nextPanels;
        }
        Entity actor = view.game.getEntity(actorId);
        boolean knownActor = actor != null && identified(actor);
        return new Frame(scene, List.of(), nextContext, List.copyOf(nextGlobal), nextTooltip,
              view.getCenterRequest(), boardGeneration, knownActor ? actor.getShortName() : "",
              atmosphereFor(view.game.getPlanetaryConditions(), board.isSpace()), log, status, panels);
    }

    /**
     * EDT: the board state's tactical capture without MegaMek's sprites the HUD draws itself (G4, a consumer filter):
     * the firing solutions always (the board labels' to-hit badges), and the movement envelope while the plan draws
     * the route and its envelope. A display's own hex pick (an escape pod's landing, a bridge) is no plan, so its
     * envelope sprites, MegaMek's pick highlight, stay. The sprites are hidden only for this capture, on the thread
     * that paints the classic board.
     */
    private BoardTactical tacticalGeometry(boolean planner) {
        List<Sprite> skipped = view.getAllSprites().stream().filter(sprite -> !sprite.isHidden()
              && (sprite instanceof FiringSolutionSprite || planner && sprite instanceof MovementEnvelopeSprite))
              .toList();
        skipped.forEach(sprite -> sprite.setHidden(true));
        try {
            return view.captureTacticalGeometry();
        } finally {
            skipped.forEach(sprite -> sprite.setHidden(false));
        }
    }

    /** EDT: the log of the game's reports, with the artillery rounds in the air the Rounds in the Air window lists. */
    private GpuReportLog.Snapshot captureReports() {
        return reports.capture(view.game.getAllReports(), view.game.getRoundCount(), view.game.getPhase(),
              RoundsInAirDialog.rows(view.game), this::reportIcon);
    }

    /** EDT: {@code id} while that unit exists and passes {@code check} now, else {@code Entity.NONE}. */
    private int checked(int id, Predicate<Entity> check) {
        Entity entity = view.game.getEntity(id);
        return entity != null && check.test(entity) ? id : Entity.NONE;
    }

    /** The turn the client lets the local player act in now (Client.isMyTurn/getMyTurn); null without a client. */
    private GameTurn activeTurn() {
        var client = view.getClientgui() == null ? null : view.getClientgui().getClient();
        return client != null && client.isMyTurn() ? client.getMyTurn() : null;
    }

    private Entity reportEntity(int id) {
        Entity entity = view.game.getEntityFromAllSources(id);
        return entity != null && EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, entity)
              && !sensorContact(entity) ? entity : null;
    }

    private BoardScene.Pixels reportIcon(int id) {
        Entity entity = reportEntity(id);
        if (entity == null) {
            return null;
        }
        Image image = view.getTilesetManager().imageFor(entity);
        return image == null ? null : BoardScene.Pixels.copy(image);
    }

    /** Raise the classic unit overview's SELECT_UNIT event; the phase display decides what selecting means. */
    void selectUnit(int id) {
        SwingUtilities.invokeLater(() -> {
            Entity entity = acceptsInput() ? view.game.getEntity(id) : null;
            if (entity != null && identified(entity)) {
                view.processBoardViewEvent(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, id));
                refresh();
            }
        });
    }

    /** Centre on an own or visible unit as the classic unit overview does; an own unit's other board is shown. */
    void locateUnit(int id) {
        SwingUtilities.invokeLater(() -> {
            Entity entity = acceptsInput() ? view.game.getEntity(id) : null;
            if (entity != null && (owned(entity) || visible(entity))) {
                if (view.getClientgui() != null) {
                    view.getClientgui().centerOnUnit(entity);
                } else {
                    centerOnBoard(entity);
                }
                refresh();
            }
        });
    }

    private void centerOnBoard(Entity entity) {
        if (entity.isDeployed() && !entity.isOffBoard() && entity.getPosition() != null
              && entity.getBoardId() == view.getBoardId()) {
            view.centerOn(entity);
        }
    }

    boolean owned(Entity entity) {
        return view.getLocalPlayer() != null && entity.getOwnerId() == view.getLocalPlayer().getId();
    }

    /** Own units, and visible units that are not anonymous sensor contacts, may be named and selected. */
    boolean identified(Entity entity) {
        return owned(entity) || visible(entity) && !sensorContact(entity);
    }

    boolean visible(Entity entity) {
        return entity.getPosition() != null && entity.getBoardId() == view.getBoardId()
              && EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, entity);
    }

    private List<BoardScene.FiringLine> firingLines() {
        List<BoardScene.FiringLine> result = new ArrayList<>();
        for (var sprite : view.getAttackSprites()) {
            Entity attacker = sprite.getAttackingEntity();
            Targetable target = sprite.getTargetedEntity();
            if (sprite.isHidden() || attacker.getPosition() == null || target.getPosition() == null
                  || !board.contains(attacker.getPosition()) || !board.contains(target.getPosition())) {
                continue;
            }
            for (EntityAction action : sprite.getActions()) {
                // ArtilleryAttackAction also represents direct artillery declared in the firing phase.
                boolean indirect = action instanceof ArtilleryAttackAction && switch (view.game.getPhase()) {
                    case TARGETING, TARGETING_REPORT, OFFBOARD, OFFBOARD_REPORT -> true;
                    default -> false;
                };
                if (action instanceof WeaponAttackAction weaponAttack) {
                    Entity weaponEntity = view.game.getEntity(action.getEntityId());
                    var weapon = weaponEntity == null ? null : weaponEntity.getEquipment(weaponAttack.getWeaponId());
                    indirect |= weapon != null && weapon.curMode().isIndirect();
                }
                result.add(new BoardScene.FiringLine(firingEndpoint(attacker), firingEndpoint(target),
                      attacker.getOwner().getColour().getColour().getRGB(), indirect, attacker.getId(),
                      target instanceof Entity entity ? entity.getId() : Entity.NONE));
            }
        }
        // Multiple weapons on one target share a trace, but direct and indirect fire remain distinct.
        return result.stream().distinct().toList();
    }

    private BoardScene.Waypoint firingEndpoint(Targetable target) {
        Coords coords = target.getPosition();
        if (target instanceof Entity entity) {
            if (sensorContact(entity)) {
                return waypoint(coords, 0.5f, 0);
            }
            return new BoardScene.Waypoint(coords, flightLevel(entity, coords) + (entity.height() + 1) * 0.5f, 0);
        }
        return waypoint(coords, target.getElevation() + Math.max(0.15f, target.getHeight() * 0.5f), 0);
    }

    private BoardScene.Tile tile(BoardArtwork.HexImage pixels, BoardScene.Tile previous) {
        Hex hex = board.getHex(pixels.coords());
        return new BoardScene.Tile(pixels.coords(), hex.getLevel(),
              hex.containsTerrain(Terrains.WATER) ? Math.max(0, hex.terrainLevel(Terrains.WATER)) : -1,
              hex.containsTerrain(Terrains.ICE),
              hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0,
              BoardFeatures.surface(hex),
              terrainImages.capture(pixels.terrain(), previous == null ? null : previous.ground()),
              terrainImages.capture(pixels.normals(), previous == null ? null : previous.normals()),
              terrainImages.captureOverlay(pixels.decals(), previous == null ? null : previous.decals()),
              terrainImages.capture(pixels.decalsWithoutLimbs(), previous == null ? null : previous.decalsWithoutLimbs()),
              terrainImages.capture(pixels.tactical(), previous == null ? null : previous.tactical()),
              BoardFeatures.capture(hex, pixels.coords(), pixels.structureModels()), pixels.text(), BoardLiquid.capture(hex),
              terrainImages.captureOverlay(pixels.foliage(), previous == null ? null : previous.foliage()));
    }

    boolean sensorContact(Entity entity) {
        return EntityVisibilityUtils.onlyDetectedBySensors(view.getLocalPlayer(), entity);
    }

    private BoardScene.Unit wreck(Entity entity, Map<Image, Boolean> usedImages) {
        var captured = unit(entity, -1, entity.getPosition(), false, usedImages);
        var model = captured.model();
        var state = model == null ? UnitModelState.capture(entity) : model.state();
        var pose = state.pose();
        var dead = new UnitModelState(state.structure(), state.appearance(),
              new UnitModelState.Pose(pose.proneCause(), pose.facing(), pose.secondaryFacing(), pose.form(), true,
                    pose.armsFlipped(), pose.hullDown()));
        model = model == null ? new BoardScene.UnitModel("", "", "", 1, 0, BoardScene.LocationDamage.NONE, dead)
              : new BoardScene.UnitModel(model.asset(), model.fallback(), model.variant(), model.figures(), model.twist(), model.damage(), dead);
        var pixels = retained(view.getTilesetManager().wreckMarkerFor(entity, -1), usedImages);
        return new BoardScene.Unit(captured.id(), -1, captured.name(), captured.location(), pixels, false, null,
              captured.height(), captured.airborne(), model, captured.outlineRgb(), captured.footprint());
    }

    /** One copy per AWT image, shared by identity by every frame and HUD list that shows it while it is in use. */
    private BoardScene.Pixels retained(Image image, Map<Image, Boolean> usedImages) {
        usedImages.put(image, true);
        return unitImages.computeIfAbsent(image, BoardScene.Pixels::copy);
    }

    /** The tileset artwork of a unit or part, or the radar blip that stands for a sensor contact. */
    private BoardScene.Pixels unitImage(Entity entity, int part, boolean sensor, Map<Image, Boolean> usedImages) {
        return retained(sensor ? view.getRadarBlipImage() : view.getTilesetManager().textureFor(entity, part), usedImages);
    }

    private BoardScene.Unit unit(Entity entity, int part, Coords coords, boolean sensor,
          Map<Image, Boolean> usedImages) {
        BoardScene.Pixels pixels = unitImage(entity, part, sensor, usedImages);
        int facing = sensor ? 0 : view.getTilesetManager().facingFor(entity);
        boolean airborne = !sensor && airborne(entity);
        List<Coords> footprint = !sensor && part < 0 ? entity.getOccupiedCoords().stream()
              .sorted(java.util.Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY)).toList() : List.of(coords);
        if (footprint.isEmpty()) {
            footprint = List.of(coords);
        }
        BoardScene.Waypoint location = airborne
              ? new BoardScene.Waypoint(coords, flightLevel(entity, coords), facing)
              : footprint.size() > 1 && UnitFootprint.terrainSupported(entity.getMovementMode())
                    ? new BoardScene.Waypoint(coords, UnitFootprint.support(board, coords, footprint, entity.getElevation()), facing)
                    : waypoint(coords, sensor ? 0 : entity.getElevation(), facing);
        if (!sensor) {
            location = location.withAeroState(aeroState(entity, entity.getElevation(), airborne))
                  .withFallSide(entity instanceof Mek ? entity.getFallSide() : null).withHullDown(entity.isHullDown());
            if (location.aeroState() != null) {
                location = location.withFootprint(footprint);
            }
        }
        Color outline = sensor ? new Color(GpuMarkers.SENSOR_RGB)
              : GUIPreferences.getInstance().getTeamColoring() && view.getLocalPlayer() != null
                    ? UIUtil.teamColor(entity.getOwner(), view.getLocalPlayer())
                    : entity.getOwner().getColour().getColour(false);
        // No unit annotations: the HUD's nameplates are the units' labels (X3).
        return new BoardScene.Unit(entity.getId(), part, sensor ? Messages.getString("BoardView1.sensorReturn")
              : entity.getShortName(),
              location, pixels, sensor,
              null,
              sensor ? 1 : entity.height() + 1, airborne,
              GpuUnitModels.ENABLED
                    ? camouflage.resolve(UnitModelSelection.capture(entity, part, sensor, MMStaticDirectoryManager.getMekTileset(),
                          sensor ? 0 : UnitModelSelection.twist(entity.getFacing(), facing))) : null,
              outline.getRGB(), footprint);
    }

    private BoardScene.Waypoint waypoint(Coords coords, float relativeElevation, int facing) {
        Hex hex = board == null ? null : board.getHex(coords);
        return new BoardScene.Waypoint(coords, relativeElevation + (hex == null ? 0 : hex.getLevel()), facing);
    }

    /**
     * True while the unit is flying. Aerospace units carry an altitude; a parked aerodyne keeps its AERODYNE movement
     * mode, so {@code isAirborne()} alone would call it airborne, but the altitude decides. VTOLs and WiGEs hover on
     * their hex-relative elevation and are landed at elevation 0.
     */
    private static boolean airborne(Entity entity) {
        return entity.getAltitude() > 0 || entity.isAirborneVTOLorWIGE();
    }

    /**
     * Absolute level a flying visual floats at, with its token staying its own height above it. Aerospace altitude is
     * already absolute above the board, exactly as the LOS height conversion treats it, and is the only height
     * available for it because {@code Aero.getElevation()} reports the airborne sentinel while flying. VTOL and WiGE
     * elevation is relative to the hex below them.
     */
    private float flightLevel(Entity entity, Coords coords) {
        if (entity.getAltitude() > 0) {
            return entity.getAltitude();
        }
        Hex hex = board == null ? null : board.getHex(coords);
        return entity.getElevation() + (hex == null ? 0 : hex.getLevel());
    }

    /**
     * Playback position of one movement path point. An aerospace reports the airborne elevation sentinel (999) in
     * every step it takes while flying instead of a real level; those steps play at the given flight altitude, while a
     * step carrying a real elevation (an aerospace taxiing, or set down on its landing hex) keeps it. Every other unit
     * keeps its own per-step elevation, so VTOL and WiGE climbs and descents still animate.
     */
    private BoardScene.Waypoint pathWaypoint(Entity entity, Coords coords, float elevation, int facing,
          int flightAltitude, UnitLocation.Form form) {
        boolean aero = form == null ? entity.isAero() : form.aero();
        if (form != null && form.altitude() > 0) { flightAltitude = form.altitude(); }
        BoardScene.Waypoint point = aero && elevation >= Aero.AERO_EFFECTIVE_ELEVATION && flightAltitude > 0
              ? new BoardScene.Waypoint(coords, flightAltitude, facing)
              : waypoint(coords, elevation, facing);
        // A real path elevation describes the displayed step; the final Entity may already have landed/taken off.
        return point.withAeroState(aeroState(aero, elevation, false)).withForm(form);
    }

    private BoardScene.AeroState aeroState(Entity entity, float relativeElevation, boolean flying) {
        return aeroState(entity.isAero(), relativeElevation, flying);
    }

    private BoardScene.AeroState aeroState(boolean aero, float relativeElevation, boolean flying) {
        if (!aero) {
            return null;
        }
        if (board.isSpace() || flying || relativeElevation >= Aero.AERO_EFFECTIVE_ELEVATION) {
            return BoardScene.AeroState.AIRBORNE;
        }
        return relativeElevation == 0 ? BoardScene.AeroState.LANDED : BoardScene.AeroState.ELEVATED;
    }

    /** Swing owns the dialog and its conditions copy; completion receives immutable settings, or null on cancel. */
    void editPlanetaryConditions(Consumer<BoardAtmosphere.Settings> completed) {
        SwingUtilities.invokeLater(() -> {
            if (!acceptsInput() || conditionsDialog != null) {
                completed.accept(null);
                return;
            }
            BoardClientState initialView = view;
            Board initialBoard = board;
            BoardAtmosphere.Settings settings = null;
            try {
                var owner = view.getClientgui() == null ? null
                      : view.getClientgui().getFrame();
                var initial = new PlanetaryConditions(previewConditions == null ? view.game.getPlanetaryConditions() : previewConditions);
                conditionsDialog = new PlanetaryConditionsDialog(owner instanceof JFrame frame ? frame : null, initial);
                conditionsDialog.setAlwaysOnTop(true);
                if (conditionsDialog.showDialog() && !closed && view == initialView && board == initialBoard) {
                    var selected = conditionsDialog.getConditions();
                    if (selected.getLight() != initial.getLight()) { previewTimeSample = atmosphereTimeSample; }
                    previewConditions = new PlanetaryConditions(selected);
                    settings = BoardAtmosphere.fromScenario(previewConditions, initialBoard.isSpace(), previewTimeSample);
                }
            } finally {
                if (conditionsDialog != null) { conditionsDialog.dispose(); }
                conditionsDialog = null;
                completed.accept(settings);
            }
        });
    }

    /** Map a conditions snapshot or preview using this window's fixed visual time selection. */
    BoardAtmosphere.Settings atmosphereFor(PlanetaryConditions conditions, boolean inSpace) {
        return BoardAtmosphere.fromScenario(conditions, inSpace, atmosphereTimeSample);
    }

    /** Presets own their conditions and reuse the same immutable time sample as the scenario/editor. */
    BoardAtmosphere.Settings atmosphereFor(AtmospherePreset preset) {
        return preset.settings(atmosphereTimeSample);
    }

    /** Remember a complete visual selection on Swing, alongside the immediate immutable GL settings. */
    BoardAtmosphere.Settings preview(AtmospherePreset preset) {
        onSwing(() -> {
            if (closed) { return; }
            previewConditions = preset.conditions();
            previewTimeSample = preset.timeSample(atmosphereTimeSample);
        });
        return atmosphereFor(preset);
    }

    void resetConditionsPreview() {
        onSwing(() -> {
            if (closed) { return; }
            previewConditions = null;
            previewTimeSample = atmosphereTimeSample;
        });
    }

    /** One waiting {@link #ask}: its nested Swing loop keeps the caller's frame on the stack until it is answered. */
    private final class PendingDialog {
        private final GpuBoardWindow.DialogRequest request;
        private final SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        private GpuBoardWindow.DialogAnswer answer;

        private PendingDialog(GpuBoardWindow.DialogRequest request) {
            this.request = request;
        }

        /**
         * EDT: the first answer wins and the native window stops showing it at once. The loop ends when the event that
         * completes it returns, and its caller resumes only after every newer dialog's caller has returned.
         */
        private void complete(GpuBoardWindow.DialogAnswer result) {
            if (answer == null) {
                answer = result;
                loop.exit();
                publishDialog();
            }
        }
    }

    /**
     * EDT: publishes the request to the native window and pumps Swing events in a nested loop until the GL thread
     * answers it or this source closes, as a modal JDialog waits. The EDT never waits on the GL thread. Returns null
     * when this source is already closed, so the caller can use its Swing dialog.
     */
    GpuBoardWindow.DialogAnswer ask(GpuBoardWindow.DialogRequest request) {
        requireSwingThread();
        if (Thread.holdsLock(this)) {
            // The GL thread takes this monitor in takeFrame(); it could never show the dialog to be answered.
            throw new IllegalStateException("A native dialog must not wait while holding the board source monitor");
        }
        if (closed) {
            return null;
        }
        PendingDialog pending = new PendingDialog(request.withId(++dialogSequence));
        pendingDialogs.push(pending);
        publishDialog();
        try {
            pending.loop.enter();
        } finally {
            pendingDialogs.remove(pending);
            publishDialog();
        }
        return pending.answer == null ? GpuBoardWindow.DialogAnswer.cancelled(request) : pending.answer;
    }

    /** EDT: shows the newest dialog that still waits for an answer; an answered outer one is never shown again. */
    private void publishDialog() {
        dialog = pendingDialogs.stream().filter(pending -> pending.answer == null).map(pending -> pending.request)
              .findFirst().orElse(null);
    }

    /** GL thread: never blocks. The answer completes its dialog on the EDT, inside that dialog's nested loop. */
    void answer(long dialogId, GpuBoardWindow.DialogAnswer answer) {
        Objects.requireNonNull(answer);
        SwingUtilities.invokeLater(() -> pendingDialogs.stream().filter(pending -> pending.request.id() == dialogId)
              .findFirst().ifPresent(pending -> pending.complete(answer)));
    }

    /** GL thread: the newest pending dialog, or null. A volatile read without any monitor. */
    GpuBoardWindow.DialogRequest dialog() {
        return dialog;
    }

    /**
     * EDT guard at the start of every command from the native window: false once closed, while any native ask of
     * this source is still on the call stack, or while a Swing modal dialog is shown. Input queued before a modal
     * appeared is therefore dropped instead of running inside its nested loop. Non-modal windows (Help, the
     * accessibility window) do not block it; keys additionally respect {@code shouldIgnoreHotKeys()}.
     */
    boolean acceptsInput() {
        return !closed && !view.isClosed() && pendingDialogs.isEmpty() && !UIUtil.isModalDialogDisplayed();
    }

    /**
     * GL thread: posts a HUD service command to the EDT. There it runs only while {@link #acceptsInput()} holds, so a
     * command queued before a dialog appeared is dropped; after it runs, the frame is republished.
     */
    void command(Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput()) {
                action.run();
                refresh();
            }
        });
    }

    /** GL thread: the unit on the unit card and record sheet. */
    void setCardUnit(int id) {
        cardUnit = id;
    }

    /** GL thread: the own focus unit, which the fire preview follows and whose draft the fire orders show (H33). */
    void setFocusUnit(int id) {
        focusUnit = id;
    }

    GpuMovePlan moves() {
        return moves;
    }

    GpuFireOrders fire() {
        return fire;
    }

    GpuPhysicalOptions physical() {
        return physical;
    }

    GpuUnitRecord record() {
        return unitRecord;
    }

    GpuChat chat() {
        return chat;
    }

    GpuToasts toasts() {
        return toasts;
    }

    GpuLosResult los() {
        return los;
    }

    GpuPlayers players() {
        return players;
    }

    public void inspect(Coords coords) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput()) {
                contextCoords = coords;
                refresh();
            }
        });
    }

    public void key(int keyCode, boolean down, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput() && view.getClientgui() != null && !view.getClientgui().shouldIgnoreHotKeys()) {
                KeyEvent event = new KeyEvent(view.getClientgui().getFrame(),
                      down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, System.currentTimeMillis(), modifiers,
                      keyCode, KeyEvent.CHAR_UNDEFINED);
                var controller = view.getClientgui().controller;
                boolean handled = controller != null && controller.dispatchKeyEvent(event);
                // The HUD's chat field takes the typed text; a key no binding handled may be a menu accelerator.
                if (down && !handled) {
                    actions.menuShortcut(KeyStroke.getKeyStrokeForEvent(event));
                }
                refresh();
            }
        });
    }

    public void stopKeys() {
        SwingUtilities.invokeLater(() -> {
            var gui = view.getClientgui();
            if (gui != null && gui.controller != null) {
                gui.controller.stopAllRepeating();
            }
        });
    }

    /** Both measurement gestures belong to the shared ruler, independently of the active phase tool. */
    static boolean isMeasurement(int modifiers) {
        return (modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) != 0;
    }

    public void click(Coords coords, boolean doubleClick, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput() && coords != null && (view.game.getPhase().isOnMap()
                  || isMeasurement(modifiers))) {
                view.mouseAction(coords, doubleClick ? BoardClientState.BOARD_HEX_DOUBLE_CLICK : BoardClientState.BOARD_HEX_CLICK,
                      modifiers, 1);
                refresh();
            }
        });
    }

    public void hover(Coords coords, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput() && coords != null && !isMeasurement(modifiers) && view.game.getPhase().isOnMap()) {
                view.mouseAction(coords, BoardClientState.BOARD_HEX_DRAG, modifiers | InputEvent.BUTTON1_DOWN_MASK, 1);
            }
        });
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::close);
            return;
        }
        if (closed) { return; }
        closed = true;
        // A closing window always releases the EDT: every pending ask returns the cancelled answer.
        pendingDialogs.forEach(pending -> pending.complete(GpuBoardWindow.DialogAnswer.cancelled(pending.request)));
        if (conditionsDialog != null) { conditionsDialog.dispose(); }
        timer.stop();
        view.setMovingUnits(false);
        view.setVisibleArea(() -> new double[] { 0, 0, 1, 1 });
        view.game.removeGameListener(gameListener);
        moves.close();
        fire.close();
        preview.close();
        chat.close();
        players.close();
        PreferenceManager.getClientPreferences().removePreferenceChangeListener(preferenceListener);
        GUIPreferences.getInstance().removePreferenceChangeListener(preferenceListener);
        if (board != null) {
            board.removeBoardListener(boardListener);
        }
        unitImages.clear();
        terrainImages.clear();
        camouflage.clear();
        tiles = List.of();
        view.releasePlanarCapture();
        synchronized (this) {
            pendingEvents.clear();
        }
    }
    private long animationsQueued;
    private long animationsTaken;
    private long reportedAnimationSerial = -1;
    private long reportedGeneration = -1;
    private boolean reportedBusy;

    /** Render-thread report about the timeline consumed with this frame; never a second animation clock. */
    public void playbackState(Frame consumed, boolean busy) {
        long serial = animationsTaken;
        if (reportedGeneration == consumed.boardGeneration() && reportedAnimationSerial == serial && reportedBusy == busy) {
            return;
        }
        reportedGeneration = consumed.boardGeneration();
        reportedAnimationSerial = serial;
        reportedBusy = busy;
        SwingUtilities.invokeLater(() -> {
            if (!isCurrentView(view) || consumed.boardGeneration() != boardGeneration) { return; }
            // A later packet may already have queued movement that this GL frame has not consumed.
            if (busy || serial == animationsQueued) { view.setMovingUnits(busy); }
        });
    }

}
