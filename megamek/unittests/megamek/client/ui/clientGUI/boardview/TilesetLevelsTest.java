/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TilesetLevelsTest {
    private static final File CLASSIC = new File(Configuration.dataDir(), "models/board/classic");

    /** Every graded image is art the Classic tileset draws: renamed art cannot silently lose its level grade. */
    @Test
    void gradedImagesAreTilesetArt() throws IOException {
        String tileset = Files.readString(new File(CLASSIC, "tileset/classic.tileset").toPath());
        var images = new ObjectMapper().readTree(new File(CLASSIC, "tileset/levels.json")).get("images");
        for (var entry : images.properties()) {
            String name = entry.getKey();
            assertTrue(new File(CLASSIC, name).isFile(), name);
            assertTrue(tileset.contains('"' + name) || tileset.contains(';' + name), name);
        }
    }

    /** As the old per-level copies: level 0 is the art itself, lower ground darker, higher ground lighter. */
    @Test
    void grassDarkensDownhillAndLightensUphill() throws IOException {
        String grass = "terrain/grass/base_grass.png";
        BufferedImage art = ImageIO.read(new File(CLASSIC, grass));
        var levels = new TilesetLevels(CLASSIC);
        assertSame(art, levels.image(art, grass, 0));
        float low = brightness(levels.image(art, grass, -3)), flat = brightness(art);
        float high = brightness(levels.image(art, grass, 3));
        assertTrue(low < flat && flat < high, low + " " + flat + " " + high);
    }

    /** Past the table's levels (-3..10) the formula goes on from its end, easing out as Enhanced's heights do. */
    @Test
    void groundPastTheTableKeepsGradingAndEasesOut() throws IOException {
        String grass = "terrain/grass/base_grass.png";
        BufferedImage art = ImageIO.read(new File(CLASSIC, grass));
        var levels = new TilesetLevels(CLASSIC);
        float deep = brightness(levels.image(art, grass, -8)), low = brightness(levels.image(art, grass, -3));
        float top = brightness(levels.image(art, grass, 10)), high = brightness(levels.image(art, grass, 20));
        float highest = brightness(levels.image(art, grass, 45));
        assertTrue(deep < low, deep + " " + low);
        assertTrue(top < high && high < highest && highest - high < high - top, top + " " + high + " " + highest);
    }

    /** Empty tables leave the formula alone, from level 0's art. */
    @Test
    void aRecipeWithoutATableIsItsFormula(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("tileset"));
        Files.writeString(root.resolve("tileset/levels.json"), """
              {"ease": 6, "images": {"grey.png": "plain"}, "recipes": {"plain": {
                "up": {"hue": 0, "saturation": 1, "value": 1.2}, "down": {"hue": 0, "saturation": 1, "value": 0.8},
                "hue": [], "saturation": [], "value": []}}}""");
        var art = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) { art.setRGB(x, y, 0xff808080); }
        }
        var levels = new TilesetLevels(root.toFile());
        assertSame(art, levels.image(art, "grey.png", 0));
        float low = brightness(levels.image(art, "grey.png", -2)), flat = brightness(art);
        float high = brightness(levels.image(art, "grey.png", 2));
        assertTrue(low < flat && flat < high, low + " " + flat + " " + high);
    }

    private static float brightness(Image image) {
        var pixels = (BufferedImage) image;
        float sum = 0;
        float[] hsb = new float[3];
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                int argb = pixels.getRGB(x, y);
                sum += Color.RGBtoHSB(argb >> 16 & 255, argb >> 8 & 255, argb & 255, hsb)[2] * (argb >>> 24);
            }
        }
        return sum;
    }
}
