/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual maps from the uphill/downhill slope reports and the authored cliff road end. */
@Tag("on-demand")
class GpuRoadSlopeSmokeTest {
    @Test
    void capturesNativeRoadSlopesAndTheMaintainedTunnelAsset() throws Exception {
        var mines = GpuRoadSourceTest.minesScene();
        Board board = new Board();
        board.load(new File("data/boards/unofficial/DarkISI/16x17 Strassengitter 3.board"));
        var streets = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                streets.set(fixture.source.takeFrame().scene());
            });
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "road-slopes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                var frame = new GpuReviewFrame(weather);
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (var scene : List.of(mines, streets.get())) {
                        String map = scene == mines ? "mines" : "strassengitter";
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        for (var at : scene == mines ? List.of(new Coords(7, 14), new Coords(2, 5))
                              : List.of(new Coords(7, 8), new Coords(6, 9))) {
                            for (boolean oblique : new boolean[] { false, true }) {
                                camera.setIsometric(oblique);
                                camera.camera.zoom = .22f;
                                camera.center(BoardGeometry.center(at, scene.tile(at).elevation()));
                                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, map + "-" + at.getBoardNum() + "-" + oblique + ".png"));
                            }
                        }
                    }
                    terrain.update(mines);
                    var portals = mines.tiles().stream().flatMap(t -> BoardTunnel.entrances(mines, t).stream()).toList();
                    assertTrue(!portals.isEmpty(), "Mines contains road exits blocked by tall rock walls");
                    for (var portal : portals) {
                        camera.setIsometric(true);
                        camera.camera.zoom = .13f;
                        float azimuth = (float) Math.toDegrees(Math.atan2(-portal.along().x, portal.along().y));
                        camera.orbit(azimuth - camera.azimuth() + 20, 0);
                        camera.center(new Vector3(portal.origin()).add(0, 0, 7 * BoardGeometry.hexScale()));
                        GpuTerrainLodSmokeTest.settle(terrain, null, mines, camera);
                        frame.render(terrain, camera, mines);
                        GpuReviewFrame.save(new File(output, "tunnel-" + portal.road().getBoardNum() + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose();
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Native road slopes and tunnels", failure.get()); }
    }
}
