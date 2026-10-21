/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoadCourseGradeTest {
    static Stream<Arguments> unequalSteps() {
        var cases = new ArrayList<Arguments>();
        for (int direction = 0; direction < 6; direction++) {
            for (int parity = 0; parity < 2; parity++) {
                for (boolean concrete : new boolean[] { false, true }) {
                    for (var panel : RoadGradeFixture.Panel.values()) {
                        for (boolean curve : new boolean[] { false, true }) {
                            for (int middle : new int[] { 1, 2 }) {
                                cases.add(Arguments.of(direction, parity, concrete, panel, curve, middle));
                            }
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("unequalSteps")
    void unequalMonotoneStepsKeepTheirMiddleHeightWithoutAFlatLanding(int direction, int parity,
          boolean concrete, RoadGradeFixture.Panel panel, boolean curve, int middle) {
        GdxNativesLoader.load();
        var board = RoadGradeFixture.focused(concrete, panel, curve ? "grade-curve" : "grade-straight", direction, parity);
        var at = RoadGradeFixture.focusedCenter(parity);
        int last = (direction + (curve ? 2 : 3)) % 6;
        board.getHex(at).setLevel(middle);
        for (int i = 1; i <= 2; i++) {
            board.getHex(at.translated(direction, i)).setLevel(0);
            board.getHex(at.translated(last, i)).setLevel(3);
        }
        var scene = RoadGradeFixture.scene(board);
        var center = BoardGeometry.center(at, middle);
        float[] a = RailRoute.border(direction), b = RailRoute.border(last);
        float length = 0;
        float[] previous = a;
        for (int i = 1; i <= 24; i++) {
            float[] p = RailRoute.course(a[0], a[1], b[0], b[1], i / 24f);
            length += (float) Math.hypot(p[0] - previous[0], p[1] - previous[1]);
            previous = p;
        }
        float least = Math.min(middle, 3 - middle) * BoardGeometry.level() / (length * BoardGeometry.hexScale());
        float greatest = Math.max(middle, 3 - middle) * BoardGeometry.level() / (length * BoardGeometry.hexScale());
        for (boolean columns : new boolean[] { false, true }) {
            var surface = columns ? BoardSurface.columns(scene, scene.tile(at), TerrainLod.FULL)
                  : new BoardSurface(scene, scene.tile(at));
            Vector3 prior = null;
            for (int i = 0; i <= 24; i++) {
                float[] local = RailRoute.course(a[0], a[1], b[0], b[1], i / 24f);
                var p = new Vector3(center).add(local[0] * BoardGeometry.hexScale(), local[1] * BoardGeometry.hexScale(), 0);
                p.z = BoardSurface.sampleHeight(surface.foundation(), p.x, p.y, Float.NaN);
                if (i == 12) { assertEquals(center.z, p.z, .1f, "Unequal steps retain the authored middle height"); }
                if (prior != null) {
                    float grade = (float) ((p.z - prior.z) / Math.hypot(p.x - prior.x, p.y - prior.y));
                    assertTrue(grade >= least * .5f && grade <= greatest * 1.5f,
                          "Unequal steady climbs must not insert a flat landing or concentrated ramp: " + grade);
                }
                prior = p;
            }
            for (int mouth : new int[] { direction, last }) {
                assertGroundMouth(scene, at, surface, mouth, columns, TerrainLod.FULL);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aThroughRoadMeetsTheActualLowerOrLevelBuiltDeckSlope(int direction) {
        GdxNativesLoader.load();
        float scale = BoardGeometry.hexScale();
        for (int parity = 0; parity < 2; parity++) {
            for (int deckLevel : new int[] { 1, 2 }) {
                var board = RoadGradeFixture.focused(false, RoadGradeFixture.Panel.ROAD, "bridge-graded", direction, parity);
                var deckAt = RoadGradeFixture.focusedCenter(parity);
                var at = deckAt.translated(direction);
                board.getHex(at.translated(direction)).setLevel(3);
                board.getHex(deckAt).addTerrain(new Terrain(Terrains.BRIDGE_ELEV, deckLevel));
                board.getHex(deckAt.translated((direction + 2) % 6)).addTerrain(new Terrain(Terrains.BRIDGE_ELEV, deckLevel));
                var scene = RoadGradeFixture.scene(board);
                var tile = scene.tile(at);
                var deckTile = scene.tile(deckAt);
                var center = BoardGeometry.center(at, tile.elevation());
                var outward = BoardGeometry.center(deckAt, 0).sub(BoardGeometry.center(at, 0)).nor();
                var gate = new Vector3(center).add(BoardGeometry.center(deckAt, 0).sub(BoardGeometry.center(at, 0)).scl(.5f));
                for (boolean columns : new boolean[] { false, true }) {
                    var surface = columns ? BoardSurface.columns(scene, tile, TerrainLod.FULL) : new BoardSurface(scene, tile);
                    var surfaces = new HashMap<Coords, BoardSurface>();
                    surfaces.put(at, surface);
                    var footing = BoardBridgeFooting.build(scene, deckTile, TerrainLod.FULL, surfaces, columns);
                    var shape = BoardBridgeSlope.build(deckTile, BoardBridge.deck(scene, deckTile), footing);
                    var top = shape.facets().stream().filter(face -> face.part() == BoardBridge.Part.TOP)
                          .map(face -> new BoardSurface.Face(face.a(), face.b(), face.c(), BoardSurface.Finish.TOP)).toList();
                    var roadTop = new ArrayList<>(surface.foundation());
                    roadTop.addAll(top);
                    float[] heights = new float[4];
                    int index = 0;
                    for (int offset : new int[] { -4, -1, 1, 4 }) {
                        var p = new Vector3(gate).mulAdd(outward, offset * scale);
                        heights[index++] = BoardSurface.sampleHeight(roadTop, p.x, p.y, Float.NaN);
                    }
                    assertEquals((heights[1] - heights[0]) / (3 * scale), (heights[3] - heights[2]) / (3 * scale), .08f,
                          "Ground and the actual built deck meet with the same longitudinal slope");
                    if (deckLevel == 1) {
                        var p = new Vector3(center).mulAdd(outward, 4 * scale);
                        assertTrue(BoardSurface.sampleHeight(surface.foundation(), p.x, p.y, Float.NaN) < center.z - .5f,
                              "A descending through road must not pause on a level hub before the bridge");
                    }
                }
            }
        }
    }

    @Test
    void citySingleClimbKeepsItsWidthLevelAcrossTheApproach() throws Exception {
        GdxNativesLoader.load();
        var scene = GpuRoadSourceTest.scene(
              "unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board");
        var lower = scene.tile(new Coords(7, 13));
        var upper = scene.tile(new Coords(8, 13));
        var from = BoardGeometry.center(lower.coords(), lower.elevation());
        var to = BoardGeometry.center(upper.coords(), upper.elevation());
        var across = new Vector3(from.y - to.y, to.x - from.x, 0).nor();
        float scale = BoardGeometry.hexScale();
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            for (boolean columns : new boolean[] { false, true }) {
                var faces = new ArrayList<BoardSurface.Face>();
                for (var tile : List.of(lower, upper)) {
                    var surface = columns ? BoardSurface.columns(scene, tile, lod) : new BoardSurface(scene, tile, lod);
                    faces.addAll(surface.foundation());
                }
                float[] previous = { from.z, from.z, from.z };
                for (int station = 2; station <= 8; station++) {
                    var middle = new Vector3(from).lerp(to, station / 10f);
                    float height = BoardSurface.sampleHeight(faces, middle.x, middle.y, Float.NaN);
                    for (int lane = -1; lane <= 1; lane++) {
                        var point = new Vector3(middle).mulAdd(across, lane * 12 * scale);
                        float z = BoardSurface.sampleHeight(faces, point.x, point.y, Float.NaN);
                        assertTrue(Float.isFinite(z), "Both lanes remain supported through the whole climb");
                        // Diagonal hex gates differ slightly from a perpendicular road section; sampled mesh
                        // chords may differ by .2 model pixels, but a neighbouring plateau must not bank the lane.
                        assertEquals(height, z, .2f * scale, "The single approach must not roll across its width");
                        assertTrue(z >= previous[lane + 1] - .06f * scale,
                              "Each side of the ramp climbs continuously instead of rising and falling at the gate");
                        previous[lane + 1] = z;
                    }
                }
            }
        }
    }

    @Test
    void airportJunctionPavementDoesNotDipBelowItsLowestConnectedMouth() throws Exception {
        GdxNativesLoader.load();
        var scene = GpuRoadSourceTest.scene("unofficial/Derv_Maps/25x25 Airport 3000.board");
        var at = new Coords(14, 12);
        var tile = scene.tile(at);
        var center = BoardGeometry.center(at, tile.elevation());
        var road = BoardRoad.of(scene, tile);
        float low = tile.elevation();
        for (int direction = 0; direction < 6; direction++) {
            float mouth = BoardSurface.roadEdgeElevation(tile, scene.tile(at.translated(direction)), direction);
            low = Math.min(low, mouth);
        }
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            for (boolean columns : new boolean[] { false, true }) {
                var surface = columns ? BoardSurface.columns(scene, tile, lod) : new BoardSurface(scene, tile, lod);
                int checked = 0;
                for (var face : surface.groundFaces()) {
                    if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                    var p = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    if (road.distance((p.x - center.x) / BoardGeometry.hexScale(),
                          (p.y - center.y) / BoardGeometry.hexScale()) > -.5f) { continue; }
                    // The sampled carrier permits .06 model pixels of chord error in its vertical curves.
                    float tolerance = .06f * BoardGeometry.hexScale();
                    assertTrue(p.z >= low * BoardGeometry.level() - tolerance,
                          "The full junction pavement must not form a trough between its graded exits: " + p);
                    checked++;
                }
                assertTrue(checked > 100, "Exercise both lanes around the whole junction");
                for (int direction : new int[] { 1, 2 }) {
                    var bridge = scene.tile(at.translated(direction));
                    var beneath = columns ? BoardSurface.columns(scene, bridge, lod) : new BoardSurface(scene, bridge, lod);
                    var middle = BoardGeometry.center(bridge.coords(), 0);
                    assertEquals(BoardGeometry.groundZ(bridge), beneath.height(middle.x, middle.y), .01f,
                          "Reserving a connected deck must not raise the ground under its span");
                }
            }
        }
    }

    @Test
    void airportDownhillCurvesKeepTheirGradeThroughTheMiddleAndAcrossTheirMouths() throws Exception {
        GdxNativesLoader.load();
        var scene = GpuRoadSourceTest.scene("unofficial/Derv_Maps/25x25 Airport 3000.board");
        for (var at : List.of(new Coords(14, 11), new Coords(14, 13))) {
            var tile = scene.tile(at);
            int first = Integer.numberOfTrailingZeros(tile.wayExits());
            int last = Integer.numberOfTrailingZeros(tile.wayExits() & ~(1 << first));
            float[] a = RailRoute.border(first), b = RailRoute.border(last);
            var center = BoardGeometry.center(at, tile.elevation());
            float length = 0;
            float[] previous = a;
            for (int i = 1; i <= 24; i++) {
                float[] p = RailRoute.course(a[0], a[1], b[0], b[1], i / 24f);
                length += (float) Math.hypot(p[0] - previous[0], p[1] - previous[1]);
                previous = p;
            }
            float mean = BoardGeometry.level() / (length * BoardGeometry.hexScale());
            for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                for (boolean columns : new boolean[] { false, true }) {
                    var surface = columns ? BoardSurface.columns(scene, tile, lod) : new BoardSurface(scene, tile, lod);
                    Vector3 prior = null;
                    for (int i = 0; i <= 24; i++) {
                        float[] local = RailRoute.course(a[0], a[1], b[0], b[1], i / 24f);
                        var p = new Vector3(center).add(local[0] * BoardGeometry.hexScale(),
                              local[1] * BoardGeometry.hexScale(), 0);
                        p.z = BoardSurface.sampleHeight(surface.foundation(), p.x, p.y, Float.NaN);
                        assertTrue(Float.isFinite(p.z), "The full curved course remains supported");
                        if (i == 12) {
                            assertEquals(center.z, p.z, .06f * BoardGeometry.hexScale(),
                                  "The sampled curve retains its authored middle elevation within its chord error");
                        }
                        if (prior != null) {
                            float grade = (float) Math.abs((p.z - prior.z) / Math.hypot(p.x - prior.x, p.y - prior.y));
                            assertTrue(grade >= mean * .7f && grade <= mean * 1.3f,
                                  "A steady downhill curve must not alternate flat landings and steep ramps at "
                                        + at + " / " + i + ": " + grade);
                        }
                        prior = p;
                    }
                    for (int direction : new int[] { first, last }) {
                        assertGroundMouth(scene, at, surface, direction, columns, lod);
                    }
                }
            }
        }
    }

    private static void assertGroundMouth(BoardScene scene, Coords at, BoardSurface surface, int direction,
          boolean columns, TerrainLod lod) {
        var other = scene.tile(at.translated(direction));
        var neighbor = columns ? BoardSurface.columns(scene, other, lod) : new BoardSurface(scene, other, lod);
        float[] local = RailRoute.border(direction);
        var gate = BoardGeometry.center(at, 0).add(local[0] * BoardGeometry.hexScale(),
              local[1] * BoardGeometry.hexScale(), 0);
        var along = new Vector3(local[0], local[1], 0).nor();
        float[] heights = new float[5];
        int index = 0;
        // One-sided quadratic differences compare the mouth tangents, without treating curvature as a crest.
        for (float offset : new float[] { -4, -2, 0, 2, 4 }) {
            var p = new Vector3(gate).mulAdd(along, offset * BoardGeometry.hexScale());
            heights[index++] = BoardSurface.sampleHeight((offset < 0 ? surface : neighbor).foundation(),
                  p.x, p.y, Float.NaN);
        }
        assertEquals((3 * heights[2] - 4 * heights[1] + heights[0]) / (4 * BoardGeometry.hexScale()),
              (-3 * heights[2] + 4 * heights[3] - heights[4]) / (4 * BoardGeometry.hexScale()), .06f,
              "A shared mouth must not add a crest between the through course and its neighbor");
    }

}
