/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Assets palette in the real panel: the start page's nine category tiles fit without scrolling and show their
 * rendered icons; a tile opens its category, [<] goes back and the category's name jumps to another; the search covers
 * every category and the designs behind a gallery card; Esc clears the search, then leaves a gallery, then a category;
 * a Theme card arms Clear ground with its theme and stays highlighted; the Water card's Brush offers Depth and Current;
 * a picker family's Type changes the brush's asset; the DropShip blueprint paints its seven hexes and erases as its
 * component, over a radius; and a new editor window opens on the page the last one showed. Captures under b12-palette at 1366x768 and 1280x800.
 */
@Tag("on-demand")
class GpuEditorPaletteSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "b12-palette");
    private static final Coords DROPSHIP = new Coords(5, 5);

    /** One step of a run: false while it waits for something (previews, the board) and runs again next frame. */
    private interface Step { boolean run(GpuBattleView view, GpuMapSource source, Group root) throws Exception; }

    @Test void paletteNavigatesSearchesAndArmsItsCards() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        run(1366, 768, List.of(
              (view, source, root) -> {
                  // An editor opened before in this JVM left its page: back to the start page first.
                  if (root.findActor("editor-back") != null) { GpuBoardTestUi.click("editor-back"); return false; }
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-category-scroll"))) { return false; }
                  for (var category : BoardEditorBlueprint.get().categories()) {
                      assertTrue(GpuBoardTestUi.shown(root.findActor("editor-category-" + category.id())), category.id());
                  }
                  assertEquals(0, ((ScrollPane) root.findActor("editor-category-scroll")).getMaxY(), .5f, "Nine tiles without scrolling");
                  assertNull(root.findActor("editor-back"), "The start page has no back button");
                  GpuBoardTestUi.capture(new File(OUTPUT, "start-1366x768.png"));
                  GpuBoardTestUi.click("editor-category-ground-water");
                  return true;
              },
              (view, source, root) -> { assertNotNull(root.findActor("editor-category")); return true; }));
        run(1280, 800, List.of(
              // The new window reopens on Ground & water: Basic, Gameplay and Water.
              (view, source, root) -> {
                  for (String section : List.of("basic", "gameplay", "water")) {
                      assertNotNull(root.findActor("editor-section-ground-water/" + section), section);
                  }
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "ground-water.png"));
                  GpuBoardTestUi.click("editor-back");
                  return true;
              },
              (view, source, root) -> {
                  assertTrue(GpuBoardTestUi.shown(root.findActor("editor-category-vegetation")), "Back on the start page");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-category-scroll"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "start-1280x800.png"));
                  GpuBoardTestUi.click("editor-category-vegetation");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-section-vegetation/broadleaf"));
                  GpuBoardTestUi.click("editor-category");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.click("editor-category-item-buildings"); return true; },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-section-buildings/looks"), "The list jumps to Buildings");
                  search(root, "tree");
                  return true;
              },
              (view, source, root) -> {
                  for (String category : List.of("ground-water", "vegetation", "transportation")) {
                      assertNotNull(root.findActor("editor-section-" + category), "tree in " + category);
                  }
                  assertNull(root.findActor("editor-section-buildings"));
                  assertNotNull(root.findActor("editor-library-rough/felled-trees"));
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "search-tree.png"));
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertEquals("", ((TextField) root.findActor("editor-search")).getText(), "Esc clears the search first");
                  assertNotNull(root.findActor("editor-section-buildings/looks"));
                  GpuBoardTestUi.click("editor-gallery-Modular buildings");
                  return true;
              },
              (view, source, root) -> {
                  assertNull(root.findActor("editor-category"), "A gallery's header names it");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "gallery-modular.png"));
                  search(root, modular().label());
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-library-" + modular().key()), "The search reaches inside the gallery");
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-back"));
                  assertNull(root.findActor("editor-category"), "Esc: from the search back to the gallery");
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-category"), "Esc: from the gallery back to its category");
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertNull(root.findActor("editor-back"), "Esc: from the category back to the start page");
                  escape();
                  GpuBoardTestUi.category("theme");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-section-theme/special"));
                  assertNotNull(root.findActor("editor-library-theme/rock"));
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "theme.png"));
                  GpuBoardTestUi.click("editor-library-theme/volcano");
                  return true;
              },
              (view, source, root) -> {
                  var brush = source.editorState().activeBrush();
                  assertEquals(List.of("ground/clear", true, "volcano"), List.of(brush.key(), brush.themeEnabled(), brush.theme()));
                  assertTrue(((UiButton) root.findActor("editor-library-theme/volcano")).isChecked(), "The Theme card stays highlighted");
                  assertTrue(root.findActor("editor-category-mark-theme").isVisible(), "The Theme tile marks the brush's category");
                  GpuBoardTestUi.category("ground-water");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.reveal("editor-section-ground-water/water"); return true; },
              (view, source, root) -> { GpuBoardTestUi.click("editor-library-water/shallow"); return true; },
              (view, source, root) -> {
                  Group brush = root.findActor("editor-brush");
                  assertNotNull(brush.findActor("editor-brush-terrain:water"), "Depth");
                  assertNotNull(brush.findActor("editor-brush-terrain:rapids"), "Current");
                  assertTrue(root.findActor("editor-category-mark-ground-water").isVisible());
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "water-brush.png"));
                  search(root, "landing beacon");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.click("editor-library-scenery/roofs/landing-beacon-hex"); return true; },
              (view, source, root) -> {
                  assertEquals("scenery/roofs/landing-beacon-hex", source.editorState().activeBrush().key());
                  GpuBoardTestUi.click("editor-choice-Type");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.clickText("Round landing beacon"); return true; },
              (view, source, root) -> {
                  assertEquals("scenery/roofs/landing-beacon-round", source.editorState().activeBrush().key(), "Type re-arms the member");
                  assertTrue(((UiButton) root.findActor("editor-library-scenery/roofs/landing-beacon-hex")).isChecked(),
                        "The family's card stays highlighted");
                  search(root, "dropship");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.click("editor-library-building/dropship"); return true; },
              (view, source, root) -> {
                  assertEquals("building/dropship", source.editorState().activeBrush().key());
                  source.editorPointer(DROPSHIP, 0, 0, false, source.takeFrame().boardGeneration()); source.endEditorStroke();
                  return true;
              },
              (view, source, root) -> {
                  assertEquals(72, source.editorState().property("building").exits(), "The DropShip's centre piece");
                  view.boardCamera.center(BoardGeometry.center(DROPSHIP, 0));
                  view.boardCamera.zoom(.35f / view.boardCamera.camera.zoom);
                  return true;
              },
              (view, source, root) -> {
                  if (busy(view)) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "dropship.png"));
                  source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.TOOL, "", "ERASE"),
                        source.takeFrame().boardGeneration());
                  return true;
              },
              (view, source, root) -> {
                  // The DropShip's Erase is its component's: a radius, and the Erase hint instead of the blueprint's.
                  Group brush = root.findActor("editor-brush");
                  assertNotNull(brush.findActor("editor-brush-"), "Radius");
                  assertNotEquals(UiKit.text("BoardEditor.hint.hexBlueprint"), ((Label) brush.findActor("editor-brush-hint")).getText().toString());
                  return true;
              }));
    }

    /** The gallery's sixth design. */
    private static BoardEditorBlueprint.Entry modular() {
        return BoardEditorBlueprint.get().categories().stream().flatMap(c -> c.sections().stream()).flatMap(s -> s.cards().stream())
              .filter(card -> card.label().equals("Modular buildings")).findFirst().orElseThrow().members().get(5);
    }

    private static boolean busy(GpuBattleView view) throws ReflectiveOperationException {
        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
        return ((GpuTerrain) field.get(view)).busy();
    }

    private static void search(Group root, String text) { ((TextField) root.findActor("editor-search")).setText(text); }

    private static void escape() {
        Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
        Gdx.input.getInputProcessor().keyUp(Input.Keys.ESCAPE);
    }

    /** Opens an editor window of {@code width} x {@code height} on a pavement board and runs {@code steps} in order. */
    private static void run(int width, int height, List<Step> steps) throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(12, 12);
            for (int x = 0; x < 12; x++) {
                for (int y = 0; y < 12; y++) { board.setHex(new Coords(x, y), new Hex(0, "pavement:1", "")); }
            }
            editor.game().setBoard(board);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(width, height);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 300_000_000_000L;
                int step;
                long after;
                AtomicBoolean applied = new AtomicBoolean(true);

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "The palette stalled at step " + step + " of " + width + "x" + height);
                        if (!applied.get()) { after = frames() + 8; return; }
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        if (step == steps.size()) { Gdx.app.exit(); return; }
                        if (steps.get(step).run(this, source, GpuBoardTestUi.stage().getRoot())) {
                            // The next step waits for the EDT to apply this one's commands, then for the frames that show them.
                            step++; after = frames() + 8; applied = new AtomicBoolean();
                            var marker = applied; SwingUtilities.invokeLater(() -> marker.set(true));
                        }
                    } catch (Throwable error) {
                        GpuBoardTestUi.capture(new File(OUTPUT, "failure-" + width + "x" + height + ".png"));
                        failure.set(error); Gdx.app.exit();
                    }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("The palette failed at " + width + "x" + height, failure.get()); }
    }
}
