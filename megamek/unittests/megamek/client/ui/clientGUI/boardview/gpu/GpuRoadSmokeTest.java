/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuRoadSmokeTest {
    @Test
    void capturesConcreteRetainingWallsBesideRoadRamps() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "roads");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(weather(0));
                try {
                    GpuRiverTerrainSmokeTest.tune(.94f, true);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.orbit(-15, -20);
                    camera.camera.zoom = .22f;
                    for (int rise : new int[] { 1, 2, 3 }) {
                        var scene = ramp(BoardRoad.Kind.PAVED, BoardScene.Surface.CONCRETE, rise);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0)
                              .lerp(BoardGeometry.center(new Coords(4, 4), rise), .5f));
                        for (int view = 0; view < 2; view++) {
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "concrete-road-walls-" + rise + "-" + view + ".png"));
                            camera.orbit(180, 0);
                        }
                    }
                    for (int exits : new int[] { 31, 63 }) {
                        var scene = BoardRoadRampTest.concreteJunction(exits);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        camera.camera.zoom = .21f;
                        camera.center(BoardGeometry.center(BoardRoadTest.CENTER, 2));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "concrete-roundabout-" + exits + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Concrete retaining wall review", failure.get()); }
    }

    @Test
    void capturesRoadMaterialsJunctionsAndRampsAndChecksLiveEdits() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "roads");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(weather(0));
                try {
                    GpuRiverTerrainSmokeTest.tune(.94f, true);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    var scene = roads(false);
                    terrain.update(scene);
                    terrain.animate(.5f, List.of());
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = .43f;
                        camera.center(BoardGeometry.center(BoardRoadTest.CENTER, 0));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, oblique ? "roads-oblique.png" : "roads-top.png"));
                    }
                    camera.camera.zoom = .19f;
                    camera.center(BoardGeometry.center(new Coords(2, 4), 0));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "junction-close.png"));
                    camera.camera.zoom = .17f;
                    camera.center(BoardGeometry.center(new Coords(3, 4), 0));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mixed-branch.png"));
                    var edited = roads(true);
                    terrain.update(edited);
                    terrain.animate(.5f, List.of());
                    frame.render(terrain, camera, edited);
                    byte[] changed = screen();
                    var fresh = new GpuTerrain();
                    try {
                        fresh.update(edited);
                        fresh.animate(.5f, List.of());
                        frame.render(fresh, camera, edited);
                        byte[] rebuilt = screen();
                        long difference = 0;
                        for (int i = 0; i < changed.length; i++) {
                            difference += Math.abs(Byte.toUnsignedInt(changed[i]) - Byte.toUnsignedInt(rebuilt[i]));
                        }
                        assertTrue(difference / (double) changed.length < .1, "Road edits must match a clean rebuild");
                    } finally { fresh.dispose(); }
                    frame.configure(weather(1));
                    frame.render(terrain, camera, edited);
                    GpuReviewFrame.save(new File(output, "junction-wet.png"));
                    frame.configure(weather(0));
                    camera.setIsometric(false);
                    camera.camera.zoom = .115f;
                    camera.center(BoardGeometry.center(BoardRoadTest.CENTER, 0));
                    for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.ALLEY, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                        var ends = ends(kind);
                        terrain.update(ends);
                        terrain.animate(.5f, List.of());
                        frame.render(terrain, camera, ends);
                        GpuReviewFrame.save(new File(output, "road-end-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                        byte[] mapped = screen();
                        terrain.setNormalMaps(false);
                        frame.render(terrain, camera, ends);
                        assertTrue(roadDifference(mapped, screen()) > .05, "The " + kind + " normal map must affect the road pixels");
                        terrain.setNormalMaps(true);
                        frame.configure(weather(1));
                        frame.render(terrain, camera, ends);
                        assertTrue(roadDifference(mapped, screen()) > .5, "Wet " + kind + " must respond to lighting");
                        GpuReviewFrame.save(new File(output, "road-end-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-wet.png"));
                        frame.configure(weather(0));
                    }
                    camera.setIsometric(true);
                    camera.center(BoardGeometry.center(BoardRoadTest.CENTER, 0)
                          .lerp(BoardGeometry.center(BoardRoadTest.CENTER.translated(3), 0), .5f));
                    for (var pair : List.of(List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.GRAVEL),
                          List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL), List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.PAVED))) {
                        var change = materialChange(pair.getFirst(), pair.getLast());
                        terrain.update(change);
                        terrain.animate(.5f, List.of());
                        frame.render(terrain, camera, change);
                        GpuReviewFrame.save(new File(output, "road-change-" + pair.getFirst() + "-" + pair.getLast() + ".png"));
                        if (pair.getFirst() == BoardRoad.Kind.DIRT && pair.getLast() == BoardRoad.Kind.PAVED) {
                            camera.setIsometric(false);
                            frame.render(terrain, camera, change);
                            GpuReviewFrame.save(new File(output, "road-change-dirt-asphalt-top.png"));
                            checkDirtCarry(camera);
                            camera.setIsometric(true);
                        }
                    }
                    camera.setIsometric(false);
                    camera.camera.zoom = .115f;
                    camera.center(BoardGeometry.center(BoardRoadTest.CENTER, 0));
                    for (var kind : List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                        var unsealed = materialChange(kind, kind);
                        terrain.update(unsealed);
                        terrain.animate(.5f, List.of());
                        frame.render(terrain, camera, unsealed);
                        GpuReviewFrame.save(new File(output, "unpaved-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                        frame.configure(weather(1));
                        camera.setIsometric(true);
                        frame.render(terrain, camera, unsealed);
                        GpuReviewFrame.save(new File(output, "unpaved-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-wet.png"));
                        frame.configure(weather(0));
                        camera.setIsometric(false);
                    }
                    var bridge = bridge();
                    terrain.update(bridge);
                    terrain.animate(.5f, List.of());
                    camera.setIsometric(true);
                    camera.camera.zoom = .24f;
                    camera.center(BoardGeometry.center(BoardRoadTest.CENTER, 0));
                    frame.render(terrain, camera, bridge);
                    GpuReviewFrame.save(new File(output, "bridge-approaches.png"));
                    camera.camera.zoom = .13f;
                    camera.orbit(-15, -20);
                    camera.center(BoardGeometry.center(new Coords(4, 3), 0)
                          .lerp(BoardGeometry.center(new Coords(4, 4), 1), .5f));
                    for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                        var ramp = ramp(kind);
                        terrain.update(ramp);
                        terrain.animate(.5f, List.of());
                        frame.render(terrain, camera, ramp);
                        GpuReviewFrame.save(new File(output, "road-ramp-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                    }
                    for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.ROCK,
                          BoardScene.Surface.SNOW)) {
                        var embankment = ramp(BoardRoad.Kind.PAVED, family, family == BoardScene.Surface.ROCK ? 3 : 2);
                        terrain.update(embankment);
                        terrain.animate(.5f, List.of());
                        camera.camera.zoom = .17f;
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0)
                              .lerp(BoardGeometry.center(new Coords(4, 4), family == BoardScene.Surface.ROCK ? 3 : 2), .5f));
                        frame.render(terrain, camera, embankment);
                        GpuReviewFrame.save(new File(output, "road-earthworks-" + family.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                    }
                    for (int layout = 0; layout < 3; layout++) {
                        int[] rises = layout == 0 ? new int[] { 2, 2, 2, 2, 2, 2 }
                              : layout == 1 ? new int[] { -2, -2, -2, -2, -2, -2 } : new int[] { -2, 1, 2, -1, 0, 2 };
                        var junction = BoardRoadRampTest.junction(BoardRoadTest.CENTER, rises);
                        terrain.update(junction);
                        terrain.animate(.5f, List.of());
                        camera.camera.zoom = .2f;
                        camera.center(BoardGeometry.center(BoardRoadTest.CENTER, 2));
                        frame.render(terrain, camera, junction);
                        GpuReviewFrame.save(new File(output, "road-six-way-" + layout + ".png"));
                    }
                    for (int exits : new int[] { 31, 63 }) {
                        for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                            var junction = BoardSurfaceBlendTest.scene(c -> {
                                int mask = c.equals(BoardRoadTest.CENTER) ? exits : 0;
                                for (int d = 0; d < 6; d++) {
                                    if ((exits & (1 << d)) != 0 && c.equals(BoardRoadTest.CENTER.translated(d))) {
                                        mask = (1 << d) | (1 << ((d + 3) % 6));
                                    }
                                }
                                return BoardRoadTest.tile(c, mask == 0 ? BoardRoad.Kind.NONE : kind,
                                      mask, 0, BoardScene.Surface.GRASS);
                            });
                            terrain.update(junction);
                            terrain.animate(.5f, List.of());
                            camera.setIsometric(false);
                            camera.camera.zoom = .16f;
                            camera.center(BoardGeometry.center(BoardRoadTest.CENTER, 0));
                            frame.render(terrain, camera, junction);
                            GpuReviewFrame.save(new File(output, "roundabout-" + exits + "-" + kind + ".png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Road surface review", failure.get()); }
    }

    private static BoardAtmosphere.Settings weather(float rain) {
        return new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0,
              new BoardAtmosphere.Effects(rain, 0, 0, 0, 0, 0, 0));
    }

    private static BoardScene ends(BoardRoad.Kind kind) {
        return BoardSurfaceBlendTest.scene(c -> {
            boolean road = c.getX() == BoardRoadTest.CENTER.getX() && c.getY() >= BoardRoadTest.CENTER.getY();
            int exits = c.equals(BoardRoadTest.CENTER) ? 8 : 9;
            return BoardRoadTest.tile(c, road ? kind : BoardRoad.Kind.NONE, road ? exits : 0, 0, BoardScene.Surface.GRASS);
        });
    }

    private static BoardScene ramp(BoardRoad.Kind kind) {
        return ramp(kind, BoardScene.Surface.GRASS, 1);
    }

    private static BoardScene ramp(BoardRoad.Kind kind, BoardScene.Surface family, int level) {
        return BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.getX() == 4 ? kind : BoardRoad.Kind.NONE, c.getX() == 4 ? 9 : 0,
              c.getY() >= 4 ? level : 0, family));
    }

    private static BoardScene materialChange(BoardRoad.Kind from, BoardRoad.Kind to) {
        return BoardSurfaceBlendTest.scene(c -> {
            boolean road = c.getX() == BoardRoadTest.CENTER.getX();
            return BoardRoadTest.tile(c, road ? c.getY() <= BoardRoadTest.CENTER.getY() ? from : to : BoardRoad.Kind.NONE,
                  road ? 9 : 0, 0, BoardScene.Surface.GRASS);
        });
    }

    static BoardScene roads(boolean edited) {
        Map<Coords, Integer> exits = new HashMap<>();
        Map<Coords, BoardRoad.Kind> kinds = new HashMap<>();
        route(exits, kinds, new Coords(2, 1), BoardRoad.Kind.PAVED, 3, 3, 3, 3, 3, 3);
        route(exits, kinds, new Coords(6, 1), BoardRoad.Kind.DIRT, 3, 3, 3, 3, 3, 3);
        route(exits, kinds, new Coords(1, 6), BoardRoad.Kind.GRAVEL, 1, 1, 1, 1, 1, 1);
        route(exits, kinds, new Coords(2, 4), BoardRoad.Kind.ALLEY, 2, 2, 1, 1);
        return BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              edited && c.equals(new Coords(2, 4)) ? BoardRoad.Kind.GRAVEL : kinds.getOrDefault(c, BoardRoad.Kind.NONE),
              exits.getOrDefault(c, 0), c.getY() < 3 ? 1 : 0,
              c.getY() < 3 ? BoardScene.Surface.SNOW : c.getY() > 5 ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS));
    }

    private static BoardScene bridge() {
        return BoardSurfaceBlendTest.scene(c -> {
            if (c.getY() == 4) {
                Hex hex = new Hex(0);
                hex.addTerrain(new Terrain(Terrains.WATER, 1));
                if (c.getX() == 4) {
                    hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 9));
                    hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
                }
                var tile = BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, 0, 1, 0);
                return new BoardScene.Tile(c, 0, 1, false, 0, tile.surface(), tile.ground(), null, null, null, null,
                      BoardFeatures.capture(hex, c, Map.of()), List.of(), BoardLiquid.WATER, null, true);
            }
            return BoardRoadTest.tile(c, c.getX() == 4 ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.getX() == 4 ? 9 : 0, 0, BoardScene.Surface.GRASS);
        });
    }

    private static void route(Map<Coords, Integer> exits, Map<Coords, BoardRoad.Kind> kinds, Coords start,
          BoardRoad.Kind kind, int... directions) {
        Coords at = start;
        for (int direction : directions) {
            Coords next = at.translated(direction);
            exits.merge(at, 1 << direction, (a, b) -> a | b);
            exits.merge(next, 1 << ((direction + 3) % 6), (a, b) -> a | b);
            kinds.putIfAbsent(at, kind);
            kinds.putIfAbsent(next, kind);
            at = next;
        }
    }

    private static byte[] screen() {
        var pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            byte[] result = new byte[pixels.getPixels().remaining()];
            pixels.getPixels().get(result);
            return result;
        } finally { pixels.dispose(); }
    }

    /** Dirt transfer must continue in both tyre bands on the asphalt side, with exposed asphalt between them. */
    private static void checkDirtCarry(BoardCamera camera) {
        byte[] pixels = screen();
        Vector3 seam = BoardGeometry.center(BoardRoadTest.CENTER, 0)
              .lerp(BoardGeometry.center(BoardRoadTest.CENTER.translated(3), 0), .5f);
        float scale = BoardGeometry.hexScale(), traces = 0, between = 0;
        for (float distance : new float[] { 4, 6, 8 }) {
            between += brightness(pixels, camera.camera.project(new Vector3(seam).add(0, -distance * scale, .1f)));
            for (int side : new int[] { -1, 1 }) {
                traces += brightness(pixels, camera.camera.project(new Vector3(seam)
                      .add(side * BoardRoad.WHEEL_OFFSET * scale, -distance * scale, .1f))) / 2;
            }
        }
        assertTrue((traces - between) / 3 > 4, "Dirt should continue on asphalt at the fixed wheel offsets");
    }

    private static float brightness(byte[] pixels, Vector3 point) {
        long total = 0;
        int width = Gdx.graphics.getBackBufferWidth();
        for (int y = Math.round(point.y) - 2; y <= Math.round(point.y) + 2; y++) {
            for (int x = Math.round(point.x) - 2; x <= Math.round(point.x) + 2; x++) {
                int index = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) { total += Byte.toUnsignedInt(pixels[index + channel]); }
            }
        }
        return total / 75f;
    }

    /** Central road pixels only: surrounding terrain also changes when normal mapping is toggled. */
    private static float roadDifference(byte[] a, byte[] b) {
        long sum = 0;
        int count = 0, width = Gdx.graphics.getBackBufferWidth();
        for (int y = 100; y < 450; y++) {
            for (int x = 625; x < 655; x++) {
                int index = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    sum += Math.abs(Byte.toUnsignedInt(a[index + channel]) - Byte.toUnsignedInt(b[index + channel]));
                    count++;
                }
            }
        }
        return sum / (float) count;
    }
}
