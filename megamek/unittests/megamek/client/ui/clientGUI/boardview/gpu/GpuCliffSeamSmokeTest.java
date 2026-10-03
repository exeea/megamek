/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The shipped CommCenter's fortified cliffs beside river hex 1210. */
@Tag("on-demand")
class GpuCliffSeamSmokeTest {
    @Test
    void rendersJoinedCliffsAroundCommCenter1210() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/GrassLands/16x17 Grasslands River CommCenter.board"));
        BoardScene scene;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                try {
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    terrain.update(scene);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (int tilt : new int[] { 0, 18 }) {
                        camera.setIsometric(true);
                        camera.orbit(75, tilt);
                        camera.camera.zoom = .18f;
                        camera.center(BoardGeometry.center(new Coords(10, 10), 1));
                        terrain.renderShadows(camera.camera, List.of());
                        ScreenUtils.clear(.3f, .55f, .8f, 1, true);
                        terrain.render(camera.camera, false);
                        terrain.renderTransparent(camera.camera);
                        GpuBoardTestUi.capture(new File(output, "commcenter-cliffs-" + tilt + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("CommCenter cliff seams", failure.get()); }
    }
}
