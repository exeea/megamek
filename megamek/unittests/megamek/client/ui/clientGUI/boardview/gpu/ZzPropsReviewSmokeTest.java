/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: close-ups of props whose textures change (zz-props/<megamek.gpu.zzlabel>). */
@Tag("on-demand")
class ZzPropsReviewSmokeTest {
    private static final Coords DEPOSITS = new Coords(1, 1), CRATE = new Coords(3, 1), SANDBAGS = new Coords(5, 1),
          SNOW = new Coords(7, 1), FELLED = new Coords(1, 3), SAWN = new Coords(3, 3), DESERT = new Coords(5, 3),
          GRASS = new Coords(7, 3), WETLAND = new Coords(1, 5), HIGHLAND = new Coords(3, 5),
          HEAVY = new Coords(5, 5);
    private static final String SHRUBS = "woods:1;foliage_elev:1";
    private static final Map<Coords, String> TERRAIN = Map.of(DEPOSITS, "metal_deposit:10", SANDBAGS, "pavement:1;fortified:1",
          SNOW, SHRUBS + ";snow:1", FELLED, "rough:1;fluff:2", SAWN, "woods:1", DESERT, SHRUBS, GRASS, SHRUBS,
          WETLAND, SHRUBS + ";swamp:1", HIGHLAND, SHRUBS + ";tundra:1", HEAVY, "woods:2;foliage_elev:2");

    @Test
    void props() throws Exception {
        File output = new File(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "zz-props"),
              System.getProperty("megamek.gpu.zzlabel", "now"));
        assertTrue(output.isDirectory() || output.mkdirs());
        Board board = Board.createEmptyBoard(9, 7);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                Coords at = new Coords(x, y);
                board.setHex(at, new Hex(0, TERRAIN.getOrDefault(at, ""), at.equals(DESERT) ? "desert" : ""));
            }
        }
        BoardScene base = GpuLegacyImportSmokeTest.scene(board);
        List<BoardScene.Tile> tiles = new ArrayList<>(base.tiles());
        tiles.replaceAll(tile -> tile.coords().equals(CRATE) ? BoardFeatures.withCargo(tile)
              : tile.coords().equals(SAWN) || tile.coords().equals(HEAVY) ? BoardFeatures.withSawClearing(tile, 1) : tile);
        BoardScene scene = new BoardScene(0, base.width(), base.height(), tiles, List.of(), List.of(), -1, "", List.of());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    record Shot(String name, Coords at, float zoom) { }
                    for (var shot : List.of(new Shot("deposits-close", DEPOSITS, .05f), new Shot("crate", CRATE, .05f),
                          new Shot("sandbags", SANDBAGS, .06f), new Shot("sandbags-close", SANDBAGS, .03f),
                          new Shot("felled", FELLED, .07f), new Shot("felled-close", FELLED, .035f),
                          new Shot("sawn", SAWN, .07f), new Shot("sawn-close", SAWN, .035f),
                          new Shot("sawn-heavy", HEAVY, .07f), new Shot("sawn-heavy-close", HEAVY, .045f),
                          new Shot("pile", HEAVY, .03f), new Shot("pile-light", SAWN, .03f),
                          new Shot("pile-side-a", HEAVY, .03f), new Shot("pile-side-b", HEAVY, .03f),
                          new Shot("pile-side-c", HEAVY, .03f),
                          new Shot("shrubs-temperate", GRASS, .035f), new Shot("shrubs-desert", DESERT, .035f),
                          new Shot("shrubs-snow", SNOW, .035f), new Shot("shrubs-wetland", WETLAND, .035f),
                          new Shot("shrubs-highland", HIGHLAND, .035f))) {
                        camera.setIsometric(true);
                        var centre = BoardGeometry.center(shot.at(), 0);
                        if (shot.name().startsWith("pile")) {
                            var logs = scene.tile(shot.at()).features().stream()
                                  .filter(feature -> feature.asset().equals(BoardFeatures.SAWN_LOG)).toList();
                            centre.add((float) logs.stream().mapToDouble(BoardScene.Feature::x).average().orElseThrow()
                                  * BoardGeometry.hexScale(), (float) logs.stream().mapToDouble(BoardScene.Feature::y)
                                  .average().orElseThrow() * BoardGeometry.hexScale(), 0);
                        }
                        if (shot.name().startsWith("crate")) {
                            var crate = scene.tile(CRATE).features().stream()
                                  .filter(feature -> feature.asset().equals(BoardFeatures.CARGO)).findFirst().orElseThrow();
                            centre.add(crate.x() * BoardGeometry.hexScale(), crate.y() * BoardGeometry.hexScale(), 0);
                        }
                        camera.center(centre);
                        if (shot.name().startsWith("pile-side")) { camera.orbit(shot.name().endsWith("a") ? 0 : 90, -15); }
                        camera.camera.zoom = shot.zoom();
                        camera.update();
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, shot.name() + ".png"));
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Props review", failure.get()); }
    }
}
