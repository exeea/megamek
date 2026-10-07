/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class GpuWaterBedMeshTest {
    @ParameterizedTest
    @ValueSource(ints = { 1, 4 })
    void levelPoolBedsStayInsideTheWaterAlongCurvedBanksAndMouths(int depth) {
        for (boolean mouths : new boolean[] { false, true }) {
            var center = BoardSurfaceBlendTest.CENTER;
            var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.SAND,
                  0, c.equals(center) || mouths && (c.equals(center.translated(1)) || c.equals(center.translated(4)))
                        ? depth : -1, 0));
            var original = new BoardSurface(scene, scene.tile(center));
            // Cached water geometry must make the same coverage decision as a freshly built surface.
            for (var surface : List.of(original, original.waterGeometry().surface(scene))) {
                assertTrue(surface.bedUnderLevelWater());
                for (var face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.BED) { continue; }
                    for (int a = 0; a <= 4; a++) for (int b = 0; b <= 4 - a; b++) {
                        var p = new Vector3(face.a()).scl(a / 4f).mulAdd(face.b(), b / 4f)
                              .mulAdd(face.c(), (4 - a - b) / 4f);
                        float water = BoardSurface.sampleHeight(surface.waterFaces, p.x, p.y, Float.NaN);
                        assertEquals(BoardGeometry.waterZ(surface.tile), water, .001f,
                              "Unclipped beds must stay within the actual water footprint, including concave banks");
                        assertTrue(p.z <= water + .001f, "An unclipped bed cannot expose a dry bar");
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void levelWaterKeepsTheSparseBedThroughMaterialPreparation(TerrainLod lod) {
        for (boolean varyingDepth : new boolean[] { false, true }) {
            var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.SAND,
                  0, varyingDepth ? 1 + Math.floorMod(c.getX() + c.getY(), 4) : 2, 0));
            var tile = scene.tile(BoardSurfaceBlendTest.CENTER);
            var surface = new BoardSurface(scene, tile, lod);
            var bed = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.BED).toList();
            var plan = GpuTerrain.prepareSculpt(scene, tile, surface, BoardGeometry.floor(scene), lod, new HashMap<>());
            var drawn = plan.blended().values().stream().flatMap(List::stream).toList();
            System.out.printf("WATER BED lod=%s varyingDepth=%s source=%d rendered=%d%n", lod, varyingDepth,
                  bed.size(), drawn.size());
            assertEquals(bed.size(), drawn.size(), "Water's internal diagonals must not subdivide the bed");
            if (!varyingDepth) { assertEquals(6, drawn.size(), "An open flat bed needs only six triangles"); }
            double area = 0;
            for (var triangle : drawn) {
                var a = triangle.a().vertex().position;
                var b = triangle.b().vertex().position;
                var c = triangle.c().vertex().position;
                float cross = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).z;
                assertTrue(cross > 0, "The bed stays upward-facing");
                area += cross * .5;
                for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                    var p = point.vertex().position;
                    assertEquals(p.z, BoardSurface.sampleHeight(bed, p.x, p.y, Float.NaN), .001f,
                          "Depth transitions retain the canonical bed height");
                    assertEquals(1, point.cover().sand(), .00001f);
                    assertTrue(point.vertex().color.b < .125f && point.vertex().color.a < .25f,
                          "Every bed vertex retains water optics");
                }
            }
            assertEquals(BoardGeometry.width() * BoardGeometry.height() * .75, area, .05,
                  "The bed still covers the complete hex");
        }
    }
}
