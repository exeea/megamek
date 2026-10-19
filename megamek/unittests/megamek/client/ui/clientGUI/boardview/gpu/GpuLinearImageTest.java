/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** CPU-only filter oracles: fractional coverage, packed numerical channels and registered placement. */
class GpuLinearImageTest {
    @Test
    void fractionalFootprintsRetainTheWholeSourceRatherThanOnlyTheirCentres() {
        var source = line(new float[][] {{0, 0, 0, 1}, {1, 1, 1, 1}, {0, 0, 0, 1},
              {1, 1, 1, 1}, {0, 0, 0, 1}});
        var result = new GpuLinearImage(2, 1);
        result.draw(source, 0, 0, 5, 1, 0, 0, 2, 1);
        // Each 2.5-pixel footprint contains exactly one white pixel. Bilinear centre sampling gives .75.
        assertArrayEquals(new float[] {.4f, .4f, .4f, 1, .4f, .4f, .4f, 1}, values(result), .000001f);
    }

    @Test
    void cutoutCoverageCannotTintVisibleBlueWithTransparentRed() {
        var source = line(new float[][] {{1, 0, 0, 0}, {0, 0, 1, 1}, {1, 0, 0, 0},
              {0, 0, 1, 1}, {1, 0, 0, 0}});
        var cutout = new GpuLinearImage(1, 1);
        cutout.drawCutout(source, 0, 0, 5, 1, 0, 0, 1, 1);
        assertArrayEquals(new float[] {0, 0, 1, .4f}, values(cutout), .000001f);
        // In material arrays alpha can be height/AO. It must never weight their independent RGB channels.
        var data = new GpuLinearImage(1, 1);
        data.draw(source, 0, 0, 5, 1, 0, 0, 1, 1);
        assertArrayEquals(new float[] {.6f, 0, .4f, .4f}, values(data), .000001f);
    }

    @Test
    void clippingKeepsTheRegisteredFootprintAndDoesNotRescaleTheVisiblePiece() {
        var source = line(new float[][] {{1, 0, 0, 1}, {0, 0, 0, 1}, {0, 0, 0, 1},
              {0, 0, 0, 1}, {0, 0, 0, 1}, {1, 1, 1, 1}, {1, 0, 0, 1}});
        var result = new GpuLinearImage(2, 2);
        result.fill(.2f, .3f, .4f, .5f);
        // Ignore both red guard texels; clip the left destination pixel and enlarge the one source row.
        result.draw(source, 1, 0, 5, 1, -1, 0, 2, 2);
        assertArrayEquals(new float[] {.4f, .4f, .4f, 1, .2f, .3f, .4f, .5f,
              .4f, .4f, .4f, 1, .2f, .3f, .4f, .5f}, values(result), .000001f);
    }

    @Test
    void bothShrinkingAxesRetainAnIsolatedCoveredTexel() {
        var source = new GpuLinearImage(3, 3);
        source.fill(1, 0, 0, 0);
        var blue = new GpuLinearImage(1, 1);
        blue.fill(0, 0, 1, 1);
        source.draw(blue, 0, 0, 1, 1, 1, 1, 1, 1);
        var result = new GpuLinearImage(1, 1);
        result.drawCutout(source, 0, 0, 3, 3, 0, 0, 1, 1);
        assertArrayEquals(new float[] {0, 0, 1, 1f / 9}, values(result), .000001f);
        result.draw(source, 0, 0, 3, 3, 0, 0, 1, 1);
        assertArrayEquals(new float[] {8f / 9, 0, 1f / 9, 1f / 9}, values(result), .000001f);
    }

    @Test
    void magnifiedIndependentChannelsKeepBilinearValues() {
        var source = line(new float[][] {{0, 0, 0, 0}, {1, 1, 1, 1}});
        var result = new GpuLinearImage(4, 1);
        result.draw(source, 0, 0, 2, 1, 0, 0, 4, 1);
        assertArrayEquals(new float[] {0, 0, 0, 0, .25f, .25f, .25f, .25f,
              .75f, .75f, .75f, .75f, 1, 1, 1, 1}, values(result), .000001f);
        // The changed cutout branch uses the same coverage weights but cannot reveal hidden red pigment.
        var cutout = line(new float[][] {{1, 0, 0, 0}, {0, 0, 1, 1}});
        result.drawCutout(cutout, 0, 0, 2, 1, 0, 0, 4, 1);
        assertArrayEquals(new float[] {1, 0, 0, 0, 0, 0, 1, .25f,
              0, 0, 1, .75f, 0, 0, 1, 1}, values(result), .000001f);
    }

    @Test
    void emptyCutoutsOverwriteCoverageButRetainTheirOwnEdgeColor() {
        var source = line(new float[][] {{1, 0, 0, 0}, {0, 0, 1, 0}, {1, 0, 0, 0}});
        var result = new GpuLinearImage(1, 1);
        result.fill(0, 1, 0, 1);
        result.drawCutout(source, 0, 0, 3, 1, 0, 0, 1, 1);
        assertArrayEquals(new float[] {2f / 3, 0, 1f / 3, 0}, values(result), .000001f);
    }

    private static GpuLinearImage line(float[][] colors) {
        var image = new GpuLinearImage(colors.length, 1);
        var pixel = new GpuLinearImage(1, 1);
        for (int x = 0; x < colors.length; x++) {
            float[] c = colors[x];
            pixel.fill(c[0], c[1], c[2], c[3]);
            image.draw(pixel, 0, 0, 1, 1, x, 0, 1, 1);
        }
        return image;
    }

    private static float[] values(GpuLinearImage image) {
        float[] result = new float[image.width * image.height * 4];
        image.buffer().get(result);
        return result;
    }
}
