/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardObstaclesTest {
    @Test
    void nativePaintAndUnresolvedModelsRemainEditableWithoutLoadingMeshes() {
        for (String kind : List.of("prop", "decal")) {
            var object = new megamek.common.board.BoardDecoration("unknown", kind, "missing/catalog-entry", null,
                  0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
            var feature = new BoardScene.Feature(object.asset(), 0, 0, 0, 1, 1, 0,
                  BoardScene.FeatureKind.PROP, 0, false, object);
            var scene = scene(CENTER, feature, 0);
            assertTrue(new BoardObstacles(scene, scene.tile(CENTER)).isEmpty());
        }
    }

    @Test
    void floatingNativeObjectsUseTheirAbsoluteHeightAndUniformScaleForClearance() {
        var object = new megamek.common.board.BoardDecoration("floating", "prop", TANK, null,
              0, 0, 0, false, .5, megamek.common.board.BoardDecoration.Placement.absolute(8), 0);
        var feature = new BoardScene.Feature(TANK, 0, 0, 0, .5f, 1, 0, BoardScene.FeatureKind.PROP, 0, false, object);
        var scene = scene(CENTER, feature, 2);
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        int occupied = 0;
        for (int x = -30; x <= 30; x += 2) {
            for (int y = -30; y <= 30; y += 2) {
                var point = BoardGeometry.center(CENTER, 2).add(x, y, 0);
                assertFalse(obstacles.obstructs(point, .1f, 1), "Floating objects cannot clear the ground below");
                point.z = 8 * BoardGeometry.level();
                if (obstacles.obstructs(point, .1f, 1)) { occupied++; }
            }
        }
        assertTrue(occupied > 0, "The actual elevated footprint still provides clearance");
    }

    private static final Coords CENTER = new Coords(2, 2);
    private static final String TANK = "buildings/saxarba/fuel_tanks/fuel_tank_hard_15";

    @Test
    void entireScatterFootprintClearsSolidWhileCourtyardsAndOtherLevelsRemainOpen() {
        List<Vector3> triangles = new ArrayList<>();
        rectangle(triangles, -10, -10, -3, 10);
        rectangle(triangles, 3, -10, 10, 10);
        var footprint = new BoardObstacles.Footprint(triangles, 0, 8,
              new BoundingBox(new Vector3(-10, -10, 0), new Vector3(10, 10, 8)));
        assertTrue(footprint.obstructs(new Vector3(5, 0, 0), .3f, 1));
        assertTrue(footprint.obstructs(new Vector3(2, 0, 0), 1.1f, 1), "The whole rock clears the wall");
        assertFalse(footprint.obstructs(new Vector3(0, 0, 0), 1, 1), "A courtyard is still open ground");
        assertFalse(footprint.obstructs(new Vector3(15, 0, 0), 1, 1));
        assertFalse(footprint.obstructs(new Vector3(5, 0, -4), .3f, 1), "A lower cliff ledge remains clear");
    }

    @Test
    void neighboringStructureOverhangIsCheckedAndClearGroundIsRetained() {
        Coords neighbor = CENTER.translated(0);
        float x = (BoardGeometry.centerX(CENTER) - BoardGeometry.centerX(neighbor)) / BoardGeometry.hexScale();
        float y = (BoardGeometry.centerY(CENTER) - BoardGeometry.centerY(neighbor)) / BoardGeometry.hexScale();
        var feature = new BoardScene.Feature(TANK, x, y, 27, 1, 2, 0, BoardScene.FeatureKind.PROP);
        BoardScene scene = scene(neighbor, feature, 0);
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        assertFalse(obstacles.isEmpty());
        int occupied = 0, clear = 0;
        Vector3 center = BoardGeometry.center(CENTER, 0);
        for (int ix = -30; ix <= 30; ix += 3) {
            for (int iy = -30; iy <= 30; iy += 3) {
                boolean blocked = obstacles.obstructs(new Vector3(center.x + ix, center.y + iy, 0), .1f, 1);
                if (blocked) { occupied++; } else { clear++; }
            }
        }
        assertTrue(occupied > 10, "An adjacent tank group occupies its actual moved footprint");
        assertTrue(clear > 10, "The rest of the hex remains available");
        assertFalse(new BoardObstacles(scene, scene.tile(new Coords(0, 0)))
              .obstructs(BoardGeometry.center(new Coords(0, 0), 0), 1, 1));
    }

    @Test
    void rimRocksCannotIntrudeIntoAnAuthoredStructure() {
        var small = new BoardScene.Feature(TANK, 18, 0, 0, 1, 2, 0, BoardScene.FeatureKind.PROP);
        var clear = new BoardScene.Feature(TANK, 300, 0, 0, 1, 2, 0, BoardScene.FeatureKind.PROP);
        BoardScene original = scene(CENTER, clear, 3), occupied = scene(CENTER, small, 3);
        var baseline = new BoardSurface(original, original.tile(CENTER)).faces.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).toList();
        var actual = new BoardSurface(occupied, occupied.tile(CENTER)).faces.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).toList();
        var obstacle = new BoardObstacles(occupied, occupied.tile(CENTER));
        assertTrue(baseline.size() > 0);
        assertTrue(actual.size() < baseline.size(), "The structure must exclude overlapping rim rocks");
        assertTrue(actual.size() > 0, "Rocks on the free side of the cliff are retained");
        for (var face : actual) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                assertFalse(obstacle.obstructs(p, 0, 0), "No retained rock vertex penetrates the structure");
            }
        }
    }

    @Test
    void modularBuildingUsesItsSelectedAuthoredPartsWithoutGpuResources() {
        var feature = new BoardScene.Feature("buildings/saxarba/fortress_light/fortress_light_a_52",
              0, 0, 0, 1, 4, 0, BoardScene.FeatureKind.BUILDING);
        BoardScene scene = scene(CENTER, feature, 0);
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        assertFalse(obstacles.isEmpty());
        int occupied = 0;
        Vector3 center = BoardGeometry.center(CENTER, 0);
        for (int x = -30; x < 30; x += 5) {
            for (int y = -30; y < 30; y += 5) {
                if (obstacles.obstructs(new Vector3(center.x + x, center.y + y, 0), .5f, 1)) { occupied++; }
            }
        }
        assertTrue(occupied > 0 && occupied < 144);
    }

    @Test
    void shiftedAuthoredModuleOriginsKeepTheSameClearanceGeometry() {
        var data = RigidGlb.loadLods(GpuBuildingTest.file()).getFirst();
        var original = BoardShape.shapes(data, true);
        // Valid authored modules can be baked far below their display origin.
        for (var mesh : data.meshes) {
            for (int i = 2; i < mesh.vertices.length; i += RigidGlb.STRIDE) { mesh.vertices[i] -= 100; }
        }
        var moved = BoardShape.shapes(data, true);
        for (String name : original.keySet()) {
            var a = original.get(name);
            var b = moved.get(name);
            assertEquals(a.height(), b.height(), .0001f);
            for (int i = 0; i < a.polygons().size(); i++) {
                for (int j = 0; j < 3; j++) {
                    assertTrue(a.polygons().get(i).points()[j].epsilonEquals(b.polygons().get(i).points()[j], .0001f));
                }
            }
        }
    }

    @Test
    void capturedScatterIsRejectedAfterSettlingAgainstAnAdjacentObject() {
        Coords neighbor = CENTER.translated(0);
        float x = (BoardGeometry.centerX(CENTER) - BoardGeometry.centerX(neighbor)) / BoardGeometry.hexScale();
        float y = (BoardGeometry.centerY(CENTER) - BoardGeometry.centerY(neighbor)) / BoardGeometry.hexScale();
        var feature = new BoardScene.Feature(TANK, x, y, 0, 1, 2, 0, BoardScene.FeatureKind.PROP);
        BoardScene scene = scene(neighbor, feature, 0);
        BoardScene clear = scene(neighbor, new BoardScene.Feature(TANK, 300, 0, 0, 1, 2, 0,
              BoardScene.FeatureKind.PROP), 0);
        var surface = new BoardSurface(scene, scene.tile(CENTER));
        var freeSurface = new BoardSurface(clear, clear.tile(CENTER));
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        Vector3 center = BoardGeometry.center(CENTER, 0), spot = null;
        for (int ix = -20; ix <= 20 && spot == null; ix += 4) {
            for (int iy = -20; iy <= 20; iy += 4) {
                var point = new Vector3(center.x + ix, center.y + iy, 0);
                if (obstacles.obstructs(point, 0, 1)) { spot = point; break; }
            }
        }
        assertTrue(spot != null);
        var scatter = new BoardScene.Feature("scatter-rock", (spot.x - center.x) / BoardGeometry.hexScale(),
              (spot.y - center.y) / BoardGeometry.hexScale(), 17, 1, .15f, 0, BoardScene.FeatureKind.SCATTER);
        var blocked = mesh();
        GpuScatter.build(blocked, scene.tile(CENTER), surface, scatter);
        assertEquals(0, blocked.getNumIndices());
        var available = mesh();
        GpuScatter.build(available, clear.tile(CENTER), freeSurface, scatter);
        assertTrue(available.getNumIndices() > 0, "The same scatter remains visible on free ground");
    }

    private static MeshBuilder mesh() {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked
              | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
        return mesh;
    }

    @Test
    void deepWaterKeepsGeologicalRocksButRejectsCosmeticScatterAtEveryDetail() {
        for (var lod : TerrainLod.values()) {
            for (int depth : new int[] { 1, 2, 4 }) {
                BoardScene scene = water(depth, List.of());
                var surface = new BoardSurface(scene, scene.tile(CENTER), lod);
                var rocks = surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).toList();
                if (lod.dressing) {
                    assertFalse(rocks.isEmpty(), lod + " depth=" + depth + ": cliff and bed boulders remain");
                }
                assertTrue(rocks.stream().allMatch(face -> surface.relief.shade(face.a()).kind() == BoardRelief.Kind.ROCK),
                      "Positive-depth water must not acquire cosmetic bushes");
                assertTrue(surface.faces.stream().anyMatch(face -> face.finish() == BoardSurface.Finish.BED));
                for (String asset : List.of("scatter-rock", "scatter-plant")) {
                    var scatter = new BoardScene.Feature(asset, 16, 0, 17, 1, .15f, 0, BoardScene.FeatureKind.SCATTER);
                    var mesh = mesh();
                    GpuScatter.build(mesh, scene.tile(CENTER), surface, scatter);
                    assertEquals(0, mesh.getNumIndices(), "Deep-water cosmetics remain excluded: " + asset);
                }
            }
        }
        var boulder = new BoardScene.Feature("boulder", 15, 0, 0, 1, .5f, 0, BoardScene.FeatureKind.BOULDER);
        BoardScene rough = water(2, List.of(boulder));
        assertTrue(new BoardSurface(rough, rough.tile(CENTER)).faces.stream()
              .anyMatch(face -> face.finish() == BoardSurface.Finish.OUTCROP), "Gameplay Rough cover is retained");
    }

    @Test
    void depthZeroAllowsVisibleStonesButRejectsHiddenUnderwaterDressing() {
        BoardScene shallow = water(0, List.of());
        var tile = shallow.tile(CENTER);
        float water = BoardGeometry.waterZ(tile);
        Vector3 center = BoardGeometry.center(CENTER, 0);
        assertTrue(BoardScatter.allowed(tile));
        assertFalse(BoardScatter.visible(shallow, tile, new Vector3(center.x, center.y, water - 2), 1));
        assertTrue(BoardScatter.visible(shallow, tile, new Vector3(center.x, center.y, water - .2f), 1));
        assertFalse(BoardScatter.allowed(water(1, List.of()).tile(CENTER)));
        var surface = new BoardSurface(shallow, tile);
        for (String asset : List.of("scatter-rock", "scatter-plant")) {
            var scatter = new BoardScene.Feature(asset, 16, 0, 17, 1, .15f, 0, BoardScene.FeatureKind.SCATTER);
            var mesh = mesh();
            GpuScatter.build(mesh, tile, surface, scatter);
            assertTrue(mesh.getNumIndices() > 0, "Visible depth-zero scatter remains allowed: " + asset);
        }
    }

    private static BoardScene water(int depth, List<BoardScene.Feature> features) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                boolean pool = coords.equals(CENTER);
                tiles.add(new BoardScene.Tile(coords, pool ? 0 : 3, pool ? depth : -1, false, 0,
                      BoardScene.Surface.SAND, null, null, null, null, null, pool ? features : List.of(), List.of(),
                      pool ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static void rectangle(List<Vector3> points, float left, float bottom, float right, float top) {
        points.addAll(List.of(new Vector3(left, bottom, 0), new Vector3(right, bottom, 0), new Vector3(right, top, 0),
              new Vector3(right, top, 0), new Vector3(left, top, 0), new Vector3(left, bottom, 0)));
    }

    @Test
    void everyGeyserClearsGrassFromItsBasinAndKeepsSurroundingMeadow() {
        for (String state : List.of("water_off", "water_on", "magma")) {
            var feature = new BoardScene.Feature("scenery/saxarba/misc/geyser_" + state,
                  7, -3, 27, .8f, 0, 0, BoardScene.FeatureKind.SCENERY);
            BoardScene scene = scene(CENTER, feature, 0, BoardScene.Surface.GRASS);
            var tile = scene.tile(CENTER);
            var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, BoardGeometry.floor(scene));
            var roots = GpuGroundCover.plant(scene, tile, surface);
            assertTrue(roots.size / 4 > 1500, "The surrounding meadow must remain planted: " + state);
            float scale = BoardGeometry.hexScale();
            float x = BoardGeometry.centerX(CENTER) + 7 * scale, y = BoardGeometry.centerY(CENTER) - 3 * scale;
            float basin = 18 * .8f * scale;
            int outside = 0;
            for (int i = 0; i < roots.size; i += 4) {
                float dx = roots.get(i) - x, dy = roots.get(i + 1) - y;
                assertTrue(dx * dx + dy * dy > basin * basin, "No grass may emerge through the basin: " + state);
                if (dx * dx + dy * dy > 30 * 30 * scale * scale) { outside++; }
            }
            assertTrue(outside > 500, "Grass outside the geyser footprint remains available");
            var obstacles = new BoardObstacles(scene, tile);
            assertTrue(obstacles.obstructs(new Vector3(x, y, 0), 0, 1));
            assertFalse(obstacles.obstructs(new Vector3(x, y, -10 * scale), 0, scale),
                  "The scenery footprint must not clear a separate lower ledge");
        }
    }

    private static BoardScene scene(Coords structure, BoardScene.Feature feature, int elevation) {
        return scene(structure, feature, elevation, BoardScene.Surface.SAND);
    }

    private static BoardScene scene(Coords structure, BoardScene.Feature feature, int elevation, BoardScene.Surface surface) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, coords.equals(structure) ? elevation : 0, -1, false, 0,
                      surface, null, null, null, null, null,
                      coords.equals(structure) ? List.of(feature) : List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
