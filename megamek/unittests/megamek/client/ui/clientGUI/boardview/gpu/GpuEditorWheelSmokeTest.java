/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.InputEvent;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercise wheel dispatch after real inspector/theme edits, without a recovery click on the board. */
@Tag("on-demand")
class GpuEditorWheelSmokeTest {
    @Test void editsDoNotStealZoomOrElevationWheel() throws Exception {
        Coords at = new Coords(4, 4);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.game().setBoard(Board.createEmptyBoard(9, 9));
            editor.pointer(at, 0, 0, false); editor.finishStroke();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 90_000_000_000L;
                int step;
                long after;
                private GpuTerrain terrain() throws Exception {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                    return (GpuTerrain) field.get(this);
                }
                private void boardWheel(int modifiers, float delta) {
                    var point = screenPosition(at);
                    Vector2 stagePoint = GpuBoardTestUi.stage().screenToStageCoordinates(new Vector2(point.x, point.y));
                    assertNull(GpuBoardTestUi.stage().hit(stagePoint.x, stagePoint.y, true));
                    wheel(Math.round(point.x), Math.round(point.y), modifiers, delta);
                }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Wheel test stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < after) { return; }
                        var stage = GpuBoardTestUi.stage(); var root = stage.getRoot();
                        switch (step) {
                            case 0 -> {
                                GpuBoardTestUi.click("map-view-top");
                                assertEquals(0, boardCamera.tilt());
                                GpuBoardTestUi.click("editor-choice-Hex theme");
                                assertTrue(root.findActor("editor-theme-gallery").isVisible());
                            }
                            case 1 -> {
                                GpuBoardTestUi.click("editor-theme-lunar");
                                assertFalse(root.findActor("editor-theme-gallery").isVisible(), "Theme click must close the gallery");
                                SwingUtilities.invokeAndWait(source::refresh);
                                source.takeFrame();
                                assertEquals("lunar", source.editorState().theme(), source.editorState().message());
                            }
                            case 2 -> {
                                assertEquals("lunar", source.editorState().theme());
                                // Rebuilding the hovered contents used to leave this pane holding wheel focus.
                                stage.setScrollFocus(root.findActor("editor-inspector-scroll"));
                                float zoom = boardCamera.camera.zoom;
                                boardWheel(0, -1);
                                assertTrue(boardCamera.camera.zoom < zoom, "Zoom must work immediately after a theme edit");
                                assertNull(stage.getScrollFocus());
                                var field = (TextField) root.findActor("editor-Ground level");
                                stage.setKeyboardFocus(field); field.setText("2");
                                boardWheel(InputEvent.CTRL_DOWN_MASK, -1);
                                assertNull(stage.getKeyboardFocus(), "Wheel over the board commits a pending field edit");
                            }
                            case 3 -> {
                                assertEquals(3, source.editorState().elevation(), "Field blur then wheel apply in order");
                                stage.setScrollFocus(root.findActor("editor-inspector-scroll"));
                                boardWheel(InputEvent.CTRL_DOWN_MASK, -1);
                            }
                            case 4 -> {
                                assertEquals(4, source.editorState().elevation(), "A second tick needs no selection click");
                                boardWheel(InputEvent.CTRL_DOWN_MASK, 1);
                                Gdx.input.getInputProcessor().keyUp(Input.Keys.CONTROL_LEFT);
                            }
                            case 5 -> {
                                assertEquals(3, source.editorState().elevation());
                                float zoom = boardCamera.camera.zoom;
                                boardWheel(0, 1);
                                assertTrue(boardCamera.camera.zoom > zoom, "Releasing Ctrl restores zoom");
                                var pane = (ScrollPane) root.findActor("editor-inspector-scroll");
                                pane.setScrollY(0); pane.updateVisualScroll();
                                var point = pane.localToStageCoordinates(new Vector2(pane.getWidth() / 2, pane.getHeight() / 2));
                                stage.stageToScreenCoordinates(point);
                                zoom = boardCamera.camera.zoom;
                                wheel(Math.round(point.x), Math.round(point.y), 0, 5);
                                assertTrue(pane.getScrollY() > 0, "The inspector still scrolls under the pointer");
                                assertEquals(zoom, boardCamera.camera.zoom, "Scrolling the inspector must not zoom the board");
                                GpuBoardTestUi.click("map-view-isometric"); assertTrue(boardCamera.tilt() > 0);
                                for (String name : java.util.List.of("top", "isometric", "fit")) {
                                    var button = root.findActor("map-view-" + name);
                                    assertEquals(button.getHeight(), button.getWidth(), .1f);
                                }
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 8;
                    } catch (Throwable error) {
                        GpuBoardTestUi.capture(new java.io.File("build/gpu-board-review/editor-wheel-failure.png"));
                        failure.set(error); Gdx.app.exit();
                    }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor wheel dispatch", failure.get()); }
    }

    private static void wheel(int x, int y, int modifiers, float delta) {
        GpuBoardTestUi.withModifiers(modifiers, () -> {
            when(Gdx.input.getX()).thenReturn(x); when(Gdx.input.getY()).thenReturn(y);
            Gdx.input.getInputProcessor().scrolled(0, delta);
        });
    }
}
