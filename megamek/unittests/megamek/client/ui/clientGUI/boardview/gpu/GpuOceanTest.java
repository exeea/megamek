/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GpuOceanTest {
    private static final int SIZE = GpuOcean.SIZE;

    @Test
    void windRaisesARealisticSeaWithACalmSwellUnderneath() {
        double calm = significantHeight(GpuOcean.initialSpectrum(.8f, .6f, 0));
        double breeze = significantHeight(GpuOcean.initialSpectrum(.8f, .6f, .5f));
        double gale = significantHeight(GpuOcean.initialSpectrum(.8f, .6f, 1));
        // Heights in metres come straight out of the transform. Beaufort 8 raises roughly 3 m over this fetch; the
        // storm is drawn half as high again.
        assertTrue(calm > .2 && calm < .5, "Calm open water keeps a low swell, not a mirror: " + calm);
        assertTrue(breeze > 1.4 && breeze < 2.6, "A fresh breeze: " + breeze);
        assertTrue(gale > 3.8 && gale < 5.4, "A gale: " + gale);
    }

    @Test
    void windSeaEnergyLiesDownwindAndCascadesShareTheSeaWithoutGaps() {
        float[] spectrum = GpuOcean.initialSpectrum(.8f, .6f, 1);
        double downwind = 0, upwind = 0;
        double[] cascades = new double[GpuOcean.PATCHES.length];
        for (int cascade = 0; cascade < cascades.length; cascade++) {
            double cell = Math.PI * 2 / GpuOcean.PATCHES[cascade];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double kx = cell * (x - SIZE / 2), ky = cell * (y - SIZE / 2), k = Math.hypot(kx, ky);
                    double energy = energy(spectrum, cascade, x, y);
                    cascades[cascade] += energy;
                    if (k > 0 && cascade == 0) {
                        double along = (kx * .8 + ky * .6) / k;
                        if (along > .5) { downwind += energy; } else if (along < -.5) { upwind += energy; }
                    }
                }
            }
        }
        assertTrue(downwind > upwind * 50, "A wind sea's energy lies with the wind; opposing trains would stand still");
        for (double share : cascades) { assertTrue(share > 0, "Every cascade carries part of the sea"); }
        assertTrue(cascades[0] > cascades[1] && cascades[1] > cascades[2], "Longer waves carry more height");
    }

    @Test
    void everyCascadeIsHermitianSoItsTransformIsReal() {
        float[] water = GpuOcean.initialSpectrum(-.6f, .8f, .7f);
        float[] lava = GpuOcean.lavaSpectrum();
        for (int cascade = 0; cascade < GpuOcean.PATCHES.length; cascade++) { assertConjugates(water, SIZE * 3, cascade); }
        assertConjugates(lava, SIZE, 0);
    }

    private static void assertConjugates(float[] data, int width, int cascade) {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int a = (y * width + cascade * SIZE + x) * 4;
                int b = (((SIZE - y) % SIZE) * width + cascade * SIZE + (SIZE - x) % SIZE) * 4;
                assertEquals(data[b], data[a + 2]);
                assertEquals(-data[b + 1], data[a + 3], "Conjugate partners keep the inverse transform real");
                for (int channel = 0; channel < 4; channel++) { assertTrue(Float.isFinite(data[a + channel])); }
            }
        }
    }

    private static double energy(float[] spectrum, int cascade, int x, int y) {
        int i = (y * SIZE * GpuOcean.PATCHES.length + cascade * SIZE + x) * 4;
        return spectrum[i] * spectrum[i] + spectrum[i + 1] * spectrum[i + 1];
    }

    /** Four standard deviations of the surface: each mode and its mirror contribute 2|h0|² of variance on average. */
    private static double significantHeight(float[] spectrum) {
        double variance = 0;
        for (int cascade = 0; cascade < GpuOcean.PATCHES.length; cascade++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) { variance += 2 * energy(spectrum, cascade, x, y); }
            }
        }
        return 4 * Math.sqrt(variance);
    }
}
