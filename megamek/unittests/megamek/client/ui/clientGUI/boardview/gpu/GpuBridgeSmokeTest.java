/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
