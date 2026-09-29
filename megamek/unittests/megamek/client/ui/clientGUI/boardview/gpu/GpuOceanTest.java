/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GpuOceanTest {
    @Test
    void lavaSuppressesShortWavesAndRetainsARealWindIndependentSpectrum() {
        float[] lava = GpuOcean.initialSpectrum(.8f, .6f, .35f, true);
        assertArrayEquals(lava, GpuOcean.initialSpectrum(-1, 0, 1, true), "Wind must not boil or stop lava");
        assertTrue(shortWaveShare(lava) < shortWaveShare(GpuOcean.initialSpectrum(.8f, .6f, .35f)) * .1,
              "Viscous lava retains broad folds instead of water's short chop");
        int size = GpuOcean.SIZE;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int a = (y * size + x) * 4, b = (((size - y) % size) * size + (size - x) % size) * 4;
                assertEquals(lava[b], lava[a + 2]);
                assertEquals(-lava[b + 1], lava[a + 3], "Conjugate partners keep the inverse transform real");
                for (int channel = 0; channel < 4; channel++) { assertTrue(Float.isFinite(lava[a + channel])); }
            }
        }
    }

    private static double shortWaveShare(float[] spectrum) {
        double total = 0, shortWaves = 0;
        for (int y = 0; y < GpuOcean.SIZE; y++) {
            for (int x = 0; x < GpuOcean.SIZE; x++) {
                double k = Math.PI * 2 * Math.hypot(x - GpuOcean.SIZE / 2, y - GpuOcean.SIZE / 2) / GpuOcean.PATCH_METRES;
                int i = (y * GpuOcean.SIZE + x) * 4;
                double energy = k * k * (spectrum[i] * spectrum[i] + spectrum[i + 1] * spectrum[i + 1]);
                total += energy;
                if (k > Math.PI / 2) { shortWaves += energy; }
            }
        }
        assertTrue(total > 0, "The spectrum must carry motion");
        return shortWaves / total;
    }
}
