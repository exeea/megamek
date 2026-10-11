/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.board.Board;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The user's retest of 2026-10-10 through the real editor input: Fit board between the Assets and Hex/Layers columns,
 * the isometric view turned to look south-east, the board pushed right against its pan limit with a right-button drag.
 * A drag up and to the right then moves the board straight up, a drag up and to the left moves it on both axes, and a
 * push to the right stays put.
 */
@Tag("on-demand")
class GpuEditorPanLimitSmokeTest {
    @Test
    void atTheLeftPanLimitADragUpAndRightStillMovesTheBoardUp() throws Exception {
        FutureTask<GpuMapSource> setup = new FutureTask<>(() -> {
            var session = new BoardEditorSession();
            session.game().setBoard(Board.createEmptyBoard(16, 17));
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var view = new GpuBattleView(source);
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        BoardCamera camera = view.boardCamera;
                        GpuBoardTestUi.click("map-view-fit");
                        camera.orbit(180, 0);
                        GpuBoardTestUi.present(view);
                        assertEquals(225, camera.azimuth(), .01f, "The isometric view turned to look south-east");
                        int x = Gdx.graphics.getWidth() / 2, y = Gdx.graphics.getHeight() / 2;
                        drag(400, 5, 0, x, y, camera);
                        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                        assertTrue(output.isDirectory() || output.mkdirs());
                        view.render();
                        GpuBoardTestUi.capture(new File(output, "editor-pan-left-limit.png"));
                        Vector3 edge = camera.focus.cpy();
                        drag(1, 5, 0, x, y, camera);
                        assertTrue(edge.epsilonEquals(camera.focus, .01f), "The board is at its left pan limit");

                        Vector3 moved = drag(60, 3, -3, x, y, camera);
                        assertEquals(0, moved.x, .5f, "Moving right stays refused");
                        assertEquals(180, moved.y, .5f, "The upward part of the drag still moves the board up");
                        view.render();
                        GpuBoardTestUi.capture(new File(output, "editor-pan-left-limit-up-right.png"));
                        edge = camera.focus.cpy();
                        drag(10, 5, 0, x, y, camera);
                        assertTrue(edge.epsilonEquals(camera.focus, .01f), "A push to the right at the limit stays put");
                        moved = drag(20, -3, -3, x, y, camera);
                        assertEquals(-60, moved.x, .5f, "Dragging up and to the left moves left");
                        assertEquals(60, moved.y, .5f, "Dragging up and to the left moves up");
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        view.dispose();
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(source::close);
        }
        if (failure.get() != null) { throw new AssertionError("Editor pan limit", failure.get()); }
    }

    /**
     * A right-button drag of {@code steps} mouse moves of (dx, dy) window pixels from (x, y), as the window delivers it;
     * returns how far the ground under the pivot moved on screen, y up.
     */
    private static Vector3 drag(int steps, int dx, int dy, int x, int y, BoardCamera camera) {
        InputProcessor input = Gdx.input.getInputProcessor();
        Vector3 anchor = camera.focus.cpy();
        Vector3 start = camera.camera.project(anchor.cpy(), 0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight);
        assertTrue(input.touchDown(x, y, 0, Input.Buttons.RIGHT), "The press lands on the board between the panels");
        try {
            for (int step = 0; step < steps; step++) {
                x += dx;
                y += dy;
                assertTrue(input.touchDragged(x, y, 0));
            }
        } finally {
            input.touchUp(x, y, 0, Input.Buttons.RIGHT);
        }
        return camera.camera.project(anchor, 0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight).sub(start);
    }
}
