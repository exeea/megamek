/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Geometry checks of the editable matrix: finite support, usable road faces, and connected approach heights. */
class BoardRoadGradeFixtureTest {
    private static final Map<Boolean, BoardScene> SCENES = new HashMap<>();
    private static final List<RoadGradeFixture.Panel> MIXED_PANELS = List.of(
          RoadGradeFixture.Panel.CENTERED, RoadGradeFixture.Panel.BESIDE);
    private static final int[] MIXED_CASES = { 2, 18, 19 };
    private static BoardScene mixedScene;
    // Below one hundredth of a model pixel, including float rounding at the matrix's farthest coordinates.
    private static final float EPSILON = .006f;

    @BeforeAll
    static void scenes() {
        GdxNativesLoader.load();
        for (boolean concrete : new boolean[] { false, true }) {
            SCENES.put(concrete, RoadGradeFixture.scene(RoadGradeFixture.create(concrete)));
        }
        var mixed = RoadGradeFixture.create(false);
        for (var panel : MIXED_PANELS) {
            for (int index : MIXED_CASES) {
                mixed.getHex(RoadGradeFixture.center(panel, index)).addTerrain(new Terrain(Terrains.PAVEMENT, 1));
            }
        }
        mixedScene = RoadGradeFixture.scene(mixed);
    }

    static Stream<Arguments> specimens() {
        var result = new ArrayList<Arguments>();
        for (boolean concrete : new boolean[] { false, true }) {
            for (var panel : RoadGradeFixture.Panel.values()) {
                for (int i = 0; i < RoadGradeFixture.cases().size(); i++) {
                    result.add(Arguments.of(concrete, panel, i, RoadGradeFixture.cases().get(i).id()));
                }
            }
        }
        return result.stream();
    }

    @ParameterizedTest(name = "concrete={0}, {1}, {3}")
    @MethodSource("specimens")
    void roadsAndRailsKeepSupportedGradesAndConnectedMouths(boolean concrete, RoadGradeFixture.Panel panel,
          int index, String name) {
        assertSupportedGradesAndMouths(SCENES.get(concrete), panel, index, name);
    }

    static Stream<Arguments> mixedSpecimens() {
        var result = new ArrayList<Arguments>();
        for (var panel : MIXED_PANELS) {
            for (int index : MIXED_CASES) {
                result.add(Arguments.of(panel, index, RoadGradeFixture.cases().get(index).id()));
            }
        }
        return result.stream();
    }

    @ParameterizedTest(name = "mixed materials, {0}, {2}")
    @MethodSource("mixedSpecimens")
    void aPavedCellKeepsTheSameWidenedMouthAsItsGrassNeighbors(RoadGradeFixture.Panel panel, int index, String name) {
        assertSupportedGradesAndMouths(mixedScene, panel, index, name);
    }

    static Stream<Arguments> xJunctions() {
        var result = new ArrayList<Arguments>();
        for (boolean concrete : new boolean[] { false, true }) {
            for (var panel : RoadGradeFixture.Panel.values()) { result.add(Arguments.of(concrete, panel)); }
        }
        return result.stream();
    }

    @ParameterizedTest(name = "X pavement grade, concrete={0}, {1}")
    @MethodSource("xJunctions")
    void mixedHeightCrossingsDoNotPutSteepBanksThroughThePavement(boolean concrete, RoadGradeFixture.Panel panel) {
        // The hub's fixed heights admit a 1:1 grade. Its approaches also retain a longitudinal vertical curve,
        // so check their transverse grade separately rather than treating that required climb as a lateral bank.
        assertSupportedGradesAndMouths(SCENES.get(concrete), panel, 19, "x-mixed", true);
    }

    private static void assertSupportedGradesAndMouths(BoardScene scene, RoadGradeFixture.Panel panel,
          int index, String name) {
        assertSupportedGradesAndMouths(scene, panel, index, name, false);
    }

    private static void assertSupportedGradesAndMouths(BoardScene scene, RoadGradeFixture.Panel panel,
          int index, String name, boolean junctionGrade) {
        assertSupportedGradesAndMouths(scene, RoadGradeFixture.center(panel, index), name, junctionGrade);
    }

    static void assertSupportedGradesAndMouths(BoardScene scene, Coords at, String name) {
        assertSupportedGradesAndMouths(scene, at, name, false);
    }

    private static void assertSupportedGradesAndMouths(BoardScene scene, Coords at, String name,
          boolean junctionGrade) {
        var sources = new HashMap<Coords, BoardSurface>();
        var grassSources = new HashMap<Coords, BoardSurface>();
        var columns = new HashMap<Coords, BoardTacticalGeometry.Surface>();
        var near = new ArrayList<Coords>();
        near.add(at);
        for (int d = 0; d < 6; d++) { near.add(at.translated(d)); }
        for (var member : near) {
            sources.put(member, new BoardSurface(scene, scene.tile(member), TerrainLod.FULL));
            if (junctionGrade && scene == SCENES.get(true)) {
                var grass = SCENES.get(false);
                grassSources.put(member, new BoardSurface(grass, grass.tile(member), TerrainLod.FULL));
            }
        }
        var bridges = new HashMap<Coords, BoardBridge.Shape>();
        for (var member : near) {
            var tile = scene.tile(member);
            if (BoardBridge.feature(tile) != null) {
                var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, sources);
                bridges.put(member, BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing));
            }
        }
        int samples = 0;
        for (var member : near) {
            var tile = scene.tile(member);
            if (!BoardRoad.rendered(tile) || tile.liquid().present()) { continue; }
            var source = sources.get(member);
            var road = BoardRoad.of(scene, tile);
            var center = BoardGeometry.center(member, tile.elevation());
            var classic = columns.computeIfAbsent(member, ignored ->
                  GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene), source, sources));
            for (int x = -24; x <= 24; x += 8) {
                for (int y = -24; y <= 24; y += 8) {
                    if (road.distance(x, y) >= -1) { continue; }
                    float wx = center.x + x * BoardGeometry.hexScale();
                    float wy = center.y + y * BoardGeometry.hexScale();
                    float support = BoardSurface.sampleHeight(source.groundFaces(), wx, wy, Float.NaN);
                    assertTrue(Float.isFinite(support),
                          name + " missing road support at " + member + " / " + x + "," + y);
                    if (classic != null) {
                        // Classic keeps sharp columns outside its route cut. Its boundary contract is checked
                        // separately; do not require Enhanced bank deformation in the Classic column.
                        assertTrue(Float.isFinite(BoardSurface.sampleHeight(classic.top(), wx, wy, Float.NaN)),
                              "Classic road support must remain closed at " + member + " / " + x + "," + y);
                    }
                    samples++;
                }
            }
            // Hidden terrain below a bridge's solid landing is not the road's visible driving surface.
            if (!name.startsWith("bridge-")) {
                for (var face : source.groundFaces()) {
                    if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                    var p = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    if (road.distance((p.x - center.x) / BoardGeometry.hexScale(),
                          (p.y - center.y) / BoardGeometry.hexScale()) >= -1) { continue; }
                    // Clipping can leave nearly collinear, flat slivers whose float normal is unstable.
                    float span = Math.max(face.a().z, Math.max(face.b().z, face.c().z))
                          - Math.min(face.a().z, Math.min(face.b().z, face.c().z));
                    if (span <= .01f * BoardGeometry.hexScale()) { continue; }
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    if (normal.len2() < .000001f) { continue; }
                    normal.nor();
                    assertTrue(normal.z > .2f,
                          name + " excessive road face grade at " + member + ": " + face);
                    if (junctionGrade) {
                        if (member.equals(at)) {
                            assertTrue(normal.z >= Math.sqrt(.5), "The X hub must spread its grade: " + face);
                        }
                        var along = road.wheelDirection((p.x - center.x) / BoardGeometry.hexScale(),
                              (p.y - center.y) / BoardGeometry.hexScale());
                        float across = Math.abs(-normal.x * along.y + normal.y * along.x) / normal.z;
                        assertTrue(across <= 1, "No steep transverse bank through the road at " + member + ": " + face);
                        var grass = grassSources.get(member);
                        if (grass != null) {
                            // Concrete changes the outer slab, not the driving height. Its coarser bank mesh can
                            // slightly change interpolation near the edge, within the existing 0.1-pixel tolerance.
                            assertEquals(p.z, BoardSurface.sampleHeight(grass.groundFaces(), p.x, p.y, Float.NaN),
                                  .1f * BoardGeometry.hexScale(),
                                  "Concrete must retain the same pavement grade at " + member + ": " + p);
                        }
                    }
                }
            }
            for (int d = 0; d < 6; d++) {
                var next = scene.tile(member.translated(d));
                if (!sources.containsKey(next.coords()) || (tile.wayExits() & 1 << d) == 0
                      || (next.wayExits() & 1 << ((d + 3) % 6)) == 0
                      || !BoardSurface.hasRoadApproach(tile, next, d)) { continue; }
                int edge = Math.floorMod(1 - d, 6);
                var left = BoardGeometry.corner(member, 0, edge);
                var right = BoardGeometry.corner(member, 0, edge + 1);
                float mouth = BoardSurface.roadMouth(tile, next, d) * BoardGeometry.hexScale();
                for (float fraction : new float[] { -.65f, 0, .65f }) {
                    // Relief may move a level gate beside a graded third hex. Check its actual shared rim,
                    // not the nominal edge which can legitimately lie inside only one of the two owners.
                    var p = rimPoint(source, edge, .5f + fraction * mouth / left.dst(right));
                    float expected = (tile.elevation() + next.elevation()) * BoardGeometry.level() / 2;
                    float first = BoardSurface.sampleHeight(source.groundFaces(), p.x, p.y, Float.NaN);
                    assertTrue(Float.isFinite(first), name + " missing shared-rim support at " + p);
                    if (fraction == 0) {
                        assertEquals(expected, first, EPSILON, name + " approach center keeps its joint height");
                    }
                    // Adjacent widened arms may overlap away from their axes; their blended grade is valid
                    // provided both owners meet on the same emitted boundary without a step or hole.
                    for (var owner : List.of(source, sources.get(next.coords()))) {
                        assertEquals(first, BoardSurface.sampleHeight(owner.groundFaces(), p.x, p.y, Float.NaN),
                              EPSILON, name + " broken shared road/rail mouth at " + member + " direction " + d
                                    + ", across " + fraction + ", owner " + owner.tile.coords() + ", point " + p);
                    }
                    // Classic keeps the nominal hex boundary rather than Enhanced's displaced earthwork rim.
                    // Its actual route cut must still meet the adjacent road, including a level junction arm.
                    var nominal = new Vector3(left).lerp(right, .5f + fraction
                          * Math.min(mouth, left.dst(right) * .49f) / left.dst(right));
                    var adjacentColumn = columns.computeIfAbsent(next.coords(), ignored ->
                          GpuTilesetTerrain.column(scene, next, BoardGeometry.floor(scene), sources.get(next.coords()), sources));
                    float columnHeight = BoardSurface.sampleHeight(classic.top(), nominal.x, nominal.y, Float.NaN);
                    assertTrue(Float.isFinite(columnHeight), name + " missing Classic mouth at " + nominal);
                    assertEquals(columnHeight,
                          BoardSurface.sampleHeight(adjacentColumn.top(), nominal.x, nominal.y, Float.NaN), EPSILON,
                          name + " broken Classic road/rail mouth at " + member + " direction " + d);
                    if (fraction == 0) {
                        assertEquals(expected, columnHeight, EPSILON, name + " Classic joint keeps its authored height");
                    }
                }
            }
            // The centre's courses stay inside this sampled neighborhood; a neighbor's remote terminal can
            // belong to a second-ring carrier that this deliberately bounded geometry check does not build.
            if (!member.equals(at)) { continue; }
            for (var feature : tile.features()) {
                if (RailRoute.kind(feature.decoration()) != RailRoute.Kind.RAIL
                      || RailRoute.onDeck(feature.decoration())) { continue; }
                var layout = BoardRail.layout(scene, tile, feature, source, bridges::get);
                assertTrue(!layout.paths().isEmpty(), "The native rail must emit a course");
                var approaches = BoardRail.approaches(scene, tile, feature).stream()
                      .map(next -> bridges.get(next.coords())).filter(java.util.Objects::nonNull).toList();
                for (var path : layout.paths()) {
                    for (var point : path) {
                        float wx = center.x + point.x * BoardGeometry.hexScale();
                        float wy = center.y + point.y * BoardGeometry.hexScale();
                        // A shifted shared rim can put the nominal rail endpoint over the adjoining carrier.
                        float ground = Float.NEGATIVE_INFINITY;
                        for (var owner : sources.values()) {
                            ground = Math.max(ground, BoardSurface.sampleHeight(owner.groundFaces(), wx, wy,
                                  Float.NEGATIVE_INFINITY));
                        }
                        for (var shape : approaches) { ground = Math.max(ground, BoardBridge.top(shape, wx, wy)); }
                        assertTrue(Float.isFinite(ground), name + " missing ground below the actual rail course");
                        assertEquals(ground, center.z + point.z * BoardGeometry.hexScale(), EPSILON,
                              name + " rail must use actual emitted support at " + member + " / " + point);
                    }
                }
            }
        }
        for (var entry : bridges.entrySet()) {
            var tile = scene.tile(entry.getKey());
            var center = BoardGeometry.center(tile.coords(), tile.elevation());
            assertEquals(BoardBridge.deckZ(tile), BoardBridge.top(entry.getValue(), center.x, center.y), EPSILON,
                  name + " deck keeps its authored center height");
            for (int d = 0; d < 6; d++) {
                var other = bridges.get(tile.coords().translated(d));
                if (other == null || (BoardBridge.feature(tile).bridgeExits() & 1 << d) == 0) { continue; }
                var next = scene.tile(tile.coords().translated(d));
                var gate = new Vector3(center).lerp(BoardGeometry.center(next.coords(), next.elevation()), .5f);
                float expected = (BoardBridge.deckZ(tile) + BoardBridge.deckZ(next)) / 2;
                assertEquals(expected, BoardBridge.top(entry.getValue(), gate.x, gate.y), EPSILON,
                      name + " deck mouth reaches its neighbor");
                assertEquals(expected, BoardBridge.top(other, gate.x, gate.y), EPSILON,
                      name + " opposite deck mouth reaches the same height");
            }
            samples++;
        }
        assertTrue(samples > 0, "Exercise actual road or deck geometry");
    }

    private static Vector3 rimPoint(BoardSurface surface, int edge, float t) {
        var rim = surface.relief.rim(edge);
        if (rim.isEmpty()) {
            return BoardGeometry.corner(surface.tile.coords(), surface.tile.elevation(), edge)
                  .lerp(BoardGeometry.corner(surface.tile.coords(), surface.tile.elevation(), edge + 1), t);
        }
        for (int i = 1; i < rim.size(); i++) {
            var a = rim.get(i - 1); var b = rim.get(i);
            if (t <= b.t() || i == rim.size() - 1) {
                return new Vector3(a.point()).lerp(b.point(), Math.clamp((t - a.t()) / (b.t() - a.t()), 0, 1));
            }
        }
        return new Vector3(rim.getFirst().point());
    }
}
