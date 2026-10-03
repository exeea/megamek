/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;
import static megamek.client.ui.gdx.UiTheme.alpha;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuCriticalTable.Strike;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.SheetTab;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.Cell;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.View;
import megamek.client.ui.clientGUI.boardview.gpu.GpuRecordSheet.Density;
import megamek.client.ui.dialogs.unitDisplay.WeaponListModel;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiList;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.ResolvedAttack;

/**
 * The unit sheet's tab bodies (unit panel design 5.2 to 5.8), built for the sheet's density from the record and the
 * frame: STATUS (this round, pending changes, heat, movement, conditions), CREW, ARMOR (front armor beside rear armor
 * over structure, or one drawing), WEAPONS, the critical table of a Mek or the SYSTEMS of any other unit, and EXTRAS.
 * One fact is shown once; a struck row prints its name and location only. Controls post the record service's commands
 * for the unit of the record shown.
 */
final class GpuUnitSheetTabs implements Disposable {
    private static final float SECTION_GAP = 18;
    private static final float COLUMN_GAP = 26;
    private static final float KV_HEIGHT = 26;
    private static final String SEPARATOR = " · ";
    /** The weapon table's grip column. */
    private static final float GRIP = 12;
    /** A doll click's flash over the location's block: its mint at the start, and its fade. */
    private static final float FLASH_ALPHA = .3f;
    private static final float FLASH_SECONDS = .9f;

    /**
     * What a tab shows besides its record, from the frame: this round's events of the unit (absent during playback's
     * older events, C.6), the heat forecast of its firing turn, its declared targets' letters by weapon, the key binds
     * for the tooltips, the playback deltas of its dolls, whether the View menu's sensor ranges and field of fire are
     * shown (null: the menu has no such item), and whether the unit is the local firing actor, whose weapons the fire
     * orders select and load (design 9 #11, #12), with the weapon they select (-1: none).
     */
    record Inputs(List<Round> thisRound, GpuFireOrders.Heat forecast, Map<Integer, Character> declared,
          List<GpuBoardSource.Bind> binds, GpuUnitCard.Deltas deltas, Boolean sensorRanges, Boolean fieldOfFire,
          boolean firingActor, int firingWeapon) { }

    /** One line of "This round": a label ("Took 21") and its details. */
    record Round(String label, String text) { }

    /** A row or table block of a location, with the background it has when the location is not linked (graft 6). */
    private record Link(String location, Table actor, Drawable background) { }

    /**
     * A change scheduled for the end of the turn or the next round (5.2 Pending changes) and, for an own unit, its
     * Cancel: the same command with the value in effect now ({@code cancel}: null for another player's unit), allowed
     * now or blocked with the reason's message key ("" without one).
     */
    record Pending(String what, String when, Consumer<GpuUnitRecord> cancel, boolean enabled, String blocker) { }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final GpuPaperdolls paperdolls;
    private final GpuRecordSheet sheet;
    private final GpuCriticalTable crit;
    private final Texture white;
    private final Drawable selectedRow;
    private final Drawable detailBox;
    private final Drawable linkedTint;
    private final GpuTextures<BoardScene.Pixels> portraits = new GpuTextures<>(true);
    /**
     * The keyboard's selection in the focused list: a critical slot, or a weapon by its equipment number (-1: none),
     * which a reordered weapon keeps.
     */
    private GpuCriticalTable.SlotKey selectedSlot;
    private int selectedWeapon = -1;
    private boolean readoutOpen;
    private List<GpuCriticalTable.SlotKey> slotOrder = List.of();
    private List<GpuUnitRecord.RecordWeapon> weaponOrder = List.of();
    /** The WEAPONS tab's rows, reorderable for an own unit; null on another tab. */
    private UiList weaponList;
    /** The ARMOR tab's locations in MegaMek's order, each shield after its arm, for the doll's arrow keys. */
    private List<String> dollOrder = List.of();
    /**
     * The shown tab's dolls and location rows and blocks, which follow {@link GpuHudState#linkedLocation} each frame
     * without a rebuild; the doll under the pointer; and the ARMOR inspector with the code it shows.
     */
    private final List<GpuPaperdoll> dolls = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private GpuPaperdoll hoveredDoll;
    private String appliedLink;
    private GpuPaperdoll appliedDoll;
    private Table inspector;
    private String inspected;
    /** The location whose block flashes once the SYSTEMS tab a doll click opened is built; "" for none. */
    private String flashLocation = "";
    /** The View menu's sensor range switch of the latest frame, run by EXTRAS' button; null without one. */
    private Runnable sensorRanges;
    /** The View menu's field of fire switch of the latest frame, run by a weapon's button; null without one. */
    private Runnable fieldOfFire;
    private GpuUnitRecord.Snapshot record = GpuUnitRecord.Snapshot.EMPTY;
    private SheetTab tab = SheetTab.STATUS;
    private Table body;

    GpuUnitSheetTabs(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu,
          GpuPaperdolls paperdolls, GpuRecordSheet sheet) {
        this.kit = kit;
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.menu = menu;
        this.paperdolls = paperdolls;
        this.sheet = sheet;
        crit = new GpuCriticalTable(ui);
        white = ui.skin.get("white", Texture.class);
        // .tbl .tr.on: a faint mint fill with a 3-unit mint bar at the left; .wdet: mint edges open at the top.
        selectedRow = new UiTheme.EdgeBox(white, alpha(UiTheme.MINT, .07f), UiTheme.MINT, 0, 0, 0, 3);
        detailBox = new UiTheme.EdgeBox(white, alpha(UiTheme.MINT, .03f), alpha(UiTheme.MINT, .4f), 0, 1, 1, 1);
        // Linked hover (5.1): the rows and table blocks of the hovered location at 4 % mint.
        linkedTint = new UiTheme.EdgeBox(white, alpha(UiTheme.MINT, .04f), UiTheme.MINT, 0, 0, 0, 0);
    }

    /** The frame's inputs of the card unit's tabs. */
    Inputs inputs(GpuHud.Inputs inputs, GpuBattleStatus.UnitStatus unit, GpuUnitCard.Deltas deltas) {
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        boolean firing = fire.active() && fire.actorId() == unit.id();
        Map<Integer, Character> declared = new HashMap<>();
        if (firing) {
            for (GpuFireOrders.Attack attack : fire.attacks()) {
                fire.targets().stream().filter(target -> target.key().equals(attack.target())).findFirst()
                      .ifPresent(target -> declared.put(attack.eqNum(), target.letter()));
            }
        }
        // C.6: the round's events show once the playback has shown them.
        List<Round> round = inputs.view().playbackBusy() ? List.of()
              : thisRound(inputs.frame().reports(), unit.id(), state.presentedUnits());
        BoardScene.Command ranges = GpuBoardActions.menuItem(inputs.frame().globalCommands(),
              ClientGUI.VIEW_TOGGLE_SENSOR_RANGE);
        sensorRanges = ranges == null || !ranges.enabled() ? null : ranges.action();
        BoardScene.Command field = GpuBoardActions.menuItem(inputs.frame().globalCommands(),
              ClientGUI.VIEW_TOGGLE_FIELD_OF_FIRE);
        fieldOfFire = field == null || !field.enabled() ? null : field.action();
        // The orders of the local declaration select and load the actor's weapons (G10, H30)
        boolean actor = firing && fire.editable();
        return new Inputs(round, firing ? fire.heat() : null, Map.copyOf(declared), inputs.preferences().binds(),
              deltas, ranges == null ? null : Boolean.TRUE.equals(ranges.selected()),
              field == null ? null : Boolean.TRUE.equals(field.selected()), actor, actor ? fire.selectedWeapon() : -1);
    }

    /** The keyboard selection, part of what the sheet compares before it rebuilds. */
    Object selection() {
        return List.of(selectedSlot == null ? "" : selectedSlot, selectedWeapon, readoutOpen, sheet.listsFocused());
    }

    /** Whether a weapon row is dragged or still moves: the sheet keeps its body until the rows rest. */
    boolean busy() {
        return weaponList != null && weaponList.busy();
    }

    /** The Esc chain's first step: a dragged weapon row goes home. True when one was. */
    boolean cancelDrag() {
        return weaponList != null && weaponList.cancel();
    }

    /** A new card unit: the list selection starts over (the tab, location and expanded row follow 2.3). */
    void unitChanged() {
        selectedSlot = null;
        selectedWeapon = -1;
        readoutOpen = false;
    }

    /**
     * Each frame: the linked location (graft 6) outlines in mint on the dolls it does not come from, tints its rows and
     * table blocks, and a doll's hovered location fills the ARMOR inspector (6.7); the selected location fills it
     * otherwise.
     */
    void refresh() {
        String linked = state.linkedLocation;
        if (linked.equals(appliedLink) && hoveredDoll == appliedDoll) {
            return;
        }
        appliedLink = linked;
        appliedDoll = hoveredDoll;
        for (GpuPaperdoll doll : dolls) {
            boolean here = doll == hoveredDoll;
            doll.marks(here ? linked : "", state.selectedLocation, here ? "" : linked);
        }
        String location = GpuCriticalTable.blockLocation(linked);
        for (Link link : links) {
            link.actor().setBackground(!location.isEmpty() && location.equals(link.location()) ? linkedTint
                  : link.background());
        }
        if (inspector != null) {
            inspect(hoveredDoll != null && !linked.isEmpty() ? linked : state.selectedLocation);
        }
    }

    /**
     * Links {@code location} while the pointer is over {@code actor} (graft 6) and registers the actor for the tint;
     * {@code hover} also hears the pointer's coming and going.
     */
    private void hoverLink(Table actor, String location, Consumer<Boolean> hover) {
        links.add(new Link(location, actor, actor.getBackground()));
        actor.setTouchable(Touchable.enabled);
        actor.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (pointer == -1 && (fromActor == null || !fromActor.isDescendantOf(actor))) {
                    state.linkedLocation = location;
                    hover.accept(true);
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(actor))) {
                    if (state.linkedLocation.equals(location)) {
                        state.linkedLocation = "";
                    }
                    hover.accept(false);
                }
            }
        });
    }

    /** A weapon's location as a doll and the critical table name it: "RT (R)" and "LT/LA" are RT and LT. */
    private static String locationOf(GpuUnitRecord.RecordWeapon weapon) {
        return weapon.location().split("[ /]", 2)[0];
    }

    /** Builds the open tab's body into {@code body} for the content {@code width}. */
    void build(Table into, SheetTab openTab, GpuBattleStatus.UnitStatus unit, GpuUnitRecord.Snapshot shown,
          Density density, float width, Inputs inputs) {
        record = shown;
        tab = openTab;
        body = into;
        slotOrder = List.of();
        weaponOrder = List.of();
        weaponList = null;
        dollOrder = List.of();
        dolls.clear();
        links.clear();
        hoveredDoll = null;
        appliedLink = null;
        inspector = null;
        body.defaults().left().growX();
        switch (openTab) {
            case STATUS -> status(body, unit, density, width, inputs);
            case CREW -> crew(body, density, width);
            case ARMOR -> armor(body, density, width, inputs);
            case WEAPONS -> weapons(body, density, width, inputs);
            case SYSTEMS -> systems(body, density, width);
            case EXTRAS -> extras(body, unit, density, width, inputs);
        }
        // A doll click's flash, on the location blocks the SYSTEMS tab registered for the linked hover.
        if (openTab == SheetTab.SYSTEMS) {
            links.stream().filter(link -> link.location().equals(flashLocation)).findFirst()
                  .ifPresent(link -> flash(link.actor()));
        }
        flashLocation = "";
    }

    // ------------------------------------------------------------------------------------------------ shared rows

    /** Two columns at wide density (.cols), else one; returns the columns to fill, in order. */
    private Table[] columns(Table body, Density density, float width) {
        if (density != Density.WIDE) {
            Table one = column();
            body.add(one).width(width).row();
            return new Table[] { one, one };
        }
        float half = (width - COLUMN_GAP) / 2;
        Table left = column();
        Table right = column();
        Table both = new Table();
        both.top().left();
        both.add(left).width(half).top().padRight(COLUMN_GAP);
        both.add(right).width(half).top();
        body.add(both).width(width).row();
        return new Table[] { left, right };
    }

    private static Table column() {
        Table column = new Table();
        column.top().left();
        column.defaults().left().growX();
        return column;
    }

    /**
     * A section (.sec, .cap2): its caption over a hairline, 18 units below the section before it, and the table its
     * rows go in; a click on the caption collapses it. Null while collapsed, when the caption shows its {@code count}
     * and a chevron instead.
     */
    private Table section(Table column, String key, String title, int count, Actor right) {
        boolean collapsed = state.collapsedSections.contains(key);
        Table caption = new Table();
        caption.setName("record-section-" + key);
        caption.add(ui.caption(collapsed && count > 0 ? title + " " + count : title)).left();
        caption.add().expandX();
        if (right != null) {
            caption.add(right).right();
        }
        if (collapsed) {
            caption.add(ui.icon("chevron-right", 12, UiTheme.MUTED)).padLeft(6);
        }
        caption.setTouchable(Touchable.enabled);
        caption.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (!state.collapsedSections.remove(key)) {
                    state.collapsedSections.add(key);
                }
            }
        });
        column.add(caption).height(23).padTop(column.hasChildren() ? SECTION_GAP : 0).row();
        column.add(new Image(ui.skin.getDrawable("rule"))).height(1).padBottom(6).row();
        if (collapsed) {
            return null;
        }
        Table rows = column();
        column.add(rows).row();
        return rows;
    }

    /** A key / value row (.kv): the muted key at the left and the value in ink at the right; a tooltip on the row. */
    private Table kv(Table rows, String key, String value, String tip) {
        Table row = new Table();
        Label keyLabel = ui.label(key, "hud-body", 12.5f, UiTheme.MUTED);
        Label valueLabel = ui.label(value, "hud-medium", 12.5f, UiTheme.TEXT);
        valueLabel.setAlignment(Align.right);
        valueLabel.setWrap(true);
        row.add(keyLabel).left().top().padRight(12).padTop(5);
        row.add(valueLabel).growX().right().minWidth(0).padTop(5).padBottom(4);
        if (tip != null && !tip.isEmpty()) {
            ui.tip(row).getActor().setText(tip);
        }
        rows.add(row).minHeight(KV_HEIGHT).row();
        return row;
    }

    /** A plain text row (.drow), wrapped, with an optional tooltip. */
    private Label line(Table rows, String text, Color color, String tip) {
        Label label = ui.label(text, "hud-body", 12.5f, color);
        label.setWrap(true);
        if (tip != null && !tip.isEmpty()) {
            ui.tip(label).getActor().setText(tip);
        }
        rows.add(label).minHeight(24).padTop(2).padBottom(2).row();
        return label;
    }

    /** A link (.lnk): mint text that runs {@code action} on a click. */
    private Label link(String text, Runnable action) {
        Label link = ui.label(text, "hud-body", 12, UiTheme.MINT);
        link.setTouchable(Touchable.enabled);
        link.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                action.run();
            }
        });
        return link;
    }

    /** The (i) of a value whose explanation is its tooltip. */
    private UiKit.Icon info(String tip) {
        UiKit.Icon info = ui.icon("info", 13, UiTheme.MUTED);
        ui.tip(info).getActor().setText(tip);
        return info;
    }

    // ------------------------------------------------------------------------------------------------ STATUS (5.2)

    private void status(Table body, GpuBattleStatus.UnitStatus unit, Density density, float width, Inputs inputs) {
        Table[] columns = columns(body, density, width);
        boolean wide = density == Density.WIDE;
        if (!wide) {
            thisRound(columns[0], inputs);
            pendingSection(columns[0]);
        }
        heat(columns[0], unit, inputs);
        movement(columns[0], unit);
        if (wide) {
            thisRound(columns[1], inputs);
            pendingSection(columns[1]);
        }
        conditions(columns[1]);
    }

    /** "This round" (R2-8, R3-5): damage taken and dealt and PSR results; absent when there are none. */
    private void thisRound(Table column, Inputs inputs) {
        if (inputs.thisRound().isEmpty()) {
            return;
        }
        Table rows = section(column, "thisRound", text("GpuBoard.hud.unit.section.thisRound"),
              inputs.thisRound().size(), null);
        if (rows == null) {
            return;
        }
        for (Round round : inputs.thisRound()) {
            Table row = kv(rows, round.label(), round.text(), text("GpuBoard.hud.unit.thisRoundTip"));
            row.setTouchable(Touchable.enabled);
            row.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (!state.logOpen()) {
                        state.toggleLog();
                    }
                }
            });
        }
    }

    /**
     * This round's events of a unit from the presented log (C.6): the damage it took by location and attacker, its
     * critical hits, its PSR results with their reasons, and the damage it dealt by target and location.
     */
    static List<Round> thisRound(GpuReportLog.Snapshot reports, int unitId, List<GpuBattleStatus.UnitStatus> units) {
        List<Round> rounds = new ArrayList<>();
        List<GpuReportLog.CombatEvent> taken = reports.combat().stream()
              .filter(event -> event.targetId() == unitId && event.hit() && event.damage() > 0).toList();
        if (!taken.isEmpty()) {
            List<String> parts = new ArrayList<>(impacts(taken));
            taken.stream().map(event -> name(units, event.attackerId())).distinct().forEach(parts::add);
            rounds.add(new Round(text("GpuBoard.hud.unit.took", taken.stream()
                  .mapToInt(GpuReportLog.CombatEvent::damage).sum()), String.join(SEPARATOR, parts)));
        }
        for (GpuReportLog.CritItem crit : reports.crits()) {
            if (crit.entityId() == unitId) {
                rounds.add(new Round(text("GpuBoard.hud.unit.crit"), crit.location().isEmpty() ? crit.text()
                      : crit.location() + SEPARATOR + crit.text()));
            }
        }
        for (GpuReportLog.PsrItem psr : reports.psr()) {
            if (psr.entityId() == unitId) {
                String result = text(psr.passed() ? "GpuBoard.hud.unit.psrPassed" : "GpuBoard.hud.unit.psrFailed",
                      psr.roll());
                rounds.add(new Round(text("GpuBoard.hud.unit.psr", psr.targetNumber()),
                      psr.reasons().isBlank() ? result : result + SEPARATOR + psr.reasons().strip()));
            }
        }
        List<GpuReportLog.CombatEvent> dealt = reports.combat().stream()
              .filter(event -> event.attackerId() == unitId && event.hit() && event.damage() > 0).toList();
        if (!dealt.isEmpty()) {
            List<String> parts = new ArrayList<>();
            Map<Integer, List<GpuReportLog.CombatEvent>> byTarget = dealt.stream()
                  .collect(Collectors.groupingBy(GpuReportLog.CombatEvent::targetId, LinkedHashMap::new,
                        Collectors.toList()));
            byTarget.forEach((target, events) -> {
                parts.add(name(units, target));
                parts.addAll(impacts(events));
            });
            rounds.add(new Round(text("GpuBoard.hud.unit.dealt", dealt.stream()
                  .mapToInt(GpuReportLog.CombatEvent::damage).sum()), String.join(SEPARATOR, parts)));
        }
        return rounds;
    }

    /** The damage of events by hit location, largest first: "RT 12", "CT 9". */
    private static List<String> impacts(List<GpuReportLog.CombatEvent> events) {
        Map<String, Integer> byLocation = new LinkedHashMap<>();
        for (GpuReportLog.CombatEvent event : events) {
            for (ResolvedAttack.Impact impact : event.impacts()) {
                byLocation.merge(impact.location(), impact.weight(), Integer::sum);
            }
        }
        return byLocation.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
              .map(entry -> entry.getKey() + " " + entry.getValue()).toList();
    }

    private static String name(List<GpuBattleStatus.UnitStatus> units, int id) {
        return units.stream().filter(unit -> unit.id() == id).findFirst()
              .map(unit -> unit.sensorContact() ? text("GpuBoard.hud.common.sensorContact")
                    : GpuUnitCard.unitName(unit))
              .orElse(text("GpuBoard.hud.common.unidentified"));
    }

    private void pendingSection(Table column) {
        List<Pending> pending = pending(record);
        if (pending.isEmpty()) {
            return;
        }
        Table rows = section(column, "pending", text("GpuBoard.hud.unit.section.pending"), pending.size(), null);
        if (rows == null) {
            return;
        }
        for (int index = 0; index < pending.size(); index++) {
            Pending change = pending.get(index);
            // .r2: the change over when it takes effect, and an own unit's Cancel at the right.
            Table row = new Table();
            Table words = new Table();
            words.left();
            words.add(ui.label(change.what(), "hud-medium", 12.5f, UiTheme.TEXT)).left().row();
            words.add(ui.label(change.when(), "hud-small", 11, UiTheme.MUTED)).left();
            row.add(words).growX().minWidth(0).left();
            if (change.cancel() != null) {
                UiButton cancel = ui.button("hud-mini", null, text("Cancel"), null);
                cancel.setName("record-pending-cancel-" + index);
                cancel.setDisabled(!change.enabled());
                if (!change.blocker().isEmpty()) {
                    ui.tip(cancel).getActor().setText(text(change.blocker()));
                }
                onChange(cancel, () -> {
                    if (!cancel.isDisabled()) {
                        change.cancel().accept(source.record());
                    }
                });
                row.add(cancel).right().padLeft(8);
            }
            rows.add(row).minHeight(40).row();
        }
    }

    /**
     * The changes scheduled for the end of the turn or the next round (graft 3): queued equipment modes, a sensor,
     * heat sink, hidden activation or console swap chosen but not yet in effect, and bins being dumped. For an own unit
     * each is cancelled by the command that made it, with the value in effect now (R2-11): the mode in effect, which
     * ends a queued switch as the Systems tab's mode list does; the choice in effect; and the dump cancelled, which the
     * owner confirms (a dump under way can no longer be cancelled, and its reason says so).
     */
    static List<Pending> pending(GpuUnitRecord.Snapshot record) {
        boolean own = record.own() && !record.removed();
        int unit = record.unitId();
        List<Pending> pending = new ArrayList<>();
        for (GpuUnitRecord.Equipment equipment : record.equipment()) {
            if (equipment.pendingMode() >= 0 && equipment.selected() >= 0
                  && equipment.selected() < equipment.modes().size()) {
                // The list offers the modes by their index, and the mode in effect is never one it leaves out
                int current = equipment.mode();
                pending.add(new Pending(text("GpuBoard.hud.unit.pendingMode", equipment.name(),
                      modeName(equipment.modes().get(equipment.selected()))), text("GpuBoard.hud.unit.endOfTurn"),
                      own ? records -> records.setMode(unit, equipment.eqNum(), current) : null,
                      equipment.changeable() && current >= 0 && current < equipment.modes().size(), ""));
            }
        }
        for (GpuUnitRecord.SystemControl control : record.systems()) {
            // A choice is pending while it differs from the one in effect; none is known before the first round.
            if (control.current() >= 0 && control.selected() != control.current() && control.selected() >= 0
                  && control.selected() < control.choices().size()
                  && !control.id().equals(GpuUnitRecord.WEAPON_ORDER)) {
                boolean nextRound = control.id().equals(GpuUnitRecord.SENSORS)
                      || control.id().equals(GpuUnitRecord.HEAT_SINKS);
                pending.add(new Pending(text("GpuBoard.hud.unit.pendingChoice", systemLabel(control.id()),
                      control.choices().get(control.selected())), text(nextRound ? "GpuBoard.hud.unit.nextRound"
                      : "GpuBoard.hud.unit.endOfTurn"),
                      own ? records -> records.setSystem(unit, control.id(), control.current()) : null,
                      control.enabled(), ""));
            }
        }
        for (GpuUnitRecord.AmmoBin bin : record.ammo()) {
            if (bin.dumping()) {
                pending.add(new Pending(text("GpuBoard.hud.unit.pendingDump", bin.name()),
                      text("GpuBoard.hud.unit.nextRound"),
                      own ? records -> records.setDumping(unit, bin.eqNum(), false) : null, bin.canDump(),
                      bin.dumpBlocker()));
            }
        }
        return pending;
    }

    private static String systemLabel(String id) {
        return text("GpuBoard.hud.unit.system." + id);
    }

    /** Heat (R3-4), for units that track it: "h → f", the bar with its ticks, one effects line, the sink stepper. */
    private void heat(Table column, GpuBattleStatus.UnitStatus unit, Inputs inputs) {
        if (unit.heatCapacity().isEmpty()) {
            return;
        }
        int forecast = inputs.forecast() == null ? -1 : Math.max(0, inputs.forecast().end());
        Table value = new Table();
        value.add(ui.label(Integer.toString(unit.heat()), "hud-medium", 12, UiTheme.TEXT));
        if (forecast >= 0 && forecast != unit.heat()) {
            value.add(ui.label(" → ", "hud-medium", 12, UiTheme.MUTED));
            value.add(ui.label(Integer.toString(forecast), "hud-medium", 12, UiTheme.AMBER));
        }
        Table rows = section(column, "heat", text("GpuBoard.hud.common.heat"), 0, value);
        if (rows == null) {
            return;
        }
        if (record.heatScale() > 0) {
            GpuHudKit.HeatBar bar = kit.heatBar(false);
            bar.set(unit.heat(), Math.max(unit.heat(), forecast), record.heatScale(), record.heatTicks());
            rows.add(bar).height(11).padTop(6).padBottom(4).row();
        }
        String effects = GpuUnitCard.heatCaption(record, unit.heat(), forecast);
        if (!effects.isEmpty()) {
            Table line = new Table();
            line.add(ui.label(effects, "hud-medium", 12.5f, forecast >= 0 ? UiTheme.AMBER : UiTheme.MUTED))
                  .left().growX();
            String now = GpuUnitCard.effectsAt(record, unit.heat());
            String tip = text("GpuBoard.hud.unit.heatNow", unit.heat(), now.isEmpty() ? "—" : now)
                  + (record.heatBuildup().text().isBlank() ? "" : "\n" + record.heatBuildup().text());
            line.add(info(tip)).padLeft(6);
            rows.add(line).minHeight(KV_HEIGHT).row();
        }
        GpuUnitRecord.SystemControl sinks = control(GpuUnitRecord.HEAT_SINKS);
        if (sinks != null) {
            control(rows, systemLabel(GpuUnitRecord.HEAT_SINKS),
                  stepper(sinks, choice -> source.record().setSystem(record.unitId(), sinks.id(), choice)));
        }
    }

    /** A key / control row: the muted key at the left and a control at the right (.kv with a stepper or select). */
    private void control(Table rows, String key, Actor control) {
        Table row = new Table();
        row.add(ui.label(key, "hud-body", 12.5f, UiTheme.MUTED)).left().expandX();
        row.add(control).right();
        rows.add(row).minHeight(KV_HEIGHT).row();
    }

    /** A stepper (.step): ‹ the chosen value › for a unit-wide choice; disabled at its ends and while blocked. */
    private Table stepper(GpuUnitRecord.SystemControl control, IntConsumer choose) {
        Table step = new Table();
        UiButton previous = ui.button("hud-mini", null, "‹", null);
        UiButton next = ui.button("hud-mini", null, "›", null);
        previous.setName("record-step-previous-" + control.id());
        next.setName("record-step-next-" + control.id());
        previous.setDisabled(!control.enabled() || control.selected() <= 0);
        next.setDisabled(!control.enabled() || control.selected() >= control.choices().size() - 1);
        onChange(previous, () -> {
            if (!previous.isDisabled()) {
                choose.accept(control.selected() - 1);
            }
        });
        onChange(next, () -> {
            if (!next.isDisabled()) {
                choose.accept(control.selected() + 1);
            }
        });
        step.add(previous).size(24);
        step.add(ui.label(Integer.toString(control.selected()), "hud-name", 13, UiTheme.TEXT)).minWidth(18).pad(0, 6, 0,
              6);
        step.add(next).size(24);
        ui.tip(step).getActor().setText(control.choices().get(Math.max(0, control.selected())));
        return step;
    }

    /**
     * Movement: the large MP with its labels and, when reduced, "from" the original; this turn's movement; the facing
     * with the unit's hex as its tooltip; the base PSR with its reasons (R2-8); a flying unit's velocity and altitude.
     */
    private void movement(Table column, GpuBattleStatus.UnitStatus unit) {
        Table rows = section(column, "movement", text("GpuBoard.hud.unit.section.movement"), 0, null);
        if (rows == null) {
            return;
        }
        Map<String, GpuUnitRecord.Vital> flight = vitals(GpuUnitRecord.VitalGroup.FLIGHT);
        Table big = new Table();
        big.left();
        boolean aero = !flight.isEmpty();
        List<String> values = aero ? List.of(Integer.toString(unit.walk()), unit.run())
              : List.of(Integer.toString(unit.walk()), unit.run(), Integer.toString(unit.jump()));
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                big.add(ui.label("/", "hud-title", 18, UiTheme.DISABLED)).pad(0, 5, 0, 5).bottom();
            }
            big.add(ui.label(values.get(index), "hud-heading", 26, UiTheme.TEXT)).bottom();
        }
        big.add(ui.label(UiTheme.upper(text(aero ? "GpuBoard.hud.unit.mpLabels.aero"
              : "GpuBoard.hud.unit.mpLabels")), "hud-caption", 10, UiTheme.MUTED)).padLeft(8).padBottom(5).bottom();
        rows.add(big).padTop(2).padBottom(4).row();
        GpuUnitRecord.Vital mp = vitals(GpuUnitRecord.VitalGroup.MP).get("MP");
        boolean reduced = mp != null && mp.value() < mp.max();
        List<String> causes = GpuUnitCard.mpCauses(record);
        if (reduced || !causes.isEmpty()) {
            // The cause chips (graft 2), the one place the current heat's MP loss is printed, and the original MP.
            HorizontalGroup line = new HorizontalGroup().wrap().rowLeft().space(6).wrapSpace(4);
            line.setName("record-mp-causes");
            causes.forEach(cause -> line.addActor(ui.chip(cause, UiKit.Tone.WARN)));
            if (reduced) {
                line.addActor(ui.label(text("GpuBoard.hud.unit.mpFrom", mp.text().replace("/", " / ")), "hud-body",
                      12, UiTheme.MUTED));
            }
            rows.add(line).growX().padBottom(4).row();
        }
        if (!unit.moved().isEmpty()) {
            kv(rows, text("GpuBoard.hud.unit.thisTurn"), text("GpuBoard.hud.unit.movedTmm", unit.moved(),
                  unit.hexesMoved(), GpuHudKit.signed(unit.tmm())), "");
        }
        if (unit.facing() >= 0) {
            kv(rows, text("GpuBoard.hud.unit.facing"), GpuHudKit.facing(unit.facing()),
                  joined(GpuUnitRecord.InfoSection.HEX));
        }
        for (GpuUnitRecord.InfoRow psr : rows(GpuUnitRecord.InfoSection.PSR)) {
            Table row = kv(rows, text("GpuBoard.hud.unit.basePsr"), text("GpuBoard.hud.common.targetNumber",
                  psr.label()), "");
            if (!psr.text().isBlank()) {
                row.add(info(GpuBoardWindow.plainText(psr.text()))).padLeft(6).padTop(5);
            }
        }
        if (aero) {
            GpuUnitRecord.Vital fuel = flight.get("FUEL");
            kv(rows, text("GpuBoard.hud.unit.flight"), text("GpuBoard.hud.unit.flightValue",
                  value(flight.get("VELOCITY")), value(flight.get("ALTITUDE")),
                  fuel == null ? "—" : fuel.value() + " / " + fuel.max()), "");
        }
    }

    /** Conditions: the Extras tab's "Affected by" list, the status and seen-by lines, and hidden activation. */
    private void conditions(Table column) {
        List<String> lines = new ArrayList<>(record.conditions());
        rows(GpuUnitRecord.InfoSection.STATUS).forEach(row -> lines.add(row.text()));
        rows(GpuUnitRecord.InfoSection.SEEN_BY).forEach(row -> lines.add(row.text()));
        GpuUnitRecord.SystemControl hidden = control(GpuUnitRecord.HIDDEN);
        if (lines.isEmpty() && hidden == null) {
            return;
        }
        Table rows = section(column, "conditions", text("GpuBoard.hud.unit.section.conditions"), lines.size(), null);
        if (rows == null) {
            return;
        }
        lines.forEach(text -> line(rows, text, UiTheme.ACCENT, ""));
        if (hidden != null) {
            UiKit.Segmented choices = ui.segmented("hud-seg", false, hidden.choices().toArray(String[]::new));
            choices.select(hidden.selected());
            for (int index = 0; index < choices.buttons.size(); index++) {
                int choice = index;
                UiButton button = choices.buttons.get(index);
                button.setDisabled(!hidden.enabled());
                onChange(button, () -> source.record().setSystem(record.unitId(), hidden.id(), choice));
            }
            rows.add(ui.label(systemLabel(GpuUnitRecord.HIDDEN), "hud-body", 12.5f, UiTheme.MUTED)).padTop(4).row();
            rows.add(choices).left().padTop(4).row();
        }
    }

    // ------------------------------------------------------------------------------------------------ CREW (5.3)

    private void crew(Table body, Density density, float width) {
        Table[] columns = columns(body, density, width);
        Table rows = section(columns[0], "pilot", text("GpuBoard.hud.record.crew"), record.crew().size(), null);
        if (rows != null) {
            Map<BoardScene.Pixels, BoardScene.Pixels> images = new HashMap<>();
            record.crew().stream().map(GpuUnitRecord.CrewSeat::portrait).filter(pixels -> pixels != null)
                  .forEach(pixels -> images.put(pixels, pixels));
            portraits.update(images);
            for (GpuUnitRecord.CrewSeat seat : record.crew()) {
                rows.add(seat(seat)).padBottom(8).row();
            }
            GpuUnitRecord.SystemControl console = control(GpuUnitRecord.CONSOLE_ROLES);
            if (console != null) {
                UiButton swap = ui.button("hud-mini", null, text("PilotMapSet.swapRoles.text"), null);
                swap.setName("record-swap-roles");
                swap.pressed(console.selected() == 1);
                swap.setDisabled(!console.enabled());
                ui.tip(swap).getActor().setText(text("PilotMapSet.swapRoles.toolTip"));
                onChange(swap, () -> {
                    if (!swap.isDisabled()) {
                        source.record().setSystem(record.unitId(), console.id(), console.selected() == 1 ? 0 : 1);
                    }
                });
                rows.add(swap).left().padTop(4).row();
            }
        }
        if (record.abilities().isEmpty()) {
            return;
        }
        int count = record.abilities().stream().mapToInt(group -> group.options().size()).sum();
        Table advantages = section(columns[1], "advantages", text("PilotMapSet.advantagesL"), count, null);
        if (advantages == null) {
            return;
        }
        float room = density == Density.WIDE ? (width - COLUMN_GAP) / 2 : width;
        List<GpuUnitCard.Chip> chips = new ArrayList<>();
        Map<GpuUnitCard.Chip, String> groups = new HashMap<>();
        for (megamek.client.ui.clientGUI.tooltip.TipUtil.OptionGroup group : record.abilities()) {
            for (String option : group.options()) {
                GpuUnitCard.Chip chip = new GpuUnitCard.Chip(GpuBoardWindow.plainText(option).strip(),
                      UiKit.Tone.NORMAL, SheetTab.CREW);
                chips.add(chip);
                groups.put(chip, group.name());
            }
        }
        Table wrap = new Table();
        wrap.left().top();
        float used = 0;
        for (GpuUnitCard.Chip chip : chips) {
            Label label = ui.chip(chip.text(), chip.tone());
            ui.tip(label).getActor().setText(groups.get(chip));
            float chipWidth = label.getPrefWidth();
            if (used > 0 && used + 8 + chipWidth > room) {
                wrap.row();
                used = 0;
            }
            wrap.add(label).left().padRight(8).padBottom(8);
            used += chipWidth + 8;
        }
        advantages.add(wrap).padTop(4).row();
    }

    /**
     * A crew seat (.seat): the portrait, the name, nickname and role, the skills, six hit boxes filled coral per hit,
     * and the game options' values; an empty seat is dimmed, a status shows only when the member is not active.
     */
    private Table seat(GpuUnitRecord.CrewSeat seat) {
        Table card = new Table();
        card.left().top();
        Table portrait = new Table();
        portrait.setBackground(new UiTheme.EdgeBox(white, null, UiTheme.RAIL, 1, 1, 1, 1));
        if (seat.portrait() != null) {
            portrait.add(new Image(portraits.region(seat.portrait()))).size(54);
        }
        card.add(portrait).size(56).top().padRight(14);
        Table text = new Table();
        text.left().top();
        text.defaults().left();
        Label name = ui.label(UiTheme.upper(seat.name()), "hud-name", 15, UiTheme.TEXT);
        name.setEllipsis(true);
        text.add(name).minWidth(0).growX().row();
        String role = seat.nickname().isBlank() ? seat.role()
              : text("GpuBoard.hud.unit.nicknameRole", seat.nickname(), seat.role());
        text.add(ui.label(role, "hud-body", 12, UiTheme.MUTED)).row();
        Table skills = new Table();
        skills.add(ui.label(text("GpuBoard.hud.unit.gunnery"), "hud-body", 12, UiTheme.MUTED)).padRight(4);
        skills.add(ui.label(seat.gunneryRpg().isEmpty() ? Integer.toString(seat.gunnery()) : seat.gunneryRpg(),
              "hud-name", 14, UiTheme.TEXT)).padRight(14);
        skills.add(ui.label(text("GpuBoard.hud.unit.piloting"), "hud-body", 12, UiTheme.MUTED)).padRight(4);
        skills.add(ui.label(Integer.toString(seat.piloting()), "hud-name", 14, UiTheme.TEXT));
        text.add(skills).padTop(6).row();
        Table hits = new Table();
        hits.add(ui.label(UiTheme.upper(text("GpuBoard.hud.unit.hits")), "hud-caption", 10, UiTheme.MUTED))
              .padRight(4);
        for (int box = 0; box < 6; box++) {
            Table square = new Table();
            boolean hit = box < seat.hits();
            square.setBackground(new UiTheme.EdgeBox(white, hit ? UiTheme.CORAL : null, hit ? UiTheme.CORAL
                  : UiTheme.RAIL, 1, 1, 1, 1));
            hits.add(square).size(10).padLeft(3);
        }
        text.add(hits).padTop(7).row();
        if (!seat.active() && !seat.missing() && !seat.status().isBlank()) {
            text.add(ui.label(seat.status(), "hud-body", 12, UiTheme.CORAL)).padTop(4).row();
        }
        for (GpuUnitRecord.InfoRow detail : seat.details()) {
            text.add(ui.label(detail.label() + " " + detail.text(), "hud-body", 12, UiTheme.MUTED)).padTop(2).row();
        }
        card.add(text).growX().minWidth(0).top();
        card.getColor().a = seat.missing() ? .45f : 1;
        return card;
    }

    // ------------------------------------------------------------------------------------------------ ARMOR (5.4)

    private void armor(Table body, Density density, float width, Inputs inputs) {
        GpuUnitRecord.Vital troopers = vitals(GpuUnitRecord.VitalGroup.TROOPERS).get("TROOPERS");
        GpuUnitRecord.Vital kit = vitals(GpuUnitRecord.VitalGroup.KIT).get("ARMOR");
        if (troopers != null && kit != null) {
            // Conventional infantry (5.4): its men and armor kit are its whole armor record, said once.
            body.add(ui.label(text("GpuBoard.hud.unit.platoon", troopers.value(), troopers.max(), kit.text()),
                  "hud-body", 12.5f, UiTheme.ACCENT)).row();
            return;
        }
        boolean aero = record.locations().stream().anyMatch(location -> location.threshold() > 0)
              || !vitals(GpuUnitRecord.VitalGroup.AERO).isEmpty();
        body.add(totals(aero)).height(22).padBottom(10).row();
        GpuPaperdolls.Doll front = GpuUnitCard.doll(paperdolls, record, GpuPaperdolls.ARMOR);
        if (front == null) {
            locationTable(body, width);
        } else if (record.hitTracks().isEmpty()) {
            // One drawing: armor regions and structure boxes (5.4 other families), a tall one fitted by its height.
            float maxHeight = density == Density.WIDE ? 420 : density == Density.MEDIUM ? 360 : 300;
            float dollWidth = Math.min(Math.min(width, density == Density.WIDE ? 280 : density == Density.MEDIUM ? 220
                  : 180), maxHeight * front.bounds()[2] / front.bounds()[3]);
            GpuPaperdoll doll = doll(front, GpuUnitCard.frontCells(record, View.ARMOR, inputs.deltas()),
                  GpuUnitCard.structureCells(record, View.STRUCTURE, inputs.deltas()), dollWidth);
            body.add(doll).size(dollWidth, height(front, dollWidth)).center().row();
            notDrawn(body, front);
        } else {
            float wide = density == Density.WIDE ? 280 : density == Density.MEDIUM ? 220 : 160;
            float narrow = density == Density.WIDE ? 160 : density == Density.MEDIUM ? 130 : 90;
            float gap = density == Density.NARROW ? 16 : 34;
            Table dolls = new Table();
            dolls.top();
            Table left = new Table();
            left.top();
            left.add(dollCaption("GpuBoard.hud.unit.frontArmor")).left().padBottom(8).row();
            left.add(doll(front, GpuUnitCard.frontCells(record, View.ARMOR, inputs.deltas()), Map.of(), wide))
                  .size(wide, height(front, wide)).row();
            Table right = new Table();
            right.top();
            GpuPaperdolls.Doll rear = GpuUnitCard.doll(paperdolls, record, GpuPaperdolls.REAR);
            GpuPaperdolls.Doll inside = GpuUnitCard.doll(paperdolls, record, GpuPaperdolls.STRUCTURE);
            if (rear != null) {
                right.add(dollCaption("GpuBoard.hud.unit.rearArmor")).left().padBottom(8).row();
                right.add(doll(rear, GpuUnitCard.rearCells(record, inputs.deltas()), Map.of(), narrow))
                      .size(narrow, height(rear, narrow)).row();
            }
            if (inside != null) {
                right.add(dollCaption("GpuBoard.hud.common.structure")).left().padTop(14).padBottom(8).row();
                right.add(doll(inside, Map.of(), GpuUnitCard.structureCells(record, View.STRUCTURE, inputs.deltas()),
                      narrow)).size(narrow, height(inside, narrow)).row();
            }
            dolls.add(left).top().padRight(gap);
            dolls.add(right).top();
            body.add(dolls).center().row();
        }
        if (front != null) {
            List<String> order = new ArrayList<>();
            for (GpuUnitRecord.Location location : record.locations()) {
                if (location.maxArmor() > 0 || location.maxInternal() > 0) {
                    order.add(location.abbr());
                }
                record.shields().stream().filter(shield -> shield.location().equals(location.abbr()))
                      .forEach(shield -> order.add("DC" + shield.location()));
            }
            dollOrder = order;
        }
        if (aero) {
            thresholds(body);
        }
        inspector = new Table();
        inspector.setName("record-inspector");
        inspected = null;
        body.add(inspector).padTop(18).row();
        inspect(state.selectedLocation);
    }

    /** "Armor a / A · Structure s / S" (+ BAR), or the armor alone for an aerospace unit (5.4 first line). */
    private Table totals(boolean aero) {
        int[] sums = GpuUnitCard.totals(record);
        Table line = new Table();
        line.left();
        if (sums[1] > 0) {
            line.add(ui.label(text("GpuBoard.hud.common.armor"), "hud-body", 12, UiTheme.MUTED)).padRight(4);
            line.add(ui.label(sums[0] + " / " + sums[1], "hud-medium", 12, UiTheme.TEXT)).padRight(16);
        }
        if (!aero) {
            line.add(ui.label(text("GpuBoard.hud.common.structure"), "hud-body", 12, UiTheme.MUTED)).padRight(4);
            line.add(ui.label(sums[2] + " / " + sums[3], "hud-medium", 12, UiTheme.TEXT)).padRight(16);
        }
        int bar = record.locations().stream().mapToInt(GpuUnitRecord.Location::bar).max().orElse(0);
        if (bar > 0) {
            line.add(ui.label(text("GpuBoard.hud.unit.bar", bar), "hud-body", 12, UiTheme.MUTED));
        }
        return line;
    }

    private Label dollCaption(String key) {
        return ui.label(UiTheme.upper(text(key)), "hud-caption", 10, UiTheme.MUTED);
    }

    private static float height(GpuPaperdolls.Doll doll, float width) {
        return width * doll.bounds()[3] / doll.bounds()[2];
    }

    /**
     * A sheet doll: its cells, the selected location's outline, and the pointer: the hovered location is outlined, the
     * other dolls outline it in mint and the inspector shows it; a click opens that location in the critical table.
     */
    private GpuPaperdoll doll(GpuPaperdolls.Doll geometry, Map<String, Cell> armorCells,
          Map<String, Cell> structureCells, float width) {
        GpuPaperdoll doll = new GpuPaperdoll(ui.skin);
        doll.maxFont(16);
        doll.show(geometry, armorCells, structureCells);
        doll.dimmed(state.presentedUnits().stream().anyMatch(unit -> unit.id() == record.unitId()
              && unit.destroyed()));
        doll.marks("", state.selectedLocation, "");
        doll.setTouchable(Touchable.enabled);
        doll.addListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                // The hovered location: the other dolls outline it and the inspector shows it (6.7, graft 6).
                String picked = doll.pick(x, y);
                hoveredDoll = doll;
                state.linkedLocation = picked == null ? "" : picked;
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && hoveredDoll == doll && (toActor == null || !toActor.isDescendantOf(doll))) {
                    hoveredDoll = null;
                    state.linkedLocation = "";
                }
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                String picked = doll.pick(x, y);
                if (picked != null && button == Input.Buttons.LEFT) {
                    jump(picked);
                    return true;
                }
                return false;
            }
        });
        doll.setName("record-doll-" + geometry.view());
        dolls.add(doll);
        return doll;
    }

    /**
     * A doll location's click, or Enter on the ARMOR dolls: the SYSTEMS tab (a Mek's critical table) opens and that
     * location's block flashes briefly; nothing is selected or filtered. A unit whose SYSTEMS tab shows nothing keeps
     * its tab.
     */
    private void jump(String code) {
        if (!unused(record).containsKey(SheetTab.SYSTEMS)) {
            flashLocation = GpuCriticalTable.blockLocation(code);
            state.sheetTab = SheetTab.SYSTEMS;
        }
    }

    /** A mint highlight over a location's block that fades out at once (a doll click's answer, not a selection). */
    private void flash(Table block) {
        Image highlight = new Image(ui.skin.newDrawable("white", alpha(UiTheme.MINT, FLASH_ALPHA)));
        highlight.setName("record-flash");
        highlight.setFillParent(true);
        highlight.setTouchable(Touchable.disabled);
        highlight.addAction(Actions.sequence(Actions.fadeOut(FLASH_SECONDS), Actions.removeActor()));
        block.addActor(highlight);
    }

    /** "Not on the drawing: LBS 12 / 20 · RBS 12 / 20" for locations without a region (5.4). */
    private void notDrawn(Table body, GpuPaperdolls.Doll doll) {
        Set<String> drawn = new TreeSet<>();
        doll.regions().forEach(region -> drawn.add(region.code()));
        List<String> missing = record.locations().stream()
              .filter(location -> !drawn.contains(location.abbr()) && location.maxArmor() > 0)
              .map(location -> location.abbr() + " " + location.armor() + " / " + location.maxArmor()).toList();
        if (!missing.isEmpty()) {
            body.add(ui.label(text("GpuBoard.hud.unit.notDrawn", String.join(SEPARATOR, missing)), "hud-body", 12,
                  UiTheme.MUTED)).padTop(10).row();
        }
    }

    /** Aerospace thresholds (R2-8): one line under the drawing, a fact of its own. */
    private void thresholds(Table body) {
        Table line = new Table();
        line.add(ui.label(UiTheme.upper(text("GpuBoard.hud.unit.thresholds")), "hud-caption", 10, UiTheme.MUTED))
              .padRight(18);
        for (GpuUnitRecord.Location location : record.locations()) {
            if (location.threshold() > 0) {
                line.add(ui.label(location.abbr(), "hud-body", 12, UiTheme.MUTED)).padRight(4);
                line.add(ui.label(Integer.toString(location.threshold()), "hud-name", 13, UiTheme.TEXT)).padRight(18);
            }
        }
        body.add(line).center().padTop(12).row();
    }

    private void inspect(String code) {
        if (code.equals(inspected)) {
            return;
        }
        inspected = code;
        Table box = inspector;
        box.clearChildren();
        box.setBackground((Drawable) null);
        if (code.isEmpty()) {
            return;
        }
        String title;
        String detail;
        if (code.startsWith("DC")) {
            GpuUnitRecord.Shield shield = record.shields().stream()
                  .filter(candidate -> candidate.location().equals(code.substring(2))).findFirst().orElse(null);
            if (shield == null) {
                return;
            }
            title = shield.name();
            detail = text("GpuBoard.hud.unit.shieldDetail", shield.location(), shield.baseCapacity(),
                  shield.baseAbsorption());
        } else {
            GpuUnitRecord.Location location = record.locations().stream()
                  .filter(candidate -> candidate.abbr().equals(code)).findFirst().orElse(null);
            if (location == null) {
                return;
            }
            title = location.name();
            detail = GpuUnitCard.locationDetail(location);
        }
        box.setBackground(ui.skin.getDrawable("unit-inspector"));
        box.left();
        box.add(ui.label(UiTheme.upper(title), "hud-name", 14, UiTheme.TEXT)).left().padRight(8);
        Label text = ui.label(detail, "hud-body", 12, UiTheme.MUTED);
        text.setWrap(true);
        box.add(text).growX().minWidth(0).left();
        Label link = link(text(record.hitTracks().isEmpty() ? "GpuBoard.hud.unit.toSystems"
              : "GpuBoard.hud.unit.toCritical"), () -> state.sheetTab = SheetTab.SYSTEMS);
        link.setName("record-inspector-link");
        box.add(link).right().top().padLeft(12);
    }

    /**
     * A unit without a drawing (battle armor, squadrons, emplacements): one row per location with its armor and
     * structure; a destroyed one red-struck (5.4).
     */
    private void locationTable(Table body, float width) {
        for (GpuUnitRecord.Location location : record.locations()) {
            if (location.maxArmor() <= 0 && location.maxInternal() <= 0) {
                continue;
            }
            Table row = new Table();
            row.add(new GpuCriticalTable.StruckLabel(location.name(), ui, "hud-body", 12.5f, location.destroyed()
                  ? UiTheme.CORAL : UiTheme.ACCENT, location.destroyed() ? Strike.RED : Strike.NONE)).left()
                  .expandX();
            if (!location.destroyed()) {
                row.add(location.maxArmor() <= 0 ? null : ui.label(location.armor() + " / " + location.maxArmor(),
                      "hud-medium", 12.5f, GpuPaperdoll.damageFill(location.armor(), location.maxArmor(), false)
                      .number)).width(70);
                row.add(ui.label(location.internal() + " / " + location.maxInternal(), "hud-medium", 12.5f,
                      GpuPaperdoll.damageFill(location.internal(), location.maxInternal(), false).number)).width(70);
            }
            body.add(row).width(width).minHeight(KV_HEIGHT).row();
        }
    }

    // ------------------------------------------------------------------------------------------------ WEAPONS (5.5)

    /** The weapon table's columns: name, location, damage, heat, minimum, S, M, L, ammunition and declared letter. */
    private enum Column {
        ST(16), LOC(28), DMG(38), HEAT(32), MIN(30), S(22), M(22), L(22), AMMO(40), DECL(24);

        private final float width;

        Column(float width) {
            this.width = width;
        }
    }

    private void weapons(Table body, Density density, float width, Inputs inputs) {
        List<Column> columns = new ArrayList<>(List.of(Column.values()));
        columns.remove(Column.ST);
        if (density != Density.WIDE) {
            // Medium: the minimum moves into the row detail.
            columns.remove(Column.MIN);
        }
        if (density == Density.NARROW) {
            // Narrow (graft 22): two-line rows, the state letter and the location beside the name, the statistics
            // under it.
            columns = List.of(Column.ST, Column.LOC, Column.DECL);
        }
        Table header = new Table();
        header.left().padLeft(4);
        GpuUnitRecord.SystemControl order = control(GpuUnitRecord.WEAPON_ORDER);
        if (order != null) {
            UiKit.Icon sort = ui.icon("list", 13, UiTheme.MUTED);
            sort.setName("record-weapon-order");
            ui.tip(sort).getActor().setText(text("GpuBoard.hud.unit.orderTip", order.choices().get(order.selected())));
            sort.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    menu.list(sort, order.choices(), order.selected(),
                          choice -> source.record().setSystem(record.unitId(), order.id(), choice));
                }
            });
            header.add(sort).width(GRIP).padRight(6);
        } else {
            header.add().width(GRIP).padRight(6);
        }
        header.add(head(text("MekDisplay.Name"), UiTheme.MUTED)).growX().left();
        for (Column column : columns) {
            Label head = head(columnHead(column), switch (column) {
                case S -> GpuBoardSkin.BAND_SHORT;
                case M -> GpuBoardSkin.BAND_MEDIUM;
                case L -> GpuBoardSkin.BAND_LONG;
                default -> UiTheme.MUTED;
            });
            if (column == Column.ST) {
                ui.tip(head).getActor().setText(text("GpuBoard.hud.unit.legend.state"));
            }
            header.add(head).width(column.width).padLeft(6).left();
        }
        body.add(header).width(width).height(22).row();
        body.add(new Image(ui.skin.getDrawable("rule"))).height(1).row();
        List<GpuUnitRecord.RecordWeapon> weapons = record.weapons();
        weaponOrder = weapons;
        UiList list = new UiList(ui);
        list.setName("record-weapons");
        weaponList = list;
        // The name's room: the row less its left pad, the grip and the columns with their gaps.
        float nameRoom = width - 4 - GRIP - 6 - (float) columns.stream().mapToDouble(column -> column.width + 6).sum();
        Map<String, Integer> ordinals = new HashMap<>();
        for (int index = 0; index < weapons.size(); index++) {
            GpuUnitRecord.RecordWeapon weapon = weapons.get(index);
            int ordinal = ordinals.merge(weapon.name(), 1, Integer::sum) - 1;
            boolean expanded = weapon.name().equals(state.expandedWeapon) && ordinal == state.expandedOrdinal;
            weaponRow(list, weapon, index, ordinal, columns, nameRoom, expanded, density, inputs);
        }
        // An own unit's weapons move by drag or Alt+Up/Down into its custom order (the Unit Display's list drag).
        int unit = record.unitId();
        list.reorderable((from, to) -> source.record().moveWeapon(unit, weapons.get(from).eqNum(), to - from));
        body.add(list).width(width).row();
    }

    private Label head(String text, Color color) {
        return ui.label(UiTheme.upper(text), "hud-caption", 9.5f, color);
    }

    private static String columnHead(Column column) {
        return switch (column) {
            case ST -> text("GpuBoard.hud.unit.column.state");
            case LOC -> text("GpuBoard.hud.unit.column.location");
            case DMG -> text("GpuBoard.hud.unit.column.damage");
            case HEAT -> text("MekDisplay.Heat");
            case MIN -> text("MekDisplay.Min");
            case S -> text("GpuBoard.hud.unit.column.short");
            case M -> text("GpuBoard.hud.unit.column.medium");
            case L -> text("GpuBoard.hud.unit.column.long");
            case AMMO -> text("MekDisplay.Ammo");
            case DECL -> "";
        };
    }

    /**
     * A weapon row (5.5): destroyed red-struck, unavailable (jammed, spent, without ammunition, blown off or breached)
     * grey-struck, a struck row with its name and location only and its reason as the tooltip; words under the name
     * only for states that do not make it unavailable; the declared target's letter. At narrow density (graft 22) the
     * row has two lines: the name with its state letter and location, then its statistics. A click expands it; the
     * focused list's selection moves with the arrows, and Alt+Up/Down or the grip, shown on hover and focus, reorder an
     * own unit's weapons through {@code list}; hovering it links its location (graft 6). The row joins the list with
     * its rule and, expanded, its detail, which move with it.
     */
    private void weaponRow(UiList list, GpuUnitRecord.RecordWeapon weapon, int index, int ordinal,
          List<Column> columns, float nameRoom, boolean expanded, Density density, Inputs inputs) {
        Strike strike = strike(weapon);
        boolean struck = strike != Strike.NONE;
        Table row = new Table();
        row.setName("record-weapon-" + index);
        row.left().padLeft(4);
        boolean focused = weapon.eqNum() == selectedWeapon && sheet.listsFocused();
        if (expanded || focused) {
            row.setBackground(selectedRow);
        }
        boolean narrow = columns.contains(Column.ST);
        UiKit.Icon grip = ui.icon("grip", 12, UiTheme.DISABLED);
        boolean movable = control(GpuUnitRecord.WEAPON_ORDER) != null;
        grip.setVisible(movable && focused);
        row.add(grip).width(GRIP).padRight(6);
        Table name = new Table();
        name.left();
        String title = weapon.row().techTag() + weapon.name();
        GpuCriticalTable.StruckLabel nameLabel = new GpuCriticalTable.StruckLabel(title, ui, "hud-body", 12.5f,
              strike == Strike.RED ? UiTheme.CORAL : struck ? UiTheme.MUTED : UiTheme.ACCENT, strike);
        // A name too long for its room takes the condensed face, then ends in an ellipsis (3.4, as a slot's).
        if (nameLabel.getPrefWidth() > nameRoom) {
            nameLabel.face(ui, "hud-sub", 12.5f);
        }
        nameLabel.setEllipsis(true);
        name.add(nameLabel).left().minWidth(0).row();
        String words = struck || narrow ? "" : String.join(SEPARATOR, words(weapon, false));
        if (!words.isEmpty()) {
            name.add(ui.label(words, "hud-small", 10.5f, UiTheme.MUTED)).left().row();
        }
        row.add(name).growX().minWidth(0).left();
        for (Column column : columns) {
            Actor cell;
            if (struck && column != Column.LOC) {
                cell = null;
            } else {
                cell = switch (column) {
                    case ST -> stateLetter(weapon);
                    case LOC -> ui.label(weapon.location(), "hud-body", 12.5f, UiTheme.MUTED);
                    case DMG -> ui.label(damage(weapon), "hud-body", 12.5f, UiTheme.ACCENT);
                    case HEAT -> ui.label(heat(weapon), "hud-body", 12.5f, UiTheme.ACCENT);
                    case MIN -> ui.label(range(weapon, 0), "hud-body", 12.5f, UiTheme.MUTED);
                    case S -> ui.label(range(weapon, 1), "hud-name", 12.5f, GpuBoardSkin.BAND_SHORT);
                    case M -> ui.label(range(weapon, 2), "hud-name", 12.5f, GpuBoardSkin.BAND_MEDIUM);
                    case L -> ui.label(range(weapon, 3), "hud-name", 12.5f, GpuBoardSkin.BAND_LONG);
                    case AMMO -> ui.label(weapon.row().totalShots() < 0 ? "—"
                          : Integer.toString(weapon.row().loadedShots()), "hud-body", 12.5f, UiTheme.ACCENT);
                    case DECL -> inputs.declared().containsKey(weapon.eqNum())
                          ? kit.letter(inputs.declared().get(weapon.eqNum()), false)
                          : null;
                };
            }
            if (cell instanceof Label label) {
                label.setEllipsis(true);
            }
            row.add(cell).width(column.width).padLeft(6).left();
        }
        if (narrow && !struck) {
            // The second line: the words the letter does not cover, the statistics and the shots.
            List<String> detail = new ArrayList<>(words(weapon, true));
            detail.add(summary(weapon));
            if (weapon.row().totalShots() >= 0) {
                detail.add(text("GpuBoard.hud.unit.ammoShort", weapon.row().loadedShots()));
            }
            Label second = ui.label(String.join(SEPARATOR, detail), "hud-small", 10.5f, UiTheme.MUTED);
            second.setEllipsis(true);
            row.row();
            row.add();
            row.add(second).colspan(columns.size() + 1).left().minWidth(0).padBottom(4);
        }
        String tip = struck ? reason(weapon) + "\n" + summary(weapon) : weapon.row().totalShots() < 0 ? ""
              : text("GpuBoard.hud.unit.ammoTip", weapon.row().loadedShots(), weapon.row().totalShots());
        if (!tip.isBlank()) {
            ui.tip(row).getActor().setText(tip.strip());
        }
        hoverLink(row, locationOf(weapon), hovered -> grip.setVisible(movable && (hovered || focused)));
        row.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                selectedWeapon = weapon.eqNum();
                expand(weapon, ordinal);
                sheet.focusLists();
            }
        });
        Table entry = new Table();
        entry.add(row).growX().minHeight(30).row();
        entry.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        if (expanded) {
            entry.add(weaponDetail(weapon, density, inputs)).growX().padLeft(16).padBottom(8);
        }
        list.add(entry, movable ? grip : null);
    }

    /** A weapon's strike: red when destroyed with its location or alone, grey when unavailable (5.5). */
    private Strike strike(GpuUnitRecord.RecordWeapon weapon) {
        List<GpuUnitRecord.Slot> slots = slots(weapon.eqNum());
        boolean unavailable = slots.stream().anyMatch(slot -> slot.missing() || slot.breached());
        if (weapon.destroyed() && !unavailable) {
            return Strike.RED;
        }
        return weapon.destroyed() || weapon.crippled() || unavailable ? Strike.GREY : Strike.NONE;
    }

    /** Every slot of a mount, by its equipment number. */
    private List<GpuUnitRecord.Slot> slots(int eqNum) {
        return record.locations().stream().flatMap(location -> location.slots().stream())
              .filter(slot -> slot.eqNum() == eqNum || slot.eqNum2() == eqNum).toList();
    }

    /** Why a struck weapon is out: destroyed, with its location, jammed, out of ammunition or spent. */
    private String reason(GpuUnitRecord.RecordWeapon weapon) {
        GpuUnitRecord.Location location = record.locations().stream()
              .filter(candidate -> candidate.abbr().equals(weapon.location())).findFirst().orElse(null);
        if (location != null && location.destroyed()) {
            return text("GpuBoard.hud.unit.reason.location", location.abbr());
        } else if (weapon.destroyed()) {
            return text("GpuBoard.hud.unit.reason.destroyed");
        } else if (weapon.jammed()) {
            return text("GpuBoard.hud.unit.reason.jammed");
        } else if (weapon.row().totalShots() == 0) {
            return text("GpuBoard.hud.unit.reason.ammo");
        }
        return text("GpuBoard.hud.unit.reason.unavailable");
    }

    /**
     * The short words of a weapon, only for states that do not make it unavailable (R3-6): its mode and the queued
     * one, hot-loaded, rapid fire, fired and dumping (left to the ST letter when {@code lettered}), its RISC module,
     * charge and called shot.
     */
    private List<String> words(GpuUnitRecord.RecordWeapon weapon, boolean lettered) {
        WeaponListModel.RowParts row = weapon.row();
        List<String> words = new ArrayList<>();
        if (row.mode() != null && !row.mode().isBlank()) {
            words.add(row.pendingMode() == null ? text("GpuBoard.hud.unit.mode", row.mode())
                  : text("GpuBoard.hud.unit.modeChange", row.mode(), modeName(row.pendingMode())));
        }
        if (!lettered) {
            if (row.hotLoaded()) {
                words.add(text("GpuBoard.hud.unit.hotLoaded"));
            }
            if (row.rapidFire()) {
                words.add(text("GpuBoard.hud.unit.rapidFire"));
            }
            if (weapon.fired()) {
                words.add(text("GpuBoard.hud.unit.fired"));
            }
            if (dumping(weapon)) {
                words.add(text("GpuBoard.hud.unit.dumping"));
            }
        }
        if (!row.riscModule().isEmpty()) {
            words.add(row.riscModule());
        }
        for (String part : new String[] { row.chargeState(), row.calledShot() }) {
            if (part != null && !part.isBlank()) {
                words.add(part.strip());
            }
        }
        return words;
    }

    /** Whether the weapon's loaded ammunition is being dumped (an own weapon's bins are known). */
    private boolean dumping(GpuUnitRecord.RecordWeapon weapon) {
        if (weapon.loadedAmmo() < 0 || weapon.loadedAmmo() >= weapon.ammoChoices().size()) {
            return false;
        }
        int eqNum = weapon.ammoChoices().get(weapon.loadedAmmo()).eqNum();
        return record.ammo().stream().anyMatch(bin -> bin.eqNum() == eqNum && bin.dumping());
    }

    /**
     * The narrow ST letter (graft 22, U-14): D dumping and HL hot-loaded in amber, F fired muted, RF rapid fire, the
     * first that applies; none for a struck weapon (its strike says it).
     */
    private Label stateLetter(GpuUnitRecord.RecordWeapon weapon) {
        if (dumping(weapon)) {
            return ui.label(text("GpuBoard.hud.unit.st.dumping"), "hud-name", 11, UiTheme.AMBER);
        } else if (weapon.fired()) {
            return ui.label(text("GpuBoard.hud.unit.st.fired"), "hud-name", 11, UiTheme.MUTED);
        } else if (weapon.row().hotLoaded()) {
            return ui.label(text("GpuBoard.hud.unit.hotLoadedTag"), "hud-name", 11, UiTheme.AMBER);
        } else if (weapon.row().rapidFire()) {
            return ui.label(text("GpuBoard.hud.unit.st.rapidFire"), "hud-name", 11, UiTheme.ACCENT);
        }
        return null;
    }

    /** The weapon display's damage, "—" until the sheet's statistics arrive. */
    private static String damage(GpuUnitRecord.RecordWeapon weapon) {
        return weapon.stats() == null ? "—" : weapon.stats().damage();
    }

    private static String heat(GpuUnitRecord.RecordWeapon weapon) {
        return weapon.stats() == null ? "—" : weapon.stats().heat();
    }

    /** A range of {@code getRanges} with the loaded ammunition: 0 minimum, 1 short, 2 medium, 3 long; "—" for none. */
    private static String range(GpuUnitRecord.RecordWeapon weapon, int bracket) {
        return bracket < weapon.ranges().size() && weapon.ranges().get(bracket) > 0
              ? Integer.toString(weapon.ranges().get(bracket)) : "—";
    }

    /** "Dmg 8 · Heat 12 · 7 / 14 / 19": a struck row's statistics, in its tooltip and detail. */
    private static String summary(GpuUnitRecord.RecordWeapon weapon) {
        return text("GpuBoard.hud.unit.statsSummary", damage(weapon), heat(weapon), range(weapon, 1),
              range(weapon, 2), range(weapon, 3));
    }

    /** Expands a weapon row, or collapses it when it is the expanded one. */
    private void expand(GpuUnitRecord.RecordWeapon weapon, int ordinal) {
        boolean open = weapon.name().equals(state.expandedWeapon) && ordinal == state.expandedOrdinal;
        state.expandedWeapon = open ? "" : weapon.name();
        state.expandedOrdinal = open ? 0 : ordinal;
    }

    /**
     * The expanded row's detail (.wdet): an own weapon's controls (the bin it loads and its field of fire) and its mode
     * select when it has modes; at medium density its minimum range; its quirks; a struck row's statistics.
     */
    private Table weaponDetail(GpuUnitRecord.RecordWeapon weapon, Density density, Inputs inputs) {
        Table detail = new Table();
        detail.setBackground(detailBox);
        detail.left().pad(6, 10, 4, 10);
        Table controls = weaponControls(weapon, inputs, density == Density.NARROW);
        if (controls != null) {
            detail.add(controls).growX().left().padTop(4).padBottom(4).row();
        }
        GpuUnitRecord.Equipment modes = equipment(weapon.eqNum());
        if (modes != null && !modes.modes().isEmpty()) {
            Table row = new Table();
            row.add(controlCaption("MekDisplay.modeLabel")).padRight(8);
            UiButton select = modeSelect(modes);
            select.setName("record-weapon-mode");
            row.add(select).minWidth(120);
            detail.add(row).left().padTop(4).padBottom(4).row();
        }
        if (density != Density.WIDE && !range(weapon, 0).equals("—")) {
            detail.add(ui.label(text("GpuBoard.hud.unit.minimum", range(weapon, 0)), "hud-body", 12, UiTheme.MUTED))
                  .left().row();
        }
        if (!weapon.quirks().isBlank()) {
            Label quirks = ui.label(weapon.quirks(), "hud-body", 12, UiTheme.MUTED);
            quirks.setWrap(true);
            detail.add(quirks).growX().left().row();
        }
        if (strike(weapon) != Strike.NONE) {
            detail.add(ui.label(reason(weapon) + SEPARATOR + summary(weapon), "hud-body", 12, UiTheme.MUTED)).left()
                  .row();
        }
        if (!detail.hasChildren() && density != Density.NARROW) {
            // Narrow rows print the statistics on their second line.
            detail.add(ui.label(summary(weapon), "hud-body", 12, UiTheme.MUTED)).left().row();
        }
        return detail;
    }

    /**
     * An own weapon's first control row (.wctl): the bin it loads (design 9 #11) and its field of fire (9 #12), which
     * takes a line of its own at narrow density; null without either. The local firing actor's weapons go through the
     * fire orders, which load the bin and declare a queued attack again with it (H30), and select the weapon whose
     * field of fire the board shows (G10). Any other own weapon loads through the unit record, and its field of fire is
     * the View menu's switch (FIELD_FIRE), which shows the weapon selected for the acting unit and changes no
     * selection.
     */
    private Table weaponControls(GpuUnitRecord.RecordWeapon weapon, Inputs inputs, boolean narrow) {
        boolean loads = !weapon.ammoChoices().isEmpty();
        boolean field = inputs.fieldOfFire() != null && record.own() && !record.removed();
        if (!loads && !field) {
            return null;
        }
        Table row = new Table();
        row.left();
        if (loads) {
            int unit = record.unitId();
            List<String> labels = weapon.ammoChoices().stream().map(GpuUnitRecord.AmmoChoice::label).toList();
            int loaded = weapon.loadedAmmo();
            UiButton select = ui.select(loaded >= 0 && loaded < labels.size() ? labels.get(loaded) : "—", false);
            select.setName("record-weapon-ammo");
            onChange(select, () -> menu.list(select, labels, loaded, choice -> {
                GpuUnitRecord.AmmoChoice bin = weapon.ammoChoices().get(choice);
                if (inputs.firingActor()) {
                    source.fire().setAmmo(weapon.eqNum(), bin);
                } else {
                    source.record().setAmmo(unit, weapon.eqNum(), bin);
                }
            }));
            row.add(controlCaption("GpuBoard.hud.unit.loaded")).padRight(8);
            row.add(select).prefWidth(150).minWidth(60);
        }
        row.add().expandX();
        if (field && loads && narrow) {
            row.row();
            row.add(fieldOfFireButton(weapon, inputs)).colspan(3).left().padTop(6);
        } else if (field) {
            row.add(fieldOfFireButton(weapon, inputs)).padLeft(8);
        }
        return row;
    }

    /**
     * The field of fire button (12: FIELD_FIRE "also offered as buttons"), pressed while the board shows the field of
     * fire it stands for: a local firing actor's weapon is selected first, so that the field is this weapon's; for any
     * other weapon it is the View menu's switch alone, whose tooltip says that it shows the selected weapon's field.
     */
    private UiButton fieldOfFireButton(GpuUnitRecord.RecordWeapon weapon, Inputs inputs) {
        UiButton button = ui.button("hud-mini", null, text("GpuBoard.hud.unit.fieldOfFire"), null);
        button.setName("record-weapon-field");
        boolean shown = Boolean.TRUE.equals(inputs.fieldOfFire());
        boolean selected = !inputs.firingActor() || inputs.firingWeapon() == weapon.eqNum();
        button.pressed(shown && selected);
        button.setDisabled(fieldOfFire == null);
        if (!inputs.firingActor()) {
            String key = inputs.binds().stream().filter(bind -> bind.command() == KeyCommandBind.FIELD_FIRE)
                  .map(GpuBoardSource.Bind::text).findFirst().orElse("");
            ui.tip(button).getActor().setText(text("GpuBoard.hud.unit.fieldOfFireSelectedTip", key));
        }
        onChange(button, () -> {
            Runnable toggle = fieldOfFire;
            if (button.isDisabled() || toggle == null) {
                return;
            }
            if (!selected) {
                source.fire().selectWeapon(weapon.eqNum());
            }
            if (!shown || selected) {
                toggle.run();
            }
        });
        return button;
    }

    /** The muted caption before a control (.wctl .lbl). */
    private Label controlCaption(String key) {
        return ui.label(UiTheme.upper(text(key)), "hud-caption", 10, UiTheme.MUTED);
    }

    /**
     * An own equipment's mode select (.fsel): the entry its mode list selects; picking another switches (setMode, by
     * the entry's index, so a renamed entry switches to MegaMek's own mode).
     */
    private UiButton modeSelect(GpuUnitRecord.Equipment modes) {
        int unit = record.unitId();
        List<String> names = modes.modes().stream().map(GpuUnitSheetTabs::modeName).toList();
        UiButton select = ui.select(names.get(Math.max(0, modes.selected())), false);
        select.setDisabled(!modes.changeable());
        onChange(select, () -> {
            if (!select.isDisabled()) {
                menu.list(select, names, modes.selected(),
                      choice -> source.record().setMode(unit, modes.eqNum(), choice));
            }
        });
        return select;
    }

    /**
     * A mode's name as the sheet shows it: MegaMek names a weapon's normal mode "" (the Unit Display's list shows a
     * blank entry), which reads "Standard" here.
     */
    private static String modeName(String mode) {
        return mode.isBlank() ? text("GpuBoard.hud.unit.modeStandard") : mode;
    }

    private GpuUnitRecord.Equipment equipment(int eqNum) {
        return record.equipment().stream().filter(item -> item.eqNum() == eqNum).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------------------------------------ F5 (5.6, 5.7)

    private void systems(Table body, Density density, float width) {
        if (!record.hitTracks().isEmpty()) {
            Table table = crit.table(record, density, width, selectedSlot, this::slotClicked);
            body.add(table).width(width).row();
            for (GpuUnitRecord.Location location : record.locations()) {
                Table block = table.findActor(GpuCriticalTable.blockName(location.abbr()));
                if (block != null) {
                    hoverLink(block, location.abbr(), hovered -> { });
                }
            }
            List<GpuCriticalTable.SlotKey> order = new ArrayList<>();
            record.locations().forEach(location -> location.slots()
                  .forEach(slot -> order.add(new GpuCriticalTable.SlotKey(location.abbr(), slot.index()))));
            slotOrder = order;
            tableNotes(body);
            return;
        }
        Table[] columns = columns(body, density, width);
        List<GpuCriticalTable.HitRow> tracks = tracks();
        if (!tracks.isEmpty()) {
            Table rows = section(columns[0], "damage", text("GpuBoard.hud.unit.section.damage"), 0, null);
            if (rows != null) {
                rows.add(crit.hitBox(tracks, density)).left().row();
            }
        }
        float column = density == Density.WIDE ? (width - COLUMN_GAP) / 2 : width;
        List<GpuUnitRecord.Location> filled = record.locations().stream()
              .filter(location -> !location.slots().isEmpty()).toList();
        if (filled.isEmpty()) {
            return;
        }
        Table equipmentColumn = tracks.isEmpty() ? columns[0] : columns[1];
        Table rows = section(equipmentColumn, "equipment", text("MekDisplay.Equipment"), filled.size(), null);
        if (rows == null) {
            return;
        }
        for (GpuUnitRecord.Location location : filled) {
            Table entry = new Table();
            entry.top().left();
            Label tag = ui.label(location.abbr(), "hud-name", 10, UiTheme.TEXT);
            Label.LabelStyle style = tag.getStyle();
            style.background = ui.skin.getDrawable("unit-badge");
            tag.setStyle(style);
            tag.setAlignment(Align.center);
            entry.add(tag).top().minWidth(30).height(16).padRight(10);
            Table items = new Table();
            items.top().left();
            for (GpuUnitRecord.Slot slot : location.slots()) {
                GpuCriticalTable.Line line = crit.line(slot.text(), GpuCriticalTable.strike(slot),
                      slot.filler(), slot.armored(), slot.armorHit(), slot.shots(), slot.fullShots(),
                      slot.hotLoaded(), slot.dumping(), density, column - 40);
                GpuCriticalTable.SlotKey key = new GpuCriticalTable.SlotKey(location.abbr(), slot.index());
                line.setTouchable(Touchable.enabled);
                line.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        slotClicked(key, line);
                    }
                });
                items.add(line).width(column - 40).height(GpuCriticalTable.LINE).row();
            }
            entry.add(items).growX().top();
            hoverLink(entry, location.abbr(), hovered -> { });
            rows.add(entry).padTop(6).padBottom(6).row();
        }
        tableNotes(equipmentColumn);
    }

    /** Partial repairs (amber) and ammunition carried for no weapon of the unit, one line each when present. */
    private void tableNotes(Table body) {
        for (GpuUnitRecord.InfoRow repairs : rows(GpuUnitRecord.InfoSection.REPAIRS)) {
            body.add(ui.label(repairs.text(), "hud-body", 12, UiTheme.AMBER)).padTop(8).row();
        }
        List<String> carried = record.ammo().stream().filter(GpuUnitRecord.AmmoBin::carried)
              .map(bin -> bin.name() + " " + bin.shots()).toList();
        if (!carried.isEmpty()) {
            body.add(ui.label(text("GpuBoard.hud.unit.carriedAmmo", String.join(SEPARATOR, carried)), "hud-body", 12,
                  UiTheme.MUTED)).padTop(8).row();
        }
    }

    /**
     * The critical damage tracks of a unit without a critical table (5.7), from its vitals: a vehicle's engine,
     * sensors, motive tier (a word: damage is a tier, not a hit capacity), commander, driver, locked turret and the
     * stabilizers only when one is hit; an aerospace unit's hits and a large craft's drive, sail and collars.
     */
    private List<GpuCriticalTable.HitRow> tracks() {
        List<GpuCriticalTable.HitRow> rows = new ArrayList<>();
        Map<String, GpuUnitRecord.Vital> system = vitals(GpuUnitRecord.VitalGroup.SYSTEM);
        Map<String, GpuUnitRecord.Vital> motive = vitals(GpuUnitRecord.VitalGroup.MOTIVE);
        for (String code : List.of("ENGINE", "SENSORS")) {
            GpuUnitRecord.Vital vital = system.get(code);
            if (vital != null) {
                rows.add(circles(code, vital));
            }
        }
        if (!motive.isEmpty()) {
            GpuUnitRecord.Vital rotor = motive.get("ROTOR");
            String word = rotor != null ? (rotor.value() > 0 ? text("GpuBoard.hud.unit.rotorLost", rotor.value(),
                  rotor.max()) : "—") : GpuUnitCard.motiveWord(record);
            Color color = word.equals("—") ? UiTheme.DISABLED : UiTheme.AMBER;
            rows.add(new GpuCriticalTable.HitRow(text(rotor != null ? "GpuBoard.hud.unit.track.ROTOR"
                  : "GpuBoard.hud.unit.track.MOTIVE"), 0, 0, word, color, ""));
        }
        for (String code : List.of("COMMANDER", "DRIVER")) {
            GpuUnitRecord.Vital vital = system.get(code);
            if (vital != null) {
                rows.add(circles(code, vital));
            }
        }
        Map<String, GpuUnitRecord.Vital> turrets = vitals(GpuUnitRecord.VitalGroup.TURRET);
        for (GpuUnitRecord.Vital turret : turrets.values()) {
            // A second turret tells the two apart by their locations.
            rows.add(new GpuCriticalTable.HitRow(text("GpuBoard.hud.unit.track.TURRET")
                  + (turrets.size() > 1 ? " " + turret.code() : ""), turret.value(), turret.max(), "", null, ""));
        }
        String stabilizers = vitals(GpuUnitRecord.VitalGroup.STABILIZER).values().stream()
              .filter(vital -> vital.value() > 0).map(GpuUnitRecord.Vital::code).collect(Collectors.joining(" "));
        if (!stabilizers.isEmpty()) {
            rows.add(new GpuCriticalTable.HitRow(text("GpuBoard.hud.unit.track.STABILIZER"), 0, 0, stabilizers,
                  UiTheme.CORAL, ""));
        }
        Map<String, GpuUnitRecord.Vital> aero = vitals(GpuUnitRecord.VitalGroup.AERO);
        for (String code : List.of("AVIONICS", "ENGINE", "FCS", "SENSORS", "THRUST_LEFT", "THRUST_RIGHT", "GEAR",
              "LIFE_SUPPORT", "CIC")) {
            GpuUnitRecord.Vital vital = aero.get(code);
            if (vital != null) {
                rows.add(vital.max() > 0 ? circles(code, vital) : new GpuCriticalTable.HitRow(
                      text("GpuBoard.hud.unit.track." + code), 0, 0, Integer.toString(vital.value()),
                      vital.value() > 0 ? UiTheme.CORAL : UiTheme.MUTED, ""));
            }
        }
        for (GpuUnitRecord.Vital vital : vitals(GpuUnitRecord.VitalGroup.SHIP).values()) {
            boolean integrity = List.of("KF", "SAIL", "DC").contains(vital.code());
            rows.add(integrity ? new GpuCriticalTable.HitRow(text("GpuBoard.hud.unit.track." + vital.code()), 0, 0,
                  vital.value() + " / " + vital.max(), vital.value() < vital.max() ? UiTheme.AMBER : UiTheme.ACCENT,
                  "") : circles(vital.code(), vital));
        }
        return rows;
    }

    private static GpuCriticalTable.HitRow circles(String code, GpuUnitRecord.Vital vital) {
        return new GpuCriticalTable.HitRow(text("GpuBoard.hud.unit.track." + code), vital.value(), vital.max(), "",
              null, "");
    }

    /**
     * A slot's or item's popover (5.6, 5.7): its name, location and slot, its state and shots in words, and for an own
     * unit the controls of the equipment in it, one row per mount: a superheavy Mek's slot with two bins lists both,
     * each with its own controls (in place of the Unit Display's "Choose Ammobin" question).
     */
    private void slotClicked(GpuCriticalTable.SlotKey key, Actor line) {
        selectedSlot = key;
        sheet.focusLists();
        GpuUnitRecord.Location location = record.locations().stream()
              .filter(candidate -> candidate.abbr().equals(key.location())).findFirst().orElse(null);
        if (location == null) {
            return;
        }
        GpuUnitRecord.Slot slot = location.slots().stream().filter(candidate -> candidate.index() == key.index())
              .findFirst().orElse(null);
        if (slot == null) {
            return;
        }
        Table content = new Table();
        content.pad(4, 14, 8, 14);
        content.defaults().left().growX();
        String state = slotState(location, slot);
        if (!state.isEmpty()) {
            kv(content, text("GpuBoard.hud.unit.state"), state, "");
        }
        if (slot.shots() >= 0) {
            kv(content, text("GpuBoard.hud.unit.shots"), slot.shots() + " / " + slot.fullShots(), "");
        }
        if (slot.armored()) {
            kv(content, text("GpuBoard.hud.unit.armored"), text(slot.armorHit() ? "GpuBoard.hud.unit.armorHit"
                  : "GpuBoard.hud.unit.armorIntact"), "");
        }
        if (!content.hasChildren()) {
            line(content, text("GpuBoard.hud.unit.noState"), UiTheme.MUTED, "");
        }
        if (record.own() && !record.removed()) {
            List<Integer> mounts = slot.eqNum2() >= 0 ? List.of(slot.eqNum(), slot.eqNum2()) : List.of(slot.eqNum());
            for (int eqNum : mounts) {
                Table controls = mountControls(eqNum, mounts.size() > 1);
                if (controls != null) {
                    content.add(controls).padTop(6).row();
                }
            }
        }
        sheet.popover(slot.text(), text("GpuBoard.hud.unit.slotOf", location.abbr(), slot.index() + 1), content,
              line);
    }

    /**
     * A mount's controls in its slot's popover (.wctl): its mode select and its dump or jettison toggle (the Systems
     * tab's Dump, which the owner confirms), disabled with the rule's reason while the rules block it; {@code named}
     * leads with its name and shots, for a slot with two mounts. Null for a mount without controls.
     */
    private Table mountControls(int eqNum, boolean named) {
        GpuUnitRecord.Equipment modes = equipment(eqNum);
        GpuUnitRecord.AmmoBin bin = record.ammo().stream().filter(candidate -> candidate.eqNum() == eqNum)
              .findFirst().orElse(null);
        boolean moded = modes != null && !modes.modes().isEmpty();
        if (!moded && bin == null) {
            return null;
        }
        Table row = new Table();
        row.left();
        if (named) {
            String name = bin == null ? modes.name()
                  : bin.name() + SEPARATOR + bin.shots() + " / " + bin.fullShots();
            Label label = ui.label(name, "hud-body", 12, UiTheme.ACCENT);
            label.setEllipsis(true);
            row.add(label).left().growX().minWidth(0).padRight(8);
        }
        if (moded) {
            UiButton select = modeSelect(modes);
            select.setName("record-slot-mode-" + eqNum);
            row.add(select).minWidth(110).padRight(8);
        }
        if (bin != null) {
            int unit = record.unitId();
            String action = bin.jettison() ? (bin.dumping() ? "GpuBoard.hud.unit.cancelJettison"
                  : "GpuBoard.hud.unit.jettison") : (bin.dumping() ? "GpuBoard.hud.unit.cancelDump"
                  : "MekDisplay.m_bDumpAmmo");
            UiButton dump = ui.button("hud-mini", null, text(action), null);
            dump.setName("record-slot-dump-" + eqNum);
            dump.setDisabled(!bin.canDump());
            if (!bin.canDump() && !bin.dumpBlocker().isEmpty()) {
                ui.tip(dump).getActor().setText(text(bin.dumpBlocker()));
            }
            onChange(dump, () -> {
                if (!dump.isDisabled()) {
                    sheet.closePopover();
                    source.record().setDumping(unit, eqNum, !bin.dumping());
                }
            });
            row.add(dump);
        }
        return row;
    }

    /** A slot's state in words: the popover is the place for words (5.6). */
    private static String slotState(GpuUnitRecord.Location location, GpuUnitRecord.Slot slot) {
        List<String> words = new ArrayList<>();
        if (location.destroyed() && !location.blownOff()) {
            words.add(text("GpuBoard.hud.unit.slot.withLocation"));
        } else if (slot.destroyed() || slot.hit()) {
            words.add(text("GpuBoard.hud.unit.slot.destroyed"));
        }
        if (slot.missing()) {
            words.add(text("GpuBoard.hud.unit.slot.missing"));
        }
        if (slot.breached()) {
            words.add(text("GpuBoard.hud.unit.slot.breached"));
        }
        if (slot.hotLoaded()) {
            words.add(text("GpuBoard.hud.unit.slot.hotLoaded"));
        }
        if (slot.dumping()) {
            words.add(text("GpuBoard.hud.unit.slot.dumping"));
        }
        if (slot.filler()) {
            words.add(text("GpuBoard.hud.unit.slot.filler"));
        }
        return String.join(SEPARATOR, words);
    }

    // ------------------------------------------------------------------------------------------------ EXTRAS (5.8)

    private void extras(Table body, GpuBattleStatus.UnitStatus unit, Density density, float width, Inputs inputs) {
        Table[] columns = columns(body, density, width);
        List<GpuUnitRecord.InfoRow> sensors = rows(GpuUnitRecord.InfoSection.SENSOR_RANGE);
        GpuUnitRecord.SystemControl next = control(GpuUnitRecord.SENSORS);
        if (!sensors.isEmpty() || next != null || !record.lastTarget().isEmpty()) {
            Table rows = section(columns[0], "sensors", text("GpuBoard.hud.unit.section.sensors"), sensors.size(),
                  null);
            if (rows != null) {
                sensors.forEach(row -> line(rows, row.text(), UiTheme.ACCENT, ""));
                if (next != null && next.selected() >= 0) {
                    UiButton select = ui.select(next.choices().get(next.selected()), false);
                    select.setName("record-next-sensor");
                    select.setDisabled(!next.enabled());
                    onChange(select, () -> {
                        if (!select.isDisabled()) {
                            menu.list(select, next.choices(), next.selected(),
                                  choice -> source.record().setSystem(record.unitId(), next.id(), choice));
                        }
                    });
                    control(rows, text("GpuBoard.hud.unit.nextSensor"), select);
                }
                if (!record.lastTarget().isEmpty()) {
                    kv(rows, text("GpuBoard.hud.unit.lastTarget"), record.lastTarget(), "");
                }
                if (inputs.sensorRanges() != null) {
                    rows.add(sensorRangeButton(inputs)).left().fill(false, false).padTop(6).row();
                }
            }
        }
        List<GpuUnitRecord.InfoRow> network = rows(GpuUnitRecord.InfoSection.NETWORK);
        if (!network.isEmpty()) {
            Table rows = section(columns[0], "network", text("GpuBoard.hud.unit.section.network"), network.size(),
                  null);
            if (rows != null) {
                network.forEach(row -> line(rows, row.text(), UiTheme.ACCENT, ""));
            }
        }
        List<GpuUnitRecord.InfoRow> transport = new ArrayList<>(rows(GpuUnitRecord.InfoSection.CARRIER));
        transport.addAll(rows(GpuUnitRecord.InfoSection.TRANSPORT));
        if (!transport.isEmpty() || !record.carried().isEmpty() || !record.unused().isEmpty()) {
            Table rows = section(columns[0], "transport", text("GpuBoard.hud.unit.section.transport"),
                  transport.size() + record.carried().size(), null);
            if (rows != null) {
                for (GpuUnitRecord.InfoRow row : transport) {
                    String label = text(row.section() == GpuUnitRecord.InfoSection.CARRIER
                          ? "GpuBoard.hud.unit.carriedBy" : "GpuBoard.hud.unit.carrying", row.text());
                    Label link = line(rows, label, UiTheme.MINT, "");
                    link.setTouchable(Touchable.enabled);
                    link.addListener(new ClickListener() {
                        @Override
                        public void clicked(InputEvent event, float x, float y) {
                            state.inspected = row.unitLink();
                        }
                    });
                }
                record.carried().forEach(text -> line(rows, text, UiTheme.ACCENT, ""));
                if (!record.unused().isEmpty()) {
                    line(rows, record.unused(), UiTheme.MUTED, "");
                }
            }
        }
        Table rows = section(columns[1], "unit", text("MekDisplay.Unit"), 0, null);
        if (rows != null) {
            kv(rows, text("GpuBoard.hud.unit.model"), String.join(SEPARATOR, unit.name(), unit.weightClass(),
                  text("GpuBoard.hud.unit.tons", unit.tons())), "");
            rows(GpuUnitRecord.InfoSection.UNIT).forEach(row -> line(rows, row.text(), UiTheme.ACCENT, ""));
        }
        if (!record.readoutRows().isEmpty()) {
            Table readout = section(columns[1], "readout", text("GpuBoard.hud.record.readout"), 0, null);
            if (readout != null) {
                Label link = link(text(readoutOpen ? "GpuBoard.hud.unit.hideReadout"
                      : "GpuBoard.hud.unit.showReadout"), () -> readoutOpen = !readoutOpen);
                link.setName("record-readout");
                readout.add(link).left().minHeight(24).row();
                if (readoutOpen) {
                    readout(readout);
                }
            }
        }
    }

    /** The unit readout as rows (U-12): titles, labelled lines, table rows in columns and text. */
    private void readout(Table rows) {
        for (EntityReadout.Row row : record.readoutRows()) {
            switch (row.kind()) {
                case TITLE -> rows.add(ui.caption(String.join(" ", row.cells()))).padTop(10).row();
                case SPACE -> rows.add().height(6).row();
                case LABELED -> kv(rows, row.cells().isEmpty() ? "" : row.cells().getFirst(),
                      String.join(" ", row.cells().subList(Math.min(1, row.cells().size()), row.cells().size())), "");
                case TABLE_HEADER, TABLE_ROW -> {
                    Table cells = new Table();
                    cells.left();
                    for (String cell : row.cells()) {
                        cells.add(ui.label(cell, row.kind() == EntityReadout.Row.Kind.TABLE_HEADER ? "hud-caption"
                              : "hud-body", row.kind() == EntityReadout.Row.Kind.TABLE_HEADER ? 9.5f : 12,
                              row.kind() == EntityReadout.Row.Kind.TABLE_HEADER ? UiTheme.MUTED : UiTheme.ACCENT))
                              .expandX().uniformX().left();
                    }
                    rows.add(cells).minHeight(18).row();
                }
                case TEXT -> line(rows, String.join(" ", row.cells()), UiTheme.ACCENT, "");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ keys and data

    /**
     * The focused list's keys (12): Up and Down move the selection, Left and Right the critical table's location or
     * the doll's, Enter or Space open a slot's popover, expand a weapon row or open the doll's location in the critical
     * table, and Alt+Up / Alt+Down move an own weapon.
     */
    boolean key(int keycode) {
        if (tab == SheetTab.WEAPONS && !weaponOrder.isEmpty()) {
            boolean alt = UIUtils.alt();
            int delta = keycode == Input.Keys.UP ? -1 : keycode == Input.Keys.DOWN ? 1 : 0;
            int at = weaponOrder.stream().map(GpuUnitRecord.RecordWeapon::eqNum).toList().indexOf(selectedWeapon);
            if (alt && delta != 0 && at >= 0 && control(GpuUnitRecord.WEAPON_ORDER) != null) {
                // The row flies there; the weapon keeps the selection.
                weaponList.move(at, delta);
                return true;
            } else if (delta != 0) {
                selectedWeapon = weaponOrder.get(Math.clamp(at + delta, 0, weaponOrder.size() - 1)).eqNum();
                return true;
            } else if (open(keycode) && at >= 0) {
                GpuUnitRecord.RecordWeapon weapon = weaponOrder.get(at);
                int ordinal = (int) weaponOrder.subList(0, at).stream()
                      .filter(other -> other.name().equals(weapon.name())).count();
                expand(weapon, ordinal);
                return true;
            }
        } else if (tab == SheetTab.ARMOR && !dollOrder.isEmpty()) {
            // The focused doll: Left and Right step through the locations, Enter or Space opens one as a click does.
            if (keycode == Input.Keys.LEFT || keycode == Input.Keys.RIGHT) {
                int at = dollOrder.indexOf(state.selectedLocation);
                int step = keycode == Input.Keys.LEFT ? -1 : 1;
                state.selectedLocation = dollOrder.get(at < 0 ? 0 : Math.floorMod(at + step, dollOrder.size()));
                return true;
            } else if (open(keycode) && !state.selectedLocation.isEmpty()) {
                jump(state.selectedLocation);
                return true;
            }
        } else if (tab == SheetTab.SYSTEMS && !slotOrder.isEmpty()) {
            int at = Math.max(0, slotOrder.indexOf(selectedSlot));
            if (keycode == Input.Keys.UP || keycode == Input.Keys.DOWN) {
                selectedSlot = slotOrder.get(Math.clamp(at + (keycode == Input.Keys.UP ? -1 : 1), 0,
                      slotOrder.size() - 1));
                return true;
            } else if (keycode == Input.Keys.LEFT || keycode == Input.Keys.RIGHT) {
                String location = slotOrder.get(at).location();
                List<String> locations = slotOrder.stream().map(GpuCriticalTable.SlotKey::location).distinct()
                      .toList();
                int next = Math.floorMod(locations.indexOf(location) + (keycode == Input.Keys.LEFT ? -1 : 1),
                      locations.size());
                selectedSlot = slotOrder.stream().filter(key -> key.location().equals(locations.get(next)))
                      .findFirst().orElse(selectedSlot);
                return true;
            } else if (open(keycode) && selectedSlot != null) {
                Actor line = sheetLine(selectedSlot);
                if (line != null) {
                    slotClicked(selectedSlot, line);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * The View menu's sensor ranges as a button (12: SENSOR_RANGE "also offered as buttons"), pressed while they show;
     * the board draws them for the selected unit.
     */
    private UiButton sensorRangeButton(Inputs inputs) {
        UiButton button = ui.button("hud-mini", null, text("GpuBoard.hud.unit.sensorRanges"), null);
        button.setName("record-sensor-ranges");
        button.pressed(inputs.sensorRanges());
        button.setDisabled(sensorRanges == null);
        onChange(button, () -> {
            if (!button.isDisabled() && sensorRanges != null) {
                sensorRanges.run();
            }
        });
        return button;
    }

    private static boolean open(int keycode) {
        return keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER || keycode == Input.Keys.SPACE;
    }

    /** The drawn line of a critical slot, found by its name, as the anchor of its popover; null when not drawn. */
    private Actor sheetLine(GpuCriticalTable.SlotKey key) {
        return body == null ? null : body.findActor("crit-" + key.location() + "-" + key.index());
    }

    /** The tabs a unit does not use, with the reason as the dimmed tab's tooltip (5.1). */
    static Map<SheetTab, String> unused(GpuUnitRecord.Snapshot record) {
        Map<SheetTab, String> unused = new EnumMap<>(SheetTab.class);
        if (record.crew().isEmpty()) {
            unused.put(SheetTab.CREW, text("GpuBoard.hud.unit.noCrew"));
        }
        boolean slots = record.locations().stream().anyMatch(location -> !location.slots().isEmpty());
        boolean tracks = record.vitals().stream().anyMatch(vital -> switch (vital.group()) {
            case SYSTEM, MOTIVE, TURRET, STABILIZER, AERO, SHIP -> true;
            default -> false;
        });
        if (!slots && !tracks && record.hitTracks().isEmpty()) {
            unused.put(SheetTab.SYSTEMS, text("GpuBoard.hud.unit.noSystems"));
        }
        return unused;
    }

    private GpuUnitRecord.SystemControl control(String id) {
        return record.systems().stream().filter(control -> control.id().equals(id)).findFirst().orElse(null);
    }

    private Map<String, GpuUnitRecord.Vital> vitals(GpuUnitRecord.VitalGroup group) {
        Map<String, GpuUnitRecord.Vital> vitals = new LinkedHashMap<>();
        record.vitals().stream().filter(vital -> vital.group() == group)
              .forEach(vital -> vitals.put(vital.code(), vital));
        return vitals;
    }

    private List<GpuUnitRecord.InfoRow> rows(GpuUnitRecord.InfoSection section) {
        return record.info().stream().filter(row -> row.section() == section).toList();
    }

    /** A section's info lines as one text, one per line (a tooltip's). */
    private String joined(GpuUnitRecord.InfoSection section) {
        return rows(section).stream().map(GpuUnitRecord.InfoRow::text).collect(Collectors.joining("\n"));
    }

    private static String value(GpuUnitRecord.Vital vital) {
        return vital == null ? "—" : Integer.toString(vital.value());
    }

    @Override
    public void dispose() {
        portraits.dispose();
    }
}
