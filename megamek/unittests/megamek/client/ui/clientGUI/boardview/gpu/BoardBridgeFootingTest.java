/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardBridgeFootingTest {
    @Test
    void convertedLegacyBridgePaintStaysAboveTheSlabAndBothBankRoads() throws Exception {
        var board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
              "GrassLands/16x17 Grasslands River CommCenter.board"), false, true);
        Coords at = new Coords(7, 14);
        assertEquals(1, board.getHex(at).terrainLevel(Terrains.BRIDGE_ROAD),
              "The production legacy conversion must infer the connected paved deck road");
        var scene = GpuLegacyImportSmokeTest.scene(board);
        var tile = scene.tile(at);
        assertEquals(BoardRoad.Kind.PAVED, tile.deckRoad());
        var deck = BoardBridge.deck(scene, tile);
        assertEquals(deck.exits(), deck.roadExits());
        var surfaces = new HashMap<Coords, BoardSurface>();
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
        var shape = footing.withDeck(scene, tile, deck, TerrainLod.FULL, surfaces);
        var painted = footing.paint(deck);
        var road = painted.road(deck, at);
        var patches = GpuRoads.deckPatches(tile, deck, road, painted);
        var asphalt = patches.stream().filter(p -> p.texture().equals("roads/asphalt") && p.fade() == null)
              .findFirst().orElseThrow();
        var marks = patches.stream().filter(p -> p.texture().equals("concrete")).findFirst().orElseThrow();
        var coat = GpuRoads.deck(tile, BoardBridge.feature(tile), asphalt, painted, surfaces, shape);
        var paint = GpuRoads.deck(tile, BoardBridge.feature(tile), marks, painted, surfaces, shape);
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(at, tile.elevation() + BoardBridge.feature(tile).elevation());
        for (int direction : new int[] { 2, 5 }) {
            var bank = scene.tile(at.translated(direction));
            assertEquals(BoardRoad.Kind.PAVED, bank.road(), "Both real bank contacts have connected roads");
            var surface = surfaces.computeIfAbsent(bank.coords(), key -> new BoardSurface(scene, bank));
            var bankRoad = BoardRoad.of(scene, bank);
            var bankAsphalt = GpuRoads.patches(bank, bankRoad).stream()
                  .filter(p -> p.texture().equals("roads/asphalt") && p.fade() == null).findFirst().orElseThrow();
            var groundCoat = GpuRoads.drape(bank, surface, bankAsphalt);
            var axis = BoardGeometry.center(bank.coords(), 0).sub(BoardGeometry.center(at, 0)).nor();
            var side = new Vector3(-axis.y, axis.x, 0);
            var gate = new Vector3(center).lerp(BoardGeometry.center(bank.coords(), bank.elevation()), .5f);
            int supported = 0, joined = 0, marked = 0;
            for (float along = 0; along <= footing.lengths().get(direction) * scale; along += .5f * scale) {
                for (float lateral : new float[] { -2, -.25f, 0, .25f, 2 }) {
                    var point = new Vector3(gate).mulAdd(axis, along).mulAdd(side, lateral * scale);
                    float x = (point.x - center.x) / scale, y = (point.y - center.y) / scale;
                    if (!asphalt.shape().contains(x, y)) { continue; }
                    float top = triangleHeight(coat, point), slab = BoardBridge.top(shape, point.x, point.y);
                    if (!Float.isFinite(top) || !Float.isFinite(slab)) { continue; }
                    assertTrue(top > slab + .005f * scale, "The opaque deck coat clears the supporting slab");
                    supported++;
                    float bx = (point.x - BoardGeometry.centerX(bank.coords())) / scale;
                    float by = (point.y - BoardGeometry.centerY(bank.coords())) / scale;
                    float ground = triangleHeight(groundCoat, point);
                    if (bankAsphalt.shape().contains(bx, by) && Float.isFinite(ground)) {
                        assertTrue(top > ground + .005f * scale, "Overlapping bank asphalt cannot fight the deck coat");
                        joined++;
                    }
                    if (marks.shape().contains(x, y)) {
                        assertTrue(triangleHeight(paint, point) > top + .005f * scale,
                              "Road markings stay above the opaque base coat at the landing");
                        marked++;
                    }
                }
            }
            assertTrue(supported > 0 && joined > 0 && marked > 0,
                  "Exercise the slab, real bank-road overlap and markings at each end: " + direction);
        }
    }

    private static float triangleHeight(List<BoardTacticalGeometry.Triangle> triangles, Vector3 point) {
        float height = Float.NEGATIVE_INFINITY;
        for (var triangle : triangles) {
            height = Math.max(height, new BoardSurface.Face(triangle.a(), triangle.b(), triangle.c(),
                  BoardSurface.Finish.TOP).height(point.x, point.y));
        }
        return height;
    }

    @Test
    void reloadingAnEditedGlbChangesTheBankExtension(@TempDir Path directory) throws Exception {
        var scene = BoardNaturalBridgeTest.scene(BoardScene.Surface.GRASS, false, true);
        var tile = scene.tile(BoardNaturalBridgeTest.CENTER);
        var surfaces = new HashMap<Coords, BoardSurface>();
        var before = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
        float length = BoardBridgeFooting.terminalLength();
        var originalData = Configuration.dataDir();
        var source = originalData.toPath().resolve("models/board");
        var target = Files.createDirectories(directory.resolve("models/board"));
        // The concrete material (its Godot 4 Standard resource and maps) with the catalogue of loose materials.
        var concrete = Files.createDirectories(target.resolve("textures/terrain/surfaces"));
        Files.copy(source.resolve("textures/materials.json"), target.resolve("textures/materials.json"));
        try (var files = Files.list(source.resolve("textures/terrain/surfaces"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("concrete(\\.tres|_.*\\.png)")).toList()) {
                Files.copy(file, concrete.resolve(file.getFileName()));
            }
        }
        // The reload also rereads the pier kits, the bridge's and the maglev's.
        Files.createDirectories(target.resolve("bridges"));
        Files.copy(source.resolve("bridges/bridge-pier.glb"), target.resolve("bridges/bridge-pier.glb"));
        Files.copy(source.resolve("bridges/maglev-pier.glb"), target.resolve("bridges/maglev-pier.glb"));
        byte[] bytes = Files.readAllBytes(source.resolve("bridge-terminal.glb"));
        int jsonLength = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        var mapper = new ObjectMapper();
        var document = mapper.readTree(new String(bytes, 20, jsonLength, StandardCharsets.UTF_8));
        for (var node : document.get("nodes")) {
            // glTF Z is the block's longitudinal board axis; both LODs retain the same footprint.
            ((ObjectNode) node).set("scale", mapper.createArrayNode().add(1).add(1).add(1.5));
        }
        byte[] json = mapper.writeValueAsBytes(document);
        int padded = (json.length + 3) & ~3;
        var output = ByteBuffer.allocate(bytes.length - jsonLength + padded).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546c67).putInt(2).putInt(output.capacity()).putInt(padded).putInt(0x4e4f534a).put(json);
        while (output.position() < 20 + padded) { output.put((byte) ' '); }
        output.put(bytes, 20 + jsonLength, bytes.length - 20 - jsonLength);
        Files.write(target.resolve("bridge-terminal.glb"), output.array());
        try {
            Configuration.setDataDir(directory.toFile());
            BoardBridgeFooting.reload();
            assertEquals(length * 1.5f, BoardBridgeFooting.terminalLength(), .001f);
            for (var lod : TerrainLod.values()) {
                var after = BoardBridgeFooting.build(scene, tile, lod, surfaces);
                for (int d : new int[] { 0, 3 }) {
                    assertEquals(before.lengths().get(d) + length * .5f, after.lengths().get(d), .001f,
                          "Actual GLB dimensions, including after reload, determine the placed block and footing");
                }
            }
        } finally {
            Configuration.setDataDir(originalData);
            BoardBridgeFooting.reload();
        }
    }

    @Test
    void everyTerminalReachesSolidBankAtAllOrientationsAndLods() {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            for (int d = 0; d < 6; d++) {
                var scene = BoardNaturalBridgeTest.scene(BoardScene.Surface.GRASS, false, true, at, d);
                int exits = (1 << d) | (1 << ((d + 3) % 6));
                scene = BoardNaturalBridgeTest.replace(scene,
                      BoardRoadTest.tile(at.translated(d), BoardRoad.Kind.PAVED, exits, 3, BoardScene.Surface.GRASS));
                var tile = scene.tile(at);
                for (var lod : TerrainLod.values()) {
                    var surfaces = new HashMap<Coords, BoardSurface>();
                    var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces);
                    for (int direction : new int[] { d, (d + 3) % 6 }) {
                        float reach = footing.lengths().get(direction) * BoardGeometry.hexScale();
                        assertTrue(reach >= BoardRelief.metres(1), "Even level road banks get a real overlap");
                        var center = BoardGeometry.center(at, 3);
                        var along = BoardGeometry.center(at.translated(direction), 3).sub(center);
                        var gate = new Vector3(center).mulAdd(along, .5f);
                        along.nor();
                        var across = new Vector3(-along.y, along.x, 0);
                        var bank = surfaces.get(at.translated(direction));
                        var ground = bank.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList();
                        for (int side : new int[] { -1, 1 }) {
                            float lateral = 9 * BoardGeometry.hexScale() + BoardRelief.metres(.15f);
                            var cap = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(1.38f));
                            float deckHeight = height(footing.shape(), cap);
                            cap.mulAdd(across, side * lateral);
                            float capHeight = height(footing.shape(), cap);
                            assertTrue(capHeight > deckHeight + 2.5f * BoardGeometry.hexScale() + BoardRelief.metres(.04f),
                                  "Both bank ends have separate raised blocks outside the original rails, at every LOD");
                            var toe = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(.05f))
                                  .mulAdd(across, side * lateral);
                            float support = BoardSurface.sampleHeight(ground, toe.x, toe.y, Float.NaN);
                            float toeHeight = height(footing.shape(), toe);
                            assertTrue(Float.isFinite(support) && toeHeight >= support
                                  && toeHeight < support + BoardRelief.metres(.3f), "The widened sloping nose has a grounded toe");
                            assertTrue(bank.relief.clearance(cap.x, cap.y) >= BoardRelief.metres(.3f),
                                  "Terminal blocks start beyond the cliff opening");
                            var passage = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(1.38f))
                                  .mulAdd(across, side * 7.25f * BoardGeometry.hexScale());
                            assertEquals(deckHeight, height(footing.shape(), passage), BoardRelief.metres(.05f),
                                  "The thicker end blocks preserve the carriageway width");
                        }
                        for (int side = -3; side <= 3; side++) {
                            var end = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(.05f))
                                  .mulAdd(across, side * 2 * BoardGeometry.hexScale());
                            float support = BoardSurface.sampleHeight(ground, end.x, end.y, Float.NaN);
                            assertTrue(Float.isFinite(support), "The bank, not a loose rock, supports the far end");
                            var ray = new Ray(new Vector3(end).add(0, 0, 40), new Vector3(0, 0, -1));
                            float deck = ray.origin.z - (float) Math.sqrt(footing.shape().hit(ray));
                            assertEquals(support, deck, BoardRelief.metres(.12f), "Deck meets the bank across its width");
                            for (int step = 1; step < 12; step++) {
                                var p = new Vector3(gate).mulAdd(along, reach * step / 12)
                                      .mulAdd(across, side * 2 * BoardGeometry.hexScale());
                                assertTrue(Float.isFinite(footing.shape().hit(new Ray(p.add(0, 0, 40), new Vector3(0, 0, -1)))),
                                      "No hole between the GLB and its bank extension");
                            }
                        }
                    }
                    int budget = lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 440 : 248;
                    assertTrue(footing.shape().facets().size() <= budget, "Close LODs bevel the caps; distant LODs keep the silhouette");
                }
            }
        }
    }

    @Test
    void aLevelBuiltLandingReachesTheNaturalBankInsteadOfPullingItUnderTheBridge() {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            Coords bridge = new Coords(3, 4), road = new Coords(4, 5);
            BoardScene plain = BoardRiverTest.commCenterRiverScene();
            var hex = new Hex(0, "water:1;bridge:4:36;bridge_cf:120;bridge_elev:1", "");
            var bridgeTile = new BoardScene.Tile(bridge, 0, 1, false, 0, BoardScene.Surface.GRASS,
                  null, null, null, null, null, BoardFeatures.capture(hex, bridge, Map.of()), List.of(),
                  BoardLiquid.WATER, null, true);
            var bare = BoardNaturalBridgeTest.replace(plain, bridgeTile);
            var scene = BoardNaturalBridgeTest.replace(bare, withRoad(bare.tile(road), 36));
            var roadOnly = BoardNaturalBridgeTest.replace(scene, plain.tile(bridge));
            var natural = BoardBridgeTypeTest.typed(scene, HexAppearance.NATURAL_BRIDGE);
            var bank = new BoardSurface(scene, scene.tile(road));
            var bareBank = new BoardSurface(bare, bare.tile(road));
            var ordinaryBank = new BoardSurface(roadOnly, roadOnly.tile(road));
            var naturalBank = new BoardSurface(natural, natural.tile(road));
            var water = new BoardSurface(scene, scene.tile(bridge));
            int edge = Math.floorMod(1 - road.direction(bridge), 6), opposite = (edge + 3) % 6;
            float top = BoardGeometry.groundZ(scene.tile(road));
            for (float z : new float[] { top, top - BoardRelief.metres(.25f), top - BoardRelief.metres(.75f), 0 }) {
                for (float t : new float[] { 1f / 3, .5f, 2f / 3 }) {
                    Vector3 actual = bank.relief.roadPoint(edge, t, z);
                    assertTrue(actual.epsilonEquals(bareBank.relief.roadPoint(edge, t, z), .002f),
                          "The built landing leaves the receding natural bank's profile intact");
                    assertTrue(actual.epsilonEquals(water.relief.roadPoint(opposite, 1 - t, z), .002f),
                          "Both sides retain one shared bank wall without a land/water crack");
                    assertTrue(ordinaryBank.relief.roadPoint(edge, t, z)
                          .epsilonEquals(naturalBank.relief.roadPoint(edge, t, z), .002f),
                          "Ordinary road clearance and natural bridge mouths keep their existing profile");
                }
            }
            assertTrue(bank.relief.roadPoint(edge, .5f, top)
                  .dst(ordinaryBank.relief.roadPoint(edge, .5f, top)) > BoardRelief.metres(2),
                  "The fixture exercises the former projecting grassy landing");
            for (float t : new float[] { 1f / 3, .5f, 2f / 3 }) {
                float aboveRoad = top + BoardRelief.metres(1);
                assertTrue(bank.relief.roadPoint(edge, t, aboveRoad)
                      .epsilonEquals(ordinaryBank.relief.roadPoint(edge, t, aboveRoad), .002f),
                      "Relief above the road retains ordinary clearance around the bridge passage");
            }
            for (int k = 0; k < 6; k++) {
                float[] actual = bank.relief.shoreShift(k), expected = bareBank.relief.shoreShift(k);
                assertEquals(expected[0], actual[0], .002f, "Landing clearance cannot move the shared coast corners");
                assertEquals(expected[1], actual[1], .002f);
            }

            var surfaces = new HashMap<Coords, BoardSurface>();
            surfaces.put(road, bank);
            var footing = BoardBridgeFooting.build(scene, scene.tile(bridge), TerrainLod.FULL, surfaces);
            int direction = bridge.direction(road);
            float reach = footing.lengths().get(direction) * BoardGeometry.hexScale();
            var center = BoardGeometry.center(bridge, 1);
            var along = BoardGeometry.center(road, 1).sub(center);
            var gate = new Vector3(center).mulAdd(along, .5f);
            along.nor();
            var across = new Vector3(-along.y, along.x, 0);
            for (int side : new int[] { -1, 0, 1 }) {
                var toe = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(.05f))
                      .mulAdd(across, side * (9 * BoardGeometry.hexScale() + BoardRelief.metres(.15f)));
                float support = BoardSurface.sampleHeight(bank.foundation(), toe.x, toe.y, Float.NaN);
                assertTrue(Float.isFinite(support), "The bridge's widened terminal reaches the actual natural bank");
                float height = height(footing.shape(), toe);
                assertTrue(height >= support && height < support + BoardRelief.metres(.3f),
                      "The bridge terminal sits on the bank without moving the terrain towards it");
            }
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void oneLevelBankDifferencesSeatTheSlabAndItsTaperedRails() {
        var at = BoardNaturalBridgeTest.CENTER;
        for (int level : new int[] { 2, 4 }) {
            var scene = BoardNaturalBridgeTest.scene(BoardScene.Surface.GRASS, false, true);
            scene = BoardNaturalBridgeTest.replace(scene,
                  BoardRoadTest.tile(at.translated(0), BoardRoad.Kind.PAVED, 9, 3, BoardScene.Surface.GRASS));
            scene = BoardNaturalBridgeTest.replace(scene,
                  BoardRoadTest.tile(at.translated(3), BoardRoad.Kind.NONE, 0, level, BoardScene.Surface.GRASS));
            var surfaces = new HashMap<Coords, BoardSurface>();
            var footing = BoardBridgeFooting.build(scene, scene.tile(at), TerrainLod.FULL, surfaces);
            var bank = surfaces.get(at.translated(3));
            var ground = bank.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList();
            float scale = BoardGeometry.hexScale();
            var end = BoardGeometry.center(at, 3).lerp(BoardGeometry.center(at.translated(3), 3), .5f)
                  .add(0, -footing.lengths().get(3) * scale + BoardRelief.metres(.01f), 0);
            for (float lateral : new float[] { 0, -8.25f, 8.25f }) {
                var p = new Vector3(end).add(lateral * scale, 0, 0);
                float support = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN);
                var ray = new Ray(new Vector3(p).add(0, 0, BoardGeometry.level() * 3), new Vector3(0, 0, -1));
                float height = ray.origin.z - (float) Math.sqrt(footing.shape().hit(ray));
                assertEquals(support + .04f * scale + (lateral == 0 ? 0 : BoardRelief.metres(.08f)), height,
                      BoardRelief.metres(.12f), "The low block toes seat on the supported slab on either grade");
            }
        }
    }

    /** The scene with the deck at {@code at} carrying a road of {@code kind} over all its exits. */
    private static BoardScene paved(BoardScene scene, Coords at, BoardRoad.Kind kind) {
        var tile = scene.tile(at);
        return BoardNaturalBridgeTest.replace(scene, BoardBridgeMaterialsTest.withDeckRoad(tile, kind,
              BoardBridge.feature(tile).bridgeExits()));
    }

    @Test
    void bridgeToBridgeJoinsDoNotGrow() {
        var scene = BoardBridgeMaterialsTest.straight(new Coords(4, 4), 3, 3, BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT,
              BoardRoad.Kind.PAVED);
        var middle = scene.tile(new Coords(4, 5));
        var footing = BoardBridgeFooting.build(scene, middle, TerrainLod.FULL, new HashMap<>());
        assertTrue(footing.shape().facets().isEmpty());
        assertTrue(footing.lengths().stream().allMatch(length -> length == 0));
    }

    @Test
    void piersStandOnlyUnderDeckJointsAndBothHexesBuildTheirOwnHalf() {
        // A paved span of three over water, toggled: two joints; the middle hex holds a half of each, an end hex one.
        var scene = span(3, true, 0, Set.of(0, 1, 2));
        var halves = new HashMap<Coords, List<BoardBridge.Facet>>();
        for (int i = 0; i < 3; i++) {
            Coords at = START.translated(3, i);
            halves.put(at, piers(scene, at));
            for (var facet : halves.get(at)) {
                for (var p : List.of(facet.a(), facet.b(), facet.c())) {
                    for (var tile : scene.tiles()) {
                        assertTrue(Vector3.dst(p.x, p.y, 0, BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()), 0)
                              >= 29.5f * BoardGeometry.hexScale() - .01f, "No pier near a hex centre, where a unit stands");
                    }
                    var owner = BoardGeometry.tile(scene, p.x - (p.x - BoardGeometry.centerX(at)) * .001f,
                          p.y - (p.y - BoardGeometry.centerY(at)) * .001f);
                    assertEquals(at, owner.coords(), "Each hex builds only the half on its own side");
                }
            }
        }
        assertEquals(List.of(1, 2, 1), List.of(joints(halves.get(START), START),
              joints(halves.get(START.translated(3)), START.translated(3)),
              joints(halves.get(START.translated(3, 2)), START.translated(3, 2))));
        for (int i = 0; i < 2; i++) {
            Coords near = START.translated(3, i), far = near.translated(3);
            var edge = BoardGeometry.center(near, 0).lerp(BoardGeometry.center(far, 0), .5f);
            var toward = BoardGeometry.center(far, 0).sub(BoardGeometry.center(near, 0)).nor();
            var mine = vertices(halves.get(near), edge);
            var theirs = vertices(halves.get(far), edge);
            assertFalse(mine.isEmpty());
            var bounds = new com.badlogic.gdx.math.collision.BoundingBox().inf();
            for (var p : mine) {
                bounds.ext(p);
                // The neighbour's half is this half's mirror image across the shared edge.
                var mirror = new Vector3(p).mulAdd(toward, -2 * new Vector3(p).sub(edge).dot(toward));
                assertTrue(theirs.stream().anyMatch(q -> q.dst(mirror.x, mirror.y, p.z) < .01f), "Mirrored: " + p);
            }
            theirs.forEach(bounds::ext);
            assertEquals(edge.x, bounds.getCenterX(), .01f, "The pier is centred on the joint");
            assertEquals(edge.y, bounds.getCenterY(), .01f);
            // Closed on the joint: from every side, as the side view draws this hex alone, the half shows its outside.
            var inside = new Vector3(edge).mulAdd(toward, -.5f * BoardGeometry.hexScale());
            inside.z = bounds.getCenterZ();
            for (int angle = 0; angle < 360; angle += 30) {
                var look = new Vector3(com.badlogic.gdx.math.MathUtils.cosDeg(angle), com.badlogic.gdx.math.MathUtils.sinDeg(angle), 0);
                var ray = new Ray(new Vector3(inside).mulAdd(look, -200), look);
                BoardBridge.Facet first = null;
                float nearest = Float.POSITIVE_INFINITY;
                var hit = new Vector3();
                for (var facet : halves.get(near)) {
                    if (com.badlogic.gdx.math.Intersector.intersectRayTriangle(ray, facet.a(), facet.b(), facet.c(), hit)
                          && hit.dst2(ray.origin) < nearest) {
                        nearest = hit.dst2(ray.origin);
                        first = facet;
                    }
                }
                assertTrue(first != null && first.normal().dot(look) < 0, near + " looking at " + angle + " degrees sees its outside");
            }
        }

        // Either hex's toggle carries the joint; untoggled, one-hex and ground-level spans and a road beneath have none.
        var either = span(2, true, 0, Set.of(1));
        assertEquals(1, joints(piers(either, START), START));
        assertEquals(1, joints(piers(either, START.translated(3)), START.translated(3)));
        assertTrue(piers(span(3, true, 0, Set.of()), START.translated(3)).isEmpty(), "Off");
        assertTrue(piers(span(1, true, 0, Set.of(0)), START).isEmpty(), "A one-hex bridge rests on its landings");
        assertTrue(piers(span(3, false, 0, Set.of(0, 1, 2)), START.translated(3)).isEmpty(), "No room under a ground-level deck");
        var raised = span(3, false, 2, Set.of(0, 1, 2));
        assertEquals(2, joints(piers(raised, START.translated(3)), START.translated(3)), "Two levels above dry ground");
        var road = BoardNaturalBridgeTest.replace(raised, withRoad(raised.tile(START.translated(3)), 9));
        assertTrue(piers(road, START.translated(3)).isEmpty() && piers(road, START).isEmpty(), "The road beneath keeps its lane");
        var across = BoardNaturalBridgeTest.replace(raised, withRoad(raised.tile(START.translated(3)), 18));
        assertEquals(2, joints(piers(across, START.translated(3)), START.translated(3)), "A road crossing beneath, not along");
    }

    @Test
    void aJunctionHasAHalfPierOnEachConnectedEdgeAndItsCentreStaysOpen() {
        Coords centre = new Coords(4, 4);
        Map<Coords, BoardScene.Tile> tiles = new HashMap<>();
        tiles.put(centre, pillared(deckTile(centre, 1 | 4 | 16, true, 0)));
        for (int d : new int[] { 0, 2, 4 }) {
            Coords at = centre.translated(d);
            tiles.put(at, pillared(deckTile(at, 1 << (d + 3) % 6, true, 0)));
        }
        var scene = BoardSurfaceBlendTest.scene(at -> tiles.getOrDefault(at,
              BoardRoadTest.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
        assertEquals(3, joints(piers(scene, centre), centre));
        for (int d : new int[] { 0, 2, 4 }) { assertEquals(1, joints(piers(scene, centre.translated(d)), centre.translated(d))); }
    }

    /**
     * A maglev's Pillars: the bridge piers' placement with the slim kit, one pier on each joint between consecutive
     * route hexes (never near a hex centre), each hex building its own half, from below the floor up to the beam,
     * whose underside the cap's shoulders hold, at both level heights.
     */
    @Test
    void maglevPiersStandOnTheJointsOfAToggledRouteUpToItsBeamAtBothLevelHeights() {
        for (int levelHeight : new int[] { BoardGeometry.DEFAULTS.levelHeight(), 30 }) {
            BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, levelHeight, .8f));
            try {
                var scene = line(RailRoute.Kind.MAGLEV, new double[] { 2, 2, 2, 2 }, Set.of(0, 1, 2, 3), Set.of());
                List<Integer> counts = new java.util.ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Coords at = START.translated(3, i);
                    var half = maglevPiers(scene, at);
                    counts.add(joints(half, at));
                    for (var facet : half) {
                        for (var p : List.of(facet.a(), facet.b(), facet.c())) {
                            for (var tile : scene.tiles()) {
                                assertTrue(Vector3.dst(p.x, p.y, 0, BoardGeometry.centerX(tile.coords()),
                                      BoardGeometry.centerY(tile.coords()), 0) >= 29.5f, "No pier near a hex centre");
                            }
                            var owner = BoardGeometry.tile(scene, p.x - (p.x - BoardGeometry.centerX(at)) * .001f,
                                  p.y - (p.y - BoardGeometry.centerY(at)) * .001f);
                            assertEquals(at, owner.coords(), "Each hex builds only the half on its own side");
                        }
                    }
                }
                assertEquals(List.of(1, 2, 2, 1), counts, levelHeight + ": one pier on each joint, none at the open ends");
                for (int i = 0; i < 3; i++) {
                    Coords near = START.translated(3, i), far = near.translated(3);
                    var edge = BoardGeometry.center(near, 0).lerp(BoardGeometry.center(far, 0), .5f);
                    var bounds = new com.badlogic.gdx.math.collision.BoundingBox().inf();
                    vertices(maglevPiers(scene, near), edge).forEach(bounds::ext);
                    vertices(maglevPiers(scene, far), edge).forEach(bounds::ext);
                    assertEquals(2 * levelHeight + .75f, bounds.max.z, .01f, levelHeight + ": the cap holds the beam's underside");
                    assertEquals(-4, bounds.min.z, .05f, levelHeight + ": the footing is buried in the ground");
                    assertEquals(edge.x, bounds.getCenterX(), .01f, "Centred on the joint");
                    assertEquals(edge.y, bounds.getCenterY(), .01f);
                    assertTrue(bounds.getWidth() <= 7.01f && bounds.getHeight() <= 7.01f, "The slim maglev kit: " + bounds);
                }
            } finally {
                BoardGeometry.tune(BoardGeometry.DEFAULTS);
            }
        }
    }

    /**
     * Where a maglev pier stands: on a climbing beam its cap follows the beam's grade and meets the neighbour's half on
     * the joint; two maglevs crossing at two levels each stand on their own joints; either marker's toggle carries a
     * joint; over water the footing sinks below the bed. None without the toggle, under a beam less than a level up, where
     * a road or ground rail runs beneath along the route or a bridge deck crosses beneath, and never under a rail.
     */
    @Test
    void maglevPiersFollowTheBeamAndKeepClearOfWhatRunsBeneath() {
        var climbing = line(RailRoute.Kind.MAGLEV, new double[] { 1, 2, 3, 4 }, Set.of(0, 1, 2, 3), Set.of());
        for (int i = 0; i < 3; i++) {
            Coords near = START.translated(3, i), far = near.translated(3);
            var from = BoardRail.joint(climbing, climbing.tile(near), BoardRail.route(climbing.tile(near)), 3);
            var back = BoardRail.joint(climbing, climbing.tile(far), BoardRail.route(climbing.tile(far)), 0);
            assertEquals(from.underside(), back.underside(), .001f, "The beam's underside on the joint, the same from both hexes");
            assertEquals(from.grade(), -back.grade(), .0001f);
            assertTrue(from.grade() > 0, "Climbing towards the neighbour");
            var edge = BoardGeometry.center(near, 0).lerp(BoardGeometry.center(far, 0), .5f);
            var toward = BoardGeometry.center(far, 0).sub(BoardGeometry.center(near, 0)).nor();
            for (Coords at : List.of(near, far)) {
                float top = (float) vertices(maglevPiers(climbing, at), edge).stream()
                      .filter(p -> Math.abs(new Vector3(p).sub(edge).dot(toward)) < .01f).mapToDouble(p -> p.z).max().orElseThrow();
                assertEquals(from.underside() + .75f, top, .02f, at + ": the cap on the joint holds the beam");
            }
        }

        // A ground rail crossing beneath the deck stays on its own layer and does not add elevated joints.
        Coords middle = START.translated(3);
        Map<Coords, BoardScene.Tile> tiles = new HashMap<>();
        tiles.put(middle, routeTile(middle, false, RailRoute.Kind.MAGLEV, 4, 18, true, 9));
        for (int d : new int[] { 1, 4 }) { tiles.put(middle.translated(d), routeTile(middle.translated(d), false,
              RailRoute.Kind.MAGLEV, 4, RailRoute.side((d + 3) % 6), false, 0)); }
        var crossing = BoardSurfaceBlendTest.scene(at -> tiles.getOrDefault(at,
              BoardRoadTest.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
        assertEquals(2, joints(maglevPiers(crossing, middle), middle));
        for (int d = 0; d < 6; d++) {
            var edge = BoardGeometry.center(middle, 0).lerp(BoardGeometry.center(middle.translated(d), 0), .5f);
            float top = (float) vertices(maglevPiers(crossing, middle.translated(d)), edge).stream()
                  .mapToDouble(p -> p.z).max().orElse(Double.NaN);
            assertEquals(d % 3 != 1 ? Float.NaN : 4 * BoardGeometry.level() + .75f, top, .01f,
                  "The neighbour's half at side " + d + " (either bridge's toggle carries the joint)");
        }

        // Over water the footing sinks out of sight, its lip (0.6) and half a px below the lowest bed under it.
        Coords second = START.translated(3);
        var dry = line(RailRoute.Kind.MAGLEV, new double[] { 2, 2, 2 }, Set.of(0, 1, 2), Set.of());
        var wet = line(RailRoute.Kind.MAGLEV, new double[] { 2, 2, 2 }, Set.of(0, 1, 2), Set.of(1));
        assertTrue(lowest(maglevPiers(wet, second)) <= lowest(maglevPiers(dry, second)) - 1.1f + .001f,
              lowest(maglevPiers(wet, second)) + " under water, " + lowest(maglevPiers(dry, second)) + " on dry ground");

        assertEquals(0, joints(maglevPiers(line(RailRoute.Kind.MAGLEV, new double[] { 2, 2, 2 }, Set.of(), Set.of()), second),
              second), "Off");
        assertEquals(0, joints(maglevPiers(line(RailRoute.Kind.MAGLEV, new double[] { 0, 0, 0 }, Set.of(0, 1, 2), Set.of()),
              second), second), "A ground maglev has no bridge piers");
        var rails = line(RailRoute.Kind.RAIL, new double[] { 0, 0, 0 }, Set.of(), Set.of());
        assertEquals(0, joints(maglevPiers(rails, second), second), "A rail lies on the ground");
        var along = BoardNaturalBridgeTest.replace(dry, withRoad(dry.tile(second), 9));
        assertEquals(0, joints(maglevPiers(along, second), second), "A road beneath along the route keeps its lane");
        var across = BoardNaturalBridgeTest.replace(dry, withRoad(dry.tile(second), 18));
        assertEquals(2, joints(maglevPiers(across, second), second), "A road crossing beneath, not along");
        var railBeneath = BoardNaturalBridgeTest.replace(dry,
              routeTile(second, false, RailRoute.Kind.MAGLEV, 2, 9, true, 9));
        assertEquals(0, joints(maglevPiers(railBeneath, second), second), "A ground rail beneath along the route keeps its track");
    }

    /**
     * Batch-10 answer 8: a maglev pier never pierces a structure: no pier stands on a joint where either hex holds a
     * building or an industrial plant (nor a fuel tank, which is a structure model too); the other joints keep theirs.
     */
    @Test
    void noMaglevPierStandsOnAJointBesideAStructure() {
        var dry = line(RailRoute.Kind.MAGLEV, new double[] { 3, 3, 3, 3 }, Set.of(0, 1, 2, 3), Set.of());
        Coords first = START, second = START.translated(3), third = second.translated(3), fourth = third.translated(3);
        assertEquals(List.of(1, 2, 2, 1), List.of(joints(maglevPiers(dry, first), first), joints(maglevPiers(dry, second), second),
              joints(maglevPiers(dry, third), third), joints(maglevPiers(dry, fourth), fourth)));
        for (var kind : List.of(BoardScene.FeatureKind.BUILDING, BoardScene.FeatureKind.INDUSTRIAL)) {
            var structure = new BoardScene.Feature("buildings/saxarba/building_light/building_light_00", 0, 0, 0, 1, 2, 0, kind);
            var built = BoardNaturalBridgeTest.replace(dry, withFeatures(dry.tile(second), List.of(structure)));
            assertEquals(0, joints(maglevPiers(built, second), second), kind + ": none in the structure's hex");
            assertEquals(0, joints(maglevPiers(built, first), first), kind + ": nor its neighbour's half of that joint");
            assertEquals(1, joints(maglevPiers(built, third), third), kind + ": the joint away from it keeps its pier");
            assertEquals(1, joints(maglevPiers(built, fourth), fourth));
        }
    }

    static final Coords START = new Coords(4, 2);

    private static BoardScene.Feature feature(BoardDecoration marker) {
        return new BoardScene.Feature(marker.asset(), 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, marker);
    }

    /** Native terrain owns the route and, for a raised maglev, the implicit bridge and its Pillars choice. */
    static BoardScene.Tile routeTile(Coords at, boolean water, RailRoute.Kind kind, int level, int sides,
          boolean pillars, int groundRail) {
        boolean deck = kind == RailRoute.Kind.MAGLEV && level > 0;
        Hex hex = new Hex(0, (water ? "water:1;" : "") + (deck
              ? "bridge:1:" + sides + ";bridge_elev:" + level + ";bridge_cf:40;bridge_rail:2:" + sides
                    + (groundRail == 0 ? "" : ";rail:1:" + groundRail)
              : "rail:" + (kind == RailRoute.Kind.MAGLEV ? 2 : 1) + ":" + sides), "");
        if (deck) { hex.setAppearance(Map.of("bridge", pillars ? HexAppearance.IMPLICIT_PILLARS : HexAppearance.IMPLICIT_BRIDGE)); }
        var ground = BoardSurfaceBlendTest.tile(at, BoardScene.Surface.GRASS, 0, water ? 1 : -1, 0).ground();
        var features = new java.util.ArrayList<BoardScene.Feature>();
        RailRoute.derived(hex, "terrain").forEach(marker -> features.add(feature(marker)));
        if (deck) { features.add(new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, level, BoardScene.FeatureKind.PROP, sides)); }
        var t = new BoardScene.Tile(at, 0, water ? 1 : -1, false, 0, BoardScene.Surface.GRASS, ground, null, null, null, null,
              features, List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE);
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.ground(),
              t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), t.bridge(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), hex.getAppearance(),
              t.cover(), t.barren(), BoardRoad.Kind.NONE, 0, false, RailRoute.groundSides(hex));
    }

    /**
     * A N-S route of {@code kind} from {@link #START}, a hex per entry of {@code levels} (a maglev's fixed level; a rail
     * lies on the ground), Pillars on the markers at the indices in {@code toggled}, water:1 beneath those in {@code wet}.
     */
    static BoardScene line(RailRoute.Kind kind, double[] levels, Set<Integer> toggled, Set<Integer> wet) {
        Map<Coords, BoardScene.Tile> tiles = new HashMap<>();
        for (int i = 0; i < levels.length; i++) {
            Coords at = START.translated(3, i);
            int sides = (i > 0 ? RailRoute.side(0) : 0) | (i < levels.length - 1 ? RailRoute.side(3) : 0);
            tiles.put(at, routeTile(at, wet.contains(i), kind, (int) levels[i], sides, toggled.contains(i), 0));
        }
        return BoardSurfaceBlendTest.scene(at -> tiles.getOrDefault(at,
              BoardRoadTest.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
    }

    private static float lowest(List<BoardBridge.Facet> facets) {
        return (float) facets.stream().flatMap(f -> java.util.stream.Stream.of(f.a(), f.b(), f.c())).mapToDouble(p -> p.z)
              .min().orElseThrow();
    }

    /** The hex's maglev pier facets, as the terrain builds them for each of its route markers. */
    static List<BoardBridge.Facet> maglevPiers(BoardScene scene, Coords at) {
        var tile = scene.tile(at);
        return BoardBridge.guideway(tile) ? piers(scene, at) : List.of();
    }

    /**
     * A N-S span of {@code length} hexes from {@link #START}, its deck {@code deck} levels above water:1 or dry ground,
     * between paved roads (a built deck); the hexes at the indices in {@code toggled} have the Pillars toggle on.
     */
    static BoardScene span(int length, boolean water, int deck, Set<Integer> toggled) {
        Map<Coords, BoardScene.Tile> route = new HashMap<>();
        for (int i = 0; i < length; i++) {
            Coords at = START.translated(3, i);
            var tile = deckTile(at, 9, water, deck);
            route.put(at, toggled.contains(i) ? pillared(tile) : tile);
        }
        Coords entrance = START.translated(0), exit = START.translated(3, length);
        route.put(entrance, BoardRoadTest.tile(entrance, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
        route.put(exit, BoardRoadTest.tile(exit, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
        return BoardSurfaceBlendTest.scene(at -> route.getOrDefault(at,
              BoardRoadTest.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
    }

    private static BoardScene.Tile deckTile(Coords at, int exits, boolean water, int deck) {
        Hex bridge = new Hex(0);
        bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, deck));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        var ground = BoardSurfaceBlendTest.tile(at, BoardScene.Surface.GRASS, 0, water ? 1 : -1, 0).ground();
        return new BoardScene.Tile(at, 0, water ? 1 : -1, false, 0, BoardScene.Surface.GRASS, ground, null, null, null, null,
              BoardFeatures.capture(bridge, at, Map.of()), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true,
              BoardRoad.Kind.NONE);
    }

    @Test
    void noPierStandsInAChasm() {
        var scene = span(3, false, 2, Set.of(0, 1, 2));
        Coords middle = START.translated(3);
        assertTrue(BoardBridgeFooting.pier(scene, scene.tile(START), 3), "A toggled span two levels up has piers");
        Map<Coords, BoardScene.Tile> tiles = new HashMap<>();
        for (var tile : scene.tiles()) { tiles.put(tile.coords(), tile.coords().equals(middle) ? chasm(tile) : tile); }
        var pit = BoardSurfaceBlendTest.scene(tiles::get);
        assertFalse(BoardBridgeFooting.pier(pit, pit.tile(START), 3), "Its deck crosses the chasm on no pier");
        assertFalse(BoardBridgeFooting.pier(pit, pit.tile(middle), 3));
    }

    /** The tile as an ultra sublevel, a chasm. */
    private static BoardScene.Tile chasm(BoardScene.Tile t) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.ground(),
              t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), t.bridge(), true, t.tilesetDecals(), t.tilesetScenery(), t.appearance());
    }

    /** The tile with its bridge's Pillars toggle on. */
    static BoardScene.Tile pillared(BoardScene.Tile t) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.ground(),
              t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), t.bridge(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(),
              Map.of("bridge", HexAppearance.PILLARS));
    }

    /** The tile with these features added. */
    private static BoardScene.Tile withFeatures(BoardScene.Tile t, List<BoardScene.Feature> added) {
        var features = new java.util.ArrayList<>(t.features());
        features.addAll(added);
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.ground(),
              t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(), List.copyOf(features), t.text(), t.liquid(),
              t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(),
              t.cliffTopExits(), t.bare(), t.groundCover(), t.bridge(), t.ultraSublevel(), t.tilesetDecals(),
              t.tilesetScenery(), t.appearance());
    }

    private static BoardScene.Tile withRoad(BoardScene.Tile t, int exits) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), exits, t.surface(), t.ground(),
              t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), BoardRoad.Kind.PAVED, t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(),
              t.bare(), t.groundCover(), t.bridge(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), t.appearance());
    }

    /** The tile's pier facets, built as the terrain's roads stage builds its bridge shape. */
    static List<BoardBridge.Facet> piers(BoardScene scene, Coords at) {
        var tile = scene.tile(at);
        var surfaces = new HashMap<Coords, BoardSurface>();
        var deck = BoardBridge.deck(scene, tile);
        BoardBridge.Shape shape;
        if (deck.natural()) {
            shape = BoardNaturalBridge.build(scene, tile, deck, TerrainLod.FULL, surfaces);
        } else {
            var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
            shape = deck.sloped() ? BoardBridgeSlope.build(tile, deck, footing) : footing.shape();
        }
        return BoardBridgeFooting.withPiers(scene, tile, deck, shape, TerrainLod.FULL, surfaces).facets().stream()
              .filter(facet -> facet.part() == BoardBridge.Part.PIER).toList();
    }

    /** How many of the hex's edges have pier facets beside their midpoint. */
    private static int joints(List<BoardBridge.Facet> piers, Coords at) {
        int count = 0;
        for (int d = 0; d < 6; d++) {
            var edge = BoardGeometry.center(at, 0).lerp(BoardGeometry.center(at.translated(d), 0), .5f);
            if (!vertices(piers, edge).isEmpty()) { count++; }
        }
        return count;
    }

    /** The pier vertices within a pier's reach of a joint. */
    private static List<Vector3> vertices(List<BoardBridge.Facet> piers, Vector3 edge) {
        return piers.stream().flatMap(f -> java.util.stream.Stream.of(f.a(), f.b(), f.c()))
              .filter(p -> Vector3.dst(p.x, p.y, 0, edge.x, edge.y, 0) < 12 * BoardGeometry.hexScale()).toList();
    }

    @Test
    void asphaltPaintEndsBeforeTheGroundedApronAndTheDeckRemainsSolid() {
        var at = BoardNaturalBridgeTest.CENTER;
        var scene = BoardNaturalBridgeTest.scene(BoardScene.Surface.GRASS, false, true);
        scene = BoardBridgeTypeTest.typed(BoardNaturalBridgeTest.replace(scene,
              BoardRoadTest.tile(at.translated(0), BoardRoad.Kind.PAVED, 9, 3, BoardScene.Surface.GRASS)), HexAppearance.BUILT_BRIDGE);
        scene = paved(scene, at, BoardRoad.Kind.PAVED);
        var tile = scene.tile(at);
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, new HashMap<>());
        float reach = footing.lengths().get(3);
        assertTrue(reach > BoardRelief.metres(1) / BoardGeometry.hexScale(), "The fixture has a receding cliff");
        var deck = BoardBridge.deck(scene, tile);
        var road = footing.road(deck, at);
        var patches = GpuRoads.deckPatches(tile, deck, road, footing);
        var asphalt = patches.stream().filter(p -> p.texture().equals("roads/asphalt")).findFirst().orElseThrow();
        var mask = GpuRoads.mask(road, asphalt);
        assertTrue(mask.y() < -BoardGeometry.TILE_HEIGHT / 2 - reach + 1);
        int painted = 0, gaps = 0;
        var marks = patches.stream().filter(p -> p.texture().equals("concrete")).findFirst().orElseThrow();
        float paintEnd = -BoardGeometry.TILE_HEIGHT / 2 - reach
              + BoardRelief.metres(1.8f) / BoardGeometry.hexScale();
        for (float y = -BoardGeometry.TILE_HEIGHT / 2 - .25f; y > -BoardGeometry.TILE_HEIGHT / 2 - reach + .25f; y -= .25f) {
            assertTrue(asphalt.shape().contains(0, y), "Asphalt covers the whole added passage");
            assertEquals(1, GpuRoads.coverage(road, asphalt, 0, y), "No transparency over the span or solid footing");
            if (marks.shape().contains(0, y)) { painted++; } else { gaps++; }
            if (y < paintEnd) { assertFalse(marks.shape().contains(0, y), "Paint stops before the rail taper"); }
        }
        assertTrue(painted > 0 && gaps > 0, "Real dashed paint continues on the extension");
        var center = BoardGeometry.center(at, 2);
        assertFalse(Float.isFinite(footing.shape().hit(new Ray(new Vector3(center).add(-40, 0, 0), Vector3.X))),
              "The lower crossing is still open");
    }

    @Test
    void bareBankMasksBlendThroughGravelToLocalGroundWithoutFadingConnectedRoads() {
        var at = BoardNaturalBridgeTest.CENTER;
        float scale = BoardGeometry.hexScale();
        for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.ALLEY, BoardRoad.Kind.GRAVEL, BoardRoad.Kind.DIRT)) {
            var scene = BoardNaturalBridgeTest.scene(BoardScene.Surface.GRASS, false, true);
            scene = BoardBridgeTypeTest.typed(BoardNaturalBridgeTest.replace(scene,
                  BoardRoadTest.tile(at.translated(0), kind, 9, 3, BoardScene.Surface.GRASS)), HexAppearance.BUILT_BRIDGE);
            scene = paved(scene, at, kind);
            var tile = scene.tile(at);
            var surfaces = new HashMap<Coords, BoardSurface>();
            var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
            assertEquals(8, footing.bareExits(), "The lower road is not mistaken for an approach");
            var deck = BoardBridge.deck(scene, tile);
            var road = footing.road(deck, at);
            var patches = GpuRoads.deckPatches(tile, deck, road, footing);
            String material = GpuRoads.texture(kind);
            var tail = patches.stream().filter(p -> p.texture().equals(material) && p.endFade() > 0
                  && !p.fade().wheels()).findFirst().orElseThrow();
            var mask = GpuRoads.mask(road, tail);
            float start = -BoardGeometry.TILE_HEIGHT / 2 - footing.lengths().get(3);
            float end = start - BoardRelief.metres(7) / scale;
            assertTrue(maskAlpha(mask, 0, start - .05f) > .95f, "The landing starts with the deck's material");
            int partial = 0;
            for (float y = start - .5f; y > end; y -= .25f) {
                float alpha = maskAlpha(mask, 0, y);
                if (alpha > .02f && alpha < .98f) { partial++; }
            }
            assertTrue(partial > 2, "The irregular apron exposes ground over a band, rather than one hard edge: " + kind);
            assertEquals(0, maskAlpha(mask, 0, end - .1f), "No hard edge at the end of the apron");
            if (kind == BoardRoad.Kind.PAVED || kind == BoardRoad.Kind.ALLEY) {
                var gravel = patches.stream().filter(p -> p.texture().equals("roads/gravel")).findFirst().orElseThrow();
                float tip = start - BoardRelief.metres(5) / scale;
                assertEquals(0, maskAlpha(mask, 0, tip), "Asphalt ends before the loose gravel");
                assertTrue(maskAlpha(GpuRoads.mask(road, gravel), 0, tip) > 0, "Gravel carries the final transition");
            }
            assertFalse(GpuRoads.drape(tile, surfaces.get(at.translated(3)), tail).isEmpty(),
                  "Existing bank triangles carry the apron");
            assertTrue(GpuRoads.drape(tile, surfaces.get(at.translated(3)), tail).stream()
                  .allMatch(t -> t.a().z > BoardGeometry.level() * 2 && t.b().z > BoardGeometry.level() * 2
                        && t.c().z > BoardGeometry.level() * 2), "Nothing is painted down onto the underpass");
            float north = BoardGeometry.TILE_HEIGHT / 2 + footing.lengths().get(0) - .25f;
            assertTrue(patches.stream().anyMatch(p -> p.texture().equals(material) && p.shape().contains(0, north)
                  && GpuRoads.coverage(road, p, 0, north) == 1), "Attached roads retain full material coverage");
        }
    }

    private static float height(BoardBridge.Shape shape, Vector3 point) {
        var ray = new Ray(new Vector3(point).add(0, 0, 40), new Vector3(0, 0, -1));
        return ray.origin.z - (float) Math.sqrt(shape.hit(ray));
    }

    private static float maskAlpha(GpuRoads.MaskData mask, float x, float y) {
        int px = (int) Math.floor((x - mask.x()) / mask.width() * mask.pixels().width());
        int py = (int) Math.floor((y - mask.y()) / mask.height() * mask.pixels().height());
        if (px < 0 || py < 0 || px >= mask.pixels().width() || py >= mask.pixels().height()) { return 0; }
        return (mask.pixels().rgba(py * mask.pixels().width() + px) & 255) / 255f;
    }
}
