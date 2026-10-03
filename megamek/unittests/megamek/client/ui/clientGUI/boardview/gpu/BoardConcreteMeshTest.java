/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardConcreteMeshTest {
    @Test
    void plainSlabsAndTwoLevelPanelsUseOnlyTheirCornersAtEveryDetail() {
        BoardScene scene = scene(at -> at.getX() >= 8 ? 2 : 0, false);
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
                float edgeLength = BoardGeometry.corner(at, 2, 0).dst(BoardGeometry.corner(at, 2, 1));
                assertEquals(2 * edgeLength * 2 * BoardGeometry.level(), wallArea, .01f, "Panels have no holes or overlaps");
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

    @Test
    void tallSlabsRetainBedrockAndEveryVertexOfTheirSharedUnderside() {
        for (int levels : new int[] { 3, 6 }) {
            BoardScene scene = scene(at -> at.getX() >= 8 ? levels : 0, false);
            Coords at = new Coords(8, 4);
            for (TerrainLod lod : TerrainLod.values()) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(at), lod);
                assertEquals(6, surface.faces.size(), "Tall bedrock must not subdivide the slab");
                float underside = (levels - 1) * BoardGeometry.level();
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                List<BoardSurface.Face> slab = walls.stream()
                      .filter(f -> Math.min(f.a().z, Math.min(f.b().z, f.c().z)) >= underside - .001f).toList();
                List<BoardSurface.Face> rock = walls.stream().filter(f -> !slab.contains(f)).toList();
                assertTrue(!slab.isEmpty() && !rock.isEmpty(), "The slab still rests on real bedrock");
                assertEquals(4, slab.size(), "Two exposed rectangular slab panels: " + lod);
                Set<Vector3> upper = vertices(slab), lower = vertices(rock);
                for (Vector3 p : upper) {
                    if (Math.abs(p.z - underside) < .001f) {
                        assertTrue(lower.stream().anyMatch(q -> q.epsilonEquals(p, .001f)), "Slab and bedrock share the underside");
                    }
                }
                assertTrue(lower.stream().anyMatch(p -> {
                    if (p.z <= 0 || p.z >= underside) { return false; }
                    for (int e = 0; e < 6; e++) {
                        Vector3 a = BoardGeometry.corner(at, 0, e), b = BoardGeometry.corner(at, 0, e + 1);
                        if (Math.abs(new Vector3(b).sub(a).crs(new Vector3(p.x, p.y, 0).sub(a)).z)
                              / a.dst(b) < .1f * BoardRelief.metres(1)) { return false; }
                    }
                    return true;
                }), "Bedrock retains its displaced profile");
            }
        }
    }

    private static Set<Vector3> vertices(List<BoardSurface.Face> faces) {
        Set<Vector3> vertices = new HashSet<>();
        for (var face : faces) { vertices.addAll(List.of(face.a(), face.b(), face.c())); }
        return vertices;
    }

    private static BoardScene scene(ToIntFunction<Coords> elevation, boolean natural) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 11; x++) {
            for (int y = 0; y < 9; y++) {
                Coords at = new Coords(x, y);
                var family = natural && x >= 8 ? BoardScene.Surface.GRASS : BoardScene.Surface.CONCRETE;
                tiles.add(new BoardScene.Tile(at, elevation.applyAsInt(at), -1, false, 0, family,
                      null, null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 11, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
