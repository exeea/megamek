/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GpuBoardPrintTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void printPreservesTheChosenLightingWithoutWeather(boolean moonlight) {
        var weather = new BoardAtmosphere.Effects(.9f, .8f, .7f, .6f, .5f, .4f, 135);
        var original = new BoardAtmosphere.Settings(22.5f, .9f, .8f, 4, .7f, -1.25f, weather,
              Atmosphere.VERY_HIGH, 40, moonlight, AtmosphericTaint.TOXIC_CAUSTIC, 1.7f);

        var printable = GpuBoardPrint.lightingOnly(original);

        assertEquals(22.5f, printable.hour());
        assertEquals(-1.25f, printable.exposure());
        assertEquals(moonlight, printable.moonlight());
        assertEquals(1.7f, printable.gravity(), "Printing preserves the terrain's gravity-dependent presentation");
        assertEquals(0, printable.clouds());
        assertEquals(0, printable.fog());
        assertEquals(0, printable.haze());
        assertEquals(BoardAtmosphere.Effects.NONE, printable.effects(), "Rain, snow, hail, sand, lightning and wind are excluded");
        assertFalse(printable.effects().hasParticles());
        assertEquals(0, BoardAtmosphere.wetness(printable), "Weather cannot add rain sheen to the printed terrain");
        assertEquals(Atmosphere.STANDARD, printable.pressure());
        assertEquals(AtmosphericTaint.BREATHABLE, printable.taint(), "Atmospheric tint is excluded from a printed board");
        assertEquals(weather, original.effects());
        assertTrue(original.clouds() > 0 && original.fog() > 0 && original.haze() > 0,
              "The source atmosphere is unchanged");
    }
}
