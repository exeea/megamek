/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import megamek.common.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Material scale reads must work before any graphics context exists, including partial data packs and reload. */
class GpuSculptMetadataTest {
    @TempDir Path directory;
    private File previousData;
    private Path sculpt;

    @BeforeEach
    void configureAssets() throws Exception {
        previousData = Configuration.dataDir();
        Configuration.setDataDir(directory.toFile());
        sculpt = Files.createDirectories(directory.resolve("models/board/textures/sculpt"));
        writeMaterial(7.5f);
    }

    @AfterEach
    void restoreAssets() { Configuration.setDataDir(previousData); }

    @Test
    void readsRepeatScaleWithoutAGraphicsContext() {
        var assets = new GpuAssets();
        assertEquals(7.5f, assets.sculptTile("test"));
        assertEquals(7.5f, assets.sculptTile("test"));
        assets.dispose();
    }

    @Test
    void incompleteMaterialsKeepTheNeutralFallback() throws Exception {
        assertEquals(4, new GpuAssets().sculptTile("absent"));
        Files.delete(sculpt.resolve("test-normal.png"));
        assertEquals(4, new GpuAssets().sculptTile("test"));
        writeMaterial(7.5f);
        Files.delete(sculpt.resolve("test.png"));
        assertEquals(4, new GpuAssets().sculptTile("test"));
        writeMaterial(7.5f);
        Files.delete(sculpt.resolve("manifest.json"));
        assertEquals(4, new GpuAssets().sculptTile("test"));
    }

    @Test
    void reloadRefreshesMetadataAndMissingAssets() throws Exception {
        var assets = new GpuAssets();
        Files.delete(sculpt.resolve("test-normal.png"));
        assertEquals(4, assets.sculptTile("test"));
        writeMaterial(9.25f);
        assertEquals(4, assets.sculptTile("test"), "The live owner keeps its material snapshot until reload");
        assertEquals(9.25f, new GpuAssets().sculptTile("test"));
        assets.dispose();
        assertEquals(9.25f, assets.sculptTile("test"), "Disposal must release cached metadata too");
        assets.dispose();
    }

    private void writeMaterial(float tile) throws Exception {
        Files.writeString(sculpt.resolve("manifest.json"), "{\"materials\":{\"test\":{\"tile\":" + tile + "}}}");
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", sculpt.resolve("test.png").toFile());
        ImageIO.write(image, "png", sculpt.resolve("test-normal.png").toFile());
    }
}
