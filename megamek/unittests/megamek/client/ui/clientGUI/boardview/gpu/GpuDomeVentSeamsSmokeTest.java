/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertAll;
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

/**
 * Dome Vent 2's cliff seams, both checked on one capture of the shipped board in one native window: the narrow rock
 * bank between the descending 1104/1105 lava and the land at 1204, and the cliff behind the lava falling from 1203 into
 * 1104, with lava and with water. The screen is cleared to magenta, which can only reach a capture through a hole.
 */
@Tag("on-demand")
class GpuDomeVentSeamsSmokeTest {
    private static final int BACKGROUND = 0xff00ffff;

    @Test
    void descendingLavaBankAndTheFallsCliffOccludeTheBackground() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"));
        BoardScene scene;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                BoardGeometry.Tuning original = BoardGeometry.tuning();
                GpuTerrain terrain = new GpuTerrain();
                try {
                    assertAll("Dome Vent 2 seams",
                          () -> lavaBank(terrain, scene, new File(output, "lava-bank-seams")),
                          () -> fallsCliff(terrain, scene, new File(output, "waterfall-seams")));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Dome Vent seams", failure.get()); }
    }

    /** The descending lava bank must close against the cliff: no connected opening in the 320 x 320 centre patch. */
    private static void lavaBank(GpuTerrain terrain, BoardScene scene, File output) {
        BoardGeometry.tune(BoardGeometry.DEFAULTS);
        terrain.update(scene);
        terrain.animate(.5f, List.of());
        assertTrue(output.isDirectory() || output.mkdirs());
        for (boolean perspective : new boolean[] { false, true }) {
            for (int angle : new int[] { -90, -60, -30 }) {
                BoardCamera camera = camera(perspective, angle, -20, .06f);
                camera.center(new Vector3(693, -288, 63));
                render(terrain, camera);
                GpuBoardTestUi.capture(new File(output, "bank-" + angle
                      + (perspective ? "-perspective" : "-ortho") + ".png"));
                int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                Pixmap pixels = ScreenUtils.getFrameBufferPixmap(width / 2 - 160, height / 2 - 160, 320, 320);
                try {
                    // Float rasterization can leave an isolated sample on a shared triangle edge.
                    // The reported missing wall produces a connected, multi-pixel opening.
                    int holes = holes(pixels);
                    assertTrue(holes <= 2, "The lava bank must close against the cliff at angle " + angle
                          + ": " + holes + " background pixels");
                    for (int y = 1; y + 1 < pixels.getHeight(); y++) {
                        for (int x = 1; x + 1 < pixels.getWidth(); x++) {
                            if (pixels.getPixel(x, y) != BACKGROUND) { continue; }
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dx = -1; dx <= 1; dx++) {
                                    if (dx == 0 && dy == 0) { continue; }
                                    assertTrue(pixels.getPixel(x + dx, y + dy) != BACKGROUND,
                                          "No connected opening may remain in the bank");
                                }
                            }
                        }
                    }
                } finally { pixels.dispose(); }
            }
        }
    }

    /** The cliff behind the falls, below the rim in every view, must occlude the background with lava and with water. */
    private static void fallsCliff(GpuTerrain terrain, BoardScene scene, File output) {
        BoardGeometry.Tuning defaults = BoardGeometry.DEFAULTS;
        BoardGeometry.tune(new BoardGeometry.Tuning(defaults.hexScale(), defaults.unitScale(),
              defaults.unitHeightScale(), 18, defaults.gridShade(), defaults.multiHexUnitScale(), true));
        assertTrue(output.isDirectory() || output.mkdirs());
        for (boolean water : new boolean[] { false, true }) {
            terrain.update(water ? BoardWaterfallTest.withWater(scene) : scene);
            terrain.animate(.5f, List.of());
            for (boolean perspective : new boolean[] { false, true }) {
                for (int angle : new int[] { -15, 0, 15 }) {
                    BoardCamera camera = camera(perspective, angle, 25, .1f);
                    camera.center(BoardGeometry.corner(new Coords(11, 2), 5.5f, 4));
                    render(terrain, camera);
                    GpuBoardTestUi.capture(new File(output, "dome-vent-" + (water ? "water-" : "lava-") + angle
                          + (perspective ? "-perspective" : "-ortho") + ".png"));
                    int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                    Pixmap pixels = ScreenUtils.getFrameBufferPixmap(width / 2 - 60, height / 2 - 80, 120, 160);
                    try {
                        assertEquals(0, holes(pixels), "The cliff behind the waterfall must occlude the background");
                    } finally { pixels.dispose(); }
                }
            }
        }
    }

    private static BoardCamera camera(boolean perspective, int angle, int tilt, float zoom) {
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setPerspective(perspective);
        camera.setIsometric(true);
        camera.orbit(angle, tilt);
        camera.camera.zoom = zoom;
        return camera;
    }

    private static void render(GpuTerrain terrain, BoardCamera camera) {
        terrain.renderShadows(camera.camera, List.of());
        ScreenUtils.clear(1, 0, 1, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
    }

    private static int holes(Pixmap pixels) {
        int holes = 0;
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                if (pixels.getPixel(x, y) == BACKGROUND) { holes++; }
            }
        }
        return holes;
    }
}
