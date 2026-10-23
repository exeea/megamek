/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** A road incline must not raise a fin where two neighboring lower plateaus meet outside its footprint. */
class BoardRoadLevelToeTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    static Stream<Arguments> specimens() {
        var cases = new ArrayList<Arguments>();
        for (String id : List.of("level-toe-straight", "level-toe-curve")) {
            for (boolean concrete : new boolean[] { false, true }) {
                for (var panel : RoadGradeFixture.Panel.values()) {
                    for (int direction = 0; direction < 6; direction++) {
                        for (int parity = 0; parity < 2; parity++) {
                            cases.add(Arguments.of(id, concrete, panel, direction, parity));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, concrete={1}, {2}, direction={3}, parity={4}")
    @MethodSource("specimens")
    void equalLowerPlateausGrowNoFinOutsideTheReservedRoad(String id, boolean concrete,
          RoadGradeFixture.Panel panel, int direction, int parity) {
        var board = RoadGradeFixture.focused(concrete, panel, id, direction, parity);
        var scene = RoadGradeFixture.scene(board);
        var at = RoadGradeFixture.focusedCenter(parity);
        BoardRoadGradeFixtureTest.assertSupportedGradesAndMouths(scene, at, id);
        for (int side : new int[] { 1, 5 }) {
            var bank = at.translated((direction + side) % 6);
            var upper = at.translated(direction);
            boolean covered = false;
            for (int corner = 0; corner < 6; corner++) {
                var p = BoardGeometry.corner(bank, 0, corner);
                if (cornerOf(at, p) && cornerOf(upper, p)) { covered = covered(scene, bank, p); }
            }
            if (scene.tile(bank).wayExits() != 0 || covered) { continue; }
            for (var lod : TerrainLod.values()) { assertPlateau(scene, bank, lod); }
        }
    }

    @Test
    void reportedMinesAndCityLowerGroundKeepsItsAuthoredPlateau() {
        for (String path : List.of("Deserts/16x17 Mines 1.board", "Map Set 3/16x17 City Hills Residential 1.board")) {
            var board = new Board();
            board.load(new File("data/boards/" + path));
            var scene = RoadGradeFixture.scene(board);
            var banks = path.startsWith("Deserts") ? List.of(new Coords(3, 5), new Coords(4, 7))
                  : List.of(new Coords(14, 5));
            for (var bank : banks) {
                assertTrue(!coveredCorner(scene, bank), "The reported raised toe lies outside the actual road");
                for (var lod : TerrainLod.values()) {
                    assertPlateau(scene, bank, lod);
                    BoardRoadSlopeTest.assertClosed(scene, bank, lod);
                }
            }
        }
    }

    /**
     * The plateau keeps its level, moved only by the bank every hex shares near roads (Enhanced): an embankment of a
     * higher road beside it may round onto it, but nothing else raises it.
     */
    private static void assertPlateau(BoardScene scene, Coords at, TerrainLod lod) {
        var tile = scene.tile(at);
        var surface = new BoardSurface(scene, tile, lod);
        var bank = new BoardRoadBank(scene, tile, surface.relief);
        float level = tile.elevation() * BoardGeometry.level();
        for (var face : surface.groundFaces()) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            for (var p : List.of(face.a(), face.b(), face.c())) {
                assertEquals(bank.active() ? bank.height(p.x, p.y, level) : level, p.z, .006f,
                      "An uncovered lower plateau must not grow a road-corner fin: " + at + "/" + lod + " " + p);
            }
        }
    }

    private static boolean coveredCorner(BoardScene scene, Coords at) {
        for (int corner = 0; corner < 6; corner++) {
            var p = BoardGeometry.corner(at, 0, corner);
            if (covered(scene, at, p)) { return true; }
        }
        return false;
    }

    private static boolean cornerOf(Coords at, Vector3 point) {
        for (int corner = 0; corner < 6; corner++) {
            if (BoardGeometry.corner(at, 0, corner).dst2(point) < .0001f) { return true; }
        }
        return false;
    }

    private static boolean covered(BoardScene scene, Coords at, Vector3 point) {
        for (var tile : scene.tiles()) {
            if (tile.coords().distance(at) > 1 || tile.wayExits() == 0) { continue; }
            var origin = BoardGeometry.center(tile.coords(), 0);
            var local = new Vector3(point).sub(origin).scl(1 / BoardGeometry.hexScale());
            if (BoardRoad.way(scene, tile).distance(local.x, local.y) <= BoardRoad.SHOULDER) { return true; }
        }
        return false;
    }
}
