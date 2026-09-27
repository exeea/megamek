/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
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
                        for (int exits = 0; exits < 64; exits++) {
                            BoardScene scene = scene(exits);
                            terrain.update(scene);
                            var bounds = terrain.roofBounds(CENTER);
                            assertNotNull(bounds);
                            assertEquals(GpuRoads.SURFACE_LIFT - 1.5f, bounds.min.z, .001f);
                            assertEquals(GpuRoads.SURFACE_LIFT + 2.5f, bounds.max.z, .001f,
                                  "Rail height must not follow the terrain-level setting");
                            for (int direction = 0; direction < 6; direction++) {
                                if ((exits & (1 << direction)) != 0) { checkJoin(scene, terrain, direction); }
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

    private static void checkJoin(BoardScene scene, GpuTerrain terrain, int direction) {
        var roadTile = scene.tile(CENTER.translated(direction));
        Vector3 center = BoardGeometry.center(CENTER, 0), roadCenter = BoardGeometry.center(roadTile.coords(), 0);
        Vector3 gate = new Vector3(center).lerp(roadCenter, .5f);
        Vector3 inward = new Vector3(center).sub(roadCenter).nor();
        Vector3 across = new Vector3(-inward.y, inward.x, 0);
        int edge = Math.floorMod(1 - direction, 6);
        Vector3 edgeStep = BoardGeometry.corner(CENTER, 0, edge + 1)
              .sub(BoardGeometry.corner(CENTER, 0, edge));
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
            assertEquals(CENTER, hit.coords());
            float deck = 200 - (float) Math.sqrt(hit.distance());
            Vector3 roadPoint = new Vector3(boundary).mulAdd(inward, -.05f);
            Ray ray = new Ray(new Vector3(roadPoint.x, roadPoint.y, 200), new Vector3(0, 0, -1));
            Vector3 intersection = new Vector3();
            boolean found = false;
            for (var triangle : triangles) {
                if (Intersector.intersectRayTriangle(ray, triangle.a(), triangle.b(), triangle.c(), intersection)) {
                    assertEquals(intersection.z, deck, .001f, "Bridge deck must be flush across the road's width");
                    found = true;
                    break;
                }
            }
            assertTrue(found, "The road must reach bridge exit " + direction + " at lateral offset " + lateral);
            Vector3 railPoint = new Vector3(bridgePoint).mulAdd(inward, 5).mulAdd(across, 8.25f - lateral);
            hit = terrain.hit(scene, new Ray(new Vector3(railPoint.x, railPoint.y, 200), new Vector3(0, 0, -1)));
            assertNotNull(hit);
            assertEquals(CENTER, hit.coords());
            assertEquals(deck + 2.5f, 200 - Math.sqrt(hit.distance()), .001f, "Raised side remains above the deck");
        }
    }
}
