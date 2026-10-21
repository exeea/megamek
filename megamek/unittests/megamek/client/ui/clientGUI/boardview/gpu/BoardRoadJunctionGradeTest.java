/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Connected mixed grades keep their actual carrier and lighting continuous, including at the board boundary. */
class BoardRoadJunctionGradeTest {
    static Stream<Arguments> boardEdges() {
        var cases = new ArrayList<Arguments>();
        // Left/right columns and both top/bottom parities exercise all shared-edge orientations.
        for (var at : List.of(new Coords(0, 3), new Coords(7, 3), new Coords(3, 0),
              new Coords(4, 0), new Coords(3, 7), new Coords(4, 7))) {
            for (var panel : RoadGradeFixture.Panel.values()) { cases.add(Arguments.of(at, panel)); }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "board edge {0}, {1}")
    @MethodSource("boardEdges")
    void mixedRoadAndRailMouthsHaveOneHeightAtTheBoardEdge(Coords at, RoadGradeFixture.Panel panel) {
        var scene = junction(at, panel);
        var tile = scene.tile(at);
        var source = new BoardSurface(scene, tile);
        int checked = 0;
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(at.translated(d));
            if (next == null || (tile.wayExits() & 1 << d) == 0) { continue; }
            var other = new BoardSurface(scene, next);
            int edge = Math.floorMod(1 - d, 6);
            var left = BoardGeometry.corner(at, 0, edge);
            var right = BoardGeometry.corner(at, 0, edge + 1);
            float half = Math.min(BoardSurface.roadMouth(tile, next, d) * BoardGeometry.hexScale(),
                  left.dst(right) * .49f);
            var across = new Vector3(right).sub(left).nor();
            var middle = new Vector3(left).lerp(right, .5f);
            for (float fraction : new float[] { -.8f, -.4f, 0, .4f, .8f }) {
                var p = new Vector3(middle).mulAdd(across, half * fraction);
                float height = BoardSurface.sampleHeight(source.groundFaces(), p.x, p.y, Float.NaN);
                assertTrue(Float.isFinite(height), "The whole road/rail mouth needs support: " + p);
                assertEquals(height, BoardSurface.sampleHeight(other.groundFaces(), p.x, p.y, Float.NaN), .006f,
                      "The two owners must meet at " + at + " direction " + d + " / " + p);
                checked++;
            }
        }
        assertTrue(checked >= 15, "Exercise the graded junction's multiple approaches");
        if (panel != RoadGradeFixture.Panel.ROAD) {
            assertTrue(tile.corridor().sides() != 0, "Exercise the captured shared rail corridor");
        }
    }

    @ParameterizedTest
    @EnumSource(RoadGradeFixture.Panel.class)
    void blendedJunctionLightingFollowsItsFinalCarrier(RoadGradeFixture.Panel panel) {
        var at = new Coords(4, 4);
        var scene = junction(at, panel);
        int checked = 0;
        for (var member : List.of(at, at.translated(0), at.translated(1))) {
            var surface = new BoardSurface(scene, scene.tile(member));
            var way = BoardRoad.way(scene, surface.tile);
            var center = BoardGeometry.center(member, surface.tile.elevation());
            for (var entry : BoardSurface.vertexNormals(surface.groundFaces()).entrySet()) {
                var p = entry.getKey();
                if (way.distance((p.x - center.x) / BoardGeometry.hexScale(),
                      (p.y - center.y) / BoardGeometry.hexScale()) >= -1) { continue; }
                var actual = entry.getValue();
                if (actual.z > .99f) { continue; }
                assertTrue(surface.roadNormal(p, actual).epsilonEquals(actual, .00001f),
                      "Lighting must describe the emitted blended pavement at " + member + " / " + p);
                checked++;
            }
        }
        assertTrue(checked > 20, "Exercise inclined pavement in the hub and its connected approaches");
    }

    private static BoardScene junction(Coords at, RoadGradeFixture.Panel panel) {
        var board = Board.createEmptyBoard(8, 8);
        board.setNativeFormat(true);
        board.setRoadsAutoExit(false);
        int[] rises = { 1, -1, 2, 0, -1, 2 };
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) { board.getHex(x, y).setLevel(3); }
        }
        int exits = 0;
        for (int d = 0; d < 6; d++) {
            if (board.getHex(at.translated(d)) != null) { exits |= 1 << d; }
        }
        // Four arms retain real centered/beside corridors rather than a five/six-way rail hub.
        while (Integer.bitCount(exits) > 4) { exits &= ~Integer.highestOneBit(exits); }
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) == 0) { continue; }
            var next = board.getHex(at.translated(d));
            next.setLevel(3 + rises[d]);
            next.addTerrain(new Terrain(Terrains.ROAD, 1, true, 1 << ((d + 3) % 6)));
        }
        board.getHex(at).addTerrain(new Terrain(Terrains.ROAD, 1, true, exits));
        if (panel != RoadGradeFixture.Panel.ROAD) {
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    var hex = board.getHex(x, y);
                    var road = hex.getTerrain(Terrains.ROAD);
                    if (road == null) { continue; }
                    RailRoute.lay(hex, false, road.getExits(), 0);
                    if (panel == RoadGradeFixture.Panel.BESIDE) { RailRoute.beside(hex, false, 64 | road.getExits()); }
                }
            }
        }
        var tiles = new ArrayList<BoardScene.Tile>();
        var pixels = new BoardScene.PixelPool();
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                var coords = new Coords(x, y);
                var artwork = new BoardArtwork.HexImage(coords, null, null, null, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(board.getHex(coords), artwork, null, pixels, board::getHex));
            }
        }
        return new BoardScene(0, 8, 8, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
