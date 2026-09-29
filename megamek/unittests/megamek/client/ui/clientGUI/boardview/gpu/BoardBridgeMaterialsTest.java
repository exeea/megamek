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
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardBridgeMaterialsTest {
    @ParameterizedTest
    @EnumSource(value = BoardRoad.Kind.class, names = { "PAVED", "ALLEY", "DIRT", "GRAVEL" })
    void matchingApproachesContinueTheirMaterialAndMarkingsAcrossTheDeck(BoardRoad.Kind kind) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            for (int d = 0; d < 6; d++) {
                var scene = straight(at, d, 1, kind, kind);
                var tile = scene.tile(at);
                var deck = BoardBridge.deck(scene, tile);
                assertEquals(kind, deck.kind());
                var road = deck.road(at);
                assertTrue(road.joins().isEmpty());
                var patches = GpuRoads.deckPatches(tile, deck, road);
                assertEquals(kind == BoardRoad.Kind.PAVED, patches.stream()
                      .anyMatch(p -> p.texture().equals("concrete") && !p.shape().isEmpty()));
                assertTrue(patches.stream().filter(p -> !p.shape().isEmpty()).allMatch(p ->
                      p.texture().equals(GpuRoads.texture(kind)) || p.texture().equals("concrete")));
                assertFalse(patches.stream().anyMatch(p -> p.fade() != null && p.fade().outer() > 0),
                      "Decks must not grow loose shoulders outside their rails");
            }
        }
    }

    @Test
    void mixedApproachesUseTheSameTransitionOnTheRoadAndDeck() {
        for (var pair : List.of(List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT),
              List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.GRAVEL), List.of(BoardRoad.Kind.ALLEY, BoardRoad.Kind.DIRT),
              List.of(BoardRoad.Kind.ALLEY, BoardRoad.Kind.GRAVEL), List.of(BoardRoad.Kind.GRAVEL, BoardRoad.Kind.DIRT))) {
            for (int x : new int[] { 3, 4 }) {
                var at = new Coords(x, 4);
                for (int d = 0; d < 6; d++) {
                    var scene = straight(at, d, 1, pair.getFirst(), pair.getLast());
                    var tile = scene.tile(at);
                    var next = scene.tile(at.translated(d));
                    var deck = BoardBridge.deck(scene, tile);
                    assertEquals(pair.getFirst(), deck.kind());
                    var bridgeRoad = deck.road(at);
                    var groundRoad = BoardRoad.of(scene, next);
                    assertEquals(1, bridgeRoad.joins().size());
                    assertEquals(1, groundRoad.joins().size());
                    var a = GpuRoads.deckPatches(tile, deck, bridgeRoad).stream()
                          .filter(p -> !p.shape().isEmpty() && p.fade() != null && p.fade().join() != null)
                          .findFirst().orElseThrow();
                    var b = GpuRoads.patches(next, groundRoad).stream()
                          .filter(p -> !p.shape().isEmpty() && p.fade() != null && p.fade().join() != null)
                          .findFirst().orElseThrow();
                    assertEquals(a.texture(), b.texture(), "Both halves must use the same material over the same base");
                    var gate = BoardGeometry.center(next.coords(), 0).sub(BoardGeometry.center(at, 0)).scl(.5f);
                    var across = new Vector3(-gate.y, gate.x, 0).nor();
                    for (float offset : new float[] { -6, 0, 6 }) {
                        var p = new Vector3(gate).mulAdd(across, offset);
                        var q = new Vector3(gate).scl(-1).mulAdd(across, offset);
                        assertEquals(.5f, GpuRoads.materialCoverage(a, p.x, p.y), .001f);
                        assertEquals(GpuRoads.materialCoverage(a, p.x, p.y),
                              GpuRoads.materialCoverage(b, q.x, q.y), .001f);
                    }
                }
            }
        }
    }

    @Test
    void pavedApproachesKeepMarkingsWhenTheOtherApproachIsAnAlley() {
        var at = new Coords(4, 4);
        for (int d = 0; d < 6; d++) {
            var scene = straight(at, d, 1, BoardRoad.Kind.PAVED, BoardRoad.Kind.ALLEY);
            var tile = scene.tile(at);
            var deck = BoardBridge.deck(scene, tile);
            assertEquals(BoardRoad.Kind.PAVED, deck.kind());
            assertTrue(GpuRoads.deckPatches(tile, deck, deck.road(at)).stream()
                  .anyMatch(p -> p.texture().equals("concrete") && !p.shape().isEmpty()));
        }
    }

    @Test
    void onlyConnectedRoadsAtDeckHeightSupplyTheMaterial() {
        var at = new Coords(4, 4);
        var scene = straight(at, 0, 1, BoardRoad.Kind.DIRT, BoardRoad.Kind.DIRT);
        scene = replace(scene, BoardRoadTest.tile(at.translated(1), BoardRoad.Kind.PAVED, 63, 0, BoardScene.Surface.GRASS));
        assertEquals(BoardRoad.Kind.DIRT, BoardBridge.kind(scene, scene.tile(at)), "A road beside the bridge is not an approach");
        scene = replace(scene, BoardRoadTest.tile(at.translated(0), BoardRoad.Kind.PAVED, 1, 0, BoardScene.Surface.GRASS));
        assertEquals(BoardRoad.Kind.DIRT, BoardBridge.kind(scene, scene.tile(at)), "The road must have a reciprocal exit");
        scene = replace(scene, BoardRoadTest.tile(at.translated(0), BoardRoad.Kind.PAVED, 9, -3, BoardScene.Surface.GRASS));
        assertEquals(BoardRoad.Kind.DIRT, BoardBridge.kind(scene, scene.tile(at)), "A road below the deck does not resurface it");
    }

    @Test
    void longSpansInheritTheirBestApproachAndRespondToDistantEdits() {
        var start = new Coords(4, 1);
        for (var best : List.of(BoardRoad.Kind.GRAVEL, BoardRoad.Kind.ALLEY, BoardRoad.Kind.PAVED)) {
            for (boolean reverse : new boolean[] { false, true }) {
                var scene = straight(start, 3, 12, reverse ? best : BoardRoad.Kind.DIRT,
                      reverse ? BoardRoad.Kind.DIRT : best);
                for (int y = 1; y <= 12; y++) {
                    var tile = scene.tile(new Coords(4, y));
                    var deck = BoardBridge.deck(scene, tile);
                    assertEquals(best, deck.kind(), "Every deck hex must inherit the best approach at either end");
                    if (y > 1 && y < 12) {
                        assertTrue(deck.road(tile.coords()).joins().isEmpty(), "No material changes inside a span");
                    }
                }
            }
        }
        var onlyDirt = straight(start, 3, 12, BoardRoad.Kind.DIRT, BoardRoad.Kind.NONE);
        var edited = straight(start, 3, 12, BoardRoad.Kind.GRAVEL, BoardRoad.Kind.NONE);
        var far = new Coords(4, 11);
        assertEquals(BoardRoad.Kind.DIRT, BoardBridge.deck(onlyDirt, onlyDirt.tile(far)).kind());
        assertEquals(BoardRoad.Kind.GRAVEL, BoardBridge.deck(edited, edited.tile(far)).kind());
        var isolated = straight(start, 3, 12, BoardRoad.Kind.NONE, BoardRoad.Kind.NONE);
        assertTrue(BoardBridge.deck(isolated, isolated.tile(far)).natural());
    }

    static BoardScene straight(Coords start, int direction, int length, BoardRoad.Kind before, BoardRoad.Kind after) {
        int exits = (1 << direction) | (1 << ((direction + 3) % 6));
        Map<Coords, BoardScene.Tile> route = new HashMap<>();
        for (int i = 0; i < length; i++) {
            Coords at = start.translated(direction, i);
            Hex bridge = new Hex(0);
            bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
            bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 0));
            bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
            var ground = BoardSurfaceBlendTest.tile(at, BoardScene.Surface.GRASS, 0, 1, 0).ground();
            route.put(at, new BoardScene.Tile(at, 0, 1, false, 0, BoardScene.Surface.GRASS,
                  ground, null, null, null, null, BoardFeatures.capture(bridge, at, Map.of()), List.of(),
                  BoardLiquid.WATER, null, true, BoardRoad.Kind.NONE));
        }
        Coords entrance = start.translated((direction + 3) % 6), exit = start.translated(direction, length);
        route.put(entrance, BoardRoadTest.tile(entrance, before, before == BoardRoad.Kind.NONE ? 0 : exits, 0, BoardScene.Surface.GRASS));
        route.put(exit, BoardRoadTest.tile(exit, after, after == BoardRoad.Kind.NONE ? 0 : exits, 0, BoardScene.Surface.GRASS));
        int width = Math.max(9, route.keySet().stream().mapToInt(Coords::getX).max().orElseThrow() + 2);
        int height = Math.max(9, route.keySet().stream().mapToInt(Coords::getY).max().orElseThrow() + 2);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                var at = new Coords(x, y);
                tiles.add(route.getOrDefault(at, BoardRoadTest.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene replace(BoardScene scene, BoardScene.Tile tile) {
        var tiles = new ArrayList<>(scene.tiles());
        tiles.set(tile.coords().getX() * scene.height() + tile.coords().getY(), tile);
        return new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }
}
