/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
