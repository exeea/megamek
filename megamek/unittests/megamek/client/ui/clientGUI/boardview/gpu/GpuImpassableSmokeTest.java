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
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real terrain/depth rendering, emphasis, and restriction-only edits in the shared tactical renderer. */
@Tag("on-demand")
class GpuImpassableSmokeTest {
    @Test
    void floatsAboveGroundAndWaterInBothCamerasAndUpdatesWithoutChangingTerrain() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "impassable");
        Files.createDirectories(output.toPath());
        AtomicReference<BoardScene> initial = new AtomicReference<>(), cleared = new AtomicReference<>();
        Coords hover = new Coords(3, 3);
        SwingUtilities.invokeAndWait(() -> {
            Game game = new Game();
            Hex[] hexes = new Hex[81];
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 9; x++) {
                    String terrain = x >= 5 && y <= 4 ? "water:2" : y >= 5 ? "pavement:1" : "";
                    if (x == 2 && y == 3) { terrain = "woods:2;foliage_elev:2"; }
                    if (x == 3 && y == 6) { terrain += ";rough:1"; }
                    boolean marked = x >= 2 && x <= 6 && y >= 2 && y <= 6 && !(x == 4 && y == 4);
                    hexes[y * 9 + x] = new Hex(x <= 3 ? 2 : 0,
                          terrain + (marked ? ";impassable:0" : ""), "grass", new Coords(x, y));
                }
            }
            game.setBoard(new Board(9, 9, hexes));
            var source = new GpuMapSource(game, null, null);
            try {
                initial.set(source.takeFrame().scene());
                Hex changed = game.getBoard().getHex(hover).duplicate();
                changed.removeTerrain(Terrains.IMPASSABLE);
                game.getBoard().setHex(hover, changed);
                source.refresh();
                cleared.set(source.takeFrame().scene());
            } finally {
                source.close();
            }
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1100, 850);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuTactical tactical = new GpuTactical(terrain::tacticalSurface);
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardScene scene = initial.get();
                    terrain.update(scene);
                    tactical.update(scene);
                    compareGeometry(scene, terrain, output);
                    long builds = tactical.builds();
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.camera.zoom = .7f;
                    camera.center(BoardGeometry.center(new Coords(4, 4), 1));
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        tactical.update(scene);
                        assertEquals(builds, tactical.builds(), "Camera changes reuse the same overlay geometry");
                        frame.render(terrain, camera, scene);
                        tactical.render(camera.camera, 0);
                        GpuReviewFrame.save(new File(output, oblique ? "impassable-oblique.png" : "impassable-top.png"));
                    }
                    tactical.update(scene, false, hover);
                    assertEquals(builds + 1, tactical.builds());
                    tactical.update(scene, false, hover);
                    assertEquals(builds + 1, tactical.builds(), "A stationary hover must not rebuild each frame");
                    frame.render(terrain, camera, scene);
                    tactical.render(camera.camera, 0);
                    GpuReviewFrame.save(new File(output, "impassable-hover.png"));

                    BoardScene planning = new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(),
                          scene.units(), List.of(new BoardScene.Waypoint(hover, 0, 0)), -1, "", List.of());
                    tactical.update(planning);
                    builds = tactical.builds();
                    tactical.update(planning, false, new Coords(5, 3));
                    assertEquals(builds, tactical.builds(), "Planning already emphasizes all restrictions");
                    frame.render(terrain, camera, planning);
                    tactical.render(camera.camera, 0);
                    GpuReviewFrame.save(new File(output, "impassable-planning.png"));

                    var support = terrain.tacticalSurface(hover);
                    assertTrue(scene.tile(hover).sameGeometry(cleared.get().tile(hover)));
                    terrain.update(cleared.get());
                    assertEquals(support, terrain.tacticalSurface(hover), "Changing the flag must preserve physical terrain");
                    tactical.update(cleared.get(), false, hover);
                    frame.render(terrain, camera, cleared.get());
                    tactical.render(camera.camera, 0);
                    GpuReviewFrame.save(new File(output, "impassable-cleared.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    tactical.dispose();
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Impassable terrain rendering", failure.get()); }
    }

    private static void compareGeometry(BoardScene scene, GpuTerrain terrain, File output) throws Exception {
        var fills = BoardImpassable.fills(scene, null, false);
        int flatTriangles = fills.stream().mapToInt(fill -> BoardTacticalGeometry.flat(fill).size()).sum();
        int[] drapedTriangles = { 0 };
        var clipper = new BoardTacticalGeometry.Clipper();
        for (int layer = 0; layer < fills.size(); layer++) {
            var fill = fills.get(layer);
            var draped = new megamek.client.ui.clientGUI.boardview.BoardTactical.Fill(
                  fill.contours(), fill.winding(), fill.argb(), fill.playback());
            BoardTacticalGeometry.drape(scene, draped, layer, triangle -> drapedTriangles[0]++,
                  terrain::tacticalSurface, clipper);
        }
        Files.writeString(new File(output, "geometry.txt").toPath(),
              "Restricted hexes: " + scene.tiles().stream().filter(BoardScene.Tile::impassable).count()
                    + "\nFloating triangles: " + flatTriangles + "\nDraped triangles for the same marking: " + drapedTriangles[0]
                    + "\nCounts measure geometry complexity, not GPU time. Floating geometry omits terrain clipping.\n");
    }
}
