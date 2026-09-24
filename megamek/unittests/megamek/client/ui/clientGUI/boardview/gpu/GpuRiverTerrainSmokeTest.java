/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Review scene for water hexes inside sculpted land, after a user's desert map: a depth-1 river at level 2 winds
 * between land at levels 1 to 4, beside higher ground and above lower ground, and falls into a lake of depth-1 and
 * depth-2 hexes at level 1. Views: straight down with and without the grid, an oblique overview, a bank beside the
 * channel, a water hex's edge against higher land, the fall and a clay overview. Captures are drawn as on the board,
 * through the atmosphere composite ({@link GpuReviewFrame}); a report lists the renderer, build time, draws and vertices.
 */
@Tag("on-demand")
class GpuRiverTerrainSmokeTest {
    /** Rows are y; characters are x. Digits are land levels, r is river (level 2, depth 1), w/W lake (level 1). */
    static final String[] LAYOUT = {
          "33r22233344433",
          "43r32233444443",
          "43rr3223344333",
          "3323r432233322",
          "2222rrrr333222",
          "11221112r33222",
          "11111122rr3322",
          "1111112222r332",
          "1111111121r122",
          "111111111www22",
          "111111111wWW11",
          "1111111111ww11",
    };

    private record View(String name, boolean isometric, float tilt, float rotation, float zoom, Coords focus,
          int level, boolean clay, float grid) { }

    private static final List<View> VIEWS = List.of(
          new View("top", false, 0, 0, .88f, new Coords(7, 5), 2, false, .8f),
          new View("top-nogrid", false, 0, 0, .88f, new Coords(7, 5), 2, false, 1f),
          new View("oblique", true, 0, 0, .62f, new Coords(6, 5), 2, false, .8f),
          // The channel at (8, 6) runs between banks at levels 2 and 3.
          new View("bank", true, -10, 0, .14f, new Coords(8, 6), 2, false, .8f),
          // From the south onto river hex (5, 4) and the level-4 land north of it.
          new View("wall", true, -10, -45, .13f, new Coords(5, 4), 3, false, .8f),
          // From the lake up at the fall where the river at (10, 8) pours into it.
          new View("fall", true, -8, 0, .1f, new Coords(10, 9), 1, false, .8f),
          // From beyond the top edge at the river's head, which pours off the board, and the lake the bottom edge cuts.
          new View("edge", true, -10, 180, .14f, new Coords(2, 0), 2, false, .8f),
          new View("cut", true, -10, 0, .14f, new Coords(10, 11), 1, false, .8f),
          new View("clay", true, 0, 0, .62f, new Coords(6, 5), 2, true, 1f));

    @Test
    void capturesWaterHexesInSculptedLand() throws Exception {
        String families = System.getProperty("megamek.gpu.river.families", "SAND");
        List<String> views = List.of(System.getProperty("megamek.gpu.river.views", "").split(","));
        // Hex transitions on or off; captures with them on are named with a "-transitions" suffix.
        boolean transitions = Boolean.parseBoolean(System.getProperty("megamek.gpu.river.transitions",
              String.valueOf(BoardGeometry.DEFAULT_TRANSITIONS)));
        String suffix = transitions ? "-transitions" : "";
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "river-terrain");
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder report = new StringBuilder();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(" / ")
                          .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append('\n');
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (String name : families.split(",")) {
                        BoardScene.Surface family = BoardScene.Surface.valueOf(name.trim().toUpperCase(Locale.ROOT));
                        BoardScene scene = scene(family);
                        long start = System.nanoTime();
                        tune(.8f, transitions);
                        terrain.update(scene);
                        report.append(String.format(Locale.ROOT, "%s: build %.1f ms%n", family,
                              (System.nanoTime() - start) / 1e6));
                        for (View view : VIEWS) {
                            if (!views.get(0).isBlank() && !views.contains(view.name())) { continue; }
                            tune(view.grid(), transitions);
                            terrain.update(scene);
                            // Advances the water's wind waves once, as the running board does every frame.
                            terrain.animate(.5f, List.of());
                            terrain.setClay(view.clay());
                            camera.setIsometric(view.isometric());
                            camera.orbit(view.rotation(), view.tilt());
                            camera.camera.zoom = view.zoom();
                            camera.center(BoardGeometry.center(view.focus(), view.level()));
                            profiler.reset();
                            profiler.enable();
                            frame.render(terrain, camera, scene);
                            profiler.disable();
                            report.append(String.format(Locale.ROOT, "  %s: draws=%d vertices=%.0f%n", view.name(),
                                  profiler.getDrawCalls(), profiler.getVertexCount().total));
                            GpuReviewFrame.save(new File(output, family.name().toLowerCase(Locale.ROOT) + "-"
                                  + view.name() + suffix + ".png"));
                        }
                    }
                    terrain.setClay(false);
                    Files.writeString(new File(output, "report" + suffix + ".txt").toPath(), report);
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
        if (failure.get() != null) { throw new AssertionError("River terrain review", failure.get()); }
        System.out.print(report);
    }

    static void tune(float grid, boolean transitions) {
        BoardGeometry.tune(new BoardGeometry.Tuning(1, .7f, 1, 18, grid, BoardGeometry.DEFAULT_MULTI_HEX_UNIT_SCALE,
              transitions, BoardGeometry.DEFAULT_PADDING));
    }

    /** Every hex, water included, carries the family, as the game gives a desert map's water hexes its theme. */
    static BoardScene scene(BoardScene.Surface family) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff8a8a70); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image);
        int width = LAYOUT[0].length(), height = LAYOUT.length;
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char cell = LAYOUT[y].charAt(x);
                int level = switch (cell) {
                    case 'r' -> 2;
                    case 'w', 'W' -> 1;
                    default -> cell - '0';
                };
                int depth = switch (cell) {
                    case 'r', 'w' -> 1;
                    case 'W' -> 2;
                    default -> -1;
                };
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(),
                      depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
