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

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Configuration;
import megamek.common.board.Coords;
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
        var mask = GpuRoads.mask(road, asphalt, false);
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
            var mask = GpuRoads.mask(road, tail, false);
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
                assertTrue(maskAlpha(GpuRoads.mask(road, gravel, false), 0, tip) > 0, "Gravel carries the final transition");
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
