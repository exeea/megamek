/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardCliffColumnTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void minesCliffColumnsDoNotFoldAcrossTheNarrowedCorner(TerrainLod detail) {
        var scene = BoardCliffSeamTest.scene(new File("data/boards/Deserts/16x17 Mines 1.board"));
        var at = new Coords(12, 3);
        var surface = new BoardSurface(scene, scene.tile(at), detail);
        var outward = BoardGeometry.center(new Coords(11, 3), 0).sub(BoardGeometry.center(at, 0)).nor();
        int checked = 0;
        for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
            if (face.landEdge() != 3 || face.finish() != BoardSurface.Finish.WALL) { continue; }
            float top = Math.max(face.a().z, Math.max(face.b().z, face.c().z));
            if (top > .5f * BoardGeometry.level()) { continue; }
            // Reduced-detail corners stitch rows at different heights. This checks the horizontal column edges,
            // whose reversal made the fin, rather than those diagonal stitching triangles.
            if (face.a().z != face.b().z && face.b().z != face.c().z && face.c().z != face.a().z) { continue; }
            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            assertTrue(normal.dot(outward) >= 0,
                  "The lower wall at Mines 1304→1204 must not turn inside out into a hanging strip: " + face);
            checked++;
        }
        assertTrue(checked > 0, "The regression must examine the exposed lower cliff at " + detail);
    }
}
