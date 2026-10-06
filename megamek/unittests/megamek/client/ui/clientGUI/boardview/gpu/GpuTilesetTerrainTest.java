/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GpuTilesetTerrainTest {
    private static final Coords CENTER = new Coords(1, 1);

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void fittedConcreteQuaysShareTheirFootprintAndWaterPickingAcrossViews() {
        BoardScene scene = GpuRiverTerrainSmokeTest.dockScene();
        float floor = BoardGeometry.floor(scene);
        for (int y = 2; y <= 5; y++) {
            Coords at = new Coords(10, y);
            var column = GpuTilesetTerrain.column(scene, scene.tile(at), floor);
            BoardSurface nativeSurface = new BoardSurface(scene, scene.tile(at));
            for (var face : column.top()) {
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(nativeSurface.faces.stream().flatMap(f -> Stream.of(f.a(), f.b(), f.c()))
                          .anyMatch(q -> p.epsilonEquals(q, .001f)), "Both views share the pier's fitted vertices");
                }
            }
            for (var face : column.walls()) {
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                assertEquals(0, normal.z, .001f, "The quay wall remains vertical");
            }
            // This lies inside the old hex's tip, beyond the fitted pier: picking must hit the water now drawn there.
            Vector3 point = BoardGeometry.center(at, 0).add(30 * BoardGeometry.hexScale(), 0, 100);
            BoardGeometry.Hit hit = BoardGeometry.hit(scene, new Ray(point, new Vector3(0, 0, -1)), scene.tiles(), floor,
                  coords -> GpuTilesetTerrain.column(scene, scene.tile(coords), floor));
            assertEquals(100 - BoardGeometry.waterZ(scene.tile(new Coords(11, y))), Math.sqrt(hit.distance()), .002f);
        }
    }

    @Test
    void changingConcreteFittingRefreshesColumnsAndKeepsArtworkInsideItsHex() {
        BoardScene scene = GpuRiverTerrainSmokeTest.dockScene();
        BoardConcrete.Mode original = BoardConcrete.mode();
        GpuTilesetTerrain terrain = new GpuTilesetTerrain();
        try {
            Coords at = new Coords(10, 3);
            float floor = BoardGeometry.floor(scene);
            BoardConcrete.tune(BoardConcrete.Mode.OFF);
            var hex = terrain.surface(scene, at, floor);
            BoardConcrete.tune(BoardConcrete.Mode.EVERYWHERE);
            var fitted = terrain.surface(scene, at, floor);
            assertNotSame(hex, fitted, "Fitting changes invalidate Tactical View even when the board snapshot is unchanged");
            BoardConcrete shape = BoardConcrete.of(scene);
            for (var tile : scene.tiles()) {
                Vector3 center = BoardGeometry.center(tile.coords(), 0);
                for (int edge = 0; edge < 6; edge++) {
                    for (float fraction : new float[] { .5f, 1 }) {
                        Vector3 rendered = new Vector3(center).lerp(shape.corner(tile.coords(), edge), fraction);
                        Vector3 source = new Vector3(center).lerp(BoardGeometry.corner(tile.coords(), 0, edge), fraction);
                        assertTrue(source.epsilonEquals(GpuTilesetTerrain.artPoint(rendered, tile.coords(), shape), .002f),
                              "Expanded water corners and narrowed concrete retain their own artwork: " + tile.coords());
                    }
                }
            }
        } finally {
            BoardConcrete.tune(original);
            terrain.dispose();
        }
    }

    @Test
    void columnsStandFlatAtTheirLevelAndWallOnlyDownToLowerNeighboursAndTheFloor() {
        // A board at level 2, except the centre's northern neighbour at 0 and its southern neighbour at 3.
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                int level = coords.equals(CENTER.translated(0)) ? 0 : coords.equals(CENTER.translated(3)) ? 3 : 2;
                tiles.add(new BoardScene.Tile(coords, level, -1, false, 0, BoardScene.Surface.GRASS, null, null, null,
                      List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(scene);
        BoardTacticalGeometry.Surface center = GpuTilesetTerrain.column(scene, scene.tile(CENTER), floor);
        for (BoardSurface.Face face : center.top()) {
            for (Vector3 corner : List.of(face.a(), face.b(), face.c())) { assertEquals(2 * BoardGeometry.level(), corner.z); }
        }
        assertEquals(2, center.walls().size(), "Only the drop to the lower neighbour has a wall");
        for (BoardSurface.Face face : center.walls()) {
            assertEquals(0, Math.min(face.a().z, Math.min(face.b().z, face.c().z)));
        }
        BoardTacticalGeometry.Surface corner = GpuTilesetTerrain.column(scene, scene.tile(new Coords(0, 0)), floor);
        assertEquals(floor, corner.walls().stream().mapToDouble(face -> face.b().z).min().orElseThrow(), 1e-4,
              "The board's edges wall down to its floor");

        // A pointer straight down beside the drop picks the flat top the view draws, not a sculpted slope.
        Vector3 nearDrop = BoardGeometry.center(CENTER, 0).add(0, .45f * BoardGeometry.height(), 100);
        BoardGeometry.Hit hit = BoardGeometry.hit(scene, new Ray(nearDrop, new Vector3(0, 0, -1)), scene.tiles(), floor,
              coords -> GpuTilesetTerrain.column(scene, scene.tile(coords), floor));
        assertEquals(CENTER, hit.coords());
        assertEquals(100 - 2 * BoardGeometry.level(), (float) Math.sqrt(hit.distance()), 1e-3);
    }

    @Test
    void aRoadRampsThroughALevelChangeAndBothHexesMeetAtItsMouth() {
        // The centre at level 1 and its northern neighbour at level 0, joined by a road across their shared edge.
        Coords north = CENTER.translated(0);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                int road = coords.equals(CENTER) ? 1 : coords.equals(north) ? 1 << 3 : 0;
                tiles.add(new BoardScene.Tile(coords, coords.equals(north) ? 0 : 1, -1, false, road,
                      BoardScene.Surface.GRASS, null, null, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(scene);
        // Edge 1 of the centre runs between its corners 1 and 2, toward direction 0.
        Vector3 mouth = BoardGeometry.corner(CENTER, 0, 1).lerp(BoardGeometry.corner(CENTER, 0, 2), .5f);
        float meet = .5f * BoardGeometry.level();
        for (Coords coords : List.of(CENTER, north)) {
            BoardTacticalGeometry.Surface column = GpuTilesetTerrain.column(scene, scene.tile(coords), floor);
            assertTrue(column.top().stream().flatMap(face -> Stream.of(face.a(), face.b(), face.c()))
                        .anyMatch(p -> Math.abs(p.z - meet) < .001f && Vector3.dst(p.x, p.y, 0, mouth.x, mouth.y, 0)
                              <= BoardSurface.ROAD_MOUTH * BoardGeometry.hexScale() + .001f),
                  coords + " ramps to the height both hexes meet at in the road's mouth");
            for (BoardSurface.Face wall : column.walls()) {
                if (wall.finish() != BoardSurface.Finish.WALL) { continue; }
                for (Vector3 p : List.of(wall.a(), wall.b(), wall.c())) {
                    assertTrue(Vector3.dst(p.x, p.y, 0, mouth.x, mouth.y, 0)
                          >= BoardSurface.ROAD_MOUTH * BoardGeometry.hexScale() - .001f, coords + " walls off the mouth");
                }
            }
        }
        assertTrue(GpuTilesetTerrain.column(scene, scene.tile(CENTER), floor).walls().stream()
              .anyMatch(face -> face.finish() == BoardSurface.Finish.WALL), "Beside the mouth the step keeps its wall");
    }

    @Test
    void waterColumnsWallFromTheBedAndDryBanksReachDownToIt() {
        Coords north = CENTER.translated(0), south = CENTER.translated(3);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, 0, coords.equals(south) ? -1 : coords.equals(north) ? 4 : 2,
                      false, 0, BoardScene.Surface.GRASS, null, null, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(scene), bed = BoardGeometry.groundZ(scene.tile(CENTER));
        var center = GpuTilesetTerrain.column(scene, scene.tile(CENTER), floor);
        assertEquals(2, center.walls().size(), "Only the deeper northern bed creates an underwater step");
        for (var face : center.walls()) {
            assertEquals(bed, Math.max(face.a().z, Math.max(face.b().z, face.c().z)), .001f);
            assertEquals(BoardGeometry.groundZ(scene.tile(north)), Math.min(face.a().z, Math.min(face.b().z, face.c().z)), .001f);
        }
        var bank = GpuTilesetTerrain.column(scene, scene.tile(south), floor);
        assertTrue(bank.walls().stream().anyMatch(face -> Math.min(face.a().z, Math.min(face.b().z, face.c().z)) == bed),
              "The dry bank meets the bed rather than leaving a hole below the liquid surface");
        var edge = GpuTilesetTerrain.column(scene, scene.tile(north), floor);
        assertTrue(edge.walls().stream().flatMap(face -> Stream.of(face.a(), face.b(), face.c()))
              .allMatch(point -> point.z <= BoardGeometry.groundZ(scene.tile(north))),
              "The board edge must not grow a solid wall up to the water surface");
    }

    @Test
    void openWaterFallsOnlyIntoTheLowerOpenWaterItJoins() {
        // A pool at level 2 among pools at its level; below it to the north open water, to the south frozen water, to
        // the south-west dry land, all at level 1; a higher pool to the north-west.
        Coords north = CENTER.translated(0);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                boolean low = coords.equals(north) || coords.equals(CENTER.translated(3)) || coords.equals(CENTER.translated(4));
                tiles.add(new BoardScene.Tile(coords, low ? 1 : coords.equals(CENTER.translated(5)) ? 3 : 2,
                      coords.equals(CENTER.translated(4)) ? -1 : 1, coords.equals(CENTER.translated(3)), 0,
                      BoardScene.Surface.GRASS, null, null, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        BoardTacticalGeometry.Surface center = GpuTilesetTerrain.column(scene, scene.tile(CENTER), BoardGeometry.floor(scene));
        assertTrue(center.faces().stream().filter(face -> face.finish() == BoardSurface.Finish.BED)
              .allMatch(face -> face.a().z == BoardGeometry.groundZ(scene.tile(CENTER))), "The lakebed retains its depth");
        assertTrue(center.water().stream().allMatch(face -> face.a().z == BoardGeometry.surfaceZ(scene.tile(CENTER))),
              "Water picking retains the liquid surface");
        assertTrue(center.top().containsAll(center.water()), "Overlay support includes the water and its dry shores");
        var frozen = scene.tile(CENTER.translated(3));
        var ice = GpuTilesetTerrain.column(scene, frozen, BoardGeometry.floor(scene));
        assertEquals(ice.top(), ice.faces(), "Ice remains a solid support at the surface");
        assertEquals(1, center.waterfalls().size(), "Only the open pool below takes a fall");
        BoardSurface.Side fall = center.waterfalls().getFirst();
        // Edge 1 of the centre runs between its corners 1 and 2, toward direction 0.
        Vector3 from = BoardGeometry.corner(CENTER, 0, 1), to = BoardGeometry.corner(CENTER, 0, 2);
        assertEquals(0, Vector3.dst(fall.a().x, fall.a().y, 0, from.x, from.y, 0), 1e-3);
        assertEquals(0, Vector3.dst(fall.b().x, fall.b().y, 0, to.x, to.y, 0), 1e-3);
        assertEquals(BoardGeometry.surfaceZ(scene.tile(CENTER)), fall.a().z, 1e-4, "It leaves the pool's surface");
        assertEquals(BoardGeometry.surfaceZ(scene.tile(north)), fall.lowA(), 1e-4, "and lands on the surface below");
    }

    @Test
    void raisedPoolHasRoundedDryBanksSlopingShallowsAndClosedOutsideWalls() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            Coords at = new Coords(x, y);
            tiles.add(new BoardScene.Tile(at, at.equals(CENTER) ? 6 : 1, at.equals(CENTER) ? 1 : -1,
                  false, 0, BoardScene.Surface.GRASS, null, null, null, List.of(), List.of()));
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        var tile = scene.tile(CENTER);
        var surface = GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene));
        float water = BoardGeometry.waterZ(tile), level = tile.elevation() * BoardGeometry.level();
        assertTrue(surface.waterfalls().isEmpty(), "A shore above dry land must not create a waterfall");
        assertTrue(surface.water().size() > 6, "The isolated pool has a curved outline");
        assertTrue(surface.faces().stream().anyMatch(face -> face.finish() == BoardSurface.Finish.BANK
              && area(face) > 0), "Shallows slope down to the unchanged bed depth");
        assertEquals(level, surface.walls().stream().flatMap(f -> Stream.of(f.a(), f.b(), f.c()))
              .mapToDouble(p -> p.z).max().orElseThrow(), .001f, "The raised shore is supported all the way to its rim");
        assertEquals(BoardGeometry.level(), surface.walls().stream().flatMap(f -> Stream.of(f.a(), f.b(), f.c()))
              .mapToDouble(p -> p.z).min().orElseThrow(), .001f, "Walls still end on the lower land");
        Vector3 middle = BoardGeometry.center(CENTER, 0);
        Vector3 nearBank = new Vector3(middle).lerp(BoardGeometry.corner(CENTER, 0, 1)
              .lerp(BoardGeometry.corner(CENTER, 0, 2), .5f), .92f);
        for (var point : List.of(middle, nearBank)) {
            float height = surface.top().stream().map(f -> f.height(point.x, point.y)).max(Float::compare).orElseThrow();
            if (point == middle) { assertEquals(water, height, .001f); }
            else { assertTrue(height > water && height <= level, "The dry shoreline slopes down to the liquid"); }
            var hit = BoardGeometry.hit(scene, new Ray(new Vector3(point.x, point.y, level + 100), new Vector3(0, 0, -1)),
                  scene.tiles(), BoardGeometry.floor(scene), coords -> GpuTilesetTerrain.column(scene, scene.tile(coords),
                        BoardGeometry.floor(scene)));
            assertEquals(CENTER, hit.coords());
            assertEquals(level + 100 - height, Math.sqrt(hit.distance()), .001f, "Picking follows the finished shore");
        }
    }

    @Test
    void everyCoastPatternCoversItsHexAndKeepsSharedWaterMouthsOpen() {
        for (int mask = 0; mask < 64; mask++) {
            List<BoardScene.Tile> tiles = new ArrayList<>();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                Coords at = new Coords(x, y);
                boolean dry = false;
                for (int d = 0; d < 6; d++) { dry |= (mask & 1 << d) != 0 && at.equals(CENTER.translated(d)); }
                tiles.add(new BoardScene.Tile(at, 0, dry ? -1 : 2, false, 0, BoardScene.Surface.GRASS,
                      null, null, null, List.of(), List.of()));
            }
            BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
            float floor = BoardGeometry.floor(scene), water = BoardGeometry.waterZ(scene.tile(CENTER));
            var surface = GpuTilesetTerrain.column(scene, scene.tile(CENTER), floor);
            double footprint = .75 * BoardGeometry.width() * BoardGeometry.height();
            assertEquals(footprint, surface.top().stream().mapToDouble(GpuTilesetTerrainTest::area).sum(), .05,
                  "Dry shore and liquid cover the hex once, mask=" + mask);
            if (mask != 0) {
                assertTrue(surface.faces().stream().anyMatch(f -> f.finish() == BoardSurface.Finish.BANK && area(f) > .01),
                      "Even a lone bank has sloping shallows, mask=" + mask);
            }
            for (var face : surface.faces()) {
                assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z >= -.001f,
                      "Submerged banks never fold back under the dry shore, mask=" + mask);
            }
            for (var face : surface.top()) {
                assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z >= -.001f,
                      "Shore triangles never fold, mask=" + mask);
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(BoardGeometry.contains(CENTER, p.x, p.y), "The bank stays inside its column, mask=" + mask);
                }
            }
            for (int edge = 0; edge < 6; edge++) {
                int direction = BoardGeometry.edgeDirection(edge);
                if ((mask & 1 << direction) != 0) { continue; }
                Coords next = CENTER.translated(direction);
                var neighbor = GpuTilesetTerrain.column(scene, scene.tile(next), floor);
                for (float t : new float[] { .05f, .5f, .95f }) {
                    Vector3 p = BoardGeometry.corner(CENTER, 0, edge).lerp(BoardGeometry.corner(CENTER, 0, edge + 1), t);
                    for (var side : List.of(surface, neighbor)) {
                        assertEquals(water, side.water().stream().map(f -> f.height(p.x, p.y)).max(Float::compare).orElseThrow(),
                              .001f, "Both water tiles meet across the whole mouth, mask=" + mask);
                    }
                }
            }
        }
    }

    @Test
    void changingLandMaterialInvalidatesItsBorrowingBankButKeepsDistantColumns() {
        BoardScene scene = GpuRiverTerrainSmokeTest.scene(BoardScene.Surface.GRASS);
        GpuTilesetTerrain terrain = new GpuTilesetTerrain();
        try {
            Coords river = new Coords(2, 1), land = river.translated(4), distant = new Coords(10, 10);
            float floor = BoardGeometry.floor(scene);
            var before = terrain.surface(scene, river, floor);
            var retained = terrain.surface(scene, distant, floor);
            var repainted = new ArrayList<>(scene.tiles());
            // The adjacent bank must refresh when its source land material changes.
            var original = scene.tile(land);
            repainted.set(land.getX() * scene.height() + land.getY(), new BoardScene.Tile(land, original.elevation(), -1,
                  false, 0, BoardScene.Surface.SAND, original.ground(), null, null, List.of(), List.of()));
            scene = scene.withTiles(repainted);
            assertNotSame(before, terrain.surface(scene, river, floor));
            assertSame(retained, terrain.surface(scene, distant, floor));
        } finally { terrain.dispose(); }
    }

    @Test
    void naturalBanksMeetFittedQuaysWithoutFoldingOrLosingCoverage() {
        for (var scene : List.of(GpuRiverTerrainSmokeTest.dockScene(),
              GpuRiverTerrainSmokeTest.quayScene(BoardScene.Surface.GRASS))) {
            var concrete = BoardConcrete.of(scene);
            float floor = BoardGeometry.floor(scene);
            for (var tile : scene.tiles()) {
                if (!tile.liquid().present() || tile.frozen()) { continue; }
                var surface = GpuTilesetTerrain.column(scene, tile, floor);
                Vector3 center = BoardGeometry.center(tile.coords(), 0);
                double footprint = 0;
                for (int edge = 0; edge < 6; edge++) {
                    footprint += area(new BoardSurface.Face(center, concrete.corner(tile.coords(), edge),
                          concrete.corner(tile.coords(), edge + 1), BoardSurface.Finish.TOP));
                }
                assertEquals(footprint, surface.top().stream().mapToDouble(GpuTilesetTerrainTest::area).sum(), .08,
                      "Land and water still cover the fitted footprint: " + tile.coords());
                for (var face : surface.top()) {
                    assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z >= -.001f,
                          "Natural banks stay unfolded beside a fitted quay: " + tile.coords());
                }
            }
        }
    }

    private static double area(BoardSurface.Face face) {
        return Math.abs((face.b().x - face.a().x) * (face.c().y - face.a().y)
              - (face.b().y - face.a().y) * (face.c().x - face.a().x)) / 2;
    }
}
