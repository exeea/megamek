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
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardNaturalBridgeTest {
    static final Coords CENTER = new Coords(4, 4);

    @ParameterizedTest
    @EnumSource(value = BoardScene.Surface.class, names = { "GRASS", "DIRT", "SAND", "ROCK", "SNOW" })
    void bareSpansUseTheBanksSurfaceAndKeepTheirLowerPassageAtEveryLod(BoardScene.Surface family) {
        var scene = scene(family, false, true);
        var tile = scene.tile(CENTER);
        var deck = BoardBridge.deck(scene, tile);
        assertTrue(deck.natural(), "A road underneath is not a road attached to the deck");
        assertEquals(family, deck.surface());
        int previous = Integer.MAX_VALUE;
        var center = BoardGeometry.center(CENTER, 3);
        var below = BoardGeometry.center(CENTER, 2);
        for (var lod : TerrainLod.values()) {
            var shape = BoardNaturalBridge.build(scene, tile, deck, lod, new HashMap<>());
            if (family == BoardScene.Surface.GRASS) {
                System.out.println("Natural bridge " + lod + ": " + shape.facets().size() + " triangles");
            }
            assertTrue(shape.facets().size() < previous, "Each LOD must reduce submitted geometry");
            previous = shape.facets().size();
            assertEquals(100, shape.hit(new Ray(new Vector3(center).add(0, 0, 10), new Vector3(0, 0, -1))), .001f);
            assertEquals(Float.POSITIVE_INFINITY,
                  shape.hit(new Ray(new Vector3(below).add(-100, 0, 0), Vector3.X)),
                  "The legal whole levels beneath the deck stay clear of rock supports");
            assertTrue(shape.facets().stream().anyMatch(f -> f.part() == BoardBridge.Part.SOFFIT && f.normal().z < -.5f));
            for (var face : shape.facets()) {
                assertTrue(Float.isFinite(face.normal().len2()));
                assertEquals(1, face.normal().len(), .001f);
            }
        }
        assertTrue(previous < 150, "A distant span needs only its silhouette and bank contacts");
    }

    @Test
    void connectedDecksKeepTheSameOpenSeamAcrossDifferentLods() {
        for (int x : new int[] { 3, 4 }) {
            var start = new Coords(x, 4);
            for (int d = 0; d < 6; d++) {
                var scene = BoardBridgeMaterialsTest.straight(start, d, 2, BoardRoad.Kind.NONE, BoardRoad.Kind.NONE);
                var a = scene.tile(start);
                var b = scene.tile(start.translated(d));
                var seam = BoardGeometry.center(start, 0).lerp(BoardGeometry.center(b.coords(), 0), .5f);
                var normal = BoardGeometry.center(b.coords(), 0).sub(BoardGeometry.center(start, 0)).nor();
                var full = BoardNaturalBridge.build(scene, a, BoardBridge.deck(scene, a), TerrainLod.FULL, new HashMap<>());
                var points = full.facets().stream().filter(f -> f.part() != BoardBridge.Part.SIDE)
                      .flatMap(f -> List.of(f.a(), f.b(), f.c()).stream())
                      .filter(p -> Math.abs(new Vector3(p).sub(seam).dot(normal)) < .0001f).distinct().toList();
                assertEquals(4, points.size(), "Only the mouth's two top and two underside corners lie on the seam");
                for (var lod : TerrainLod.values()) {
                    var other = BoardNaturalBridge.build(scene, b, BoardBridge.deck(scene, b), lod, new HashMap<>());
                    var vertices = other.facets().stream().flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).toList();
                    for (var p : points) {
                        assertTrue(vertices.stream().anyMatch(q -> p.dst2(q) < .000001f), "No crack at a mixed-LOD join");
                    }
                    assertFalse(other.facets().stream().anyMatch(f -> List.of(f.a(), f.b(), f.c()).stream()
                          .allMatch(p -> Math.abs(new Vector3(p).sub(seam).dot(normal)) < .0001f)),
                          "Connected spans must not have an internal end wall");
                }
            }
        }
    }

    @Test
    void groundRoadAndWaterGeometryRemainIndependentOfTheArch() {
        for (boolean water : new boolean[] { false, true }) {
            var scene = scene(BoardScene.Surface.GRASS, water, true);
            var tile = scene.tile(CENTER);
            var without = new BoardScene.Tile(tile.coords(), tile.elevation(), tile.waterDepth(), tile.frozen(),
                  tile.roadExits(), tile.surface(), tile.ground(), null, null, null, null, List.of(), List.of(),
                  tile.liquid(), null, true, tile.road());
            var groundOnly = replace(scene, without);
            var before = new BoardSurface(groundOnly, without);
            var after = new BoardSurface(scene, tile);
            assertEquals(before.faces, after.faces, "The natural bridge must not fill or reshape the ground below");
            assertEquals(before.waterFaces, after.waterFaces, "The river must remain below the separate bridge shell");
            assertEquals(BoardRoad.of(groundOnly, without).joins(), BoardRoad.of(scene, tile).joins());
            if (!water) {
                var at = BoardGeometry.center(CENTER, 0);
                assertEquals(UnitLandingSupports.ground(groundOnly, at.x, at.y),
                      UnitLandingSupports.ground(scene, at.x, at.y), .001f, "A unit below must stay on the lower road");
            }
        }
    }

    @Test
    void attachingAndRemovingAnApproachChangesTheWholeBridgeAppearance() {
        var scene = scene(BoardScene.Surface.SAND, false, true);
        var tile = scene.tile(CENTER);
        assertTrue(BoardBridge.deck(scene, tile).natural());
        var entrance = CENTER.translated(0);
        var road = BoardRoadTest.tile(entrance, BoardRoad.Kind.GRAVEL, 9, 3, BoardScene.Surface.SAND);
        var edited = replace(scene, road);
        assertFalse(BoardBridge.deck(edited, tile).natural());
        assertEquals(BoardRoad.Kind.GRAVEL, BoardBridge.kind(edited, tile));
        assertTrue(BoardBridge.deck(replace(edited, scene.tile(entrance)), tile).natural());
    }

    @Test
    void naturalMouthsMeetTheScallopedBankAcrossThePassageAtEveryLod() {
        var scene = scene(BoardScene.Surface.GRASS, false, true);
        var tile = scene.tile(CENTER);
        var center = BoardGeometry.center(CENTER, 3);
        for (var lod : TerrainLod.values()) {
            var surfaces = new HashMap<Coords, BoardSurface>();
            var bridge = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), lod, surfaces);
            for (int d : new int[] { 0, 3 }) {
                var bank = surfaces.get(CENTER.translated(d));
                var direction = BoardGeometry.center(bank.tile.coords(), 3).sub(center);
                var across = new Vector3(-direction.y, direction.x, 0).nor();
                var ground = bank.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList();
                for (int side = -4; side <= 4; side++) {
                    for (int step = 0; step <= 80; step++) {
                        var p = new Vector3(center).mulAdd(direction, .4f + step * .005f)
                              .mulAdd(across, BoardRelief.metres(.7f) * side);
                        float floor = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NEGATIVE_INFINITY);
                        var ray = new Ray(new Vector3(p).add(0, 0, 50), new Vector3(0, 0, -1));
                        float deck = ray.origin.z - (float) Math.sqrt(bridge.hit(ray));
                        assertTrue(Math.max(floor, deck) > center.z - BoardRelief.metres(.6f),
                              "Continuous bank contact, without relying on boulders: " + lod + ", direction " + d + ", " + p);
                    }
                }
            }
        }
    }

    @Test
    void bridgeEntrancesRelocateRimAndFieldRocksWithoutRemovingThem() {
        int moved = 0;
        for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.ROCK, BoardScene.Surface.SAND)) {
            for (var kind : List.of(BoardRoad.Kind.NONE, BoardRoad.Kind.PAVED)) {
                for (int x : new int[] { 3, 4 }) {
                    for (int d = 0; d < 6; d++) {
                        moved += checkRocks(family, kind, new Coords(x, 4), d);
                    }
                }
            }
        }
        assertTrue(moved > 0, "The fixture must actually exercise obstructing boulders");
    }

    private static int checkRocks(BoardScene.Surface family, BoardRoad.Kind kind, Coords center, int direction) {
        int moved = 0;
        var scene = scene(family, false, true, center, direction);
        // One approach makes an ordinary bridge; inspect the unchanged opposite bank in both cases.
        int exits = (1 << direction) | (1 << ((direction + 3) % 6));
        if (kind != BoardRoad.Kind.NONE) {
            scene = replace(scene, BoardRoadTest.tile(center.translated(direction), kind, exits, 3, family));
        }
        var tile = scene.tile(center.translated((direction + 3) % 6));
        int crossing = (1 << ((direction + 1) % 6)) | (1 << ((direction + 4) % 6));
        var without = replace(scene, BoardRoadTest.tile(center, BoardRoad.Kind.GRAVEL, crossing, 0, family));
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.MEDIUM)) {
            var before = new BoardSurface(without, tile, lod);
            var after = new BoardSurface(scene, tile, lod);
            var oldRocks = rockFaces(before);
            var rocks = rockFaces(after);
            assertEquals(oldRocks.size(), rocks.size(), "An open bank has room beside the entrance for every rock it moves");
            var approaches = BoardBridge.approaches(scene, tile);
            for (int i = 0; i < rocks.size(); i++) {
                var old = oldRocks.get(i);
                var rock = rocks.get(i);
                assertEquals(old.a().dst2(old.b()), rock.a().dst2(rock.b()), .002f, "Move, do not shrink the boulders");
                if (!old.equals(rock)) { moved++; }
                for (var p : List.of(rock.a(), rock.b(), rock.c())) {
                    assertFalse(approaches.stream().anyMatch(a -> a.obstructs(p, 0, 0)),
                          "The whole rock must clear the entrance: " + family + ", " + kind + ", " + lod
                                + ", " + center + ", exit " + direction + ", " + p);
                }
            }
            assertEquals(before.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList(),
                  after.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList(),
                  "Relocation must not cut or fill the bank");
        }
        return moved;
    }

    private static List<BoardSurface.Face> rockFaces(BoardSurface surface) {
        return surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.OUTCROP
              && surface.relief.shade(f.a()).kind() == BoardRelief.Kind.ROCK).toList();
    }

    @Test
    void everyAuthoredExitHasAnOpenTopAndClosedRockShell() {
        var scene = scene(BoardScene.Surface.ROCK, false, false);
        for (int exits = 0; exits < 64; exits++) {
            var tile = bridge(scene.tile(CENTER), exits);
            var edited = replace(scene, tile);
            var shape = BoardNaturalBridge.build(edited, tile, BoardBridge.deck(edited, tile), TerrainLod.FULL, new HashMap<>());
            assertTrue(shape.facets().size() < 1100, "Bounded geometry even for branching natural spans");
            Map<List<Vector3>, Integer> edges = new HashMap<>();
            for (var face : shape.facets()) {
                var points = List.of(face.a(), face.b(), face.c());
                for (int i = 0; i < 3; i++) { edges.merge(List.of(points.get(i), points.get((i + 1) % 3)), 1, Integer::sum); }
            }
            edges.forEach((edge, count) -> {
                assertEquals(1, count);
                assertEquals(1, edges.getOrDefault(edge.reversed(), 0), "No cracks or reversed faces in the arch shell");
            });
            for (int d = 0; d < 6; d++) {
                var p = BoardGeometry.center(CENTER, 3).lerp(BoardGeometry.center(CENTER.translated(d), 3), .49f);
                float hit = shape.hit(new Ray(p.add(0, 0, 20), new Vector3(0, 0, -1)));
                assertEquals((exits & (1 << d)) != 0, Float.isFinite(hit), "Only authored exits may reach a hex edge");
            }
        }
    }

    static BoardScene scene(BoardScene.Surface family, boolean water, boolean underRoad) {
        return scene(family, water, underRoad, CENTER, 0);
    }

    static BoardScene scene(BoardScene.Surface family, boolean water, boolean underRoad, Coords bridge, int direction) {
        var tiles = new ArrayList<BoardScene.Tile>();
        var center = BoardGeometry.center(bridge, 0);
        var along = BoardGeometry.center(bridge.translated((direction + 1) % 6), 0).sub(center).nor();
        var across = new Vector3(-along.y, along.x, 0);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                var at = new Coords(x, y);
                var relative = BoardGeometry.center(at, 0).sub(center);
                boolean canyon = Math.abs(relative.dot(across)) < BoardGeometry.height() * .55f;
                boolean road = !water && underRoad && Math.abs(relative.dot(across)) < .01f;
                int level = canyon ? 0 : 3;
                var ground = BoardRoadTest.tile(at, road ? BoardRoad.Kind.GRAVEL : BoardRoad.Kind.NONE,
                      road ? (1 << ((direction + 1) % 6)) | (1 << ((direction + 4) % 6)) : 0, level, family);
                if (water && canyon) {
                    ground = new BoardScene.Tile(at, 0, 1, false, 0, family, ground.ground(), null, null, null, null,
                          List.of(), List.of(), BoardLiquid.WATER, null, true, BoardRoad.Kind.NONE);
                }
                tiles.add(at.equals(bridge) ? bridge(ground, (1 << direction) | (1 << ((direction + 3) % 6))) : ground);
            }
        }
        return new BoardScene(0, 9, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile bridge(BoardScene.Tile ground, int exits) {
        var hex = new Hex(ground.elevation());
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, exits));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 3));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 100));
        return new BoardScene.Tile(ground.coords(), ground.elevation(), ground.waterDepth(), ground.frozen(),
              ground.roadExits(), ground.surface(), ground.ground(), null, null, null, null,
              BoardFeatures.capture(hex, ground.coords(), Map.of()), List.of(), ground.liquid(), null, true, ground.road());
    }

    static BoardScene replace(BoardScene scene, BoardScene.Tile tile) {
        var tiles = new ArrayList<>(scene.tiles());
        tiles.set(tile.coords().getX() * scene.height() + tile.coords().getY(), tile);
        return new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }
}
