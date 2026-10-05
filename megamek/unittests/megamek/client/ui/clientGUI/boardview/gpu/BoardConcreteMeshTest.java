/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardConcreteMeshTest {
    @Test
    void commCenterBorderSlopeUsesItsActualFaceNormals() throws Exception {
        BoardScene scene = commCenter();
        for (TerrainLod lod : TerrainLod.values()) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(new Coords(0, 10)), lod);
            var panels = surface.walls(scene, BoardGeometry.floor(scene)).stream()
                  .filter(face -> face.landEdge() == 4).toList();
            assertTrue(panels.size() >= 2, "The two-level concrete drop beside 0112 is present");
            for (var face : panels) {
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                for (var vertex : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(surface.relief.shade(vertex).normal().epsilonEquals(normal, .0001f),
                          "A concrete face must not inherit its horizontal rim's texture projection: " + lod + " " + face);
                }
            }
        }
    }

    @Test
    void commCenterFittedSlopeKeepsAStraightRimAndConstantWidth() throws Exception {
        BoardScene scene = commCenter();
        assertEquals(BoardConcrete.Mode.EVERYWHERE, BoardConcrete.mode(), "Exercise the default fitted concrete outline");
        BoardConcrete shape = BoardConcrete.of(scene);
        Coords first = new Coords(1, 14), last = new Coords(2, 15);
        Vector3 a = shape.corner(first, 3), direction = shape.corner(last, 5).sub(a).nor();
        float rimDistance = Float.NaN;
        for (Coords at : List.of(first, last)) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(at));
            for (int k = 3; k <= 5; k++) {
                Vector3 rim = surface.relief.seam(k, k, 0).sub(a);
                float distance = direction.x * rim.y - direction.y * rim.x;
                if (Float.isNaN(rimDistance)) { rimDistance = distance; }
                assertEquals(rimDistance, distance, .003f,
                      "The rim above 0216 follows the fitted straight boundary: " + at + " corner " + k);
            }
            for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                if (face.landEdge() != 3 && face.landEdge() != 4) { continue; }
                for (var vertex : List.of(face.a(), face.b(), face.c())) {
                    Vector3 offset = new Vector3(vertex).sub(a);
                    float distance = direction.x * offset.y - direction.y * offset.x;
                    float expected = rimDistance * (2 * vertex.z / BoardGeometry.level() - 1);
                    assertEquals(expected, distance, .003f,
                          "The entire concrete slope remains planar across former hex edges at " + at);
                }
            }
        }
    }

    private static BoardScene commCenter() throws Exception {
        return BoardCliffSeamTest.capturedScene("GrassLands/16x17 Grasslands River CommCenter.board");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 6 })
    void markedCliffsStayPlanarForTheirFullHeightAtEveryDetail(int levels) {
        BoardScene scene = scene(at -> at.getX() >= 8 ? levels : 0, false, 63);
        for (TerrainLod lod : TerrainLod.values()) {
            for (Coords at : List.of(new Coords(2, 2), new Coords(7, 4), new Coords(8, 4))) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(at), lod);
                assertEquals(6, surface.faces.size(), "One center fan, including across chunk boundaries: " + lod);
                float area = 0;
                Vector3 center = BoardGeometry.center(at, scene.tile(at).elevation());
                for (var face : surface.faces) {
                    Vector3 cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(cross.z > 0, "Slab faces point up");
                    area += cross.z / 2;
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        assertEquals(center.z, p.z, .001f);
                        assertTrue(surface.relief.shade(p).normal().epsilonEquals(Vector3.Z, .0001f),
                              "Planar tops keep a vertical normal");
                    }
                }
                assertEquals(BoardGeometry.width() * BoardGeometry.height() * .75f, area, .01f);
                for (int e = 0; e < 6; e++) {
                    for (float t : new float[] { .1f, .5f, .99f }) {
                        Vector3 p = new Vector3(center).lerp(BoardGeometry.corner(at, scene.tile(at).elevation(), e), t);
                        assertEquals(center.z, surface.height(p.x, p.y), .001f, "Picking/support covers the whole slab");
                    }
                }
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                if (at.getX() < 8) { assertTrue(walls.isEmpty()); continue; }
                assertEquals(4, walls.size(), "Two exposed rectangular panels: " + lod);
                float wallArea = 0;
                for (var face : walls) {
                    Vector3 cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    wallArea += cross.len() / 2;
                    assertTrue(cross.dot(new Vector3(face.a()).sub(center)) > 0, "Panels face out");
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(surface.relief.shade(p).normal().epsilonEquals(new Vector3(cross).nor(), .0001f));
                        assertEquals(p.z / BoardRelief.metres(1), surface.relief.shade(p).rim(), .001f);
                        assertEquals((center.z - p.z) / BoardRelief.metres(1), surface.relief.shade(p).foot(), .001f);
                    }
                }
                float edgeLength = BoardGeometry.corner(at, levels, 0).dst(BoardGeometry.corner(at, levels, 1));
                assertEquals(2 * edgeLength * levels * BoardGeometry.level(), wallArea, .01f, "Panels have no holes or overlaps");
            }
        }
    }

    @Test
    void naturalNeighborsMeetTheStraightSlabAcrossMixedDetailChunks() {
        BoardScene scene = scene(at -> 0, true);
        Coords at = new Coords(7, 4);
        BoardSurface concrete = new BoardSurface(scene, scene.tile(at), TerrainLod.DISTANT);
        assertEquals(6, concrete.faces.size(), "Adjacent soil must not subdivide the slab");
        int compared = 0;
        for (int e = 0; e < 6; e++) {
            Coords other = at.translated(BoardGeometry.edgeDirection(e));
            if (other.getX() != 8) { continue; }
            BoardSurface natural = new BoardSurface(scene, scene.tile(other), TerrainLod.FULL);
            Set<Vector3> own = vertices(concrete.faces), adjacent = vertices(natural.faces);
            for (int i = 0; i <= 1; i++) {
                Vector3 p = concrete.relief.seam(e, e, i);
                p.z = concrete.relief.groundHeight(p.x, p.y);
                assertTrue(own.stream().anyMatch(q -> q.epsilonEquals(p, .001f)), "Concrete keeps the straight edge");
                assertTrue(adjacent.stream().anyMatch(q -> q.epsilonEquals(p, .001f)), "Both chunk details agree");
                compared++;
            }
            for (int i = 1; i < 10; i++) {
                Vector3 p = concrete.relief.seam(e, e, i / 10f);
                assertEquals(0, concrete.height(p.x, p.y), .001f);
                assertEquals(0, natural.height(p.x, p.y), .001f, "Natural ground meets the whole straight seam");
            }
        }
        assertTrue(compared >= 4, "Exercise both shared edges, including their endpoints");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void unmarkedConcreteStepsHaveSharpPlanarSlopesAndMatchingPicking(int levels) {
        BoardSculptTest.withTransitions(true, () -> {
            BoardScene scene = scene(at -> at.getX() >= 8 ? levels : 0, false);
            Coords at = new Coords(8, 4);
            for (TerrainLod lod : TerrainLod.values()) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(at), lod);
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                assertEquals(4, walls.size(), "Two planar slopes need two triangles each: " + lod);
                for (var face : walls) {
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    assertTrue(normal.z > .1f && normal.z < .95f, "The side slopes between the two levels");
                    for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(surface.relief.shade(p).normal().epsilonEquals(normal, .0001f), "Slopes have sharp arrises");
                    }
                    Vector3 middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    Ray ray = new Ray(new Vector3(middle.x, middle.y, 500), new Vector3(0, 0, -1));
                    BoardGeometry.Hit hit = BoardGeometry.hit(scene, ray);
                    assertNotNull(hit);
                    assertEquals(middle.z, 500 - Math.sqrt(hit.distance()), .002, "Picking follows the drawn slope");
                }
            }
        });
    }

    private static Set<Vector3> vertices(List<BoardSurface.Face> faces) {
        Set<Vector3> vertices = new HashSet<>();
        for (var face : faces) { vertices.addAll(List.of(face.a(), face.b(), face.c())); }
        return vertices;
    }

    private static BoardScene scene(ToIntFunction<Coords> elevation, boolean natural) {
        return scene(elevation, natural, 0);
    }

    private static BoardScene scene(ToIntFunction<Coords> elevation, boolean natural, int cliffs) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 11; x++) {
            for (int y = 0; y < 9; y++) {
                Coords at = new Coords(x, y);
                Hex hex = new Hex(elevation.applyAsInt(at));
                if (!natural || x < 8) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                hex.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, cliffs));
                var art = new BoardArtwork.HexImage(at, null, null, null, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(hex, art, null, new BoardScene.PixelPool()));
            }
        }
        return new BoardScene(0, 11, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
