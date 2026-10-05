/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardSceneryTest {
    @Test
    void tacticalSceneryKeepsOriginalArtworkSeparateFromTheGroundOnRoofsAndUnderwater() {
        for (String support : List.of("pavement:1", "water:2",
              "pavement:1;building:2;bldg_elev:3;bldg_cf:90")) {
            var bare = capture(new Hex(0, support, ""));
            for (String decoration : List.of("fluff:6:0", "fluff:14:0", "geyser:1")) {
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
            assertEquals(List.of("scenery/saxarba/rubble_" + families.get(type - 1) + "_path"), path.scenery().models());
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
            assertTrue(models.size() >= 281, "The full family and rotation catalog is shipped");
            for (Path file : models) {
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
            var hex = new Hex(0, "pavement:1;" + terrain, "");
            var image = capture(hex);
            var tile = tile(hex, image);
            assertTrue(tile.detailedGround(), terrain);
            assertEquals(1, image.scenery().models().size(), terrain);
            assertTrue(tile.features().stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY));
            assertEquals(0, alpha(image.decals()), "A modeled object must not leave its painted twin: " + terrain);
            assertTrue(alpha(image.tileset()) > 0, "Tactical artwork remains available");
        }
    }

    @Test
    void everySkylightAndLedgeRotationUsesTheSelectedSourceVariant() {
        for (int variant = 0; variant < 6; variant++) {
            for (String family : List.of("6", "8")) {
                var image = capture(new Hex(0, "fluff:" + family + ":" + variant, ""));
                assertEquals(List.of("scenery/fluff/" + (family.equals("6") ? "skylight" : "ledge")
                      + (variant + 1)), image.scenery().models());
            }
        }
    }

    @Test
    void everyExportedLedgeFacesOutwardAndHasAVisibleUpperSurface() {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        for (int variant = 1; variant <= 6; variant++) {
            var file = root.resolve("scenery/fluff/ledge" + variant + ".glb");
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
    }

    @Test
    void surfaceSymbolsRemainDecalsWithoutSwitchingOffTheTerrainMaterial() {
        for (String terrain : List.of("fluff:14:0", "fluff:50:1", "fluff:70:1", "fluff:91:1", "fluff:10:9")) {
            var hex = new Hex(0, "pavement:1;" + terrain, "");
            var image = capture(hex);
            assertTrue(image.scenery().models().isEmpty(), terrain);
            assertTrue(alpha(image.decals()) > 0, terrain);
            assertTrue(tile(hex, image).detailedGround(), terrain);
        }
    }

    @Test
    void compositePortArtReplacesVisualTreesButRetainsTheGamesWoodsTerrain() {
        var hex = new Hex(0, "pavement:1;woods:1;fluff:80:1", "");
        var image = capture(hex);
        var tile = tile(hex, image);
        assertEquals(1, hex.terrainLevel(Terrains.WOODS));
        assertTrue(image.scenery().modelTerrains().contains(Terrains.WOODS));
        assertFalse(tile.features().stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.TREE));
        assertTrue(tile.features().stream().anyMatch(f -> f.asset().contains("SeaportSystem")));
    }

    @Test
    void roadsideTreesAndParkedCarsPreserveTheRoadAndAllSourceVariants() {
        for (String terrain : List.of("road:2:9", "road:1:9;road_fluff:3", "road:1:9;fluff:5:2")) {
            var hex = new Hex(0, terrain, "");
            var image = capture(hex);
            var tile = tile(hex, image);
            assertFalse(image.scenery().models().isEmpty(), terrain);
            assertEquals(0, alpha(image.decals()), terrain);
            assertEquals(9, tile.roadExits(), terrain);
            assertTrue(tile.road() != BoardRoad.Kind.NONE, terrain);
            assertTrue(tile.detailedGround(), terrain);
        }
    }

    @Test
    void allParkingRoadsUseTheRoadEngineWithoutAddingGameplayTerrain() {
        int[] exits = { 18, 36, 9, 18, 36, 9, 36, 9 };
        for (int variant = 0; variant < exits.length; variant++) {
            var hex = new Hex(0, "fluff:5:" + variant, "");
            var image = capture(hex);
            var tile = tile(hex, image);
            assertFalse(hex.containsTerrain(Terrains.ROAD), "Cosmetic roads do not change movement");
            assertEquals(BoardRoad.Kind.PAVED, tile.road());
            assertEquals(exits[variant], tile.roadExits());
            assertEquals(0, alpha(image.decals()));
            assertTrue(BoardRoad.rendered(tile));
            assertTrue(tile.detailedGround());
        }
        for (int variant : new int[] { 98, 99 }) {
            var hex = new Hex(0, "fluff:5:" + variant, "");
            var tile = tile(hex, capture(hex));
            assertEquals(BoardRoad.Kind.NONE, tile.road(), "Furniture-only variants have no cosmetic road");
            assertEquals(0, tile.roadExits());
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
        for (int variant = 1; variant <= 5; variant++) {
            var hex = new Hex(0, "water:0;fluff:94:" + variant, "");
            var image = capture(hex);
            var tile = tile(hex, image);
            assertTrue(image.scenery().modelTerrains().contains(Terrains.WATER));
            assertTrue(hex.containsTerrain(Terrains.WATER));
            assertEquals(0, hex.terrainLevel(Terrains.WATER));
            assertFalse(tile.liquid().present(), "The model provides the pond's water inside its banks");
            assertFalse(tile.water());
        }
        for (String terrain : List.of("water:0", "water:1;fluff:94:1", "water:0;ice:1;fluff:94:1")) {
            var hex = new Hex(0, terrain, "");
            assertTrue(tile(hex, capture(hex)).liquid().present(), "Ordinary game water keeps the water engine");
        }
    }

    @Test
    void roofFurnitureRetainsNominalBuildingHeightForClearance() {
        var hex = new Hex(0, "building:2;bldg_elev:3;bldg_cf:50;fluff:6:6", "");
        var image = capture(hex);
        var feature = tile(hex, image).features().stream()
              .filter(f -> f.kind() == BoardScene.FeatureKind.SCENERY).findFirst().orElseThrow();
        assertEquals(3, feature.elevation());
        assertEquals(1, feature.height(), "Authored furniture retains physical dimensions");
    }

    private static BoardArtwork.HexImage capture(Hex hex) {
        Board board = Board.createEmptyBoard(1, 1);
        board.setHex(new Coords(0, 0), hex);
        try (var artwork = new BoardArtwork()) { return artwork.capture(board, new Coords(0, 0), true); }
    }

    private static BoardScene.Tile tile(Hex hex, BoardArtwork.HexImage image) {
        return BoardScene.captureTile(hex, image, null, new BoardScene.PixelPool());
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
