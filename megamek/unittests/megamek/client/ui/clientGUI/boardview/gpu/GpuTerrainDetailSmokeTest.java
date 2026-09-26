/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native shader, shadow and mesh review for mixed shores and terrain-matched Rough boulders. */
@Tag("on-demand")
class GpuTerrainDetailSmokeTest {
    @Test
    void capturesMixedBanksAndRoughAcrossMaterials() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "terrain-detail");
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    GpuRiverTerrainSmokeTest.tune(.94f, true);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (int depth : new int[] { 0, 1 }) {
                        BoardScene scene = BoardTerrainDetailTest.shores(depth, false);
                        capture(scene, "mixed-shores-depth" + depth, BoardTerrainDetailTest.WATER, 0, .24f,
                              terrain, frame, camera);
                    }
                    for (var family : BoardScene.Surface.values()) {
                        capture(BoardTerrainDetailTest.rough(family), "rough-" + family.name().toLowerCase(Locale.ROOT),
                              new Coords(2, 2), 1, .34f, terrain, frame, camera);
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }

            private void capture(BoardScene scene, String name, Coords focus, int level, float zoom,
                  GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera) {
                terrain.update(scene);
                terrain.animate(.5f, List.of());
                for (boolean oblique : new boolean[] { false, true }) {
                    camera.setIsometric(oblique);
                    camera.camera.zoom = zoom;
                    camera.center(BoardGeometry.center(focus, level));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, name + (oblique ? "-oblique" : "-top") + ".png"));
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain detail review", failure.get()); }
    }
}
