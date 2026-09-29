/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GL compilation and pixels, including live discovery and the normal-map/detail controls. */
@Tag("on-demand")
class GpuIceSmokeTest {
    private static final Coords ROAD = new Coords(1, 2), LAKE = new Coords(4, 2);

    @Test
    void iceUsesMappedMaterialsWithoutRevealingUndetectedBlackIce() throws Exception {
        BoardScene dry = scene(0), hidden = scene(1), detected = scene(2);
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(settings(13));
                try {
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    var camera = new BoardCamera();
                    camera.resize(1200, 900);
                    camera.fit(dry);
                    terrain.setAtmosphere(frame.lighting());
                    terrain.update(dry);
                    int[] road = sample(terrain, camera, ROAD);
                    GpuReviewFrame.save(new File(output, "ice-debug-dry.png"));
                    terrain.update(hidden);
                    assertArrayEquals(road, sample(terrain, camera, ROAD), "Undetected ice must be visually identical");
                    terrain.update(detected);
                    int[] glazed = sample(terrain, camera, ROAD);
                    GpuReviewFrame.save(new File(output, "ice-debug-detected.png"));
                    assertTrue(difference(road, glazed) > 1, "Discovery must visibly coat the road: " + difference(road, glazed));
                    terrain.setNormalMaps(false);
                    camera.setIsometric(true);
                    camera.camera.zoom = .16f;
                    camera.center(BoardGeometry.center(LAKE, 0));
                    int[] flat = sample(terrain, camera, LAKE);
                    terrain.setNormalMaps(true);
                    double normalChange = difference(flat, sample(terrain, camera, LAKE));
                    assertTrue(normalChange > .05, "Ice normal maps must reach the GL shader: " + normalChange);
                    frame.render(terrain, camera, detected);
                    GpuReviewFrame.save(new File(output, "ice-lake-detail.png"));
                    camera.center(BoardGeometry.center(ROAD, 0));
                    frame.render(terrain, camera, detected);
                    GpuReviewFrame.save(new File(output, "ice-road-detected.png"));
                    for (boolean perspective : new boolean[] {false, true}) {
                        camera.setPerspective(perspective);
                        camera.setIsometric(true);
                        camera.fit(detected);
                        frame.render(terrain, camera, detected);
                        GpuReviewFrame.save(new File(output, perspective ? "ice-perspective.png" : "ice-isometric.png"));
                    }
                    camera.setPerspective(false);
                    camera.setIsometric(false);
                    camera.fit(detected);
                    frame.configure(settings(0));
                    frame.render(terrain, camera, detected);
                    GpuReviewFrame.save(new File(output, "ice-night.png"));
                    frame.configure(settings(13));
                    terrain.setAtmosphere(frame.lighting());
                    int[] beforeRemoval = sample(terrain, camera, ROAD);
                    terrain.update(dry);
                    int[] restored = sample(terrain, camera, ROAD);
                    assertTrue(difference(beforeRemoval, restored) > 1, "Removing ice must remove the visible coat");
                    terrain.update(hidden);
                    assertArrayEquals(restored, sample(terrain, camera, ROAD), "Hidden ice retains the restored dry surface");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Ice material rendering", failure.get()); }
    }

    private static BoardAtmosphere.Settings settings(float hour) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }

    private static BoardScene scene(int discovery) throws Exception {
        var task = new FutureTask<BoardScene>(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(7, 5);
            for (int x = 0; x < 7; x++) {
                for (int y = 0; y < 5; y++) {
                    String contents = x == 1 ? "road:1:9" : "";
                    if (x == 1 && discovery > 0) { contents += ";black_ice:1"; }
                    if (x > 2 && x < 6 && y > 0 && y < 4) { contents = "water:2;ice:1"; }
                    if (x == 6 && y == 2) { contents = "ice:1"; }
                    var hex = new Hex(0, contents, "grass");
                    if (x == 1 && discovery > 1) { hex.getTerrain(Terrains.BLACK_ICE).detectBlackIce(); }
                    board.setHex(new Coords(x, y), hex);
                }
            }
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static int[] sample(GpuTerrain terrain, BoardCamera camera, Coords coords) {
        ScreenUtils.clear(.02f, .025f, .035f, 1, true);
        terrain.render(camera.camera, false);
        Vector3 screen = camera.camera.project(BoardGeometry.center(coords, 0));
        Pixmap image = ScreenUtils.getFrameBufferPixmap(Math.round(screen.x) - 24, Math.round(screen.y) - 24, 48, 48);
        try {
            int[] result = new int[48 * 48];
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 48; x++) { result[y * 48 + x] = image.getPixel(x, y); }
            }
            return result;
        } finally { image.dispose(); }
    }

    private static double difference(int[] a, int[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift : new int[] {24, 16, 8}) { sum += Math.abs((a[i] >>> shift & 255) - (b[i] >>> shift & 255)); }
        }
        return sum / (a.length * 3);
    }
}
