/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay.FiringCommand;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/**
 * Context menus and select lists in one popover (C.1 G12; r1 3.17, r2 8.1, shot 13): the unit menus at the pointer
 * (M2-M4), the hex menu with MegaMek's own map menu in sections (M5, M10), the weapon row and queued attack menus
 * (H36, H37), the dock's More above its button (A.7 G14, H38) and the select lists of select faces (C.3). A group
 * opens its submenu beside its item, as a desktop menu does (the user's decision of 2026-10-03): the menus before it
 * stay open, a press outside them all closes them, and Esc closes the deepest. Items run existing commands only: the
 * HUD's selection rule, the source's locate and centre requests, and the movement, fire and line-of-sight services.
 * Opening a menu never changes orders.
 */
final class GpuContextMenu implements GpuHud.Component {
    /** The dock's More opens this far left of its button (app.js pop:more). */
    private static final float MORE_SHIFT = -150;
    private static final String CENTER_CAMERA = "GpuBoard.hud.common.centerCamera";
    private static final String SEPARATOR = " \u00B7 ";

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final IntConsumer select;
    private final Table root = new Table();
    private final UiPopover popover;
    /** The submenus by depth, from 1, made the first time a menu opens that deep; each lies beside its item. */
    private final List<UiPopover> submenus = new ArrayList<>();
    private GpuHud.Inputs inputs;
    /** The hex whose menu is open; its terrain line and map menu come with later frames. Null otherwise. */
    private Coords hex;
    /** Whether the open hex menu lists MegaMek's map menu, which a later frame brings. */
    private boolean mapListed;
    /** The world height of the terrain the pointer hit where the open menu opened; NaN for none. */
    private float pointedZ = Float.NaN;
    /** The frame parts the open hex menu last showed; a capture publishes new ones. */
    private String shownTooltip;
    private BoardScene.Context shownContext;

    /**
     * {@code select} selects or inspects a unit ({@link GpuHud#select}). Both "Center camera" items go through the
     * client's centre request, which the board view frames with the HUD's camera width.
     */
    GpuContextMenu(GpuHudKit kit, GpuBoardSource source, GpuHudState state, IntConsumer select) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.select = select;
        root.setName("context-menu");
        popover = new UiPopover(ui);
        popover.setName("context-menu-popover");
        root.addActor(popover);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** Keeps the frame the menus read; an open hex menu takes its terrain line and map menu from it. */
    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        if (!popover.isVisible()) {
            closed();
        } else if (hex != null) {
            showHex();
        }
    }

    /** A press outside it, or another menu: closes the open menu with its submenus; true when one was open. */
    boolean cancel() {
        boolean open = popover.cancel();
        closed();
        return open;
    }

    /** One Esc step (C.4): closes the open menu's deepest submenu, or the menu; true when one was open. */
    boolean back() {
        boolean open = popover.back();
        if (!popover.isVisible()) {
            closed();
        }
        return open;
    }

    /** Forgets a closed hex menu and the hex context it asked the source for. */
    private void closed() {
        if (hex != null) {
            hex = null;
            source.inspect(null);
        }
    }

    /**
     * Opens the menu of the unit, or of the hex without one, at stage point (x, y): an own or allied unit (M2), an
     * identified enemy (M3), a sensor contact (M4) or the hex (M5).
     */
    void open(Coords coords, int unitId, float x, float y) {
        open(coords, unitId, x, y, Float.NaN);
    }

    /**
     * As {@link #open(Coords, int, float, float)}, from the board, where the pointer hit terrain at world height
     * {@code pointedZ} (NaN for none): its line of sight measures to that height.
     */
    void open(Coords coords, int unitId, float x, float y, float pointedZ) {
        if (inputs == null) {
            return;
        }
        cancel();
        this.pointedZ = pointedZ;
        UnitStatus unit = state.presented(unitId);
        if (unit == null && (coords == null || inputs.frame().scene() == null)) {
            return;
        } else if (unit == null) {
            hexMenu(coords);
        } else if (unit.sensorContact()) {
            contactMenu(unit);
        } else if (unit.side() == GpuBattleStatus.Side.ENEMY) {
            enemyMenu(unit);
        } else {
            friendlyMenu(unit);
        }
        popover.showAt(x, y);
    }

    /** M2: select (not the acting unit, not an ally's), centre on it as its key does, and its record (2.2). */
    private void friendlyMenu(UnitStatus unit) {
        int id = unit.id();
        int acting = acting();
        boolean own = unit.side() == GpuBattleStatus.Side.OWN;
        UiMenuList list = new UiMenuList(ui);
        item(list, text("GpuBoard.hud.context.selectUnit"), null, own && id != acting, () -> select.accept(id));
        item(list, text(CENTER_CAMERA), GpuHintLine.key(inputs.preferences(), KeyCommandBind.CENTER_ON_SELECTED), true,
              () -> source.locateUnit(id));
        item(list, text("GpuBoard.hud.common.unitRecord"), null, true,
              () -> GpuRecordSheet.showUnit(state, id, acting));
        String subtitle = !own ? text("GpuBoard.hud.forces.allies", owner(unit))
              : text(unit.done() ? "GpuBoard.hud.context.yourUnitActed" : "GpuBoard.hud.context.yourUnit");
        show(title(unit), subtitle, list);
    }

    /**
     * M3: an identified enemy. While the local actor's orders can change, the attack items come first (an armed
     * weapon, the target, primary, removal); then inspect, its record (design 2.2), centre and line of sight, with the
     * hex distance from the acting unit.
     */
    private void enemyMenu(UnitStatus unit) {
        int id = unit.id();
        TargetKey key = TargetKey.unit(id);
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        UiMenuList list = new UiMenuList(ui);
        if (fire.active() && fire.editable() && !unit.destroyed()) {
            GpuFireOrders.WeaponRow armed = row(fire, state.armedWeapon);
            if (armed != null) {
                item(list, text("GpuBoard.hud.context.assignArmed", armed.name()), null, true,
                      () -> focusTarget(source, state, key));
            }
            GpuFireOrders.Target target = fire.targets().stream().filter(candidate -> candidate.key().equals(key))
                  .findFirst().orElse(null);
            if (target == null) {
                item(list, text("GpuBoard.hud.context.setTarget"), null, true, () -> focusTarget(source, state, key));
            } else {
                item(list, text("GpuBoard.hud.context.editAttacks"), null, true,
                      () -> focusTarget(source, state, key));
                if (!target.primary()) {
                    item(list, text("GpuBoard.hud.context.makePrimary"), null, true,
                          () -> source.fire().setPrimary(key));
                }
                item(list, text("GpuBoard.hud.context.removeTarget"), null, true,
                      () -> source.fire().removeTarget(key));
            }
            list.separator();
        }
        item(list, text("GpuBoard.hud.context.inspectUnit"), null, true, () -> state.inspected = id);
        item(list, text("GpuBoard.hud.common.unitRecord"), null, true,
              () -> GpuRecordSheet.showUnit(state, id, acting()));
        item(list, text(CENTER_CAMERA), null, true, () -> source.locateUnit(id));
        lineOfSight(list, unit.position());
        int distance = distance(state.presented(acting()), unit);
        show(title(unit), distance < 0 ? text("GpuBoard.hud.context.visualContact")
              : text("GpuBoard.hud.context.visualContactHex", distance), list);
    }

    /** M4: a sensor contact, whose line of sight measures the bare hex, so its unit's height and cover stay hidden. */
    private void contactMenu(UnitStatus unit) {
        UiMenuList list = new UiMenuList(ui);
        item(list, text(CENTER_CAMERA), null, true, () -> source.locateUnit(unit.id()));
        item(list, text("GpuBoard.hud.context.inspectSensorReturn"), null, true, () -> state.inspected = unit.id());
        lineOfSight(list, unit.position());
        show(text("GpuBoard.hud.common.sensorContact"), unit.position() == null
              ? text("GpuBoard.hud.common.unidentified")
              : text("GpuBoard.hud.context.unidentifiedHex", unit.position().getBoardNum()), list);
    }

    /**
     * M5: a hex. In the local planning turn, plan to it or pin it (a jump has one landing hex); centre on it; line of
     * sight; and MegaMek's map menu for it (M10), which the source captures once asked for this hex.
     */
    private void hexMenu(Coords coords) {
        hex = coords;
        source.inspect(coords);
        shownTooltip = null;
        shownContext = null;
        popover.header(text("GpuBoard.hud.context.hex", coords.getBoardNum()), null).content(hexItems());
        showHex();
    }

    /**
     * The open hex menu's items: plan and pin in the local planning turn, centre, line of sight, and MegaMek's map menu
     * once captured. The map menu's commands are listed in sections rather than behind a menu of menus (the user's
     * decision of 2026-10-03).
     */
    private UiMenuList hexItems() {
        Coords coords = hex;
        UiMenuList list = new UiMenuList(ui);
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        if (GpuHud.planning(inputs)) {
            int board = inputs.frame().scene().boardId();
            item(list, text("GpuBoard.hud.context.planMove"), null, true,
                  () -> source.moves().planTo(coords, board, false));
            item(list, text("GpuBoard.hud.context.planPin"), text("GpuBoard.hud.mouse.ctrlClick"),
                  move.mode() != GpuMovePlan.Mode.JUMP, () -> source.moves().planTo(coords, board, true));
        }
        item(list, text("GpuBoard.hud.context.centerHere"), null, true,
              () -> source.command(() -> source.currentView().centerOnHex(coords)));
        lineOfSight(list, coords);
        List<BoardScene.Command> map = mapActions();
        mapListed = !map.isEmpty();
        sections(list, map);
        return list;
    }

    /**
     * MegaMek's map menu (M10) in the hex menu: each group's commands in a section of their own and the other commands
     * together, each section after a separator; a group within a section opens as a submenu. A disabled group's
     * commands cannot run, so it shows none.
     */
    private void sections(UiMenuList list, List<BoardScene.Command> commands) {
        boolean loose = false;
        for (BoardScene.Command command : commands) {
            boolean group = !command.children().isEmpty();
            if (group && !command.enabled()) {
                continue;
            }
            if (group || !loose) {
                list.separator();
            }
            loose = !group;
            commands(popover, list, group ? command.children() : List.of(command), true);
        }
    }

    /**
     * The open hex menu's subtitle and map menu from the newest capture: its tooltip and context, compared by identity
     * because each capture publishes new ones. The map menu is listed once it arrives.
     */
    private void showHex() {
        String tooltip = inputs.frame().tooltip();
        BoardScene.Context context = inputs.frame().context();
        if (tooltip != shownTooltip || context != shownContext) {
            shownTooltip = tooltip;
            shownContext = context;
            if (!mapListed && inputs.frame().scene() != null && !mapActions().isEmpty()) {
                popover.content(hexItems());
            }
            BoardScene scene = inputs.frame().scene();
            BoardScene.Tile tile = scene == null ? null : scene.tile(hex);
            popover.header(text("GpuBoard.hud.context.hex", hex.getBoardNum()),
                  tile == null ? null : subtitle(tooltip, hex, tile.elevation()));
        }
    }

    /** MegaMek's map menu of the open hex (M10), once the source captured that hex's context. */
    private List<BoardScene.Command> mapActions() {
        BoardScene.Context context = inputs.frame().context();
        return context == null || !context.coords().equals(hex) ? List.of() : context.commands();
    }

    /**
     * The hex's subtitle (M5): MegaMek's terrain and the level, from the tooltip's line for that hex
     * (HexTooltip.getTerrainTip: "Hex: {xxyy} - Level: {n}", then each terrain after a dot spacer); "Clear" when it
     * names no terrain. Null while the tooltip shows another hex or no terrain line (the map hex tooltip setting).
     */
    static String subtitle(String tooltip, Coords hex, int level) {
        for (String key : List.of("BoardView1.Tooltip.Hex", "BoardView1.Tooltip.HexAlt")) {
            String start = Messages.getString(key, hex.getBoardNum(), level);
            for (String line : tooltip == null ? new String[0] : tooltip.split("\n")) {
                String terrain = line.strip();
                if (terrain.equals(start) || terrain.startsWith(start + UIUtil.DOT_SPACER)) {
                    List<String> parts = Arrays.stream(terrain.substring(start.length())
                          .split(UIUtil.DOT_SPACER.strip())).map(String::strip).filter(part -> !part.isEmpty())
                          .toList();
                    return text("GpuBoard.hud.context.hexLevel",
                          parts.isEmpty() ? text("GpuBoard.hud.context.clearTerrain") : String.join(SEPARATOR, parts),
                          level);
                }
            }
        }
        return null;
    }

    /**
     * Opens the weapon row menu (H36) of the local actor's weapon {@code eqNum} at stage point (x, y): assign it to
     * each target or to the focused enemy (disabled where the orders know it has no shot), remove its attack, show or
     * hide its solution and arc ({@code solutionShown}; {@code solution} is the weapons panel's toggle, the row name's
     * click), and MegaMek's mode, called shot, aimed shot and ammunition choices.
     */
    void weapon(int eqNum, boolean solutionShown, Runnable solution, float x, float y) {
        GpuFireOrders.Snapshot fire = inputs == null ? GpuFireOrders.Snapshot.EMPTY : inputs.frame().panels().fire();
        GpuFireOrders.WeaponRow row = row(fire, eqNum);
        if (row == null) {
            return;
        }
        cancel();
        TargetKey assigned = fire.attacks().stream().filter(attack -> attack.eqNum() == eqNum)
              .map(GpuFireOrders.Attack::target).findFirst().orElse(TargetKey.NONE);
        UiMenuList list = new UiMenuList(ui);
        if (fire.editable()) {
            for (GpuFireOrders.Target target : fire.targets().stream()
                  .sorted(Comparator.comparing(GpuFireOrders.Target::letter)).toList()) {
                item(list, text("GpuBoard.hud.context.assignToTarget", target.letter(), target.name()),
                      target.key().equals(assigned) ? text("GpuBoard.hud.context.assigned") : null,
                      shot(row, target.key()), () -> assign(eqNum, assigned, target.key()));
            }
            GpuFireOrders.Focus focus = fire.focus();
            if (!focus.key().equals(TargetKey.NONE)
                  && fire.targets().stream().noneMatch(target -> target.key().equals(focus.key()))) {
                item(list, text("GpuBoard.hud.weapons.assignTo", focus.name()), null, shot(row, focus.key()),
                      () -> assign(eqNum, assigned, focus.key()));
            }
            if (!assigned.equals(TargetKey.NONE)) {
                item(list, text("GpuBoard.hud.context.removeThisAttack"), null, true,
                      () -> source.fire().remove(eqNum));
            }
            list.separator();
        }
        item(list, text(solutionShown ? "GpuBoard.hud.context.hideSolution" : "GpuBoard.hud.context.showSolution"),
              null, true, solution);
        if (fire.editable()) {
            settings(list, row, eqNum, fire.aim());
        }
        show(row.name(), GpuWeaponsPanel.detail(row), list);
        popover.showAt(x, y);
    }

    /**
     * MegaMek's weapon settings: its next and previous mode for a weapon with modes, the called shot while the phase
     * offers it, the aimed shot's locations when the weapon may aim (R4), and each ammunition when it has several (the
     * loaded one says so and stays loaded).
     */
    private void settings(UiMenuList list, GpuFireOrders.WeaponRow row, int eqNum, GpuFireOrders.Aim aim) {
        if (row.mode() != null && !row.mode().isEmpty()) {
            item(list, text("GpuBoard.hud.context.nextMode"), row.mode(), true,
                  () -> source.fire().cycleMode(eqNum, true));
            item(list, text("GpuBoard.hud.context.previousMode"), null, true,
                  () -> source.fire().cycleMode(eqNum, false));
        }
        BoardScene scene = inputs.frame().scene();
        if (scene != null && scene.commands().stream().anyMatch(command -> command.enabled()
              && command.id().equals(FiringCommand.FIRE_CALLED.getCmd()))) {
            item(list, text("GpuBoard.hud.context.calledShot"), null, true, () -> source.fire().calledShot(eqNum));
        }
        if (aim != null && aim.weapons().contains(eqNum)) {
            String aimed = aim.location() >= 0 && aim.location() < aim.locations().size()
                  ? aim.locations().get(aim.location()) : null;
            submenu(popover, list, text("GpuBoard.hud.context.aimAtLocation"), aimed, null, true,
                  (menu, choices) -> aim(choices, eqNum, aim));
        }
        if (row.ammo().size() > 1) {
            for (int index = 0; index < row.ammo().size(); index++) {
                GpuUnitRecord.AmmoChoice ammo = row.ammo().get(index);
                boolean loaded = index == row.loadedAmmo();
                // The bin's Unit Display entry, as the row's select lists it: it names its carrier and shots.
                item(list, ammo.label(), loaded ? text("GpuBoard.hud.context.loaded") : null, true, () -> {
                    if (!loaded) {
                        source.fire().setAmmo(eqNum, ammo);
                    }
                });
            }
        }
    }

    /**
     * The aimed shot's choice in place of MegaMek's dialog (R4), in {@code list}: the locations of the focus it lists,
     * those it disables dim and the one aimed at checked, then "Don't aim".
     */
    private void aim(UiMenuList list, int eqNum, GpuFireOrders.Aim aim) {
        for (int location = 0; location < aim.locations().size(); location++) {
            int chosen = location;
            item(list, aim.locations().get(location), null, location == aim.location(), aim.enabled().get(location),
                  () -> source.fire().aim(eqNum, chosen));
        }
        list.separator();
        item(list, text("AimedShotDialog.dontAim"), null, aim.location() == Entity.LOC_NONE, true,
              () -> source.fire().aim(eqNum, Entity.LOC_NONE));
    }

    /**
     * Assigns the weapon to the target (H15): a weapon that attacks another target is retargeted in its place (H18);
     * its own target is only focused.
     */
    private void assign(int eqNum, TargetKey assigned, TargetKey target) {
        if (assigned.equals(target)) {
            source.fire().focusTarget(target);
        } else if (!assigned.equals(TargetKey.NONE)) {
            source.fire().retarget(eqNum, target);
        } else {
            source.fire().assign(eqNum, target);
        }
    }

    /**
     * Whether the orders allow the weapon a shot at the target: it can fire, and the target its to-hit column shows
     * ({@code targetId}: its attack's, else the focus) has no reason against it. Other targets' numbers are not in the
     * orders; an assignment the rules refuse is answered by the fire service's refusal toast (H17).
     */
    private static boolean shot(GpuFireOrders.WeaponRow row, TargetKey target) {
        return row.usable() && (!row.target().equals(target) || row.reason() == null || row.reason().isBlank());
    }

    /**
     * Opens the queued attack menu (H37) of the local actor's weapon {@code eqNum} at stage point (x, y): fire it
     * earlier or later among the attacks on its target, or remove it.
     */
    void attack(int eqNum, float x, float y) {
        GpuFireOrders.Snapshot fire = inputs == null ? GpuFireOrders.Snapshot.EMPTY : inputs.frame().panels().fire();
        List<GpuFireOrders.Attack> attacks = fire.attacks();
        int index = IntStream.range(0, attacks.size()).filter(at -> attacks.get(at).eqNum() == eqNum).findFirst()
              .orElse(-1);
        if (index < 0) {
            return;
        }
        cancel();
        GpuFireOrders.Attack attack = attacks.get(index);
        boolean earlier = index > 0 && attacks.get(index - 1).target().equals(attack.target());
        boolean later = index + 1 < attacks.size() && attacks.get(index + 1).target().equals(attack.target());
        UiMenuList list = new UiMenuList(ui);
        item(list, text("GpuBoard.hud.context.fireEarlier"), text("GpuBoard.hud.context.altUp"),
              fire.editable() && earlier, () -> source.fire().move(eqNum, -1));
        item(list, text("GpuBoard.hud.context.fireLater"), text("GpuBoard.hud.context.altDown"),
              fire.editable() && later, () -> source.fire().move(eqNum, 1));
        item(list, text("GpuBoard.hud.context.removeAttack"), null, fire.editable(), () -> source.fire().remove(eqNum));
        show(attack.weapon(), text("GpuBoard.hud.context.declaredAttack"), list);
        popover.showAt(x, y);
    }

    /**
     * Shows the dock's More popover above its {@code button}, 150 units to its left: the dock's own items, a separator
     * and MegaMek's other phase commands, each with its detail.
     */
    void more(GpuCommandDock.More more, Actor button) {
        cancel();
        UiMenuList list = new UiMenuList(ui);
        commands(popover, list, more.items(), false);
        if (!more.items().isEmpty() && !more.commands().isEmpty()) {
            list.separator();
        }
        commands(popover, list, more.commands(), false);
        popover.header(more.title(), more.subtitle()).content(list);
        popover.showAbove(button, MORE_SHIFT);
    }

    /**
     * Opens the select list of a select face (C.3) below it: the choices, a check mark at {@code selected}; choosing
     * one calls {@code choose} with its index.
     */
    void list(Actor face, List<String> choices, int selected, IntConsumer choose) {
        cancel();
        UiMenuList list = new UiMenuList(ui);
        for (int index = 0; index < choices.size(); index++) {
            int choice = index;
            item(list, choices.get(index), null, index == selected, true, () -> choose.accept(choice));
        }
        popover.header(null, null).content(list);
        Vector2 corner = face.localToStageCoordinates(new Vector2());
        popover.showAt(corner.x, corner.y);
    }

    /**
     * Commands as items of {@code menu}'s list with their detail, or their shortcut ({@code shortcuts}, for MegaMek's
     * map menu, whose details are tooltips); a group opens its commands as a submenu beside its item.
     */
    private void commands(UiPopover menu, UiMenuList list, List<BoardScene.Command> commands, boolean shortcuts) {
        for (BoardScene.Command command : commands) {
            String detail = shortcuts ? command.shortcut() : command.detail();
            if (command.children().isEmpty()) {
                item(list, command.label(), detail, command.selected(), command.enabled(), command.action());
            } else {
                submenu(menu, list, command.label(), detail, command.selected(), command.enabled(),
                      (submenu, children) -> commands(submenu, children, command.children(), shortcuts));
            }
        }
    }

    /**
     * An item of {@code menu}'s list that opens a submenu beside it, as a desktop menu does: {@code fill} fills the
     * submenu's list, {@code menu} stays open, and a submenu it opened before closes.
     */
    private void submenu(UiPopover menu, UiMenuList list, String text, String detail, Boolean checked,
          boolean enabled, BiConsumer<UiPopover, UiMenuList> fill) {
        UiButton[] item = new UiButton[1];
        item[0] = list.item(text, detail, checked, true, enabled, () -> {
            UiPopover submenu = level(depth(menu) + 1);
            UiMenuList children = new UiMenuList(ui);
            fill.accept(submenu, children);
            submenu.header(null, null).content(children);
            menu.cascade(submenu, item[0]);
        });
    }

    /** The submenu popover {@code depth} levels below the menu, from 1, made the first time a menu opens that deep. */
    private UiPopover level(int depth) {
        while (submenus.size() < depth) {
            UiPopover submenu = new UiPopover(ui);
            submenu.setName("context-submenu-" + (submenus.size() + 1));
            root.addActor(submenu);
            submenus.add(submenu);
        }
        return submenus.get(depth - 1);
    }

    private int depth(UiPopover menu) {
        return menu == popover ? 0 : submenus.indexOf(menu) + 1;
    }

    /**
     * "Line of sight from {acting}" (M8), which MegaMek's ruler measures and shows; never from outside the board or
     * into its own hexes.
     */
    private void lineOfSight(UiMenuList list, Coords to) {
        UnitStatus acting = state.presented(acting());
        if (acting != null && acting.position() != null && to != null && !occupies(acting, to)) {
            item(list, text("GpuBoard.hud.context.lineOfSight", name(acting)), null, true,
                  () -> source.los().lineOfSight(acting.id(), to, pointedZ));
        }
    }

    /** The unit stands in that hex: one of its drawn parts' hexes, or its position while the scene lacks it. */
    private boolean occupies(UnitStatus unit, Coords coords) {
        BoardScene scene = inputs.frame().scene();
        List<Coords> hexes = scene == null ? List.of() : scene.units().stream().filter(drawn -> drawn.id() == unit.id())
              .flatMap(drawn -> drawn.footprint().isEmpty() ? Stream.of(drawn.location().coords())
                    : drawn.footprint().stream()).toList();
        return hexes.isEmpty() ? coords.equals(unit.position()) : hexes.contains(coords);
    }

    /**
     * As a left click on the target in the local declaration (H15, H16): focus it, and assign the armed weapon to it
     * once.
     */
    static void focusTarget(GpuBoardSource source, GpuHudState state, TargetKey target) {
        source.fire().focusTarget(target);
        if (state.armedWeapon >= 0) {
            source.fire().assign(state.armedWeapon, target);
            state.armedWeapon = -1;
        }
    }

    /** An item that closes the menu before it runs {@code action}. */
    private void item(UiMenuList list, String text, String detail, boolean enabled, Runnable action) {
        item(list, text, detail, null, enabled, action);
    }

    private void item(UiMenuList list, String text, String detail, Boolean checked, boolean enabled, Runnable action) {
        list.item(text, detail, checked, false, enabled, () -> {
            cancel();
            action.run();
        });
    }

    /** A unit menu's content. */
    private void show(String title, String subtitle, UiMenuList list) {
        popover.header(title, subtitle).content(list);
    }

    /** The prototype's acting(): the acting unit during the local turn, otherwise the own focus unit (C.5). */
    private int acting() {
        return GpuHudKit.UnitRow.acting(inputs.frame().status(), state.focus());
    }

    private static GpuFireOrders.WeaponRow row(GpuFireOrders.Snapshot fire, int eqNum) {
        return fire.weapons().stream().filter(row -> row.eqNum() == eqNum).findFirst().orElse(null);
    }

    /** The prototype's "{name} {variant}" header. */
    private static String title(UnitStatus unit) {
        return unit.chassis().isEmpty() ? unit.name() : (unit.chassis() + " " + unit.model()).strip();
    }

    /** A unit's short name, as its nameplate and card show it. */
    private static String name(UnitStatus unit) {
        return unit.chassis().isEmpty() ? unit.name() : unit.chassis();
    }

    /** The allied player's name, as the forces list groups allies, or the formation while the list lacks it. */
    private String owner(UnitStatus unit) {
        return inputs.frame().panels().players().players().stream().filter(row -> row.id() == unit.ownerId())
              .map(GpuPlayers.PlayerRow::name).findFirst().orElse(unit.formation());
    }

    /** Hexes between two units on the same board, or -1 when either is missing or elsewhere. */
    private static int distance(UnitStatus from, UnitStatus to) {
        return from == null || from.position() == null || to.position() == null || from.boardId() != to.boardId()
              ? -1 : from.position().distance(to.position());
    }
}
