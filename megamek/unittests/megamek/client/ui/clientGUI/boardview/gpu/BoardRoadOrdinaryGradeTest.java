/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Everyday inclines complement the matrix's deliberately extreme rises and dips. */
class BoardRoadOrdinaryGradeTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    static Stream<Arguments> specimens() {
        var cases = new ArrayList<Arguments>();
        for (var sample : RoadGradeFixture.ordinaryCases()) {
            for (boolean concrete : new boolean[] { false, true }) {
                for (var panel : RoadGradeFixture.Panel.values()) {
                    for (int direction = 0; direction < 6; direction++) {
                        for (int parity = 0; parity < 2; parity++) {
                            cases.add(Arguments.of(sample.id(), concrete, panel, direction, parity));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, concrete={1}, {2}, direction={3}, parity={4}")
    @MethodSource("specimens")
    void ordinaryRoutesRetainSupportedMouthsAndAuthoredLandforms(String id, boolean concrete,
          RoadGradeFixture.Panel panel, int direction, int parity) {
        var board = RoadGradeFixture.focused(concrete, panel, id, direction, parity);
        var scene = RoadGradeFixture.scene(board);
        var at = RoadGradeFixture.focusedCenter(parity);
        BoardRoadGradeFixtureTest.assertSupportedGradesAndMouths(scene, at, id);

        var near = new ArrayList<Coords>();
        near.add(at);
        for (int d = 0; d < 6; d++) { near.add(at.translated(d)); }
        for (var member : near) {
            var tile = scene.tile(member);
            if (!BoardRoad.rendered(tile) || tile.liquid().present()) { continue; }
            var surface = new BoardSurface(scene, tile);
            var course = BoardRoad.way(scene, tile);
            var center = BoardGeometry.center(member, tile.elevation());
            for (var face : surface.groundFaces()) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                var p = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                if (course.distance((p.x - center.x) / BoardGeometry.hexScale(),
                      (p.y - center.y) / BoardGeometry.hexScale()) >= -2) { continue; }
                float span = Math.max(face.a().z, Math.max(face.b().z, face.c().z))
                      - Math.min(face.a().z, Math.min(face.b().z, face.c().z));
                if (span <= .01f * BoardGeometry.hexScale()) { continue; }
                var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                if (normal.len2() < .000001f) { continue; }
                // A one-level route has ample run for a 1:1 grade. Its bank must not pinch through the
                // pavement. Extreme authored stress cases intentionally have a different bound.
                assertTrue(normal.nor().z >= Math.sqrt(.5),
                      "An ordinary incline must not put a cliff through the corridor at " + member + " / " + p);
            }
        }

        if (id.equals("grade-straight") || id.equals("grade-transition")) {
            // Equal one-level steps allow one line through each interior center. A flat hub followed by a
            // steeper ramp satisfies endpoint/monotonic checks but produces the unwanted repeated landings.
            var along = BoardGeometry.center(at.translated((direction + 3) % 6), 0)
                  .sub(BoardGeometry.center(at, 0));
            float grade = BoardGeometry.level() / along.len();
            along.nor();
            for (var member : List.of(at.translated(direction), at, at.translated((direction + 3) % 6))) {
                var surface = new BoardSurface(scene, scene.tile(member));
                var center = BoardGeometry.center(member, scene.tile(member).elevation());
                for (int offset = -8; offset <= 8; offset += 4) {
                    float distance = offset * BoardGeometry.hexScale();
                    var p = new Vector3(center).mulAdd(along, distance);
                    assertEquals(center.z + distance * grade,
                          BoardSurface.sampleHeight(surface.groundFaces(), p.x, p.y, Float.NaN), .006f,
                          "A continuous climb must not acquire a landing at " + member + " / " + offset);
                }
            }
        }
        if (id.equals("grade-high-bank") || id.equals("grade-low-bank")) {
            // Corner cut/fill must join the corridor without leveling the whole adjacent hex.
            var bank = at.translated((direction + 1) % 6);
            var surface = new BoardSurface(scene, scene.tile(bank));
            var center = BoardGeometry.center(bank, board.getHex(bank).getLevel());
            assertEquals(center.z, BoardSurface.sampleHeight(surface.groundFaces(), center.x, center.y, Float.NaN),
                  .006f, "The neighboring plateau keeps its authored elevation");
        }
    }
}
