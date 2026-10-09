/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.InputEvent;
import java.io.File;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The Sculpt tool through the real toolbar, tray, board clicks and Ctrl+wheel on a small grass board. */
@Tag("on-demand")
class GpuEditorSculptSmokeTest {
    @Test void clicksSculptSlopesButCtrlWheelChangesOnlyHoveredHex() throws Exception {
        Coords clicked = new Coords(3, 6), wheeled = new Coords(9, 6), lowered = new Coords(6, 3);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.game().setBoard(Board.createEmptyBoard(13, 13));
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File folder = new File("build/gpu-board-review");
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step, clicks, notches;
                long after;
                Vector3 summit;
                private GpuTerrain terrain() throws Exception {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                    return (GpuTerrain) field.get(this);
                }
                /** The level the renderer installed, from the published scene rather than the EDT-owned board. */
                private int level(Coords at) {
                    try {
                        var field = GpuBattleView.class.getDeclaredField("scene"); field.setAccessible(true);
                        return ((BoardScene) field.get(this)).tile(at).elevation();
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                }
                private Vector3 board(Coords at) {
                    Vector3 point = screenPosition(at);
                    Vector2 stagePoint = GpuBoardTestUi.stage().screenToStageCoordinates(new Vector2(point.x, point.y));
                    assertNull(GpuBoardTestUi.stage().hit(stagePoint.x, stagePoint.y, true), at + " is under a panel");
                    return point;
                }
                private void click(Coords at, int modifiers) {
                    Vector3 point = board(at);
                    GpuBoardTestUi.withModifiers(modifiers, () -> {
                        Gdx.input.getInputProcessor().touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                        Gdx.input.getInputProcessor().touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                    });
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Sculpt test stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < after) { return; }
                        var root = GpuBoardTestUi.stage().getRoot();
                        var state = source.editorState();
                        switch (step) {
                            case 0 -> GpuBoardTestUi.click("editor-tool-sculpt");
                            case 1 -> {
                                assertEquals(BoardEditorSession.Tool.SCULPT, state.tool());
                                assertTrue(GpuBoardTestUi.shown(root.findActor("editor-sculpt-slope")), "Sculpt options share the brush tray");
                                assertTrue(state.activeBrush().slope(), "Slope is on by default");
                                Vector3 point = board(clicked);
                                Gdx.input.getInputProcessor().mouseMoved(Math.round(point.x), Math.round(point.y));
                            }
                            case 2 -> {
                                Label hint = root.findActor("editor-sculpt-hint");
                                if (!hint.textEquals("Raise · 1 hex")) { return; }
                                assertTrue(folder.isDirectory() || folder.mkdirs());
                                GpuBoardTestUi.capture(new File(folder, "editor-sculpt-tray.png"));
                                click(clicked, 0); clicks = 1;
                            }
                            case 3 -> {
                                // One click per stroke: wait for each to install before aiming at the raised hex.
                                if (level(clicked) < clicks) { return; }
                                if (clicks < 3) { click(clicked, 0); clicks++; after = frames() + 8; return; }
                                assertEquals(3, level(clicked));
                            }
                            case 4 -> {
                                if (level(wheeled) < notches) { return; }
                                if (notches < 3) {
                                    // The pointer holds still on the summit while the ground rises under it (spec 3.3).
                                    if (summit == null) { summit = board(wheeled); }
                                    wheel(Math.round(summit.x), Math.round(summit.y), InputEvent.CTRL_DOWN_MASK, -1);
                                    notches++; after = frames() + 4; return;
                                }
                                Gdx.input.getInputProcessor().keyUp(Input.Keys.CONTROL_LEFT);
                            }
                            case 5 -> click(lowered, InputEvent.CTRL_DOWN_MASK);
                            case 6 -> {
                                if (level(lowered) == 0) { return; }
                                assertEquals(3, level(clicked));
                                clicked.allAdjacent().forEach(at -> assertEquals(2, level(at), "Slope at " + at));
                                clicked.allAtDistance(2).forEach(at -> assertEquals(1, level(at), "Foot at " + at));
                                assertEquals(3, level(wheeled));
                                wheeled.allAdjacent().forEach(at -> assertEquals(0, level(at), "Wheel neighbour at " + at));
                                wheeled.allAtDistance(2).forEach(at -> assertEquals(0, level(at), "Wheel neighbour at " + at));
                                assertEquals(-1, level(lowered), "Ctrl inverts Raise");
                                lowered.allAdjacent().forEach(at -> assertEquals(0, level(at)));
                                GpuBoardTestUi.capture(new File(folder, "editor-sculpt-hill.png"));
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 8;
                    } catch (Throwable error) {
                        GpuBoardTestUi.capture(new File(folder, "editor-sculpt-failure.png"));
                        failure.set(error); Gdx.app.exit();
                    }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor sculpt", failure.get()); }
    }

    private static void wheel(int x, int y, int modifiers, float delta) {
        GpuBoardTestUi.withModifiers(modifiers, () -> {
            when(Gdx.input.getX()).thenReturn(x); when(Gdx.input.getY()).thenReturn(y);
            Gdx.input.getInputProcessor().scrolled(0, delta);
        });
    }
}
