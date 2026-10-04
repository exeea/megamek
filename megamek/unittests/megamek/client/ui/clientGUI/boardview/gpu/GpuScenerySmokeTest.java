/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuScenerySmokeTest {
    @Test
    void rendersSceneryDecalsAndFccwThroughTheSharedScene() throws Exception {
        var catalog = Board.createEmptyBoard(9, 8);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 8; y++) {
            catalog.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        List<String> variants = List.of("fluff:7:0", "fluff:6:0", "fluff:8:0", "fluff:6:6", "fluff:6:12",
              "road:1:9;fluff:5:2", "woods:1;fluff:80:1", "woods:1;fluff:81:1", "fluff:9:7", "fluff:50:1",
              "fluff:14:0", "fluff:91:1", "fluff:4:0", "fluff:93:6", "fluff:13:1", "fluff:10:9",
              "fluff:2:1", "fluff:2:3", "fortified:1", "geyser:2", "fluff:7:1", "fluff:7:2",
              "road:2:9", "rubble:3");
        for (int i = 0; i < variants.size(); i++) {
            catalog.setHex(new Coords(1 + i % 6, 1 + i / 6), new Hex(0, "pavement:1;" + variants.get(i), ""));
        }
        var details = Board.createEmptyBoard(9, 8);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 8; y++) {
            details.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        List<String> detailVariants = List.of("fluff:5:0", "fluff:5:1", "fluff:5:2", "fluff:5:6", "fluff:5:7", "fluff:5:98",
              "woods:1;fluff:80:1", "woods:1;fluff:80:8", "woods:1;fluff:80:16", "woods:1;fluff:80:23",
              "fluff:93:11", "fluff:92:3", "fluff:13:3", "fluff:13:3", "fluff:13:3", "fluff:13:3", "fluff:13:3", "fluff:13:3",
              "fluff:7:3", "fluff:7:4", "fluff:7:5", "fluff:8:6", "fluff:4:6", "fluff:30:1",
              "fluff:6:18", "fluff:92:1", "fluff:92:2", "fluff:92:3", "fluff:92:4", "fluff:92:5",
              "water:0;fluff:94:1", "water:0;fluff:94:2", "water:0;fluff:94:3", "water:0;fluff:94:4", "water:0;fluff:94:5");
        for (int i = 0; i < detailVariants.size(); i++) {
            details.setHex(new Coords(1 + i % 6, 1 + i / 6), new Hex(0, "pavement:1;" + detailVariants.get(i), ""));
        }
        var corrections = Board.createEmptyBoard(9, 8);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 8; y++) {
            corrections.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        List<String> correctedVariants = List.of("fluff:4:0", "fluff:4:1", "fluff:4:2", "fluff:4:3", "fluff:4:4", "fluff:4:5",
              "fluff:7:0", "fluff:7:1", "fluff:7:2", "fluff:13:2", "fluff:13:2", "fluff:13:2",
              "fluff:13:2", "fluff:13:2", "fluff:13:2",
              "fluff:13:1", "fluff:13:1", "fluff:13:1", "fluff:13:1", "fluff:13:1", "fluff:13:1",
              "fluff:13:0", "fluff:13:0", "fluff:13:0", "fluff:13:0", "fluff:13:0", "fluff:13:0",
              "fluff:13:4", "fluff:13:4");
        for (int i = 0; i < correctedVariants.size(); i++) {
            corrections.setHex(new Coords(1 + i % 6, 1 + i / 6), new Hex(0, "pavement:1;" + correctedVariants.get(i), ""));
        }
        var fccw = new Board();
        fccw.load(new File("data/boards/unofficial/Aokarasu/45x45 FCCW 4-1.board"));
        assertEquals(45, fccw.getWidth());
        var scenes = new ArrayList<BoardScene>();
        for (Board board : List.of(catalog, fccw, details, corrections)) {
            var captured = new AtomicReference<BoardScene>();
            SwingUtilities.invokeAndWait(() -> {
                try (var artwork = new BoardArtwork()) {
                    var tiles = new ArrayList<BoardScene.Tile>();
                    var pool = new BoardScene.PixelPool();
                    for (int x = 0; x < board.getWidth(); x++) for (int y = 0; y < board.getHeight(); y++) {
                        var coords = new Coords(x, y);
                        tiles.add(BoardScene.captureTile(board.getHex(coords), artwork.capture(board, coords, true),
                              null, pool, board::getHex));
                    }
                    captured.set(new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of()));
                }
            });
            scenes.add(captured.get());
        }
        var fccwScene = scenes.get(1);
        assertTrue(fccwScene.tiles().stream().filter(t -> t.features().stream()
              .anyMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY)).count() >= 25);
        assertEquals(2, scenes.get(3).tiles().stream().flatMap(t -> t.features().stream())
              .map(BoardScene.Feature::asset).filter(a -> a.startsWith("scenery/fluff/horses")).distinct().count(),
              "The native review must exercise both shuffled horse variants");
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "scenery");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1100);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    GpuRiverTerrainSmokeTest.tune(.8f, BoardGeometry.DEFAULT_TRANSITIONS);
                    for (int i = 0; i < scenes.size(); i++) {
                        var scene = scenes.get(i);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        Coords focus = i != 1 ? new Coords(4, 3) : scene.tiles().stream()
                              .filter(t -> t.features().stream().anyMatch(f -> f.asset().equals("scenery/fluff/construction1")))
                              .findFirst().orElseThrow().coords();
                        var ray = new Ray(BoardGeometry.center(focus, 0).add(0, 0, 1000), new Vector3(0, 0, -1));
                        BoardGeometry.Hit picked = null;
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = i != 1 ? .48f : .35f;
                            camera.center(BoardGeometry.center(focus, scene.tile(focus).elevation()));
                            frame.render(terrain, camera, scene);
                            var hit = terrain.hit(scene, ray);
                            assertTrue(hit != null);
                            if (picked == null) { picked = hit; } else { assertEquals(picked, hit); }
                            GpuReviewFrame.save(new File(output, List.of("catalog", "fccw-4-1", "variants", "corrections").get(i)
                                  + (oblique ? "-oblique.png" : "-top.png")));
                        }
                        if (i == 2) {
                            camera.camera.zoom = .13f;
                            camera.center(BoardGeometry.center(new Coords(3, 3), 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "chickens-in-game.png"));
                            camera.camera.zoom = .29f;
                            camera.center(BoardGeometry.center(new Coords(3, 5), 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "pools-in-game.png"));
                        }
                        if (i == 3) {
                            for (String asset : List.of("horses1", "horses2", "cattle1", "cattle2", "cattle3",
                                  "pigs1", "pigs2", "bison1")) {
                                Coords horse = scene.tiles().stream().filter(t -> t.features().stream()
                                      .anyMatch(f -> f.asset().equals("scenery/fluff/" + asset))).findFirst().orElseThrow().coords();
                                camera.camera.zoom = .12f;
                                camera.center(BoardGeometry.center(horse, 0));
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, asset + "-in-game.png"));
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Scenery and FCCW review", failure.get()); }
    }
}
