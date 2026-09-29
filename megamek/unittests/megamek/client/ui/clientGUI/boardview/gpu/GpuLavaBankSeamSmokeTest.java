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
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The narrow rock bank between Dome Vent 2's descending 1104/1105 lava and the land at 1204. */
@Tag("on-demand")
class GpuLavaBankSeamSmokeTest {
    @Test
    void descendingLavaBankOccludesTheBackground() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"));
        BoardScene scene;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var original = BoardGeometry.tuning();
                GpuTerrain terrain = new GpuTerrain();
                try {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    terrain.update(scene);
                    terrain.animate(.5f, List.of());
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "lava-bank-seams");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    for (boolean perspective : new boolean[] { false, true }) {
                        for (int angle : new int[] { -90, -60, -30 }) {
                            BoardCamera camera = new BoardCamera();
                            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                            camera.setPerspective(perspective);
                            camera.setIsometric(true);
                            camera.orbit(angle, -20);
                            camera.camera.zoom = .06f;
                            camera.center(new Vector3(693, -288, 63));
                            terrain.renderShadows(camera.camera, List.of());
                            ScreenUtils.clear(1, 0, 1, 1, true);
                            terrain.render(camera.camera, false);
                            terrain.renderTransparent(camera.camera);
                            GpuBoardTestUi.capture(new File(output, "bank-" + angle
                                  + (perspective ? "-perspective" : "-ortho") + ".png"));
                            int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                            Pixmap pixels = ScreenUtils.getFrameBufferPixmap(width / 2 - 160, height / 2 - 160, 320, 320);
                            try {
                                int holes = 0;
                                for (int y = 0; y < pixels.getHeight(); y++) {
                                    for (int x = 0; x < pixels.getWidth(); x++) {
                                        if (pixels.getPixel(x, y) == 0xff00ffff) { holes++; }
                                    }
                                }
                                // Float rasterization can leave an isolated sample on a shared triangle edge.
                                // The reported missing wall produces a connected, multi-pixel opening.
                                assertTrue(holes <= 2, "The lava bank must close against the cliff at angle " + angle
                                      + ": " + holes + " background pixels");
                                for (int y = 1; y + 1 < pixels.getHeight(); y++) {
                                    for (int x = 1; x + 1 < pixels.getWidth(); x++) {
                                        if (pixels.getPixel(x, y) != 0xff00ffff) { continue; }
                                        for (int dy = -1; dy <= 1; dy++) {
                                            for (int dx = -1; dx <= 1; dx++) {
                                                if (dx == 0 && dy == 0) { continue; }
                                                assertTrue(pixels.getPixel(x + dx, y + dy) != 0xff00ffff,
                                                      "No connected opening may remain in the bank");
                                            }
                                        }
                                    }
                                }
                            } finally { pixels.dispose(); }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Dome Vent lava bank seam", failure.get()); }
    }
}
