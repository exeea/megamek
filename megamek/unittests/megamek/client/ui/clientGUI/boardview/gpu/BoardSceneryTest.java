/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.JsonReader;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardSceneryTest {
    @Test
    void compositionsReuseMeshesWithoutChangingAuthoredTransformsOrTreeDensity() {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        var catalog = new JsonReader().parse(new FileHandle(root.resolve("scenery/layouts.json").toFile()));
        int trees = 0;
        int picnicTables = 0;
        var coords = new Coords(7, 9);
        for (var entry : catalog) {
            var layout = BoardSceneryLayouts.layout(entry.name);
            assertEquals(entry.size, layout.components().size());
            // The conversion places the meshes; a car park's lane decal is paint, a route marker's track generated.
            var meshes = layout.components().stream().filter(c -> !c.decal() && !c.route()).toList();
            var natural = layout.chosen(coords, BoardTreeDistribution.PARK, true).stream().filter(c -> !c.decal() && !c.route()).toList();
            var planted = layout.chosen(coords, BoardTreeDistribution.PARK, false).stream().filter(c -> !c.decal() && !c.route()).toList();
            assertEquals(natural, layout.chosen(coords, BoardTreeDistribution.PARK, true).stream().filter(c -> !c.decal() && !c.route()).toList());
            for (int i = 0; i < meshes.size(); i++) {
                var authored = meshes.get(i);
                var component = natural.get(i);
                assertFalse(component.asset().matches("scenery/.*-[0-9a-f]{8}"),
                      "Shared asset identities must not depend on parameter hashes");
                assertEquals(authored.x(), component.x());
                assertEquals(authored.y(), component.y());
                assertEquals(authored.z(), component.z());
                assertEquals(authored.scale(), component.scale());
                assertEquals(authored.asset(), planted.get(i).asset());
                assertEquals(authored.rotation(), planted.get(i).rotation());
                assertTrue(Files.isRegularFile(root.resolve(component.asset() + ".glb")));
                if (authored.tree()) {
                    trees++;
                } else {
                    assertEquals(authored, component, "Only the park trees draw their species and heading");
                    // The remaining furniture meshes cannot carry duplicate copies of the shared trees.
                    var data = RigidGlb.loadLods(new FileHandle(root.resolve(component.asset() + ".glb").toFile()), root).getFirst();
                    for (var material : data.materials) { assertFalse(material.id.endsWith("-cutout"), entry.name); }
                }
                if (authored.asset().equals("scenery/parks/picnic-table")) { picnicTables++; }
            }
            if (layout.components().stream().noneMatch(c -> c.asset().equals(entry.name))) {
                assertFalse(Files.exists(root.resolve(entry.name + ".glb")), "Compositions need no duplicate mesh");
            }
        }
        // Batch 10: heli pads, elevator plates, cleared-rubble gravel, legacy rails and crossings, train stamps.
        assertEquals(381, catalog.size);
        assertEquals(422, trees);
        assertTrue(picnicTables > 10, "The same picnic-table mesh is reused by picnic and courtyard layouts");
    }

    @Test
    void tacticalSceneryKeepsOriginalArtworkSeparateFromTheGroundOnRoofsAndUnderwater() {
        for (String support : List.of("pavement:1", "water:2",
              "pavement:1;building:2;bldg_elev:3;bldg_cf:90")) {
            var bare = capture(new Hex(0, support, ""));
            // Untranslated paint and terrain-owned art keep their sprites; art the conversion took has none.
            for (String decoration : List.of("fluff:14:0", "geyser:1")) {
                var hex = new Hex(0, support + ";" + decoration, "");
                var image = capture(hex);
                assertTrue(alpha(image.tilesetDecals()) + alpha(image.tilesetScenery()) > 0,
                      "Tactical View must retain " + decoration);
                assertEquals(BoardScene.Pixels.capture(bare.tileset(), null), BoardScene.Pixels.capture(image.tileset(), null),
                      "The ground must not retain a duplicate of " + decoration);
                var tile = tile(hex, image);
                assertEquals(tile.tilesetDecals(), tile.withTactical(null).tilesetDecals(),
                      "Tactical marker refreshes must retain painted artwork");
                assertEquals(tile.tilesetScenery(), tile.withTactical(null).tilesetScenery(),
                      "Tactical marker refreshes must retain object artwork");
            }
            var skylight = drawn(support + ";fluff:6:0");
            assertTrue(skylight.hex().containsTerrain(Terrains.FLUFF), "The token stays for the 2D board");
            assertNull(skylight.tile().tilesetScenery(), "The Tactical View shows the skylight object, not its sprite");
            assertNull(skylight.tile().tilesetDecals());
            assertEquals(BoardScene.Pixels.capture(bare.tileset(), null), skylight.tile().tileset(),
                  "The Tactical View's base tile has no copy of it either");
        }
    }

    @Test
    void rubbleUsesTheDestroyedStructureTypeAndClearedPathsStayCosmetic() {
        var families = List.of("light", "medium", "heavy", "hardened", "wall", "heavy");
        for (int type = 1; type <= 6; type++) {
            var rubble = new Hex(0, "rubble:" + type, "");
            var captured = capture(rubble);
            assertEquals(List.of("scenery/saxarba/misc/rubble_" + families.get(type - 1)), captured.scenery().models());
            assertEquals(type, rubble.terrainLevel(Terrains.RUBBLE));
            assertEquals(0, alpha(captured.decals()), "The modeled pile replaces its painted duplicate");
            if (type > 5) { continue; }
            var cleared = new Hex(0, "ground_fluff:2000;fluff:" + (2000 + type), "");
            var path = capture(cleared);
            // The cleared ground's gravel (a terrain-owned decal row) under the path's debris.
            assertEquals(List.of("scenery/saxarba/cleared_rubble_gravel", "scenery/saxarba/rubble_" + families.get(type - 1)
                  + "_path"), path.scenery().models());
            assertFalse(cleared.containsTerrain(Terrains.RUBBLE), "Cosmetic debris must not restore the obstacle");
            assertTrue(cleared.isClearHex());
        }
        assertEquals(List.of("scenery/saxarba/misc/fortified"), capture(new Hex(0, "fortified:1", "")).scenery().models());
    }

    @Test
    void everyShippedSceneryAssetDecodesThroughTheRuntimeIncludingItsSharedTextures() throws Exception {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        try (var files = Files.walk(root.resolve("scenery"))) {
            var models = files.filter(p -> p.toString().endsWith(".glb")).toList();
            int compositions = 0;
            for (var entry : new JsonReader().parse(new FileHandle(root.resolve("scenery/layouts.json").toFile()))) {
                if (!Files.exists(root.resolve(entry.name + ".glb"))) { compositions++; }
            }
            assertTrue(models.size() + compositions >= 281, "The full model and composition catalog is shipped");
            for (Path file : models) {
                assertFalse(file.getFileName().toString().matches(".*-[0-9a-f]{8}\\.glb"),
                      "No obsolete hashed component stays in the asset pool");
                var data = RigidGlb.loadLods(new FileHandle(file.toFile()), root).getFirst();
                assertFalse(data.meshes.isEmpty(), file.toString());
            }
        }
    }

    @Test
    void nativeRoadBendsAndGroundTransitionsStayNativeBesideScenery() {
        var hex = new Hex(0, "road:1:5;road_fluff:1;ground_fluff:1:3;fluff:6:0", "");
        var image = capture(hex);
        assertEquals(0, image.scenery().cosmeticRoadExits());
        assertEquals(0, alpha(image.decals()));
        assertTrue(tile(hex, image).detailedGround());
    }
    @Test
    void fccwCraneSkylightsAndLedgesKeepPhysicalGroundAndReplaceTheirPaintedObjects() {
        for (String terrain : List.of("fluff:7:0", "fluff:6:0", "fluff:8:0", "fluff:2:3")) {
            var drawn = drawn("pavement:1;" + terrain);
            assertFalse(drawn.hex().getDecorations().isEmpty(), terrain);
            assertTrue(drawn.tile().detailedGround(), terrain);
            assertTrue(drawn.tile().features().stream().anyMatch(f -> f.decoration() != null), terrain);
            assertTrue(drawn.tile().features().stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY), terrain);
            assertNull(drawn.tile().decals(), "A modeled object must not leave its painted twin: " + terrain);
            assertNotNull(drawn.tile().tileset(), "Tactical artwork remains available");
        }
    }

    @Test
    void everySkylightAndLedgeRotationUsesTheSelectedSourceVariant() {
        for (int variant = 0; variant < 6; variant++) {
            for (String family : List.of("6", "8")) {
                var hex = new Hex(0, "fluff:" + family + ":" + variant, "");
                assertEquals(List.of("scenery/fluff/" + (family.equals("6") ? "skylight" : "ledge")
                      + (variant + 1)), TilesetScenery.resolve(hex, false).scenery().models());
            }
        }
    }

    @Test
    void everyExportedLedgeFacesOutwardAndHasAVisibleUpperSurface() {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        // All six legacy ledge orientations are rows on this one mesh.
        var file = root.resolve("scenery/roofs/ledge.glb");
        var data = RigidGlb.loadLods(new FileHandle(file.toFile()), root).getFirst();
        double volume = 0;
        double upperArea = 0;
        double footprintArea = 0;
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) {
                for (int i = 0; i < part.indices.length; i += 3) {
                    var points = new Vector3[3];
                    for (int p = 0; p < 3; p++) {
                        int offset = Short.toUnsignedInt(part.indices[i + p]) * RigidGlb.STRIDE;
                        points[p] = new Vector3(mesh.vertices[offset], mesh.vertices[offset + 1], mesh.vertices[offset + 2]);
                    }
                    var normal = points[1].cpy().sub(points[0]).crs(points[2].cpy().sub(points[0]));
                    volume += points[0].dot(normal) / 6.0;
                    if (points[0].z + points[1].z + points[2].z > .001f) {
                        upperArea += Math.max(0, normal.z) / 2.0;
                    } else {
                        footprintArea += Math.abs(normal.z) / 2.0;
                    }
                }
            }
        }
        // Inward winding previously hid the parapet and left only its narrow trim visible.
        assertTrue(volume > 0, "The closed ledge must enclose positive volume: " + file);
        assertTrue(footprintArea > 0 && upperArea >= footprintArea * .99,
              "The upper faces must cover the body footprint when backface culling is enabled: " + file);
    }

    @Test
    void surfaceSymbolsRemainDecalsWithoutSwitchingOffTheTerrainMaterial() {
        for (String terrain : List.of("fluff:14:0", "fluff:50:1", "fluff:70:1", "fluff:91:1")) {
            var hex = new Hex(0, "pavement:1;" + terrain, "");
            var image = capture(hex);
            assertTrue(image.scenery().models().isEmpty(), terrain);
            assertTrue(alpha(image.decals()) > 0, terrain);
            assertTrue(tile(hex, image).detailedGround(), terrain);
        }
    }

    @Test
    void compositePortArtReplacesVisualTreesButRetainsTheGamesWoodsTerrain() {
        var drawn = drawn("pavement:1;woods:1;fluff:80:1");
        assertEquals(1, drawn.hex().terrainLevel(Terrains.WOODS));
        assertEquals(BoardTreeDistribution.NO_TREES, drawn.hex().getAppearance().get("vegetation").variant());
        assertFalse(drawn.tile().features().stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.TREE));
        assertTrue(drawn.tile().features().stream().anyMatch(f -> f.decoration() != null
              && f.decoration().asset().startsWith("scenery/seaport/")));
        // The Tactical View's base tile follows the same design (b9-decisions T): no woods sprite under the yard, as the
        // old tileset's seaport super replaced it; plain woods keep theirs.
        assertEquals(drawn("pavement:1").tile().tileset(), drawn.tile().tileset());
        assertNotEquals(drawn("pavement:1;woods:1").tile().tileset(), drawn.tile().tileset());
    }

    @Test
    void roadsideTreesAndParkedCarsPreserveTheRoadAndAllSourceVariants() {
        for (String terrain : List.of("road:2:9", "road:1:9;road_fluff:3", "road:1:9;fluff:5:2")) {
            var drawn = drawn(terrain);
            assertFalse(drawn.hex().getDecorations().isEmpty(), terrain);
            assertNull(drawn.tile().decals(), terrain);
            assertEquals(9, drawn.tile().roadExits(), terrain);
            assertTrue(drawn.tile().road() != BoardRoad.Kind.NONE, terrain);
            assertTrue(drawn.tile().detailedGround(), terrain);
        }
    }

    @Test
    void everyParkingLaneIsARealRoadThroughItsBays() {
        int[] exits = { 18, 36, 9, 18, 36, 9, 36, 9 };
        for (int variant = 0; variant < exits.length; variant++) {
            var drawn = drawn("fluff:5:" + variant);
            var tile = drawn.tile();
            assertEquals(exits[variant], drawn.hex().getTerrain(Terrains.ROAD).getExits(), "The lane is rules terrain");
            assertEquals(BoardRoad.Kind.PAVED, tile.road());
            assertEquals(exits[variant], tile.roadExits());
            assertNull(tile.decals());
            assertTrue(BoardRoad.rendered(tile));
            assertTrue(tile.detailedGround());
            // One standard car-park decal per side of the lane with cars, its aisle (along the decal's width) through
            // the same exits; the other side is turned 180 degrees (sized for its own cars).
            var lanes = lanes(drawn.legacy());
            assertTrue(lanes.size() == 1 || lanes.size() == 2
                  && Math.floorMod(Math.round(lanes.get(0).rotation() - lanes.get(1).rotation()), 360) == 180, "Variant " + variant);
            for (var lane : lanes) {
                assertTrue(lane.asset().matches("decal/car-park/(straight|parallel)-\\d+"), lane.asset());
                for (int direction = 0; direction < 6; direction++) {
                    if ((exits[variant] & 1 << direction) != 0) {
                        assertEquals(0, Math.floorMod(Math.round(lane.rotation()) + 60 * direction + 90, 180),
                              "The aisle turns to exit " + direction);
                    }
                }
            }
            assertTrue(tile.features().stream().anyMatch(f -> f.decoration() != null
                  && f.decoration().asset().startsWith("decal/")), "The bays are decal objects");
        }
        for (int variant : new int[] { 98, 99 }) {
            var drawn = drawn("fluff:5:" + variant);
            assertEquals(BoardRoad.Kind.NONE, drawn.tile().road(), "Furniture-only variants have no lane");
            assertEquals(0, drawn.tile().roadExits());
            assertFalse(lanes(drawn.legacy()).isEmpty(), "Their parked cars still get their bays");
        }
    }

    @Test
    void horizontalAndLegacyDirtRoadFluffUsesNativeRoadMaterials() {
        for (String terrain : List.of("road:1:9;road_fluff:2", "road:3:9;road_fluff:2", "road:3:9;road_fluff:100")) {
            var hex = new Hex(0, terrain, "");
            var image = capture(hex);
            var tile = tile(hex, image);
            assertEquals(0, alpha(image.decals()), terrain);
            assertEquals(BoardRoad.capture(hex), tile.road(), terrain);
            assertEquals(9, tile.roadExits());
            assertTrue(BoardRoad.rendered(tile), terrain);
        }
    }

    @Test
    void gardenLakesKeepTheirBoundedWaterWithoutChangingGameDepth() {
        // Garden lakes are plain FLUFF (the shipped template and tileset no longer pair them with WATER:0).
        for (int variant = 1; variant <= 5; variant++) {
            var drawn = drawn("fluff:94:" + variant);
            assertEquals(List.of("scenery/saxarba/SMV_Fluff/FluffSystem-07-Garden-04-Lake-1-0" + variant),
                  TilesetScenery.resolve(drawn.legacy(), false).scenery().models());
            assertFalse(drawn.tile().liquid().present(), "The object provides the pond's water inside its banks");
            assertFalse(drawn.tile().water());
        }
        // Game water under lake art is game water too: no artwork turns a WATER token into a decorative basin.
        for (String terrain : List.of("water:0", "water:0;fluff:94:1", "water:1;fluff:94:1", "water:0;ice:1;fluff:94:1")) {
            assertTrue(drawn(terrain).tile().liquid().present(), "Ordinary game water keeps the water engine");
        }
    }

    @Test
    void roofFurnitureDecodesToOneObjectOnTheRoof() {
        // Every legacy key decodes through rows, the glass dome too: the object stands on the roof.
        var drawn = drawn("building:2;bldg_elev:3;bldg_cf:50;fluff:90:9");
        var dome = drawn.hex().getDecorations().stream().filter(o -> o.asset().equals("scenery/roofs/glass-dome")).toList();
        assertEquals(1, dome.size());
        assertEquals("building", dome.getFirst().placement().receiver().terrain());
        assertTrue(drawn.tile().features().stream().anyMatch(f -> dome.getFirst().equals(f.decoration())));
        assertTrue(drawn.tile().features().stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY));
    }

    private static List<BoardSceneryLayouts.Component> lanes(Hex legacy) {
        return BoardSceneryLayouts.layout(TilesetScenery.resolve(legacy, false).scenery().models().getFirst()).components()
              .stream().filter(BoardSceneryLayouts.Component::decal).toList();
    }

    /** A legacy hex, the same hex converted (its token kept) and the tile every 3D view draws for it. */
    private record Drawn(Hex legacy, Hex hex, BoardScene.Tile tile) { }

    private static Drawn drawn(String terrain) {
        Board board = Board.createEmptyBoard(1, 1);
        board.setHex(new Coords(0, 0), new Hex(0, terrain, ""));
        Hex legacy = board.getHex(0, 0);
        BoardSceneryLayouts.convert(board);
        return new Drawn(legacy, board.getHex(0, 0), BoardTiles.captureAll(board).getFirst());
    }

    private static BoardArtwork.HexImage capture(Hex hex) {
        Board board = Board.createEmptyBoard(1, 1);
        board.setHex(new Coords(0, 0), hex);
        try (var artwork = new BoardArtwork()) { return artwork.capture(board, new Coords(0, 0), board.getHex(new Coords(0, 0))); }
    }

    private static BoardScene.Tile tile(Hex hex, BoardArtwork.HexImage image) {
        return BoardScene.captureTile(hex, image, null, new BoardScene.PixelPool(), neighbour -> null);
    }

    private static int alpha(java.awt.image.BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) { count++; }
            }
        }
        return count;
    }
}
