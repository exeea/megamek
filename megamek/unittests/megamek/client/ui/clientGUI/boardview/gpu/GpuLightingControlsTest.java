/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import com.badlogic.gdx.graphics.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuLightingControlsTest {
    @TempDir Path directory;

    @Test void defaultsPreserveLightingAndControlsKeepTheBackdrop() {
        var base = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        assertSame(base, base.tuned(1, BoardAtmosphere.DEFAULT_AMBIENT_SHARE, 1, null));
        var noSky = base.tuned(.25f, BoardAtmosphere.DEFAULT_AMBIENT_SHARE, 0, null);
        assertEquals(base.direct(), noSky.direct());
        assertEquals(0, noSky.ambient().r);
        assertEquals(0, noSky.environmentSky().r);
        assertSame(base.sky(), noSky.sky());
        assertSame(base.horizon(), noSky.horizon());
        assertEquals(.25f, noSky.shadowStrength());
        var ambientOnly = base.tuned(1, 1, 1, null);
        assertFalse(ambientOnly.hasDirectLight());
        assertEquals(base.ambient().r / BoardAtmosphere.DEFAULT_AMBIENT_SHARE, ambientOnly.ambient().r, 1e-6);
        var directOnly = base.tuned(1, 0, 1, null);
        assertEquals(0, directOnly.ambient().r);
        assertEquals(base.direct().r / (1 - BoardAtmosphere.DEFAULT_AMBIENT_SHARE), directOnly.direct().r, 1e-6);
    }

    @Test void panoramaDecodesSrgbOnceAndFiltersConstantRadianceWithoutChangingIt() throws Exception {
        Path file = constantPng(directory.resolve("constant.png"), 0x804020);
        var image = GpuLightingEnvironment.load(file);
        float[] expected = { GpuColor.linear(128 / 255f), GpuColor.linear(64 / 255f), GpuColor.linear(32 / 255f) };
        var pixels = FloatBuffer.allocate(512 * 512 * 4);
        image.write(pixels, 512);
        for (int level = 0; level < 6; level++) {
            for (int y = 0; y < 66; y++) {
                for (int x = 0; x < 130; x++) {
                    int at = ((130 + level / 3 * 66 + y) * 512 + level % 3 * 130 + x) * 4;
                    for (int c = 0; c < 3; c++) { assertEquals(expected[c], pixels.get(at + c), 2e-6); }
                }
            }
        }
        var base = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        var custom = base.tuned(1, .2f, .5f, image);
        assertSame(image, custom.environmentImage());
        assertSame(base.sky(), custom.sky());
        assertEquals(expected[0] * .5f, custom.ambient().r, 2e-6);
    }

    @Test void hdrKeepsRadianceAboveOne() throws Exception {
        Path file = directory.resolve("constant.hdr");
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < 8; i++) { bytes.write(new byte[] { (byte) 128, 32, 16, (byte) 130 }); }
        Files.write(file, bytes.toByteArray());
        Color color = GpuLightingEnvironment.load(file).upwardIrradiance();
        assertEquals(2, color.r, 2e-5);
        assertEquals(.5, color.g, 2e-5);
        assertEquals(.25, color.b, 2e-5);
    }

    @Test void rejectsAnOrdinaryNonPanoramicImage() throws Exception {
        Path file = directory.resolve("square.png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        assertThrows(java.io.IOException.class, () -> GpuLightingEnvironment.load(file));
    }

    static Path constantPng(Path file, int rgb) throws Exception {
        var image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) { for (int x = 0; x < 8; x++) { image.setRGB(x, y, rgb); } }
        ImageIO.write(image, "png", file.toFile());
        return file;
    }
}
