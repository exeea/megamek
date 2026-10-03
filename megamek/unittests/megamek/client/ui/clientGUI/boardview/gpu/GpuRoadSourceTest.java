/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class GpuRoadSourceTest {
    @Test
    void forestsEndRoadsReachBothEndsOfTheBridgeAtEveryDetail() throws Exception {
        var scene = scene("unofficial/Strategoslevel3/32x17 (CW) Forests End  - Road.board");
        var bridge = scene.tile(new Coords(8, 8));
        assertEquals(36, bridge.features().stream().filter(f -> f.asset().equals("bridge"))
              .findFirst().orElseThrow().bridgeExits());
        for (int direction : new int[] { 2, 5 }) {
            var tile = scene.tile(bridge.coords().translated(direction));
            assertTrue(BoardRoad.rendered(tile));
            assertEquals(36, tile.roadExits());
            var center = BoardGeometry.center(tile.coords(), tile.elevation());
            var gate = BoardGeometry.center(bridge.coords(), bridge.elevation()).lerp(center, .5f);
            var inward = new Vector3(center).sub(gate).nor();
            var across = new Vector3(-inward.y, inward.x, 0);
            int edge = Math.floorMod(1 - direction, 6);
            var step = BoardGeometry.corner(bridge.coords(), 0, edge + 1)
                  .sub(BoardGeometry.corner(bridge.coords(), 0, edge));
            step.scl(1 / step.dot(across));
            var road = BoardRoad.of(scene, tile);
            var pavement = GpuRoads.patches(tile, road).stream()
                  .filter(p -> p.texture().equals("roads/asphalt") && !p.blended()).findFirst().orElseThrow();
            for (var lod : TerrainLod.values()) {
                var triangles = GpuRoads.drape(tile, new BoardSurface(scene, tile, lod), pavement);
                for (float lateral : new float[] { -7, -6, 0, 6, 7 }) {
                    for (float distance : new float[] { .05f, 1, 3, 6, 10 }) {
                        var point = new Vector3(gate).mulAdd(step, lateral).mulAdd(inward, distance);
                        assertTrue(pavement.shape().contains((point.x - center.x) / BoardGeometry.hexScale(),
                              (point.y - center.y) / BoardGeometry.hexScale()));
                        var ray = new Ray(new Vector3(point.x, point.y, 200), new Vector3(0, 0, -1));
                        var hit = new Vector3();
                        assertTrue(triangles.stream().anyMatch(t -> Intersector.intersectRayTriangle(ray,
                              t.a(), t.b(), t.c(), hit)),
                              tile.coords().getBoardNum() + " " + lod + " road missing at " + point);
                        if (distance == .05f) {
                            assertEquals(GpuRoads.SURFACE_LIFT, hit.z, .001f,
                                  tile.coords().getBoardNum() + " " + lod + " road must meet the level deck");
                        }
                    }
                }
            }
        }
    }

    @Test
    void lavaTubesBridgeFloorsUseNativeRockInsteadOfTheVioletLegacyTile() throws Exception {
        var scene = scene("Map Pack Volcanic/16x17 Lava Tubes 1.board");
        var bridges = scene.tiles().stream().filter(t -> t.features().stream().anyMatch(f -> f.asset().equals("bridge")))
              .toList();
        assertEquals(4, bridges.size());
        for (var tile : bridges) {
            assertTrue(tile.detailedGround(), "Native ground beneath bridge " + tile.coords().getBoardNum());
            assertEquals(BoardScene.Surface.ROCK, tile.surface());
            assertFalse(tile.liquid().present());
            assertEquals(-2, tile.elevation());
        }
    }

    @Test
    void minesCapturesEveryAuthoredRoadExitWithSandUnderTheCurves() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Deserts/16x17 Mines 1.board"));
        BoardScene scene = minesScene();
        int roads = 0, decorated = 0;
        for (var tile : scene.tiles()) {
            Hex hex = board.getHex(tile.coords());
            if (!hex.containsTerrain(Terrains.ROAD)) { continue; }
            roads++;
            if (hex.containsTerrain(Terrains.ROAD_FLUFF)) { decorated++; }
            assertEquals(BoardRoad.Kind.PAVED, tile.road(), tile.coords().getBoardNum());
            assertEquals(hex.getTerrain(Terrains.ROAD).getExits(), tile.roadExits(), tile.coords().getBoardNum());
            assertTrue(BoardRoad.rendered(tile), "Native road including curved artwork: " + tile.coords().getBoardNum());
            assertEquals(BoardScene.Surface.SAND, tile.surface(), "Desert ground: " + tile.coords().getBoardNum());
        }
        assertEquals(30, roads);
        assertEquals(9, decorated);
    }

    @Test
    void referenceRoadsClearTheirTreesWallOnlySlopesTheyCutAndNeverFoldTheirGround() throws Exception {
        var scene = scene("unofficial/Drewbacca/16x17 Fire And Ice 2.board");
        for (var tile : scene.tiles()) {
            if (!BoardRoad.rendered(tile)) { continue; }
            // Capture places scenery clear of the same bent course the scene gives the road.
            var road = BoardRoad.of(scene, tile);
            for (var feature : tile.features()) {
                if (feature.kind() != BoardScene.FeatureKind.TREE) { continue; }
                assertTrue(road.distance(feature.x(), feature.y()) >= BoardRoad.SHOULDER + 2 - .01f,
                      "A tree stands off the road in " + tile.coords().getBoardNum());
            }
            var surface = new BoardSurface(scene, tile);
            for (var face : surface.retainingPanels) {
                var neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
                assertTrue(neighbor.elevation() > tile.elevation(),
                      "A road only walls a slope it cuts into, never one it runs along the top of: " + tile.coords().getBoardNum());
            }
            for (var face : surface.groundFaces()) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                float up = (face.b().x - face.a().x) * (face.c().y - face.a().y) - (face.b().y - face.a().y) * (face.c().x - face.a().x);
                float rise = Math.max(face.a().z, Math.max(face.b().z, face.c().z))
                      - Math.min(face.a().z, Math.min(face.b().z, face.c().z));
                // A folded slope stands up as a fin. Small slivers can still fold where two rims meet at a corner;
                // they stay well below a visible fin (see docs/gpu-road-slopes-tunnels.md).
                assertTrue(up > -1e-4f || rise < BoardRelief.metres(.3f),
                      "Graded ground never folds into a fin in " + tile.coords().getBoardNum() + ": " + face);
            }
        }
    }

    @Test
    void rocksAndShrubsStandBesideRoadsAndBridgesNeverOnThem() throws Exception {
        // MesaCity's promontory 3725 once kept a rim boulder on its bridge deck, where neither side had room for it.
        var scene = scene("unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board");
        float scale = BoardGeometry.hexScale();
        int checked = 0;
        for (var tile : scene.tiles()) {
            var passages = BoardBridge.approaches(scene, tile);
            var road = BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
            if (road == null && passages.isEmpty()) { continue; }
            float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
            for (var face : new BoardSurface(scene, tile).faces) {
                if (face.finish() != BoardSurface.Finish.OUTCROP) { continue; }
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    checked++;
                    assertTrue(road == null || road.distance((p.x - cx) / scale, (p.y - cy) / scale) >= BoardRoad.SHOULDER - .01f,
                          "Rocks and shrubs stand beside the road of " + tile.coords().getBoardNum() + ", never on it: " + p);
                    for (var passage : passages) {
                        assertFalse(passage.obstructs(p, 0, 0),
                              "Nothing stands on a bridge's deck or approach in " + tile.coords().getBoardNum() + ": " + p);
                    }
                }
            }
        }
        assertTrue(checked > 0, "The board's roads and bridge banks carry rocks and shrubs to check");
    }

    static BoardScene minesScene() throws Exception {
        return scene("Deserts/16x17 Mines 1.board");
    }

    static BoardScene scene(String path) throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/" + path));
        var scene = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                scene.set(fixture.source.takeFrame().scene());
            });
        }
        return scene.get();
    }

    @Test
    void roadAppearanceSurvivesRecaptureAndChangesWithoutDependingOnTheLegacyImage() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords at = new Coords(3, 4);
                for (int level = 1; level <= 4; level++) {
                    Hex hex = new Hex(0);
                    hex.addTerrain(new Terrain(Terrains.ROAD, level, true, 9));
                    fixture.game.getBoard().setHex(at, hex);
                    fixture.source.refresh();
                    var tile = fixture.source.takeFrame().scene().tile(at);
                    assertEquals(BoardRoad.capture(hex), tile.road());
                    assertEquals(9, tile.roadExits());
                    assertTrue(BoardRoad.rendered(tile));
                    fixture.view.centerOnHex(at);
                    fixture.source.refresh();
                    assertEquals(tile.road(), fixture.source.takeFrame().scene().tile(at).road());
                }
                Hex custom = fixture.game.getBoard().getHex(at).duplicate();
                custom.setTheme("desert");
                custom.addTerrain(new Terrain(Terrains.ROAD_FLUFF, 1));
                fixture.game.getBoard().setHex(at, custom);
                fixture.source.refresh();
                var curve = fixture.source.takeFrame().scene().tile(at);
                assertTrue(BoardRoad.rendered(curve));
                assertEquals(BoardScene.Surface.SAND, curve.surface());
                custom = custom.duplicate();
                custom.addTerrain(new Terrain(Terrains.ROAD_FLUFF, 3));
                fixture.game.getBoard().setHex(at, custom);
                fixture.source.refresh();
                assertFalse(BoardRoad.rendered(fixture.source.takeFrame().scene().tile(at)));
                fixture.game.getBoard().setHex(at, new Hex(0));
                fixture.source.refresh();
                assertEquals(BoardRoad.Kind.NONE, fixture.source.takeFrame().scene().tile(at).road());
            });
        }
    }
}
