/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardBackdropBoundaryTest {
    private static final Coords CENTER = new Coords(2, 2);

    @Test
    void solidIslandsKeepAllTheirExposedSidesBesideAerospacePickingPlanes() {
        for (String backdrop : List.of("space:1", "sky:1")) {
            for (int emptyElevation : new int[] { -2, 1, 4 }) {
                var scene = scene(backdrop, emptyElevation, false);
                var tile = scene.tile(CENTER);
                float floor = BoardGeometry.floor(scene);
                for (TerrainLod lod : TerrainLod.values()) {
                    var surface = new BoardSurface(scene, tile, lod);
                    var sides = surface.sides(scene, floor, Map.of());
                    assertEquals(6, sides.stream().map(BoardSurface.Side::edge).distinct().count());
                    for (var side : sides) {
                        assertEquals(floor, side.lowA(), .001f);
                        assertEquals(floor, side.lowB(), .001f);
                        assertEquals(BoardGeometry.level(), side.a().z, .001f);
                        assertEquals(BoardGeometry.level(), side.b().z, .001f);
                    }
                    assertFalse(surface.walls(scene, floor).isEmpty());
                }
            }
        }
    }

    @Test
    void realSolidNeighborsStillHideSharedSidesAndBackdropEditsInvalidateTheShape() {
        var empty = scene("space:1", 1, false);
        var filled = scene("space:1", 1, true);
        var cache = new BoardSurface.Cache();
        var exposed = cache.get(empty, empty.tile(CENTER));
        var joined = cache.get(filled, filled.tile(CENTER));
        assertNotSame(exposed, joined);
        assertEquals(6, exposed.sides(empty, BoardGeometry.floor(empty), Map.of()).size());
        assertEquals(5, joined.sides(filled, BoardGeometry.floor(filled), Map.of()).size());
        var space = cache.get(empty, empty.tile(CENTER.translated(0)));
        assertTrue(space.walls(empty, BoardGeometry.floor(empty)).isEmpty());
    }

    private static BoardScene scene(String backdrop, int emptyElevation, boolean solidNorth) {
        var tiles = new ArrayList<BoardScene.Tile>();
        var pool = new BoardScene.PixelPool();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                var at = new Coords(x, y);
                boolean solid = at.equals(CENTER) || solidNorth && at.equals(CENTER.translated(0));
                var hex = new Hex(solid ? 1 : emptyElevation, solid ? "pavement:1" : backdrop, "lunar");
                var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(hex, artwork, null, pool, ignored -> null));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
