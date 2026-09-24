/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardSurfaceTest {
    @Test
    void supportCacheReusesGeometryAcrossPosesAndInvalidatesForTerrainAndTuning() {
        var cache = new BoardSurface.Cache();
        var scene = scene(false);
        var first = cache.get(scene, scene.tile(FIRST));
        assertSame(first, cache.get(scene.withUnits(List.of()), scene.tile(FIRST)));
        var edited = scene(false, 1, false, false, 2);
        var cliff = cache.get(edited, edited.tile(FIRST));
        assertEquals(0, cliff.ramps);
        assertNotSame(first, cliff);
        var tuning = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(tuning.hexScale() * 1.2f, tuning.unitScale(), tuning.unitHeightScale(),
                  tuning.levelHeight(), tuning.gridShade(), tuning.multiHexUnitScale()));
            var resized = cache.get(edited, edited.tile(FIRST));
            assertNotSame(cliff, resized);
            var center = BoardGeometry.center(FIRST, 2);
            assertEquals(center.z, resized.height(center.x, center.y), .001f);
        } finally { BoardGeometry.tune(tuning); }
        cache.clear();
        assertNotSame(cliff, cache.get(edited, edited.tile(FIRST)));
    }

    private static final Coords FIRST = new Coords(1, 1);
    private static final Coords SECOND = FIRST.translated(1);

    @Test
    void connectedRoadsMeetWithoutAnExposedStepAndPickingFollowsTheCut() {
        BoardScene scene = scene(false);
        BoardSurface high = new BoardSurface(scene, scene.tile(FIRST));
        BoardSurface low = new BoardSurface(scene, scene.tile(SECOND));
        Vector3 gate = BoardGeometry.corner(FIRST, 0, 0).lerp(BoardGeometry.corner(FIRST, 0, 1), 0.5f);
        assertEquals(BoardGeometry.LEVEL, high.height(gate.x, gate.y), 0.01f);
        assertEquals(high.height(gate.x, gate.y), low.height(gate.x, gate.y), 0.01f);
        Vector3 highCenter = BoardGeometry.center(FIRST, 2);
        Vector3 lowCenter = BoardGeometry.center(SECOND, 0);
        Vector3 cut = new Vector3(gate).lerp(highCenter, 0.15f);
        Vector3 ramp = new Vector3(gate).lerp(lowCenter, 0.15f);
        assertTrue(high.height(cut.x, cut.y) < highCenter.z);
        assertTrue(low.height(ramp.x, ramp.y) > lowCenter.z);
        assertEquals(FIRST, BoardGeometry.pick(scene, new Ray(new Vector3(cut.x, cut.y, 200), new Vector3(0, 0, -1))));
        assertEquals(SECOND, BoardGeometry.pick(scene, new Ray(new Vector3(ramp.x, ramp.y, 200), new Vector3(0, 0, -1))));
        UnitMotion movement = new UnitMotion(new BoardScene.Waypoint(FIRST, 2, 1));
        movement.append(List.of(new BoardScene.Waypoint(FIRST, 2, 1), new BoardScene.Waypoint(SECOND, 0, 1)),
              EntityMovementType.MOVE_WALK, 0);
        movement.advance(movement.remainingSeconds() * 0.4, 1);
        Vector3 moving = movement.surfacePosition(scene);
        assertEquals(high.height(moving.x, moving.y), moving.z, 0.01f, "Walking must not sink into the upper road shoulder");
    }

    @Test
    void aRoadEndingAtTheEdgeStillCutsAndFillsBothApproachesInEveryDirection() {
        for (int direction = 0; direction < 6; direction++) {
            for (boolean roadOnHighSide : List.of(true, false)) {
                BoardScene scene = scene(false, direction, roadOnHighSide, !roadOnHighSide, 2);
                Coords neighbor = FIRST.translated(direction);
                BoardSurface high = new BoardSurface(scene, scene.tile(FIRST));
                BoardSurface low = new BoardSurface(scene, scene.tile(neighbor));
                Vector3 highCenter = BoardGeometry.center(FIRST, 2);
                Vector3 lowCenter = BoardGeometry.center(neighbor, 0);
                Vector3 gate = new Vector3(highCenter).lerp(lowCenter, 0.5f);
                assertEquals(BoardGeometry.LEVEL, high.height(gate.x, gate.y), 0.01f);
                assertEquals(high.height(gate.x, gate.y), low.height(gate.x, gate.y), 0.01f);
                Vector3 cut = new Vector3(gate).lerp(highCenter, 0.15f);
                Vector3 fill = new Vector3(gate).lerp(lowCenter, 0.15f);
                assertTrue(high.height(cut.x, cut.y) < highCenter.z);
                assertTrue(low.height(fill.x, fill.y) > lowCenter.z);
                UnitMotion movement = new UnitMotion(new BoardScene.Waypoint(FIRST, 2, direction));
                movement.append(List.of(new BoardScene.Waypoint(FIRST, 2, direction),
                      new BoardScene.Waypoint(neighbor, 0, direction)), EntityMovementType.MOVE_WALK, 0);
                double duration = movement.remainingSeconds();
                movement.advance(duration * 0.4, 1);
                Vector3 moving = movement.surfacePosition(scene);
                assertEquals(high.height(moving.x, moving.y), moving.z, 0.01f);
                movement.advance(duration * 0.2, 1);
                moving = movement.surfacePosition(scene);
                assertEquals(low.height(moving.x, moving.y), moving.z, 0.01f);
            }
        }
        BoardScene scene = scene(false, 1, false, false, 2);
        BoardSurface high = new BoardSurface(scene, scene.tile(FIRST));
        assertEquals(0, high.ramps, "Ground without an edge-reaching road retains the cliff");
        Vector3 center = BoardGeometry.center(FIRST, 2);
        assertEquals(center.z, high.height(center.x, center.y), .001f,
              "An eroded cliff rim preserves the level interior and introduces no road ramp");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadsMeetBridgeDecksWithoutCuttingOrFillingTheGroundBelow(int direction) {
        for (boolean roadUnderBridge : List.of(false, true)) {
            BoardScene scene = bridgeScene(direction, (direction + 3) % 6, 2, roadUnderBridge);
            Coords neighbor = FIRST.translated(direction);
            BoardSurface road = new BoardSurface(scene, scene.tile(FIRST));
            BoardSurface belowBridge = new BoardSurface(scene, scene.tile(neighbor));
            Vector3 gate = BoardGeometry.center(FIRST, 0).lerp(BoardGeometry.center(neighbor, 0), 0.5f);
            assertEquals(0, road.height(gate.x, gate.y), 0.01f,
                  "The road must stay level with the connecting bridge deck");
            assertEquals(-2 * BoardGeometry.LEVEL, belowBridge.height(gate.x, gate.y), 0.01f,
                  "The bridge approach must not raise the ground beneath the deck");
            Vector3 approach = new Vector3(gate).lerp(BoardGeometry.center(FIRST, 0), 0.15f);
            BoardGeometry.Hit hit = BoardGeometry.hit(scene,
                  new Ray(new Vector3(approach.x, approach.y, 200), new Vector3(0, 0, -1)));
            assertNotNull(hit);
            assertEquals(FIRST, hit.coords());
            assertEquals(200, Math.sqrt(hit.distance()), 0.01, "Picking must follow the flat bridge approach");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadsRampUpAndDownToAlignedBridgeDecksOverLandAndWater(int direction) {
        for (int roadElevation : new int[] { 0, 1, 2 }) {
            for (boolean water : List.of(false, true)) {
                BoardScene scene = bridgeScene(direction, (direction + 3) % 6, 1, water, roadElevation, 0, water);
                Coords neighbor = FIRST.translated(direction);
                BoardSurface road = new BoardSurface(scene, scene.tile(FIRST));
                BoardSurface belowBridge = new BoardSurface(scene, scene.tile(neighbor));
                Vector3 center = BoardGeometry.center(FIRST, roadElevation);
                Vector3 gate = new Vector3(center).lerp(BoardGeometry.center(neighbor, 1), 0.5f);
                assertEquals(BoardGeometry.LEVEL, road.height(gate.x, gate.y), 0.01f,
                      "The road must reach the deck's full height at the shared edge");
                assertEquals(center.z, road.height(center.x, center.y), 0.01f);
                assertEquals(0, belowBridge.height(gate.x, gate.y), 0.01f,
                      "The bridge approach must not deform the ground beneath the deck");
                Vector3 bridgeCenter = BoardGeometry.center(neighbor, 0);
                assertEquals(BoardGeometry.groundZ(scene.tile(neighbor)),
                      belowBridge.height(bridgeCenter.x, bridgeCenter.y), 0.01f);
                Vector3 approach = new Vector3(gate).lerp(center, 0.25f);
                float height = (roadElevation + 1) * BoardGeometry.LEVEL / 2;
                assertEquals(height, road.height(approach.x, approach.y), 0.01f,
                      "The existing road corridor must slope between the unchanged hub and the deck");
                BoardGeometry.Hit hit = BoardGeometry.hit(scene,
                      new Ray(new Vector3(approach.x, approach.y, 200), new Vector3(0, 0, -1)));
                assertNotNull(hit);
                assertEquals(FIRST, hit.coords());
                assertEquals(200 - height, Math.sqrt(hit.distance()), 0.01);
                if (roadElevation == 0) {
                    Vector3 inward = new Vector3(center.x - gate.x, center.y - gate.y, 0).nor();
                    Vector3 origin = new Vector3(approach.x, approach.y, height).mulAdd(inward, -2);
                    hit = BoardGeometry.hit(scene, new Ray(origin, inward));
                    assertNotNull(hit, "Picking bounds must include a ramp above both hexes' ground levels");
                    assertEquals(FIRST, hit.coords());
                    assertEquals(2, Math.sqrt(hit.distance()), 0.01);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aConnectingLandRoadWinsOverABridgeAtADifferentLevel(int direction) {
        for (int roadElevation : new int[] { 0, 2 }) {
            for (int bridgeHeight : new int[] { 1, 3 }) {
                BoardScene scene = bridgeScene(direction, (direction + 3) % 6, bridgeHeight, true,
                      roadElevation, 0, false);
                Coords neighbor = FIRST.translated(direction);
                Vector3 gate = BoardGeometry.center(FIRST, 0).lerp(BoardGeometry.center(neighbor, 0), 0.5f);
                float height = roadElevation * BoardGeometry.LEVEL / 2;
                assertEquals(height, new BoardSurface(scene, scene.tile(FIRST)).height(gate.x, gate.y), 0.01f);
                assertEquals(height, new BoardSurface(scene, scene.tile(neighbor)).height(gate.x, gate.y), 0.01f,
                      "Both ground roads must meet, even when an aligned bridge is within one level");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aBridgeRampRequiresARoadExitTowardTheBridge(int direction) {
        for (int exits : new int[] { 0, 1 << ((direction + 1) % 6) }) {
            BoardScene scene = bridgeScene(direction, (direction + 3) % 6, 1, false, 0, 0, true);
            List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
            BoardScene.Tile tile = scene.tile(FIRST);
            tiles.set(FIRST.getX() * scene.height() + FIRST.getY(), new BoardScene.Tile(FIRST, 0, -1, false,
                  exits, tile.surface(), tile.ground(), null, null, List.of(), List.of()));
            scene = new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
            Vector3 gate = BoardGeometry.center(FIRST, 0)
                  .lerp(BoardGeometry.center(FIRST.translated(direction), 0), 0.5f);
            assertEquals(0, new BoardSurface(scene, scene.tile(FIRST)).height(gate.x, gate.y), 0.01f);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void unrelatedBridgeArmsAndOverpassesKeepTheGroundRoadApproach(int direction) {
        int reverse = (direction + 3) % 6;
        for (int bridgeDirection = 0; bridgeDirection < 6; bridgeDirection++) {
            for (int bridgeHeight : new int[] { 0, 1, 2, 3, 4 }) {
                for (boolean roadUnderBridge : List.of(false, true)) {
                    if (bridgeDirection == reverse && Math.abs(bridgeHeight - 2) <= 1
                          && (bridgeHeight == 2 || !roadUnderBridge)) {
                        continue;
                    }
                    BoardScene scene = bridgeScene(direction, bridgeDirection, bridgeHeight, roadUnderBridge);
                    Coords neighbor = FIRST.translated(direction);
                    Vector3 gate = BoardGeometry.center(FIRST, 0).lerp(BoardGeometry.center(neighbor, 0), 0.5f);
                    assertEquals(-BoardGeometry.LEVEL,
                          new BoardSurface(scene, scene.tile(FIRST)).height(gate.x, gate.y), 0.01f);
                    assertEquals(-BoardGeometry.LEVEL,
                          new BoardSurface(scene, scene.tile(neighbor)).height(gate.x, gate.y), 0.01f);
                }
            }
        }
    }

    @Test
    void riverBanksOccupyOnlyTheLandEdgesAndNeighboringWaterMouthsMatch() {
        BoardScene scene = scene(true);
        BoardSurface first = new BoardSurface(scene, scene.tile(FIRST));
        BoardSurface second = new BoardSurface(scene, scene.tile(SECOND));
        Vector3 center = BoardGeometry.center(FIRST, 0);
        assertEquals(-2 * BoardGeometry.LEVEL, first.height(center.x, center.y), 0.01f);
        Vector3 bank = BoardGeometry.corner(FIRST, 0, 3).lerp(BoardGeometry.corner(FIRST, 0, 4), 0.5f);
        assertEquals(0, first.height(bank.x, bank.y), 0.01f);
        Vector3 a = BoardGeometry.corner(FIRST, 0, 0), b = BoardGeometry.corner(FIRST, 0, 1);
        List<Vector3> mouth = first.water.stream().filter(p -> onEdge(p, a, b)).toList();
        List<Vector3> neighbor = second.water.stream().filter(p -> onEdge(p, a, b)).toList();
        assertTrue(mouth.size() >= 2);
        assertEquals(mouth.size(), neighbor.size());
        assertTrue(mouth.stream().allMatch(p -> neighbor.stream().anyMatch(n -> n.epsilonEquals(p, 0.01f))));
        assertTrue(first.water.stream().anyMatch(p -> p.dst2(BoardGeometry.corner(FIRST, 0, 3)) > 1));
    }

    private static boolean onEdge(Vector3 p, Vector3 a, Vector3 b) {
        return Math.abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) < 0.01f;
    }

    @Test
    void largeCliffsLeaveRoadEndsFlatUnlessBothRoadExitsConnect() {
        for (int direction = 0; direction < 6; direction++) {
            Coords neighbor = FIRST.translated(direction);
            Vector3 gate = BoardGeometry.center(FIRST, 0).lerp(BoardGeometry.center(neighbor, 0), 0.5f);
            for (int elevation : new int[] { -3, 3 }) {
                for (boolean roadOnFirstSide : List.of(true, false)) {
                    BoardScene scene = scene(false, direction, roadOnFirstSide, !roadOnFirstSide, elevation);
                    BoardSurface first = new BoardSurface(scene, scene.tile(FIRST));
                    BoardSurface second = new BoardSurface(scene, scene.tile(neighbor));
                    assertEquals(elevation * BoardGeometry.LEVEL, first.height(gate.x, gate.y), 0.01f,
                          "A road end at a large cliff must retain its own elevation");
                    assertEquals(0, second.height(gate.x, gate.y), 0.01f,
                          "The adjacent hex must also retain its flat surface");
                }
                BoardScene connected = scene(false, direction, true, true, elevation);
                BoardSurface first = new BoardSurface(connected, connected.tile(FIRST));
                BoardSurface second = new BoardSurface(connected, connected.tile(neighbor));
                assertEquals(elevation * BoardGeometry.LEVEL / 2, first.height(gate.x, gate.y), 0.01f);
                assertEquals(first.height(gate.x, gate.y), second.height(gate.x, gate.y), 0.01f,
                      "An actual connecting road still meets at the shared ramp height");
            }
        }
    }

    @Test
    void straightRiversMeanderAsChannelsInsteadOfMakingAPool() {
        for (int direction = 0; direction < 6; direction++) {
            Coords center = new Coords(2, 2);
            BoardScene scene = riverScene(Map.of(center, 0, center.translated(direction), 0,
                  center.translated((direction + 3) % 6), 0), false);
            BoardSurface surface = new BoardSurface(scene, scene.tile(center));
            Vector3 along = BoardGeometry.center(center.translated(direction), 0).sub(BoardGeometry.center(center, 0)).nor();
            Vector3 across = new Vector3(along).crs(Vector3.Z);
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (Vector3 point : surface.water) {
                float offset = new Vector3(point).sub(BoardGeometry.center(center, 0)).dot(across);
                min = Math.min(min, offset);
                max = Math.max(max, offset);
            }
            int edge = Math.floorMod(1 - direction, 6);
            Vector3 a = BoardGeometry.corner(center, 0, edge), b = BoardGeometry.corner(center, 0, edge + 1);
            float edgeWidth = a.dst(b);
            var mouth = surface.water.stream().filter(point -> onEdge(point, a, b)).toList();
            float mouthWidth = 0;
            for (Vector3 first : mouth) {
                for (Vector3 second : mouth) {
                    mouthWidth = Math.max(mouthWidth, first.dst(second));
                }
            }
            assertTrue(mouthWidth > edgeWidth * 0.75f && mouthWidth < edgeWidth * 0.85f,
                  "The water uses most of the shared edge while leaving room for the sand fade");
            // It meanders and breathes within the hex, but stays a channel: never much wider than its mouths.
            assertTrue(max - min > mouthWidth * .99f && max - min < mouthWidth * 1.6f,
                  "The channel stays a channel: " + (max - min) + " across for a mouth of " + mouthWidth);
            for (Vector3 corner : List.of(a, b)) {
                assertTrue(surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.TOP)
                      .anyMatch(face -> face.a().epsilonEquals(corner, 0.01f)
                            || face.b().epsilonEquals(corner, 0.01f) || face.c().epsilonEquals(corner, 0.01f)),
                      "The bank starts at the shared edge's corners");
            }
            assertEquals(-BoardGeometry.LEVEL, surface.height(BoardGeometry.centerX(center), BoardGeometry.centerY(center)), 0.01f);
        }
    }

    @Test
    void aRiverRunsUpToTheSlopesBesideItWhichCarryOnUnderItToTheBed() {
        // A river a level deep between banks a level above its surface: two levels between the grounds units stand on,
        // a slope that meets the water on the edge and carries on down to the bed.
        BoardSculptTest.withTransitions(true, () -> {
            Coords center = new Coords(3, 3);
            for (int direction = 0; direction < 6; direction++) {
                List<Coords> river = List.of(center, center.translated(direction),
                      center.translated((direction + 3) % 6));
                List<BoardScene.Tile> tiles = new ArrayList<>();
                for (int x = 0; x < 7; x++) {
                    for (int y = 0; y < 7; y++) {
                        Coords coords = new Coords(x, y);
                        boolean wet = river.contains(coords);
                        tiles.add(new BoardScene.Tile(coords, wet ? 0 : 1, wet ? 1 : -1, false, 0,
                              BoardScene.Surface.SAND, null, null, null, null, null, List.of(), List.of(),
                              wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
                    }
                }
                BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                List<Integer> mouths = List.of(Math.floorMod(1 - direction, 6), Math.floorMod(-2 - direction, 6));
                for (int edge = 0; edge < 6; edge++) {
                    if (mouths.contains(edge)) { continue; }
                    Vector3 a = BoardGeometry.corner(center, 0, edge), b = BoardGeometry.corner(center, 0, edge + 1);
                    Vector3 inward = BoardGeometry.center(center, 0).sub(new Vector3(a).lerp(b, .5f)).nor();
                    for (int sample = 20; sample <= 80; sample += 5) {
                        Vector3 point = new Vector3(a).lerp(b, sample / 100f);
                        float nearest = Float.MAX_VALUE;
                        for (int i = 0; i < surface.water.size(); i++) {
                            nearest = Math.min(nearest, distance(point, surface.water.get(i),
                                  surface.water.get((i + 1) % surface.water.size())));
                        }
                        assertTrue(nearest < .08f * BoardGeometry.WIDTH, "Direction " + direction + ", edge " + edge
                              + ", sample " + sample + ": the water keeps " + nearest + " from the slope");
                    }
                    // Where a slope a level high would stop at the water's own level, it is already deep under it.
                    Vector3 foot = new Vector3(a).lerp(b, .5f).mulAdd(inward, BoardRelief.stepRoom());
                    assertTrue(surface.height(foot.x, foot.y) < -.6f * BoardGeometry.LEVEL,
                          "Direction " + direction + ", edge " + edge + ": the bed at the slope's foot lies at "
                                + surface.height(foot.x, foot.y));
                }
            }
        });
    }

    @Test
    void bentChannelsTriangulateTheirActualOutlineAndMeetTheNeighboringMouths() {
        Coords center = new Coords(2, 2);
        for (int from = 0; from < 6; from++) {
            for (int separation : new int[] { 2, 3, 4 }) {
                int to = (from + separation) % 6;
                BoardScene scene = riverScene(Map.of(center, 0, center.translated(from), 0,
                      center.translated(to), 0), false);
                BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                assertEquals(-BoardGeometry.LEVEL, surface.height(BoardGeometry.centerX(center), BoardGeometry.centerY(center)), 0.01f,
                      "A unit at the centre of a bend must stand on its full-depth bed");
                float polygonArea = 0, trianglesArea = 0;
                for (int i = 0; i < surface.water.size(); i++) {
                    Vector3 a = surface.water.get(i), b = surface.water.get((i + 1) % surface.water.size());
                    polygonArea += a.x * b.y - a.y * b.x;
                }
                for (BoardSurface.Face face : surface.waterFaces) {
                    float area = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                    assertTrue(area >= -0.01f, "No inverted water triangles");
                    trianglesArea += area;
                }
                assertEquals(polygonArea, trianglesArea, 0.1f, "Water must not fan across the curved banks");
                for (int direction : new int[] { from, to }) {
                    BoardSurface neighbor = new BoardSurface(scene, scene.tile(center.translated(direction)));
                    int edge = Math.floorMod(1 - direction, 6);
                    Vector3 a = BoardGeometry.corner(center, 0, edge), b = BoardGeometry.corner(center, 0, edge + 1);
                    var mouth = surface.water.stream().filter(p -> onEdge(p, a, b)).toList();
                    assertTrue(mouth.size() >= 2);
                    assertTrue(mouth.stream().allMatch(p -> neighbor.water.stream().anyMatch(n -> n.epsilonEquals(p, 0.01f))));
                }
            }
        }
    }

    @Test
    void waterAndBanksStayInsideTheirHexForEveryNeighborPattern() {
        Coords center = new Coords(7, 33);
        for (int mask = 0; mask < 64; mask++) {
            BoardScene scene = riverScene(center, mask);
            BoardSurface surface = new BoardSurface(scene, scene.tile(center));
            List<BoardSurface.Face> faces = new ArrayList<>(surface.faces);
            faces.addAll(surface.waterFaces);
            for (BoardSurface.Face face : faces) {
                for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                    for (int edge = 0; edge < 6; edge++) {
                        Vector3 a = BoardGeometry.corner(center, 0, edge);
                        Vector3 b = BoardGeometry.corner(center, 0, edge + 1);
                        Vector3 outward = new Vector3(b).sub(a).crs(Vector3.Z).nor();
                        float outside = new Vector3(point).sub(a).dot(outward);
                        // Only a fall's crest, rounding the prow between two falls, bows out over the pool below.
                        boolean falls = (mask & 1 << BoardGeometry.edgeDirection(edge)) != 0;
                        float allowed = falls ? BoardSurface.VALLEY : 0.01f;
                        assertTrue(outside <= allowed * BoardGeometry.HEX_SCALE,
                              "Water neighbor mask " + mask + ", " + face.finish() + " outside edge " + edge + ": " + point);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(floats = { 0.5f, 1, 2 })
    void dryRiverEdgesRetainTheirFullHeight(float scale) {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(scale, 0.6f, 0.87f, 18, 0.8f));
            Coords center = new Coords(7, 33);
            for (int mask = 0; mask < 64; mask++) {
                BoardScene scene = riverScene(center, mask);
                BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                float top = scene.tile(center).elevation() * BoardGeometry.LEVEL;
                for (int edge = 0; edge < 6; edge++) {
                    if ((mask & (1 << BoardGeometry.edgeDirection(edge))) != 0) {
                        continue;
                    }
                    Vector3 a = BoardGeometry.corner(center, 0, edge);
                    Vector3 b = BoardGeometry.corner(center, 0, edge + 1);
                    Vector3 middle = new Vector3(a).lerp(b, .5f);
                    Vector3 inward = BoardGeometry.center(center, 0).sub(middle).nor();
                    // Just inside the bank's rim, which rounds its corners as every cliff's does.
                    for (int sample = 20; sample <= 80; sample++) {
                        Vector3 point = new Vector3(a).lerp(b, sample / 100f).mulAdd(inward, .015f * BoardGeometry.WIDTH);
                        assertEquals(top, surface.height(point.x, point.y), 0.01f * scale,
                              "Water neighbor mask " + mask + ", dry edge " + edge + ", sample " + sample);
                    }
                }
                for (BoardSurface.Side side : surface.sides(scene, BoardGeometry.floor(scene))) {
                    if ((mask & (1 << BoardGeometry.edgeDirection(side.edge()))) == 0) {
                        assertEquals(top, side.a().z, 0.01f * scale, "The cliff must close beneath the dry bank");
                        assertEquals(top, side.b().z, 0.01f * scale, "The cliff must close beneath the dry bank");
                    }
                }
            }
        } finally {
            BoardGeometry.tune(original);
        }
    }

    private static BoardScene riverScene(Coords center, int mask) {
        Map<Coords, Integer> levels = new HashMap<>();
        levels.put(center, 2);
        for (int direction = 0; direction < 6; direction++) {
            if ((mask & (1 << direction)) != 0) {
                levels.put(center.translated(direction), 0);
            }
        }
        return riverScene(levels, false);
    }

    @Test
    void waterfallsJoinTheUpperAndLowerWaterSurfacesOnlyOnceAndArePickable() {
        Coords high = new Coords(2, 2);
        for (int direction = 0; direction < 6; direction++) {
            Coords low = high.translated(direction);
            BoardScene scene = riverScene(Map.of(high, 3, low, 0), false);
            BoardSurface upper = new BoardSurface(scene, scene.tile(high));
            BoardSurface lower = new BoardSurface(scene, scene.tile(low));
            assertEquals(1, upper.waterfalls.size());
            assertTrue(lower.waterfalls.isEmpty());
            BoardSurface.Side fall = upper.waterfalls.getFirst();
            assertEquals(BoardGeometry.waterZ(scene.tile(high)), fall.a().z, 0.001f);
            assertEquals(BoardGeometry.waterZ(scene.tile(low)), fall.lowA(), 0.001f);
            Vector3 point = new Vector3(fall.a()).lerp(fall.b(), 0.5f).add(0, 0, -2);
            Vector3 outward = new Vector3(fall.b()).sub(fall.a()).crs(Vector3.Z).nor();
            assertEquals(high, BoardGeometry.pick(scene, new Ray(new Vector3(point).mulAdd(outward, 6), outward.scl(-1))));
        }
        BoardScene frozen = riverScene(Map.of(high, 3, high.translated(3), 0), true);
        assertTrue(new BoardSurface(frozen, frozen.tile(high)).waterfalls.isEmpty());
    }

    @Test
    void sculptedBedsDescendWithoutFoldingAndMeetBedsOfTheirLevelWithoutAStep() {
        var random = new java.util.Random(7);
        var art = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        for (int trial = 0; trial < 12; trial++) {
            List<BoardScene.Tile> tiles = new ArrayList<>();
            for (int x = 0; x < 7; x++) {
                for (int y = 0; y < 7; y++) {
                    tiles.add(new BoardScene.Tile(new Coords(x, y), random.nextInt(3) == 0 ? 1 : 0,
                          random.nextInt(5) == 0 ? -1 : random.nextInt(4), false, 0, BoardScene.Surface.GRASS, art,
                          null, null, List.of(), List.of()));
                }
            }
            BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
            for (BoardScene.Tile tile : tiles) {
                if (!tile.liquid().present()) { continue; }
                BoardSurface surface = new BoardSurface(scene, tile);
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() == BoardSurface.Finish.BED) {
                        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                        assertTrue(normal.z > 0, "A sculpted bed must never fold over at " + tile.coords());
                    }
                }
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                assertEquals(BoardGeometry.groundZ(tile), surface.height(center.x, center.y), 0.01f,
                      "A unit at the centre stands on the full-depth bed");
                for (int edge = 0; edge < 6; edge++) {
                    BoardScene.Tile other = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
                    if (other == null || !other.liquid().present() || other.elevation() != tile.elevation()) { continue; }
                    BoardSurface neighbor = new BoardSurface(scene, other);
                    Vector3 a = BoardGeometry.corner(tile.coords(), 0, edge);
                    Vector3 b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
                    for (int sample = 1; sample < 60; sample++) {
                        Vector3 point = new Vector3(a).lerp(b, sample / 60f);
                        assertEquals(surface.height(point.x, point.y), neighbor.height(point.x, point.y), 0.01f,
                              "Beds of one level meet without a step: " + tile.coords() + " edge " + edge);
                    }
                }
            }
        }
    }

    @Test
    void fallsThatShareACornerTurnItTogetherWithoutAGap() {
        Coords high = new Coords(3, 3);
        // One pool falling over two neighbouring edges, then two pools side by side falling into one pool.
        for (boolean twoPools : List.of(false, true)) {
            Map<Coords, Integer> levels = new HashMap<>();
            levels.put(high, 3);
            if (twoPools) {
                levels.put(high.translated(1), 3);
                levels.put(high.translated(2), 1);
            } else {
                levels.put(high.translated(2), 1);
                levels.put(high.translated(3), 1);
            }
            BoardScene scene = riverScene(levels, false);
            List<BoardSurface> pools = new ArrayList<>(List.of(new BoardSurface(scene, scene.tile(high))));
            if (twoPools) { pools.add(new BoardSurface(scene, scene.tile(high.translated(1)))); }
            List<Vector3[][]> sheets = new ArrayList<>();
            List<BoardSurface> owners = new ArrayList<>();
            for (BoardSurface pool : pools) {
                for (BoardSurface.Side fall : pool.waterfalls) {
                    sheets.add(GpuWaterfall.grid(pool, fall));
                    owners.add(pool);
                }
            }
            assertEquals(2, sheets.size(), "Two falls meet at the corner");
            Vector3[] first = null, second = null;
            for (Vector3[] columnA : List.of(sheets.get(0)[0], sheets.get(0)[sheets.get(0).length - 1])) {
                for (Vector3[] columnB : List.of(sheets.get(1)[0], sheets.get(1)[sheets.get(1).length - 1])) {
                    if (columnA[columnA.length - 1].dst(columnB[columnB.length - 1]) < 1) {
                        first = columnA;
                        second = columnB;
                    }
                }
            }
            assertNotNull(first, "The sheets must end at the same corner");
            for (int row = 0; row < first.length; row++) {
                assertTrue(first[row].epsilonEquals(second[row], 0.01f),
                      "Both sheets turn the corner on the same vertices, row " + row + ": " + first[row] + " " + second[row]);
            }
            // Where the water leaves the pool, the sheet starts on the pool's own pulled-back edge.
            Vector3 crest = first[0];
            for (BoardSurface pool : owners) {
                assertTrue(pool.water.stream().anyMatch(point -> point.epsilonEquals(crest, 0.01f)),
                      "Each pool's surface reaches the shared crest corner: " + crest);
            }
        }
    }

    @Test
    void fallsAlongARowCurveRoundEveryCornerAndMeetWithoutAGap() {
        // A lake spilling along a whole row of hexes: its edge zigzags, prows and valleys by turns.
        Map<Coords, Integer> levels = new HashMap<>();
        for (int x = 1; x <= 7; x++) {
            levels.put(new Coords(x, 2), 3);
            levels.put(new Coords(x, 3), 0);
            levels.put(new Coords(x, 4), 0);
        }
        BoardScene scene = riverScene(levels, false);
        float floor = BoardGeometry.floor(scene);
        List<BoardSurface> pools = new ArrayList<>();
        for (int x = 1; x <= 7; x++) { pools.add(new BoardSurface(scene, scene.tile(new Coords(x, 2)))); }
        List<Vector3[]> ends = new ArrayList<>();
        List<Vector3> crestEnds = new ArrayList<>();
        List<BoardSurface.Side> walls = new ArrayList<>();
        int joined = 0, falls = 0;
        for (BoardSurface pool : pools) {
            for (BoardSurface.Face face : pool.faces) {
                if (face.finish() == BoardSurface.Finish.BED) {
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(normal.z > 0, "The bed reaching out to a crest never folds at " + pool.tile.coords());
                }
            }
            walls.addAll(pool.sides(scene, floor));
            for (BoardSurface.Side fall : pool.waterfalls) {
                BoardSurface.Crest crest = pool.crest(fall);
                Coords below = pool.tile.coords().translated(BoardGeometry.edgeDirection(fall.edge()));
                Vector3 a = BoardGeometry.corner(pool.tile.coords(), 0, fall.edge());
                Vector3 b = BoardGeometry.corner(pool.tile.coords(), 0, fall.edge() + 1);
                Vector3 outward = new Vector3(b).sub(a).crs(Vector3.Z).nor();
                for (int k = 0; k <= 24; k++) {
                    Vector3 point = crest.point(k / 24f);
                    float out = new Vector3(point).sub(a).dot(outward);
                    assertTrue(out > -0.01f && out < (BoardSurface.VALLEY + .01f) * BoardGeometry.HEX_SCALE,
                          "The crest bows out a little over the pool below, never into its own hex: " + out);
                    Vector3 inward = BoardGeometry.center(below, 0).sub(point.x, point.y, 0).nor().scl(.01f);
                    assertTrue(BoardGeometry.contains(below, point.x + inward.x, point.y + inward.y),
                          "The crest stays over the pool it falls into: " + point);
                }
                Vector3[][] grid = GpuWaterfall.grid(pool, fall);
                ends.add(grid[0]);
                ends.add(grid[grid.length - 1]);
                crestEnds.add(crest.a());
                crestEnds.add(crest.b());
                joined += (pool.fallJoins(fall, true) ? 1 : 0) + (pool.fallJoins(fall, false) ? 1 : 0);
                falls++;
            }
        }
        assertEquals(13, falls, "The row falls over every edge it shares with the lake below");
        assertEquals(2 * (falls - 1), joined, "Every corner along the row carries the fall on into the next");
        for (int i = 0; i < ends.size(); i++) {
            for (int j = i + 1; j < ends.size(); j++) {
                if (ends.get(i)[0].dst(ends.get(j)[0]) > 1) { continue; }
                for (int row = 0; row < ends.get(i).length; row++) {
                    assertTrue(ends.get(i)[row].epsilonEquals(ends.get(j)[row], 0.01f),
                          "Neighbouring sheets turn each corner on the same vertices, row " + row);
                }
            }
        }
        // The walls beneath the crests run on from one fall to the next: every crest end tops one.
        for (Vector3 end : crestEnds) {
            long meeting = walls.stream().filter(side -> side.a().dst(end.x, end.y, side.a().z) < 0.01f
                  || side.b().dst(end.x, end.y, side.b().z) < 0.01f).count();
            assertTrue(meeting >= 1, "A wall stands under the crest at " + end);
        }
    }

    @Test
    void poolsSharingAnEdgeChurnWithTheSameFallsNearIt() {
        Map<Coords, Integer> levels = new HashMap<>();
        for (int x = 1; x <= 7; x++) {
            levels.put(new Coords(x, 2), 3);
            levels.put(new Coords(x, 3), 0);
            levels.put(new Coords(x, 4), 0);
        }
        BoardScene scene = riverScene(levels, false);
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        java.util.function.Function<Coords, BoardSurface> lookup = coords -> {
            BoardScene.Tile tile = scene.tile(coords);
            return tile == null || !tile.liquid().present() ? null
                  : surfaces.computeIfAbsent(coords, key -> new BoardSurface(scene, tile));
        };
        Map<Coords, List<GpuWaterShader.Impact>> impacts = new HashMap<>();
        for (int x = 1; x <= 7; x++) {
            for (int y = 3; y <= 4; y++) {
                Coords coords = new Coords(x, y);
                impacts.put(coords, GpuWaterShader.impacts(scene, scene.tile(coords), lookup));
            }
        }
        assertFalse(impacts.get(new Coords(4, 3)).isEmpty(), "A pool below falls churns");
        for (var entry : impacts.entrySet()) {
            for (int direction = 0; direction < 6; direction++) {
                List<GpuWaterShader.Impact> other = impacts.get(entry.getKey().translated(direction));
                if (other == null) { continue; }
                int edge = Math.floorMod(1 - direction, 6);
                Vector3 a = BoardGeometry.corner(entry.getKey(), 0, edge);
                Vector3 b = BoardGeometry.corner(entry.getKey(), 0, edge + 1);
                for (GpuWaterShader.Impact impact : entry.getValue()) {
                    // The shader's boil reaches at most 2.1 radii from its landing, and behind a curtain a further
                    // 0.085 hex widths; one that can reach the shared edge churns both sides of it, so no seam shows.
                    float reach = 2.1f * impact.radius() + .085f * BoardGeometry.WIDTH, nearest = Float.MAX_VALUE;
                    for (int k = 0; k <= 20; k++) {
                        Vector3 point = new Vector3(a).lerp(b, k / 20f);
                        nearest = Math.min(nearest, distance(point, impact.from(), impact.to()));
                    }
                    if (nearest < reach) {
                        assertTrue(other.contains(impact), "Both pools churn with a landing near their edge");
                    }
                }
            }
        }
    }

    @Test
    void waterAboveLandKeepsItsOutlineOnTheEdge() {
        // A pond raised above the land round it, frozen or not: the land takes the whole step on its own side, so the
        // pond's bank stands at its own height right up to the hex edge (rim rocks may stand on it) and its ice rests
        // on it.
        Coords center = new Coords(3, 3);
        for (Runnable tuning : tunings(true, () -> {
            for (int[] pond : new int[][] { { 1, 0 }, { 1, 1 }, { 2, 1 }, { 3, 1 }, { 3, 3 } }) {
                for (boolean frozen : List.of(false, true)) {
                    BoardScene scene = sandScene(7, 0, Map.of(center, pond[0]), Map.of(center, pond[1]), frozen);
                    BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                    for (int edge = 0; edge < 6; edge++) {
                        Vector3 a = BoardGeometry.corner(center, 0, edge);
                        Vector3 b = BoardGeometry.corner(center, 0, edge + 1);
                        Vector3 inward = BoardGeometry.center(center, 0).sub(new Vector3(a).lerp(b, .5f)).nor();
                        for (int sample = 30; sample <= 70; sample += 10) {
                            Vector3 point = new Vector3(a).lerp(b, sample / 100f)
                                  .mulAdd(inward, BoardGeometry.HEX_SCALE);
                            assertTrue(surface.height(point.x, point.y) > (pond[0] - .01f) * BoardGeometry.LEVEL,
                                  "Water " + pond[0] + " deep " + pond[1] + (frozen ? " frozen" : "")
                                        + " over land 0, edge " + edge + ", sample " + sample + ": the bank dips to "
                                        + surface.height(point.x, point.y));
                        }
                    }
                }
            }
        })) {
            tuning.run();
        }
    }

    @Test
    void waterBanksNeverFoldWhereSlopesMeetBeachesAndWalls() {
        // Ponds and rivers a level deep whose banks mix slopes up from the water (land a level up), beaches (land at
        // the water's level) and walls (land two levels up): the bank strips never turn face down.
        Coords center = new Coords(3, 3);
        for (Runnable tuning : tunings(true, () -> {
            List<int[]> ponds = new ArrayList<>(List.of(new int[] { 1, 2, 1, 1, 1, 1 }, new int[] { 1, 1, 0, 0, 0, 0 },
                  new int[] { 1, 3, 1, 2, 1, 4 }));
            for (int edge = 0; edge < 6; edge++) {
                int[] one = new int[6];
                one[edge] = 1;
                ponds.add(one);
            }
            for (int[] land : ponds) {
                Map<Coords, Integer> levels = new HashMap<>();
                for (int edge = 0; edge < 6; edge++) {
                    levels.put(center.translated(BoardGeometry.edgeDirection(edge)), land[edge]);
                }
                assertBanksFaceUp(sandScene(7, 0, levels, Map.of(center, 1), false), List.of(center),
                      "pond beside " + Arrays.toString(land));
            }
            int[][] kinds = { { 1, 0, 0, 1, 0, 0 }, { 1, 2, 1, 2, 1, 2 }, { 2, 1, 2, 1, 2, 1 } };
            for (int from = 0; from < 6; from++) {
                for (int separation : new int[] { 2, 3, 4 }) {
                    List<Coords> river = List.of(center, center.translated(from),
                          center.translated((from + separation) % 6));
                    for (int[] kind : kinds) {
                        Map<Coords, Integer> levels = new HashMap<>(), depths = new HashMap<>();
                        for (int direction = 0; direction < 6; direction++) {
                            levels.put(center.translated(direction), kind[direction]);
                        }
                        for (Coords coords : river) {
                            levels.put(coords, 0);
                            depths.put(coords, 1);
                        }
                        assertBanksFaceUp(sandScene(7, 1, levels, depths, false), river, "river from " + from + " by "
                              + separation + " beside " + Arrays.toString(kind));
                    }
                }
            }
        })) {
            tuning.run();
        }
    }

    @Test
    void openMouthsMatchWhereTheStepsThroughTheirCornersMoveThem() {
        // Water no deeper than its surface between slopes, and water raised above the land: the steps through a
        // mouth's corners move its ends, and both hexes of the mouth still meet without a step and never fold.
        Coords center = new Coords(3, 3);
        for (Runnable tuning : tunings(true, () -> {
            for (int[] water : new int[][] { { 0, 0, 1 }, { 0, 0, 2 }, { 1, 1, 0 }, { 1, 2, 0 } }) {
                for (int from = 0; from < 6; from++) {
                    for (int separation : new int[] { 2, 3 }) {
                        List<Coords> river = List.of(center, center.translated(from),
                              center.translated((from + separation) % 6));
                        Map<Coords, Integer> levels = new HashMap<>(), depths = new HashMap<>();
                        for (Coords coords : river) {
                            levels.put(coords, water[0]);
                            depths.put(coords, water[1]);
                        }
                        BoardScene scene = sandScene(7, water[2], levels, depths, false);
                        String label = "water " + water[0] + " deep " + water[1] + " by land " + water[2] + ", from "
                              + from + " by " + separation;
                        assertBanksFaceUp(scene, river, label);
                        BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                        for (int direction : new int[] { from, (from + separation) % 6 }) {
                            BoardSurface neighbor = new BoardSurface(scene, scene.tile(center.translated(direction)));
                            int edge = Math.floorMod(1 - direction, 6);
                            Vector3 a = BoardGeometry.corner(center, 0, edge);
                            Vector3 b = BoardGeometry.corner(center, 0, edge + 1);
                            for (int sample = 1; sample < 60; sample++) {
                                // Where the steps through a corner move the seam off the edge line, only one hex
                                // covers the point; where both do, they meet.
                                Vector3 point = new Vector3(a).lerp(b, sample / 60f);
                                float own = BoardSurface.sampleHeight(surface.faces, point.x, point.y,
                                      Float.NEGATIVE_INFINITY);
                                float other = BoardSurface.sampleHeight(neighbor.faces, point.x, point.y,
                                      Float.NEGATIVE_INFINITY);
                                assertTrue(Float.isInfinite(own) || Float.isInfinite(other)
                                      || Math.abs(own - other) < .01f, label + ": both hexes meet on the mouth, sample "
                                            + sample + ": " + own + " against " + other);
                            }
                        }
                    }
                }
            }
        })) {
            tuning.run();
        }
    }

    @Test
    void unitsStandOnTheGroundDrawnBesideWaterOnRandomBoards() {
        // Over land beside water the ground drawn may be a neighbour's: a water hex's bank or bed, or a step's slope
        // lying over the land's footprint. Units stand on the highest of it (within a quarter level, where a
        // neighbour's rim rock stands a little over the land's own top).
        BoardSculptTest.withTransitions(true, () -> {
            Random random = new Random(3);
            for (int trial = 0; trial < 20; trial++) {
                BoardScene scene = randomBoard(random);
                BoardSurface.Cache cache = new BoardSurface.Cache();
                float floor = BoardGeometry.floor(scene);
                for (BoardScene.Tile tile : scene.tiles()) {
                    List<BoardScene.Tile> around = new ArrayList<>(List.of(tile));
                    for (int direction = 0; direction < 6; direction++) {
                        BoardScene.Tile other = scene.tile(tile.coords().translated(direction));
                        if (other != null) { around.add(other); }
                    }
                    if (tile.liquid().present() || around.stream().noneMatch(other -> other.liquid().present())) {
                        continue;
                    }
                    // The tops, banks and beds drawn over the hex by it and its neighbours, and their steps' slopes.
                    float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
                    List<BoardSurface.Face> tops = new ArrayList<>(), slopes = new ArrayList<>();
                    for (BoardScene.Tile other : around) {
                        BoardSurface surface = cache.get(scene, other);
                        tops.addAll(over(surface.faces, cx, cy));
                        slopes.addAll(over(BoardTacticalGeometry.lying(surface.walls(scene, floor)), cx, cy));
                    }
                    for (float dx = -42; dx <= 42; dx += 6) {
                        for (float dy = -36; dy <= 36; dy += 6) {
                            float x = cx + dx * BoardGeometry.HEX_SCALE, y = cy + dy * BoardGeometry.HEX_SCALE;
                            if (!BoardGeometry.contains(tile.coords(), x, y)) { continue; }
                            float drawn = BoardSurface.sampleHeight(tops, x, y, Float.NEGATIVE_INFINITY);
                            for (BoardSurface.Face face : slopes) { drawn = Math.max(drawn, face.height(x, y)); }
                            if (Float.isFinite(drawn)) {
                                assertEquals(drawn, UnitLandingSupports.ground(scene, x, y, cache),
                                      BoardGeometry.LEVEL / 4, "Trial " + trial + ", " + tile.coords() + " at " + dx
                                            + ", " + dy);
                            }
                        }
                    }
                }
            }
        });
    }

    @Test
    void aStraightGroupEdgeGivesAStraightShore() {
        // A lake three columns wide: its western bank runs straight down a column however the hex outline zig-zags,
        // near the midline between the water and the land.
        Map<Coords, Integer> lake = lake(0, 10);
        BoardScene scene = sandScene(11, 0, Map.of(), lake, false);
        Vector3 top = BoardGeometry.center(new Coords(3, 1), 0);
        float[] west = reach(scene, lake.keySet(), top, new Vector3(0, -1, 0), new Vector3(-1, 0, 0), 8 * 72);
        float hex = amplitude(west, 72), half = amplitude(west, 36);
        assertTrue(hex < 2 && half < 2, "The west bank keeps to a line: " + hex + " and " + half
              + " peak to peak at the hex's period and half of it; along the hex outline it swung 16");
        // The midline runs between the columns; the bank keeps its bias (3) and slow wander (8) inside the water.
        float midline = (BoardGeometry.centerX(new Coords(3, 1)) - BoardGeometry.centerX(new Coords(2, 1))) / 2;
        float mean = 0;
        for (float value : west) { mean += (midline - value) / BoardGeometry.HEX_SCALE / west.length; }
        assertTrue(mean > -2.25f && mean < 8.25f, "The bank keeps near the midline, " + mean + " inside the water");
        // A river two hexes wide running diagonally: its bank keeps to a line too.
        Map<Coords, Integer> river = new HashMap<>();
        for (int x = 0; x < 13; x++) {
            river.put(new Coords(x, x / 2 + 1), 1);
            river.put(new Coords(x, x / 2 + 2), 1);
        }
        BoardScene diagonal = sandScene(13, 0, Map.of(), river, false);
        Vector3 from = BoardGeometry.center(new Coords(2, 2), 0), to = BoardGeometry.center(new Coords(10, 6), 0);
        Vector3 along = new Vector3(to).sub(from).nor(), across = new Vector3(along.y, -along.x, 0);
        float[] bank = reach(diagonal, river.keySet(), from, along, across, from.dst(to));
        float step = from.dst(to) / 8;
        assertTrue(amplitude(bank, step) < 2, "The diagonal bank keeps to a line: " + amplitude(bank, step));
    }

    @Test
    void theWaterRunsPastTheCornerALandHexPokesInAndBothWaterHexesEndTheMouthThere() {
        // Land (2, 4) pokes its east corner in between water hexes (3, 3) and (3, 4).
        BoardScene scene = sandScene(9, 0, Map.of(), lake(1, 7), false);
        Coords land = new Coords(2, 4);
        List<Coords> water = List.of(new Coords(3, 3), new Coords(3, 4));
        Vector3 corner = BoardGeometry.corner(land, 0, 0);
        Vector3 toLand = BoardGeometry.center(land, 0).sub(corner).nor();
        List<Vector3> ends = new ArrayList<>();
        for (Coords coords : water) {
            // The mouth's end near the corner: its water point on the shared edge's line nearest the land.
            Vector3 best = null;
            for (Vector3 p : new BoardSurface(scene, scene.tile(coords)).water) {
                Vector3 d = new Vector3(p).sub(corner);
                d.z = 0;
                if (Math.abs(new Vector3(d).crs(toLand).z) < .01f
                      && (best == null || d.dot(toLand) > new Vector3(best).sub(corner).dot(toLand))) {
                    best = p;
                }
            }
            assertNotNull(best, coords + " has water on the mouth's line");
            ends.add(best);
        }
        assertEquals(ends.get(0), ends.get(1), "Both water hexes end the mouth at the same point");
        float past = new Vector3(ends.getFirst()).sub(corner).dot(toLand) / BoardGeometry.HEX_SCALE;
        assertTrue(past > 1.5f && past < 12.5f, "The mouth ends " + past + " past the land's corner");
        // The land's top stands back there; the water hexes' banks and water cover it, and the land stays level.
        BoardSurface ground = new BoardSurface(scene, scene.tile(land));
        Vector3 inside = new Vector3(corner).mulAdd(toLand, 5 * BoardGeometry.HEX_SCALE);
        assertTrue(Float.isNaN(BoardSurface.sampleHeight(ground.faces, inside.x, inside.y, Float.NaN)));
        float bank = Float.NEGATIVE_INFINITY;
        for (Coords coords : water) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
            bank = Math.max(bank, BoardSurface.sampleHeight(surface.faces, inside.x, inside.y, bank));
        }
        assertTrue(bank <= .001f && bank > -BoardGeometry.LEVEL, "A water hex's bank or bed lies there: " + bank);
        Vector3 anchor = BoardGeometry.center(land, 0);
        assertEquals(0, ground.height(anchor.x, anchor.y), .001f, "The land's anchor stays level");
        // Every seam around the corner closes: each edge near it belongs to exactly two faces.
        Map<List<Long>, Integer> edges = new HashMap<>();
        for (Coords coords : List.of(land, water.get(0), water.get(1))) {
            for (BoardSurface.Face face : new BoardSurface(scene, scene.tile(coords)).faces) {
                Vector3[] q = { face.a(), face.b(), face.c() };
                for (int i = 0; i < 3; i++) {
                    Vector3 p = q[i], r = q[(i + 1) % 3];
                    if (p.dst(corner.x, corner.y, p.z) > 20 * BoardGeometry.HEX_SCALE
                          || r.dst(corner.x, corner.y, r.z) > 20 * BoardGeometry.HEX_SCALE) { continue; }
                    List<Long> a = key(p), b = key(r);
                    List<Long> edge = new ArrayList<>(a.toString().compareTo(b.toString()) < 0 ? a : b);
                    edge.addAll(a.toString().compareTo(b.toString()) < 0 ? b : a);
                    edges.merge(edge, 1, Integer::sum);
                }
            }
        }
        edges.forEach((edge, count) -> assertEquals(2, count, "An edge near the corner is shared by " + count));
    }

    @Test
    void theShoreOverALandCornerIsPickedAndSupportedAsTheLand() {
        BoardScene scene = sandScene(9, 0, Map.of(), lake(1, 7), false);
        Coords land = new Coords(2, 4);
        Vector3 center = BoardGeometry.center(land, 0);
        int checked = 0;
        for (int k = 0; k < 6; k++) {
            Vector3 corner = BoardGeometry.corner(land, 0, k);
            Vector3 point = new Vector3(corner).lerp(center, 3 * BoardGeometry.HEX_SCALE / corner.dst(center));
            float drawn = Float.NEGATIVE_INFINITY, water = Float.NEGATIVE_INFINITY, own = Float.NEGATIVE_INFINITY;
            for (int direction = -1; direction < 6; direction++) {
                Coords coords = direction < 0 ? land : land.translated(direction);
                if (scene.tile(coords) == null) { continue; }
                BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
                float here = BoardSurface.sampleHeight(surface.faces, point.x, point.y, Float.NEGATIVE_INFINITY);
                drawn = Math.max(drawn, here);
                if (direction < 0) { own = here; }
                water = Math.max(water, BoardSurface.sampleHeight(surface.waterFaces, point.x, point.y,
                      Float.NEGATIVE_INFINITY));
            }
            if (Float.isFinite(own)) { continue; } // A corner the land keeps.
            Ray ray = new Ray(new Vector3(point.x, point.y, 500), new Vector3(0, 0, -1));
            assertEquals(land, BoardGeometry.pick(scene, ray), "Picked as the hex whose footprint holds it");
            assertEquals(drawn, UnitLandingSupports.ground(scene, point.x, point.y), .01f,
                  "Units stand on the ground drawn there");
            assertEquals(Math.max(drawn, water), UnitLandingSupports.surface(scene, point.x, point.y, null), .01f,
                  "Markers lie on the water drawn there");
            checked++;
        }
        assertTrue(checked > 0, "The land gives up a corner to the shore");
    }

    @Test
    void iceCoversTheCornersTheLandGivesUpToTheShore() {
        Map<Coords, Integer> lake = lake(1, 7);
        BoardScene frozen = sandScene(9, 0, Map.of(), lake, true), liquid = sandScene(9, 0, Map.of(), lake, false);
        for (Coords coords : lake.keySet()) {
            BoardSurface surface = new BoardSurface(frozen, frozen.tile(coords));
            for (Vector3 p : new BoardSurface(liquid, liquid.tile(coords)).outline) {
                Vector3 q = new Vector3(p).lerp(BoardGeometry.center(coords, 0), .02f);
                boolean covered = surface.faces.stream().anyMatch(face -> face.finish() == BoardSurface.Finish.ICE
                      && Float.isFinite(face.height(q.x, q.y)));
                assertTrue(covered, "Ice covers the water up to its shore at " + coords);
            }
        }
    }

    @Test
    void waterTwoHexesAwayThatMakesItsLandNeighbourAPointChangesItsCacheKey() {
        // (2, 3) is two hexes from (3, 4); as water it gives land (2, 4) a third water neighbour in a row, a point,
        // whose corner the shore of (3, 4) runs past less far.
        Coords water = new Coords(3, 4);
        Map<Coords, Integer> lake = lake(1, 7);
        BoardScene before = sandScene(9, 0, Map.of(), lake, false);
        lake.put(new Coords(2, 3), 1);
        BoardScene after = sandScene(9, 0, Map.of(), lake, false);
        assertNotEquals(new BoardSurface(before, before.tile(water)).outline,
              new BoardSurface(after, after.tile(water)).outline, "The shore stands back from a point's corner");
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(water)),
              BoardSurface.geometryKey(after, after.tile(water)), "So the cache must see that water");
    }

    @Test
    void bedsNeverFoldAndAnchorsKeepTheirGameHeightsOnRandomBoards() {
        Random random = new Random(3);
        for (int trial = 0; trial < 20; trial++) {
            BoardScene scene = randomBoard(random);
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                assertEquals(BoardGeometry.groundZ(tile), surface.height(center.x, center.y), .01f,
                      "Anchor of " + tile.coords() + " in trial " + trial);
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.BED) { continue; }
                    Vector3 n = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(n.z > 0, "A bed of " + tile.coords() + " folds in trial " + trial);
                }
            }
        }
    }

    /** Water a level deep in columns 3 to 5 of rows {@code from} to {@code to}: a lake three hexes wide. */
    private static Map<Coords, Integer> lake(int from, int to) {
        Map<Coords, Integer> lake = new HashMap<>();
        for (int x = 3; x <= 5; x++) {
            for (int y = from; y <= to; y++) { lake.put(new Coords(x, y), 1); }
        }
        return lake;
    }

    /**
     * How far toward the land along {@code across} the water of the given hexes reaches from {@code origin}, every
     * 2 units along {@code along} for {@code length}.
     */
    private static float[] reach(BoardScene scene, Iterable<Coords> water, Vector3 origin, Vector3 along,
          Vector3 across, float length) {
        List<List<Vector3>> outlines = new ArrayList<>();
        for (Coords coords : water) { outlines.add(new BoardSurface(scene, scene.tile(coords)).outline); }
        float[] result = new float[(int) (length / 2) + 1];
        for (int i = 0; i < result.length; i++) {
            float s = 2 * i;
            result[i] = Float.NaN;
            for (float a = -60; a <= 60; a += .25f) {
                float x = origin.x + along.x * s + across.x * a, y = origin.y + along.y * s + across.y * a;
                for (List<Vector3> outline : outlines) {
                    if (inside(outline, x, y)) { result[i] = a; }
                }
            }
        }
        return result;
    }

    /** Whether (x, y) lies inside the closed polygon, by the even-odd rule. */
    private static boolean inside(List<Vector3> polygon, float x, float y) {
        boolean in = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Vector3 a = polygon.get(i), b = polygon.get(j);
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) { in = !in; }
        }
        return in;
    }

    /** Peak-to-peak amplitude of a profile's component with the given period, sampled every 2 units. */
    private static float amplitude(float[] profile, float period) {
        double cc = 0, ss = 0, cs = 0, mean = 0, yc = 0, ys = 0;
        for (float value : profile) { mean += value / profile.length; }
        for (int i = 0; i < profile.length; i++) {
            double phase = 2 * Math.PI * 2 * i / period, co = Math.cos(phase), si = Math.sin(phase);
            cc += co * co;
            ss += si * si;
            cs += co * si;
            yc += (profile[i] - mean) * co;
            ys += (profile[i] - mean) * si;
        }
        double det = cc * ss - cs * cs, c = (yc * ss - ys * cs) / det, s = (ys * cc - yc * cs) / det;
        return (float) (2 * Math.hypot(c, s));
    }

    /** A vertex rounded to a thousandth of a unit, to match shared vertices. */
    private static List<Long> key(Vector3 p) {
        return List.of(Math.round(p.x * 1000.0), Math.round(p.y * 1000.0), Math.round(p.z * 1000.0));
    }

    /** Level distance from p to the segment from a to b. */
    private static float distance(Vector3 p, Vector3 a, Vector3 b) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float t = Math.clamp(((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy), 0, 1);
        return (float) Math.hypot(a.x + dx * t - p.x, a.y + dy * t - p.y);
    }

    private static BoardScene riverScene(Map<Coords, Integer> levels, boolean frozen) {
        var art = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        int width = Math.max(5, levels.keySet().stream().mapToInt(Coords::getX).max().orElseThrow() + 2);
        int height = Math.max(5, levels.keySet().stream().mapToInt(Coords::getY).max().orElseThrow() + 2);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, levels.getOrDefault(coords, 0), levels.containsKey(coords) ? 1 : -1,
                      frozen && levels.containsKey(coords), 0, BoardScene.Surface.GRASS, art, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The check with hex transitions on, and again with the tiles padded eight (and three) metres apart. */
    private static List<Runnable> tunings(boolean narrow, Runnable check) {
        List<Runnable> result = new ArrayList<>(List.of(() -> BoardSculptTest.withTransitions(true, check),
              () -> BoardSculptTest.withPadding(8, check)));
        if (narrow) { result.add(() -> BoardSculptTest.withPadding(3, check)); }
        return result;
    }

    /** The faces whose bounds reach over the footprint of the hex centred at (x, y). */
    private static List<BoardSurface.Face> over(List<BoardSurface.Face> faces, float x, float y) {
        float w = BoardGeometry.WIDTH / 2, h = BoardGeometry.HEIGHT / 2;
        return faces.stream().filter(face -> Math.max(face.a().x, Math.max(face.b().x, face.c().x)) >= x - w
              && Math.min(face.a().x, Math.min(face.b().x, face.c().x)) <= x + w
              && Math.max(face.a().y, Math.max(face.b().y, face.c().y)) >= y - h
              && Math.min(face.a().y, Math.min(face.b().y, face.c().y)) <= y + h).toList();
    }

    /**
     * A random seven by seven board of detailed ground of the first four surfaces: land up to three levels high, and
     * two hexes in five water up to two levels deep at level 0 or 1.
     */
    private static BoardScene randomBoard(Random random) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                boolean wet = random.nextInt(5) < 2;
                tiles.add(new BoardScene.Tile(new Coords(x, y), wet ? random.nextInt(2) : random.nextInt(4),
                      wet ? random.nextInt(3) : -1, false, 0, BoardScene.Surface.values()[random.nextInt(4)], null,
                      null, null, null, null, List.of(), List.of(), wet ? BoardLiquid.WATER : BoardLiquid.NONE, null,
                      true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** No bank (TOP or SHORE face) of the given water hexes faces down. */
    private static void assertBanksFaceUp(BoardScene scene, List<Coords> water, String label) {
        for (Coords coords : water) {
            for (BoardSurface.Face face : new BoardSurface(scene, scene.tile(coords)).faces) {
                if (face.finish() != BoardSurface.Finish.TOP && face.finish() != BoardSurface.Finish.SHORE) {
                    continue;
                }
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                assertTrue(normal.z >= -.001f * normal.len(), label + ": a bank of " + coords + " faces down");
            }
        }
    }

    /**
     * A square board of sand with detailed ground, {@code size} hexes a side: hexes at {@code land} unless levels says
     * otherwise, dry unless depths gives them water.
     */
    private static BoardScene sandScene(int size, int land, Map<Coords, Integer> levels, Map<Coords, Integer> depths,
          boolean frozen) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                Coords coords = new Coords(x, y);
                boolean wet = depths.containsKey(coords);
                tiles.add(new BoardScene.Tile(coords, levels.getOrDefault(coords, land), wet ? depths.get(coords) : -1,
                      frozen && wet, 0, BoardScene.Surface.SAND, null, null, null, null, null, List.of(), List.of(),
                      wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, size, size, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene bridgeScene(int direction, int bridgeDirection, int bridgeHeight, boolean roadUnderBridge) {
        return bridgeScene(direction, bridgeDirection, bridgeHeight, roadUnderBridge, 0, -2, false);
    }

    private static BoardScene bridgeScene(int direction, int bridgeDirection, int bridgeHeight, boolean roadUnderBridge,
          int roadElevation, int bridgeElevation, boolean water) {
        BoardScene scene = scene(false, direction, true, roadUnderBridge, roadElevation);
        Coords coords = FIRST.translated(direction);
        Hex hex = new Hex(bridgeElevation);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 1 << bridgeDirection));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, bridgeHeight));
        BoardScene.Tile tile = scene.tile(coords);
        List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
        tiles.set(coords.getX() * scene.height() + coords.getY(), new BoardScene.Tile(coords, hex.getLevel(),
              water ? 1 : -1, false, tile.roadExits(), tile.surface(), tile.ground(), null, null,
              BoardFeatures.capture(hex, coords, Map.of()), List.of()));
        return new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene scene(boolean river) {
        return scene(river, 1, true, true, 2);
    }

    private static BoardScene scene(boolean river, int direction, boolean firstRoad, boolean secondRoad, int elevation) {
        var art = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                Coords coords = new Coords(x, y);
                boolean first = coords.equals(FIRST), second = coords.equals(FIRST.translated(direction));
                tiles.add(new BoardScene.Tile(coords, !river && first ? elevation : 0,
                      river && (first || second) ? 2 : -1, false,
                      river ? 0 : first && firstRoad ? 1 << direction
                            : second && secondRoad ? 1 << ((direction + 3) % 6) : 0, BoardScene.Surface.GRASS,
                      art, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 4, 4, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
