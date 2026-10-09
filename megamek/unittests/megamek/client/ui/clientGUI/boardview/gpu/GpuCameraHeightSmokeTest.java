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
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Live editor elevation changes must refresh the rendered camera limit without fitting the board again. */
@Tag("on-demand")
class GpuCameraHeightSmokeTest {
    @Test
    void editingATallPillarAllowsPanningUpToItsSummit() throws Exception {
        Coords pillar = new Coords(3, 3);
        AtomicReference<BoardEditorSession> editor = new AtomicReference<>();
        FutureTask<GpuMapSource> setup = new FutureTask<>(() -> {
            var session = new BoardEditorSession();
            session.game().setBoard(Board.createEmptyBoard(7, 7));
            editor.set(session);
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
                        camera.setIsometric(false);
                        camera.tilt(70);
                        camera.zoom(1 / camera.camera.zoom);
                        camera.center(BoardGeometry.center(pillar, 0));
                        camera.pan(0, 100000);
                        float flatLimit = camera.focus.y;

                        SwingUtilities.invokeAndWait(() -> {
                            editor.get().adjustElevation(pillar, 100);
                            source.refresh();
                        });
                        GpuBoardTestUi.present(view);
                        assertEquals(100, source.takeFrame().scene().tile(pillar).elevation());
                        camera.pan(0, 100000);
                        assertTrue(camera.focus.y > flatLimit + 100 * BoardGeometry.level(),
                              () -> "The renderer must install the edited height: " + flatLimit + " -> " + camera.focus
                                    + ", tilt=" + camera.tilt());

                        camera.center(BoardGeometry.center(pillar, 0));
                        Vector3 peak = BoardGeometry.center(pillar, 100);
                        Vector3 screen = project(camera, peak);
                        camera.pan(camera.camera.viewportWidth / 2 - screen.x, screen.y - camera.camera.viewportHeight / 2);
                        GpuBoardTestUi.present(view);
                        screen = project(camera, peak);
                        assertEquals(camera.camera.viewportWidth / 2, screen.x, .1f);
                        assertEquals(camera.camera.viewportHeight / 2, screen.y, .1f,
                              "The summit must be reachable by panning, without another fit");
                        assertTrue(project(camera, BoardGeometry.center(pillar, 0)).y < 0,
                              "The board base may leave the screen while inspecting a tall summit");
                        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                        assertTrue(output.isDirectory() || output.mkdirs());
                        GpuBoardTestUi.capture(new File(output, "camera-pillar-height.png"));
                        assertVerticalRightDrag(camera, pillar);

                        SwingUtilities.invokeAndWait(() -> {
                            editor.get().adjustElevation(pillar, -100);
                            source.refresh();
                        });
                        GpuBoardTestUi.present(view);
                        camera.orbit(-camera.azimuth(), 0);
                        camera.center(BoardGeometry.center(pillar, 0));
                        camera.pan(0, 100000);
                        assertEquals(flatLimit, camera.focus.y, .05f, "Lowering the pillar restores the flat-board limit");
                        assertVerticalRightDrag(camera, pillar);
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
        if (failure.get() != null) { throw new AssertionError("Camera height boundary", failure.get()); }
    }

    private static void assertVerticalRightDrag(BoardCamera camera, Coords pillar) {
        var input = Gdx.input.getInputProcessor();
        int x = Gdx.graphics.getWidth() / 2;
        for (float bearing : new float[] { 30, 150 }) {
            camera.orbit(bearing - camera.azimuth(), 0);
            for (int direction : new int[] { -1, 1 }) {
                camera.center(BoardGeometry.center(pillar, 0));
                camera.pan(0, 0);
                Vector3 anchor = camera.focus.cpy();
                float screenX = project(camera, anchor).x;
                int y = Gdx.graphics.getHeight() / 2;
                assertTrue(input.touchDown(x, y, 0, Input.Buttons.RIGHT));
                try {
                    for (int step = 0; step < 200; step++) {
                        y += direction * 100;
                        assertTrue(input.touchDragged(x, y, 0));
                        assertEquals(screenX, project(camera, anchor).x, .1f,
                              "Holding right-click and moving vertically must not slide the board sideways");
                    }
                    Vector3 edge = camera.focus.cpy();
                    y += direction * 100;
                    input.touchDragged(x, y, 0);
                    assertTrue(edge.epsilonEquals(camera.focus, .01f), "Holding the drag at the limit stops movement");
                    y -= direction * 10;
                    input.touchDragged(x, y, 0);
                    assertTrue(camera.focus.dst(edge) > 1, "Reversing the held drag moves immediately");
                } finally {
                    input.touchUp(x, y, 0, Input.Buttons.RIGHT);
                }
            }
        }
    }

    private static Vector3 project(BoardCamera camera, Vector3 point) {
        return camera.camera.project(point.cpy(), 0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight);
    }
}
