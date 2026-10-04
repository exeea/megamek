/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardBridgeSlopeTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @Test
    void connectionsUseAbsoluteDeckLevelsAndRequireReciprocalExits() {
        var at = new Coords(4, 4);
        for (int d = 0; d < 6; d++) {
            int reverse = (d + 3) % 6;
            var a = bridge(at, -2, 3, 1 << d);
            for (int step = -2; step <= 2; step++) {
                var b = bridge(at.translated(d), -1, 2 + step, 1 << reverse);
                assertEquals(Math.abs(step) <= 1, BoardBridge.connected(a, b, d));
                assertEquals(BoardBridge.connected(a, b, d), BoardBridge.connected(b, a, reverse));
                if (Math.abs(step) <= 1) {
                    assertEquals(1 + step / 2f, BoardBridge.edgeElevation(a, b, d));
                    assertEquals(BoardBridge.edgeElevation(a, b, d), BoardBridge.edgeElevation(b, a, reverse));
                }
            }
            assertFalse(BoardBridge.connected(a, bridge(at.translated(d), 0, 2, 1 << d), d));
            assertFalse(BoardBridge.connected(a, null, d));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 3, 5, 7, 9, 21, 27, 63 })
    void manufacturedTurnsAndJunctionsJoinTheirDecksRailsAndSoffits(int exits) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            var scene = scene(at, exits, true);
            var tile = scene.tile(at);
            var deck = BoardBridge.deck(scene, tile);
            assertEquals(BoardRoad.Kind.PAVED, deck.kind(), "The road material traverses the stepped span");
            var shape = manufactured(scene, tile);
            var road = deck.road(at);
            var footprint = road.footprint(0);
            var center = BoardGeometry.center(at, 2);
            float lift = GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
            for (int d = 0; d < 6; d++) {
                if ((exits & (1 << d)) == 0) { continue; }
                var next = scene.tile(at.translated(d));
                var other = manufactured(scene, next);
                var gate = BoardGeometry.center(next.coords(), 2).sub(center).scl(.5f);
                int edge = Math.floorMod(1 - d, 6);
                var across = BoardGeometry.corner(at, 0, edge + 1).sub(BoardGeometry.corner(at, 0, edge)).nor();
                float joint = (2 + step(d) / 2f) * BoardGeometry.level() + lift;
                for (float offset : new float[] { -8.25f, -6, 0, 6, 8.25f }) {
                    var p = new Vector3(center).add(gate).mulAdd(across, offset * BoardGeometry.hexScale());
                    var inside = new Vector3(p).mulAdd(gate, -.0001f);
                    var outside = new Vector3(p).mulAdd(gate, .0001f);
                    boolean rail = Math.abs(offset) > BoardRoad.Kind.PAVED.halfWidth;
                    float top = joint + (rail ? 2.5f * BoardGeometry.hexScale() : 0);
                    assertEquals(top, height(shape, inside, false), .005f, "First mouth, exit " + d);
                    assertEquals(top, height(other, outside, false), .005f, "Opposite mouth, exit " + d);
                    assertEquals(joint - 1.5f * BoardGeometry.hexScale(), height(shape, inside, true), .005f);
                    assertEquals(height(shape, inside, true), height(other, outside, true), .01f);
                }
                // Check the actual incline between the nominal centre and the shared edge, including both rails.
                for (float t : new float[] { .55f, .7f, .9f }) {
                    for (float offset : new float[] { -8.25f, 0, 8.25f }) {
                        var p = new Vector3(center).mulAdd(gate, t).mulAdd(across, offset * BoardGeometry.hexScale());
                        boolean lane = footprint.contains((p.x - center.x) / BoardGeometry.hexScale(),
                              (p.y - center.y) / BoardGeometry.hexScale());
                        float expected = center.z + lift + step(d) * BoardGeometry.level() * (t - .5f)
                              + (lane ? 0 : 2.5f * BoardGeometry.hexScale());
                        assertEquals(expected, height(shape, p, false), .01f, "Continuous grade at exit " + d);
                    }
                }
            }
            var carriageway = road.footprint(-.1f);
            for (var patch : GpuRoads.deckPatches(tile, deck, road)) {
                var paint = GpuRoads.deck(patch, shape);
                float offset = (patch.lift() + .01f - GpuRoads.SURFACE_LIFT) * BoardGeometry.hexScale();
                for (var face : paint) {
                    var p = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    if (!carriageway.contains((p.x - center.x) / BoardGeometry.hexScale(),
                          (p.y - center.y) / BoardGeometry.hexScale()) || !BoardGeometry.contains(at, p.x, p.y)) { continue; }
                    assertEquals(height(shape, p, false) + offset, p.z, .005f,
                          "Road material and markings must follow the physical deck at " + p + " on " + face);
                }
            }
        }
    }

    @Test
    void naturalStepsShareTheirEntireOpenMouthAtMixedLods() {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            var scene = scene(at, 63, false);
            var tile = scene.tile(at);
            var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), TerrainLod.FULL, new HashMap<>());
            var center = BoardGeometry.center(at, 2);
            assertEquals(center.z, height(shape, center, false), .001f);
            for (int d = 0; d < 6; d++) {
                var next = scene.tile(at.translated(d));
                var gate = BoardGeometry.center(next.coords(), 2).sub(center).scl(.5f);
                var seam = new Vector3(center).add(gate);
                var along = new Vector3(gate).nor();
                var points = shape.facets().stream().filter(f -> f.part() != BoardBridge.Part.SIDE)
                      .flatMap(f -> List.of(f.a(), f.b(), f.c()).stream())
                      .filter(p -> Math.abs(new Vector3(p).sub(seam).dot(along)) < .001f).distinct().toList();
                assertEquals(4, points.size());
                assertEquals((2 + step(d) / 2f) * BoardGeometry.level(),
                      points.stream().mapToDouble(p -> p.z).max().orElseThrow(), .001f);
                for (var lod : TerrainLod.values()) {
                    var other = BoardNaturalBridge.build(scene, next, BoardBridge.deck(scene, next), lod, new HashMap<>());
                    var vertices = other.facets().stream().flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).toList();
                    for (var p : points) {
                        assertTrue(vertices.stream().anyMatch(q -> q.epsilonEquals(p, .001f)), "Shared top and underside: " + lod);
                    }
                    assertFalse(other.facets().stream().anyMatch(f -> List.of(f.a(), f.b(), f.c()).stream()
                          .allMatch(p -> Math.abs(new Vector3(p).sub(seam).dot(along)) < .001f)),
                          "No end wall across connected natural bridges");
                }
            }
        }
    }

    static BoardBridge.Shape manufactured(BoardScene scene, BoardScene.Tile tile) {
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, new HashMap<>());
        return BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing.shape());
    }

    static float height(BoardBridge.Shape shape, Vector3 point, boolean underside) {
        var ray = new Ray(new Vector3(point.x, point.y, underside ? -200 : 200),
              new Vector3(0, 0, underside ? 1 : -1));
        float distance = (float) Math.sqrt(shape.hit(ray));
        return ray.origin.z + ray.direction.z * distance;
    }

    static int step(int direction) { return direction % 3 - 1; }

    static BoardScene scene(Coords center, int exits, boolean roads) {
        var route = new HashMap<Coords, BoardScene.Tile>();
        route.put(center, bridge(center, -1, 3, exits));
        for (int d = 0; d < 6; d++) {
            if ((exits & (1 << d)) == 0) { continue; }
            var at = center.translated(d);
            int straight = (1 << d) | (1 << ((d + 3) % 6));
            // Vary the supporting ground too: connectivity uses absolute deck elevation.
            route.put(at, bridge(at, -2, 4 + step(d), straight));
            if (roads) {
                at = at.translated(d);
                route.put(at, BoardRoadTest.tile(at, BoardRoad.Kind.PAVED, straight, 2 + step(d), BoardScene.Surface.ROCK));
            }
        }
        var tiles = new ArrayList<BoardScene.Tile>();
        int width = Math.max(9, center.getX() + 4), height = Math.max(9, center.getY() + 4);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                var at = new Coords(x, y);
                tiles.add(route.getOrDefault(at, BoardRoadTest.tile(at, BoardRoad.Kind.NONE, 0, -2, BoardScene.Surface.ROCK)));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene.Tile bridge(Coords at, int ground, int height, int exits) {
        var tile = BoardRoadTest.tile(at, BoardRoad.Kind.NONE, 0, ground, BoardScene.Surface.ROCK);
        var hex = new Hex(ground);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, height));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        return new BoardScene.Tile(at, ground, -1, false, 0, tile.surface(), tile.ground(), null, null, null, null,
              BoardFeatures.capture(hex, at, Map.of()), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE);
    }
}
