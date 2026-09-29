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
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The cliff behind the lava falling from Dome Vent 2's 1203 into 1104. */
@Tag("on-demand")
class GpuWaterfallSeamSmokeTest {
    @Test
    void rendersDomeVentFallsFromBothCameraProjections() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"));
        BoardScene scene;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                BoardGeometry.Tuning geometry = BoardGeometry.tuning();
                GpuTerrain terrain = new GpuTerrain();
                try {
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "waterfall-seams");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    BoardGeometry.tune(new BoardGeometry.Tuning(geometry.hexScale(), geometry.unitScale(),
                          geometry.unitHeightScale(), 18, geometry.gridShade(), geometry.multiHexUnitScale(), true));
                    for (boolean water : new boolean[] { false, true }) {
                        terrain.update(water ? BoardWaterfallTest.withWater(scene) : scene);
                        terrain.animate(.5f, List.of());
                        for (boolean perspective : new boolean[] { false, true }) {
                            for (int angle : new int[] { -15, 0, 15 }) {
                                BoardCamera camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                camera.setPerspective(perspective);
                                camera.setIsometric(true);
                                camera.orbit(angle, 25);
                                camera.camera.zoom = .1f;
                                Vector3 focus = BoardGeometry.corner(new Coords(11, 2), 5.5f, 4);
                                camera.center(focus);
                                terrain.renderShadows(camera.camera, List.of());
                                ScreenUtils.clear(1, 0, 1, 1, true);
                                terrain.render(camera.camera, false);
                                terrain.renderTransparent(camera.camera);
                                GpuBoardTestUi.capture(new File(output, "dome-vent-" + (water ? "water-" : "lava-") + angle
                                      + (perspective ? "-perspective" : "-ortho") + ".png"));
                                assertClosedCliff();
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    BoardGeometry.tune(geometry);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Dome Vent waterfall seams", failure.get()); }
    }

    /** This patch of the cliff is below the rim in every captured view; clear colour can only enter through a hole. */
    private static void assertClosedCliff() {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Pixmap pixels = ScreenUtils.getFrameBufferPixmap(width / 2 - 60, height / 2 - 80, 120, 160);
        try {
            int holes = 0;
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    if (pixels.getPixel(x, y) == 0xff00ffff) { holes++; }
                }
            }
            assertEquals(0, holes, "The cliff behind the waterfall must occlude the background");
        } finally { pixels.dispose(); }
    }
}
