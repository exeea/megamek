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

/**
 * Real UIKit controls, source capture, object geometry and both cameras without creating an old editor; the workspace
 * layout (Assets, Layers over Edit, the Brush panel and the collapsible side view) at 1920x1080 and 1280x800.
 */
@Tag("on-demand")
class GpuStandaloneEditorSmokeTest {
    @Test void rendersAndEditsIndependentObjectLayers() throws Exception {
        Coords at = new Coords(2, 2);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(6, 6);
            Hex hex = new Hex(0, "pavement:1;bridge:1:9;bridge_cf:40;bridge_elev:4", "");
            hex.setDecorations(List.of(
                  new BoardDecoration("car-under", "prop", "scenery/vehicles/car", null, -.24, 0, 20, false, .8,
                        BoardDecoration.Placement.ground(), 0),
                  new BoardDecoration("car-air", "prop", "scenery/vehicles/car", null, .23, .12, 65, false, .8,
                        BoardDecoration.Placement.absolute(6), 0),
                  new BoardDecoration("tree", "prop", "birch-young", null, -.3, .3, 0, true, 1,
                        BoardDecoration.Placement.ground(), 0),
                  new BoardDecoration("deck-paint", "decal", "decal/damage/rubble-light-path", null, 0, 0, 0, false, 1,
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
                            var category = (megamek.client.ui.gdx.UiButton) GpuBoardTestUi.stage().getRoot().findActor("editor-category");
                            assertEquals("Terrain · Ground", category.getText().toString(), "One compact category drop-down");
                            assertWorkspaceSpacing();
                            assertCorners(source.editorState().tool());
                            assertTrue(GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("editor-section-panel")),
                                  "A wide board area starts with the side view open");
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
                            for (String axis : List.of("W", "L", "H")) {
                                assertNotNull(GpuBoardTestUi.stage().getRoot().findActor("editor-" + axis), "Stretch " + axis);
                            }
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-object.png"));
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-layout-1920.png"));
                            var stretch = (com.badlogic.gdx.scenes.scene2d.ui.TextField) GpuBoardTestUi.stage().getRoot().findActor("editor-H");
                            GpuBoardTestUi.stage().setKeyboardFocus(stretch); stretch.setText("2"); GpuBoardTestUi.stage().setKeyboardFocus(null);
                            var field = (com.badlogic.gdx.scenes.scene2d.ui.TextField) GpuBoardTestUi.stage().getRoot().findActor("editor-Scale");
                            GpuBoardTestUi.stage().setKeyboardFocus(field); field.setText("1.25"); GpuBoardTestUi.stage().setKeyboardFocus(null);
                            step++; nextFrame = frames() + 6;
                        } else if (step == 3) {
                            var air = source.editorState().objects().stream().filter(d -> d.id().equals("car-air")).findFirst().orElseThrow();
                            assertEquals(1.25, air.scale());
                            assertEquals(new BoardDecoration.Stretch(1, 1, 2), air.stretch(), "Height stretches through the session");
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
                            var contents = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) GpuBoardTestUi.stage().getRoot().findActor("editor-layers-scroll");
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
                            GpuBoardTestUi.click("editor-side-view-toggle"); step++; nextFrame = frames() + 4;
                        } else if (step == 10) {
                            var root = GpuBoardTestUi.stage().getRoot();
                            assertNull(root.findActor("editor-section-panel"), "Collapsing removes the section");
                            assertTrue(root.findActor("editor-side-view").getHeight() < 44, "A collapsed side view is one line");
                            assertTrue(((com.badlogic.gdx.scenes.scene2d.ui.Label) root.findActor("editor-side-view-summary")).getText()
                                  .toString().startsWith(at.getBoardNum() + " · Ground L0"));
                            assertCorners(source.editorState().tool());
                            GpuBoardTestUi.click("editor-side-view-toggle"); step++; nextFrame = frames() + 4;
                        } else if (step == 11) {
                            assertTrue(GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("editor-section-panel")));
                            dragSection(true); step++; nextFrame = frames() + 8;
                        } else if (step == 12) {
                            double offset = source.editorState().objects().stream().filter(d -> d.id().equals("tree")).findFirst().orElseThrow().placement().offset();
                            assertTrue(offset > 0 && offset < 5, "Losing touch focus retains the last previewed height");
                            // Paint shows the Brush panel beside the side view. GLFW can render recursively while resizing;
                            // advance before changing the window.
                            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.TOOL, "", "PAINT"),
                                  source.takeFrame().boardGeneration());
                            step++; nextFrame = frames() + 8; Gdx.graphics.setWindowedMode(1280, 800);
                        } else if (step == 13) {
                            assertWorkspaceSpacing();
                            assertCorners(BoardEditorSession.Tool.PAINT);
                            var root = GpuBoardTestUi.stage().getRoot();
                            var contents = (com.badlogic.gdx.scenes.scene2d.ui.Table) root.findActor("editor-contents");
                            var layers = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) root.findActor("editor-layers-scroll");
                            var inspectorScroll = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) root.findActor("editor-inspector-scroll");
                            assertTrue(contents.isDescendantOf(layers.getActor()), "Layers scroll on their own");
                            assertTrue(root.findActor("editor-Scale").isDescendantOf(inspectorScroll.getActor()), "Edit scrolls on its own");
                            assertTrue(layers.getY() > inspectorScroll.getTop(), "Layers above Edit, the divider between them");
                            assertTrue(layers.getHeight() >= 59 && inspectorScroll.getHeight() >= 59);
                            var documentStatus = (com.badlogic.gdx.scenes.scene2d.ui.Table) root.findActor("editor-document-status");
                            assertNull(documentStatus.getBackground(), "The document name has no shaded panel");
                            // The hex by its board number, as everywhere else in the editor.
                            assertEquals(source.editorState().title() + " (0303)",
                                  ((com.badlogic.gdx.scenes.scene2d.ui.Label) root.findActor("editor-document-name")).getText().toString());
                            var section = root.findActor("editor-section-panel");
                            assertTrue(section.isDescendantOf(root.findActor("editor-side-view")), "The section is in the side view");
                            double offset = source.editorState().objects().stream().filter(d -> d.id().equals("tree")).findFirst().orElseThrow().placement().offset();
                            assertTrue(GpuBoardTestUi.texts(root.findActor("editor-inspector")).contains("Resolved anchor · L"
                                  + megamek.client.ui.gdx.UiNumber.format(offset)), "Inspector heights must refresh when edited geometry finishes installing");
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-compact.png"));
                            GpuBoardTestUi.capture(new File(output, "standalone-editor-layout-1280.png"));
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

    /**
     * The corner panels in the board area: the Brush panel at the bottom left only for a tool with options, the side
     * view at the bottom right, both clear of the side columns and of each other.
     */
    private static void assertCorners(BoardEditorSession.Tool tool) {
        var root = GpuBoardTestUi.stage().getRoot();
        var library = root.findActor("editor-library");
        var inspector = root.findActor("editor-inspector");
        var brush = root.findActor("editor-brush");
        var side = root.findActor("editor-side-view");
        float margin = library.getY();
        assertEquals(margin, inspector.getY(), .5f, "Both columns reach the bottom margin");
        assertEquals(margin, side.getY(), .5f);
        assertEquals(inspector.getX() - margin, side.getRight(), .5f, "The side view sits beside the right column");
        assertEquals(tool != BoardEditorSession.Tool.SELECT, brush.isVisible(), "The Brush panel follows the tool");
        if (brush.isVisible()) {
            assertEquals(library.getRight() + margin, brush.getX(), .5f, "The Brush panel sits beside Assets");
            assertEquals(margin, brush.getY(), .5f);
            assertTrue(brush.getRight() + margin <= side.getX() + .5f, "The corner panels do not overlap");
            GpuBoardTestUi.assertHorizontalBounds((com.badlogic.gdx.scenes.scene2d.Group) brush, brush);
        }
    }

    /** Drags the tree's pill in the side view upwards. */
    private static void dragSection(boolean cancel) {
        var stage = GpuBoardTestUi.stage();
        var section = (GpuHexSection) stage.getRoot().findActor("editor-hex-section");
        stage.draw();
        var pill = section.pill("tree");
        assertNotNull(pill, "The side view shows the tree's pill");
        var point = stage.stageToScreenCoordinates(section.localToStageCoordinates(pill.cpy()));
        // Two levels up: whole levels snap, and Reveal's close fit makes a level tall.
        int up = Math.round(stage.stageToScreenCoordinates(section.localToStageCoordinates(pill.cpy().add(0, section.y(2) - section.y(0)))).y);
        var input = Gdx.input.getInputProcessor();
        input.touchDown((int) point.x, (int) point.y, 0, com.badlogic.gdx.Input.Buttons.LEFT);
        input.touchDragged((int) point.x, up, 0);
        if (cancel) { stage.cancelTouchFocus(); }
        else { input.touchUp((int) point.x, up, 0, com.badlogic.gdx.Input.Buttons.LEFT); }
    }

    private static com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration configuration() {
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1920, 1080); return config;
    }
}
