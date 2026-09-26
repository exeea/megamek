/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardWetCliffTest {
    private static final Coords LAND = new Coords(3, 3);

    @Test
    void cliffClassificationCountsBedDepthAndLeavesClimbableSlopesAlone() {
        var original = BoardRelief.tuning();
        try {
            tune(true);
            for (int level : new int[] { 1, 2 }) {
                for (int depth : new int[] { 0, 1, 2 }) {
                    var scene = island(level, depth);
                    for (int direction = 0; direction < 6; direction++) {
                        var coords = LAND.translated(direction);
                        var surface = new BoardSurface(scene, scene.tile(coords));
                        int edge = Math.floorMod(1 - (direction + 3), 6);
                        assertEquals(level + depth >= 3, surface.relief.wetCliff(edge));
                        assertTrue(surface.waterfalls.isEmpty(), "Land cliffs do not create waterfalls");
                        assertEquals(BoardGeometry.waterZ(surface.tile), surface.waterHeight(
                              BoardGeometry.centerX(coords), BoardGeometry.centerY(coords)), .001f);
                    }
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void cliffKeepsMorePlateauAndJoinsTheSubmergedRockWithoutHoles() {
        var original = BoardRelief.tuning();
        try {
            BoardSculptTest.withTransitions(true, () -> {
                var scene = island(2, 1);
                var cache = new BoardSurface.Cache();
                tune(false);
                var beach = cache.get(scene, scene.tile(LAND));
                float oldArea = area(beach);
                tune(true);
                var cliff = cache.get(scene, scene.tile(LAND));
                assertNotSame(beach, cliff, "Toggling rebuilds support and picking geometry");
                assertTrue(area(cliff) > oldArea * 1.1f, "Removing the cliff setback recovers usable plateau");
                var walls = cliff.walls(scene, BoardGeometry.floor(scene));
                for (int direction = 0; direction < 6; direction++) {
                    var water = cache.get(scene, scene.tile(LAND.translated(direction)));
                    int landEdge = Math.floorMod(1 - direction, 6);
                    int waterEdge = (landEdge + 3) % 6;
                    var rock = water.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.WALL
                          && f.landEdge() == waterEdge).toList();
                    assertFalse(rock.isEmpty());
                    for (var face : rock) {
                        for (var vertex : vertices(face)) {
                            assertEquals(BoardRelief.Kind.SUBMERGED_CLIFF, water.relief.shade(vertex).kind(),
                                  "Every submerged wall vertex must receive water optics: " + vertex);
                        }
                    }
                    assertTrue(rock.stream().flatMap(f -> vertices(f).stream())
                          .anyMatch(p -> p.z <= -BoardGeometry.LEVEL), "Rock continues to the bed");
                    for (var face : walls) {
                        if (face.landEdge() != landEdge) { continue; }
                        for (var point : vertices(face)) {
                            if (Math.abs(point.z) > .001f) { continue; }
                            assertTrue(rock.stream().flatMap(f -> vertices(f).stream())
                                        .anyMatch(p -> p.epsilonEquals(point, .001f)),
                                  "The submerged face must share every upper cliff foot vertex: " + point);
                        }
                    }
                }
                tune(false);
                assertEquals(oldArea, area(cache.get(scene, scene.tile(LAND))), .001f,
                      "Disabling the option restores the beach and original plateau");
            });
        } finally {
            BoardRelief.tune(original);
        }
    }

    static void tune(boolean enabled) {
        var t = BoardRelief.tuning();
        BoardRelief.tune(new BoardRelief.Tuning(t.shoreShift(), t.shoreRoom(), t.shoreReach(), t.shoreNarrow(),
              t.shoreHard(), t.shorePool(), t.shoreIsle(), t.shoreBlend(), t.shoreWander(), t.wanderCell(),
              t.shoreSpread(), t.landKeep(), t.shoreLip(), t.transition(), t.fullDetailHexes(), t.mediumDetailHexes(),
              t.riverWidth(), enabled));
    }

    @Test
    void aCliffAndSlopeShareTheMouthWhereWaterDepthChanges() {
        var original = BoardRelief.tuning();
        try {
            tune(true);
            var shallowScene = island(1, 1);
            var deeper = LAND.translated(0);
            var shallower = LAND.translated(1);
            var tiles = new ArrayList<>(shallowScene.tiles());
            tiles.set(deeper.getX() * 7 + deeper.getY(), new BoardScene.Tile(deeper, 0, 2, false, 0, BoardScene.Surface.SAND, null,
                  null, null, null, null, List.of(), List.of(), BoardLiquid.WATER, null, true));
            var scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
            var a = new BoardSurface(scene, scene.tile(deeper));
            var b = new BoardSurface(scene, scene.tile(shallower));
            for (int edge = 0; edge < 6; edge++) {
                if (!deeper.translated(BoardGeometry.edgeDirection(edge)).equals(shallower)) { continue; }
                int n = BoardSurface.SHORE_SEGMENTS;
                for (int i = 0; i < n; i++) {
                    var p = a.outline.get(edge * n + i);
                    assertTrue(b.outline.stream().anyMatch(p::equals), "Shared water opening");
                    assertEquals(a.height(p.x, p.y), b.height(p.x, p.y), .01f,
                          "Different depths must not leave a step or hole through the mouth");
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static List<Vector3> vertices(BoardSurface.Face face) {
        return List.of(face.a(), face.b(), face.c());
    }

    /** A low river with abrupt changes of bed depth and bank height, including cliff/slope junctions. */
    static BoardScene mixedDepthScene() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff8a8a70);
        var pixels = new BoardScene.Pixels(image);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                boolean water = x >= 1 && x <= 7 && (y == 3 || y == 4 && x >= 3 && x <= 6);
                int depth = !water ? -1 : new int[] { 1, 2, 1, 5, 2, 1, 2 }[x - 1];
                int level = water ? -2 : y < 3 ? x == 4 ? 9 : x == 5 ? -2 : x < 5 ? 1 : 3 : -2;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, BoardScene.Surface.GRASS,
                      pixels, null, null, null, null, List.of(), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, 9, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static float area(BoardSurface surface) {
        float result = 0;
        for (var f : surface.faces) {
            if (f.finish() == BoardSurface.Finish.TOP) {
                result += new Vector3(f.b()).sub(f.a()).crs(new Vector3(f.c()).sub(f.a())).z / 2;
            }
        }
        return result;
    }

    private static BoardScene island(int level, int depth) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                var coords = new Coords(x, y);
                boolean land = coords.equals(LAND);
                tiles.add(new BoardScene.Tile(coords, land ? level : 0, land ? -1 : depth, false, 0,
                      BoardScene.Surface.SAND, null, null, null, null, null, List.of(), List.of(),
                      land ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
