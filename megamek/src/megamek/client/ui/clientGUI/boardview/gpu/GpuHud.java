/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * The hud-v3 battle HUD on the render thread (rebuild plan C.4): its Stage, the layout metrics and layers, the
 * components, key dispatch with the Esc chain and the hotkeys, and board-click routing. It presents snapshots and calls
 * the source's existing commands; the client keeps every rule.
 */
final class GpuHud implements GpuBoardHud {
    /** The prototype's spacing between stacked panels. */
    private static final float STACK = 12;
    /** The minimap's top, and the top of panels beside the right column; it does not follow the gap. */
    private static final float SECOND_ROW = 90;
    private static final float RIGHT_TOP_WITHOUT_MAP = 94;
    private static final float UTILITY_TOP = 18;
    private static final float CHIP_TOP = 20;
    private static final float RIGHT_BOTTOM = 70;
    private static final float DOCK_BOTTOM = 30;
    private static final float HINT_BOTTOM = 7;
    private static final float CHAT_BUTTON_BOTTOM = 18;
    private static final float CHAT_BOTTOM = 66;
    private static final float TOAST_BOTTOM = 232;
    /** The initiative card's top, as a fraction of the window height. */
    private static final float INITIATIVE_TOP = .2f;
    /** The solution card moves beside the right column when the list would keep less than this height. */
    private static final float SOLUTION_ROOM = 430;
    private static final float CONDITIONS_WIDTH = 260;
    /**
     * The forces list's width, at most the left column's (the user's request of 2026-10-02: the prototype's column of
     * 300 left the rows much room to spare). The phase header and the unit card keep the column; the forces grid
     * keeps its own width.
     */
    static final float FORCES_WIDTH = 250;
    private static final float INITIATIVE_WIDTH = 560;
    private static final float CHAT_WIDTH = 340;
    private static final float CHAT_HEIGHT = 320;
    /**
     * The binds MegaMek applies without a unit besides its menu bar's ({@link #unitless}): the camera, the chat, the
     * pause, the report keys, the turn timer and the HUD's own panels.
     */
    private static final Set<KeyCommandBind> UNITLESS_BINDS = EnumSet.of(KeyCommandBind.SCROLL_NORTH,
          KeyCommandBind.SCROLL_SOUTH, KeyCommandBind.SCROLL_EAST, KeyCommandBind.SCROLL_WEST,
          KeyCommandBind.CAMERA_ROTATE_LEFT, KeyCommandBind.CAMERA_ROTATE_RIGHT, KeyCommandBind.CAMERA_TILT_UP,
          KeyCommandBind.CAMERA_TILT_DOWN, KeyCommandBind.CAMERA_RESET, KeyCommandBind.CAMERA_FIT_BOARD,
          KeyCommandBind.TOGGLE_CHAT, KeyCommandBind.TOGGLE_CHAT_CMD, KeyCommandBind.PAUSE, KeyCommandBind.UNPAUSE,
          KeyCommandBind.REPORT_KEY_NEXT, KeyCommandBind.REPORT_KEY_PREV, KeyCommandBind.REPORT_KEY_SELECT_NEXT,
          KeyCommandBind.REPORT_KEY_SELECT_PREVIOUS, KeyCommandBind.REPORT_FILTER_KEY_SELECT_NEXT,
          KeyCommandBind.REPORT_KEY_FILTER, KeyCommandBind.EXTEND_TURN_TIMER, KeyCommandBind.BOT_COMMANDS,
          KeyCommandBind.FORCES_GRID, KeyCommandBind.PLAYBACK_TOGGLE, KeyCommandBind.PLAYBACK_PREV,
          KeyCommandBind.PLAYBACK_NEXT, KeyCommandBind.SHOW_NAMEPLATES, KeyCommandBind.UD_GENERAL,
          KeyCommandBind.UD_PILOT, KeyCommandBind.UD_ARMOR, KeyCommandBind.UD_WEAPONS, KeyCommandBind.UD_SYSTEMS,
          KeyCommandBind.UD_EXTRAS);

    /**
     * The prototype's layout sizes for one window, in stage units (its CSS pixels): the gap, the left and right column
     * widths, and the dock, log and target-card widths, with the breakpoints W <= 1500 (compact), W <= 1350 (narrow)
     * and H <= 800 (low height).
     */
    record Metrics(float width, float height, float gap, float left, float right, float dock, float log, float card,
          boolean compact, boolean narrow, boolean lowHeight) {
        static Metrics of(float width, float height) {
            boolean compact = width <= 1500;
            boolean narrow = width <= 1350;
            return new Metrics(width, height, narrow ? 16 : 20, narrow ? 250 : compact ? 270 : 300,
                  narrow ? 270 : compact ? 290 : 310, narrow ? 500 : compact ? 540 : 580,
                  narrow ? 310 : compact ? 340 : 380, narrow ? 270 : 300, compact, narrow, height <= 800);
        }

        /** The forces grid's width, min(660, W / 2 - 330) and at least the left column. */
        float grid() {
            return Math.max(left, Math.min(660, width / 2 - 330));
        }

        /** The forces list's width: FORCES_WIDTH, and at most the left column. */
        float forces() {
            return Math.min(FORCES_WIDTH, left);
        }
    }

    /**
     * The board view's facts of one frame, by unit id: the view mode and whether the playback still animates; each
     * drawn unit's screen rectangle and label anchor (head) in stage units, y up, left out while its head lies behind
     * the camera; each drawn unit's animated board position in world units; the hovered hex and unit; a hex's
     * width on the screen, corner to corner, in stage units; the height of the ring the view draws on a building
     * floor under the pointer, NaN while the pointer is on the hovered hex's ground (GpuBattleView.hoverTop); and the
     * anchor of each fire target that is no unit, its hex's centre, as a unit's head is its anchor.
     */
    record HudView(boolean tactical, boolean playbackBusy, Map<Integer, Rectangle> unitRects,
          Map<Integer, Vector2> unitHeads, Map<Integer, Vector2> unitPositions, Coords hovered, int hoveredUnit,
          float hexPixels, float hoverTop, Map<TargetKey, Vector2> targetHeads) {
        static final HudView EMPTY = new HudView(false, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0);

        HudView {
            unitRects = Map.copyOf(unitRects);
            unitHeads = Map.copyOf(unitHeads);
            unitPositions = Map.copyOf(unitPositions);
            targetHeads = Map.copyOf(targetHeads);
        }

        /** The facts while no building floor is under the pointer and every fire target is a unit. */
        HudView(boolean tactical, boolean playbackBusy, Map<Integer, Rectangle> unitRects,
              Map<Integer, Vector2> unitHeads, Map<Integer, Vector2> unitPositions, Coords hovered, int hoveredUnit,
              float hexPixels) {
            this(tactical, playbackBusy, unitRects, unitHeads, unitPositions, hovered, hoveredUnit, hexPixels,
                  Float.NaN, Map.of());
        }

        /** A fire target's anchor: a unit's head, else its hex's; null where the view draws neither. */
        Vector2 head(TargetKey target) {
            return (target.unitId() != Entity.NONE) ? unitHeads.get(target.unitId()) : targetHeads.get(target);
        }
    }

    /**
     * What every component reads in one frame. {@code dialog} is the pending native dialog, or null;
     * {@code panelBounds} are the stage bounds (y up) of the panels the last layout showed, which board labels and
     * target cards keep clear of (the prototype's HUD rectangles).
     */
    record Inputs(GpuBoardSource.Frame frame, HudView view, GpuBoardWindow.DialogRequest dialog,
          GpuBoardSource.UiPreferences preferences, Metrics metrics, List<Rectangle> panelBounds) { }

    /**
     * One hud-v3 component (rebuild plan C.1): it builds its actor once and shows the snapshots of each frame. Units
     * come from {@link GpuHudState#presentedUnits()} only. The Esc chain calls the {@code cancel()} of the context
     * menu, the dock, the LOS card, the unit sheet, the weapons panel and the modal; every other panel's open state is
     * a {@link GpuHudState} flag that the HUD closes.
     */
    interface Component {
        /** The actor the HUD places in the component's slot and layer. */
        Actor actor();

        /** One frame's snapshots and layout inputs. */
        void update(Inputs inputs);

        /** Frees what the component owns; the HUD calls it once. */
        default void dispose() {
        }
    }

    final Stage stage;
    final GpuHudState state;
    private final GpuBoardSource source;
    private final GpuHudKit kit;
    private final GpuContextMenu contextMenu;
    /** The board labels draw the leaders to the target cards as the cards placed themselves this frame. */
    private final GpuBoardLabels boardLabels;
    private final GpuTargetCards targetCards;
    private final GpuRecordSheet recordSheet;
    private final GpuWeaponsPanel weapons;
    private final GpuCommandDock dock;
    private final GpuChatPanel chat;
    private final GpuLogPanel log;
    private final GpuModalDialog modal;
    /** The layer of Help, Menu, Players and the tuning panel: a focus in it is that dialog's Esc step. */
    private final Group dialogs;
    /** Every component, bottom layer first and the developer tuning tool last: the order of updates. */
    private final List<Component> components;
    private final Container<Actor> phaseSlot;
    private final Container<Actor> initiativeSlot;
    private final Container<Actor> conditionsSlot;
    private final Container<Actor> forcesSlot;
    private final Container<Actor> unitSlot;
    private final Container<Actor> sheetSlot;
    private final Container<Actor> utilitySlot;
    private final Container<Actor> chipSlot;
    private final Container<Actor> hintSlot;
    private final Container<Actor> pickSlot;
    private final Container<Actor> minimapSlot;
    private final Container<Actor> contactsSlot;
    private final Container<Actor> weaponsSlot;
    private final Container<Actor> solutionSlot;
    private final Container<Actor> logSlot;
    private final Container<Actor> dockSlot;
    private final Container<Actor> chatButtonSlot;
    private final Container<Actor> chatSlot;
    private final Container<Actor> overviewSlot;
    private final Container<Actor> helpSlot;
    private final Container<Actor> menuSlot;
    private final Container<Actor> playersSlot;
    private final Container<Actor> toastSlot;
    private final Container<Actor> modalSlot;
    /** The Tactical View's north mark: part of the board drawing, under the board labels. */
    private final Container<Actor> northSlot;
    /**
     * The slots that span the window: board labels, target cards, the context menu and the tuning panel. Their Table
     * roots take presses on their widgets only (Touchable.childrenOnly, a Table's default).
     */
    private final List<Container<Actor>> windowSlots;
    /** The prototype's panels and the hint line, whose shown bounds are the frame's panel bounds. */
    private final List<Container<Actor>> panelSlots = new ArrayList<>();
    /** Keys whose press the HUD consumed, so their release is consumed as well. */
    private final Set<Integer> consumedKeys = new HashSet<>();
    private Metrics metrics;
    private float scale = 1;
    private Inputs inputs;
    private int publishedFocus = Entity.NONE;
    private int publishedCard = Entity.NONE;
    /** The unit the HUD selects once the local turn began (C.5), held while a dialog would drop the command. */
    private int pendingSelect = Entity.NONE;
    /** The key that opened the chat also types a character (Enter's newline) that must not start the message. */
    private boolean swallowTyped;

    /**
     * Builds the HUD on the render thread. The skin, the batch and the tuning model stay with their owner; the camera
     * is the board view's, which the utilities, minimap, dock, context menu and tuning panel use, and so is the
     * playback {@code history}, which the board feeds and the dock, the log, the labels and the keys review.
     */
    GpuHud(GpuBoardSource source, Skin skin, Batch batch, BoardCamera camera, GpuBoardTuning tuningModel,
          GpuPlaybackHistory history) {
        this.source = source;
        state = new GpuHudState(history);
        kit = new GpuHudKit(skin);
        stage = new Stage(new ScreenViewport(), batch) {
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                // A press anywhere else ends a text field's or a grip's keyboard focus (rebuild plan A.17 Q1).
                Actor focus = getKeyboardFocus();
                Vector2 point = screenToStageCoordinates(new Vector2(screenX, screenY));
                Actor target = hit(point.x, point.y, true);
                if (focus != null && (target == null || !target.isDescendantOf(focus))) {
                    setKeyboardFocus(null);
                }
                return super.touchDown(screenX, screenY, pointer, button);
            }
        };
        // The prototype's stacking, bottom to top, with the native modal above everything.
        Group labels = layer();
        Group panels = layer();
        Group chatLayer = layer();
        Group overviewLayer = layer();
        dialogs = layer();
        Group popover = layer();
        Group toastLayer = layer();
        Group modalLayer = layer();
        // First: the components with unit menus and select lists open this one.
        contextMenu = new GpuContextMenu(kit, source, state, this::select);
        GpuNameplates nameplates = new GpuNameplates(kit, source, state);
        targetCards = new GpuTargetCards(kit, source, state, contextMenu);
        GpuPhaseHeader phaseHeader = new GpuPhaseHeader(kit, source, state);
        GpuInitiativeCard initiativeCard = new GpuInitiativeCard(kit, source, state);
        GpuConditionsCard conditionsCard = new GpuConditionsCard(kit, source, state);
        GpuForcesPanel forces = new GpuForcesPanel(kit, source, state, contextMenu, this::select);
        // The unit panel's paperdoll geometry, read once per window (unit panel design 6.2).
        GpuPaperdolls paperdolls = new GpuPaperdolls();
        GpuUnitCard unitCard = new GpuUnitCard(kit, source, state, contextMenu, paperdolls);
        recordSheet = new GpuRecordSheet(kit, source, state, contextMenu, paperdolls);
        GpuUtilityBar utilities = new GpuUtilityBar(kit, source, state, camera, tuningModel);
        GpuHintLine hint = new GpuHintLine(kit, source, state, camera);
        GpuMinimap minimap = new GpuMinimap(kit, source, state, camera);
        GpuContactsPanel contacts = new GpuContactsPanel(kit, source, state, contextMenu, this::select);
        // The guides follow the contacts panel's toggles and open row; hex-anchored labels need the board camera.
        boardLabels = new GpuBoardLabels(kit, source, state, camera, contacts);
        weapons = new GpuWeaponsPanel(kit, source, state, contextMenu);
        GpuSolutionCard solution = new GpuSolutionCard(kit, source, state);
        log = new GpuLogPanel(kit, source, state, contextMenu);
        dock = new GpuCommandDock(kit, source, state, camera, contextMenu);
        chat = new GpuChatPanel(kit, source, state);
        GpuForceOverview overview = new GpuForceOverview(kit, source, state, contextMenu, this::select);
        GpuHelpDialog help = new GpuHelpDialog(kit, source, state);
        GpuMenuPanel menu = new GpuMenuPanel(kit, source, state, camera);
        GpuPlayersPanel players = new GpuPlayersPanel(kit, source, state);
        GpuToastStack toasts = new GpuToastStack(kit, source, state);
        modal = new GpuModalDialog(kit, source, state);
        List<Component> parts = new ArrayList<>(List.of(nameplates, boardLabels, targetCards, phaseHeader,
              initiativeCard, conditionsCard, forces, unitCard, recordSheet, utilities, hint, minimap, contacts,
              weapons, solution, log, dock, chat, overview, help, menu, players, contextMenu, toasts, modal));

        // The Tactical View's north mark first: the prototype draws it on the board, under the labels (G4). Then the
        // prototype's #fx strokes (guides, traces, leaders) under every board label, the nameplates included (G8b).
        northSlot = slot(labels, utilities.north());
        // The unit panel's hover card and slot popover share the popover layer with the context menu.
        List<Container<Actor>> spanning = new ArrayList<>(List.of(slot(labels, boardLabels.fx()),
              slot(labels, nameplates.actor()), slot(labels, boardLabels.actor()), slot(labels, targetCards.actor()),
              slot(popover, unitCard.overlay()), slot(popover, recordSheet.overlay()),
              slot(popover, contextMenu.actor())));
        phaseSlot = panel(panels, phaseHeader.actor()).top().left().fillX();
        conditionsSlot = panel(panels, conditionsCard.actor()).top().left().fillX();
        forcesSlot = panel(panels, forces.actor()).top().left().fillX();
        unitSlot = panel(panels, unitCard.actor()).bottom().left().fillX();
        sheetSlot = panel(panels, recordSheet.actor()).fill();
        Table utilityRow = new Table();
        utilityRow.add(utilities.actor());
        utilitySlot = panel(panels, utilityRow).top().right();
        chipSlot = panel(panels, utilities.chip()).top();
        minimapSlot = panel(panels, minimap.actor()).top().fillX();
        contactsSlot = panel(panels, contacts.actor()).top().fillX();
        weaponsSlot = panel(panels, weapons.actor()).top().fillX();
        logSlot = panel(panels, log.actor()).top().fillX();
        solutionSlot = panel(panels, solution.actor()).top().fillX();
        initiativeSlot = panel(panels, initiativeCard.actor()).top().fillX();
        // Presses pass through the hint line to the board, as in the prototype; board labels keep clear of it.
        hintSlot = slot(panels, hint.actor());
        panelSlots.add(hintSlot);
        // Where the window has no room for the hint line, a pick's chip stands on the dock with Done and Cancel.
        pickSlot = panel(panels, hint.chip());
        dockSlot = panel(panels, dock.actor()).bottom().fillX();
        chatButtonSlot = panel(panels, chat.button()).bottom().right();
        chatSlot = panel(chatLayer, chat.actor()).fill();
        overviewSlot = panel(overviewLayer, overview.actor()).fill();
        helpSlot = panel(dialogs, help.actor()).fillX();
        menuSlot = panel(dialogs, menu.actor()).fillX();
        playersSlot = panel(dialogs, players.actor()).fillX();
        toastSlot = slot(toastLayer, toasts.actor()).bottom();
        // The modal's window-sized root is its scrim: every press around the dialog lands on it.
        modal.actor().setTouchable(Touchable.enabled);
        modalSlot = slot(modalLayer, modal.actor()).fill();

        // The developer tuning utility (user correction 5): its button ends the utility row, its panel places itself.
        // Before release, delete this block, the tuningModel parameter, GpuHudState.Dialog.TUNING and GpuTuningPanel.
        GpuTuningPanel tuning = new GpuTuningPanel(kit, source, state, camera, tuningModel);
        utilityRow.add(tuning.button()).padLeft(8);
        parts.add(tuning);
        spanning.add(slot(dialogs, tuning.actor()));

        components = List.copyOf(parts);
        windowSlots = List.copyOf(spanning);
        windowSlots.forEach(Container::fill);
        metrics = Metrics.of(stage.getWidth(), stage.getHeight());
    }

    private Group layer() {
        Group layer = new Group();
        layer.setTransform(false);
        layer.setTouchable(Touchable.childrenOnly);
        stage.addActor(layer);
        return layer;
    }

    /** A slot the HUD positions; its own empty area never takes a press meant for the board. */
    private static Container<Actor> slot(Group layer, Actor actor) {
        Container<Actor> slot = new Container<>(actor);
        slot.setTouchable(Touchable.childrenOnly);
        layer.addActor(slot);
        return slot;
    }

    /**
     * A slot for one of the prototype's panels. As there, the panel takes every press on its area, padding included,
     * so none reaches the board below it.
     */
    private Container<Actor> panel(Group layer, Actor actor) {
        actor.setTouchable(Touchable.enabled);
        Container<Actor> slot = slot(layer, actor);
        panelSlots.add(slot);
        return slot;
    }

    /** The window's logical size and the display scale; one stage unit is one prototype CSS pixel. */
    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public void resize(int width, int height, float displayScale) {
        scale = displayScale;
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / displayScale);
        stage.getViewport().update(width, height, true);
        metrics = Metrics.of(stage.getWidth(), stage.getHeight());
        layout();
    }

    /**
     * One frame's snapshots. Applies the frame's state ({@link #updateState}), updates every component and lays them
     * out.
     */
    @Override
    public void update(GpuBoardSource.Frame frame, HudView view, GpuBoardWindow.DialogRequest dialog,
          GpuBoardSource.UiPreferences preferences) {
        // The panels stay where the last layout put them until this frame's layout. A cleared selection presents the
        // local turn without an acting unit (GpuHudState.presented).
        inputs = new Inputs(state.presented(frame), view, dialog, preferences, metrics, panelBounds());
        updateState(frame, view.playbackBusy(), dialog);
        for (Component component : components) {
            component.update(inputs);
        }
        // The leaders run to the cards where the cards placed themselves in this frame (G9).
        boardLabels.cards(targetCards.placed());
        keepModalFocus();
        layout();
    }

    /**
     * Applies the focus rule (C.5) and the presented units (C.6) and publishes the focus and card units to the source.
     * They open and close the log and the sheet, and with them the board area between the columns, so the board view
     * applies them before its camera frames the frame; called again with the same frame, it applies nothing twice.
     */
    void updateState(GpuBoardSource.Frame frame, boolean playbackBusy, GpuBoardWindow.DialogRequest dialog) {
        int select = state.update(frame.status(), frame.panels().record(), playbackBusy);
        if (select != Entity.NONE) {
            pendingSelect = select;
        }
        if (pendingSelect != Entity.NONE && (!frame.status().myTurn() || state.focus() != pendingSelect)) {
            // The turn or the focus moved on first.
            pendingSelect = Entity.NONE;
        } else if (pendingSelect != Entity.NONE && dialog == null) {
            // Not while a dialog is pending: the source's input guard would drop the command.
            source.selectUnit(pendingSelect);
            pendingSelect = Entity.NONE;
        }
        if (state.focus() != publishedFocus) {
            publishedFocus = state.focus();
            source.setFocusUnit(publishedFocus);
        }
        int card = state.cardUnit();
        if (card != publishedCard) {
            publishedCard = card;
            source.setCardUnit(card);
        }
    }

    /** While a dialog is pending the keyboard focus stays inside it; afterwards nothing of it keeps the focus. */
    private void keepModalFocus() {
        Actor focus = stage.getKeyboardFocus();
        if (inputs.dialog() != null && (focus == null || !focus.isDescendantOf(modal.actor()))) {
            stage.setKeyboardFocus(modal.actor());
        } else if (inputs.dialog() == null && focus != null && focus.isDescendantOf(modal.actor())) {
            stage.setKeyboardFocus(null);
        }
    }

    /** The stage bounds (y up) of the shown panels, as the last layout placed them. */
    List<Rectangle> panelBounds() {
        List<Rectangle> bounds = new ArrayList<>();
        for (Container<Actor> slot : panelSlots) {
            if (shown(slot)) {
                slot.validate();
                Actor panel = slot.getActor();
                bounds.add(new Rectangle(slot.getX() + panel.getX(), slot.getY() + panel.getY(), panel.getWidth(),
                      panel.getHeight()));
            }
        }
        return List.copyOf(bounds);
    }

    /** Draws the HUD over the board. The kit's sprite masks follow the units, reports and toasts of the snapshots. */
    @Override
    public void draw() {
        Set<BoardScene.Pixels> sprites = new HashSet<>();
        if (inputs != null) {
            state.presentedUnits().stream().map(GpuBattleStatus.UnitStatus::icon).forEach(sprites::add);
            inputs.frame().status().units().stream().map(GpuBattleStatus.UnitStatus::icon).forEach(sprites::add);
            sprites.addAll(inputs.frame().reports().icons().values());
            inputs.frame().panels().toasts().toasts().stream().map(GpuToasts.Toast::icon).forEach(sprites::add);
            sprites.remove(null);
        }
        kit.update(sprites);
        stage.getViewport().apply();
        stage.act(Math.min(Gdx.graphics.getDeltaTime(), .1f));
        stage.draw();
    }

    /** True over a HUD widget, and everywhere while a dialog is pending (its scrim takes every press). */
    @Override
    public boolean hit(int x, int y) {
        if (inputs != null && inputs.dialog() != null) {
            return true;
        }
        Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
        return stage.hit(point.x, point.y, true) != null;
    }

    /** True over the minimap's canvas, which a drag moves the camera on (the prototype's grab pointer). */
    @Override
    public boolean dragsCamera(int x, int y) {
        Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
        Actor target = stage.hit(point.x, point.y, true);
        return target != null && "minimap-canvas".equals(target.getName());
    }

    @Override
    public boolean isTextEditing() {
        return stage.getKeyboardFocus() instanceof TextField;
    }

    /**
     * A key press (C.4), with its libGDX code and its AWT code and modifiers. Returns true when the HUD consumed it;
     * the board view then neither moves the camera nor forwards it to Swing. The order: a pending dialog takes every
     * key; the CANCEL bind runs the Esc chain; a focused text field takes every other key; a focused grip or menu gets
     * the key first; then the hotkeys.
     */
    @Override
    public boolean keyDown(int key, int awt, int modifiers) {
        swallowTyped = false;
        boolean consumed = inputs != null && dispatch(key, binds(awt, modifiers));
        if (consumed) {
            consumedKeys.add(key);
        }
        return consumed;
    }

    private boolean dispatch(int key, Set<KeyCommandBind> binds) {
        if (inputs.dialog() != null) {
            // The modal's controls handle Enter and the arrows; CANCEL presses its cancel button.
            if (binds.contains(KeyCommandBind.CANCEL)) {
                modal.cancel();
            } else {
                stage.keyDown(key);
            }
            return true;
        }
        if (binds.contains(KeyCommandBind.CANCEL)) {
            return escape() || state.turnLocked(inputs.frame().status());
        }
        Actor focus = stage.getKeyboardFocus();
        if (focus instanceof TextField) {
            stage.keyDown(key);
            return true;
        }
        // A focused grip reorders on Alt+Up/Down and an open menu takes its navigation keys.
        return focus != null && stage.keyDown(key) || hotkey(binds, focus != null);
    }

    /**
     * True when the release belongs to a press the HUD consumed. Releasing the nameplate key ends the nameplates,
     * whatever other modifier is still held.
     */
    @Override
    public boolean keyUp(int key, int awt) {
        if (inputs != null && inputs.preferences().binds().stream()
              .anyMatch(bind -> bind.command() == KeyCommandBind.SHOW_NAMEPLATES && bind.keyCode() == awt)) {
            state.altHeld = false;
        }
        stage.keyUp(key);
        return consumedKeys.remove(key);
    }

    /** A typed character; true while a text field or a pending dialog takes the keyboard. */
    @Override
    public boolean keyTyped(char character) {
        if (swallowTyped) {
            swallowTyped = false;
            return true;
        }
        boolean typing = isTextEditing() || inputs != null && inputs.dialog() != null;
        return stage.keyTyped(character) || typing;
    }

    /** The window lost the focus: no key is held any more. */
    @Override
    public void focusLost() {
        state.altHeld = false;
        consumedKeys.clear();
    }

    private Set<KeyCommandBind> binds(int awt, int modifiers) {
        return binds(inputs.preferences().binds(), awt, modifiers);
    }

    /**
     * The commands of {@code binds}, the key bindings the client captured on the Swing thread, that a key press with
     * the AWT code and modifiers {@code awt} and {@code modifiers} invokes.
     */
    static Set<KeyCommandBind> binds(List<GpuBoardSource.Bind> binds, int awt, int modifiers) {
        Set<KeyCommandBind> matched = EnumSet.noneOf(KeyCommandBind.class);
        for (GpuBoardSource.Bind bind : binds) {
            if (KeyCommandBind.matches(bind.keyCode(), bind.modifiers(), awt, modifiers)) {
                matched.add(bind.command());
            }
        }
        return matched;
    }

    /**
     * The Esc chain (C.4), one step per press. In FIRING, TARGETING, OFFBOARD (the targeting display) and PHYSICAL it
     * never forwards CANCEL, which would clear declared attacks; elsewhere the last step forwards it to the phase
     * display.
     */
    private boolean escape() {
        // A row dragged in a list (a target card's attacks, the sheet's weapons) goes home first.
        if (targetCards.cancelDrag() || recordSheet.cancelDrag()) {
            return true;
        }
        GamePhase phase = inputs.frame().status().phase();
        Actor focus = stage.getKeyboardFocus();
        // A popover's content holds the focus while it is open; its owner's step closes it. So do the list of an open
        // Help, Menu or Players dialog and the tuning panel's choice list: the dialog's step closes it with them.
        boolean inDialog = focus != null && state.dialog != GpuHudState.Dialog.NONE && focus.isDescendantOf(dialogs);
        if (focus != null && !inDialog && !focus.isDescendantOf(contextMenu.actor())
              && !focus.isDescendantOf(recordSheet.overlay())) {
            stage.setKeyboardFocus(null);
        } else if (contextMenu.back()) {
            return true;
        } else if (state.dialog != GpuHudState.Dialog.NONE) {
            state.dialog = GpuHudState.Dialog.NONE;
            if (inDialog) {
                stage.setKeyboardFocus(null);
            }
        } else if (state.overview) {
            state.overview = false;
        } else if (state.chatOpen) {
            state.chatOpen = false;
        } else if (picking(inputs)) {
            source.players().endPick(false);
        } else if (dock.cancel() || recordSheet.cancel()) {
            // The sheet's step: its popover, the expanded weapon row, then the sheet (U3).
            return true;
        } else if (weapons.cancel()) {
            return true;
        } else if (planning(inputs) && !inputs.frame().panels().move().route().isEmpty()) {
            source.moves().clearRoute();
        } else if (state.inspected != Entity.NONE) {
            state.inspected = Entity.NONE;
        } else {
            return phase.isFiring() || phase.isTargeting() || phase.isOffboard() || phase.isPhysical();
        }
        return true;
    }

    /**
     * The local movement turn of a unit the HUD plans for. Other movement (aerospace, vector movement, charge or DFA
     * gears; plan A.7 G20) keeps MegaMek's own keys, CANCEL and board tool. The hint line and the hex menu read it too.
     */
    static boolean planning(Inputs inputs) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        return status.myTurn() && status.phase().isMovement() && inputs.frame().panels().move().planner();
    }

    /**
     * A bot order picks hexes (its HexTargetPicker runs): a left board click picks a hex, the Done key ends the pick
     * with the order and Esc without it. The hint line reads it too.
     */
    static boolean picking(Inputs inputs) {
        return inputs.frame().panels().players().pick() != null;
    }

    /**
     * The C.4 hotkey table; binds it does not list stay with the camera and Swing. The targeting display's phases
     * (TARGETING, OFFBOARD) keep MegaMek's own keys and board tool, as its turn has no native orders (lead decision
     * D1). The physical attack keys PHYS_PUNCH, PHYS_KICK and PHYS_PUSH stay MegaMek's too: with the native dialogs,
     * which skip the physical "To hit" question, they declare their attack at once.
     */
    private boolean hotkey(Set<KeyCommandBind> binds, boolean focused) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GamePhase phase = status.phase();
        boolean moving = planning(inputs);
        boolean locked = state.turnLocked(status);
        boolean firing = status.myTurn() && phase.isFiring() && !locked;
        GpuMovePlan.Mode mode = binds.contains(KeyCommandBind.MOVE_MODE_WALK) ? GpuMovePlan.Mode.WALK
              : binds.contains(KeyCommandBind.MOVE_MODE_RUN) ? GpuMovePlan.Mode.RUN
              : binds.contains(KeyCommandBind.MOVE_MODE_JUMP) ? GpuMovePlan.Mode.JUMP : null;
        int framed = status.actorId() != Entity.NONE ? status.actorId() : state.inspected;
        if (state.logOpen() && log.key(binds)) {
            // The report keys (REPORT_KEY_NEXT and the like) find and filter in the open log.
            return true;
        }
        if (binds.contains(KeyCommandBind.SHOW_NAMEPLATES)) {
            // Alt with a focused grip belongs to the grip's reordering.
            state.altHeld = !focused;
        } else if (binds.contains(KeyCommandBind.ROUND_REPORT)) {
            state.toggleLog();
        } else if (binds.contains(KeyCommandBind.KEY_BINDS)) {
            state.toggle(GpuHudState.Dialog.HELP);
        } else if (binds.contains(KeyCommandBind.BOT_COMMANDS)) {
            state.toggle(GpuHudState.Dialog.PLAYERS);
        } else if (binds.contains(KeyCommandBind.LOS_SETTING)) {
            // View > Ruler / LOS Tool: MegaMek's ruler, which line of sight stays (the user's decision of 2026-10-03).
            BoardScene.Command ruler = GpuBoardActions.menuItem(inputs.frame().globalCommands(),
                  ClientGUI.VIEW_LOS_SETTING);
            if (ruler != null && ruler.enabled()) {
                ruler.action().run();
            }
        } else if (binds.contains(KeyCommandBind.UNIT_OVERVIEW) || binds.contains(KeyCommandBind.FORCE_DISPLAY)) {
            state.overview = !state.overview;
        } else if (binds.contains(KeyCommandBind.UNIT_DISPLAY)) {
            // With the overview open, the key closes it and shows the sheet (unit panel design 12).
            state.recordOpen = state.overview || !state.recordOpen;
            state.overview = false;
        } else if (sheetTab(binds) != null) {
            GpuRecordSheet.show(state, sheetTab(binds));
        } else if (binds.contains(KeyCommandBind.FORCES_GRID)) {
            // One wide left panel at a time: the key closes an open sheet and shows the grid.
            state.forcesGrid = GpuRecordSheet.open(state) || !state.forcesGrid;
            state.recordOpen = false;
        } else if (binds.contains(KeyCommandBind.TOGGLE_CHAT) || binds.contains(KeyCommandBind.TOGGLE_CHAT_CMD)) {
            state.chatOpen = true;
            chat.focus();
            // The "/" of TOGGLE_CHAT_CMD starts the message; the character of TOGGLE_CHAT (Enter's newline) does not.
            swallowTyped = !binds.contains(KeyCommandBind.TOGGLE_CHAT_CMD);
        } else if (binds.contains(KeyCommandBind.DONE) && picking(inputs)) {
            source.players().endPick(true);
        } else if (binds.contains(KeyCommandBind.DONE)) {
            if (!locked) {
                dock.main();
            }
        } else if (binds.contains(KeyCommandBind.NEXT_UNIT) || binds.contains(KeyCommandBind.PREV_UNIT)) {
            // MegaMek's next or previous unit becomes the selected one, also when it is the current unit again.
            state.restoreSelection();
            return false;
        } else if (moving && mode != null) {
            source.moves().setMode(mode);
        } else if (moving && (binds.contains(KeyCommandBind.TURN_LEFT) || binds.contains(KeyCommandBind.TURN_RIGHT))) {
            source.moves().turn(binds.contains(KeyCommandBind.TURN_LEFT) ? -1 : 1);
        } else if (firing
              && (binds.contains(KeyCommandBind.TWIST_LEFT) || binds.contains(KeyCommandBind.TWIST_RIGHT))) {
            dock.twist(binds.contains(KeyCommandBind.TWIST_LEFT) ? -1 : 1);
        } else if (moving && binds.contains(KeyCommandBind.UNDO_LAST_STEP)) {
            source.moves().undo();
        } else if (firing && binds.contains(KeyCommandBind.UNDO_LAST_STEP)) {
            source.fire().removeLast();
        } else if (moving && binds.contains(KeyCommandBind.CLEAR_ORDERS)) {
            source.moves().clearRoute();
        } else if (firing && binds.contains(KeyCommandBind.CLEAR_ORDERS)) {
            dock.clearOrders();
        } else if (phase.isReport() && binds.contains(KeyCommandBind.PLAYBACK_TOGGLE)) {
            state.history.togglePaused();
        } else if (phase.isReport()
              && (binds.contains(KeyCommandBind.PLAYBACK_PREV) || binds.contains(KeyCommandBind.PLAYBACK_NEXT))) {
            state.history.step(binds.contains(KeyCommandBind.PLAYBACK_PREV) ? -1 : 1);
        } else if (!phase.isReport() && binds.contains(KeyCommandBind.CENTER_ON_SELECTED) && framed != Entity.NONE) {
            source.locateUnit(framed);
        } else {
            // MegaMek gets the rest, but with the selection cleared in the local turn only the binds of no unit.
            return locked && !binds.stream().allMatch(GpuHud::unitless);
        }
        return true;
    }

    /**
     * Whether MegaMek may get {@code bind} while the player cleared the selection in the local turn: its menu bar's
     * binds and {@link #UNITLESS_BINDS} act on no unit. Every other bind stays with the HUD, so MegaMek's current unit
     * never acts.
     */
    private static boolean unitless(KeyCommandBind bind) {
        return bind.isMenuBar || UNITLESS_BINDS.contains(bind);
    }

    /** The unit sheet's tab of the Unit Display's keys F1 to F6 ({@code UD_GENERAL} to {@code UD_EXTRAS}), or null. */
    private static GpuHudState.SheetTab sheetTab(Set<KeyCommandBind> binds) {
        for (GpuHudState.SheetTab tab : GpuHudState.SheetTab.values()) {
            if (binds.contains(tab.key)) {
                return tab;
            }
        }
        return null;
    }

    /**
     * A press on the board, outside every panel; GpuBattleView calls it on every board pointer-down, before a drag
     * pans or orbits. As a press outside them in the prototype, it ends a keyboard focus and closes the open menu.
     */
    @Override
    public void boardPress() {
        if (inputs != null && inputs.dialog() == null) {
            stage.setKeyboardFocus(null);
            contextMenu.cancel();
        }
    }

    /**
     * A short board click (C.4) on {@code coords} and the unit {@code unitId} picked there ({@code Entity.NONE} for
     * none), at screen pixel ({@code x}, {@code y}), with the world height of the terrain the pointer hit
     * ({@code pointedZ}; NaN on a unit, or in the Tactical View). A right click opens the context menu and never
     * changes orders; Ctrl or Alt keeps MegaMek's measurement tools, measuring at the pointed height, and a plain left
     * click ends a measurement waiting for its second point with that measurement's modifier (rimshaderv1's board);
     * while a bot order picks hexes, a left click picks one, as the classic board's click does.
     */
    void boardClick(Coords coords, int unitId, int button, int modifiers, int x, int y, float pointedZ) {
        if (inputs == null || inputs.dialog() != null || coords == null || inputs.frame().scene() == null) {
            return;
        }
        int clickModifiers = GpuBoardSource.isMeasurement(modifiers) ? modifiers
              : modifiers | inputs.frame().panels().los().pending();
        if (button == Input.Buttons.RIGHT) {
            Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
            contextMenu.open(coords, unitId, point.x, point.y, pointedZ);
        } else if (button == Input.Buttons.LEFT && GpuBoardSource.isMeasurement(clickModifiers)) {
            source.measure(coords, clickModifiers, pointedZ);
        } else if (button == Input.Buttons.LEFT && picking(inputs)) {
            source.click(coords, false, clickModifiers);
        } else if (button == Input.Buttons.LEFT) {
            leftClick(coords, GpuHudState.unit(inputs.frame().status(), unitId), modifiers);
        }
    }

    /**
     * Left click by phase: the HUD's own gestures during the local turn, MegaMek's board tool for everything else of
     * that turn, and the selection rule outside it, as for a unit row.
     */
    private void leftClick(Coords coords, GpuBattleStatus.UnitStatus unit, int modifiers) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuHudData data = inputs.frame().panels();
        GamePhase phase = status.phase();
        boolean own = unit != null && unit.side() == GpuBattleStatus.Side.OWN;
        boolean shift = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
        if (!status.myTurn() || state.turnLocked(status)) {
            // Outside the local turn, and in it with the selection cleared: a unit is selected or inspected, a hex
            // does nothing.
            if (unit != null) {
                select(unit.id());
            }
        } else if (planning(inputs)) {
            // As in the prototype, a hex plans the route (Shift pins it), and so does Shift on a unit, except on an
            // own unit before a route exists.
            if (unit == null || shift && (!own || !data.move().route().isEmpty())) {
                source.moves().planTo(coords, inputs.frame().scene().boardId(), shift);
            } else {
                select(unit.id());
            }
        } else if (phase.isFiring() && unit != null) {
            if (own) {
                select(unit.id());
            } else if (unit.sensorContact()) {
                // Not targetable (H19): the fire orders refuse it with their toast, and the armed weapon stays armed.
                source.fire().focusTarget(TargetKey.unit(unit.id()));
            } else {
                GpuContextMenu.focusTarget(source, state, TargetKey.unit(unit.id()));
            }
        } else if (phase.isFiring()) {
            // A hex: MegaMek's board click chooses its target, a building or a wooded hex too, and the armed weapon
            // fires at it once, as at an enemy.
            source.hover(coords, modifiers);
            source.fire().clickHex(coords, modifiers, state.armedWeapon);
            state.armedWeapon = -1;
        } else if (phase.isPhysical() && unit != null) {
            if (own) {
                select(unit.id());
            } else {
                state.inspected = unit.id();
                if (data.physical().adjacentTargets().contains(unit.id())) {
                    source.physical().target(unit.id());
                }
            }
        } else {
            // Deployment, the targeting display's phases (D1), non-planner movement and every other tool of the turn.
            source.hover(coords, modifiers);
            source.click(coords, false, modifiers);
        }
    }

    /**
     * The one selection rule (the prototype's app.js selectUnit), for board clicks and for the unit rows, cards and
     * menus of the components: an own unit that can act now in the local MOVEMENT, FIRING, TARGETING, OFFBOARD or
     * PHYSICAL turn is selected through that phase's command (the fire orders' in FIRING, the display's own selection
     * elsewhere). Outside the local turn, where MegaMek selects nothing, an own unit becomes the focus (C.5), which the
     * card and the panels show as the selected unit (the user's decision of 2026-10-02). Any other unit is inspected.
     * Selecting ends an inspection and a cleared selection (the card's ✕); selecting the acting unit again posts
     * nothing, so its plan stays, unless the selection was cleared.
     */
    void select(int unitId) {
        GpuBattleStatus.Snapshot status = inputs == null ? GpuBattleStatus.Snapshot.EMPTY : inputs.frame().status();
        GpuBattleStatus.UnitStatus unit = GpuHudState.unit(status, unitId);
        if (unit == null) {
            return;
        }
        boolean own = unit.side() == GpuBattleStatus.Side.OWN;
        if (own && !status.myTurn()) {
            state.restoreSelection();
            state.inspected = Entity.NONE;
            state.pick(unitId);
            return;
        }
        GamePhase phase = status.phase();
        boolean firing = phase.isFiring();
        boolean ready = status.myTurn() && own && unit.canActNow()
              && (firing || phase.isMovement() || phase.isPhysical() || phase.isTargeting() || phase.isOffboard());
        // Only a selection ends a cleared one; an inspected unit leaves it cleared. With the selection cleared the
        // presented status has no actor, so MegaMek selects even its current unit again, afresh.
        state.inspected = ready ? Entity.NONE : unitId;
        if (ready) {
            state.restoreSelection();
        }
        if (ready && unitId != status.actorId()) {
            if (firing) {
                source.fire().selectUnit(unitId);
            } else {
                source.selectUnit(unitId);
            }
        }
    }

    /** Window pixels left of the unobstructed board: the left column and its two gaps (rebuild plan A.1 A5). */
    @Override
    public float cameraLeft() {
        return (2 * metrics.gap() + leftWidth()) * scale;
    }

    /** The left column's width: the grid's while the unit sheet or the forces grid is open (unit panel design 3.6). */
    private float leftWidth() {
        return state.forcesGrid || GpuRecordSheet.open(state) && !state.overview ? metrics.grid() : metrics.left();
    }

    /** Window pixels between the left and right columns and their gaps. */
    @Override
    public float cameraWidth() {
        return Math.max(1, (metrics.width() - 4 * metrics.gap() - leftWidth() - rightWidth()) * scale);
    }

    /** The right column's width: the log's while it is open (r1 3.15). */
    private float rightWidth() {
        return state.logOpen() ? metrics.log() : metrics.right();
    }

    /**
     * Places every slot as the prototype's CSS and layout() do (r1 section 2), from the metrics and the current
     * content sizes. Panels of the middle area stack below the top row's panels they would overlap and stay above
     * the dock; the tactical chip hides when the top row has no room for it (the pressed Tactical-view utility shows
     * the mode).
     */
    private void layout() {
        if (inputs == null) {
            return;
        }
        Metrics m = metrics;
        float width = m.width();
        float height = m.height();
        float gap = m.gap();
        boolean logOpen = state.logOpen();
        boolean weaponsOpen = !logOpen && inputs.frame().panels().fire().active();
        // The forces overview covers both columns and the middle; what lies under it is hidden, where the prototype
        // blurs it (shot 12).
        boolean covered = state.overview;
        unitSlot.setVisible(!covered);
        // The dock acts for the selected unit: none while the player cleared the selection in the local turn.
        dockSlot.setVisible(!covered && !state.turnLocked(inputs.frame().status()));
        hintSlot.setVisible(!covered && !m.narrow());
        pickSlot.setVisible(!covered && m.narrow() && picking(inputs));
        initiativeSlot.setVisible(!covered);
        forcesSlot.setVisible(!covered);
        boolean sheet = GpuRecordSheet.open(state) && !covered;
        sheetSlot.setVisible(sheet);
        minimapSlot.setVisible(!covered && inputs.preferences().minimapEnabled());
        logSlot.setVisible(!covered && logOpen);
        weaponsSlot.setVisible(!covered && weaponsOpen);
        solutionSlot.setVisible(!covered && weaponsOpen);
        contactsSlot.setVisible(!covered && !logOpen && !weaponsOpen && inputs.preferences().contactsEnabled());
        chatSlot.setVisible(state.chatOpen);
        overviewSlot.setVisible(state.overview);
        helpSlot.setVisible(state.dialog == GpuHudState.Dialog.HELP);
        menuSlot.setVisible(state.dialog == GpuHudState.Dialog.MENU);
        playersSlot.setVisible(state.dialog == GpuHudState.Dialog.PLAYERS);
        modalSlot.setVisible(inputs.dialog() != null);
        windowSlots.forEach(slot -> slot.setBounds(0, 0, width, height));
        modalSlot.setBounds(0, 0, width, height);

        // Left column: phase header, forces between it and the unit card. While the unit sheet is open (unit panel
        // design 3.3) the forces shrink to their strip, the card to its mini form, and the sheet fills the column
        // between them at the grid's width; it does not move the dock.
        float phaseHeight = height(phaseSlot);
        place(phaseSlot, gap, gap, m.left(), phaseHeight);
        float cardHeight = height(unitSlot);
        place(unitSlot, gap, height - gap - cardHeight, m.left(), cardHeight);
        float forcesWidth = state.forcesGrid && !sheet ? m.grid() : m.forces();
        float forcesTop = gap + phaseHeight + STACK;
        float forcesBottom = cardHeight > 0 ? height - gap - cardHeight - STACK : height - gap;
        float forcesHeight = sheet ? height(forcesSlot) : forcesBottom - forcesTop;
        place(forcesSlot, gap, forcesTop, forcesWidth, forcesHeight);
        float sheetTop = forcesTop + forcesHeight + STACK;
        place(sheetSlot, gap, sheetTop, m.grid(), forcesBottom - sheetTop);

        // Right column: utilities, minimap, contacts / weapons / log, solution card, chat button.
        float utilityWidth = utilitySlot.getPrefWidth();
        float utilityLeft = width - gap - utilityWidth;
        place(utilitySlot, utilityLeft, UTILITY_TOP, utilityWidth, utilitySlot.getPrefHeight());
        float minimapHeight = height(minimapSlot);
        place(minimapSlot, width - gap - m.right(), SECOND_ROW, m.right(), minimapHeight);
        float rightWidth = rightWidth();
        float rightTop = minimapHeight > 0 ? SECOND_ROW + minimapHeight + STACK : RIGHT_TOP_WITHOUT_MAP;
        float solutionHeight = height(solutionSlot);
        boolean beside = solutionHeight > 0
              && height - rightTop - RIGHT_BOTTOM - solutionHeight - STACK < SOLUTION_ROOM;
        float rightBottom = height - RIGHT_BOTTOM - (solutionHeight > 0 && !beside ? solutionHeight + STACK : 0);
        for (Container<Actor> column : List.of(contactsSlot, weaponsSlot, logSlot)) {
            place(column, width - gap - rightWidth, rightTop, rightWidth, rightBottom - rightTop);
        }
        place(solutionSlot, width - gap - m.right(), height - RIGHT_BOTTOM - solutionHeight, m.right(),
              solutionHeight);
        float chatWidth = chatButtonSlot.getPrefWidth();
        float chatHeight = chatButtonSlot.getPrefHeight();
        place(chatButtonSlot, width - gap - chatWidth, height - CHAT_BUTTON_BOTTOM - chatHeight, chatWidth,
              chatHeight);

        // Middle band between the columns: the dock, the hint line under it and a pick's chip on it, centred in the
        // window where the band allows. It starts after the left column, which the unit card fills, or the wider grid.
        float bandLeft = gap + Math.max(forcesWidth, m.left()) + gap;
        float bandRight = width - gap - rightWidth - gap;
        float dockWidth = Math.min(m.dock(), bandRight - bandLeft);
        float dockHeight = height(dockSlot);
        place(dockSlot, centred(dockWidth, bandLeft, bandRight), height - DOCK_BOTTOM - dockHeight, dockWidth,
              dockHeight);
        float hintWidth = Math.min(hintSlot.getPrefWidth(), bandRight - bandLeft);
        float hintHeight = hintSlot.getPrefHeight();
        place(hintSlot, centred(hintWidth, bandLeft, bandRight), height - HINT_BOTTOM - hintHeight, hintWidth,
              hintHeight);
        float pickWidth = Math.min(pickSlot.getPrefWidth(), dockWidth);
        float pickHeight = height(pickSlot);
        place(pickSlot, centred(pickWidth, bandLeft, bandRight), height - DOCK_BOTTOM - dockHeight - STACK - pickHeight,
              pickWidth, pickHeight);

        // Top row: the conditions card beside the left column (the sheet's width while it is open), the tactical chip
        // centred in the room that is left.
        float conditionsX = gap + leftWidth() + STACK;
        float conditionsWidth = Math.min(CONDITIONS_WIDTH, utilityLeft - STACK - conditionsX);
        place(conditionsSlot, conditionsX, gap, conditionsWidth, height(conditionsSlot));
        float chipLeft = Math.max(gap + m.left(), shown(conditionsSlot) ? conditionsX + conditionsWidth : 0) + STACK;
        float chipRight = utilityLeft - STACK;
        float chipWidth = chipSlot.getPrefWidth();
        chipSlot.setVisible(chipRight - chipLeft >= chipWidth);
        place(chipSlot, centred(chipWidth, chipLeft, chipRight), CHIP_TOP, chipWidth, height(chipSlot));
        // The north mark stays centred under the chip's place, whether or not the chip has room (G4).
        float northWidth = northSlot.getPrefWidth();
        place(northSlot, (width - northWidth) / 2, GpuUtilityBar.NORTH_TOP, northWidth, northSlot.getPrefHeight());

        // Panels of the middle area, each below what it would overlap; one without room for its minimum is hidden.
        float initiativeWidth = Math.min(INITIATIVE_WIDTH, bandRight - bandLeft);
        stack(initiativeSlot, centred(initiativeWidth, bandLeft, bandRight), height * INITIATIVE_TOP,
              initiativeWidth, List.of(conditionsSlot, chipSlot));
        if (beside) {
            stack(solutionSlot, width - gap - 2 * m.right() - STACK, SECOND_ROW, m.right(),
                  List.of(conditionsSlot, chipSlot));
        }

        // Overlays: chat, forces overview, centred dialogs and toasts.
        place(chatSlot, width - gap - CHAT_WIDTH, height - CHAT_BOTTOM - CHAT_HEIGHT, CHAT_WIDTH, CHAT_HEIGHT);
        place(overviewSlot, gap, SECOND_ROW, width - 2 * gap, height - SECOND_ROW - RIGHT_BOTTOM);
        float dialogWidth = Math.min(UiKit.DIALOG_WIDTH, width - 2 * gap);
        for (Container<Actor> dialog : List.of(helpSlot, menuSlot, playersSlot)) {
            float dialogHeight = Math.min(height(dialog), height - UiKit.DIALOG_MARGIN);
            place(dialog, (width - dialogWidth) / 2, (height - dialogHeight) / 2, dialogWidth, dialogHeight);
        }
        float toastWidth = Math.min(GpuToastStack.MAX_WIDTH, width - 2 * gap);
        place(toastSlot, (width - toastWidth) / 2, SECOND_ROW, toastWidth, height - SECOND_ROW - TOAST_BOTTOM);
    }

    /**
     * Places a panel of the middle area at {@code top}, or below every shown panel of {@code above} it overlaps, and
     * keeps it above the dock and a pick's chip where they share columns and above the prototype's bottom margin
     * elsewhere. A panel with less room than its minimum height (a scrolling panel's header) is hidden for the frame:
     * its slot would keep that minimum and spill over the dock.
     */
    private void stack(Container<Actor> slot, float x, float top, float width, List<Container<Actor>> above) {
        float height = metrics.height();
        for (Container<Actor> other : above) {
            if (shown(other) && overlapsHorizontally(other, x, width)) {
                top = Math.max(top, height - other.getY() + STACK);
            }
        }
        float bottom = height - RIGHT_BOTTOM;
        for (Container<Actor> below : List.of(dockSlot, pickSlot)) {
            if (shown(below) && overlapsHorizontally(below, x, width)) {
                bottom = Math.min(bottom, height - below.getY() - below.getHeight() - STACK);
            }
        }
        if (shown(slot) && bottom - top < slot.getMinHeight()) {
            slot.setVisible(false);
        }
        place(slot, x, top, width, Math.min(height(slot), bottom - top));
    }

    private static boolean overlapsHorizontally(Actor actor, float x, float width) {
        return x < actor.getX() + actor.getWidth() && actor.getX() < x + width;
    }

    /** Centred in the window when that fits between {@code left} and {@code right}, else as close as it can. */
    private float centred(float width, float left, float right) {
        return MathUtils.clamp((metrics.width() - width) / 2, left, right - width);
    }

    /** Places a slot by its distance from the window's top, as the prototype's CSS does. */
    private void place(Container<Actor> slot, float x, float top, float width, float height) {
        slot.setBounds(x, metrics.height() - top - Math.max(0, height), width, Math.max(0, height));
    }

    /** The slot's content height, or zero while the HUD or the component hides it. */
    private static float height(Container<Actor> slot) {
        return shown(slot) ? slot.getPrefHeight() : 0;
    }

    private static boolean shown(Container<Actor> slot) {
        return slot.isVisible() && slot.getActor() != null && slot.getActor().isVisible();
    }

    @Override
    public void dispose() {
        components.forEach(Component::dispose);
        stage.dispose();
        kit.dispose();
    }
}
