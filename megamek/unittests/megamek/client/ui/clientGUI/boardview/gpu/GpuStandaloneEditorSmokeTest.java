/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real UIKit controls, source capture, object geometry and both cameras without creating an old editor. */
@Tag("on-demand")
class GpuStandaloneEditorSmokeTest {
    @Test void rendersAndEditsIndependentObjectLayers() throws Exception {
        Coords at = new Coords(2, 2);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(6, 6);
            Hex hex = new Hex(0, "pavement:1;bridge:1:9;bridge_cf:40;bridge_elev:4", "");
            hex.setDecorations(List.of(
                  new BoardDecoration("car-under", "prop", "scenery/components/car-red", null, -.24, 0, 20, false, .8,
                        BoardDecoration.Placement.ground(), 0),
                  new BoardDecoration("car-air", "prop", "scenery/components/car-silver", null, .23, .12, 65, false, .8,
                        BoardDecoration.Placement.absolute(6), 0),
                  new BoardDecoration("tree", "prop", "birch-young", null, -.3, .3, 0, true, 1,
                        BoardDecoration.Placement.ground(), 0),
                  new BoardDecoration("deck-paint", "decal", "decal/saxarba/rubble_light_path", null, 0, 0, 0, false, 1,
                        BoardDecoration.Placement.surface("bridge", "deck", 0), 0)));
            board.setHex(at, hex); editor.game().setBoard(board); editor.pointer(at, 0, 0, false);
            editor.command(new BoardEditorSession.Command(BoardEditorSession.Action.COMPONENT, "bridge"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step;
                long nextFrame;
                private GpuTerrain terrain() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("terrain");
                    field.setAccessible(true);
                    return (GpuTerrain) field.get(this);
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, () -> "Native editor stalled at step " + step + ", frame " + frames()
                              + ", next " + nextFrame + ", message " + source.editorState().message());
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < nextFrame) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (step == 0) {
                            var ground = (megamek.client.ui.gdx.UiButton) GpuBoardTestUi.stage().getRoot()
                                  .findActor("editor-library-ground");
                            assertNotNull(ground);
                            assertTrue(ground.getLabel().getWidth() >= 80, "Library labels must fill the two-column cards");
                            assertWorkspaceSpacing();
                            var scene = source.takeFrame().scene();
                            float x = BoardGeometry.centerX(at) + .23f * BoardGeometry.width();
                            float y = BoardGeometry.centerY(at) + .12f * BoardGeometry.height();
                            var picked = terrain().decorationHit(scene, new Ray(new Vector3(x, y, 300), new Vector3(0, 0, -1)));
                            assertNotNull(picked); assertEquals("car-air", picked.id());
                            assertEquals(6 * BoardGeometry.level(), picked.anchorZ(), .01f);
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-isometric.png"));
                            boardCamera.setTactical(true, source.takeFrame().scene()); step++; nextFrame = frames() + 4;
                        } else if (step == 1) {
                            var scene = source.takeFrame().scene();
                            assertEquals(4, scene.tile(at).features().stream().filter(f -> f.decoration() != null).count());
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-tactical.png"));
                            var button = GpuBoardTestUi.stage().getRoot().findActor("editor-content-car-air");
                            assertNotNull(button, "The inspector must list independently selectable objects");
                            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_OBJECT, "car-air"),
                                  source.takeFrame().boardGeneration());
                            step++; nextFrame = frames() + 6;
                        } else if (step == 2) {
                            assertNotNull(GpuBoardTestUi.stage().getRoot().findActor("editor-Scale"));
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-object.png"));
                            var field = (com.badlogic.gdx.scenes.scene2d.ui.TextField) GpuBoardTestUi.stage().getRoot().findActor("editor-Scale");
                            GpuBoardTestUi.stage().setKeyboardFocus(field); field.setText("1.25"); GpuBoardTestUi.stage().setKeyboardFocus(null);
                            step++; nextFrame = frames() + 6;
                        } else if (step == 3) {
                            assertEquals(1.25, source.editorState().objects().stream().filter(d -> d.id().equals("car-air")).findFirst().orElseThrow().scale());
                            var root = GpuBoardTestUi.stage().getRoot();
                            var inspector = root.findActor("editor-inspector");
                            var settings = root.findActor("editor-settings-button");
                            var corner = settings.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
                            assertTrue(inspector.getTop() < corner.y, "Inspector must not cover camera/settings controls");
                            assertFalse(GpuBoardTestUi.texts(inspector).stream().anyMatch(t -> t.contains("Validate") || t.contains("New board")));
                            GpuBoardTestUi.click("tuning-button"); step++; nextFrame = frames() + 4;
                        } else if (step == 4) {
                            var stage = GpuBoardTestUi.stage(); var frame = stage.getRoot().findActor("tuning-frame");
                            var point = frame.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(frame.getWidth() / 2, frame.getHeight() / 2));
                            assertTrue(stage.hit(point.x, point.y, true).isDescendantOf(frame), "Tuning must receive input above inspector");
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-tuning.png"));
                            GpuBoardTestUi.click("editor-settings-button"); step++; nextFrame = frames() + 4;
                        } else if (step == 5) {
                            assertTrue(GpuBoardTestUi.stage().getRoot().findActor("editor-settings").isVisible());
                            assertFalse(source.editorThemes().isEmpty(), "Settings use the installed terrain themes");
                            assertNotNull(GpuBoardTestUi.stage().getRoot().findActor("editor-choice-Global theme"));
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-settings.png"));
                            Gdx.input.getInputProcessor().keyDown(com.badlogic.gdx.Input.Keys.ESCAPE);
                            Gdx.input.getInputProcessor().keyUp(com.badlogic.gdx.Input.Keys.ESCAPE);
                            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_OBJECT, "tree"), source.takeFrame().boardGeneration());
                            boardCamera.setTactical(false, source.takeFrame().scene()); boardCamera.setIsometric(true);
                            try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain().settings())) {
                                boardCamera.center(BoardGeometry.center(at, 2)); boardCamera.zoom(.25f);
                            }
                            step++; nextFrame = frames() + 8;
                        } else if (step == 6) {
                            var contents = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) GpuBoardTestUi.stage().getRoot().findActor("editor-inspector-scroll");
                            var selected = GpuBoardTestUi.stage().getRoot().findActor("editor-content-tree");
                            var point = selected.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(selected.getWidth() / 2, selected.getHeight() / 2));
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-tree.png"));
                            var hit = GpuBoardTestUi.stage().hit(point.x, point.y, true);
                            assertTrue(hit.isDescendantOf(selected),
                                  "The selected contents row must remain visible: point=" + point + ", hit=" + hit
                                        + ", scroll=" + contents.getScrollY() + ", max=" + contents.getMaxY());
                            GpuBoardTestUi.click("editor-section-reveal"); step++; nextFrame = frames() + 4;
                        } else if (step == 7) {
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-tree.png"));
                            dragSection(false);
                            step++; nextFrame = frames() + 8;
                        } else if (step == 8) {
                            var tree = source.editorState().objects().stream().filter(d -> d.id().equals("tree")).findFirst().orElseThrow();
                            assertTrue(tree.placement().offset() > 0, "Side-view dragging changes the surface offset");
                            assertEquals("ground", tree.placement().receiver().terrain());
                            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), source.takeFrame().boardGeneration());
                            step++; nextFrame = frames() + 8;
                        } else if (step == 9) {
                            assertEquals(0, source.editorState().objects().stream().filter(d -> d.id().equals("tree")).findFirst().orElseThrow().placement().offset());
                            assertNull(GpuBoardTestUi.stage().getRoot().findActor("editor-section-toggle")); step++; nextFrame = frames() + 4;
                        } else if (step == 10) {
                            assertTrue(GpuBoardTestUi.stage().getRoot().findActor("editor-section-panel").isVisible());
                            assertNull(GpuBoardTestUi.stage().getRoot().findActor("editor-section-toggle")); step++; nextFrame = frames() + 4;
                        } else if (step == 11) {
                            assertTrue(GpuBoardTestUi.stage().getRoot().findActor("editor-section-panel").isVisible());
                            dragSection(true); step++; nextFrame = frames() + 8;
                        } else if (step == 12) {
                            double offset = source.editorState().objects().stream().filter(d -> d.id().equals("tree")).findFirst().orElseThrow().placement().offset();
                            assertTrue(offset > 0 && offset < 5, "Losing touch focus retains the last previewed height");
                            // GLFW can render recursively while resizing; advance before changing the window.
                            step++; nextFrame = frames() + 8; Gdx.graphics.setWindowedMode(1280, 800);
                        } else if (step == 13) {
                            assertWorkspaceSpacing();
                            var root = GpuBoardTestUi.stage().getRoot();
                            var contents = (com.badlogic.gdx.scenes.scene2d.ui.Table) root.findActor("editor-contents");
                            assertEquals(contents.getPrefHeight(), contents.getHeight(), .5f, "Contents take their full natural height");
                            var inspectorScroll = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) root.findActor("editor-inspector-scroll");
                            assertTrue(contents.isDescendantOf(inspectorScroll.getActor()));
                            assertTrue(root.findActor("editor-Scale").isDescendantOf(inspectorScroll.getActor()),
                                  "Contents and properties share one scrolling body");
                            var documentStatus = (com.badlogic.gdx.scenes.scene2d.ui.Table) root.findActor("editor-document-status");
                            assertNull(documentStatus.getBackground(), "The document name has no shaded panel");
                            assertEquals(source.editorState().title() + " (3, 3)",
                                  ((com.badlogic.gdx.scenes.scene2d.ui.Label) root.findActor("editor-document-name")).getText().toString());
                            var section = root.findActor("editor-section-panel");
                            assertTrue(section.isDescendantOf(inspectorScroll.getActor()), "The section shares the inspector's scrolling body");
                            assertTrue(section.getWidth() < root.findActor("editor-inspector").getWidth());
                            double offset = source.editorState().objects().stream().filter(d -> d.id().equals("tree")).findFirst().orElseThrow().placement().offset();
                            assertTrue(GpuBoardTestUi.texts(root.findActor("editor-inspector")).contains("Resolved anchor · L"
                                  + megamek.client.ui.gdx.UiNumber.format(offset)), "Inspector heights must refresh when edited geometry finishes installing");
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-compact.png"));
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, configuration());
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Standalone editor failed", failure.get()); }
    }

    private static void assertWorkspaceSpacing() throws ReflectiveOperationException {
        var stage = GpuBoardTestUi.stage(); var root = stage.getRoot();
        var toolbar = root.findActor("editor-toolbar");
        var utilities = root.findActor("map-utilities");
        var library = root.findActor("editor-library");
        var inspector = root.findActor("editor-inspector");
        var corner = utilities.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
        float margin = toolbar.getX();
        // Scene2D rounds its table children to pixels; direct stage actors retain fractional viewport positions.
        assertEquals(margin, stage.getHeight() - toolbar.getTop(), .5f);
        assertEquals(margin, stage.getWidth() - corner.x - utilities.getWidth(), .5f);
        assertEquals(margin, stage.getHeight() - corner.y - utilities.getHeight(), .5f);
        assertEquals(margin, toolbar.getY() - library.getTop(), .5f);
        assertEquals(margin, corner.y - inspector.getTop(), .5f);
        assertEquals(library.getX(), stage.getWidth() - inspector.getRight(), .5f);
    }

    private static void dragSection(boolean cancel) throws ReflectiveOperationException {
        var stage = GpuBoardTestUi.stage();
        var section = (GpuHexSection) stage.getRoot().findActor("editor-hex-section");
        var scroll = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) stage.getRoot().findActor("editor-inspector-scroll");
        scroll.validate();
        var position = section.localToAscendantCoordinates(scroll.getActor(), new com.badlogic.gdx.math.Vector2());
        scroll.scrollTo(position.x, position.y, section.getWidth(), section.getHeight(), false, true);
        scroll.updateVisualScroll(); stage.draw();
        var height = GpuHexSection.class.getDeclaredMethod("height", float.class); height.setAccessible(true);
        float y = (float) height.invoke(section, 0f);
        var point = stage.stageToScreenCoordinates(section.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(section.getWidth() - 18, y)));
        var input = Gdx.input.getInputProcessor();
        input.touchDown((int) point.x, (int) point.y, 0, com.badlogic.gdx.Input.Buttons.LEFT);
        input.touchDragged((int) point.x, (int) point.y - 38, 0);
        if (cancel) { stage.cancelTouchFocus(); }
        else { input.touchUp((int) point.x, (int) point.y - 38, 0, com.badlogic.gdx.Input.Buttons.LEFT); }
    }

    private static com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration configuration() {
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1920, 1080); return config;
    }
}
