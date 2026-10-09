/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.utils.FloatArray;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** No ground cover grows through paint: every planter of {@link BoardPlants} honours {@link BoardDecals.Opacity}. */
class BoardPaintCoverTest {
    private static final Coords AT = new Coords(4, 4);

    private static List<BoardDecals.Stamp> stamp(BoardDecoration object) { return List.of(new BoardDecals.Stamp(AT, object)); }

    private static BoardTacticalGeometry.Surface support(BoardScene scene) {
        return BoardTacticalGeometry.Surface.of(new BoardSurface(scene, scene.tile(AT)), scene, BoardGeometry.floor(scene));
    }

    /** Roots as (x, y, z, rank) entries. */
    private static Set<List<Float>> roots(FloatArray roots) {
        Set<List<Float>> result = new HashSet<>();
        for (int i = 0; i < roots.size; i += 4) {
            result.add(List.of(roots.get(i), roots.get(i + 1), roots.get(i + 2), roots.get(i + 3)));
        }
        return result;
    }

    /** An opaque car park stretched over the whole hex and a little beyond. */
    private static final BoardDecoration COVER = new BoardDecoration("lot", "decal", "decal/car-park/straight-12", null, 0, 0, 0,
          false, 1, BoardDecoration.Placement.ground(), 0, false).withStretch(new BoardDecoration.Stretch(1.6, 10, 1));

    @Test
    void bladesStayOffAnEmblemYetGrowInItsTransparentCornersAndKeepTheirPlacesElsewhere() {
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var tile = scene.tile(AT);
        var cross = new BoardDecoration("cross", "decal", "decal/emblems/red-cross", null, .05, 0, 20, false, .5,
              BoardDecoration.Placement.ground(), 0, false);
        var paint = BoardDecals.Opacity.of(tile, stamp(cross));
        var bare = roots(GpuGroundCover.plant(scene, tile, support(scene), null));
        var painted = roots(GpuGroundCover.plant(scene, tile, support(scene), paint));
        assertTrue(bare.containsAll(painted), "Paint only removes roots: the others keep their places and ranks");
        var uv = BoardDecals.paintUv(cross);
        float x0 = BoardGeometry.centerX(AT) + (float) cross.x() * BoardGeometry.width(), y0 = BoardGeometry.centerY(AT);
        int corners = 0;
        for (var root : bare) {
            float opacity = paint.at(root.get(0), root.get(1));
            if (painted.contains(root)) {
                assertTrue(opacity < .5f, "No blade through the emblem");
                float u = uv.u(root.get(0) - x0, root.get(1) - y0), v = uv.v(root.get(0) - x0, root.get(1) - y0);
                if (u > 0 && u < 1 && v > 0 && v < 1) { corners++; }
            } else {
                assertTrue(opacity > 0, "A root outside the paint and its margin stays");
            }
        }
        assertTrue(painted.size() < bare.size() - 200, "The emblem clears its grass: " + painted.size() + " of " + bare.size());
        assertTrue(corners > 50, "Grass grows inside the emblem's square where its image is transparent: " + corners);
    }

    @Test
    void bankTurfReedsAndCropsLoseTheirRootsUnderOpaquePaint() {
        var bank = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, c.getX() < 5 ? 2 : 0, -1, 0));
        var tile = bank.tile(AT);
        var paint = BoardDecals.Opacity.of(tile, stamp(COVER));
        assertTrue(GpuBankTurf.plant(bank, tile, support(bank), null).size > 0, "The bank grows turf");
        assertNull(GpuBankTurf.plant(bank, tile, support(bank), paint));
        for (var kind : List.of(BoardScene.Biome.MARSH, BoardScene.Biome.FIELD)) {
            var scene = BoardSurfaceBlendTest.scene(c -> BoardBiomeTest.tile(c, kind, 0));
            var biome = scene.tile(AT);
            var covered = BoardDecals.Opacity.of(biome, stamp(COVER));
            if (kind == BoardScene.Biome.MARSH) {
                assertTrue(GpuBiomeVegetation.plantReeds(scene, biome, support(scene), 1, null).size > 0, "The marsh grows reeds");
                assertEquals(0, GpuBiomeVegetation.plantReeds(scene, biome, support(scene), 1, covered).size);
            } else {
                assertTrue(GpuBiomeVegetation.plant(scene, biome, support(scene), null).roots().size > 0, "The field grows crops");
                assertEquals(0, GpuBiomeVegetation.plant(scene, biome, support(scene), covered).roots().size);
            }
        }
    }
}
