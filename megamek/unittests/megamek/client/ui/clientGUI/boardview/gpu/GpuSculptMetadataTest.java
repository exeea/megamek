/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

import megamek.common.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Material metadata and missing-file errors must work before any graphics context exists. */
class GpuSculptMetadataTest {
    @TempDir Path directory;
    private File previousData;
    private Path sculpt;

    @BeforeEach
    void configureAssets() throws Exception {
        previousData = Configuration.dataDir();
        Configuration.setDataDir(directory.toFile());
        sculpt = Files.createDirectories(directory.resolve("models/board/textures/terrain/surfaces"));
        writeMaterial(7.5f);
    }

    @AfterEach
    void restoreAssets() { Configuration.setDataDir(previousData); }

    @Test
    void readsRepeatScaleWithoutAGraphicsContext() throws Exception {
        var assets = new GpuAssets();
        assertEquals(7.5f, assets.sculptTile("test"));
        Files.copy(sculpt.resolve("test.png"), sculpt.resolve("other.png"));
        Files.copy(sculpt.resolve("test-normal.png"), sculpt.resolve("other-normal.png"));
        Files.writeString(sculpt.resolve("other.json"), "{\"tile\":3.25}");
        assertEquals(3.25f, assets.sculptTile("other"), "Each material owns its native exported definition");
        writeMaterial(9.25f);
        assertEquals(7.5f, assets.sculptTile("test"), "Editing one file waits for asset reload");
        assets.dispose();
        assertEquals(9.25f, assets.sculptTile("test"));
        assets.dispose();
    }

    @Test
    void incompleteMaterialsReportTheMissingFileBeforeAnyUpload() throws Exception {
        missing("absent.json", () -> new GpuAssets().sculptTile("absent"));
        for (String suffix : List.of(".png", "-normal.png", ".json")) {
            writeMaterial(7.5f);
            Files.delete(sculpt.resolve("test" + suffix));
            missing("test" + suffix, () -> new GpuAssets().sculptTile("test"));
            missing("test" + suffix, () -> new GpuAssets().sculpt("test"));
            missing("test" + suffix, () -> new GpuAssets().sculptArray(List.of("test")));
        }
    }

    @Test
    void incompleteEnhancedMaterialsNeverUseAvailableClassicArtwork() throws Exception {
        Path textures = directory.resolve("models/board/textures");
        for (String classic : List.of("snow", "concrete", "dirt", "rock")) {
            Path file = textures.resolve("terrain/classic/" + classic + ".png");
            Files.createDirectories(file.getParent());
            Files.copy(sculpt.resolve("test.png"), file);
        }
        for (String set : List.of("roads/asphalt", "roads/gravel", "roads/dirt", "terrain/ice/sheet",
              "terrain/lava/lava", "terrain/lava/crust")) {
            List<String> suffixes = set.startsWith("terrain/lava/")
                  ? List.of("", "-normal", "-surface", "-heat") : List.of("", "-normal", "-surface");
            for (String suffix : suffixes) {
                Path file = textures.resolve(set + suffix + ".png");
                Files.createDirectories(file.getParent());
                Files.copy(sculpt.resolve("test.png"), file);
            }
        }
        for (String set : List.of("roads/asphalt", "terrain/ice/sheet", "terrain/lava/lava", "terrain/lava/crust")) {
            Path normal = textures.resolve(set + "-normal.png");
            Files.delete(normal);
            if (set.startsWith("roads/")) {
                missing("asphalt-normal.png", () -> new GpuAssets().road("asphalt"));
                missing("asphalt-normal.png", () -> new GpuAssets().roadArray());
            } else if (set.startsWith("terrain/ice/")) {
                missing("sheet-normal.png", () -> new GpuAssets().ice());
            } else {
                missing(normal.getFileName().toString(), () -> new GpuAssets().magma(set.endsWith("/lava")));
            }
            Files.copy(sculpt.resolve("test-normal.png"), normal);
        }
    }

    @Test
    void reloadRefreshesMetadataAndMissingAssets() throws Exception {
        var assets = new GpuAssets();
        assertEquals(7.5f, assets.sculptTile("test"));
        Files.delete(sculpt.resolve("test-normal.png"));
        assertEquals(7.5f, assets.sculptTile("test"), "The loaded owner retains its snapshot until reload");
        missing("test-normal.png", () -> new GpuAssets().sculptTile("test"));
        writeMaterial(9.25f);
        assertEquals(7.5f, assets.sculptTile("test"), "The live owner keeps its material snapshot until reload");
        assertEquals(9.25f, new GpuAssets().sculptTile("test"));
        assets.dispose();
        assertEquals(9.25f, assets.sculptTile("test"), "Disposal must release cached metadata too");
        assets.dispose();
    }

    private static void missing(String name, org.junit.jupiter.api.function.Executable load) {
        var error = assertThrows(IllegalArgumentException.class, load);
        assertTrue(error.getMessage().contains("Missing required Enhanced terrain asset"), error.getMessage());
        assertTrue(error.getMessage().contains(name), error.getMessage());
    }

    private void writeMaterial(float tile) throws Exception {
        Files.writeString(sculpt.resolve("test.json"), "{\"tile\":" + tile + "}");
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", sculpt.resolve("test.png").toFile());
        ImageIO.write(image, "png", sculpt.resolve("test-normal.png").toFile());
    }
}
