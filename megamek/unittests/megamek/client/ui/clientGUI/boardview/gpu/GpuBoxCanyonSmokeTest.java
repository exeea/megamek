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

/** Deeply notched plateau tops on the shipped Savannah Box Canyon. */
@Tag("on-demand")
class GpuBoxCanyonSmokeTest {
    @Test
    void rendersConcavePlateausWithoutShadingSpokes() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"));
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
                    for (boolean iso : new boolean[] { false, true }) {
                        camera.setIsometric(iso);
                        camera.camera.zoom = .24f;
                        camera.center(BoardGeometry.center(new Coords(12, 13), 8));
                        terrain.renderShadows(camera.camera, List.of());
                        for (boolean clay : new boolean[] { false, true }) {
                            terrain.setClay(clay);
                            ScreenUtils.clear(.3f, .55f, .8f, 1, true);
                            terrain.render(camera.camera, false);
                            terrain.renderTransparent(camera.camera);
                            GpuBoardTestUi.capture(new File(output, "box-canyon-" + (iso ? "iso" : "top")
                                  + (clay ? "-clay" : "") + ".png"));
                        }
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
        if (failure.get() != null) { throw new AssertionError("Box Canyon plateau shading", failure.get()); }
    }
}
