/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.CONTACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay.FiringCommand;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The context menus (G12) in the component harness: every menu kind with its texts, details, disabled items and
 * separators (A.13 M1-M7, M10, A.7 G14, C.3), the King Crab menu beside the hud-v3 shot 13, the placement
 * at the pointer, under a select face and above the More button, the keyboard, the press outside, and the commands
 * the items post. The orders are display values read off shot 13, as E3b would publish them.
 */
@Tag("on-demand")
class GpuContextMenuSmokeTest {
    private static final GpuBattleStatus.Snapshot MOVEMENT = GpuHudFixtures.status();
    private static final int WARHAMMER = 2;
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int BATTLEMASTER = 8;
    private static final int AC20 = 1;
    private static final int LRM = 2;
    private static final int SRM = 3;
    private static final int LASER = 4;
    private static final int REAR_LASER = 5;
    /** The right arm's laser, whose attack follows the SRM's; its row is not needed. */
    private static final int ARM_LASER = 6;
    private static final int STANDARD = 11;
    private static final int ARMOR_PIERCING = 12;
    /** Hex 1512, two hexes ahead of the Atlas. */
    private static final Coords HEX = GpuContextMenuTest.HEX;
    private static final String OFF = " (off)";

    /** A menu under test with the services it posts to, as the HUD builds it in its window-wide popover slot. */
    private static final class Menu {
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final GpuFireOrders fire = mock(GpuFireOrders.class);
        final GpuMovePlan moves = mock(GpuMovePlan.class);
        final GpuLosResult los = mock(GpuLosResult.class);
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final List<Integer> selected = new ArrayList<>();
        final GpuContextMenu menu;
        final Table root;
        final UiPopover popover;

        Menu(GpuHudTestStage hud) {
            when(source.fire()).thenReturn(fire);
            when(source.moves()).thenReturn(moves);
            when(source.los()).thenReturn(los);
            menu = new GpuContextMenu(hud.kit, source, state, selected::add);
            root = (Table) menu.actor();
            root.setBounds(0, 0, hud.width(), hud.height());
            hud.window.addActor(root);
            popover = root.findActor("context-menu-popover");
        }

        /** One frame: the status through the HUD state's focus rule, then the menu. */
        Menu update(GpuHudTestStage hud, GpuBoardSource.Frame frame) {
            state.update(frame.status(), GpuUnitRecord.Snapshot.EMPTY, false);
            menu.update(new GpuHud.Inputs(frame, GpuHud.HudView.EMPTY, null, preferences(),
                  GpuHud.Metrics.of(hud.width(), hud.height()), List.of()));
            return this;
        }

        /** The open menu's item with this text. */
        UiButton item(String text) {
            UiButton item = find(popover, text);
            assertNotNull(item, "no item \"" + text + "\" in " + lines(popover));
            return item;
        }
    }

    @Test
    void theEnemyMenuBesideShot13() {
        GpuHudTestStage.run(hud -> {
            Menu menu = new Menu(hud).update(hud, frame(firing(), fire(true)));
            // Shot 13: the King Crab's menu at the pointer, with the popover's corner where the mock's is.
            menu.menu.open(new Coords(12, 3), KING_CRAB, 784, hud.height() - 193);
            assertEquals(List.of("KING CRAB KGC-000", "Visual contact · 11 hex", "Set as attack target", "---",
                  "Inspect unit", "Unit record", "Center camera", "Line of sight from Atlas"),
                  lines(menu.popover));
            Rectangle area = UiTestStage.bounds(menu.popover);
            assertEquals(784, area.x, .01f);
            assertEquals(hud.height() - 193, area.y + area.height, .01f);
            assertTrue(area.width >= 240);
            hud.draw();
            Pixmap image = hud.capture("context-menu-13");
            try {
                hud.compare("context-menu-13", image, menu.popover, "13-context-menu.jpg", 784, 193);
            } finally {
                image.dispose();
            }

            click(hud, menu.item("Set as attack target"));
            verify(menu.fire).focusTarget(KING_CRAB);
            assertFalse(menu.popover.isVisible(), "choosing an item closes the menu");
            menu.menu.open(new Coords(12, 3), KING_CRAB, 784, hud.height() - 193);
            click(hud, menu.item("Inspect unit"));
            assertEquals(KING_CRAB, menu.state.inspected);
            // Its record (unit panel design 2.2): the sheet opens on the inspected enemy, over the overview too
            menu.state.inspected = Entity.NONE;
            menu.state.overview = true;
            menu.menu.open(new Coords(12, 3), KING_CRAB, 784, hud.height() - 193);
            click(hud, menu.item("Unit record"));
            assertEquals(List.of(KING_CRAB, true, false), List.of(menu.state.inspected, menu.state.recordOpen,
                  menu.state.overview));
            menu.menu.open(new Coords(12, 3), KING_CRAB, 784, hud.height() - 193);
            click(hud, menu.item("Center camera"));
            verify(menu.source).locateUnit(KING_CRAB);
            menu.menu.open(new Coords(12, 3), KING_CRAB, 784, hud.height() - 193);
            click(hud, menu.item("Line of sight from Atlas"));
            verify(menu.los).lineOfSight(ATLAS, KING_CRAB, new Coords(12, 3));
            verify(menu.fire, never()).assign(anyInt(), anyInt());
        });
    }

    @Test
    void attackItemsFollowTheOrdersAndTheArmedWeapon() {
        GpuHudTestStage.run(hud -> {
            Menu menu = new Menu(hud).update(hud, frame(firing(), fire(true)));
            // The BattleMaster is a secondary target and the AC/20 is armed.
            menu.state.armedWeapon = AC20;
            menu.menu.open(new Coords(18, 2), BATTLEMASTER, 900, 700);
            assertEquals(List.of("BATTLEMASTER BLR-1G", "Visual contact · 13 hex", "Assign armed AC/20 here",
                  "Edit attacks on this target", "Make primary target", "Remove target and its attacks", "---",
                  "Inspect unit", "Unit record", "Center camera", "Line of sight from Atlas"),
                  lines(menu.popover));
            hud.draw();
            hud.capture("context-menu-enemy-armed").dispose();
            click(hud, menu.item("Assign armed AC/20 here"));
            verify(menu.fire).focusTarget(BATTLEMASTER);
            verify(menu.fire).assign(AC20, BATTLEMASTER);
            assertEquals(-1, menu.state.armedWeapon, "the armed weapon is used once");
            menu.menu.open(new Coords(18, 2), BATTLEMASTER, 900, 700);
            assertNull(find(menu.popover, "Assign armed AC/20 here"));
            click(hud, menu.item("Make primary target"));
            verify(menu.fire).setPrimary(BATTLEMASTER);
            menu.menu.open(new Coords(18, 2), BATTLEMASTER, 900, 700);
            click(hud, menu.item("Remove target and its attacks"));
            verify(menu.fire).removeTarget(BATTLEMASTER);

            // The primary target has no "Make primary"; read-only orders (another player's turn) no attack items.
            menu.menu.open(new Coords(14, 4), TIMBER_WOLF, 900, 700);
            assertEquals(List.of("TIMBER WOLF PRIME", "Visual contact · 9 hex", "Edit attacks on this target",
                  "Remove target and its attacks", "---", "Inspect unit", "Unit record", "Center camera",
                  "Line of sight from Atlas"), lines(menu.popover));
            menu.update(hud, frame(firing(), fire(false)));
            menu.menu.open(new Coords(14, 4), TIMBER_WOLF, 900, 700);
            assertEquals(List.of("TIMBER WOLF PRIME", "Visual contact · 9 hex", "Inspect unit", "Unit record",
                  "Center camera", "Line of sight from Atlas"), lines(menu.popover));
        });
    }

    @Test
    void ownContactAndHexMenus() {
        GpuHudTestStage.run(hud -> {
            Menu menu = new Menu(hud).update(hud, frame(MOVEMENT, move(GpuMovePlan.Mode.AUTO)));
            // M2: the acting Atlas cannot be selected again; the key that centres it is the camera item's detail.
            String space = KeyCommandBind.getDesc(KeyCommandBind.CENTER_ON_SELECTED.keyDefault,
                  KeyCommandBind.CENTER_ON_SELECTED.modifiersDefault);
            menu.menu.open(new Coords(14, 13), ATLAS, 700, 600);
            assertEquals(List.of("ATLAS AS7-D", "Your unit", "Select unit" + OFF, "Center camera [" + space + "]",
                  "Unit record"), lines(menu.popover));
            hud.draw();
            hud.capture("context-menu-friendly").dispose();
            click(hud, menu.item("Unit record"));
            assertTrue(menu.state.recordOpen);
            assertEquals(Entity.NONE, menu.state.inspected, "the acting unit's record needs no inspection");
            menu.menu.open(new Coords(18, 12), WARHAMMER, 700, 600);
            click(hud, menu.item("Select unit"));
            assertEquals(List.of(WARHAMMER), menu.selected, "selecting goes through the HUD's one rule");
            menu.menu.open(new Coords(18, 12), WARHAMMER, 700, 600);
            click(hud, menu.item("Unit record"));
            assertEquals(WARHAMMER, menu.state.inspected);
            menu.update(hud, frame(status(MOVEMENT, unit -> unit.id() == WARHAMMER ? done(unit) : unit),
                  move(GpuMovePlan.Mode.AUTO)));
            menu.menu.open(new Coords(18, 12), WARHAMMER, 700, 600);
            assertEquals("Your unit · acted", lines(menu.popover).get(1));

            // M4: a sensor contact's line of sight measures its bare hex.
            menu.menu.open(new Coords(9, 3), CONTACT, 700, 600);
            assertEquals(List.of("SENSOR CONTACT", "Unidentified · hex 1004", "Center camera",
                  "Inspect sensor return", "Line of sight from Atlas"), lines(menu.popover));
            hud.draw();
            hud.capture("context-menu-contact").dispose();
            click(hud, menu.item("Line of sight from Atlas"));
            verify(menu.los).lineOfSight(ATLAS, Entity.NONE, new Coords(9, 3));

            // M5: in the local planning turn a hex plans or pins; MegaMek's map menu follows under More actions.
            verifyNoInteractions(menu.moves);
            menu.menu.open(HEX, Entity.NONE, 700, 600);
            verify(menu.source).inspect(HEX);
            String title = "HEX 1512";
            String subtitle = "Light woods (TF: 50) · Road (TF: 150) · Woods/Jungle elevation: 2 · level 0";
            assertEquals(List.of(title, subtitle, "Plan move here", "Plan and pin as waypoint [Shift+click]",
                  "Center camera here", "Line of sight from Atlas", "More actions ›" + OFF),
                  lines(menu.popover));
            menu.update(hud, frame(MOVEMENT, move(GpuMovePlan.Mode.AUTO), context()));
            assertEquals("More actions ›", lines(menu.popover).get(6), "filled once the hex's context arrives");
            hud.draw();
            hud.capture("context-menu-hex").dispose();
            click(hud, menu.item("More actions"));
            assertEquals(List.of("MORE ACTIONS", "Hex 1512", "Clear minefield", "Special hex ›"),
                  lines(menu.popover), "the old UI's tool and weapon items stay out");
            click(hud, menu.item("Special hex"));
            assertEquals(List.of("SPECIAL HEX", "More actions", "Mark as objective [Ctrl+O]", "✓ Show elevation"),
                  lines(menu.popover));
            hud.draw();
            hud.capture("context-menu-hex-group").dispose();
            assertTrue(menu.menu.cancel());
            verify(menu.source).inspect(null);
            menu.menu.open(HEX, Entity.NONE, 700, 600);
            click(hud, menu.item("Plan and pin as waypoint"));
            verify(menu.moves).planTo(HEX, 0, true);
            menu.menu.open(HEX, Entity.NONE, 700, 600);
            click(hud, menu.item("Center camera here"));
            verify(menu.source).command(any());
            menu.menu.open(HEX, Entity.NONE, 700, 600);
            click(hud, menu.item("Plan move here"));
            verify(menu.moves).planTo(HEX, 0, false);

            // A jump has one landing hex; outside the planning turn the hex only centres and measures.
            menu.update(hud, frame(MOVEMENT, move(GpuMovePlan.Mode.JUMP)));
            menu.menu.open(HEX, Entity.NONE, 700, 600);
            assertEquals("Plan and pin as waypoint [Shift+click]" + OFF, lines(menu.popover).get(3));
            menu.update(hud, frame(status(MOVEMENT, unit -> unit), GpuMovePlan.Snapshot.EMPTY));
            menu.menu.open(HEX, Entity.NONE, 700, 600);
            assertEquals(List.of(title, subtitle, "Center camera here", "Line of sight from Atlas",
                  "More actions ›" + OFF), lines(menu.popover));
            // No line of sight into the acting unit's own hex.
            menu.menu.open(new Coords(14, 13), Entity.NONE, 700, 600);
            assertFalse(lines(menu.popover).contains("Line of sight from Atlas"));
        });
    }

    @Test
    void weaponRowAndQueuedAttackMenus() {
        GpuHudTestStage.run(hud -> {
            Menu menu = new Menu(hud).update(hud, frame(firing(), fire(true), called(true)));
            List<Boolean> toggled = new ArrayList<>();
            // H36: the AC/20 is assigned to A; the focused King Crab is no target yet.
            menu.menu.weapon(AC20, true, () -> toggled.add(true), 1400, 600);
            assertEquals(List.of("AC/20", "RT · 20 dmg · 7 heat", "Assign to A · Timber Wolf [assigned]",
                  "Assign to B · BattleMaster", "Assign to King Crab", "Remove this attack", "---",
                  "Hide solution and arc", "Called shot", "[RT] AC/20  (8) [loaded]",
                  "[RT] AC/20 Armor-Piercing  (4)"), lines(menu.popover));
            hud.draw();
            hud.capture("context-menu-weapon").dispose();
            click(hud, menu.item("Assign to B · BattleMaster"));
            verify(menu.fire).retarget(AC20, BATTLEMASTER);
            menu.menu.weapon(AC20, true, () -> toggled.add(true), 1400, 600);
            click(hud, menu.item("Assign to A · Timber Wolf"));
            verify(menu.fire).focusTarget(TIMBER_WOLF);
            menu.menu.weapon(AC20, true, () -> toggled.add(true), 1400, 600);
            click(hud, menu.item("Hide solution and arc"));
            assertEquals(List.of(true), toggled, "the weapons panel's own toggle shows or hides the solution");
            menu.menu.weapon(AC20, true, () -> toggled.add(true), 1400, 600);
            click(hud, menu.item("[RT] AC/20  (8)"));
            menu.menu.weapon(AC20, true, () -> toggled.add(true), 1400, 600);
            click(hud, menu.item("[RT] AC/20 Armor-Piercing  (4)"));
            verify(menu.fire).setAmmo(AC20, bin(ARMOR_PIERCING, "[RT] AC/20 Armor-Piercing  (4)"));
            verify(menu.fire, never()).setAmmo(AC20, bin(STANDARD, "[RT] AC/20  (8)"));
            menu.menu.weapon(AC20, true, () -> toggled.add(true), 1400, 600);
            click(hud, menu.item("Called shot"));
            verify(menu.fire).calledShot(AC20);
            menu.menu.weapon(AC20, true, () -> toggled.add(true), 1400, 600);
            click(hud, menu.item("Remove this attack"));
            verify(menu.fire).remove(AC20);

            // An unassigned weapon with modes; the rear laser has no shot at its column's target.
            menu.menu.weapon(LASER, false, () -> { }, 1400, 600);
            assertEquals(List.of("MEDIUM LASER", "LA · 5 dmg · 3 heat", "Assign to A · Timber Wolf",
                  "Assign to B · BattleMaster", "Assign to King Crab", "---", "Show solution and arc",
                  "Next mode [Pulse]", "Previous mode", "Called shot"), lines(menu.popover));
            click(hud, menu.item("Assign to King Crab"));
            verify(menu.fire).assign(LASER, KING_CRAB);
            menu.menu.weapon(LASER, false, () -> { }, 1400, 600);
            click(hud, menu.item("Next mode"));
            verify(menu.fire).cycleMode(LASER, true);
            menu.menu.weapon(REAR_LASER, false, () -> { }, 1400, 600);
            assertEquals("Assign to King Crab" + OFF, lines(menu.popover).get(4));
            assertEquals("Assign to A · Timber Wolf", lines(menu.popover).get(2));

            // H37: an attack moves only among the attacks on its target.
            menu.menu.attack(SRM, 1400, 600);
            String declared = Messages.getString("GpuBoard.hud.context.declaredAttack");
            assertEquals(List.of("SRM 6", declared, "Fire earlier [Alt+↑]", "Fire later [Alt+↓]", "Remove attack"),
                  lines(menu.popover));
            hud.draw();
            hud.capture("context-menu-attack").dispose();
            click(hud, menu.item("Fire earlier"));
            verify(menu.fire).move(SRM, -1);
            menu.menu.attack(AC20, 1400, 600);
            assertEquals(List.of("Fire earlier [Alt+↑]" + OFF, "Fire later [Alt+↓]"),
                  lines(menu.popover).subList(2, 4), "the first attack on its target cannot fire earlier");
            menu.menu.attack(LRM, 1400, 600);
            assertEquals(List.of("Fire earlier [Alt+↑]" + OFF, "Fire later [Alt+↓]" + OFF),
                  lines(menu.popover).subList(2, 4), "the only attack on B moves nowhere");
            menu.menu.attack(SRM, 1400, 600);
            click(hud, menu.item("Remove attack"));
            verify(menu.fire).remove(SRM);

            // Read-only orders: no assignment, nothing to reorder.
            menu.update(hud, frame(firing(), fire(false)));
            menu.menu.weapon(AC20, true, () -> { }, 1400, 600);
            assertEquals(List.of("AC/20", "RT · 20 dmg · 7 heat", "Hide solution and arc"),
                  lines(menu.popover));
            menu.menu.attack(SRM, 1400, 600);
            assertEquals(List.of("Fire earlier [Alt+↑]" + OFF, "Fire later [Alt+↓]" + OFF, "Remove attack" + OFF),
                  lines(menu.popover).subList(2, 5));
        });
    }

    @Test
    void aimAtLocationListsTheAimedShotHandlersChoicesInPlace() {
        GpuHudTestStage.run(hud -> {
            // R4: the handler aims at the focused King Crab's head and offers every location but the covered legs.
            GpuFireOrders.Aim aim = new GpuFireOrders.Aim(List.of("Head", "Center Torso", "Right Torso",
                  "Left Torso", "Right Arm", "Left Arm", "Right Leg", "Left Leg"), List.of(true, true, true, true,
                  true, true, false, false), Mek.LOC_HEAD, Set.of(AC20, LASER));
            Menu menu = new Menu(hud).update(hud, frame(firing(), fire(true, aim)));
            menu.menu.weapon(LASER, false, () -> { }, 1400, 600);
            assertEquals(List.of("MEDIUM LASER", "LA · 5 dmg · 3 heat", "Assign to A · Timber Wolf",
                  "Assign to B · BattleMaster", "Assign to King Crab", "---", "Show solution and arc",
                  "Next mode [Pulse]", "Previous mode", "Aim at location… › [Head]"), lines(menu.popover));
            click(hud, menu.item("Aim at location…"));
            assertEquals(List.of("AIMED SHOT", "Medium Laser", "✓ Head", "Center Torso", "Right Torso", "Left Torso",
                  "Right Arm", "Left Arm", "Right Leg" + OFF, "Left Leg" + OFF, "---", "Don't aim"),
                  lines(menu.popover));
            assertTrue(menu.popover.isVisible(), "the choice opens in place");
            hud.draw();
            hud.capture("context-menu-aim").dispose();
            click(hud, menu.item("Left Arm"));
            verify(menu.fire).aim(LASER, Mek.LOC_LEFT_ARM);
            assertFalse(menu.popover.isVisible());
            menu.menu.weapon(LASER, false, () -> { }, 1400, 600);
            click(hud, menu.item("Aim at location…"));
            click(hud, menu.item("Don't aim"));
            verify(menu.fire).aim(LASER, Entity.LOC_NONE);

            // A weapon that cannot aim, and no offer at all, show no item.
            menu.menu.weapon(SRM, false, () -> { }, 1400, 600);
            assertTrue(lines(menu.popover).stream().noneMatch(line -> line.startsWith("Aim at location")));
            menu.update(hud, frame(firing(), fire(true)));
            menu.menu.weapon(LASER, false, () -> { }, 1400, 600);
            assertTrue(lines(menu.popover).stream().noneMatch(line -> line.startsWith("Aim at location")));
        });
    }

    @Test
    void moreSelectListsKeysPlacementAndOutsidePress() {
        GpuHudTestStage.run(hud -> {
            Menu menu = new Menu(hud).update(hud, frame(MOVEMENT, move(GpuMovePlan.Mode.AUTO)));
            // A.7 G14: the dock's More above its button, 150 to the left.
            List<String> ran = new ArrayList<>();
            UiButton more = UiTestStage.place(hud.window, hud.kit.ui.button("hud", "more", null, null), 1100, 900);
            menu.menu.more(new GpuCommandDock.More("More movement", "Atlas",
                  List.of(command("Walk backwards", "", true, ran), command("Clear route", "Esc", false, ran),
                        command("Hold all remaining units", "", true, ran)),
                  List.of(command("Go prone", "unavailable", false, ran), command("Hull down", "unavailable", false,
                        ran), command("Get up", "Ctrl+U", true, ran))), more);
            assertEquals(List.of("MORE MOVEMENT", "Atlas", "Walk backwards", "Clear route [Esc]" + OFF,
                  "Hold all remaining units", "---", "Go prone [unavailable]" + OFF, "Hull down [unavailable]" + OFF,
                  "Get up [Ctrl+U]"), lines(menu.popover));
            Rectangle button = UiTestStage.bounds(more);
            Rectangle area = UiTestStage.bounds(menu.popover);
            assertEquals(button.x - 150, area.x, .01f);
            assertEquals(button.y + button.height + 8, area.y, .01f);
            hud.draw();
            hud.capture("context-menu-more").dispose();
            // Keys: Down starts at the first enabled item, skips the disabled ones and wraps; Enter chooses.
            assertTrue(hud.stage.keyDown(Input.Keys.DOWN));
            assertTrue(hud.stage.keyDown(Input.Keys.DOWN));
            assertEquals(List.of("Hold all remaining units"), highlighted(menu.popover));
            hud.stage.keyDown(Input.Keys.DOWN);
            assertEquals(List.of("Get up"), highlighted(menu.popover));
            hud.stage.keyDown(Input.Keys.DOWN);
            assertEquals(List.of("Walk backwards"), highlighted(menu.popover));
            hud.stage.keyDown(Input.Keys.END);
            assertTrue(hud.stage.keyDown(Input.Keys.ENTER));
            assertEquals(List.of("Get up"), ran);
            assertFalse(menu.popover.isVisible());
            assertNull(hud.stage.getKeyboardFocus(), "a closed menu leaves the keyboard");

            // C.3: a select face's list below it, the current choice checked; a choice reports its index.
            List<Integer> chosen = new ArrayList<>();
            UiButton face = UiTestStage.place(hud.window, hud.kit.ui.select("By readiness", false), 40, 200);
            face.setWidth(297);
            menu.menu.list(face, List.of("By formation", "By readiness", "By weight class"), 1, chosen::add);
            assertEquals(List.of("By formation", "✓ By readiness", "By weight class"), lines(menu.popover));
            Rectangle faceArea = UiTestStage.bounds(face);
            area = UiTestStage.bounds(menu.popover);
            assertEquals(faceArea.x, area.x, .01f);
            assertEquals(faceArea.y, area.y + area.height, .01f, "the list opens below its face");
            hud.draw();
            hud.capture("context-menu-select").dispose();
            hud.stage.keyDown(Input.Keys.HOME);
            hud.stage.keyDown(Input.Keys.ENTER);
            assertEquals(List.of(0), chosen);

            // M1: kept 10 inside the window at its corner; Esc and a press outside close it.
            menu.menu.open(new Coords(14, 13), ATLAS, hud.width() - 3, 3);
            area = UiTestStage.bounds(menu.popover);
            assertEquals(hud.width() - 10, area.x + area.width, .01f);
            assertEquals(10, area.y, .01f);
            menu.menu.open(new Coords(14, 13), ATLAS, -20, hud.height() + 20);
            area = UiTestStage.bounds(menu.popover);
            assertEquals(10, area.x, .01f);
            assertEquals(hud.height() - 10, area.y + area.height, .01f);
            assertTrue(menu.menu.cancel(), "Esc closes the open menu");
            assertFalse(menu.menu.cancel(), "and then has nothing to close");
            menu.menu.open(HEX, Entity.NONE, 700, 600);
            assertSame(UiMenuList.class, hud.stage.getKeyboardFocus().getClass(), "an open menu takes the keys");
            press(hud, 300, 300);
            assertFalse(menu.popover.isVisible(), "a press outside closes the menu");
            menu.update(hud, frame(MOVEMENT, move(GpuMovePlan.Mode.AUTO)));
            verify(menu.source).inspect(null);
            menu.menu.open(HEX, Entity.NONE, 700, 600);
            click(hud, find(menu.popover, "HEX 1512"));
            assertTrue(menu.popover.isVisible(), "a press inside keeps it open");
        });
    }

    // ---------------------------------------------------------------- fixtures

    /** Shot 13's declaration: the Atlas acting in round 3, every roster unit identified but the contact. */
    private static GpuBattleStatus.Snapshot firing() {
        return new GpuBattleStatus.Snapshot(MOVEMENT.round(), GamePhase.FIRING, true, MOVEMENT.localPlayerId(), ATLAS,
              MOVEMENT.turns(), 0, MOVEMENT.units(), List.of(), false);
    }

    private static GpuBattleStatus.Snapshot status(GpuBattleStatus.Snapshot status, UnaryOperator<UnitStatus> change) {
        return new GpuBattleStatus.Snapshot(status.round(), status.phase(), status.myTurn(), status.localPlayerId(),
              status.actorId(), status.turns(), status.turnIndex(), status.units().stream().map(change).toList(),
              status.initiative(), status.turnOrderHidden());
    }

    private static UnitStatus done(UnitStatus unit) {
        return new UnitStatus(unit.id(), unit.side(), unit.sensorContact(), unit.name(), unit.chassis(), unit.model(),
              unit.tons(), unit.weightClass(), unit.formation(), unit.pilot(), unit.gunnery(), unit.piloting(),
              unit.armor(), unit.structure(), unit.heat(), unit.heatRgb(), unit.heatCapacity(), unit.walk(),
              unit.run(), unit.jump(), unit.moved(), unit.mpUsed(), unit.hexesMoved(), unit.facing(), unit.tmm(),
              false, false, true, unit.destroyed(), unit.damageLevel(), unit.destroyedLocations(), unit.heatEffects(),
              unit.position(), unit.boardId(), unit.icon(), unit.statusWords(), unit.weightClassIndex(),
              unit.declaredAttacks(), unit.ownerId(), unit.statusTiles());
    }

    /**
     * Shot 13's orders: five attacks on A (Timber Wolf, primary) and B (BattleMaster), the King Crab focused. The
     * rear laser's column shows the King Crab, which it cannot hit.
     */
    private static GpuFireOrders.Snapshot fire(boolean editable) {
        return fire(editable, null);
    }

    /** Shot 13's orders with an aimed shot choice on the focused King Crab (null: none offered). */
    private static GpuFireOrders.Snapshot fire(boolean editable, GpuFireOrders.Aim aim) {
        List<GpuFireOrders.WeaponRow> weapons = List.of(
              row(AC20, "AC/20", "RT", "20", 7, "", List.of(bin(STANDARD, "[RT] AC/20  (8)"),
                    bin(ARMOR_PIERCING, "[RT] AC/20 Armor-Piercing  (4)")), 0, 8, TIMBER_WOLF, ""),
              row(LRM, "LRM 20", "LT", "1×20", 6, "", List.of(bin(13, "[LT] LRM 20  (11)")), 0, 11, BATTLEMASTER,
                    ""),
              row(SRM, "SRM 6", "LT", "2×6", 4, "", List.of(bin(14, "[LT] SRM 6  (9)")), 0, 9, TIMBER_WOLF, ""),
              row(LASER, "Medium Laser", "LA", "5", 3, "Pulse", List.of(), -1, -1, KING_CRAB, ""),
              row(REAR_LASER, "Medium Laser", "CT(R)", "5", 3, "", List.of(), -1, -1, KING_CRAB, "rear arc only"));
        List<GpuFireOrders.Target> targets = List.of(new GpuFireOrders.Target(BATTLEMASTER, 'B', "BattleMaster", false,
              1, false), new GpuFireOrders.Target(TIMBER_WOLF, 'A', "Timber Wolf", true, 0, true));
        List<GpuFireOrders.Attack> attacks = List.of(attack(AC20, TIMBER_WOLF, "AC/20"),
              attack(SRM, TIMBER_WOLF, "SRM 6"), attack(ARM_LASER, TIMBER_WOLF, "Medium Laser"),
              attack(LRM, BATTLEMASTER, "LRM 20"));
        return new GpuFireOrders.Snapshot(true, editable, ATLAS, KING_CRAB, AC20, weapons, targets, attacks, 0, true,
              true, "Center", null, null, null, List.of(), null, Map.of(), 4, 0, aim);
    }

    /** One of the Atlas's own bins, by its number and Unit Display entry. */
    private static GpuUnitRecord.AmmoChoice bin(int eqNum, String label) {
        return new GpuUnitRecord.AmmoChoice(ATLAS, eqNum, label);
    }

    private static GpuFireOrders.WeaponRow row(int eqNum, String name, String location, String damage, int heat,
          String mode, List<GpuUnitRecord.AmmoChoice> ammo, int loaded, int shots, int target, String reason) {
        return new GpuFireOrders.WeaponRow(eqNum, name, location, "", damage, heat, mode, ammo, loaded, shots, target,
              7, .58, reason, true, false);
    }

    private static GpuFireOrders.Attack attack(int eqNum, int target, String weapon) {
        return new GpuFireOrders.Attack(eqNum, target, weapon, "RT", "", "", 0, 7, .58, "");
    }

    private static GpuMovePlan.Snapshot move(GpuMovePlan.Mode mode) {
        return new GpuMovePlan.Snapshot(true, true, false, ATLAS, mode, mode != GpuMovePlan.Mode.AUTO, "", List.of(),
              List.of(), List.of(), null, 0, 0, 3, EntityMovementType.MOVE_NONE, "", true, 0, 0, true, List.of(), false,
              false, Map.of(), 0);
    }

    /** MegaMek's context of hex 1512 as the source captures it: the map menu's items, a group among them. */
    private static BoardScene.Context context() {
        List<String> ran = new ArrayList<>();
        return new BoardScene.Context(HEX, List.of(
              command("Clear minefield", "A tooltip that the menu does not show", true, ran),
              new BoardScene.Command("special", "Special hex", "", true, false, false, List.of(
                    new BoardScene.Command("objective", "Mark as objective", "", true, false, false, List.of(),
                          () -> { }, "Ctrl+O", null),
                    new BoardScene.Command("elevation", "Show elevation", "", true, false, false, List.of(),
                          () -> { }, "", true)), () -> { }, "", null)));
    }

    private static BoardScene.Command command(String label, String detail, boolean enabled, List<String> ran) {
        return new BoardScene.Command(label, label, detail, enabled, false, List.of(), () -> ran.add(label));
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuFireOrders.Snapshot fire) {
        return frame(status, fire, called(false));
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuFireOrders.Snapshot fire,
          BoardScene scene) {
        return new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, GpuHudInputTest.panels(GpuMovePlan.Snapshot.EMPTY, fire,
              GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuMovePlan.Snapshot move) {
        return frame(status, move, null);
    }

    /** A movement frame whose tooltip is the light-woods road hex 1512's, with the given hex context. */
    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuMovePlan.Snapshot move,
          BoardScene.Context context) {
        return new GpuBoardSource.Frame(called(false), List.of(), context, List.of(),
              GpuContextMenuTest.terrainLine(0, "woods:1;foliage_elev:2;road:1:9"), null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, GpuHudInputTest.panels(move, GpuFireOrders.Snapshot.EMPTY,
              GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
    }

    /** A scene of board 0 whose hexes are at level 0, with the firing display's called-shot command. */
    private static BoardScene called(boolean enabled) {
        BoardScene scene = mock(BoardScene.class);
        when(scene.tile(any())).thenAnswer(call -> new BoardScene.Tile(call.getArgument(0), 0, -1, false, 0,
              BoardScene.Surface.GRASS, null, null, null, null, null, List.of(), List.of()));
        when(scene.commands()).thenReturn(List.of(new BoardScene.Command(FiringCommand.FIRE_CALLED.getCmd(),
              "Called shot", "", enabled, false, List.of(), () -> { })));
        return scene;
    }

    /** MegaMek's default key texts, as UiPreferences captures them. */
    static GpuBoardSource.UiPreferences preferences() {
        List<GpuBoardSource.Bind> binds = Stream.of(KeyCommandBind.values()).map(bind -> new GpuBoardSource.Bind(bind,
              bind.keyDefault, bind.modifiersDefault, KeyCommandBind.getDesc(bind.keyDefault, bind.modifiersDefault)))
              .toList();
        return new GpuBoardSource.UiPreferences(1, "", "", true, true, false, false, false, binds, 0, 0, 0);
    }

    // ---------------------------------------------------------------- reading and pressing the menu

    /**
     * The open menu as the player reads it, top to bottom: the header's title and subtitle, each item as its text
     * with "✓ " when checked, " ›" when it opens a group, its detail in brackets and " (off)" when disabled, and "---"
     * for a separator.
     */
    private static List<String> lines(UiPopover popover) {
        List<String> lines = new ArrayList<>();
        collect(popover, lines);
        return lines;
    }

    private static void collect(Actor actor, List<String> lines) {
        if (actor instanceof UiButton item) {
            String detail = item.details.isEmpty() ? "" : " [" + item.details.getFirst().getText() + "]";
            boolean checked = item.icons.stream().anyMatch(icon -> icon.isVisible()
                  && ((Image) icon).getDrawable() == item.getSkin().getDrawable("icon-check"));
            boolean group = item.icons.stream().anyMatch(icon
                  -> ((Image) icon).getDrawable() == item.getSkin().getDrawable("icon-chevron-right"));
            lines.add((checked ? "✓ " : "") + item.getText() + (group ? " ›" : "") + detail
                  + (item.isDisabled() ? OFF : ""));
        } else if (actor instanceof Label label && !label.getText().isEmpty()) {
            lines.add(label.getText().toString());
        } else if (actor instanceof Image && actor.getParent() instanceof UiMenuList) {
            lines.add("---");
        } else if (actor instanceof Table table) {
            // Table cells in layout order: an open popover re-adds a header it hid at the end of its children.
            for (Cell<?> cell : table.getCells()) {
                if (cell.getActor() != null) {
                    collect(cell.getActor(), lines);
                }
            }
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, lines));
        }
    }

    /** The menu item or label with this text, or null. */
    private static <T extends Actor> T find(Actor actor, String text) {
        if (actor instanceof UiButton item && item.getText().toString().equals(text)
              || actor instanceof Label label && label.getText().toString().equals(text)) {
            @SuppressWarnings("unchecked")
            T found = (T) actor;
            return found;
        } else if (actor instanceof Group group && !(actor instanceof UiButton)) {
            for (Actor child : group.getChildren()) {
                T found = find(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The texts of the keyboard-highlighted items. */
    private static List<String> highlighted(UiPopover popover) {
        List<String> texts = new ArrayList<>();
        collectHighlighted(popover, texts);
        return texts;
    }

    private static void collectHighlighted(Actor actor, List<String> texts) {
        if (actor instanceof UiButton item) {
            if (item.isChecked()) {
                texts.add(item.getText().toString());
            }
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collectHighlighted(child, texts));
        }
    }

    /** A left click at the actor's centre through the stage's own input. */
    private static void click(GpuHudTestStage hud, Actor actor) {
        Vector2 centre = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        press(hud, centre.x, centre.y);
    }

    /** A left press and release at stage point (x, y). */
    private static void press(GpuHudTestStage hud, float x, float y) {
        Vector2 screen = hud.stage.stageToScreenCoordinates(new Vector2(x, y));
        hud.stage.touchDown((int) screen.x, (int) screen.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) screen.x, (int) screen.y, 0, Input.Buttons.LEFT);
    }
}
