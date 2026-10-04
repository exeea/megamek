/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardSceneryTest {
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
    void surfaceSymbolsRemainDecalsWithoutSwitchingOffTheTerrainMaterial() {
        for (String terrain : List.of("fluff:14:0", "fluff:50:1", "fluff:70:1", "fluff:91:1", "fluff:10:9", "fluff:100:1")) {
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
    void roofFurnitureUsesTheCapturedBuildingHeight() {
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
