/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Review renders of shipped boards, such as the Savannah map pack that the user compares with its printed maps: each
 * board named by {@code megamek.gpu.boards} (paths under data/boards, comma separated) straight down and from an
 * oblique angle, at the hour {@code megamek.gpu.boards.hour}, with hex transitions as by default, drawn as on the board
 * ({@link GpuReviewFrame}).
 */
@Tag("on-demand")
class GpuBoardFileSmokeTest {
    @Test
    void capturesShippedBoards() throws Exception {
        capture(System.getProperty("megamek.gpu.boards", "Map Pack Savannahs/16x17 Wide River (Svannah).board"));
    }

    @Test
    void rendersTallCliffBoardsAcrossMeshBoundaries() throws Exception {
        // Maze A overflows a partially filled mesh; Thunder Rift can overflow even a fresh mesh within one hex.
        capture("unofficial/Jakes Map Pack/16x17 Maze A.board,Legendary Battles BattleMats/32x17 Thunder Rift.board");
    }

    private static void capture(String boards) throws Exception {
        float hour = Float.parseFloat(System.getProperty("megamek.gpu.boards.hour", "13"));
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "boards");
        Files.createDirectories(output.toPath());
        List<String> names = new ArrayList<>();
        List<BoardScene> scenes = new ArrayList<>();
        for (String path : boards.split(",")) {
            Board board = new Board();
            board.load(new File("data/boards", path.trim()));
            try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
                AtomicReference<BoardScene> scene = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    fixture.source.refresh();
                    scene.set(fixture.source.takeFrame().scene());
                });
                scenes.add(scene.get());
            }
            names.add(new File(path.trim()).getName().replaceFirst("\\.board$", "").toLowerCase(Locale.ROOT)
                  .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""));
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(hour, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    GpuRiverTerrainSmokeTest.tune(.8f, BoardGeometry.DEFAULT_TRANSITIONS);
                    for (int i = 0; i < scenes.size(); i++) {
                        BoardScene scene = scenes.get(i);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        Coords middle = new Coords(scene.width() / 2, scene.height() / 2);
                        // Zoom that fits the board: the river review frames a 14 by 12 board at .88 from above.
                        float fit = .88f * Math.max(scene.width() / 14f, scene.height() / 12f);
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.orbit(0, 0);
                            camera.camera.zoom = oblique ? .7f * fit : fit;
                            camera.center(BoardGeometry.center(middle, scene.tile(middle).elevation()));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, names.get(i) + (oblique ? "-oblique" : "-top")
                                  + String.format(Locale.ROOT, "-h%s.png", hour)));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Shipped board review", failure.get()); }
    }
}
