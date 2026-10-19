/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native optical and motion checks for frozen falls beside the flowing and mixed-water cases. */
@Tag("on-demand")
class GpuFrozenWaterfallSmokeTest {
    private static final int WIDTH = 1200, HEIGHT = 900;
    private static final List<Coords> SOURCES = List.of(new Coords(2, 2), new Coords(6, 2),
          new Coords(10, 2), new Coords(14, 2));
    private static final List<String> NAMES = List.of("frozen-frozen", "frozen-open", "open-frozen", "open-open");

    @Test
    void frozenDropsStayStillAndThawIntoFlowingWaterInBothCameras() throws Exception {
        BoardScene frozen = scene(false), thawed = scene(true);
        var corners = new ArrayList<BoardScene>();
        var cornerDirections = new ArrayList<Integer>();
        var liquidCorners = new ArrayList<Boolean>();
        for (int direction = 0; direction < 6; direction++) {
            if (direction != 3 && !Boolean.getBoolean("megamek.gpu.iceUvReview")) { continue; }
            corners.add(joinedScene(direction, false));
            corners.add(joinedScene(direction, true));
            cornerDirections.add(direction);
            cornerDirections.add(direction);
            liquidCorners.add(false);
            liquidCorners.add(false);
        }
        if (Boolean.getBoolean("megamek.gpu.iceImpactReview")) {
            // Append extra scenes after all original controls so their material clock remains comparable.
            for (int direction = 0; direction < 6; direction++) {
                if (direction != 3 && !Boolean.getBoolean("megamek.gpu.iceUvReview")) { continue; }
                corners.add(joinedScene(direction, false, true));
                corners.add(joinedScene(direction, true, true));
                cornerDirections.add(direction);
                cornerDirections.add(direction);
                liquidCorners.add(true);
                liquidCorners.add(true);
            }
        }
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(WIDTH, HEIGHT);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                          "frozen-waterfall");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    var camera = new BoardCamera();
                    camera.resize(WIDTH, HEIGHT);
                    camera.setIsometric(true);
                    camera.fit(frozen);
                    terrain.setAtmosphere(frame.lighting());
                    terrain.update(frozen);
                    settle(terrain, frame, camera, frozen);
                    frame.render(terrain, camera, frozen);
                    GpuReviewFrame.save(new File(output, "all-drops-isometric.png"));
                    camera.setIsometric(false);
                    camera.fit(frozen);
                    settle(terrain, frame, camera, frozen);
                    frame.render(terrain, camera, frozen);
                    GpuReviewFrame.save(new File(output, "all-drops-top.png"));

                    for (boolean perspective : new boolean[] { false, true }) {
                        camera.setPerspective(perspective);
                        String suffix = perspective ? "-perspective" : "-isometric";
                        for (int i = 0; i < SOURCES.size(); i++) {
                            focus(camera, SOURCES.get(i));
                            settle(terrain, frame, camera, frozen);
                            frame.render(terrain, camera, frozen);
                            GpuReviewFrame.save(new File(output, NAMES.get(i) + suffix + ".png"));
                            List<Vector3> points = probes(frozen, SOURCES.get(i));
                            int[] before = read(camera, points);
                            frame.render(terrain, camera, frozen, false);
                            assertTrue(difference(before, read(camera, points)) > .5,
                                  "The falling sheet must be visible in front of its cliff: " + NAMES.get(i) + suffix);
                            frame.render(terrain, camera, frozen);
                            assertArrayEquals(before, read(camera, points), "A paused fall must repeat its pixels");
                            if (i < 2) {
                                // Constant-background ice-only draws isolate the frozen material itself. The
                                // full transparent pass also contains the animated open receiver behind the ice.
                                try (var pixels = new GpuSceneTestOutput()) {
                                    int[] mixed = transparent(pixels, terrain, camera, points);
                                    int[] ice = frozenOnly(pixels, terrain, camera, points);
                                    terrain.animate(.7f, List.of());
                                    int[] mixedAfter = transparent(pixels, terrain, camera, points);
                                    int[] iceAfter = frozenOnly(pixels, terrain, camera, points);
                                    stationarity(NAMES.get(i) + suffix, camera, points, mixed, mixedAfter, ice, iceAfter);
                                    assertArrayEquals(ice, iceAfter,
                                          "The isolated frozen material must remain exactly stationary: " + NAMES.get(i));
                                    terrain.setNormalMaps(false);
                                    double changed = difference(ice, frozenOnly(pixels, terrain, camera, points));
                                    assertTrue(changed > .05, "Frozen fall normal detail must reach the shader: " + changed);
                                    terrain.setNormalMaps(true);
                                    if (Boolean.getBoolean("megamek.gpu.iceCompositionReview")) {
                                        GpuIceSmokeTest.verifyIceComposition(pixels, terrain, camera, frame, frozen,
                                              6, NAMES.get(i) + suffix);
                                    }
                                }
                            } else {
                                terrain.animate(.7f, List.of());
                                frame.render(terrain, camera, frozen);
                                assertTrue(difference(before, read(camera, points)) > .05,
                                      "An unfrozen source must keep flowing: " + NAMES.get(i) + suffix);
                            }
                        }
                    }

                    camera.setPerspective(false);
                    focus(camera, SOURCES.getFirst());
                    camera.orbit(180, 0);
                    settle(terrain, frame, camera, frozen);
                    frame.render(terrain, camera, frozen);
                    GpuReviewFrame.save(new File(output, "frozen-frozen-behind.png"));
                    camera.orbit(-180, 0);
                    settle(terrain, frame, camera, frozen);
                    frame.render(terrain, camera, frozen);
                    List<Vector3> frozenPoints = probes(frozen, SOURCES.getFirst());
                    int[] ice = read(camera, frozenPoints);
                    terrain.update(thawed);
                    settle(terrain, frame, camera, thawed);
                    frame.render(terrain, camera, thawed);
                    assertTrue(difference(ice, read(camera, frozenPoints)) > .5,
                          "A live thaw must replace the frozen curtain");
                    List<Vector3> waterPoints = probes(thawed, SOURCES.getFirst());
                    int[] water = read(camera, waterPoints);
                    terrain.animate(.7f, List.of());
                    frame.render(terrain, camera, thawed);
                    assertTrue(difference(water, read(camera, waterPoints)) > .05, "The thawed curtain must animate");
                    GpuReviewFrame.save(new File(output, "thawed.png"));
                    for (int corner = 0; corner < corners.size(); corner++) {
                        BoardScene joined = corners.get(corner);
                        terrain.update(joined);
                        terrain.animate(.37f, List.of());
                        List<Vector3> points = joined.tiles().stream().filter(tile -> tile.water() && tile.elevation() > 0)
                              .flatMap(tile -> probes(new BoardSurface(joined, tile)).stream()).toList();
                        for (boolean perspective : new boolean[] { false, true }) {
                            camera.setPerspective(perspective);
                            focus(camera, new Coords(3, 3), cornerDirections.get(corner));
                            // Board directions turn clockwise; the camera's azimuth turns counterclockwise.
                            camera.orbit((3 - cornerDirections.get(corner)) * 60, 0);
                            camera.update();
                            settle(terrain, frame, camera, joined);
                            if (liquidCorners.get(corner)) {
                                renderImpact(terrain, () -> frame.render(terrain, camera, joined));
                            } else { frame.render(terrain, camera, joined); }
                            String name = (liquidCorners.get(corner) ? "impact-" : "")
                                  + (corner % 2 == 0 ? "joined-prow" : "joined-valley")
                                  + (cornerDirections.get(corner) == 3 ? "" : "-direction-" + cornerDirections.get(corner))
                                  + (perspective ? "-perspective" : "-isometric");
                            GpuReviewFrame.save(new File(output, name + ".png"));
                            int[] pixels = read(camera, points);
                            frame.render(terrain, camera, joined, false);
                            assertTrue(difference(pixels, read(camera, points)) > .5,
                                  "The joined curtain must be visible over its cliff: " + name);
                            frame.render(terrain, camera, joined);
                            assertArrayEquals(pixels, read(camera, points), "Joined curtains must repeat while paused");
                        }
                    }
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
        if (failure.get() != null) { throw new AssertionError("Frozen waterfall rendering", failure.get()); }
    }

    private static void focus(BoardCamera camera, Coords source) {
        focus(camera, source, 3);
    }

    private static void focus(BoardCamera camera, Coords source, int direction) {
        camera.setIsometric(true);
        camera.camera.zoom = .16f;
        camera.center(BoardGeometry.center(source, 2.5f)
              .lerp(BoardGeometry.center(source.translated(direction), 2.5f), .5f));
        camera.update();
    }

    private static void settle(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
        frame.prepare(terrain, camera, scene);
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (terrain.refine(camera.camera) || terrain.busy()) {
            assertTrue(System.nanoTime() < deadline, "The waterfall fixture must finish preparing");
            frame.render(terrain, camera, scene);
        }
    }

    private static BoardScene scene(boolean thawed) throws Exception {
        var task = new FutureTask<BoardScene>(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(18, 7);
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    int channel = (x - 2) / 4;
                    boolean water = x >= 2 && x <= 14 && (x - 2) % 4 == 0 && y >= 1 && y <= 4;
                    boolean frozen = water && (y < 3 ? channel <= 1 : channel == 0 || channel == 2)
                          && !(thawed && channel == 0);
                    board.setHex(new Coords(x, y), new Hex(y < 3 ? 5 : 0,
                          water ? "water:2" + (frozen ? ";ice:1" : "") : "snow:1", "snow"));
                }
            }
            game.setBoard(board);
            BoardSceneryLayouts.convert(game.getBoard());
            try (var source = new GpuMapSource(game, null, null)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static BoardScene joinedScene(boolean valley) throws Exception {
        return joinedScene(3, valley);
    }

    private static BoardScene joinedScene(int direction, boolean valley) throws Exception {
        return joinedScene(direction, valley, false);
    }

    private static BoardScene joinedScene(int direction, boolean valley, boolean liquidSource) throws Exception {
        var task = new FutureTask<BoardScene>(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(7, 7);
            Coords high = new Coords(3, 3), low = high.translated(direction), side = high.translated((direction + 1) % 6);
            List<Coords> water = List.of(high, low, side);
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    Coords coords = new Coords(x, y);
                    boolean upper = coords.equals(high) || valley && coords.equals(side);
                    String terrain = water.contains(coords) ? "water:2" + (upper && liquidSource ? "" : ";ice:1") : "snow:1";
                    board.setHex(coords, new Hex(upper ? 5 : 0, terrain, "snow"));
                }
            }
            game.setBoard(board);
            BoardSceneryLayouts.convert(game.getBoard());
            try (var source = new GpuMapSource(game, null, null)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static List<Vector3> probes(BoardScene scene, Coords source) {
        var surface = new BoardSurface(scene, scene.tile(source));
        assertEquals(1, surface.waterfalls.size(), "The source must have one drop: " + source);
        return probes(surface);
    }

    private static List<Vector3> probes(BoardSurface surface) {
        var points = new ArrayList<Vector3>();
        for (var drop : surface.waterfalls) {
            Vector3[][] grid = surface.tile.frozen() ? GpuFrozenWaterfall.grid(surface, drop) : GpuWaterfall.grid(surface, drop);
            // The middle of the actual curtain avoids its lake slab and the receiving pool's moving surface.
            for (int column = 3; column + 3 < grid.length; column++) {
                for (int row = 4; row + 4 < grid[column].length; row++) {
                    points.add(new Vector3(grid[column][row]).add(grid[column + 1][row])
                          .add(grid[column][row + 1]).scl(1 / 3f));
                }
            }
        }
        assertTrue(points.size() > 10, "Exercise the interior of the waterfall curtain");
        return points;
    }

    private static int[] read(BoardCamera camera, List<Vector3> points) {
        Pixmap image = ScreenUtils.getFrameBufferPixmap(0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int[] result = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                Vector3 point = camera.camera.project(new Vector3(points.get(i)));
                int x = Math.round(point.x * image.getWidth() / Gdx.graphics.getWidth());
                int y = Math.round(point.y * image.getHeight() / Gdx.graphics.getHeight());
                assertTrue(x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight(),
                      "The visible curtain probe must stay inside the framebuffer: " + x + ", " + y);
                result[i] = image.getPixel(x, y);
            }
            return result;
        } finally { image.dispose(); }
    }

    private static int[] transparent(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera,
          List<Vector3> points) {
        output.begin(.04f, .05f, .06f, 1);
        terrain.renderTransparent(camera.camera);
        output.display();
        return read(camera, points);
    }

    /** Filter only this assertion's queued draws; retain the actual terrain pass, shader provider and globals. */
    private static int[] frozenOnly(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera,
          List<Vector3> points) throws ReflectiveOperationException {
        var field = GpuTerrain.class.getDeclaredField("batch");
        field.setAccessible(true);
        ModelBatch batch = (ModelBatch) field.get(terrain);
        RenderableSorter original = batch.getRenderableSorter();
        var sorter = ModelBatch.class.getDeclaredField("sorter");
        sorter.setAccessible(true);
        int[] drawn = { 0 };
        try {
            sorter.set(batch, (RenderableSorter) (view, parts) -> {
                for (int index = parts.size - 1; index >= 0; index--) {
                    if (GpuIceShader.mode(parts.get(index).material) != 6) { parts.removeIndex(index); }
                }
                drawn[0] += parts.size;
                original.sort(view, parts);
            });
            int[] pixels = transparent(output, terrain, camera, points);
            assertTrue(drawn[0] > 0, "The isolation oracle must draw actual frozen waterfall materials");
            return pixels;
        } finally {
            sorter.set(batch, original);
        }
    }

    /** Observe actual submitted production materials; the existing water foam and spray must both be present. */
    private static void renderImpact(GpuTerrain terrain, Runnable render) throws ReflectiveOperationException {
        var field = GpuTerrain.class.getDeclaredField("batch");
        field.setAccessible(true);
        ModelBatch batch = (ModelBatch) field.get(terrain);
        RenderableSorter original = batch.getRenderableSorter();
        var sorter = ModelBatch.class.getDeclaredField("sorter");
        sorter.setAccessible(true);
        int[] drawn = new int[2];
        try {
            sorter.set(batch, (RenderableSorter) (view, parts) -> {
                for (var part : parts) {
                    var water = part.material.get(GpuWaterShader.class, GpuWaterShader.TYPE);
                    if (water == null) { continue; }
                    if (water.mode == GpuWaterShader.Mode.SURFACE && !water.impacts.isEmpty()) { drawn[0]++; }
                    if (water.mode == GpuWaterShader.Mode.SPRAY) { drawn[1]++; }
                }
                original.sort(view, parts);
            });
            render.run();
            assertTrue(drawn[0] > 0, "The receiving pocket must submit the existing impacted water surface");
            assertTrue(drawn[1] > 0, "The flowing fall must retain its existing splash spray");
            System.out.printf("Liquid ice pocket: impactedSurface=%d spray=%d%n", drawn[0], drawn[1]);
        } finally {
            sorter.set(batch, original);
        }
    }

    private static void stationarity(String name, BoardCamera camera, List<Vector3> points,
          int[] mixed, int[] mixedAfter, int[] ice, int[] iceAfter) {
        int mixedChanges = 0, iceChanges = 0;
        for (int index = 0; index < mixed.length; index++) {
            if (ice[index] != iceAfter[index]) { iceChanges++; }
            if (mixed[index] == mixedAfter[index]) { continue; }
            mixedChanges++;
            Vector3 point = camera.camera.project(new Vector3(points.get(index)));
            int x = Math.round(point.x * Gdx.graphics.getBackBufferWidth() / Gdx.graphics.getWidth());
            int y = Math.round(point.y * Gdx.graphics.getBackBufferHeight() / Gdx.graphics.getHeight());
            System.out.printf("Frozen stationarity %s probe=%d world=%s framebuffer=(%d,%d) mixed=%08x->%08x ice=%08x->%08x%n",
                  name, index, points.get(index), x, y, mixed[index], mixedAfter[index], ice[index], iceAfter[index]);
        }
        System.out.printf("Frozen stationarity %s mixedChanged=%d iceOnlyChanged=%d probes=%d%n",
              name, mixedChanges, iceChanges, points.size());
    }

    private static double difference(int[] a, int[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift : new int[] { 24, 16, 8 }) { sum += Math.abs((a[i] >>> shift & 255) - (b[i] >>> shift & 255)); }
        }
        return sum / (a.length * 3);
    }
}
