/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Camera sorting used to leave an active magma sampler on an array texture, rejecting entire terrain draws. */
@Tag("on-demand")
class GpuDomeVentRotationSmokeTest {
    @Test
    void rotatingDomeVentKeepsItsInteriorOpaque() throws Exception {
        var board = new Board();
        board.load(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 1.board"));
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "dome-orbit");
        Files.createDirectories(output.toPath());
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            var failure = new AtomicReference<Throwable>();
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(960, 960);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var original = BoardGeometry.tuning();
                    var view = new GpuBattleView(fixture.source);
                    try {
                        BoardGeometry.tune(BoardGeometry.DEFAULTS);
                        // Exercise the real passes, shaders and sorting: terrain-only stills missed the invalid draws.
                        view.create();
                        GpuCamouflageReview.renderReady(view);
                        GpuBoardTestUi.present(view);
                        var atmosphere = GpuCamouflageReview.field(view, "atmosphere");
                        var scene = (BoardScene) GpuCamouflageReview.field(view, "scene");
                        var camera = view.boardCamera;
                        camera.setIsometric(true);
                        camera.camera.zoom = .35f;
                        camera.center(BoardGeometry.center(new Coords(13, 9), 3));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Board preparation");
                        for (int angle = 0; angle < 360; angle += 2) {
                            if (angle > 0) { camera.orbit(2, 0); }
                            view.render();
                            int error = Gdx.gl.glGetError();
                            var target = (FrameBuffer) GpuCamouflageReview.field(atmosphere, "sceneColor");
                            int openings = openings(target, camera, scene);
                            if (error != GL20.GL_NO_ERROR || openings > 0 || angle == 174 || angle == 354) {
                                GpuReviewFrame.save(new File(output, "dome-vent-" + angle + ".png"));
                            }
                            assertEquals(GL20.GL_NO_ERROR, error, "Terrain draw at angle " + angle);
                            assertTrue(openings == 0, "Terrain exposes " + openings + " sky blocks at angle " + angle);
                        }
                        System.out.println("Dome Vent: 180 orbit frames, no GL errors or interior sky blocks");
                    } catch (Throwable error) { failure.set(error); }
                    finally {
                        view.dispose();
                        BoardGeometry.tune(original);
                        Gdx.app.exit();
                    }
                }
            }, config);
            if (failure.get() != null) { throw new AssertionError("Dome Vent rotation", failure.get()); }
        }
    }

    private static int openings(FrameBuffer target, BoardCamera camera, BoardScene scene) {
        target.begin();
        var pixels = ScreenUtils.getFrameBufferPixmap(0, 0, target.getWidth(), target.getHeight());
        try {
            int width = pixels.getWidth(), height = pixels.getHeight(), openings = 0;
            Vector3 origin = new Vector3(), end = new Vector3();
            for (int y = 0; y + 1 < height; y++) {
                for (int x = 0; x + 1 < width; x++) {
                    // Scene alpha is zero for sky. Ignore isolated float rasterization samples on triangle edges;
                    // a missing draw exposes connected areas, including many fully transparent 2x2 blocks.
                    if ((pixels.getPixel(x, y) & 255) != 0 || (pixels.getPixel(x + 1, y) & 255) != 0
                          || (pixels.getPixel(x, y + 1) & 255) != 0 || (pixels.getPixel(x + 1, y + 1) & 255) != 0) {
                        continue;
                    }
                    origin.set((x + .5f) * 2 / width - 1, (y + .5f) * 2 / height - 1, -1)
                          .prj(camera.camera.invProjectionView);
                    end.set((x + .5f) * 2 / width - 1, (y + .5f) * 2 / height - 1, 1)
                          .prj(camera.camera.invProjectionView);
                    origin.lerp(end, (BoardGeometry.floor(scene) - origin.z) / (end.z - origin.z));
                    var tile = BoardGeometry.tile(scene, origin.x, origin.y);
                    // Every ray through the inner footprint must meet the closed terrain above its base plane.
                    if (tile != null && tile.coords().getX() > 1 && tile.coords().getX() < scene.width() - 2
                          && tile.coords().getY() > 1 && tile.coords().getY() < scene.height() - 2) { openings++; }
                }
            }
            return openings;
        } finally {
            pixels.dispose();
            target.end();
        }
    }
}
