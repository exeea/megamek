/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import megamek.client.ui.tileset.HexTileset;
import megamek.common.Configuration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in, image-aware coverage of every installed board. Set {@code MEGAMEK_LEGACY_CORPUS=1}; takes minutes. */
@EnabledIfEnvironmentVariable(named = "MEGAMEK_LEGACY_CORPUS", matches = "1")
class EnhancedTerrainCorpusTest {
    @Test
    void everyBoardUsesNativeGroundAndHasItsStructureModels() throws Exception {
        Path root = Configuration.boardsDir().toPath();
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                  .filter(path -> BoardFile.isBoardName(path.toString())).sorted().toList();
        }
        assertFalse(files.isEmpty(), "The installed board corpus must be present");
        var definitions = TilesetScenery.tileset();
        long checked = 0;
        try (var images = new HexTileset(definitions.root(), definitions)) {
            for (Path file : files) {
                var board = assertDoesNotThrow(() -> BoardFile.read(file), file.toString());
                assertDoesNotThrow(() -> BoardSceneryLayouts.convert(board), file.toString());
                for (int x = 0; x < board.getWidth(); x++) {
                    for (int y = 0; y < board.getHeight(); y++) {
                        var at = new Coords(x, y);
                        var original = board.getHex(at);
                        assertFalse(original.containsTerrain(0), () -> file + " " + at + ": none marker");
                        var hex = BoardSceneryLayouts.withoutTakenTokens(original);
                        var models = TilesetScenery.structureModels(hex);
                        for (int type : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL }) {
                            assertTrue(!hex.containsTerrain(type) || models.containsKey(type),
                                  () -> file + " " + at + ": missing model for " + Terrains.getName(type));
                        }
                        // Liquids have their own native surface renderer, independent of the ground-material predicate.
                        if (!BoardLiquid.capture(hex).present()) {
                            var scenery = TilesetScenery.resolve(hex, models, true).scenery();
                            boolean enhanced = BoardFeatures.detailedGround(hex, models, Set.of(), scenery);
                            if (!enhanced) {
                                enhanced = BoardFeatures.detailedGround(hex, models, images.blankTerrainTypes(hex), scenery);
                                images.clearHex(hex);
                            }
                            assertTrue(enhanced, () -> file + " " + at + ": legacy ground for " + hex);
                        }
                        checked++;
                    }
                }
            }
        }
        System.out.printf("ENHANCED_CORPUS maps=%d hexes=%d: native ground and complete structure coverage%n",
              files.size(), checked);
    }
}
