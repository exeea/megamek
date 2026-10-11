/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Authored bridge geometry meets the actual road triangles without using level height for its dimensions. */
@Tag("on-demand")
class GpuBridgeSmokeTest {
    private static final Coords CENTER = BoardSurfaceBlendTest.CENTER;

    @Test
    void koziceRaisedBridgeApproachesRenderWithoutHoles() throws Exception {
        var scene = GpuRoadSourceTest.scene("unofficial/Strategoslevel3/32x17 (CDS) Kozice Valley Grain Mills.board");
        var at = new Coords(19, 8);
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "bridges");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.camera.zoom = .15f;
                    camera.center(BoardGeometry.center(at, 0));
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    terrain.animate(0, List.of());
                    for (int azimuth : new int[] { 70, 250 }) {
                        camera.orbit(azimuth - camera.azimuth(), 0);
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "kozice-bridge-" + azimuth + ".png"));
                    }
                    camera.orbit(70 - camera.azimuth(), 0);
                    camera.camera.zoom = .075f;
                    for (int y : new int[] { 7, 9 }) {
                        camera.center(BoardGeometry.center(new Coords(19, y), 0).lerp(BoardGeometry.center(at, 0), .5f));
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "kozice-ramp-" + (y + 1) + ".png"));
                        for (float t : new float[] { .35f, .65f }) {
                            var point = BoardGeometry.center(at, 0).lerp(BoardGeometry.center(new Coords(19, y), 0), t);
                            var hit = terrain.selectionHit(scene, new Ray(new Vector3(point.x, point.y, 200), new Vector3(0, 0, -1)));
                            assertNotNull(hit, "Both halves of the installed ramp must be pickable");
                            assertEquals(t < .5f ? at : new Coords(19, y), hit.coords());
                            assertEquals((1.5f - 2 * t) * BoardGeometry.level() + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale(),
                                  200 - Math.sqrt(hit.distance()), .02f, "Picking must follow the rendered solid, above the bank");
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Kozice raised bridge approaches", failure.get()); }
    }

    @Test
    void forestsEndBridgeMeetsTheRoadsOnBothBanks() throws Exception {
        var scene = GpuRoadSourceTest.scene("unofficial/Strategoslevel3/32x17 (CW) Forests End  - Road.board");
        var at = new Coords(8, 8);
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "bridges");
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
                    terrain.update(scene);
                    terrain.animate(0, List.of());
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = .16f;
                        camera.center(BoardGeometry.center(at, 0));
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "forests-end-" + (oblique ? "oblique" : "top") + ".png"));
                        checkMarkings(scene, camera, at);
                    }
                    for (int direction : new int[] { 2, 5 }) { checkJoin(scene, terrain, at, direction); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Forests End bridge approach", failure.get()); }
    }

    /** Check actual rendered paint and exposed asphalt between dashes in both camera views. */
    private static void checkMarkings(BoardScene scene, BoardCamera camera, Coords at) {
        var tile = scene.tile(at);
        var bridge = tile.features().stream().filter(f -> f.asset().equals("bridge")).findFirst().orElseThrow();
        var paint = BoardRoad.clearance(at, bridge.bridgeExits()).markings(at, bridge.bridgeExits());
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(at, tile.elevation() + bridge.elevation()).add(0, 0, .065f * scale);
        var along = BoardGeometry.center(at.translated(2), 0).sub(BoardGeometry.center(at, 0)).nor();
        var across = new Vector3(-along.y, along.x, 0);
        var pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        int marked = 0, gaps = 0;
        float paintContrast = 0, gapContrast = 0;
        try {
            for (float distance = -30; distance <= 30; distance += 1) {
                boolean dash = paint.contains(along.x * distance, along.y * distance);
                // Skip antialiased ends of each dash.
                if (paint.contains(along.x * (distance - 1), along.y * (distance - 1)) != dash
                      || paint.contains(along.x * (distance + 1), along.y * (distance + 1)) != dash) { continue; }
                var point = new Vector3(center).mulAdd(along, distance * scale);
                float contrast = brightness(pixels, camera.camera.project(new Vector3(point)))
                      - (brightness(pixels, camera.camera.project(new Vector3(point).mulAdd(across, 2 * scale)))
                      + brightness(pixels, camera.camera.project(new Vector3(point).mulAdd(across, -2 * scale)))) / 2;
                if (dash) { marked++; paintContrast += contrast; }
                else { gaps++; gapContrast += contrast; }
            }
            assertTrue(marked >= 12 && gaps >= 10, "Sample several complete bridge dashes and gaps");
            assertTrue(paintContrast / marked > 20, "Bridge dashes must be brighter than adjacent asphalt: " + paintContrast / marked);
            assertTrue(Math.abs(gapContrast / gaps) < 12, "Asphalt must remain exposed between bridge dashes: " + gapContrast / gaps);
        } finally { pixels.dispose(); }
    }

    private static float brightness(Pixmap pixels, Vector3 screen) {
        int rgba = pixels.getPixel(Math.round(screen.x), Math.round(screen.y));
        return ((rgba >>> 24) + ((rgba >>> 16) & 255) + ((rgba >>> 8) & 255)) / 3f;
    }

    @Test
    void bridgeMaterialsFollowApproachesAndDistantEdits() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "bridges");
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
                    camera.setIsometric(false);
                    camera.camera.zoom = .16f;
                    camera.center(BoardGeometry.center(CENTER, 0));
                    var plain = new EnumMap<BoardRoad.Kind, int[]>(BoardRoad.Kind.class);
                    for (var pair : List.of(List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.PAVED),
                          List.of(BoardRoad.Kind.ALLEY, BoardRoad.Kind.ALLEY),
                          List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.DIRT),
                          List.of(BoardRoad.Kind.GRAVEL, BoardRoad.Kind.GRAVEL),
                          List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT),
                          List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.GRAVEL),
                          List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL))) {
                        var scene = BoardBridgeMaterialsTest.straight(CENTER, 2, 1, pair.getFirst(), pair.getLast());
                        terrain.update(scene);
                        terrain.animate(0, List.of());
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        String name = pair.getFirst().name().toLowerCase(java.util.Locale.ROOT) + "-"
                              + pair.getLast().name().toLowerCase(java.util.Locale.ROOT);
                        GpuReviewFrame.save(new File(output, "material-" + name + "-top.png"));
                        int[] pixels = deckPixels(camera, CENTER, 2);
                        if (pair.getFirst() == pair.getLast()) { plain.put(pair.getFirst(), pixels); }
                        else {
                            var expected = pair.getFirst() == BoardRoad.Kind.DIRT ? BoardRoad.Kind.GRAVEL : pair.getFirst();
                            float error = difference(plain.get(expected), pixels);
                            assertTrue(error < 1,
                                  "Mixed approaches must keep the best surface at the deck centre: " + name + ", error " + error);
                        }
                        // Banked spans carry the authored terminal block, which stands above the rails.
                        assertEquals(GpuRoads.SURFACE_LIFT + Math.max(2.5f, BoardBridgeFooting.terminalHeight()),
                              terrain.roofBounds(CENTER).max.z, .001f);
                    }
                    assertTrue(difference(plain.get(BoardRoad.Kind.PAVED), plain.get(BoardRoad.Kind.DIRT)) > 8,
                          "Dirt approaches must visibly resurface the deck");
                    assertTrue(difference(plain.get(BoardRoad.Kind.PAVED), plain.get(BoardRoad.Kind.GRAVEL)) > 8,
                          "Gravel approaches must visibly resurface the deck");
                    assertTrue(difference(plain.get(BoardRoad.Kind.DIRT), plain.get(BoardRoad.Kind.GRAVEL)) > 5,
                          "Dirt and gravel keep their distinct textures");

                    var start = new Coords(4, 1);
                    var far = new Coords(4, 11);
                    var before = BoardBridgeMaterialsTest.straight(start, 3, 12, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL);
                    terrain.update(before);
                    camera.center(BoardGeometry.center(far, 0));
                    GpuTerrainLodSmokeTest.settle(terrain, null, before, camera);
                    frame.render(terrain, camera, before);
                    int[] gravel = deckPixels(camera, far, 3);
                    var edited = BoardBridgeMaterialsTest.straight(start, 3, 12, BoardRoad.Kind.PAVED, BoardRoad.Kind.GRAVEL);
                    terrain.update(edited);
                    GpuTerrainLodSmokeTest.settle(terrain, null, edited, camera);
                    frame.render(terrain, camera, edited);
                    int[] live = deckPixels(camera, far, 3);
                    GpuReviewFrame.save(new File(output, "material-long-span-edited.png"));
                    assertTrue(difference(gravel, live) > 5,
                          "Upgrading the distant approach must repaint the span even beside the unchanged gravel approach");
                    var fresh = new GpuTerrain();
                    try {
                        fresh.update(edited);
                        fresh.animate(0, List.of());
                        GpuTerrainLodSmokeTest.settle(fresh, null, edited, camera);
                        frame.render(fresh, camera, edited);
                        assertTrue(difference(live, deckPixels(camera, far, 3)) < 1,
                              "Reused chunks must render the same deck materials as a fresh build");
                    } finally { fresh.dispose(); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Bridge road materials", failure.get()); }
    }

    /** Sample exposed material at the deck centre, avoiding paint, wheel wear, rails, and bank transitions. */
    private static int[] deckPixels(BoardCamera camera, Coords at, int direction) {
        var center = BoardGeometry.center(at, 0).add(0, 0, .1f * BoardGeometry.hexScale());
        var along = BoardGeometry.center(at.translated(direction), 0).sub(BoardGeometry.center(at, 0)).nor();
        var across = new Vector3(-along.y, along.x, 0);
        var pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        int[] result = new int[12];
        int index = 0;
        try {
            for (float distance : new float[] { -12, 0, 12 }) {
                for (float lane : new float[] { -6, -5, 5, 6 }) {
                    var point = new Vector3(center).mulAdd(along, distance * BoardGeometry.hexScale())
                          .mulAdd(across, lane * BoardGeometry.hexScale());
                    var screen = camera.camera.project(point);
                    result[index++] = pixels.getPixel(Math.round(screen.x), Math.round(screen.y));
                }
            }
            return result;
        } finally { pixels.dispose(); }
    }

    private static float difference(int[] a, int[] b) {
        float total = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift : new int[] { 24, 16, 8 }) { total += Math.abs(((a[i] >>> shift) & 255) - ((b[i] >>> shift) & 255)); }
        }
        return total / (3 * a.length);
    }

    @Test
    void deckJoinsRoadsAndRaisedSidesKeepTheirSize() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "bridges");
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BoardGeometry.Tuning previous = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                GpuReviewFrame frame = new GpuReviewFrame(weather);
                try {
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    for (int height : new int[] { 12, 36 }) {
                        BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, height, previous.gridShade()));
                        // A span without attached roads is a natural bridge (docs/gpu-roads.md) with no GLB deck.
                        for (int exits = 1; exits < 64; exits++) {
                            BoardScene scene = scene(exits);
                            terrain.update(scene);
                            var bounds = terrain.roofBounds(CENTER);
                            assertNotNull(bounds);
                            // The authored deck kit (kerbs, roundabouts, terminal block) sets the exact underside and rail
                            // heights; they must stay near the slab's 1.5 below and 2.5 above, whatever the level setting.
                            assertEquals(GpuRoads.SURFACE_LIFT - 1.5f, bounds.min.z, .15f);
                            assertEquals(GpuRoads.SURFACE_LIFT + Math.max(2.5f, BoardBridgeFooting.terminalHeight()), bounds.max.z, .15f,
                                  "Rail and terminal height must not follow the terrain-level setting");
                            for (int direction = 0; direction < 6; direction++) {
                                if ((exits & (1 << direction)) != 0) { checkJoin(scene, terrain, CENTER, direction); }
                            }
                            if (height == 12 && List.of(0, 1, 3, 5, 9, 11, 21, 27, 31, 63).contains(exits)) {
                                camera.fit(scene);
                                camera.camera.zoom = .16f;
                                camera.center(BoardGeometry.center(CENTER, 0));
                                terrain.animate(0, List.of());
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "exits-" + exits + "-oblique.png"));
                                camera.setIsometric(false);
                                camera.center(BoardGeometry.center(CENTER, 0));
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "exits-" + exits + "-top.png"));
                                camera.setIsometric(true);
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    BoardGeometry.tune(previous);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Bridge road join", failure.get()); }
    }

    /** Each case of the piers board: its label, where the camera looks, and how many joints of each hex carry a pier. */
    private record Case(String label, Coords view, Map<Coords, Integer> joints) { }

    private static final List<Case> CASES = List.of(
          // Oanhu: paved decks at the water's surface over water:1, between roads.
          new Case("water", new Coords(1, 3), Map.of(new Coords(1, 2), 1, new Coords(1, 3), 2, new Coords(1, 4), 1)),
          new Case("short", new Coords(4, 3), Map.of(new Coords(4, 2), 1, new Coords(4, 3), 2, new Coords(4, 4), 1)),
          new Case("tall", new Coords(7, 3), Map.of(new Coords(7, 2), 1, new Coords(7, 3), 2, new Coords(7, 4), 1)),
          // Decks one level apart: both are graded, and so is the cap between them.
          new Case("graded", new Coords(10, 2), Map.of(new Coords(10, 2), 1, new Coords(10, 3), 1)),
          // A rock arch never has piers (batch 7b): its hexes store the natural type.
          new Case("natural", new Coords(13, 3), Map.of()),
          // A built deck without a road: plain asphalt, a pavement bank (no apron) north, a bare one (apron) south.
          new Case("plain", new Coords(4, 7), Map.of(new Coords(4, 7), 1, new Coords(4, 8), 1)),
          new Case("single", new Coords(16, 2), Map.of()),
          new Case("ground", new Coords(16, 7), Map.of()),
          new Case("junction", new Coords(10, 8), Map.of(new Coords(10, 7), 1, new Coords(10, 8), 3,
                new Coords(10, 8).translated(2), 1, new Coords(10, 8).translated(4), 1)));

    /** Every deck kind with its Pillars toggle on, except the natural arch, a column per case (see {@link #CASES}). */
    private static Board piers() {
        Board board = Board.createEmptyBoard(19, 11);
        String deck = "bridge:1:09;bridge_cf:40;bridge_elev:";
        java.util.function.BiConsumer<Coords, Hex> put = (at, hex) -> {
            if (hex.containsTerrain(Terrains.BRIDGE)) {
                hex.setAppearance(Map.of("bridge", at.getX() == 13 ? megamek.common.board.HexAppearance.NATURAL_BRIDGE
                      : megamek.common.board.HexAppearance.PILLARS));
            }
            board.setHex(at, hex);
        };
        int[][] spans = { { 1, 0, 0 }, { 4, 1, 1 }, { 7, 6, 6 } };
        for (int[] span : spans) {
            put.accept(new Coords(span[0], 1), new Hex(span[2], "road:1:09", ""));
            for (int y = 2; y <= 4; y++) {
                put.accept(new Coords(span[0], y), new Hex(0, (span[0] == 1 ? "water:1;" : "") + deck + span[1], ""));
            }
            put.accept(new Coords(span[0], 5), new Hex(span[2], "road:1:09", ""));
        }
        put.accept(new Coords(10, 1), new Hex(1, "road:1:09", ""));
        put.accept(new Coords(10, 2), new Hex(0, deck + 1, ""));
        put.accept(new Coords(10, 3), new Hex(0, deck + 2, ""));
        put.accept(new Coords(10, 4), new Hex(2, "road:1:09", ""));
        put.accept(new Coords(13, 1), new Hex(2, "", ""));
        for (int y = 2; y <= 4; y++) { put.accept(new Coords(13, y), new Hex(0, deck + 2, "")); }
        put.accept(new Coords(13, 5), new Hex(2, "", ""));
        put.accept(new Coords(4, 6), new Hex(2, "pavement:1", ""));
        for (int y = 7; y <= 8; y++) { put.accept(new Coords(4, y), new Hex(0, deck + 2, "")); }
        put.accept(new Coords(4, 9), new Hex(2, "", ""));
        put.accept(new Coords(16, 1), new Hex(1, "road:1:09", ""));
        put.accept(new Coords(16, 2), new Hex(0, deck + 1, ""));
        put.accept(new Coords(16, 3), new Hex(1, "road:1:09", ""));
        put.accept(new Coords(16, 6), new Hex(0, "road:1:09", ""));
        for (int y = 7; y <= 8; y++) { put.accept(new Coords(16, y), new Hex(0, deck + 0, "")); }
        put.accept(new Coords(16, 9), new Hex(0, "road:1:09", ""));
        Coords centre = new Coords(10, 8);
        put.accept(new Coords(10, 6), new Hex(0, "road:1:09", ""));
        put.accept(new Coords(10, 7), new Hex(0, "water:1;" + deck + 0, ""));
        put.accept(centre, new Hex(0, "water:1;bridge:1:21;bridge_cf:40;bridge_elev:0", ""));
        for (int d : new int[] { 2, 4 }) {
            put.accept(centre.translated(d), new Hex(0, "water:1;bridge:1:" + (1 << (d + 3) % 6) + ";bridge_cf:40;bridge_elev:0", ""));
        }
        return board;
    }

    /** The case's own hexes of the piers board, nothing else, so a low side view sees only this bridge. */
    private static Board only(Board all, Case shown) {
        var own = new java.util.HashSet<>(shown.joints().keySet());
        own.add(shown.view());
        Board board = Board.createEmptyBoard(all.getWidth(), all.getHeight());
        for (int x = 0; x < all.getWidth(); x++) {
            for (int y = 0; y < all.getHeight(); y++) {
                Coords at = new Coords(x, y);
                if (own.stream().anyMatch(hex -> hex.distance(at) <= 2)) { board.setHex(at, all.getHex(at)); }
            }
        }
        return board;
    }

    @Test
    void piersReachTheDrawnDeckOnEveryDeckKind() throws Exception {
        Board all = piers();
        Map<String, BoardScene> scenes = new java.util.LinkedHashMap<>();
        for (Case shown : CASES) { scenes.put(shown.label(), GpuLegacyImportSmokeTest.scene(only(all, shown))); }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "bridges");
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BoardGeometry.Tuning previous = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                GpuReviewFrame frame = new GpuReviewFrame(weather);
                try {
                    for (int levelHeight : new int[] { 18, 30 }) {
                        BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, levelHeight, previous.gridShade()));
                        for (Case shown : CASES) {
                            var scene = scenes.get(shown.label());
                            GpuTerrain terrain = new GpuTerrain();
                            try {
                                var camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                camera.setIsometric(true);
                                camera.fit(scene);
                                frame.prepare(terrain, camera, scene);
                                terrain.update(scene);
                                terrain.animate(0, List.of());
                                for (boolean side : new boolean[] { false, true }) {
                                    camera.setIsometric(true);
                                    // The side view looks across the N-S spans, low over the ground.
                                    if (side) { camera.orbit(45, 78 - camera.tilt()); }
                                    camera.camera.zoom = .14f;
                                    // The tall deck is six levels up: its side view frames the caps under the slab.
                                    camera.center(BoardGeometry.center(shown.view(), side && shown.label().equals("tall") ? 5 : 1));
                                    GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                                    frame.render(terrain, camera, scene);
                                    GpuReviewFrame.save(new File(output, "piers-" + shown.label() + "-lh" + levelHeight
                                          + (side ? "-side" : "-iso") + ".png"));
                                }
                                checkPiers(scene, terrain, shown);
                                if (shown.label().equals("tall")) { checkWalkable(scene, terrain); }
                            } finally { terrain.dispose(); }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    BoardGeometry.tune(previous);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Bridge piers", failure.get()); }
    }

    /** Captures of the truss design (b10-f1). */
    private static final File TRUSS = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "b10-f1");
    /** The piers board's water span is painted green; every other truss keeps its model's Oxide red. */
    private static final String GREEN = "#2e5233";
    /** A flat truss deck that carries a painted decal, which lies on its road surface. */
    private static final Coords DECAL = new Coords(16, 2);

    /** Every built span of {@code board} a truss with its piers kept, the water span (column 1) green. */
    private static Board truss(Board board) {
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Hex hex = board.getHex(x, y);
                if (!Boolean.TRUE.equals(megamek.common.board.HexAppearance.bridgeBuilt(hex.getAppearance()))) { continue; }
                hex.setAppearance(Map.of("bridge", megamek.common.board.HexAppearance.built(true,
                      megamek.common.board.HexAppearance.pillars(hex.getAppearance()),
                      x == 1 ? megamek.common.board.BoardDecoration.Colours.of(GREEN) : megamek.common.board.BoardDecoration.Colours.NONE)));
            }
        }
        board.getHex(DECAL).setDecorations(List.of(new megamek.common.board.BoardDecoration("deck-paint", "decal",
              "decal/damage/rubble-light-path", null, 0, 0, 0, false, 1,
              megamek.common.board.BoardDecoration.Placement.surface("bridge", "deck", 0), 1)));
        return board;
    }

    /**
     * The truss design on every deck kind of the piers board ({@link #CASES}): each exit draws exactly one of its two
     * alternatives, the joint where a deck continues and the end post at a bank, a road landing or an open end, and a ray
     * that meets only an undrawn alternative passes it (picking sees what is drawn); sloped decks draw their steel in the
     * span's colour; the piers stand as under the standard deck; a decal on a flat truss deck lies on its road surface,
     * not on the chords. Captures {@code b10-f1/truss-<case>-iso.png} and {@code -side.png}.
     */
    @Test
    void trussSpansDrawOneJointOrEndPerExitInTheirColour() throws Exception {
        Board all = truss(piers());
        Map<String, BoardScene> scenes = new java.util.LinkedHashMap<>();
        for (Case shown : CASES) { scenes.put(shown.label(), GpuLegacyImportSmokeTest.scene(only(all, shown))); }
        Files.createDirectories(TRUSS.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                GpuReviewFrame frame = new GpuReviewFrame(weather);
                try {
                    for (Case shown : CASES) {
                        var scene = scenes.get(shown.label());
                        GpuTerrain terrain = new GpuTerrain();
                        try {
                            var camera = new BoardCamera();
                            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                            camera.setIsometric(true);
                            camera.fit(scene);
                            frame.prepare(terrain, camera, scene);
                            terrain.update(scene);
                            terrain.animate(0, List.of());
                            for (boolean side : new boolean[] { false, true }) {
                                camera.setIsometric(true);
                                if (side) { camera.orbit(45, 78 - camera.tilt()); }
                                camera.camera.zoom = .14f;
                                camera.center(BoardGeometry.center(shown.view(), side && shown.label().equals("tall") ? 5 : 1));
                                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(TRUSS, "truss-" + shown.label() + (side ? "-side" : "-iso") + ".png"));
                            }
                            checkPiers(scene, terrain, shown);
                            checkTruss(scene, terrain, shown);
                            if (scene.tile(DECAL) != null && BoardBridge.feature(scene.tile(DECAL)) != null) { checkDeckPaint(scene, terrain); }
                        } finally { terrain.dispose(); }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Truss bridges", failure.get()); }
    }

    /**
     * The Trainjob's rail bridge made a blue truss in the editor (Built: Truss, then a colour: two undo steps), saved as
     * .board2 and loaded the game's way: the truss in its colour, the rail riding the deck between the trusses; opened
     * the editor's way it gives the same tiles, which the same renderer draws (b13). Capture
     * {@code b10-f1/truss-trainjob-game.png}.
     */
    @Test
    void trussCarriesTheTrainjobRail() throws Exception {
        Files.createDirectories(TRUSS.toPath());
        Coords deck = new Coords(37, 29);
        File board = new File(megamek.common.Configuration.boardsDir(), "unofficial/VictorMorson/48x48 The Trainjob.board");
        File file = new File(TRUSS, "trainjob-truss.board2");
        var session = new megamek.client.ui.boardeditor.BoardEditorSession();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                session.open(board.toPath());
                for (var command : List.of(new String[] { "SELECT_AT", deck.getX() + "," + deck.getY(), "" },
                      new String[] { "BRIDGE_DESIGN", "", "truss" }, new String[] { "BRIDGE_COLOUR", "", "#24407a" })) {
                    session.command(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                          megamek.client.ui.boardeditor.BoardEditorSession.Action.valueOf(command[0]), command[1], command[2]), null);
                }
                megamek.common.board.BoardFile.save(session.board(), file.toPath());
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        assertTrue(session.snapshot().canUndo(), session.snapshot().message());
        BoardScene scene;
        try (var fixture = GpuBoardFixture.create(megamek.common.board.BoardFile.loadSheet(file, false, true))) {
            javax.swing.SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        var tile = scene.tile(deck);
        assertTrue(BoardBridge.deck(scene, tile).truss(), "The span is a truss");
        assertEquals("#24407a", BoardBridge.deck(scene, tile).colours().slot(0), "in its colour");
        assertTrue(tile.features().stream().anyMatch(BoardRail::route), "with its rail");
        // The editor opens the saved file into the same tiles (its own open path, then the same renderer).
        var opened = new java.util.concurrent.FutureTask<>(() -> {
            var editor = new megamek.client.ui.boardeditor.BoardEditorSession();
            editor.open(file.toPath());
            try (var source = new GpuMapSource(editor.game(), null, editor)) { return source.takeFrame().scene(); }
        });
        javax.swing.SwingUtilities.invokeAndWait(opened);
        assertEquals(scene.tiles().stream().map(t -> t.withMarkings(null, List.of())).toList(),
              opened.get().tiles().stream().map(t -> t.withMarkings(null, List.of())).toList(), "One tile build");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                GpuTerrain terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    terrain.setGrass(false);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    camera.setIsometric(true);
                    camera.orbit(20 - camera.azimuth(), 45 - camera.tilt());
                    camera.center(BoardGeometry.center(deck, 2));
                    camera.zoom(.3f / camera.camera.zoom);
                    camera.update();
                    GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(TRUSS, "truss-trainjob-game.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Trainjob truss", failure.get()); }
    }

    /** The case's decks: one joint or end node per exit, picks on drawn nodes only, sloped steel in the span's colour. */
    private static void checkTruss(BoardScene scene, GpuTerrain terrain, Case shown) throws Exception {
        float s = BoardGeometry.hexScale();
        var hexes = new java.util.LinkedHashSet<>(shown.joints().keySet());
        hexes.add(shown.view());
        for (Coords at : hexes) {
            var tile = scene.tile(at);
            var deck = BoardBridge.deck(scene, tile);
            if (deck == null || deck.natural()) { continue; }
            String name = "truss " + shown.label() + " " + at.getBoardNum();
            assertTrue(deck.truss(), name + " is a truss");
            assertEquals(at.getX() == 1 ? GREEN : null, deck.colours().slot(0), name + ": the span's colour");
            for (int d = 0; d < 6; d++) {
                assertEquals(BoardBridge.connected(tile, scene.tile(at.translated(d)), d), (deck.joints() & (1 << d)) != 0,
                      name + ": a joint toward a joined deck only, " + d);
            }
            var nodes = BoardShape.loadNodes(deck.asset(), megamek.common.board.BoardDecoration.Colours.NONE);
            var drawn = BoardBridge.trussNodes(deck);
            assertEquals(1 + Integer.bitCount(deck.exits()), drawn.size(), name + ": the body and one node per exit");
            assertTrue(nodes.keySet().containsAll(drawn), name + ": " + drawn + " in " + nodes.keySet());
            assertEquals(1 + 2 * Integer.bitCount(deck.exits()), nodes.size(), name + ": two alternatives per exit");
            if (deck.sloped()) {
                var steel = ((BoardBridge.Shape) field(tileMesh(terrain, at), "bridgeShape")).facets().stream()
                      .filter(f -> f.part() == BoardBridge.Part.STEEL).toList();
                assertFalse(steel.isEmpty(), name + ": a sloped truss draws its steel");
                var expected = com.badlogic.gdx.graphics.Color.valueOf(at.getX() == 1 ? GREEN : "732417");
                for (var facet : steel) {
                    assertTrue(Math.abs(facet.colour().r - expected.r) < .01f && Math.abs(facet.colour().g - expected.g) < .01f
                          && Math.abs(facet.colour().b - expected.b) < .01f, name + ": steel in the span's colour, " + facet.colour());
                }
                System.out.printf("TRUSS %s: sloped, %d steel facets%n", name, steel.size());
                continue;
            }
            // A flat deck's GLB: a ray onto each undrawn alternative's faces, along their normal, never stops on them
            // (where a drawn face lies at the same spot, the spot is skipped).
            var origin = new Vector3(BoardGeometry.centerX(at), BoardGeometry.centerY(at), BoardBridge.deckZ(tile));
            float reach = 30 * s, tolerance = .05f * s;
            int passed = 0;
            for (var node : nodes.entrySet()) {
                if (drawn.contains(node.getKey())) { continue; }
                for (var polygon : node.getValue().polygons()) {
                    var c = new Vector3(polygon.points()[0]).add(polygon.points()[1]).add(polygon.points()[2]).scl(s / 3).add(origin);
                    var ray = new Ray(new Vector3(polygon.normal()).scl(reach).add(c), new Vector3(polygon.normal()).scl(-1));
                    var point = new Vector3();
                    boolean shared = drawn.stream().flatMap(id -> nodes.get(id).polygons().stream()).anyMatch(p ->
                          Intersector.intersectRayTriangle(ray, world(p.points()[0], s, origin), world(p.points()[1], s, origin),
                                world(p.points()[2], s, origin), point) && Math.abs(point.dst(ray.origin) - reach) < tolerance);
                    if (shared) { continue; }
                    var pick = terrain.selectionHit(scene, ray);
                    assertTrue(pick == null || Math.abs((float) Math.sqrt(pick.distance()) - reach) > tolerance,
                          name + ": the undrawn " + node.getKey() + " is not picked at " + c);
                    passed++;
                }
            }
            System.out.printf("TRUSS %s: drawn %s, %d faces of undrawn alternatives looked through%n", name, drawn, passed);
            assertTrue(deck.exits() == 0 || passed > 0, name + ": an undrawn alternative was probed");
        }
    }

    private static Vector3 world(Vector3 model, float scale, Vector3 origin) { return new Vector3(model).scl(scale).add(origin); }

    private static Object tileMesh(GpuTerrain terrain, Coords at) throws Exception {
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            Object mesh = ((Map<?, ?>) field(chunk, "tileMeshes")).get(at);
            if (mesh != null) { return mesh; }
        }
        throw new AssertionError("No tile mesh at " + at);
    }

    /** The decal on the flat truss deck lies on its road surface, at the deck plane, never on the rails' or chords' tops. */
    private static void checkDeckPaint(BoardScene scene, GpuTerrain terrain) throws Exception {
        var painted = (List<?>) field(tileMesh(terrain, DECAL), "paint");
        assertEquals(1, painted.size(), "The deck carries its decal");
        float deck = BoardBridge.deckZ(scene.tile(DECAL));
        float highest = Float.NEGATIVE_INFINITY;
        for (var face : (List<?>) field(painted.getFirst(), "faces")) {
            var surface = (BoardSurface.Face) face;
            highest = Math.max(highest, Math.max(surface.a().z, Math.max(surface.b().z, surface.c().z)));
        }
        assertTrue(Math.abs(highest - deck) < .01f, "The decal lies on the deck's road surface: " + highest + " / " + deck);
    }

    /** A corpus span per decode reason: its board, the span hex the camera centres and the type the decode gives it. */
    private record Example(String reason, String board, String hex, boolean built) {
        Coords coords() { return new Coords(Integer.parseInt(hex.substring(0, 2)) - 1, Integer.parseInt(hex.substring(2)) - 1); }
    }

    private static final List<Example> EXAMPLES = List.of(
          new Example("road", "Map Set 7/16x17 Archipelago 1.board", "0309", true),
          new Example("pillars", "unofficial/Derv_Maps/35x35 2Fort.board", "0909", true),
          new Example("concrete", "unofficial/Flynn/34x34 Starleague Depot11 River.board", "1716", true),
          new Example("rail", "unofficial/Cakefish/General/45x45 Arid Overlay.board", "1331", true),
          new Example("building", "BattleMat Strana Mechty/32x17 Circle of Equals [simplified].board", "1714", true),
          new Example("natural", "Battle of Tukayyid Pack/32x17 Kozice Valley (CDS).board", "2609", false));

    /**
     * One legacy decode (BridgeSpan.decode): the conversion stores each corpus example's type, and the 3D board draws
     * the stored type (it never decodes); captures {@code bridges/corpus-<reason>-imported.png}.
     */
    @Test
    void corpusExamplesDrawTheTypeTheConversionStores() throws Exception {
        Map<Example, List<BoardScene>> scenes = new java.util.LinkedHashMap<>();
        for (Example example : EXAMPLES) {
            var file = megamek.common.Configuration.boardsDir().toPath().resolve(example.board());
            Board imported = megamek.common.board.BoardFile.read(file);
            javax.swing.SwingUtilities.invokeAndWait(() -> BoardSceneryLayouts.convert(imported));
            assertEquals(example.built(), megamek.common.board.HexAppearance.bridgeBuilt(
                  imported.getHex(example.coords()).getAppearance()), example.reason() + ": the type import stores");
            scenes.put(example, List.of(GpuLegacyImportSmokeTest.scene(imported)));
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "bridges");
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                GpuReviewFrame frame = new GpuReviewFrame(weather);
                try {
                    for (var entry : scenes.entrySet()) {
                        Example example = entry.getKey();
                        for (var scene : entry.getValue()) {
                            var tile = scene.tile(example.coords());
                            String name = "corpus-" + example.reason() + "-imported";
                            assertEquals(!example.built(), BoardBridge.deck(scene, tile).natural(), name);
                            GpuTerrain terrain = new GpuTerrain();
                            try {
                                var camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                camera.setIsometric(true);
                                camera.fit(scene);
                                frame.prepare(terrain, camera, scene);
                                terrain.update(scene);
                                terrain.animate(0, List.of());
                                camera.camera.zoom = .3f;
                                camera.center(BoardGeometry.center(example.coords(),
                                      tile.elevation() + BoardBridge.feature(tile).elevation()));
                                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, name + ".png"));
                            } finally { terrain.dispose(); }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Corpus bridge types", failure.get()); }
    }

    /** The hover outline (walkableHit) looks past a pier to the ground behind it; the other picks see the pier. */
    private static void checkWalkable(BoardScene scene, GpuTerrain terrain) {
        Coords near = new Coords(7, 2), far = new Coords(7, 3);
        // Aimed 2 px off the joint into the near hex, not along the cut between the two halves.
        var joint = BoardGeometry.center(near, 3).lerp(BoardGeometry.center(far, 3), .5f).add(0, 2, 0);
        var across = new Vector3(1, 0, 0);
        var ray = new Ray(new Vector3(joint).mulAdd(across, 60).add(0, 0, 18), new Vector3(across).scl(-60).add(0, 0, -18).nor());
        var solid = terrain.hit(scene, ray);
        var walkable = terrain.walkableHit(scene, ray);
        assertNotNull(solid);
        assertTrue(Math.sqrt(solid.distance()) < 60, "The ray meets the tall pier's shaft: " + Math.sqrt(solid.distance()));
        assertTrue(walkable == null || walkable.distance() > solid.distance() + 100,
              "No one stands on a pier: " + (walkable == null ? "nothing" : Math.sqrt(walkable.distance())));
    }

    /**
     * The installed piers of one case against what is drawn: one under each joint, never near a hex centre; the cap's
     * top at most 0.75 px above the drawn deck underside (the GLB slab's bottom on a flat deck, the lowest downward face
     * of a graded slab) and reaching it; nothing above the deck's top; the footing on or below the lowest drawn floor
     * under it. A rock arch has none.
     */
    private static void checkPiers(BoardScene scene, GpuTerrain terrain, Case shown) throws Exception {
        float s = BoardGeometry.hexScale();
        Map<Coords, BoardBridge.Shape> shapes = new java.util.HashMap<>();
        Map<Coords, TerrainLod> lods = new java.util.HashMap<>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (var entry : ((Map<?, ?>) field(chunk, "tileMeshes")).entrySet()) {
                var shape = (BoardBridge.Shape) field(entry.getValue(), "bridgeShape");
                if (shape != null) { shapes.put((Coords) entry.getKey(), shape); }
                lods.put((Coords) entry.getKey(), (TerrainLod) field(chunk, "lod"));
            }
        }
        Map<Coords, com.badlogic.gdx.math.collision.BoundingBox> decks = new java.util.HashMap<>();
        for (var prop : GpuLegacyImportSmokeTest.installed(terrain)) {
            if (BoardBridge.feature(scene.tile(prop.coords())) != null) { decks.put(prop.coords(), prop.bounds()); }
        }
        var hexes = new java.util.LinkedHashSet<>(shown.joints().keySet());
        hexes.add(shown.view());
        for (Coords at : hexes) {
            var tile = scene.tile(at);
            var shape = shapes.get(at);
            var piers = shape == null ? List.<BoardBridge.Facet>of()
                  : shape.facets().stream().filter(f -> f.part() == BoardBridge.Part.PIER).toList();
            var structure = shape == null ? List.<BoardBridge.Facet>of()
                  : shape.facets().stream().filter(f -> f.part() != BoardBridge.Part.PIER).toList();
            String name = shown.label() + " " + at.getBoardNum() + " at " + BoardGeometry.level() / s + " px per level";
            int joints = 0;
            for (int d = 0; d < 6; d++) {
                var next = at.translated(d);
                var edge = BoardGeometry.center(at, 0).lerp(BoardGeometry.center(next, 0), .5f);
                var mine = piers.stream().flatMap(f -> java.util.stream.Stream.of(f.a(), f.b(), f.c()))
                      .filter(p -> Vector3.dst(p.x, p.y, 0, edge.x, edge.y, 0) < 12 * s).toList();
                if (mine.isEmpty()) { continue; }
                joints++;
                var glb = decks.get(at);
                float highest = Float.NEGATIVE_INFINITY;
                for (var p : mine) {
                    for (Coords hex : List.of(at, next)) {
                        assertTrue(Vector3.dst(p.x, p.y, 0, BoardGeometry.centerX(hex), BoardGeometry.centerY(hex), 0)
                              >= 29.5f * s - .01f, name + ": no pier near a hex centre");
                    }
                    var probe = new Vector3(p);
                    // A flat deck's GLB draws its slab under the deck plane (a truss's chords reach lower).
                    float under = glb != null ? BoardBridge.deckZ(tile) - BoardBridge.SLAB * s : Float.POSITIVE_INFINITY;
                    float top = glb != null ? BoardBridge.deckZ(tile) : Float.NEGATIVE_INFINITY;
                    if (glb == null) {
                        for (var facet : structure) {
                            float z = new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP).height(probe.x, probe.y);
                            if (!Float.isFinite(z)) { continue; }
                            // The slab's underside; a truss's steel chords reach below it.
                            if (facet.normal().z < -.5f && facet.part() != BoardBridge.Part.STEEL) { under = Math.min(under, z); }
                            if (facet.part() == BoardBridge.Part.TOP) { top = Math.max(top, z); }
                        }
                    }
                    if (Float.isFinite(top)) { assertTrue(p.z <= top + .01f, name + ": the pier stays below the deck's top"); }
                    if (Float.isFinite(under)) {
                        assertTrue(p.z <= under + .75f * s + .01f, name + ": the cap ends inside the slab, " + (p.z - under));
                        highest = Math.max(highest, p.z - under);
                    }
                }
                assertTrue(highest >= -.01f, name + ": the cap reaches the drawn underside, " + highest);
                // The footing's outer corners stand on or below the lowest drawn floor under the footing.
                var footprint = BoardBridgeFooting.pierFootprint(tile, d, false);
                float floor = Float.POSITIVE_INFINITY;
                for (var corner : footprint) {
                    var owner = BoardGeometry.tile(scene, corner.x, corner.y);
                    var surface = new BoardSurface(scene, owner, lods.get(at));
                    floor = Math.min(floor, BoardSurface.sampleHeight(surface.foundation(), corner.x, corner.y,
                          BoardGeometry.groundZ(owner)));
                }
                float lip = Float.NEGATIVE_INFINITY;
                for (var p : mine) {
                    for (var corner : footprint) {
                        if (Vector3.dst(p.x, p.y, 0, corner.x, corner.y, 0) < .01f) { lip = Math.max(lip, p.z); }
                    }
                }
                assertTrue(Float.isFinite(lip) && lip <= floor + s + .01f, name + ": the footing stands on the floor, " + lip + " / " + floor);
                System.out.printf(java.util.Locale.ROOT, "PIER %s edge %d: cap gap %.3f, footing corner %.3f over the floor %.3f%n",
                      name, d, highest, lip - floor, floor);
            }
            assertEquals((int) shown.joints().getOrDefault(at, 0), joints, name + ": piers under its joints only");
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var type = owner.getClass();
        while (true) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
                if (type == null) { throw missing; }
            }
        }
    }

    private static BoardScene scene(int exits) {
        Hex bridge = new Hex(-2);
        bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        return BoardSurfaceBlendTest.scene(coords -> {
            if (coords.equals(CENTER)) {
                var art = BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.GRASS, -2, 1, 0).ground();
                return new BoardScene.Tile(coords, -2, 1, false, 0, BoardScene.Surface.GRASS,
                      art, null, null, null, null, BoardFeatures.capture(bridge, coords, Map.of()), List.of(),
                      BoardLiquid.WATER, null, true, BoardRoad.Kind.NONE);
            }
            int road = 0;
            for (int d = 0; d < 6; d++) {
                if ((exits & (1 << d)) != 0 && coords.equals(CENTER.translated(d))) {
                    road = (1 << d) | (1 << ((d + 3) % 6));
                }
            }
            return BoardRoadTest.tile(coords, road == 0 ? BoardRoad.Kind.NONE : BoardRoad.Kind.ALLEY,
                  road, 0, BoardScene.Surface.GRASS);
        });
    }

    private static void checkJoin(BoardScene scene, GpuTerrain terrain, Coords at, int direction) {
        var roadTile = scene.tile(at.translated(direction));
        Vector3 center = BoardGeometry.center(at, 0), roadCenter = BoardGeometry.center(roadTile.coords(), 0);
        Vector3 gate = new Vector3(center).lerp(roadCenter, .5f);
        Vector3 inward = new Vector3(center).sub(roadCenter).nor();
        Vector3 across = new Vector3(-inward.y, inward.x, 0);
        int edge = Math.floorMod(1 - direction, 6);
        Vector3 edgeStep = BoardGeometry.corner(at, 0, edge + 1)
              .sub(BoardGeometry.corner(at, 0, edge));
        edgeStep.scl(1 / edgeStep.dot(across));
        BoardSurface surface = new BoardSurface(scene, roadTile);
        BoardRoad road = BoardRoad.of(scene, roadTile);
        var pavement = GpuRoads.patches(roadTile, road).stream()
              .filter(patch -> patch.texture().equals("roads/asphalt") && !patch.blended()).findFirst().orElseThrow();
        var triangles = GpuRoads.drape(roadTile, surface, pavement);
        for (float lateral : new float[] { -6, 0, 6 }) {
            Vector3 boundary = new Vector3(gate).mulAdd(edgeStep, lateral);
            Vector3 bridgePoint = new Vector3(boundary).mulAdd(inward, .05f);
            var hit = terrain.hit(scene, new Ray(new Vector3(bridgePoint.x, bridgePoint.y, 200), new Vector3(0, 0, -1)));
            assertNotNull(hit);
            assertEquals(at, hit.coords());
            float deck = 200 - (float) Math.sqrt(hit.distance());
            Vector3 roadPoint = new Vector3(boundary).mulAdd(inward, -.05f);
            Ray ray = new Ray(new Vector3(roadPoint.x, roadPoint.y, 200), new Vector3(0, 0, -1));
            Vector3 intersection = new Vector3();
            boolean found = false;
            for (var triangle : triangles) {
                if (Intersector.intersectRayTriangle(ray, triangle.a(), triangle.b(), triangle.c(), intersection)) {
                    assertEquals(intersection.z, deck, .001f,
                          "Bridge " + at.getBoardNum() + " must be flush at exit " + direction + ", offset " + lateral);
                    found = true;
                    break;
                }
            }
            assertTrue(found, "The road must reach bridge exit " + direction + " at lateral offset " + lateral);
            Vector3 railPoint = new Vector3(bridgePoint).mulAdd(inward, 5).mulAdd(across, 8.25f - lateral);
            hit = terrain.hit(scene, new Ray(new Vector3(railPoint.x, railPoint.y, 200), new Vector3(0, 0, -1)));
            assertNotNull(hit);
            assertEquals(at, hit.coords());
            assertEquals(deck + 2.5f, 200 - Math.sqrt(hit.distance()), .001f, "Raised side remains above the deck");
        }
    }
}
