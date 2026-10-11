/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static megamek.client.ui.gdx.UiTestStage.bounds;
import static megamek.client.ui.gdx.UiTestStage.place;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.gdx.UiTree.Node;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * UiTree through the stage's own input: chevrons, Expand all and Collapse all, a silent select that expands the
 * ancestors and scrolls the row into view, the keys, hover, actions, a new model keeping the state and the unchanged
 * rows, and the filter. The Client Settings sidebar (Main, Audio, ... Advanced, with its search field and Expand all
 * and Collapse all) is built as a tree to show that the tree can host it; its capture is b9-uitree-settings-demo.png.
 */
@Tag("on-demand")
class UiTreeSmokeTest {
    /** The Client Settings sidebar's pages, as CommonSettingsDialog builds them: ids, and the sections of each page. */
    private static Node sidebar(String id, Object... parts) {
        Node node = new Node(id, UiKit.text("CommonSettingsDialog.page." + id));
        List<Node> children = new ArrayList<>();
        List<String> keywords = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Node child) {
                children.add(child);
            } else {
                // A page's sections' titles and summaries, which the Swing sidebar's search matches too.
                String section = "CommonSettingsDialog.section." + part;
                keywords.add(UiKit.text(section + ".title"));
                keywords.add(UiKit.text(section + ".summary"));
            }
        }
        return node.keywords(keywords).children(children);
    }

    private static List<Node> settings() {
        return List.of(
              sidebar("main",
                    sidebar("main.application", "main.locale", "main.scale", "main.userFiles", "main.mml", "main.theme"),
                    sidebar("main.colours", "main.statusColours", "main.unitColours", "main.playerColours"),
                    sidebar("main.behavior", "main.interface", "main.units", "main.logging", "main.privacy")),
              sidebar("audio", "audio.volume", "audio.notifications"),
              sidebar("keyBinds", "keyBinds.commands"),
              sidebar("gameBoard",
                    sidebar("gameBoard.general", "gameBoard.tileset", "gameBoard.nags", "gameBoard.actions",
                          "gameBoard.controls", "gameBoard.pathfinder", "gameBoard.units"),
                    sidebar("gameBoard.appearance", "gameBoard.rendering", "gameBoard.indicators", "gameBoard.movement",
                          "gameBoard.fire"),
                    sidebar("gameBoard.fov", "gameBoard.fovInside", "gameBoard.fovOutside")),
              sidebar("unitDisplay",
                    sidebar("unitDisplay.tooltips", "unitDisplay.tooltip", "unitDisplay.armor"),
                    sidebar("unitDisplay.interface", "unitDisplay.heat", "unitDisplay.order", "unitDisplay.weapons",
                          "unitDisplay.sensors", "unitDisplay.fonts")),
              sidebar("miniMap", "miniMap.theme", "miniMap.display"),
              sidebar("report", "report.appearance", "report.content", "report.filter"),
              sidebar("overlays", "overlays.overview", "overlays.planetary", "overlays.toasts", "overlays.trace"),
              sidebar("buttonOrder", "buttonOrder.movement", "buttonOrder.deployment", "buttonOrder.firing",
                    "buttonOrder.physical", "buttonOrder.targeting"),
              sidebar("autoDisplay", "autoDisplay.unit", "autoDisplay.minimap", "autoDisplay.report",
                    "autoDisplay.players", "autoDisplay.force", "autoDisplay.bots", "autoDisplay.tabs"),
              sidebar("aiDisplay", "aiDisplay.settings"),
              sidebar("advanced", "advanced.chat", "advanced.timing", "advanced.safety"));
    }

    private static final List<String> TOP = List.of("main", "audio", "keyBinds", "gameBoard", "unitDisplay", "miniMap",
          "report", "overlays", "buttonOrder", "autoDisplay", "aiDisplay", "advanced");

    @Test
    void theClientSettingsSidebarFiltersToMatchesWithTheirParentsAndRestoresItsExpansion() {
        UiTestStage.run(ui -> {
            UiTree tree = new UiTree(ui.kit);
            tree.nodes(settings());
            Table panel = sidebarPanel(ui, tree);
            place(ui.window, panel, 40, 40);
            List<String> all = new ArrayList<>(List.of("main", "main.application", "main.colours", "main.behavior",
                  "audio", "keyBinds", "gameBoard", "gameBoard.general", "gameBoard.appearance", "gameBoard.fov",
                  "unitDisplay", "unitDisplay.tooltips", "unitDisplay.interface", "miniMap", "report", "overlays",
                  "buttonOrder", "autoDisplay", "aiDisplay", "advanced"));
            assertEquals(all, tree.shown(), "Every node starts expanded, in the sidebar's order");
            assertEquals("Field of View", ((UiButton) tree.findActor("gameBoard.fov")).getText().toString());

            // The tool row's buttons.
            click(ui.stage, panel.findActor("tree-collapse-all"));
            assertEquals(TOP, tree.shown(), "Collapse all leaves the top level");
            click(ui.stage, panel.findActor("tree-expand-all"));
            assertEquals(all, tree.shown());

            // The filter: the matches with their ancestors, expanded; clearing it restores the expansion from before.
            click(ui.stage, panel.findActor("main-chevron"));
            assertFalse(tree.expanded("main"));
            TextField search = panel.findActor("tree-search");
            search.setText("view");
            // "Field of View" by its label (and its sections' summaries), Overlays by its section "Unit Overview".
            // Nothing of "Unit Display" says "view" (the Swing dialog's own search does not list it either).
            assertEquals(List.of("gameBoard", "gameBoard.fov", "overlays"), tree.shown());
            search.setText("display");
            assertTrue(tree.shown().containsAll(List.of("unitDisplay", "unitDisplay.interface", "autoDisplay")),
                  "Unit Display under its parent: " + tree.shown());
            assertFalse(tree.shown().contains("main.application"), tree.shown().toString());

            // A second sidebar beside the first shows the "view" filter in the capture.
            UiTree filtered = new UiTree(ui.kit);
            filtered.nodes(settings());
            Table second = sidebarPanel(ui, filtered);
            place(ui.window, second, 330, 40);
            ((TextField) second.findActor("tree-search")).setText("view");
            filtered.select("gameBoard.fov");
            search.setText("");
            assertFalse(tree.expanded("main"), "Clearing the filter restores the collapsed Main");
            assertTrue(tree.expanded("gameBoard"));
            tree.select("unitDisplay.interface");
            ui.draw();
            Rectangle area = bounds(panel).merge(bounds(second));
            capture(ui, "b9-uitree-settings-demo", area);
        });
    }

    /** The tree's behaviour on a small model of three levels in a short scroll pane. */
    @Test
    void rowsExpandCollapseSelectAndFollowTheKeys() {
        UiTestStage.run(ui -> {
            Stage stage = ui.stage;
            UiTree tree = new UiTree(ui.kit);
            List<String> selections = new ArrayList<>(), hovers = new ArrayList<>(), actions = new ArrayList<>();
            tree.onSelect(selections::add);
            tree.onHover(hovers::add);
            tree.onAction((id, action) -> actions.add(id + ":" + action));
            tree.reorderable("Drag", (id, target, before) -> { });
            tree.empty("Nothing matches");
            tree.nodes(model("B"));
            ScrollPane scroll = ui.kit.scrollList(tree);
            Table panel = ui.kit.panel();
            panel.add(scroll).width(280).height(300);
            place(ui.window, panel, 100, 100);
            ui.draw();
            List<String> all = Stream.concat(Stream.of("a", "a1", "a1x", "a1y", "a2", "b", "b1", "b2", "c"),
                  IntStream.rangeClosed(1, 12).mapToObj(i -> "c" + i)).toList();
            assertEquals(all, tree.shown());
            assertNotNull(tree.findActor("b1-grip"), "Two rows of one stack drag by their grips");
            assertNull(tree.findActor("c1-grip"), "Rows without a stack have none");

            // A chevron collapses and expands; the rows below follow.
            click(stage, tree.findActor("a-chevron"));
            assertFalse(tree.expanded("a"));
            assertEquals(all.subList(5, all.size()), tree.shown().subList(1, tree.shown().size()));
            tree.collapseAll();
            assertEquals(List.of("a", "b", "c"), tree.shown());
            tree.expandAll();
            assertEquals(all, tree.shown());
            tree.collapseAll();

            // A silent select expands every ancestor and scrolls the row into view.
            tree.select("c12");
            ui.draw();
            assertTrue(selections.isEmpty(), "The view's own select is silent");
            assertTrue(tree.expanded("c") && tree.shown().contains("c12"));
            assertTrue(((UiButton) tree.findActor("c12")).isChecked());
            assertTrue(scroll.getScrollY() > 0, "The pane scrolled");
            assertHit(stage, tree.findActor("c12"));
            tree.select("a1y");
            ui.draw();
            assertTrue(tree.expanded("a") && tree.expanded("a1"), "Both ancestors expand");
            assertFalse(((UiButton) tree.findActor("c12")).isChecked(), "One selection");
            assertHit(stage, tree.findActor("a1y"));

            // The keys, while the tree holds the keyboard focus.
            stage.setKeyboardFocus(tree);
            key(stage, Input.Keys.UP);
            assertEquals("a1x", tree.cursor());
            assertTrue(((UiButton) tree.findActor("a1x")).isOver(), "The keyboard's row shows the hover look");
            key(stage, Input.Keys.LEFT);
            assertEquals("a1", tree.cursor(), "Left on a leaf goes to its parent");
            key(stage, Input.Keys.LEFT);
            assertFalse(tree.expanded("a1"), "Left on an expanded row collapses it");
            key(stage, Input.Keys.RIGHT);
            assertTrue(tree.expanded("a1"), "Right expands");
            key(stage, Input.Keys.RIGHT);
            assertEquals("a1x", tree.cursor(), "Right on an expanded row goes to its first child");
            key(stage, Input.Keys.DOWN);
            key(stage, Input.Keys.DOWN);
            assertEquals("a2", tree.cursor());
            assertTrue(selections.isEmpty(), "Moving the keyboard's row selects nothing");
            key(stage, Input.Keys.ENTER);
            assertEquals(List.of("a2"), selections, "Enter selects as a click does");
            assertEquals("a2", tree.selected());
            key(stage, Input.Keys.END);
            ui.draw();
            assertEquals("c12", tree.cursor());
            assertHit(stage, tree.findActor("c12"));
            key(stage, Input.Keys.HOME);
            assertEquals("a", tree.cursor());
            assertFalse(stage.keyDown(Input.Keys.Z), "Other keys stay with the view");
            ui.draw();

            // A click selects its row, takes the keys and is heard; an action is heard and selects nothing.
            stage.setKeyboardFocus(null);
            tree.expand("b", true);
            ui.draw();
            click(stage, tree.findActor("b"));
            assertEquals(List.of("a2", "b"), selections);
            assertSame(tree, stage.getKeyboardFocus());
            click(stage, tree.findActor("b1-remove"));
            assertEquals(List.of("b1:b1-remove"), actions);
            assertEquals("b", tree.selected(), "The action selects nothing");

            // Hover: entering a row and leaving the rows.
            move(stage, tree.findActor("b2"));
            assertEquals("b2", hovers.getLast());
            stage.mouseMoved(5, 5);
            stage.act(0);
            assertEquals("", hovers.getLast());

            // An equal model changes nothing; a new one keeps the state by id and every row whose node is unchanged.
            Actor b2 = tree.findActor("b2"), b1 = tree.findActor("b1");
            tree.expand("a1", false);
            tree.nodes(model("B"));
            assertSame(b2, tree.findActor("b2"));
            tree.nodes(model("Bee"));
            assertNotSame(b1, tree.findActor("b1"), "A changed node gets a new row");
            assertSame(b2, tree.findActor("b2"), "An unchanged row is kept");
            assertFalse(tree.expanded("a1"), "Expansion is kept by id");
            assertEquals("b", tree.selected(), "So is the selection");
            assertTrue(((UiButton) tree.findActor("b")).isChecked());

            // The filter, and its empty line.
            tree.filter("x");
            assertEquals(List.of("a", "a1", "a1x"), tree.shown(), "The match with its ancestors, expanded");
            tree.filter("zzz");
            assertTrue(tree.shown().isEmpty());
            assertNotNull(tree.findActor("tree-empty"));
            tree.filter("");
            assertFalse(tree.expanded("a1"), "Clearing the filter restores the expansion");
            ui.draw();
            ui.capture("ui-tree").dispose();
        });
    }

    /** a > a1 > (a1x, a1y), a2; b > b1, b2 (one stack, b1 with a remove action); c > c1 ... c12. */
    private static List<Node> model(String bee) {
        return List.of(
              new Node("a", "Alpha").icon("hex").children(List.of(
                    new Node("a1", "Alpha one").detail("First").children(List.of(new Node("a1x", "Alpha one x"),
                          new Node("a1y", "Alpha one y"))),
                    new Node("a2", "Alpha two"))),
              new Node("b", bee).icon("stack").children(List.of(
                    new Node("b1", bee + " one").icon("car").stack("deck")
                          .action(new UiTree.Action("b1-remove", "close", null, true)),
                    new Node("b2", "Bee two").icon("car").stack("deck"))),
              new Node("c", "Charlie").children(IntStream.rangeClosed(1, 12)
                    .mapToObj(i -> new Node("c" + i, "Charlie " + i)).toList()));
    }

    /** A sidebar panel: the tree's tool row over the tree in a scroll pane. */
    private static Table sidebarPanel(UiTestStage ui, UiTree tree) {
        Table panel = ui.kit.panel();
        panel.add(tree.controls(UiKit.text("CommonSettingsDialog.Search"))).growX().pad(10, 10, 6, 10).row();
        panel.add(ui.kit.scrollList(tree)).width(260).height(560).pad(0, 6, 8, 6);
        return panel;
    }

    private static void assertHit(Stage stage, Actor row) {
        Vector2 centre = row.localToStageCoordinates(new Vector2(row.getWidth() / 2, row.getHeight() / 2));
        Actor hit = stage.hit(centre.x, centre.y, true);
        assertTrue(hit != null && (hit == row || hit.isDescendantOf(row)), row.getName() + " is in view, hit " + hit);
    }

    private static void click(Stage stage, Actor actor) {
        Vector2 screen = stage.stageToScreenCoordinates(actor.localToStageCoordinates(
              new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        stage.touchDown(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
        stage.touchUp(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
    }

    private static void move(Stage stage, Actor actor) {
        Vector2 screen = stage.stageToScreenCoordinates(actor.localToStageCoordinates(
              new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        stage.mouseMoved(Math.round(screen.x), Math.round(screen.y));
        // The stage fires enter and exit when it acts.
        stage.act(0);
    }

    private static void key(Stage stage, int key) {
        assertTrue(stage.keyDown(key), "The tree takes " + Input.Keys.toString(key));
        stage.keyUp(key);
    }

    /**
     * Writes the area of the back buffer, with a margin of 8, as an opaque PNG (the captures' alpha dropped), after
     * checking that no label shows a missing message key.
     */
    private static void capture(UiTestStage ui, String name, Rectangle area) {
        UiTestStage.assertTexts(ui.stage.getRoot());
        int x = Math.round(area.x) - 8, y = Math.round(area.y) - 8;
        int width = Math.round(area.width) + 16, height = Math.round(area.height) + 16;
        Pixmap image = Pixmap.createFromFrameBuffer(x, y, width, height);
        try {
            image.setBlending(Pixmap.Blending.None);
            for (int py = 0; py < height; py++) {
                for (int px = 0; px < width; px++) {
                    image.drawPixel(px, py, image.getPixel(px, py) | 0xFF);
                }
            }
            assertTrue(UiTestStage.OUTPUT.isDirectory() || UiTestStage.OUTPUT.mkdirs());
            PixmapIO.writePNG(new FileHandle(new File(UiTestStage.OUTPUT, name + ".png")), image, -1, true);
        } finally {
            image.dispose();
        }
    }
}
