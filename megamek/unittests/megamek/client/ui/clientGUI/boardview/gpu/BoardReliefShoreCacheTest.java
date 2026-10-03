/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardReliefShoreCacheTest {
    @Test
    void mixedLiquidFieldsStayExactAcrossQueryOrderOwnersAndBoardEdges() {
        try (var ignored = TerrainSettings.use(TerrainSettings.capture())) {
            BoardScene scene = scene(false);
            List<Vector3> points = points();
            int[][] expected = new int[points.size()][2];
            int differentLiquids = 0;
            Coords anchor = new Coords(3, 3);
            for (int i = 0; i < points.size(); i++) {
                Vector3 p = points.get(i);
                for (int liquid = 0; liquid < 2; liquid++) {
                    // Each reference begins cold and queries only one liquid.
                    expected[i][liquid] = bits(relief(scene, anchor).shore(p.x, p.y, liquid == 1));
                }
                if (expected[i][0] != expected[i][1]) { differentLiquids++; }
            }
            assertTrue(differentLiquids > 0, "The two liquid fields must actually differ in this fixture");
            for (Coords owner : List.of(anchor, anchor.translated(2), new Coords(0, 0), new Coords(8, 8))) {
                var relief = relief(scene, owner);
                for (int pass = 0; pass < 3; pass++) {
                    for (int at = 0; at < points.size(); at++) {
                        int i = pass == 1 ? points.size() - 1 - at : at;
                        Vector3 p = points.get(i);
                        for (int order = 0; order < 2; order++) {
                            int liquid = (i + pass + order) & 1;
                            assertEquals(expected[i][liquid], bits(relief.shore(p.x, p.y, liquid == 1)),
                                  "Shared shore at " + p + ", owner=" + owner + ", molten=" + (liquid == 1)
                                        + ", pass=" + pass);
                        }
                    }
                }
            }
        }
    }

    @Test
    void newSceneDoesNotReuseThePreviousLiquidClassification() {
        try (var ignored = TerrainSettings.use(TerrainSettings.capture())) {
            Coords at = new Coords(3, 3);
            var before = relief(scene(false), at);
            var after = relief(scene(true), at);
            int changed = 0;
            for (Vector3 p : points()) {
                int old = bits(before.shore(p.x, p.y, true));
                int next = bits(after.shore(p.x, p.y, true));
                assertEquals(next, bits(after.shore(p.x, p.y, true)), "New scene remains stable after warming");
                assertEquals(old, bits(before.shore(p.x, p.y, true)), "Old captured scene retains its own field");
                if (old != next) { changed++; }
            }
            assertTrue(changed > 0, "Changing liquid types must change the molten shore field");
        }
    }

    private static int bits(float value) {
        assertTrue(Float.isFinite(value), "Shore queries must stay finite");
        return Float.floatToRawIntBits(value);
    }

    private static BoardRelief relief(BoardScene scene, Coords at) {
        return new BoardRelief(scene, scene.tile(at), BoardSurface.ramps(scene, scene.tile(at)));
    }

    private static List<Vector3> points() {
        List<Vector3> result = new ArrayList<>();
        // Includes off-board clamping and points beyond any owner's six-cell memo window.
        for (int x : new int[] { -8, -1, 0, 3, 4, 8, 15 }) {
            for (int y : new int[] { -8, 0, 3, 4, 8, 15 }) {
                result.add(BoardGeometry.center(new Coords(x, y), 0).add(.25f, -.75f, 0));
            }
        }
        for (Coords at : List.of(new Coords(0, 0), new Coords(3, 3), new Coords(4, 3))) {
            for (int edge = 0; edge < 6; edge++) {
                result.add(BoardGeometry.corner(at, 0, edge));
                result.add(BoardGeometry.corner(at, 0, edge).lerp(BoardGeometry.corner(at, 0, edge + 1), .5f));
            }
        }
        return result;
    }

    private static BoardScene scene(boolean swapLiquids) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                boolean pool = x >= 1 && x <= 7 && y >= 2 && y <= 6;
                boolean molten = (x >= 4) != swapLiquids;
                BoardLiquid liquid = !pool ? BoardLiquid.NONE
                      : molten ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.WATER;
                BoardScene.Surface family = x == 4 && y == 1 ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND;
                tiles.add(new BoardScene.Tile(new Coords(x, y), pool ? 0 : 2, pool ? 1 : -1, false, 0, family,
                      null, null, null, null, null, List.of(), List.of(), liquid, null, !(x == 0 && y == 0)));
            }
        }
        return new BoardScene(0, 9, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
