/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The reported single-frame sky flashes need a continuous orbit, not three still camera angles. */
@Tag("on-demand")
class GpuDomeVentRotationSmokeTest {
    @Test
    void rotatingDomeVentKeepsItsInteriorOpaque() throws Exception {
        var board = new Board();
        board.load(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 1.board"));
        var scene = BoardAridSurfaceTest.capture(board);
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "dome-orbit");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var original = BoardGeometry.tuning();
                var atmosphere = new GpuAtmosphere();
                atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    terrain.update(scene);
                    var camera = new BoardCamera();
                    camera.resize(960, 960);
                    camera.setIsometric(true);
                    camera.camera.zoom = .35f;
                    camera.center(BoardGeometry.center(new Coords(13, 9), 3));
                    int total = 0;
                    Vector3 origin = new Vector3(), end = new Vector3();
                    for (int angle = 0; angle < 360; angle += 2) {
                        if (angle > 0) { camera.orbit(2, 0); }
                        terrain.refine(camera.camera);
                        terrain.animate(1f / 30, List.of());
                        frame.prepare(terrain, camera, scene);
                        terrain.renderShadows(camera.camera, List.of());
                        atmosphere.updateLight(camera.camera);
                        atmosphere.prepareClouds(terrain, scene, 1f / 30);
                        atmosphere.begin(960, 960, 1f / 30);
                        ScreenUtils.clear(1, 0, 1, 1, true);
                        terrain.render(camera.camera, false);
                        terrain.renderTransparent(camera.camera);
                        var pixels = ScreenUtils.getFrameBufferPixmap(0, 0, 960, 960);
                        int holes = 0;
                        try {
                            for (int y = 0; y < 960; y++) for (int x = 0; x < 960; x++) {
                                if (pixels.getPixel(x, y) != 0xff00ffff) { continue; }
                                origin.set((x + .5f) / 480 - 1, (y + .5f) / 480 - 1, -1)
                                      .prj(camera.camera.invProjectionView);
                                end.set((x + .5f) / 480 - 1, (y + .5f) / 480 - 1, 1)
                                      .prj(camera.camera.invProjectionView);
                                float t = (BoardGeometry.floor(scene) - origin.z) / (end.z - origin.z);
                                origin.lerp(end, t);
                                var tile = BoardGeometry.tile(scene, origin.x, origin.y);
                                // Every ray through the inner footprint must meet a closed terrain column.
                                if (tile != null && tile.coords().getX() > 1 && tile.coords().getX() < scene.width() - 2
                                      && tile.coords().getY() > 1 && tile.coords().getY() < scene.height() - 2) {
                                    holes++;
                                }
                            }
                            if (holes > 0) {
                                System.out.println("Dome Vent angle " + angle + ": " + holes + " exposed sky pixels");
                                if (total == 0 || holes > 10) { GpuReviewFrame.save(new File(output, "holes-" + angle + ".png")); }
                            }
                            total += holes;
                        } finally { pixels.dispose(); }
                        atmosphere.end(camera.camera, terrain, scene, 0);
                    }
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "volcano-dome-vent.png"));
                    assertTrue(total == 0, "Rotating the closed board exposes " + total + " interior sky pixels");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(original);
                    atmosphere.dispose();
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Dome Vent rotation", failure.get()); }
    }
}
