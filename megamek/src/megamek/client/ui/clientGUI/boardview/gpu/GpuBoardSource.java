/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;


import java.awt.Color;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.event.BoardViewEvent;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.UnitAnnotations;
import megamek.client.ui.clientGUI.boardview.overlay.OverlayImage;
import megamek.client.ui.clientGUI.boardview.sprite.FieldOfFireSprite;
import megamek.client.ui.entityreadout.LiveReadoutDialog;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.client.ui.util.UIUtil;
import megamek.common.Hex;
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
import megamek.common.moves.MovePath;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Aero;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;

/** Thin Swing adapter. Reuses MegaMek's tileset, visibility checks, movement path, and actual phase buttons. */
final class GpuBoardSource implements BoardSource {
    private final java.util.LinkedHashSet<java.util.UUID> receivedAttacks = new java.util.LinkedHashSet<>();
    private final Map<Integer, BoardScene.Combat> removalAttempts = new HashMap<>();
    private final Set<Integer> voluntaryReleases = new java.util.HashSet<>();

    private volatile BoardClientState view;
    /** Swing-owned input intent, valid only for the selection and camera request produced by that mouse gesture. */
    private record MouseSelection(BoardClientState view, Board board, int actorId, BoardFocus request) { }
    private MouseSelection mouseSelection;
    private final Supplier<JComponent> phasePanel;
    private GpuBoardActions actions;
    volatile UiPreferences uiPreferences;
    volatile PhaseStatus phaseStatus = new PhaseStatus("", false);
    private final Map<Image, BoardScene.Pixels> unitImages = new IdentityHashMap<>();
    private record AnnotationKey(int entityId, int part) { }
    private final Map<AnnotationKey, UnitAnnotations.Annotations> unitAnnotations = new HashMap<>();
    private final Map<Image, BoardScene.Pixels> overlayImages = new IdentityHashMap<>();
    private final UnitCamouflage camouflage = new UnitCamouflage();
    private final GpuReportLog reports = new GpuReportLog();
    private final GpuAtmosphereControls atmosphere;
    private final BoardScene.PixelPool terrainImages = new BoardScene.PixelPool();
    private final List<BoardScene.Animation> pendingEvents = new ArrayList<>();
    private final Timer timer;
    private final GameListenerAdapter gameListener;
    private final BoardListenerAdapter boardListener;
    private final IPreferenceChangeListener preferenceListener = event -> {
        if (ClientPreferences.MAP_TILESET.equals(event.getName())) {
            dirtyTerrain();
        } else if (Set.of(GUIPreferences.GUI_SCALE, ClientPreferences.REPORT_KEYWORDS,
              ClientPreferences.REPORT_FILTER_KEYWORDS).contains(event.getName())) {
            uiPreferences = UiPreferences.capture();
        }
    };
    private Board board;
    private List<BoardScene.Tile> tiles = List.of();
    private BoardFieldOfView fieldOfView = BoardFieldOfView.EMPTY;
    private boolean terrainDirty = true;
    /** Swing-owned artwork invalidation; neighbouring exits and terrain blends also change after a hex edit. */
    private Rectangle dirtyHexes;
    private volatile boolean closed;
    /** Swing publishes chat focus for native camera/menu input; the BoardClientState owns the actual state. */
    private volatile boolean chatActive;
    private boolean suppressChatCharacter;
    private Frame frame;
    private Coords contextCoords;
    /** The GL thread publishes layout and raster sizes together; Swing owns overlay painting and hit testing. */
    private record OverlayViewport(Dimension size, Dimension pixels) { }
    private volatile OverlayViewport viewport = new OverlayViewport(new Dimension(1, 1), new Dimension(1, 1));
    private volatile Coords hoverCoords;
    private boolean overlayGesture;
    private volatile Point pointer = new Point(-1, -1);
    private long boardGeneration;
    private volatile Rectangle visibleArea = new Rectangle(0, 0, 16, 16);
    private Rectangle capturedArea;
    private long capturedRevision = -1;
    private GamePhase capturedPhase;

    public void setVisibleArea(Rectangle area) {
        visibleArea = new Rectangle(area);
    }

    public void setViewport(int width, int height, int pixelWidth, int pixelHeight) {
        viewport = new OverlayViewport(new Dimension(Math.max(1, width), Math.max(1, height)),
              new Dimension(Math.max(1, pixelWidth), Math.max(1, pixelHeight)));
    }

    public void setHover(Coords coords) {
        hoverCoords = coords;
    }

    public void setPointer(int x, int y) {
        pointer = new Point(x, y);
    }

    BoardClientState currentView() {
        return view;
    }

    public GpuBoardSource(BoardClientState view, Supplier<JComponent> phasePanel) {
        requireSwingThread();
        this.view = view;
        this.phasePanel = phasePanel;
        atmosphere = new GpuAtmosphereControls(
              () -> view.getClientgui() == null ? null
                    : view.getClientgui().getFrame(),
              () -> this.view.game.getBoard(this.view.getBoardId()), () -> this.view.game.getPlanetaryConditions(),
              () -> closed);
        GUIPreferences preferences = GUIPreferences.getInstance();
        uiPreferences = UiPreferences.capture();
        boardListener = new BoardListenerAdapter() {
            @Override
            public void boardNewBoard(BoardEvent event) {
                boardChangedAllHexes(event);
            }

            @Override
            public void boardChangedHex(BoardEvent event) {
                onSwing(() -> {
                    if (closed || event.getSource() != board) { return; }
                    Coords coords = event.getCoords();
                    if (coords == null) {
                        terrainDirty = true;
                    } else {
                        Rectangle area = new Rectangle(coords.getX() - 1, coords.getY() - 1, 3, 3);
                        dirtyHexes = dirtyHexes == null ? area : dirtyHexes.union(area);
                    }
                });
            }

            @Override
            public void boardChangedAllHexes(BoardEvent event) {
                onSwing(() -> {
                    if (!closed && event.getSource() == board) { terrainDirty = true; }
                });
            }
        };
        gameListener = new GameListenerAdapter() {
            @Override
            public void gameReport(megamek.common.event.GameReportEvent event) {
                onSwing(() -> {
                    if (!closed) {
                        reports.capture(view.game.getAllReports(), view.game.getRoundCount(), view.game.getPhase(),
                              GpuBoardSource.this::reportIcon);
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

    private static void requireSwingThread() {
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
        onSwing(() -> { if (!closed) { terrainDirty = true; } });
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
        List<BoardScene.Waypoint> points = new ArrayList<>();
        // An airborne visual plays at the altitude the unit has now, and at the altitude it started the move at for
        // its first point and when it has just landed, so flying stays on the board through climbs and landings.
        int flightAltitude = entity.getAltitude() > 0 ? entity.getAltitude() : startAltitude;
        if (start != null && start.boardId() == view.getBoardId()) {
            points.add(unitWaypoint(entity, start.coords(), start.elevation(), start.facing(),
                  startAltitude > 0 ? startAltitude : flightAltitude, start.form()).withProneCause(start.proneCause())
                  .withFallSide(start.fallSide()).withHullDown(start.hullDown()));
        } else if (frame != null) {
            frame.scene().units().stream().filter(unit -> unit.id() == entityId && !unit.sensorContact())
                  .findFirst().ifPresent(unit -> points.add(unit.location()));
        }
        for (UnitLocation location : path) {
            var observedForm = location.form() != null ? location.form() : points.isEmpty() ? null : points.getLast().form();
            BoardScene.Waypoint point = unitWaypoint(entity, location.coords(), location.elevation(),
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
            points.add(unitWaypoint(entity, last.coords(), last.elevation(), last.facing(), flightAltitude, finalForm)
                  .withProneCause(points.getLast().proneCause()).withFallSide(points.getLast().fallSide())
                  .withHullDown(points.getLast().hullDown()));
        }
        // Publish the final visible state and its movement together, so a frame cannot jump to the end first.
        Frame next = capture();
        var captured = next.scene().units().stream()
              .filter(unit -> unit.id() == entityId && !unit.sensorContact()).findFirst().orElse(null);
        if (!points.isEmpty()) {
            points.set(0, supportedEndpoint(points.getFirst(), frame, entityId));
            points.set(points.size() - 1, supportedEndpoint(points.getLast(), next, entityId));
            if (captured != null && points.getLast().coords().equals(captured.location().coords())
                  && (UnitMotion.changesPosture(points.getLast(), captured.location())
                        || points.getLast().elevation() != captured.location().elevation())) {
                // Legacy path steps can omit the fall at arrival; the final entity packet still confirms it.
                points.add(captured.location());
            }
        }
        synchronized (this) {
            if (view == movingView) {
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
              unit.height(), form == null ? unit.airborne() : form.airborne(), model, unit.outlineRgb(), unit.footprint(), unit.attachment(), unit.heat());
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
        if (result.kind() == megamek.common.ResolvedAttack.Kind.DEATH && frame != null
              && frame.scene().units().stream().anyMatch(unit -> unit.id() == attacker.getId() && unit.attachment() != null)) {
            return; // Exterior casualties remain on their carrier until its resolved release, rather than collapsing in midair.
        }
        if (result.kind() == megamek.common.ResolvedAttack.Kind.STOP_SWARM) {
            voluntaryReleases.add(attacker.getId());
            return;
        }
        var usedImages = new IdentityHashMap<Image, Boolean>();
        var firing = unit(attacker, -1, result.attacker().coords(), false, usedImages);
        if (result.shot() != null && result.shot().launch() != null) {
            firing = inForm(firing, attacker, unitWaypoint(attacker, result.attacker().coords(),
                  result.attacker().elevation(), result.attacker().facing(), attacker.getAltitude(), result.attacker().form()),
                  result.attacker().form());
        }
        var receiving = target == null ? null : unit(target, -1, result.target().coords(), false, usedImages);
        if (frame != null) {
            var observed = frame.scene().units().stream().collect(Collectors.toMap(BoardScene.Unit::id,
                  java.util.function.Function.identity(), (first, second) -> first));
            if (observed.containsKey(firing.id())) { firing = firing.withAttachment(observed.get(firing.id()).attachment()); }
            if (receiving != null && observed.containsKey(receiving.id())) {
                receiving = receiving.withAttachment(observed.get(receiving.id()).attachment());
            }
        }
        var destination = receiving == null
              ? waypoint(result.target().coords(), result.target().elevation(), result.target().facing())
              : receiving.location();
        if (result.kind() == megamek.common.ResolvedAttack.Kind.SHAKE_OFF) {
            // Movement resolution sends this before the carrier's path. Keep its confirmed result with that path.
            removalAttempts.put(attacker.getId(), new BoardScene.Combat(result, firing, receiving, destination));
            return;
        }
        // A resolved attack packet precedes its damage packets. This checkpoint closes the preceding action.
        Frame before = capture();
        synchronized (this) {
            publishScene(before, true);
            queueAnimation(new BoardScene.Combat(result, firing, receiving, destination,
                  target != null && target.isConventionalInfantry()));
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
                } else if (old != null && old.attachment() == null && unit.attachment() == null
                      && UnitMotion.forcedChange(old, unit)) {
                    // No movement path accompanied this authoritative update (push, domino displacement, or fall).
                    queueAnimation(new BoardScene.Movement(unit.id(), next.scene().boardId(),
                          List.of(old.location(), unit.location()), EntityMovementType.MOVE_NONE, 0, 0, unit));
                }
            }
        }
        if (frame != null && frame.boardGeneration() == next.boardGeneration()) {
            queueAttachments(frame.scene(), next.scene());
        }
        if (frame == null || frame.boardGeneration() != next.boardGeneration() || !next.scene().samePlaybackState(frame.scene())
              || (!pendingEvents.isEmpty() && !(pendingEvents.getLast() instanceof BoardScene.SceneUpdate))) {
            queueAnimation(new BoardScene.SceneUpdate(next.scene()));
        }
        frame = next;
    }

    /** Close carrier movement at its destination before releasing any of its exterior passengers. */
    private void queueAttachments(BoardScene previous, BoardScene next) {
        Map<Integer, BoardScene.Unit> oldUnits = new HashMap<>(), newUnits = new HashMap<>();
        previous.units().forEach(unit -> oldUnits.put(unit.id(), unit));
        next.units().forEach(unit -> newUnits.put(unit.id(), unit));
        List<BoardScene.AttachmentChange> changes = new ArrayList<>();
        List<BoardScene.Unit> held = new ArrayList<>(next.units());
        for (var before : oldUnits.values()) {
            var after = newUnits.get(before.id());
            var from = before.attachment();
            var to = after == null ? null : after.attachment();
            if (before.sensorContact() || after != null && after.sensorContact() || java.util.Objects.equals(from, to)) { continue; }
            int carrierId = to == null ? from.carrierId() : to.carrierId();
            var carrier = newUnits.get(carrierId);
            if (carrier == null || carrier.sensorContact()) { continue; }
            Entity passenger = view.game.getEntityFromAllSources(before.id());
            if (after == null && (passenger == null || !passenger.isDestroyed() && !passenger.isDoomed())) { continue; }
            if (passenger != null && (!EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, passenger)
                  || sensorContact(passenger))) { continue; }
            var release = to != null ? BoardScene.Release.BOARD : from.hostile() ? BoardScene.Release.THROWN : BoardScene.Release.CLIMB_DOWN;
            boolean voluntary = to == null && voluntaryReleases.remove(before.id());
            if (voluntary) { release = BoardScene.Release.CLIMB_DOWN; }
            var movement = !pendingEvents.isEmpty() && pendingEvents.getLast() instanceof BoardScene.Movement move
                  && move.entityId() == carrierId ? move : null;
            var destination = after != null ? after.location() : passenger != null && passenger.getPosition() != null
                  ? waypoint(passenger.getPosition(), passenger.getElevation(), passenger.getFacing()) : carrier.location();
            if (to == null && !voluntary && movement != null) {
                var tile = next.tile(destination.coords());
                if (tile != null && tile.waterDepth() > 0 && !tile.frozen()) { release = BoardScene.Release.WATER; }
                if (movement.type() == EntityMovementType.MOVE_JUMP) { release = BoardScene.Release.JUMP; }
            }
            changes.add(new BoardScene.AttachmentChange(next.boardId(), before, after, carrier, release, destination));
            held.removeIf(unit -> unit.id() == before.id());
            held.add(before);
        }
        var attempts = removalAttempts.values().stream().filter(attempt -> newUnits.containsKey(attempt.entityId())
              && pendingEvents.stream().anyMatch(event -> event instanceof BoardScene.Movement
                    && event.entityId() == attempt.entityId())).toList();
        if (changes.isEmpty() && attempts.isEmpty()) { return; }
        BoardScene.Movement continuation = null;
        if (changes.size() == 1 && changes.getFirst().release() == BoardScene.Release.WATER
              && !pendingEvents.isEmpty() && pendingEvents.getLast() instanceof BoardScene.Movement movement
              && movement.entityId() == changes.getFirst().carrier().id() && movement.type() != EntityMovementType.MOVE_JUMP) {
            var change = changes.getFirst();
            int index = releaseStep(movement.path(), change.destination());
            if (index > 0 && index < movement.path().size() - 1) {
                // The unit can continue past the water-entry hex. Release there, never at its later endpoint.
                var atWater = change.carrier().at(movement.path().get(index));
                pendingEvents.set(pendingEvents.size() - 1, new BoardScene.Movement(movement.entityId(), movement.boardId(),
                      movement.path().subList(0, index + 1), movement.type(), movement.jumpMP(), movement.movementMP(), atWater, movement.gravity()));
                continuation = new BoardScene.Movement(movement.entityId(), movement.boardId(),
                      movement.path().subList(index, movement.path().size()), movement.type(), movement.jumpMP(), movement.movementMP(),
                      movement.unit(), movement.gravity());
                held.replaceAll(unit -> unit.id() == atWater.id() ? atWater : unit);
                changes.set(0, new BoardScene.AttachmentChange(change.boardId(), change.before(), change.after(), atWater,
                      change.release(), change.destination()));
            }
        }
        queueAnimation(new BoardScene.SceneUpdate(next.withUnits(held)));
        for (var attempt : attempts) {
            removalAttempts.remove(attempt.entityId());
            var carrier = newUnits.get(attempt.entityId());
            Entity target = attempt.target() == null ? null : view.game.getEntityFromAllSources(attempt.target().id());
            if (carrier.sensorContact() || target == null || !visible(target) || sensorContact(target)) { continue; }
            queueAnimation(new BoardScene.Combat(attempt.result(), carrier, attempt.target(), carrier.location()));
        }
        changes.forEach(this::queueAnimation);
        if (continuation != null) {
            var atWater = changes.getFirst().carrier();
            queueAnimation(new BoardScene.SceneUpdate(next.withUnits(next.units().stream()
                  .map(unit -> unit.id() == atWater.id() ? atWater : unit).toList())));
            queueAnimation(continuation);
        }
    }

    static int releaseStep(List<BoardScene.Waypoint> path, BoardScene.Waypoint destination) {
        for (int index = 1; index < path.size(); index++) {
            if (path.get(index).coords().equals(destination.coords())) { return index; }
        }
        return -1;
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
        if (frame == null || point.aeroState() != BoardScene.AeroState.LANDED) {
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

    @Override
    public void reloadAssets() throws java.io.IOException {
        requireSwingThread();
        if (closed) { return; }
        megamek.common.util.ImageUtil.reloadImages();
        MMStaticDirectoryManager.refreshMekTileset();
        MMStaticDirectoryManager.refreshCamouflageDirectory();
        view.reloadAssets();
        unitImages.clear();
        unitAnnotations.clear();
        camouflage.clear();
        terrainImages.clear();
        terrainDirty = true;
        refresh();
    }

    public synchronized Frame takeFrame() {
        Frame result = new Frame(frame.scene(), List.copyOf(pendingEvents), frame.context(), frame.globalCommands(),
              frame.hud(), frame.tooltip(), frame.centerRequest(), frame.boardGeneration(), frame.actorName(),
              frame.scenarioAtmosphere(), frame.attack(), frame.reports(), frame.keepSelectionCamera());
        pendingEvents.clear();
        animationsTaken = animationsQueued;
        return result;
    }

    private Frame capture() {
        PhaseStatus nextPhaseStatus = GpuBoardActions.phaseStatus(phasePanel.get());
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
                unitAnnotations.clear();
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
            actions = new GpuBoardActions(owner, phasePanel,
                  () -> !isCurrentView(owner) || generation != boardGeneration, this::refresh);
            view.setMovingUnits(false);
            atmosphere.reset();
            synchronized (this) {
                pendingEvents.clear();
                receivedAttacks.clear();
                removalAttempts.clear();
                voluntaryReleases.clear();
            }
            if (board != null) { board.addBoardListener(boardListener); }
            terrainDirty = true;
            dirtyHexes = null;
        }
        if (board == null) {
            tiles = List.of();
            terrainImages.clear();
            unitImages.clear();
            unitAnnotations.clear();
            overlayImages.clear();
            fieldOfView = BoardFieldOfView.EMPTY;
            chatActive = false;
            phaseStatus = nextPhaseStatus;
            return new Frame(null, List.of(), null, List.of(), new Hud(1, 1, List.of()), "",
                  new BoardFocus(0, null), boardGeneration, "");
        }
        view.setVisibleArea(() -> {
            Rectangle area = visibleArea;
            Board currentBoard = view.getBoard();
            return area == null ? new double[] { 0, 0, 1, 1 } : new double[] {
                  area.x / (double) currentBoard.getWidth(), area.y / (double) currentBoard.getHeight(),
                  (area.x + area.width) / (double) currentBoard.getWidth(),
                  (area.y + area.height) / (double) currentBoard.getHeight() };
        });
        int measurement = pendingMeasurementModifiers();
        if (measurement != 0 && !nextPhaseStatus.blocking()) {
            nextPhaseStatus = new PhaseStatus("Left-click an endpoint to complete the "
                  + (measurement == InputEvent.CTRL_DOWN_MASK ? "line of sight" : "distance") + " measurement.", false);
        }
        phaseStatus = nextPhaseStatus;
        chatActive = view.getChatterBoxActive();
        OverlayViewport overlayViewport = viewport;
        view.overlayInput(MouseEvent.MOUSE_MOVED, pointer, overlayViewport.size(), overlayViewport.pixels());
        Hud nextHud = captureHud(overlayViewport);
        // A toggle starts on Swing. Publish its timeline before potentially expensive board/command capture,
        // so native rendering can already animate it while the rest of this scene snapshot is being prepared.
        synchronized (this) {
            if (frame != null && frame.scene() != null && frame.scene().boardId() == view.getBoardId()) {
                frame = new Frame(frame.scene(), frame.timeline(), frame.context(), frame.globalCommands(), nextHud,
                      frame.tooltip(), frame.centerRequest(), frame.boardGeneration(), frame.actorName(),
                      frame.scenarioAtmosphere(), frame.attack(), frame.reports(), frame.keepSelectionCamera());
            }
        }
        boolean allTerrain = terrainDirty;
        Rectangle changedHexes = dirtyHexes;
        boolean changedTerrain = allTerrain || changedHexes != null;
        if (changedTerrain) {
            List<BoardScene.Tile> nextTiles = terrainDirty
                  ? new ArrayList<>(Collections.nCopies(board.getWidth() * board.getHeight(), null))
                  : new ArrayList<>(tiles);
            Rectangle area = terrainDirty ? new Rectangle(0, 0, board.getWidth(), board.getHeight()) : dirtyHexes;
            view.capturePlanarHexes(area, false, hex -> {
                int index = hex.coords().getX() * board.getHeight() + hex.coords().getY();
                nextTiles.set(index, BoardScene.captureTile(board.getHex(hex.coords()), hex, nextTiles.get(index), terrainImages,
                      board::getHex));
            });
            tiles = List.copyOf(nextTiles);
            terrainDirty = false;
            dirtyHexes = null;
        }
        Rectangle area = visibleArea;
        long revision = view.getPlanarRevision();
        boolean allMarkings = allTerrain || revision != capturedRevision || view.game.getPhase() != capturedPhase;
        boolean changedMarkings = changedTerrain || allMarkings;
        if (changedMarkings || !area.equals(capturedArea)) {
            List<BoardScene.Tile> painted = new ArrayList<>(tiles);
            for (int index = 0; index < tiles.size(); index++) {
                BoardScene.Tile old = tiles.get(index);
                if (old.tactical() != null && !area.contains(old.coords().getX(), old.coords().getY())) {
                    painted.set(index, old.withTactical(null));
                }
            }
            // A terrain edit repaints its changed hexes and newly exposed view only. Explicit painter/overlay
            // invalidation remains global, including FoV changes caused by an edited blocker.
            List<Rectangle> exposed = new ArrayList<>();
            if (allMarkings || capturedArea == null || !area.intersects(capturedArea)) {
                exposed.add(area);
            } else {
                exposed.addAll(List.of(SwingUtilities.computeDifference(area, capturedArea)));
                if (changedHexes != null) {
                    Rectangle local = changedHexes.intersection(area).intersection(capturedArea);
                    if (!local.isEmpty()) { exposed.add(local); }
                }
            }
            for (Rectangle region : exposed) { view.capturePlanarTactical(region, hex -> {
                int index = hex.coords().getX() * board.getHeight() + hex.coords().getY();
                BoardScene.Tile old = tiles.get(index);
                BoardScene.Tile next = new BoardScene.Tile(old.coords(), old.elevation(), old.waterDepth(), old.frozen(),
                      old.roadExits(), old.surface(), old.ground(), old.normals(), old.decals(), old.decalsWithoutLimbs(),
                      terrainImages.capture(hex.tactical(), old.tactical()), old.features(), hex.text(), old.liquid(),
                      old.tileset(), old.detailedGround(), old.road(), old.fireSmoke(), old.biome(), old.impassable(),
                      old.blackIce(), old.cliffTopExits(), old.bare(), old.groundCover(), old.bridge());
                if (!next.equals(old)) {
                    painted.set(index, next);
                }
            }); }
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
        var attachments = exteriorAttachments(view.game.getEntitiesVector(), entity ->
              EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, entity) && !sensorContact(entity),
              this::visible);
        for (Entity entity : view.game.getEntitiesVector()) {
            var attachment = attachments.get(entity.getId());
            if (attachment == null && (!visible(entity) || entity.getTransportId() != Entity.NONE)) {
                continue;
            }
            if (attachment != null) {
                Entity carrier = view.game.getEntity(attachment.carrierId());
                var before = frame == null ? null : frame.scene().units().stream()
                      .filter(unit -> unit.id() == entity.getId() && !unit.sensorContact()).findFirst().orElse(null);
                units.add((entity.isDestroyed() || entity.isDoomed()) && before != null ? before
                      : unit(entity, -1, carrier.getPosition(), false, usedImages).withAttachment(attachment));
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
        if (frame != null) {
            for (var passenger : frame.scene().units()) {
                if (passenger.attachment() == null || !passenger.attachment().hostile()
                      || units.stream().anyMatch(unit -> unit.id() == passenger.id())) { continue; }
                Entity carrier = view.game.getEntity(passenger.attachment().carrierId());
                Entity removed = view.game.getEntityFromAllSources(passenger.id());
                if (view.game.getEntity(passenger.id()) == null && removed != null && (removed.isDestroyed() || removed.isDoomed())
                      && EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, removed) && !sensorContact(removed)
                      && carrier != null && visible(carrier) && !sensorContact(carrier) && carrier.getSwarmAttackerId() == passenger.id()) {
                    // A removal packet can precede its carrier's path. Retain only the already authorized appearance.
                    units.add(passenger);
                }
            }
        }
        if (GpuUnitModels.ENABLED && GUIPreferences.getInstance().getShowWrecks()) {
            // BoardClientState already owns which removed units leave wrecks, including infantry/CVEP exceptions.
            var live = units.stream().map(BoardScene.Unit::id).collect(Collectors.toSet());
            view.getWrecks().stream()
                  .filter(entity -> !live.contains(entity.getId()) && visible(entity) && !sensorContact(entity))
                  .forEach(entity -> units.add(wreck(entity, usedImages)));
        }
        unitImages.keySet().retainAll(usedImages.keySet());
        unitAnnotations.values().removeIf(annotations -> !usedImages.containsKey(annotations.image()));
        camouflage.retain();
        JComponent panel = phasePanel.get();
        List<BoardScene.Command> commands = actions.phaseCommands();
        BoardScene.Context nextContext = contextCoords == null ? null : new BoardScene.Context(contextCoords,
              actions.contextCommands(contextCoords));
        List<BoardScene.Command> nextGlobal = new ArrayList<>(actions.globalCommands());
        if (view.getClientgui() != null) {
            var gui = view.getClientgui();
            List<BoardScene.Command> boards = gui.boardStates().stream().map(boardView -> new BoardScene.Command(
                  "Map " + boardView.getBoardId(), true, () -> SwingUtilities.invokeLater(() -> {
                      if (!closed) {
                          gui.showBoardView(boardView.getBoardId());
                          refresh();
                      }
                  }))).toList();
            nextGlobal.add(new BoardScene.Command("boards", "Maps", "", true, false, boards, () -> { }));
        }
        String nextTooltip = GpuBoardActions.plainText(view.getHexTooltip(contextCoords == null ? hoverCoords : contextCoords));
        List<BoardScene.Waypoint> planned = new ArrayList<>();
        if (panel instanceof MovementDisplay movement) {
            MovePath path = movement.getPlannedMovement();
            if (path != null && path.getEntity().getBoardId() == view.getBoardId()) {
                Entity entity = path.getEntity();
                if (entity.getPosition() != null) {
                    planned.add(unitWaypoint(entity, entity.getPosition(), entity.getFacing()));
                }
                path.getStepVector().stream().filter(step -> step.getPosition() != null).forEach(step ->
                      planned.add(unitWaypoint(entity, step.getPosition(), step.getElevation(), step.getFacing(),
                            step.getAltitude(), null)));
            }
        }
        Point light = view.getTerrainLightDirection();
        BoardScene scene = new BoardScene(view.getBoardId(), board.getWidth(), board.getHeight(), tiles, units, planned,
              actions.actorId(), view.game.getPhase().localizedName(), commands,
              light == null || light.x == 0 && light.y == 0 ? null : new BoardScene.Light(light.x, -light.y),
              firingLines(), view.getWeaponRangeSprites().stream().map(sprite -> new BoardScene.RangeBorder(
                    sprite.getPosition(), sprite.getBorders(),
                    FieldOfFireSprite.getFieldOfFireColor(sprite.getRangeBracket()).getRGB(),
                    FieldOfFireSprite.getRangeText(sprite.getRangeBracket()))).toList(),
              view.getBoardMarkers(), view.captureTacticalGeometry(),
              view.getWeaponRangeTextSprites().stream().map(sprite -> new BoardScene.RangeLabel(sprite.getPosition(),
                    FieldOfFireSprite.getFieldOfFireColor(sprite.getRangeBracket()).getRGB(),
                    FieldOfFireSprite.getRangeText(sprite.getRangeBracket()))).toList(), fieldOfView);
        Entity actor = view.game.getEntity(actions.actorId());
        boolean knownActor = actor != null && (actor.getOwner().equals(view.getLocalPlayer())
              || visible(actor) && !sensorContact(actor));
        boolean keepSelectionCamera = mouseSelection != null
              && mouseSelection.equals(new MouseSelection(view, board, actions.actorId(), view.getCenterRequest()));
        if (!keepSelectionCamera) { mouseSelection = null; }
        return new Frame(scene, List.of(), nextContext, List.copyOf(nextGlobal), nextHud, nextTooltip,
              view.getCenterRequest(), boardGeneration, knownActor ? actor.getShortName() : "",
              atmosphereFor(view.game.getPlanetaryConditions(), board.isSpace()), actions.attackState(),
              reports.capture(view.game.getAllReports(), view.game.getRoundCount(), view.game.getPhase(), this::reportIcon),
              keepSelectionCamera);
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

    /** Recheck visibility on the EDT before opening the existing live unit readout from a report link. */
    public void reportUnit(int id) {
        SwingUtilities.invokeLater(() -> {
            if (closed || view.getClientgui() == null || frame == null || frame.reports().entries().stream()
                  .flatMap(entry -> entry.units().stream()).noneMatch(unit -> unit.id() == id)) {
                return;
            }
            Entity entity = reportEntity(id);
            if (entity != null) {
                if (entity.isDeployed() && !entity.isOffBoard() && entity.getPosition() != null
                      && entity.getBoardId() == view.getBoardId()) {
                    view.centerOn(entity);
                }
                new LiveReadoutDialog(view.getClientgui().getFrame(), view.game, id).setVisible(true);
            }
        });
    }

    private Hud captureHud(OverlayViewport layout) {
        // The initial snapshot predates the native window's first layout. Never scale its 1px placeholder
        // into sidebar bounds: it would reserve the entire board and make the initial camera fit enormous.
        if (layout.size().width <= 1 || layout.size().height <= 1) {
            return new Hud(layout.pixels().width, layout.pixels().height, List.of());
        }
        List<OverlayImage> artwork = view.captureOverlayLayers(layout.size(), layout.pixels());
        List<HudLayer> layers = new ArrayList<>();
        Map<Image, BoardScene.Pixels> retained = new IdentityHashMap<>();
        for (int index = 0; index < artwork.size(); index++) {
            OverlayImage layer = artwork.get(index);
            BoardScene.Pixels pixels = overlayImages.get(layer.image());
            if (pixels == null) {
                BoardScene.Pixels previous = frame == null || index >= frame.hud().layers().size() ? null
                      : frame.hud().layers().get(index).pixels();
                pixels = BoardScene.Pixels.capture(layer.image(), previous);
            }
            retained.put(layer.image(), pixels);
            layers.add(new HudLayer(pixels, layer.x(), layer.y(), layer.fade(), layer.shiftY()));
        }
        overlayImages.clear();
        overlayImages.putAll(retained);
        return new Hud(layout.pixels().width, layout.pixels().height, List.copyOf(layers),
              view.sidePanelInset()
                    * layout.pixels().width / (float) Math.max(1, layout.size().width),
              view.leftPanelInset() * layout.pixels().width / (float) Math.max(1, layout.size().width));
    }

    private boolean visible(Entity entity) {
        return entity.getPosition() != null && entity.getBoardId() == view.getBoardId()
              && EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, entity);
    }

    /** The carrier owns exterior occupancy, so separately delivered passenger updates cannot release it early. */
    static Map<Integer, BoardScene.Attachment> exteriorAttachments(List<Entity> entities,
          java.util.function.Predicate<Entity> identified, java.util.function.Predicate<Entity> onBoard) {
        Map<Integer, BoardScene.Attachment> result = new HashMap<>();
        Map<Integer, Entity> byId = new HashMap<>();
        entities.forEach(entity -> byId.put(entity.getId(), entity));
        for (Entity carrier : entities) {
            if (!onBoard.test(carrier) || !identified.test(carrier)) { continue; }
            for (Entity passenger : carrier.getExternalUnits()) {
                if (passenger instanceof megamek.common.battleArmor.BattleArmor && identified.test(passenger)) {
                    result.put(passenger.getId(), new BoardScene.Attachment(carrier.getId(), false));
                }
            }
            Entity swarmer = byId.get(carrier.getSwarmAttackerId());
            if (swarmer instanceof megamek.common.units.Infantry && identified.test(swarmer)) {
                result.put(swarmer.getId(), new BoardScene.Attachment(carrier.getId(), true));
            }
        }
        return result;
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
            var position = unitWaypoint(entity, coords, 0);
            return new BoardScene.Waypoint(coords, position.elevation() + (entity.height() + 1) * 0.5f, 0);
        }
        return waypoint(coords, target.getElevation() + Math.max(0.15f, target.getHeight() * 0.5f), 0);
    }

    private boolean sensorContact(Entity entity) {
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
        Image image = view.getTilesetManager().wreckMarkerFor(entity, -1);
        usedImages.put(image, true);
        var pixels = unitImages.computeIfAbsent(image, BoardScene.Pixels::copy);
        return new BoardScene.Unit(captured.id(), -1, captured.name(), captured.location(), pixels, false, null,
              captured.height(), captured.airborne(), model, captured.outlineRgb(), captured.footprint(), null, captured.heat());
    }

    private BoardScene.Unit unit(Entity entity, int part, Coords coords, boolean sensor,
          Map<Image, Boolean> usedImages) {
        Image image = sensor ? view.getRadarBlipImage() : view.getTilesetManager().textureFor(entity, part);
        usedImages.put(image, true);
        BoardScene.Pixels pixels = unitImages.computeIfAbsent(image, BoardScene.Pixels::copy);
        AnnotationKey annotationKey = new AnnotationKey(entity.getId(), part);
        UnitAnnotations.Annotations annotations = view.captureUnitAnnotations(entity, part, unitAnnotations.get(annotationKey));
        unitAnnotations.put(annotationKey, annotations);
        usedImages.put(annotations.image(), true);
        BoardScene.Pixels annotationPixels = unitImages.computeIfAbsent(annotations.image(), BoardScene.Pixels::copy);
        int facing = sensor ? 0 : view.getTilesetManager().facingFor(entity);
        boolean airborne = !sensor && airborne(entity);
        List<Coords> footprint = !sensor && part < 0 ? entity.getOccupiedCoords().stream()
              .sorted(java.util.Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY)).toList() : List.of(coords);
        if (footprint.isEmpty()) {
            footprint = List.of(coords);
        }
        BoardScene.Waypoint location = airborne
              ? unitWaypoint(entity, coords, facing)
              : footprint.size() > 1 && UnitFootprint.terrainSupported(entity.getMovementMode())
                    ? new BoardScene.Waypoint(coords, UnitFootprint.support(board, coords, footprint, entity.getElevation()), facing)
                    : waypoint(coords, sensor ? 0 : entity.getElevation(), facing);
        if (!sensor) {
            location = location.withAeroState(aeroState(entity, entity.getElevation(), airborne))
                  .withProneCause(entity.getProneCause())
                  .withFallSide(entity instanceof Mek ? entity.getFallSide() : null).withHullDown(entity.isHullDown());
            if (location.aeroState() != null) {
                location = location.withFootprint(footprint);
            }
        }
        Color outline = sensor ? new Color(GpuMarkers.SENSOR_RGB)
              : GUIPreferences.getInstance().getTeamColoring() && view.getLocalPlayer() != null
                    ? UIUtil.teamColor(entity.getOwner(), view.getLocalPlayer())
                    : entity.getOwner().getColour().getColour(false);
        return new BoardScene.Unit(entity.getId(), part, sensor ? Messages.getString("BoardView1.sensorReturn")
              : entity.getShortName(),
              location, pixels, sensor,
              annotationPixels,
              sensor ? 1 : entity.height() + 1, airborne,
              GpuUnitModels.ENABLED
                    ? camouflage.resolve(UnitModelSelection.capture(entity, part, sensor, MMStaticDirectoryManager.getMekTileset(),
                          sensor ? 0 : UnitModelSelection.twist(entity.getFacing(), facing))) : null,
              outline.getRGB(), footprint, null, sensor || !entity.tracksHeat() ? -1 : Math.max(0, entity.heat));
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
     * Capture the current unit through the same elevation conversion as historical and planned movement.
     */
    private BoardScene.Waypoint unitWaypoint(Entity entity, Coords coords, int facing) {
        return unitWaypoint(entity, coords, entity.getElevation(), facing, entity.getAltitude(), UnitLocation.Form.capture(entity));
    }

    /**
     * Single conversion for current units, playback, planned movement and firing. An aerospace reports the airborne
     * sentinel (999) while flying instead of a real level; those steps play at the flight altitude above their hex.
     * A step carrying a real elevation (an aerospace taxiing, or set down on its landing hex) keeps it. Every other unit
     * keeps its own per-step elevation, so VTOL and WiGE climbs and descents still animate.
     * Published waypoint elevations are absolute render levels. Renderers must not add terrain height again;
     * this visual conversion does not change the rules engine's altitude or LOS calculations.
     */
    private BoardScene.Waypoint unitWaypoint(Entity entity, Coords coords, float elevation, int facing,
          int flightAltitude, UnitLocation.Form form) {
        boolean aero = form == null ? entity.isAero() : form.aero();
        if (form != null && form.altitude() > 0) { flightAltitude = form.altitude(); }
        BoardScene.Waypoint point = aero && elevation >= Aero.AERO_EFFECTIVE_ELEVATION && flightAltitude > 0
              ? waypoint(coords, flightAltitude, facing)
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

    /** Mouse selection and phase actions use the existing controllers without changing the camera. */
    public void primaryClick(Coords coords, int entityId, int modifiers, long generation) {
        SwingUtilities.invokeLater(() -> {
            if (!isCurrentView(view) || generation != boardGeneration
                  || coords != null && !board.contains(coords)
                  || view.getClientgui() != null && view.getClientgui().shouldIgnoreHotKeys()) {
                return;
            }
            Entity entity = view.game.getEntity(entityId);
            boolean knownUnit = entity != null && visible(entity) && !sensorContact(entity);
            int clickModifiers = isMeasurement(modifiers) ? modifiers : modifiers | pendingMeasurementModifiers();
            boolean modified = (clickModifiers & (InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) != 0;
            if (knownUnit && !modified && (!entity.getOwner().isEnemyOf(view.getLocalPlayer())
                  || !ClientGUI.hasUnitSelectionController(phasePanel.get()))) {
                // Reselecting the acting unit would reset its phase tool and discard planned orders.
                if (entityId != actions.actorId()) {
                    view.processBoardViewEvent(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, entityId));
                }
            } else if (entityId == Entity.NONE || knownUnit || isMeasurement(clickModifiers)) {
                actions.defaultAction(coords, entity, clickModifiers);
            }
            if (!isMeasurement(clickModifiers)) {
                view.selectForInspection(coords);
            }
            mouseSelection = new MouseSelection(view, board, actions.actorId(), view.getCenterRequest());
            refresh();
        });
    }

    public void inspect(Coords coords) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && !view.isClosed()) {
                contextCoords = coords;
                refresh();
            }
        });
    }

    public void overlayInput(int event, int x, int y, Runnable unhandled) {
        SwingUtilities.invokeLater(() -> {
            if (closed || view.isClosed()) {
                return;
            }
            OverlayViewport overlayViewport = viewport;
            boolean handled = view.overlayInput(event, new Point(x, y), overlayViewport.size(), overlayViewport.pixels());
            if (event == MouseEvent.MOUSE_PRESSED) {
                overlayGesture = handled;
            } else {
                handled |= overlayGesture;
                if (event == MouseEvent.MOUSE_RELEASED) {
                    overlayGesture = false;
                }
            }
            if (!handled) {
                unhandled.run();
            } else {
                // Publish overlay selection and camera requests together, without waiting for the HUD timer.
                refresh();
            }
        });
    }

    public void key(int keyCode, boolean down, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && !view.isClosed() && view.getClientgui() != null && !view.getClientgui().shouldIgnoreHotKeys()) {
                KeyEvent event = new KeyEvent(view.getClientgui().getFrame(),
                      down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, System.currentTimeMillis(), modifiers,
                      keyCode, KeyEvent.CHAR_UNDEFINED);
                var controller = view.getClientgui().controller;
                boolean handled = controller != null && controller.dispatchKeyEvent(event);
                if (down) {
                    // Opening chat (especially with '/') must not also type the shortcut into its message.
                    suppressChatCharacter = handled;
                    if (!handled) {
                        if (view.getChatterBoxActive()) {
                            chatKey(event);
                        } else {
                            actions.menuShortcut(KeyStroke.getKeyStrokeForEvent(event));
                        }
                    }
                }
                refresh();
            }
        });
    }

    public boolean chatActive() {
        return chatActive;
    }

    public void keyTyped(char character) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && !view.isClosed() && !suppressChatCharacter && !Character.isISOControl(character)
                  && view.getChatterBoxActive() && view.getClientgui() != null
                  && !view.getClientgui().shouldIgnoreHotKeys()) {
                // ChatterBoxOverlay edits text in keyPressed; GLFW supplies Unicode separately from physical keys.
                chatKey(new KeyEvent(view.getClientgui().getFrame(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                      KeyEvent.VK_UNDEFINED, character));
                refresh();
            }
        });
    }

    private void chatKey(KeyEvent event) {
        view.chatKey(event);
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

    /** Read the shared tools on the EDT; a pending endpoint takes priority over the phase's default action. */
    private int pendingMeasurementModifiers() {
        if (view.getFirstLOS() != null) {
            return InputEvent.CTRL_DOWN_MASK;
        }
        return (view.getRulerStart() != null) != (view.getRulerEnd() != null) ? InputEvent.ALT_DOWN_MASK : 0;
    }

    public void click(Coords coords, boolean doubleClick, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && !view.isClosed() && coords != null && (view.game.getPhase().isOnMap()
                  || isMeasurement(modifiers))) {
                view.mouseAction(coords, doubleClick ? BoardClientState.BOARD_HEX_DOUBLE_CLICK : BoardClientState.BOARD_HEX_CLICK,
                      modifiers, 1);
                refresh();
            }
        });
    }

    public void hover(Coords coords, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && !view.isClosed() && coords != null && !isMeasurement(modifiers) && view.game.getPhase().isOnMap()) {
                view.mouseAction(coords, BoardClientState.BOARD_HEX_DRAG, modifiers | InputEvent.BUTTON1_DOWN_MASK, 1);
            }
        });
    }

    public UiPreferences uiPreferences() { return uiPreferences; }
    public PhaseStatus phaseStatus() { return phaseStatus; }
    public GpuAtmosphereControls atmosphere() { return atmosphere; }

    public boolean isGameplay() { return true; }

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
        atmosphere.close();
        timer.stop();
        view.setMovingUnits(false);
        view.setVisibleArea(() -> new double[] { 0, 0, 1, 1 });
        view.game.removeGameListener(gameListener);
        PreferenceManager.getClientPreferences().removePreferenceChangeListener(preferenceListener);
        GUIPreferences.getInstance().removePreferenceChangeListener(preferenceListener);
        if (board != null) {
            board.removeBoardListener(boardListener);
        }
        unitImages.clear();
        unitAnnotations.clear();
        overlayImages.clear();
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

    @Override
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
