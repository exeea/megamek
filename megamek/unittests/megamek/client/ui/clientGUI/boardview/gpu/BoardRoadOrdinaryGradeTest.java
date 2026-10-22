/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

        var surfaces = new LinkedHashMap<Coords, BoardSurface>();
        for (int d = -1; d < 6; d++) {
            var member = d < 0 ? at : at.translated(d);
            surfaces.put(member, new BoardSurface(scene, scene.tile(member)));
        }
        for (var member : surfaces.keySet()) {
            var tile = scene.tile(member);
            if (!BoardRoad.rendered(tile) || tile.liquid().present()) { continue; }
            var surface = surfaces.get(member);
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
                assertTrue(normal.nor().z >= Math.sqrt(.5) || BoardRoadGradeFixtureTest.hidden(surfaces.values(), p),
                      "An ordinary incline must not put a cliff through the corridor at " + member + " / " + p);
            }
        }

        if (id.equals("grade-straight") || id.equals("grade-transition")) {
            // Equal one-level steps share each ramp between both hexes over their outer SHARED_RAMP, so every interior
            // hex keeps its middle at its own level and climbs only towards its edges. The emitted road follows that
            // grade, except that its edge meets the natural ground beside it where the two miss by under a tenth of a
            // level (BoardRoadCut#meet).
            var along = BoardGeometry.center(at.translated((direction + 3) % 6), 0)
                  .sub(BoardGeometry.center(at, 0)).nor();
            for (var member : List.of(at.translated(direction), at, at.translated((direction + 3) % 6))) {
                var surface = surfaces.get(member);
                var grade = BoardRoadGrade.of(scene, scene.tile(member), true);
                var center = BoardGeometry.center(member, scene.tile(member).elevation());
                for (int offset = -8; offset <= 8; offset += 4) {
                    var p = new Vector3(center).mulAdd(along, offset * BoardGeometry.hexScale());
                    assertEquals(center.z, grade.height(p.x, p.y), .006f,
                          "A shared ramp keeps the hex's middle level at " + member + " / " + offset);
                    assertEquals(center.z, BoardSurface.sampleHeight(surface.groundFaces(), p.x, p.y, Float.NaN),
                          .1f * BoardGeometry.level(),
                          "The emitted road follows its grade at " + member + " / " + offset);
                }
            }
        }
        if (id.equals("grade-high-bank") || id.equals("grade-low-bank")) {
            // Corner cut/fill must join the corridor without leveling the whole adjacent hex. Its bank may reach into
            // the middle, as a drop of up to BoardRoadGrade.FILL below a road fills up as an embankment; beyond the
            // bank's toe, away from the corridor, the plateau keeps its authored elevation.
            var bank = at.translated((direction + 1) % 6);
            var surface = surfaces.get(bank);
            var center = BoardGeometry.center(bank, board.getHex(bank).getLevel());
            var away = BoardGeometry.center(bank.translated((direction + 1) % 6), board.getHex(bank).getLevel());
            boolean kept = false;
            for (int step = 0; step <= 5 && !kept; step++) {
                var p = new Vector3(center).lerp(away, step * .05f);
                float height = BoardSurface.sampleHeight(surface.groundFaces(), p.x, p.y, Float.NaN);
                kept = Math.abs(height - center.z) <= .006f;
            }
            assertTrue(kept, "The neighboring plateau keeps its authored elevation beyond its bank");
        }
    }
}
