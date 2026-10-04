/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoadConcreteTest {
    private static BoardScene scene() {
        return BoardCliffSeamTest.scene(new File(
              "data/boards/unofficial/SimonLandmine/32x34/32x34 DesertCity1 NE.board"));
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void desertCityRoadsStayPaintedAcrossTheirMovedBorders(TerrainLod lod) {
        var scene = scene();
        var at = new Coords(23, 2);
        var center = BoardGeometry.center(at, 0);
        var roads = List.of(road(scene, at.translated(0), lod), road(scene, at, lod),
              road(scene, at.translated(3), lod));
        float scale = BoardGeometry.hexScale();
        for (int direction : new int[] { 0, 3 }) {
            float borderY = (center.y + BoardGeometry.centerY(at.translated(direction))) / 2;
            for (float across = -6; across <= 6; across += 1.5f) {
                for (float along = -8; along <= 8; along += .5f) {
                    float x = center.x + across * scale, y = borderY + along * scale;
                    assertTrue(roads.stream().anyMatch(road -> road.covers(x, y)),
                          lod + " road gap at 2403 direction " + direction + ": " + x + ", " + y);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void desertCityRoadMeetsTheFullWidthOfTheDiagonalConcreteEdge(TerrainLod lod) {
        var scene = scene();
        var at = new Coords(24, 9);
        var center = BoardGeometry.center(at, 0);
        var road = road(scene, at, lod);
        var concrete = new BoardSurface(scene, scene.tile(at.translated(3)), lod);
        assertEquals(BoardScene.Surface.CONCRETE, concrete.tile.surface());
        float scale = BoardGeometry.hexScale();
        for (float across = -6; across <= 6; across += 1.5f) {
            for (float along = 24; along <= 60; along += .5f) {
                float x = center.x + across * scale, y = center.y - along * scale;
                boolean paved = Float.isFinite(BoardSurface.sampleHeight(concrete.groundFaces(), x, y, Float.NaN));
                assertTrue(paved || road.covers(x, y), lod + " road must reach concrete at 2511: " + x + ", " + y);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadsReachFittedConcreteFromEveryDirection(int direction) {
        var at = BoardRoadTest.CENTER;
        var receiver = at.translated(direction);
        var paving = new HashSet<Coords>();
        for (int i = -2; i <= 2; i++) {
            paving.add(receiver.translated((direction + (i < 0 ? 4 : 1)) % 6, Math.abs(i)));
        }
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 1 << direction : 0, 0,
              paving.contains(c) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND));
        var concrete = new BoardSurface(scene, scene.tile(receiver));
        var road = road(scene, at, TerrainLod.FULL);
        var center = BoardGeometry.center(at, 0);
        var along = BoardGeometry.center(receiver, 0).sub(center).nor();
        var across = new Vector3(-along.y, along.x, 0);
        float scale = BoardGeometry.hexScale();
        assertTrue(BoardConcrete.of(scene).corners(receiver).stream()
              .anyMatch(shift -> Math.hypot(shift.x(), shift.y()) > scale), "Exercise a fitted concrete edge");
        for (float lateral = -6; lateral <= 6; lateral += 1.5f) {
            boolean joined = false;
            for (float distance = 24; distance <= 65; distance += .5f) {
                var point = new Vector3(center).mulAdd(along, distance * scale).mulAdd(across, lateral * scale);
                if (Float.isFinite(BoardSurface.sampleHeight(concrete.groundFaces(), point.x, point.y, Float.NaN))) {
                    joined = true;
                    break;
                }
                assertTrue(road.covers(point.x, point.y), "Road gap before concrete, direction " + direction + ": " + point);
            }
            assertTrue(joined, "Every part of the carriageway must reach the concrete");
        }
    }

    private static Road road(BoardScene scene, Coords at, TerrainLod lod) {
        var tile = scene.tile(at);
        var surface = new BoardSurface(scene, tile, lod);
        var road = BoardRoad.of(scene, tile);
        var asphalt = GpuRoads.patches(tile, road).stream()
              .filter(patch -> patch.texture().equals("roads/asphalt") && !patch.blended()).findFirst().orElseThrow();
        return new Road(at, GpuRoads.mask(road, asphalt), GpuRoads.drape(tile, surface, asphalt));
    }

    private record Road(Coords at, GpuRoads.MaskData mask, List<BoardTacticalGeometry.Triangle> triangles) {
        boolean covers(float x, float y) {
            float scale = BoardGeometry.hexScale();
            if (BoardRoadTest.maskAlpha(mask, (x - BoardGeometry.centerX(at)) / scale,
                  (y - BoardGeometry.centerY(at)) / scale) < .99f) { return false; }
            var ray = new Ray(new Vector3(x, y, 100), new Vector3(0, 0, -1));
            return triangles.stream().anyMatch(t -> Intersector.intersectRayTriangle(ray, t.a(), t.b(), t.c(), null));
        }
    }
}
