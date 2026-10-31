/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.client.lobby.PlanetaryConditionsForm.Field;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.EMI;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.planetaryConditions.Wind;
import megamek.common.planetaryConditions.WindDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanetaryConditionsFormTest {
    private PlanetaryConditions original;
    private PlanetaryConditionsForm form;

    @BeforeEach
    void setUp() {
        original = new PlanetaryConditions();
        form = new PlanetaryConditionsForm(original);
    }

    @Test
    void readsTheConditionsAndLeavesTheOriginalAlone() {
        assertEquals(original.getLight(), form.light());
        assertEquals(Integer.toString(original.getTemperature()), form.temperature());
        assertEquals(Float.toString(original.getGravity()), form.gravity());
        form.light(Light.FULL_MOON);
        form.temperature("-30");
        PlanetaryConditions result = form.result();
        assertEquals(Light.FULL_MOON, result.getLight());
        assertEquals(-30, result.getTemperature());
        assertEquals(Light.DAY, original.getLight());
        assertTrue(form.valid());
    }

    @Test
    void snowCapsTheTemperature() {
        form.temperature("25");
        form.weather(Weather.LIGHT_SNOW);
        assertEquals("-40", form.temperature());
        form.weather(Weather.HEAVY_SNOW);
        assertEquals("-50", form.temperature());
        form.weather(Weather.ICE_STORM);
        assertEquals("-60", form.temperature());
        assertTrue(form.valid());
        form.temperature("-10");
        assertEquals(List.of("PlanetaryConditionsDialog.invalid.iceStormTemp"), form.errors(Field.TEMPERATURE));
        assertEquals(List.of("PlanetaryConditionsDialog.invalid.iceStormTemp"), form.errors(Field.WEATHER));
        assertFalse(form.valid());
        assertFalse(form.errorText(Field.WEATHER).contains("<BR>"));
    }

    @Test
    void stormyWeatherForcesTheWindAndLocksIt() {
        form.shiftStrength(true);
        form.weather(Weather.GUSTING_RAIN);
        assertEquals(Wind.STRONG_GALE, form.wind());
        assertFalse(form.shiftStrength());
        assertFalse(form.enabled(Field.WIND));
        assertFalse(form.enabled(Field.SHIFT_STRENGTH));
        form.weather(Weather.LIGHTNING_STORM);
        assertEquals(Wind.MOD_GALE, form.wind());
    }

    @Test
    void vacuumClearsTheAtmosphereFields() {
        form.weather(Weather.HEAVY_RAIN);
        form.fog(Fog.FOG_HEAVY);
        form.taint(AtmosphericTaint.TOXIC_POISON);
        form.wind(Wind.STORM);
        form.blowingSand(true);
        form.shiftDirection(true);
        form.atmosphere(Atmosphere.VACUUM);
        assertEquals(Weather.CLEAR, form.weather());
        assertEquals(Fog.FOG_NONE, form.fog());
        assertEquals(AtmosphericTaint.BREATHABLE, form.taint());
        assertEquals(Wind.CALM, form.wind());
        assertFalse(form.blowingSand());
        assertFalse(form.shiftDirection());
        for (Field field : List.of(Field.WEATHER, Field.FOG, Field.TAINT, Field.WIND, Field.BLOWING_SAND,
              Field.SHIFT_DIRECTION, Field.SHIFT_STRENGTH, Field.WIND_DIRECTION)) {
            assertFalse(form.enabled(field), field.name());
        }
        assertTrue(form.enabled(Field.LIGHT));
    }

    @Test
    void thinAtmosphereClearsWeatherButKeepsTheWind() {
        form.wind(Wind.STRONG_GALE);
        form.weather(Weather.MOD_RAIN);
        form.atmosphere(Atmosphere.THIN);
        assertEquals(Weather.CLEAR, form.weather());
        assertEquals(Wind.STRONG_GALE, form.wind());
        assertFalse(form.enabled(Field.WEATHER));
        assertTrue(form.enabled(Field.WIND));
    }

    @Test
    void blowingSandNeedsAModerateGale() {
        form.blowingSand(true);
        assertEquals(Wind.MOD_GALE, form.wind());
        assertTrue(form.valid());
        form.wind(Wind.LIGHT_GALE);
        assertEquals(List.of("PlanetaryConditionsDialog.invalid.sandsLost"), form.errors(Field.BLOWING_SAND));
        assertEquals(List.of("PlanetaryConditionsDialog.invalid.sandsLost"), form.errors(Field.WIND));
    }

    @Test
    void traceAtmosphereRejectsALightGale() {
        form.atmosphere(Atmosphere.TRACE);
        form.wind(Wind.LIGHT_GALE);
        assertEquals(List.of("PlanetaryConditionsDialog.invalid.traceLightGale"), form.errors(Field.ATMOSPHERE));
        assertFalse(form.valid());
    }

    @Test
    void shiftingWindRangeClampsTheWind() {
        form.shiftStrength(true);
        assertTrue(form.enabled(Field.WIND_FROM));
        form.wind(Wind.STORM);
        form.windTo(Wind.MOD_GALE);
        assertEquals(Wind.MOD_GALE, form.wind());
        form.windFrom(Wind.STRONG_GALE);
        assertEquals(Wind.MOD_GALE, form.windMin());
        assertEquals(Wind.STRONG_GALE, form.windMax());
        form.wind(Wind.CALM);
        assertEquals(List.of("PlanetaryConditionsDialog.invalid.windRange"), form.errors(Field.WIND));
        PlanetaryConditions result = form.result();
        assertEquals(Wind.MOD_GALE, result.getWind());
        assertEquals(Wind.MOD_GALE, result.getWindMin());
        assertEquals(Wind.STRONG_GALE, result.getWindMax());
        assertTrue(result.shiftingWindStrength());
    }

    @Test
    void numbersOutsideTheirRangesAreErrors() {
        form.temperature("250");
        form.gravity("12");
        assertEquals(List.of("PlanetaryConditionsDialog.invalid.tempRange"), form.errors(Field.TEMPERATURE));
        assertEquals(List.of("PlanetaryConditionsDialog.invalid.gravRange"), form.errors(Field.GRAVITY));
        form.temperature("20");
        form.gravity("0.5");
        assertTrue(form.valid());
    }

    @Test
    void resultCarriesEveryField() {
        form.emi(true);
        form.terrainAffected(false);
        form.windDirection(WindDirection.NORTH);
        form.gravity("1.5");
        form.temperature("not a number");
        PlanetaryConditions result = form.result();
        assertEquals(EMI.EMI, result.getEMI());
        assertFalse(result.isTerrainAffected());
        assertEquals(WindDirection.NORTH, result.getWindDirection());
        assertEquals(1.5f, result.getGravity());
        assertEquals(25, result.getTemperature());
    }
}
