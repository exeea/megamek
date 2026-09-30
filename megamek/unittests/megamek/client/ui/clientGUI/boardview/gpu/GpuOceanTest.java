/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GpuOceanTest {
    private static final int SIZE = GpuOcean.SIZE;

    @Test
    void strongerWindRaisesLongerWavesFromCalmRipplesToAGaleSea() {
        double previous = 0;
        for (float strength = 0; strength <= 1; strength += .25f) {
            double length = peakWavelength(strength);
            assertTrue(length > previous, "Stronger wind builds longer waves: " + length);
            previous = length;
        }
        // Deep water: λ = 2πg/ω². Calm air only ripples; Beaufort 8 over the open-sea fetch peaks near 70 m.
        assertTrue(peakWavelength(0) < 5, "Calm: " + peakWavelength(0));
        assertTrue(peakWavelength(1) > 55 && peakWavelength(1) < 85, "Gale: " + peakWavelength(1));
        assertTrue(GpuOcean.sea(1)[2] > GpuOcean.sea(0)[2], "The swell grows a little under wind");
        // JONSWAP's closed form for the unscaled sea: about 3 m in a gale over this fetch, a 0.3 m swell in calm.
        assertEquals(3, GpuOcean.significantHeight(1), .3);
        assertEquals(.3, GpuOcean.significantHeight(0), .05);
        assertEquals(1, GpuOcean.storm(0), 1e-6, "Calm water is never exaggerated");
    }

    @Test
    void lavaSpectrumIsHermitianSoItsTransformIsReal() {
        float[] lava = GpuOcean.lavaSpectrum();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int a = (y * SIZE + x) * 4, b = (((SIZE - y) % SIZE) * SIZE + (SIZE - x) % SIZE) * 4;
                assertEquals(lava[b], lava[a + 2]);
                assertEquals(-lava[b + 1], lava[a + 3], "Conjugate partners keep the inverse transform real");
                for (int channel = 0; channel < 4; channel++) { assertTrue(Float.isFinite(lava[a + channel])); }
            }
        }
    }

    private static double peakWavelength(float strength) {
        double omega = GpuOcean.sea(strength)[0];
        return 2 * Math.PI * 9.81 / (omega * omega);
    }
}
