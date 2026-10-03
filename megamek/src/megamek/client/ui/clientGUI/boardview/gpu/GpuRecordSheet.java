/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.TimeUtils;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.SheetTab;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.units.Entity;

/**
 * The unit sheet (unit panel design 3.3, 5.1): the card unit's record in six tabs above the mini card, at the forces
 * grid's width. Its frame holds the tab row (attention dots on inactive tabs, dimmed unused tabs, and the sheet's ✕ at
 * the right end), the alert strip of the card's chips whose home is not the open tab, and the tab body that
 * {@link GpuUnitSheetTabs} builds for the sheet's density. It owns the slot popover and the lists' keyboard focus, and
 * the Esc chain's sheet step: popover, expanded row, then the sheet itself, which its ✕ closes at once.
 */
final class GpuRecordSheet implements GpuHud.Component {
    /** The sheet's density from its content width (3.4); every tab builder takes it. */
    enum Density {
        WIDE, MEDIUM, NARROW;

        static Density of(float contentWidth) {
            return contentWidth >= 500 ? WIDE : contentWidth >= 380 ? MEDIUM : NARROW;
        }
    }

    /** The sheet's 2-unit side borders and its body's 14-unit padding on each side (.tbody). */
    static final float SIDES = 2 * 2 + 2 * 14;
    /** The tab row (.tabrow: 38, 34 with icon tabs) and the alert strip (.alert: 28, 0 when empty). */
    private static final float TAB_ROW = 38;
    private static final float ICON_TAB_ROW = 34;
    private static final float ALERT = 28;
    /** The tabs' gap (.tabrow 18, compacted 12, icons 14) and the narrow tabs' icon size. */
    private static final float TAB_GAP = 18;
    private static final float COMPACT_TAB_GAP = 12;
    private static final float ICON_TAB_GAP = 14;
    private static final float TAB_ICON = 17;
    /** The sheet's ✕ at the tab row's right end, as large as the card's, and its gap to the tabs. */
    private static final float CLOSE = 28;
    private static final float CLOSE_GAP = 8;
    /** Each tab's icon at narrow density, in tab order. */
    private static final List<String> TAB_ICONS = List.of("info", "group", "layers", "target", "grid", "more");

    /** What the sheet shows; it is rebuilt when this changes. Snapshots keep their identity while unchanged. */
    private record Shown(GpuBattleStatus.UnitStatus unit, GpuUnitRecord.Snapshot record, SheetTab tab, float width,
          String selected, String expanded, int ordinal, Set<String> collapsed, GpuUnitSheetTabs.Inputs inputs,
          Object selection) { }

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final GpuUnitSheetTabs tabs;
    private final Table root;
    private final Drawable friendPanel;
    private final Drawable foePanel;
    private final TabRow tabRow = new TabRow();
    private final Table alert = new Table();
    private final Cell<Table> alertCell;
    private final Table body = new Table();
    private final ScrollPane scroll;
    /** The window-sized layer of the slot popover, which GpuHud places on its popover layer. */
    private final Group overlay = new Group();
    private final UiPopover popover;
    private Shown shown;
    private Object tabRowShown;
    private boolean sentOpen;
    private int shownUnit = Entity.NONE;
    private GpuUnitRecord.Snapshot previousRecord = GpuUnitRecord.Snapshot.EMPTY;
    private GpuUnitCard.Deltas deltas = GpuUnitCard.Deltas.NONE;

    GpuRecordSheet(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu,
          GpuPaperdolls paperdolls) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        popover = new UiPopover(ui);
        popover.setName("record-sheet-popover");
        overlay.setTouchable(Touchable.childrenOnly);
        overlay.addActor(popover);
        tabs = new GpuUnitSheetTabs(kit, source, state, menu, paperdolls, this);
        root = ui.panel();
        root.setName("record-sheet");
        friendPanel = ui.skin.getDrawable("panel");
        foePanel = ui.skin.getDrawable("panel-foe");
        root.add(tabRow).growX().row();
        root.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        alert.setName("record-sheet-alerts");
        alert.left().pad(0, 12, 0, 12);
        alertCell = root.add(alert).growX();
        root.row();
        body.top().left();
        body.pad(14, 12, 18, 12);
        body.setName("record-sheet-body");
        body.setTouchable(Touchable.enabled);
        body.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return tabs.key(keycode);
            }
        });
        scroll = ui.scrollList(body);
        scroll.setName("record-sheet-list");
        root.add(scroll).grow().minHeight(0);
    }

    /**
     * Whether the sheet shows: it is open and the card shows a unit with a record, not a sensor contact. The card, the
     * forces strip and the HUD layout follow this one rule (the card is the mini card exactly while it holds).
     */
    static boolean open(GpuHudState state) {
        GpuBattleStatus.UnitStatus unit = state.presented(state.cardUnit());
        return state.recordOpen && unit != null && !unit.sensorContact();
    }

    /** Opens the sheet on a tab, as F1 to F6 and the card's elements do. */
    static void show(GpuHudState state, SheetTab tab) {
        state.overview = false;
        state.recordOpen = true;
        state.sheetTab = tab;
    }

    /**
     * Opens the sheet on a unit's record, on the tab it showed last ("Unit record" in the unit menus and the dock's
     * More, design 2.2): the card shows the acting unit {@code acting} uninspected, any other unit inspected.
     */
    static void showUnit(GpuHudState state, int unitId, int acting) {
        state.inspected = unitId == acting ? Entity.NONE : unitId;
        show(state, state.sheetTab);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** The popover layer's part of the sheet: a window-sized group holding its slot popover. */
    Actor overlay() {
        return overlay;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        boolean open = open(state);
        if (open != sentOpen) {
            // The record's sheet-only details are captured while the sheet is open (U1).
            sentOpen = open;
            source.record().setSheetOpen(open);
        }
        root.setVisible(open);
        if (!open) {
            popover.cancel();
            return;
        }
        GpuBattleStatus.UnitStatus unit = state.presented(state.cardUnit());
        GpuUnitRecord.Snapshot record = state.presentedRecord();
        boolean ready = record.unitId() == unit.id();
        if (unit.id() != shownUnit) {
            shownUnit = unit.id();
            popover.cancel();
            tabs.unitChanged();
            deltas = GpuUnitCard.Deltas.NONE;
        }
        if (ready) {
            forget(record);
            deltas = deltas.next(previousRecord, record, TimeUtils.millis());
            previousRecord = record;
        }
        float width = inputs.metrics().grid() - SIDES;
        Density density = Density.of(width);
        root.setBackground(unit.side() == GpuBattleStatus.Side.ENEMY ? foePanel : friendPanel);
        GpuUnitSheetTabs.Inputs tabInputs = tabs.inputs(inputs, unit, deltas.shown(TimeUtils.millis()));
        showTabRow(unit, ready ? record : GpuUnitRecord.Snapshot.EMPTY, density, width, inputs);
        showAlerts(unit, ready ? record : GpuUnitRecord.Snapshot.EMPTY, inputs);
        Shown next = new Shown(unit, ready ? record : null, state.sheetTab, width, state.selectedLocation,
              state.expandedWeapon, state.expandedOrdinal, Set.copyOf(state.collapsedSections), tabInputs,
              tabs.selection());
        // A weapon row that is dragged or still moves keeps the body: the new record shows once the rows rest.
        if (!next.equals(shown) && !tabs.busy()) {
            // The body keeps its scroll position while the same unit's tab changes (a new value, a selection).
            boolean sameTab = shown != null && shown.tab() == next.tab() && shown.unit().id() == next.unit().id();
            shown = next;
            float scrollY = scroll.getScrollY();
            body.clearChildren();
            if (ready) {
                tabs.build(body, state.sheetTab, unit, record, density, width, tabInputs);
            }
            scroll.layout();
            if (sameTab) {
                scroll.setScrollY(scrollY);
                scroll.updateVisualScroll();
            }
        }
        tabs.refresh();
    }

    /**
     * Blink memory (2.3): another unit keeps the inspected location and the expanded weapon only where it has them;
     * anything without a match is cleared silently.
     */
    private void forget(GpuUnitRecord.Snapshot record) {
        if (!state.selectedLocation.isEmpty() && !hasLocation(record, state.selectedLocation)) {
            state.selectedLocation = "";
        }
        if (!state.expandedWeapon.isEmpty() && record.weapons().stream()
              .filter(weapon -> weapon.name().equals(state.expandedWeapon)).count() <= state.expandedOrdinal) {
            state.expandedWeapon = "";
            state.expandedOrdinal = 0;
        }
    }

    /** Whether the record has a location, or for "DC" + arm a shield on that arm. */
    static boolean hasLocation(GpuUnitRecord.Snapshot record, String code) {
        if (code.startsWith("DC")) {
            return record.shields().stream().anyMatch(shield -> shield.location().equals(code.substring(2)));
        }
        return record.locations().stream().anyMatch(location -> location.abbr().equals(code));
    }

    /**
     * The tab row (5.1): text tabs, compacted at medium density, or icon tabs with the active tab's label where the
     * labels do not fit; attention dots on inactive tabs; a tab the unit does not use dimmed with its reason; the
     * sheet's ✕ at the right end.
     */
    private void showTabRow(GpuBattleStatus.UnitStatus unit, GpuUnitRecord.Snapshot record, Density density,
          float width, GpuHud.Inputs inputs) {
        boolean mek = !record.hitTracks().isEmpty();
        Map<SheetTab, Color> dots = record.unitId() == Entity.NONE ? Map.of() : dots(record);
        Map<SheetTab, String> unused = record.unitId() == Entity.NONE ? Map.of() : GpuUnitSheetTabs.unused(record);
        Object key = List.of(state.sheetTab, mek, dots, unused, density, width, inputs.preferences().binds(),
              weaponsTip(record));
        if (key.equals(tabRowShown)) {
            return;
        }
        tabRowShown = key;
        tabRow.clearChildren();
        tabRow.dots.clear();
        tabRow.left().bottom().pad(0, 12, 0, 12);
        List<String> labels = new ArrayList<>();
        for (SheetTab tab : SheetTab.values()) {
            labels.add(tabLabel(tab, mek));
        }
        // Text tabs where they fit beside the ✕, else icons with the open tab's label.
        float room = width + 2 * 14 - CLOSE - CLOSE_GAP;
        float gap = density == Density.WIDE ? TAB_GAP : COMPACT_TAB_GAP;
        float textWidth = -gap;
        for (String label : labels) {
            textWidth += tabButton(label, null, false).getPrefWidth() + gap;
        }
        boolean icons = density == Density.NARROW || textWidth > room;
        tabRow.icons = icons;
        for (SheetTab tab : SheetTab.values()) {
            boolean active = tab == state.sheetTab;
            String label = labels.get(tab.ordinal());
            UiButton button = tabButton(icons && !active ? null : label, icons && !active
                  ? TAB_ICONS.get(tab.ordinal()) : null, icons);
            button.setName("record-tab-" + tab.name().toLowerCase(java.util.Locale.ROOT));
            button.pressed(active);
            // A tooltip where it tells more than the tab shows: why the unit does not use it, the name and key of an
            // icon tab, and the weapons' usable count.
            String reason = unused.get(tab);
            boolean usable = tab == SheetTab.WEAPONS && !weaponsTip(record).isEmpty();
            if (reason != null) {
                ui.tip(button).getActor().setText(reason);
                button.getColor().a = .45f;
            } else if (icons && !active || usable) {
                String tip = text("GpuBoard.hud.unit.tabTip", label, GpuHintLine.key(inputs.preferences(), tab.key));
                ui.tip(button).getActor().setText(usable ? text("GpuBoard.hud.unit.tabTipDetail", tip,
                      weaponsTip(record)) : tip);
            }
            onChange(button, () -> state.sheetTab = tab);
            tabRow.add(button).minWidth(0).padLeft(tabRow.hasChildren() ? (icons ? ICON_TAB_GAP : gap) : 0)
                  .bottom();
            if (!active && dots.containsKey(tab)) {
                tabRow.dots.put(button, dots.get(tab));
            }
        }
        tabRow.add().expandX();
        UiButton close = ui.closeButton(this::close);
        close.setName("record-sheet-close");
        float rowHeight = icons ? ICON_TAB_ROW : TAB_ROW;
        tabRow.add(close).size(CLOSE).padLeft(CLOSE_GAP).padBottom((rowHeight - CLOSE) / 2).bottom();
        tabRow.setHeight(rowHeight);
        tabRow.invalidateHierarchy();
    }

    /** A tab's label: F5 is the critical table on a Mek and the systems on any other unit. */
    private static String tabLabel(SheetTab tab, boolean mek) {
        return switch (tab) {
            case STATUS -> text("GpuBoard.hud.unit.tab.status");
            case CREW -> text("GpuBoard.hud.record.crew");
            case ARMOR -> text("GpuBoard.hud.common.armor");
            case WEAPONS -> text("GpuBoard.hud.common.weapons");
            case SYSTEMS -> text(mek ? "GpuBoard.hud.unit.tab.critical" : "GpuBoard.hud.record.systems");
            case EXTRAS -> text("GpuBoard.hud.unit.tab.extras");
        };
    }

    /** The weapons tab's tooltip detail: "n of m usable" ("" without weapons). */
    private static String weaponsTip(GpuUnitRecord.Snapshot record) {
        int usable = GpuUnitCard.usableWeapons(record);
        return record.weapons().isEmpty() ? "" : text("GpuBoard.hud.unit.usable", usable, record.weapons().size());
    }

    /**
     * A tab (.tab): an underlined upper-case label, or an icon; among icon tabs the open tab's label is smaller
     * (.tabrow.icons .tab: 11 units). A label too long for the row (a long translation of the open tab's name) ends in
     * an ellipsis instead of widening the sheet.
     */
    private UiButton tabButton(String label, String icon, boolean small) {
        UiButton button = ui.button("hud-tab-caps", icon, label, null);
        if (icon != null) {
            button.getCells().first().size(TAB_ICON);
        } else {
            if (small) {
                UiKit.size(button.getLabel(), "hud-button", 11);
            }
            button.getLabel().setEllipsis(true);
            button.getLabelCell().minWidth(0);
        }
        return button;
    }

    /**
     * The attention dots (5.1) of the record: STATUS mint while a change is pending, ARMOR coral when a location is
     * exposed or breached, WEAPONS amber when a weapon jams, SYSTEMS amber for an empty or dumping bin, CREW coral when
     * a crew member is out and amber at three or more hits.
     */
    private static Map<SheetTab, Color> dots(GpuUnitRecord.Snapshot record) {
        Map<SheetTab, Color> dots = new EnumMap<>(SheetTab.class);
        if (!GpuUnitSheetTabs.pending(record).isEmpty()) {
            dots.put(SheetTab.STATUS, UiTheme.MINT);
        }
        if (record.locations().stream().anyMatch(location -> location.breached() || GpuUnitCard.exposed(location))) {
            dots.put(SheetTab.ARMOR, UiTheme.CORAL);
        }
        if (record.weapons().stream().anyMatch(GpuUnitRecord.RecordWeapon::jammed)) {
            dots.put(SheetTab.WEAPONS, UiTheme.AMBER);
        }
        boolean bins = record.locations().stream().flatMap(location -> location.slots().stream())
              .anyMatch(slot -> slot.shots() == 0 && slot.fullShots() > 0 || slot.dumping());
        if (bins) {
            dots.put(SheetTab.SYSTEMS, UiTheme.AMBER);
        }
        if (record.crew().stream().anyMatch(seat -> !seat.missing() && !seat.active())) {
            dots.put(SheetTab.CREW, UiTheme.CORAL);
        } else if (record.crew().stream().anyMatch(seat -> seat.hits() >= 3)) {
            dots.put(SheetTab.CREW, UiTheme.AMBER);
        }
        return dots;
    }

    /** The alert strip (3.3, R3-2): the card's chips minus those whose home is the open tab; empty, it collapses. */
    private void showAlerts(GpuBattleStatus.UnitStatus unit, GpuUnitRecord.Snapshot record, GpuHud.Inputs inputs) {
        List<GpuUnitCard.Chip> chips = GpuUnitCard.chips(unit, record, inputs.frame().status().phase()).stream()
              .filter(chip -> chip.home() != state.sheetTab).toList();
        alert.clearChildren();
        float room = inputs.metrics().grid() - 28;
        GpuUnitCard.fillChips(ui, alert, chips, room, chip -> show(state, chip.home()));
        alertCell.height(chips.isEmpty() ? 0 : ALERT);
        alert.setVisible(!chips.isEmpty());
    }

    /** Gives the sheet's lists the keyboard focus (the list focus of 12), as a click on a row or slot does. */
    void focusLists() {
        if (body.getStage() != null) {
            body.getStage().setKeyboardFocus(body);
        }
    }

    /** Whether the sheet's lists hold the keyboard focus; the focused list draws its ring. */
    boolean listsFocused() {
        return body.getStage() != null && body.getStage().getKeyboardFocus() == body;
    }

    /**
     * Opens the popover with a title, subtitle and content next to {@code anchor} (a clicked slot or row), on the
     * popover layer; one at a time.
     */
    void popover(String title, String subtitle, Actor content, Actor anchor) {
        popover.header(title, subtitle).content(content);
        Vector2 corner = anchor.localToStageCoordinates(new Vector2(anchor.getWidth(), anchor.getHeight()));
        popover.showAt(corner.x + 6, corner.y);
    }

    /** Closes the popover once one of its controls acted: its content shows the record of before. */
    void closePopover() {
        popover.cancel();
    }

    /** The Esc chain's first step: a weapon row dragged on the sheet goes home. True when one was. */
    boolean cancelDrag() {
        return open(state) && tabs.cancelDrag();
    }

    /**
     * The sheet's step of the Esc chain (12): closes the popover, else collapses the expanded weapon row, else closes
     * the sheet. True when it did one of them.
     */
    boolean cancel() {
        if (popover.cancel()) {
            return true;
        } else if (open(state) && !state.expandedWeapon.isEmpty()) {
            state.expandedWeapon = "";
            state.expandedOrdinal = 0;
            return true;
        } else if (state.recordOpen) {
            close();
            return true;
        }
        return false;
    }

    /** The sheet's ✕ and the last step of its Esc chain: closes the sheet with its popover. */
    private void close() {
        popover.cancel();
        state.recordOpen = false;
    }

    @Override
    public void dispose() {
        tabs.dispose();
    }

    /** The tab row with the attention dots of its inactive tabs at their labels' top right (.tab .dot). */
    private final class TabRow extends Table {
        private final Map<UiButton, Color> dots = new java.util.HashMap<>();
        private boolean icons;

        @Override
        public float getPrefHeight() {
            return icons ? ICON_TAB_ROW : TAB_ROW;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            super.draw(batch, parentAlpha);
            float previous = batch.getPackedColor();
            for (Map.Entry<UiButton, Color> dot : dots.entrySet()) {
                UiButton tab = dot.getKey();
                Color color = dot.getValue();
                Drawable disc = ui.skin.getDrawable("unit-disc-6");
                batch.setColor(color.r, color.g, color.b, getColor().a * parentAlpha);
                disc.draw(batch, getX() + tab.getX() + tab.getWidth() + 2, getY() + tab.getY() + tab.getHeight() - 4,
                      6, 6);
            }
            batch.setPackedColor(previous);
        }
    }

}
