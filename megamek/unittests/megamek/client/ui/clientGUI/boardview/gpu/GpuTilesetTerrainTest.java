/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(center.faces().stream().allMatch(face -> face.a().z == BoardGeometry.groundZ(scene.tile(CENTER))),
              "Artwork's solid receiver is the lakebed");
        assertTrue(center.top().stream().allMatch(face -> face.a().z == BoardGeometry.surfaceZ(scene.tile(CENTER))),
              "Tactical overlays and water picking retain the liquid surface");
        assertEquals(center.top(), center.water());
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
}
