/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A road's graded earthwork and the native slope below it form one continuously shaded surface. */
class BoardRoadEmbankmentTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void mesaRoadBankSharesItsNormalsWithTheReceivingSlope(TerrainLod lod) throws Exception {
        var scene = BoardCliffSeamTest.scene(new File(
              "data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board"));
        // The line reported above 2823 is the graded southern rim of 2822, continuing around its level eastern rim.
        var surface = new BoardSurface(scene, scene.tile(new Coords(27, 21)), lod);
        assertTrue(surface.ramps != 0);
        assertJoinedNormals(scene, surface, List.of(4, 5), 12);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void aGradedBankSharesTheCarrierNormalInEveryDirection(int direction) throws Exception {
        var at = BoardRoadTest.CENTER;
        var approach = at.translated(direction);
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(at) ? 1 << direction : 0,
              c.equals(at) ? 2 : c.equals(approach) ? 1 : 0, BoardScene.Surface.SAND));
        var surface = new BoardSurface(scene, scene.tile(at));
        assertTrue(surface.ramps != 0);
        assertJoinedNormals(scene, surface, List.of(0, 1, 2, 3, 4, 5), 12);
    }

    @SuppressWarnings("unchecked")
    private static void assertJoinedNormals(BoardScene scene, BoardSurface surface, List<Integer> edges,
          int minimum) throws Exception {
        var top = surface.groundFaces().stream().filter(face -> face.finish() == BoardSurface.Finish.TOP).toList();
        // Compare to the renderer's normal field, not a second implementation of the contact calculation.
        var rendererNormals = GpuTerrain.class.getDeclaredMethod("wallNormals", List.class);
        rendererNormals.setAccessible(true);
        var normals = (Map<Vector3, Vector3>) rendererNormals.invoke(null, top);
        var walls = surface.walls(scene, BoardGeometry.floor(scene));
        int checked = 0;
        float tolerance = .002f * BoardGeometry.hexScale();
        for (var face : walls) {
            if (!edges.contains(face.landEdge())) { continue; }
            for (var p : List.of(face.a(), face.b(), face.c())) {
                if (p.z < .5f * BoardGeometry.hexScale()) { continue; }
                Map.Entry<Vector3, Vector3> nearest = null;
                float distance = tolerance * tolerance;
                for (var entry : normals.entrySet()) {
                    float d = p.dst2(entry.getKey());
                    if (d < distance) { distance = d; nearest = entry; }
                }
                if (nearest == null) { continue; }
                Vector3 expected = surface.roadNormal(nearest.getKey(), nearest.getValue());
                var shade = surface.relief.shade(p);
                assertTrue(shade != null && shade.normal().dot(expected) > .9999f,
                      "Road/cliff normal split at " + p + ": top=" + expected + ", cliff=" + shade);
                assertEquals(1, shade.occlusion(), .00001f, "The same road contact must have one ambient shading value");
                checked++;
            }
        }
        assertTrue(checked >= minimum, "Must exercise the actual emitted road-to-slope boundary: " + checked);
    }
}
