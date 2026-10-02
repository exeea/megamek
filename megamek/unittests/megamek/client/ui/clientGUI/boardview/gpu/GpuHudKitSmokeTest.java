/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiTestStage.place;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.Letter;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The battle HUD's own widgets and the two harnesses: unit rows and sprites, letter squares and heat bars in one
 * swatch beside crops of the hud-v3 shots 02, 05 and 12, on the toolkit's generic widgets (UiGallerySmokeTest shows
 * those alone); sprite mask ownership; and the roster over the shipped board as sprite models in the 3D view and as
 * GpuUnitIcons in both views.
 */
@Tag("on-demand")
class GpuHudKitSmokeTest {
    /** The heat scale and tick marks drawn in the prototype's pictures (ui.js); swatch inputs, not a heat table. */
    private static final int PICTURE_HEAT_SCALE = 30;
    private static final List<Integer> PICTURE_HEAT_TICKS = List.of(5, 8, 14, 19, 24);
    private static final GpuBattleStatus.Snapshot STATUS = GpuHudFixtures.status();

    @Test
    void everyBattleWidgetBesideTheMock() {
        GpuHudTestStage.run(hud -> {
            GpuHudKit kit = hud.kit;
            STATUS.units().stream().filter(unit -> !unit.sensorContact())
                  .forEach(unit -> hud.sprites.add(unit.icon()));
            Table forces = place(hud.window, forces(kit), 20, 20);
            Table weapons = place(hud.window, weapons(kit), 340, 20);
            Table contactCard = contactCard(kit);
            place(hud.window, contacts(kit, contactCard), 670, 205);
            place(hud.window, states(kit), 1348, 150);
            Table card = place(hud.window, card(kit), 20, 560);
            Table overview = place(hud.window, overview(kit), 940, 560);
            hud.draw();
            Pixmap swatch = hud.capture("hud-kit-swatch");
            try {
                hud.compare("hud-kit-forces", swatch, forces, "02-movement-fire-preview.jpg", 20, 130);
                hud.compare("hud-kit-card", swatch, card, "05-weapon-declaration.jpg", 20, 810);
                hud.compare("hud-kit-weapons", swatch, weapons, "05-weapon-declaration.jpg", 1590, 315);
                hud.compare("hud-kit-overview", swatch, overview, "12-force-overview.jpg", 20, 90);
                hud.compare("hud-kit-contact-card", swatch, contactCard, "12-force-overview.jpg", 38, 572);
            } finally {
                swatch.dispose();
            }
            // E rule 6 at the three window sizes: the forces panel in its left-column slot never leaves the window,
            // meets the utility row's slot or lets a child overflow it.
            float utilityWidth = utilityRowWidth(kit.ui);
            hud.window.clearChildren();
            for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
                hud.size(size[0], size[1]);
                place(hud.window, forces, 20, 20);
                hud.draw();
                hud.capture("hud-kit-forces-" + size[0] + "x" + size[1]).dispose();
                Rectangle utilitySlot = new Rectangle(size[0] - 20 - utilityWidth, size[1] - 18 - 56, utilityWidth,
                      56);
                hud.assertLayout(forces, utilitySlot);
            }
        });
    }

    @Test
    void masksLiveWhileReferenced() {
        GpuHudTestStage.run(hud -> {
            // Sprite masks share one atlas that lives while the snapshots reference them, drawn or not (C.4).
            Set<Integer> live = UiTestStage.liveTextures();
            BoardScene.Pixels atlas = GpuHudFixtures.sprite("Atlas");
            GpuHudKit.UnitSprite sprite = hud.kit.sprite(40, 34).set(atlas, GpuHudKit.FRIEND_SPRITE);
            place(hud.window, sprite, 100, 300);
            hud.sprites.add(atlas);
            hud.sprites.add(GpuHudFixtures.sprite("Warhammer"));
            hud.draw();
            assertEquals(1, added(live).size(), "One atlas page holds both referenced masks");
            sprite.setVisible(false);
            hud.draw();
            assertEquals(1, added(live).size(), "A mask outlives a frame that does not draw it");
            hud.sprites.clear();
            sprite.remove();
            hud.draw();
            assertTrue(added(live).isEmpty(), "Masks are freed once nothing references them");
        });
    }

    /** The GL textures alive now that were not alive {@code before}. */
    private static Set<Integer> added(Set<Integer> before) {
        Set<Integer> now = UiTestStage.liveTextures();
        now.removeAll(before);
        return now;
    }

    @Test
    void boardSpaceHarnessDrawsTheRosterInTheThreeDimensionalAndTacticalViews() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        assertEquals(10, scene.units().size());
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            GpuUnitIcons icons = new GpuUnitIcons();
            try {
                Map<BoardScene.Unit, Vector3> iconAnchors = new HashMap<>();
                for (boolean tactical : new boolean[] { false, true }) {
                    board.view(tactical);
                    assertEquals(tactical, board.tactical());
                    String view = tactical ? "tactical" : "3d";
                    board.units = false;
                    board.draw(camera -> { });
                    Pixmap bare = hud.captureBackBuffer("board-space-" + view + "-bare");
                    try {
                        if (!tactical) {
                            // The 3D view's units: GpuBattleView's sprite models and the contact's marker.
                            board.units = true;
                            board.draw(camera -> { });
                            board.units = false;
                            Pixmap units = hud.captureBackBuffer("board-space-units-3d");
                            try {
                                assertEquals(scene.units().size(), board.anchors.size(), "An anchor for every unit");
                                for (BoardScene.Unit unit : scene.units()) {
                                    Vector2 center = board.screen(
                                          board.instances.get(unit).transform.getTranslation(new Vector3()));
                                    assertTrue(changedTexels(bare, units, center) >= 13, unit.name()
                                          + " is drawn at " + center + " in the 3D view");
                                }
                            } finally {
                                units.dispose();
                            }
                        }
                        // GpuBattleView shows icons only in the Tactical View; here they are forced on in 3D as
                        // well, so a real board-space component checks the tilted camera, ground heights and
                        // projection.
                        board.draw(camera -> {
                            icons.update(true, camera, board.scene, board.status,
                                  unit -> unit.id() == GpuHudFixtures.ATLAS, unit -> false, board.poses,
                                  iconAnchors, board.surfaces);
                            icons.render(camera);
                        });
                        Pixmap drawn = hud.captureBackBuffer("board-space-icons-" + view);
                        try {
                            for (BoardScene.Unit unit : board.scene.units()) {
                                assertNotNull(icons.instance(unit), "An icon for " + unit.name());
                                Vector2 center = board.screen(
                                      icons.instance(unit).transform.getTranslation(new Vector3()));
                                assertTrue(changedTexels(bare, drawn, center) >= 13, unit.name()
                                      + "'s icon is drawn at " + center + " in the " + view + " view");
                            }
                        } finally {
                            drawn.dispose();
                        }
                    } finally {
                        bare.dispose();
                    }
                }
            } finally {
                icons.dispose();
                board.dispose();
            }
        });
    }

    // ---------------------------------------------------------------- swatch groups

    /** The forces navigator of shot 02: header tools, tabs, grouped rows, footer. */
    private static Table forces(GpuHudKit kit) {
        UiKit ui = kit.ui;
        Table forces = panel(ui, "panel", 2, 0);
        forces.add(ui.header("Forces", null, ui.button("hud-icon", "expand", null, null),
              ui.button("hud-icon", "search", null, null), ui.button("hud-icon", "grid", null, null))).growX().row();
        UiKit.Segmented tabs = ui.segmented("hud-tab", false, "Friendly 5", "Contacts 5").select(0);
        tabs.left().pad(2, 14, 0, 14);
        forces.add(tabs).growX().row();
        forces.add(group(ui, "Alpha lance", "3 units")).growX().row();
        String[] lines = { "Acting now", "Pending", "Pending", "Pending", "Pending" };
        for (GpuBattleStatus.UnitStatus unit : STATUS.units()) {
            if (unit.side() != GpuBattleStatus.Side.OWN) {
                continue;
            }
            if (unit.id() == 4) {
                forces.add(group(ui, "Bravo lance", "2 units")).growX().row();
            }
            GpuHudKit.UnitRow row = kit.unitRow(40, 34).set(unit.icon(), GpuHudKit.FRIEND_SPRITE, unit.model(),
                  unit.chassis(), lines[unit.id() - 1], unit.canActNow() ? Tone.OK : Tone.WARN);
            row.pressed(unit.canActNow());
            forces.add(row).growX().pad(0, 10, 6, 10).row();
        }
        forces.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).padTop(2).row();
        Table foot = new Table();
        foot.pad(8, 14, 9, 14);
        foot.add(ui.label("5 pending / 5", "hud-small", 11.5f, UiTheme.MUTED)).expandX().left();
        foot.add(ui.button("hud-mini", null, "Next pending ›", null));
        forces.add(foot).growX();
        forces.setWidth(300);
        return forces;
    }

    private static Table group(UiKit ui, String name, String count) {
        Table group = new Table();
        group.pad(9, 14, 5, 14);
        group.add(ui.icon("chevron-down", 12, UiTheme.MUTED)).padRight(6);
        group.add(ui.label(name, "hud-medium", 11.5f, UiTheme.ACCENT)).expandX().left();
        group.add(ui.label(count, "hud-small", 11, UiTheme.MUTED));
        return group;
    }

    /** The unit card of shot 05: meters, the heat line with its forecast, a chip and two buttons. */
    private static Table card(GpuHudKit kit) {
        UiKit ui = kit.ui;
        Table card = panel(ui, "panel", 14, 16);
        card.add(new Label(UiTheme.upper("Atlas"), ui.skin, "hud-heading")).left().colspan(2).row();
        card.add(ui.label("AS7-D · 100 t · Cpt. R. Hayes 3/4", "hud-body", 12, UiTheme.MUTED))
              .left().colspan(2).row();
        card.add(ui.meter("Armor", false).set("91%", .91f, UiTheme.MINT)).growX().padTop(10).padRight(12);
        card.add(ui.meter("Structure", false).set("100%", 1, UiTheme.MINT)).growX().padTop(10).row();
        GpuHudKit.HeatBar heat = kit.heatBar(false).set(0, 4, PICTURE_HEAT_SCALE, PICTURE_HEAT_TICKS);
        // The heat value's parts: white heat, the amber forecast after the arrow icon (Roboto has no U+2192), and the
        // muted sinks in a lighter weight.
        Table heatValue = new Table();
        heatValue.add(ui.label("0", "hud-button", 13, Color.WHITE));
        heatValue.add(ui.icon("arrow-right", 15, UiTheme.AMBER)).padLeft(3).padRight(2);
        heatValue.add(ui.label("4", "hud-button", 13, UiTheme.AMBER));
        heatValue.add(ui.label(UiTheme.upper(" · 20 sinks"), "hud-sub", 13, UiTheme.MUTED));
        card.add(ui.meter("Heat", heat).value(heatValue)).colspan(2).growX().padTop(10).row();
        Table movement = new Table();
        movement.add(ui.label("W / R / J", "hud-small", 11.5f, UiTheme.MUTED)).padRight(8);
        movement.add(ui.label("3 / 5 / 0", "hud-name", 14, Color.WHITE)).expandX().left();
        movement.add(ui.label("100 tons", "hud-small", 11.5f, UiTheme.MUTED));
        card.add(movement).colspan(2).growX().padTop(10).row();
        card.add(ui.chip("Walk · 2 hex · TMM +0", Tone.NORMAL)).colspan(2).left().padTop(9).row();
        Table buttons = new Table();
        buttons.defaults().growX().uniformX().minHeight(34);
        buttons.add(ui.button("hud-plain", "report", "Unit record", null)).padRight(8);
        buttons.add(ui.button("hud-plain", "locate", "Locate", null));
        card.add(buttons).colspan(2).growX().padTop(11);
        card.setWidth(300);
        return card;
    }

    /** The weapons panel of shot 05: header count, pills with letters, two weapon rows and the heat forecast. */
    private static Table weapons(GpuHudKit kit) {
        UiKit ui = kit.ui;
        Table weapons = panel(ui, "panel", 2, 0);
        // The weapons panel shows its count at the right, where tools go.
        weapons.add(ui.header("Weapons", null, ui.label("5 / 7 queued", "hud-medium", 12, UiTheme.MUTED)))
              .growX().row();
        Table pills = new Table();
        pills.left().pad(0, 14, 6, 14);
        pills.add(pill(kit, "A", "Timber Wolf", Letter.FOCUSED)).padRight(6);
        pills.add(pill(kit, "B", "BattleMaster", Letter.SECONDARY));
        weapons.add(pills).growX().row();
        weapons.add(ui.label("Assign weapon › A · Timber Wolf", "hud-small", 11.5f, UiTheme.MUTED))
              .left().pad(0, 14, 8, 14).row();
        weapons.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        weapons.add(weapon(ui, "row-weapon-selected", "AC/20", "RT · 20 dmg · 7 heat", "8 shots", "7+",
              "58%", "A")).growX().pad(6, 10, 0, 10).row();
        weapons.add(weapon(ui, "row", "LRM 20", "LT · 1×20 dmg · 6 heat", "11 shots", "8+", "42%",
              "B")).growX().pad(6, 10, 10, 10).row();
        Table heat = new Table();
        heat.pad(9, 14, 11, 14);
        heat.add(ui.caption("Heat if fired")).left();
        heat.add(ui.label("0 › 24 · −20 at end = 4", "hud-body", 12, UiTheme.TEXT))
              .expandX().right().row();
        heat.add(kit.heatBar(true).set(0, 24, PICTURE_HEAT_SCALE, PICTURE_HEAT_TICKS)).colspan(2).growX()
              .pad(7, 0, 6, 0).row();
        heat.add(ui.label("No heat effects at the end of the turn", "hud-small", 11.5f, UiTheme.ACCENT))
              .colspan(2).left();
        weapons.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        weapons.add(heat).growX();
        weapons.setWidth(310);
        return weapons;
    }

    private static Table pill(GpuHudKit kit, String letter, String name, Letter kind) {
        boolean on = kind == Letter.FOCUSED;
        Table pill = new Table();
        pill.setBackground(kit.ui.skin.getDrawable(on ? "pill-on" : "pill"));
        pill.add(kit.letter(letter, kind, false)).padRight(6);
        pill.add(kit.ui.label(name, "hud-medium", 11.5f, on ? Color.valueOf("17201D") : UiTheme.TEXT));
        if (on) {
            pill.add(kit.ui.icon("star", 11, UiTheme.AMBER)).padLeft(6);
        }
        return pill;
    }

    private static Table weapon(UiKit ui, String background, String name, String detail, String shots,
          String target, String chance, String slot) {
        Table row = new Table();
        row.setBackground(ui.skin.getDrawable(background));
        Table text = new Table();
        text.left().defaults().left();
        text.add(new Label(UiTheme.upper(name), ui.skin, "hud-name")).row();
        text.add(ui.label(detail, "hud-small", 11, UiTheme.MUTED)).row();
        Table ammo = new Table();
        ammo.add(ui.select("Standard ammo", true)).padRight(6);
        ammo.add(ui.label("· " + shots, "hud-small", 11, UiTheme.ACCENT));
        text.add(ammo).padTop(2);
        row.add(text).growX().left();
        Table odds = new Table();
        odds.add(ui.label(target, "hud-heading", 20, UiTheme.MINT)).right().row();
        odds.add(ui.label(chance, "hud-small", 10.5f, UiTheme.MUTED)).right().padTop(3);
        row.add(odds).minWidth(40).padRight(10);
        row.add(ui.button("hud", null, slot, null).pressed(true)).size(30);
        return row;
    }

    /** The forces overview of shot 12: header, grouping and side segments, search, and three unit cards. */
    private static Table overview(GpuHudKit kit) {
        UiKit ui = kit.ui;
        Table overview = panel(ui, "panel", 2, 0);
        overview.add(ui.header("Forces overview", "10 units",
              ui.button("hud-icon", "close", null, null))).growX().row();
        Table bar = new Table();
        bar.pad(2, 16, 10, 16);
        bar.add(ui.caption("Group")).padRight(8);
        bar.add(ui.segmented("hud-seg", false, "Formation", "Weight", "Status").select(2));
        bar.add(ui.caption("Show")).pad(0, 16, 0, 8);
        bar.add(ui.segmented("hud-seg", false, "Both", "Mine", "Contacts").select(0));
        bar.add().expandX();
        bar.add(ui.search("Search units…")).width(280);
        overview.add(bar).growX().row();
        overview.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        Table side = new Table();
        side.add(ui.label(UiTheme.upper("Your force"), "hud-heading", 17, UiTheme.MINT)).padRight(10);
        side.add(ui.label(UiTheme.upper("5 shown"), "hud-medium", 11.5f, UiTheme.MUTED));
        overview.add(side).left().pad(14, 16, 0, 16).row();
        overview.add(ui.label(UiTheme.upper("Needs orders  5"), "hud-name", 12, UiTheme.ACCENT)).left()
              .pad(12, 16, 7, 16).row();
        Table cards = new Table();
        cards.defaults().width(255).padRight(10);
        for (GpuBattleStatus.UnitStatus unit : STATUS.units().subList(0, 3)) {
            cards.add(unitCard(kit, unit));
        }
        overview.add(cards).left().pad(0, 16, 14, 16);
        overview.setWidth(960);
        return overview;
    }

    private static Table unitCard(GpuHudKit kit, GpuBattleStatus.UnitStatus unit) {
        UiKit ui = kit.ui;
        Table card = cardTop(ui, UiTheme.MINT, 3);
        card.add(cardHead(ui, kit.sprite(36, 30).set(unit.icon(), GpuHudKit.FRIEND_SPRITE), unit.chassis(),
              unit.model() + " · " + (int) unit.tons() + " t")).colspan(3).growX().pad(11, 13, 0, 13).row();
        card.defaults().growX().uniformX().pad(9, 13, 0, 0);
        card.add(ui.meter("Armor", true).set(String.valueOf(Math.round(unit.armor() * 100)), (float) unit.armor(),
              UiTheme.MINT)).padLeft(13);
        card.add(ui.meter("Struct.", true).set("100", 1, UiTheme.MINT)).padLeft(10);
        card.add(ui.meter("Heat", true).set(String.valueOf(unit.heat()), unit.heat() / (float) PICTURE_HEAT_SCALE,
              UiTheme.AMBER))
              .padLeft(10).padRight(13).row();
        Table meta = new Table();
        meta.add(ui.label(UiTheme.upper("Needs orders"), "hud-name", 10.5f, UiTheme.AMBER)).expandX()
              .left();
        meta.add(ui.label("W " + unit.walk() + " · R " + unit.run(), "hud-small", 11, UiTheme.MUTED))
              .expandX();
        meta.add(ui.label(unit.gunnery() + "/" + unit.piloting(), "hud-small", 11, UiTheme.MUTED));
        card.add(meta).colspan(3).pad(9, 13, 10, 13);
        return card;
    }

    /** An overview card's surface and its 2-unit top border in the side's color (.ucard, .ucard.red). */
    private static Table cardTop(UiKit ui, Color side, int columns) {
        Table card = new Table();
        card.setBackground(ui.skin.newDrawable("white", new Color(1, 1, 1, .03f)));
        card.add(new Image(ui.skin.newDrawable("white", side))).colspan(columns).growX().height(2).row();
        return card;
    }

    /** An overview card's head (.ucard .h): the sprite, the upper-case name and the sub-line. */
    private static Table cardHead(UiKit ui, GpuHudKit.UnitSprite sprite, String title, String sub) {
        Table head = new Table();
        head.add(sprite).padRight(10);
        Table name = new Table();
        name.left().defaults().left();
        name.add(ui.label(UiTheme.upper(title), "hud-name", 15, UiTheme.TEXT)).row();
        name.add(ui.label(sub, "hud-small", 11, UiTheme.MUTED));
        head.add(name).growX().left();
        return head;
    }

    /** Sensor contacts: a forces row and a fire preview row (.prow.contact) with the "?", then the overview card. */
    private static Table contacts(GpuHudKit kit, Table card) {
        Table contacts = new Table();
        contacts.defaults().width(300).left().padBottom(8);
        contacts.add(kit.unitRow(40, 34).set(null, UiTheme.BLIP, "Unidentified", "Sensor contact",
              "Unidentified · sensor return", Tone.NORMAL)).row();
        GpuHudKit.UnitRow preview = kit.unitRow(34, 30).set(null, UiTheme.BLIP, "Unidentified",
              "Sensor contact", "Sensor return · 7 hexes", Tone.NORMAL);
        preview.line.setColor(UiTheme.MUTED);
        preview.right.setActor(kit.ui.label("—", "hud-small", 11, UiTheme.MUTED));
        preview.getColor().a = .8f;
        contacts.add(preview).row();
        contacts.add(card).width(255);
        return contacts;
    }

    /** The overview's contact card of shot 12 ("Unidentified 1"). */
    private static Table contactCard(GpuHudKit kit) {
        UiKit ui = kit.ui;
        Table card = cardTop(ui, UiTheme.CORAL, 1);
        GpuHudKit.UnitSprite sprite = kit.sprite(36, 30).set(null, UiTheme.MUTED);
        // The mock's .ucard .h span rule outranks .q, so the card's "?" is 11 muted units.
        sprite.setActor(ui.label("?", "hud-heading", 11, UiTheme.MUTED));
        card.add(cardHead(ui, sprite, "Sensor contact", "Identity unknown · hex 1004")).growX()
              .pad(11, 13, 0, 13).row();
        card.add(ui.label("Sensor return only", "hud-small", 11, UiTheme.MUTED)).left().pad(9, 13, 10, 13);
        return card;
    }

    /**
     * Battle states the shots do not show: the Tactical View chip, every letter square, and inspected and faded unit
     * rows.
     */
    private static Table states(GpuHudKit kit) {
        UiKit ui = kit.ui;
        Table states = new Table();
        states.defaults().left().padBottom(8);
        Table chip = new Table();
        chip.setBackground(ui.skin.getDrawable("panel-rails"));
        chip.add(ui.icon("map", 16, UiTheme.MINT)).padRight(6);
        chip.add(ui.label(UiTheme.upper("Tactical view"), "hud-caption", 12, UiTheme.MINT)).padRight(10);
        chip.add(ui.button("hud-utility-small", null, "Back to 3D", null));
        states.add(chip).row();
        Table marks = new Table();
        marks.defaults().padRight(6);
        for (Letter kind : Letter.values()) {
            marks.add(kit.letter(kind == Letter.NEW ? "+" : "A", kind, false));
            marks.add(kit.letter(kind == Letter.NEW ? "+" : "B", kind, true)).padRight(12);
        }
        states.add(marks).row();
        GpuBattleStatus.UnitStatus timberWolf = STATUS.units().get(5);
        GpuHudKit.UnitRow inspected = kit.unitRow(34, 30).set(timberWolf.icon(), UiTheme.CORAL,
              timberWolf.model(), timberWolf.chassis(), "5/7 weapons · torso forward", Tone.OK);
        inspected.inspected(true);
        Table target = new Table();
        target.add(ui.label("4+", "hud-heading", 20, Color.WHITE)).right().row();
        target.add(ui.label("92%", "hud-small", 11, UiTheme.MINT)).right();
        inspected.right.setActor(target);
        states.add(inspected).width(300).row();
        GpuBattleStatus.UnitStatus locust = STATUS.units().get(4);
        GpuHudKit.UnitRow faded = kit.unitRow(40, 34).set(locust.icon(), GpuHudKit.FRIEND_SPRITE, locust.model(),
              locust.chassis(), "Destroyed", Tone.BAD);
        faded.right.setActor(ui.icon("close", 15, UiTheme.MUTED));
        faded.getColor().a = .4f;
        states.add(faded).width(300);
        return states;
    }

    // ---------------------------------------------------------------- helpers

    /** The width of shot 08's utility row (Tactical view, Map, Log, Help, Menu, Tuning), 8 units apart. */
    private static float utilityRowWidth(UiKit ui) {
        Table row = new Table();
        row.defaults().padRight(8);
        for (String[] utility : new String[][] { { "tactical", "Tactical view" }, { "map", "Map" },
              { "report", "Log" }, { "help", "Help" }, { "menu", "Menu" }, { "tune", "Tuning" } }) {
            row.add(ui.button("hud-utility", utility[0], utility[1], null));
        }
        return row.getPrefWidth() - 8;
    }

    private static Table panel(UiKit ui, String background, float vertical, float horizontal) {
        Table panel = new Table();
        panel.setBackground(ui.skin.getDrawable(background));
        panel.pad(vertical, horizontal, vertical, horizontal);
        panel.top().left();
        return panel;
    }

    /** Texels in a 5 x 5 window at the point (back-buffer pixels, y up) whose color changed by more than 30. */
    private static int changedTexels(Pixmap before, Pixmap after, Vector2 point) {
        int count = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = Math.round(point.x) + dx;
                int y = Math.round(point.y) + dy;
                int a = before.getPixel(x, y);
                int b = after.getPixel(x, y);
                for (int shift = 8; shift < 32; shift += 8) {
                    if (Math.abs((a >>> shift & 0xFF) - (b >>> shift & 0xFF)) > 30) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }
}
