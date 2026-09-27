/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardRoadTest {
    static final Coords CENTER = BoardSurfaceBlendTest.CENTER;

    static BoardScene.Tile tile(Coords coords, BoardRoad.Kind road, int exits, int level, BoardScene.Surface family) {
        var base = BoardSurfaceBlendTest.tile(coords, family, level, -1, exits);
        return new BoardScene.Tile(coords, level, -1, false, exits, family, base.ground(), null, null, null, null,
              List.of(), List.of(), BoardLiquid.NONE, null, true, road);
    }

    @Test
    void fiveAndSixExitsCirculateAroundAnIslandAndKeepEveryApproachFullWidth() {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4);
            for (int absent = -1; absent < 6; absent++) {
                int exits = absent < 0 ? 63 : 63 & ~(1 << absent);
                var road = BoardRoad.clearance(at, exits);
                var shape = road.footprint(0);
                var verge = road.footprint(BoardRoad.SHOULDER);
                var paint = road.markings(at, exits);
                for (int angle = 0; angle < 360; angle += 3) {
                    float nx = (float) Math.cos(Math.toRadians(angle)), ny = (float) Math.sin(Math.toRadians(angle));
                    assertFalse(verge.contains(7.5f * nx, 7.5f * ny), "The central island stays clear of road and shoulders");
                    assertFalse(paint.contains(22 * nx, 22 * ny), "Paint ends before entering the circulating lane");
                    for (float radius : new float[] { 11, 18, 25 }) {
                        assertTrue(shape.contains(radius * nx, radius * ny),
                              "The whole ring keeps the approach's 15-unit width, including room around the island");
                    }
                    for (float along : new float[] { -9, 9 }) {
                        for (float across : new float[] { -5, 5 }) {
                            assertTrue(shape.contains((18 + across) * nx - along * ny, (18 + across) * ny + along * nx),
                                  "An 18-by-10 vehicle envelope can turn through the entire ring without hitting the island or verge");
                        }
                    }
                }
                for (int d = 0; d < 6; d++) {
                    Vector3 gate = gate(at, d), across = new Vector3(-gate.y, gate.x, 0).nor();
                    for (float offset : new float[] { -7, 0, 7 }) {
                        var p = new Vector3(gate).mulAdd(across, offset);
                        assertEquals((exits & (1 << d)) != 0, shape.contains(p.x, p.y), "No pinched or phantom entrance");
                        if ((exits & (1 << d)) != 0) {
                            assertEquals(Math.abs(offset) - 7.5f, road.distance(p.x, p.y), .001f,
                                  "Clearance and material feathering must use the whole carriageway at a join");
                        }
                    }
                }
            }
        }
        assertTrue(BoardRoad.clearance(CENTER, 27).footprint(0).contains(0, 0), "Four exits still use an ordinary junction");
    }

    @Test
    void everyExitPatternReachesOnlyItsAuthoritativeSides() {
        for (int mask = 0; mask < 64; mask++) {
            int exits = mask;
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c, BoardRoad.Kind.PAVED,
                  c.equals(CENTER) ? exits : 0, 0, BoardScene.Surface.GRASS));
            var road = BoardRoad.of(scene, scene.tile(CENTER));
            var shape = road.footprint(0);
            for (int d = 0; d < 6; d++) {
                Vector3 gate = gate(CENTER, d);
                assertEquals((mask & 1 << d) != 0, shape.contains(gate.x, gate.y), "mask " + mask + ", side " + d);
            }
        }
    }

    @Test
    void connectedMaterialsMeetAcrossEveryOrientationAndColumnParity() {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4);
            for (int d = 0; d < 6; d++) {
                int direction = d, reverse = (d + 3) % 6;
                Coords next = at.translated(d);
                var scene = BoardSurfaceBlendTest.scene(c -> tile(c,
                      c.equals(at) ? BoardRoad.Kind.ALLEY : BoardRoad.Kind.PAVED,
                      c.equals(at) ? 1 << direction : c.equals(next) ? 1 << reverse : 0, 0, BoardScene.Surface.GRASS));
                var first = BoardRoad.of(scene, scene.tile(at));
                var second = BoardRoad.of(scene, scene.tile(next));
                Vector3 gate = gate(at, d), normal = new Vector3(-gate.y, gate.x, 0).nor();
                for (float offset = -10; offset <= 10; offset += .5f) {
                    Vector3 a = new Vector3(gate).mulAdd(normal, offset);
                    Vector3 b = new Vector3(gate).scl(-1).mulAdd(normal, offset);
                    assertEquals(first.distance(a.x, a.y), second.distance(b.x, b.y), .002f,
                          "Changing material must not open a crack at a shared edge");
                }
            }
        }
    }

    @Test
    void deadEndsContinuePastTheCentreWithFlatCutsAndLeaveTheNextHexUnconnected() {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4);
            for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.ALLEY, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                for (int d = 0; d < 6; d++) {
                    int exits = 1 << d;
                    var scene = BoardSurfaceBlendTest.scene(c -> tile(c, kind, exits, 0, BoardScene.Surface.GRASS));
                    var road = BoardRoad.of(scene, scene.tile(at));
                    var shape = road.footprint(0);
                    Vector3 outward = gate(at, d).scl(-1), across = new Vector3(-outward.y, outward.x, 0).nor();
                    for (float lateral : new float[] { -.9f, 0, .9f }) {
                        Vector3 inside = new Vector3(outward).scl(.78f).mulAdd(across, lateral * kind.halfWidth);
                        Vector3 outside = new Vector3(outward).scl(.82f).mulAdd(across, lateral * kind.halfWidth);
                        assertTrue(shape.contains(inside.x, inside.y), "Full width should reach close to the far edge");
                        assertTrue(road.distance(inside.x, inside.y) < 0, "Clearance must include the extended end");
                        assertFalse(shape.contains(outside.x, outside.y), "No round cap beyond the terminal plane");
                        assertTrue(road.distance(outside.x, outside.y) > 0);
                    }
                    assertFalse(road.footprint(BoardRoad.SHOULDER).contains(outward.x, outward.y),
                          "A dead end must leave ground before the opposite hex");
                }
            }
        }
    }

    @Test
    void unsealedEndsExposeTheGroundAndDirtWheelMarksContinueBeyondTheFullRoadbed() {
        for (var kind : List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c, kind, 1, 0, BoardScene.Surface.GRASS));
            var tile = scene.tile(CENTER);
            var road = BoardRoad.of(scene, tile);
            var patches = GpuRoads.patches(tile, road);
            var tail = patches.stream().filter(p -> p.endFade() > 0 && p.fade().outer() == p.fade().inner() && p.lift() < .05f)
                  .findFirst().orElseThrow();
            Vector3 outward = gate(CENTER, 0).scl(-1);
            Vector3 nearEnd = new Vector3(outward).scl(.76f);
            assertEquals(1, GpuRoads.coverage(road, tail, 0, 0));
            assertTrue(GpuRoads.coverage(road, tail, nearEnd.x, nearEnd.y) < .5f,
                  "The unsealed surface should dissolve before its final extent");
            assertEquals(0, GpuRoads.coverage(road, tail, outward.x, outward.y));
            assertTrue(patches.stream().noneMatch(p -> !p.blended() && p.shape().contains(nearEnd.x, nearEnd.y)),
                  "An opaque road underneath the fade would hide the original ground");
            if (kind == BoardRoad.Kind.DIRT) {
                var tracks = road.tracks(1);
                Vector3 worn = new Vector3(outward).scl(.68f);
                assertTrue(tracks.contains(worn.x - 3, worn.y) && tracks.contains(worn.x + 3, worn.y));
                assertFalse(tracks.contains(worn.x, worn.y), "The ground can return between the two wheel marks");
            }
        }
    }

    @Test
    void unsealedMarginsExposeGroundWithoutAnOpaqueOrContrastingShoulderUnderThem() {
        for (var kind : List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c, kind, 9, 0, BoardScene.Surface.GRASS));
            var tile = scene.tile(CENTER);
            var road = BoardRoad.of(scene, tile);
            var patches = GpuRoads.patches(tile, road);
            for (int side : new int[] { -1, 1 }) {
                float x = side * (kind.halfWidth - 1);
                var margin = patches.stream().filter(p -> p.shape().contains(x, 0)).toList();
                assertEquals(1, margin.size(), "No backing strip may hide the loose margin's ground coverage");
                assertTrue(margin.getFirst().blended());
                float coverage = GpuRoads.coverage(road, margin.getFirst(), x, 0);
                assertTrue(coverage > .2f && coverage < .8f, "The verge reaches inside the nominal boundary");
                assertEquals(kind == BoardRoad.Kind.DIRT ? "roads/dirt" : "roads/gravel", margin.getFirst().texture(),
                      "No pale gravel outline round a dirt road");
                assertEquals(0, GpuRoads.coverage(road, margin.getFirst(), side * (kind.halfWidth + BoardRoad.SHOULDER), 0));
            }
        }
    }

    @Test
    void isolatedRoadHasTwoProperEndsWithoutCreatingAnyExits() {
        var scene = BoardSurfaceBlendTest.scene(c -> tile(c, BoardRoad.Kind.DIRT, 0, 0, BoardScene.Surface.GRASS));
        var road = BoardRoad.of(scene, scene.tile(CENTER));
        var shape = road.footprint(0);
        assertTrue(shape.contains(0, -20) && shape.contains(0, 20));
        assertFalse(shape.contains(0, -32) || shape.contains(0, 32));
        assertFalse(road.tracks(0).isEmpty());
        assertTrue(road.markings(CENTER, 0).isEmpty());
        for (int d = 0; d < 6; d++) {
            Vector3 gate = gate(CENTER, d);
            assertFalse(shape.contains(gate.x, gate.y));
        }
    }

    @Test
    void dirtAndGravelKeepTheirLooseMarginsThroughAJoin() {
        for (int d = 0; d < 6; d++) {
            int direction = d, reverse = (d + 3) % 6;
            Coords next = CENTER.translated(d);
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c,
                  c.equals(CENTER) ? BoardRoad.Kind.DIRT : BoardRoad.Kind.GRAVEL,
                  c.equals(CENTER) ? 1 << direction : 1 << reverse, 0, BoardScene.Surface.GRASS));
            Vector3 gate = gate(CENTER, d), across = new Vector3(-gate.y, gate.x, 0).nor();
            for (Coords at : List.of(CENTER, next)) {
                var tile = scene.tile(at);
                var road = BoardRoad.of(scene, tile);
                var patches = GpuRoads.patches(tile, road);
                for (float offset : new float[] { -8, -6.5f, 6.5f, 8 }) {
                    Vector3 p = new Vector3(gate).scl(at.equals(CENTER) ? 1 : -1).mulAdd(across, offset);
                    var margin = patches.stream().filter(t -> t.shape().contains(p.x, p.y)).toList();
                    assertEquals(2, margin.size(), "Only the two fading materials meet in the loose margin");
                    Vector3 beforeJoin = new Vector3(across).scl(offset);
                    var usualEdge = patches.stream().filter(t -> t.shape().contains(beforeJoin.x, beforeJoin.y))
                          .findFirst().orElseThrow();
                    for (var patch : margin) {
                        assertTrue(patch.blended(), "An opaque strip would harden the join's edge");
                        assertEquals(GpuRoads.coverage(road, usualEdge, beforeJoin.x, beforeJoin.y),
                              GpuRoads.coverage(road, patch, p.x, p.y), .002f,
                              "Material changes must preserve the broad edge fade");
                    }
                }
            }
        }
    }

    @Test
    void wheelSpacingStaysConstantThroughMaterialChangesAndRoadEndFades() {
        for (int d = 0; d < 6; d++) {
            int direction = d;
            Coords neighbor = CENTER.translated(d);
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c,
                  c.equals(neighbor) ? BoardRoad.Kind.ALLEY : BoardRoad.Kind.DIRT,
                  c.equals(neighbor) ? 1 << ((direction + 3) % 6) : 1 << direction, 0, BoardScene.Surface.GRASS));
            var road = BoardRoad.of(scene, scene.tile(CENTER));
            Vector3 along = gate(CENTER, d).nor(), across = new Vector3(-along.y, along.x, 0);
            for (float progress = -23; progress <= 34; progress += 3) {
                for (int side : new int[] { -1, 1 }) {
                    Vector3 centre = new Vector3(along).scl(progress).mulAdd(across, side * BoardRoad.WHEEL_OFFSET);
                    assertEquals(1, road.wheelCoverage(centre.x, centre.y), .001f);
                    var heading = road.wheelDirection(centre.x, centre.y);
                    assertEquals(1, heading.len(), .001f);
                    assertEquals(1, Math.abs(heading.x * along.x + heading.y * along.y), .001f,
                          "Compacted scuffs follow the road, including reversed paths");
                    assertTrue(road.tracks(1 << d).contains(centre.x, centre.y));
                    Vector3 outside = new Vector3(centre).mulAdd(across, side * (BoardRoad.TRACK_HALF_WIDTH + .2f));
                    assertEquals(0, road.wheelCoverage(outside.x, outside.y));
                }
            }
        }
    }

    @Test
    void mixedMaterialsAgreeAtTheirSharedEdgeInEveryDirection() {
        for (var pair : List.of(List.of(BoardRoad.Kind.ALLEY, BoardRoad.Kind.GRAVEL),
              List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL), List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.PAVED))) {
            for (int d = 0; d < 6; d++) {
                int direction = d, reverse = (d + 3) % 6;
                Coords next = CENTER.translated(d);
                var scene = BoardSurfaceBlendTest.scene(c -> tile(c,
                      c.equals(CENTER) ? pair.getFirst() : pair.getLast(),
                      c.equals(CENTER) ? 1 << direction : 1 << reverse, 0, BoardScene.Surface.GRASS));
                var first = BoardRoad.of(scene, scene.tile(CENTER));
                var second = BoardRoad.of(scene, scene.tile(next));
                Vector3 gate = gate(CENTER, d), across = new Vector3(-gate.y, gate.x, 0).nor();
                var a = first.joins().getFirst();
                var b = second.joins().getFirst();
                for (float offset = -4; offset <= 4; offset++) {
                    Vector3 p = new Vector3(gate).mulAdd(across, offset);
                    Vector3 q = new Vector3(gate).scl(-1).mulAdd(across, offset);
                    assertEquals(.5f, a.coverage(p.x, p.y), .001f);
                    assertEquals(.5f, b.coverage(q.x, q.y), .001f);
                }
                var firstCover = GpuRoads.patches(scene.tile(CENTER), first).stream()
                      .filter(p -> p.fade() != null && p.fade().join() != null).findFirst().orElseThrow();
                var secondCover = GpuRoads.patches(scene.tile(next), second).stream()
                      .filter(p -> p.fade() != null && p.fade().join() != null).findFirst().orElseThrow();
                assertEquals(firstCover.texture(), secondCover.texture(), "Use the same deposit on both sides of the edge");
                assertEquals(GpuRoads.materialCoverage(firstCover, gate.x, gate.y),
                      GpuRoads.materialCoverage(secondCover, -gate.x, -gate.y), .001f);
                Vector3 along = new Vector3(gate).nor();
                for (float progress = -20; progress < 35; progress += 2) {
                    Vector3 edgePoint = new Vector3(along).scl(progress).mulAdd(across, 7.5f);
                    assertEquals(0, first.distance(edgePoint.x, edgePoint.y), .001f,
                          "The material transition must not squeeze the carriageway");
                }
            }
        }
    }

    @Test
    void junctionPaintFollowsThePavedRouteAndDoesNotSpillOntoAnAlley() {
        Coords alley = CENTER.translated(2);
        var scene = BoardSurfaceBlendTest.scene(c -> tile(c,
              c.equals(alley) ? BoardRoad.Kind.ALLEY : BoardRoad.Kind.PAVED,
              c.equals(CENTER) ? 13 : c.equals(alley) ? 32 : 9, 0, BoardScene.Surface.GRASS));
        var road = BoardRoad.of(scene, scene.tile(CENTER));
        var paint = road.markings(CENTER, 13);
        Vector3 branch = gate(CENTER, 2).nor();
        for (float at = 14; at < 36; at += .5f) {
            assertFalse(paint.contains(branch.x * at, branch.y * at), "No stranded lane dashes on an unmarked branch");
        }
        var straightScene = BoardSurfaceBlendTest.scene(c -> tile(c, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
        var straight = BoardRoad.of(straightScene, straightScene.tile(CENTER)).markings(CENTER, 9);
        // Avoid sampling exactly on a dash's float-rounded transverse boundary.
        for (float y = -33.875f; y <= 34; y += .25f) {
            assertEquals(straight.contains(0, y), paint.contains(0, y),
                  "The main road's dash sequence must continue unchanged past an unmarked branch, y=" + y);
        }
    }

    @Test
    void allRoadLayersLieOnTheExistingRampAndDoNotChangeSupport() {
        for (int d = 0; d < 6; d++) {
            int direction = d;
            Coords next = CENTER.translated(d);
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c, BoardRoad.Kind.PAVED,
                  c.equals(CENTER) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
                  c.equals(CENTER) ? 2 : 0, BoardScene.Surface.GRASS));
            var tile = scene.tile(CENTER);
            var surface = new BoardSurface(scene, tile);
            var road = BoardRoad.of(scene, tile);
            int triangles = 0;
            for (var patch : GpuRoads.patches(tile, road)) {
                for (var triangle : GpuRoads.drape(tile, surface, patch)) {
                    Vector3 p = new Vector3(triangle.a()).add(triangle.b()).add(triangle.c()).scl(1f / 3);
                    assertEquals(surface.height(p.x, p.y) + patch.lift() * BoardGeometry.hexScale(), p.z, .002f);
                    triangles++;
                }
            }
            assertTrue(triangles > 10);
            Vector3 shared = BoardGeometry.center(CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
            assertEquals(BoardGeometry.level(), surface.height(shared.x, shared.y), .01f);
        }
    }

    @Test
    void roadKindsUseEngineGroundButUnknownArtworkAndSpecialCombinationsRemainVisible() {
        Hex hex = new Hex(0);
        for (int level = 1; level <= 4; level++) {
            hex.addTerrain(new Terrain(Terrains.ROAD, level, true, 9));
            assertNotEquals(BoardRoad.Kind.NONE, BoardRoad.capture(hex));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
            for (int special : new int[] { Terrains.ROAD_FLUFF, Terrains.RUBBLE, Terrains.WATER, Terrains.ICE }) {
                hex.addTerrain(new Terrain(special, 1));
                assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Preserve unsupported combination " + special);
                hex.removeTerrain(special);
            }
        }
        hex.addTerrain(new Terrain(Terrains.ROAD, 17, true, 9));
        assertEquals(BoardRoad.Kind.NONE, BoardRoad.capture(hex));
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()));
    }

    @Test
    void changingRoadKindInvalidatesGeometryAndSurroundingGroundStillBlends() {
        var before = BoardSurfaceBlendTest.scene(c -> tile(c, c.equals(CENTER) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(CENTER) ? 9 : 0, 0, c.getX() < 5 ? BoardScene.Surface.GRASS : BoardScene.Surface.SAND));
        var after = BoardSurfaceBlendTest.scene(c -> tile(c, c.equals(CENTER) ? BoardRoad.Kind.ALLEY : BoardRoad.Kind.NONE,
              c.equals(CENTER) ? 9 : 0, 0, c.getX() < 5 ? BoardScene.Surface.GRASS : BoardScene.Surface.SAND));
        assertFalse(before.tile(CENTER).sameGeometry(after.tile(CENTER)));
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(CENTER)), BoardSurface.geometryKey(after, after.tile(CENTER)));
        Vector3 edge = BoardGeometry.center(CENTER, 0).lerp(BoardGeometry.center(CENTER.translated(1), 0), .5f);
        var cover = BoardSurfaceBlend.sample(before, before.tile(CENTER), edge.x, edge.y, edge.z);
        assertTrue(cover.grass() > .1f && cover.sand() > .1f, "Natural verges still blend across the road tile's border");
    }

    @Test
    void roughAndWoodsLeaveRoomForCurvesAndDeadEndsWithoutErasingTheirTerrainMeaning() {
        for (int exits : new int[] { 0, 1, 2, 3, 4, 8, 16, 32 }) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, exits));
            hex.addTerrain(new Terrain(Terrains.ROUGH, 2));
            hex.addTerrain(new Terrain(Terrains.WOODS, 2));
            var road = BoardRoad.clearance(CENTER, exits);
            var features = BoardFeatures.capture(hex, CENTER, Map.of());
            assertEquals(9, features.stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).count());
            assertTrue(features.stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
            for (var feature : features) {
                float radius = feature.kind() == BoardScene.FeatureKind.TREE ? 2 : feature.scale() * BoardFeatures.ROUGH_BOULDER_WIDTH / 2;
                assertTrue(road.distance(feature.x(), feature.y()) >= BoardRoad.SHOULDER + radius);
            }
        }
    }

    private static Vector3 gate(Coords at, int d) {
        return BoardGeometry.center(at.translated(d), 0).sub(BoardGeometry.center(at, 0)).scl(.5f / BoardGeometry.hexScale());
    }
}
