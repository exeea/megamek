/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardBridgeFootingTest {
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
        Files.createDirectories(target.resolve("textures/sculpt"));
        Files.copy(source.resolve("textures/sculpt/concrete.png"), target.resolve("textures/sculpt/concrete.png"));
        // The reload also rereads the pier kit.
        Files.createDirectories(target.resolve("bridges"));
        Files.copy(source.resolve("bridges/bridge-pier.glb"), target.resolve("bridges/bridge-pier.glb"));
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

    @Test
    void bridgeToBridgeJoinsDoNotGrow() {
        var scene = BoardBridgeMaterialsTest.straight(new Coords(4, 4), 3, 3, BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT);
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

    static final Coords START = new Coords(4, 2);

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

    /** The tile with its bridge's Pillars toggle on. */
    static BoardScene.Tile pillared(BoardScene.Tile t) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.ground(),
              t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), t.bridge(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(),
              Map.of("bridge", HexAppearance.PILLARS));
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
        scene = BoardNaturalBridgeTest.replace(scene,
              BoardRoadTest.tile(at.translated(0), BoardRoad.Kind.PAVED, 9, 3, BoardScene.Surface.GRASS));
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
            scene = BoardNaturalBridgeTest.replace(scene, BoardRoadTest.tile(at.translated(0), kind, 9, 3, BoardScene.Surface.GRASS));
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
