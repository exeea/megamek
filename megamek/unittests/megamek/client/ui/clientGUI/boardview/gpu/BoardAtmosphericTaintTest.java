/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static megamek.client.ui.clientGUI.boardview.gpu.BoardAtmosphereTest.assertSameColor;
import static megamek.client.ui.clientGUI.boardview.gpu.BoardAtmosphereTest.assertSameLighting;

import java.util.HashSet;

import com.badlogic.gdx.graphics.Color;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.PlanetaryConditions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardAtmosphericTaintTest {
    @ParameterizedTest
    @EnumSource(AtmosphericTaint.class)
    void palettePreservesBrightnessAndSurfaceLightingAtEveryHour(AtmosphericTaint taint) {
        for (float clouds : new float[] { 0, 1 }) {
            for (int hour = 0; hour < 24; hour++) {
                var clear = settings(hour, clouds, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE);
                var tinted = settings(hour, clouds, Atmosphere.STANDARD, taint);
                var before = BoardAtmosphere.lighting(clear);
                for (float strength : new float[] { 0, 0.001f, 1, 2, 10 }) {
                    var after = BoardAtmosphere.lighting(tinted, BoardAtmosphere.MOONLIGHT_SHADOW_CONTRAST, strength);
                    assertEquals(before.direction(), after.direction());
                    assertSameColor(before.direct(), after.direct(), "Taint never changes the light");
                    assertSameColor(before.ambient(), after.ambient(), "Taint never changes the light");
                    assertEquals(before.saturation(), after.saturation());
                    assertBrightness(before.sky(), after.sky());
                    assertBrightness(before.horizon(), after.horizon());
                    assertLight(before.fog(), after.fog());
                    // The multiplicative grade may exceed one; its luminance must not change exposure.
                    assertEquals(luminance(before.tint()), luminance(after.tint()), 0.000001f);
                    float gradeShift = distance(before.tint(), after.tint());
                    if (strength == 0 || taint.isBreathable()) {
                        assertEquals(0, gradeShift, "Clear or untinted air must leave the grade alone");
                        assertSameLighting(before, after, "Clear or untinted air changes nothing");
                    } else {
                        assertTrue(gradeShift > 0, "Taint must grade the board, not only the sky and fog: " + taint);
                    }
                }
            }
        }
    }

    @Test
    void reducingStrengthContinuouslyRemovesHorizonAndFogTint() {
        var clear = BoardAtmosphere.lighting(settings(12, 0, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE));
        var tainted = settings(12, 0, Atmosphere.STANDARD, AtmosphericTaint.TOXIC_CAUSTIC);
        var full = BoardAtmosphere.lighting(tainted);
        var almostOff = BoardAtmosphere.lighting(tainted, BoardAtmosphere.MOONLIGHT_SHADOW_CONTRAST, 0.001f);
        assertTrue(distance(clear.horizon(), almostOff.horizon()) < distance(clear.horizon(), full.horizon()) * 0.002f);
        assertTrue(distance(clear.fog(), almostOff.fog()) < distance(clear.fog(), full.fog()) * 0.002f);
    }

    @Test
    void severityStrengthensDistinctPalettesWithoutOverpoweringTwilightOrNight() {
        var clear = BoardAtmosphere.lighting(settings(12, 0, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE));
        var palettes = new HashSet<Integer>();
        for (var taint : new AtmosphericTaint[] { AtmosphericTaint.TAINTED_CAUSTIC, AtmosphericTaint.TAINTED_POISON,
              AtmosphericTaint.TAINTED_FLAME, AtmosphericTaint.TOXIC_CAUSTIC, AtmosphericTaint.TOXIC_POISON,
              AtmosphericTaint.TOXIC_FLAME }) {
            var setting = settings(12, 0, Atmosphere.STANDARD, taint);
            var day = BoardAtmosphere.lighting(setting);
            assertTrue(palettes.add(day.horizon().toIntBits()), "Each type/severity has a distinct palette");
            assertTrue(distance(clear.horizon(), day.horizon()) > distance(clear.sky(), day.sky()),
                  "Taint is strongest near the horizon");
            var night = BoardAtmosphere.lighting(settings(0, 0, Atmosphere.STANDARD, taint));
            var clearNight = BoardAtmosphere.lighting(settings(0, 0, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE));
            assertTrue(distance(night.horizon(), clearNight.horizon()) < distance(day.horizon(), clear.horizon()));
            for (float hour : new float[] { 6, 6.75f, 17.25f, 18 }) {
                var twilight = BoardAtmosphere.lighting(settings(hour, 0, Atmosphere.STANDARD, taint));
                assertTrue(twilight.horizon().r > twilight.horizon().b * 1.8f,
                      "Dawn/dusk must retain their warm horizon for " + taint);
                assertTrue(twilight.sky().b > twilight.sky().r);
            }
        }
        AtmosphericTaint[] mild = { AtmosphericTaint.TAINTED_CAUSTIC, AtmosphericTaint.TAINTED_POISON,
              AtmosphericTaint.TAINTED_FLAME };
        AtmosphericTaint[] toxic = { AtmosphericTaint.TOXIC_CAUSTIC, AtmosphericTaint.TOXIC_POISON,
              AtmosphericTaint.TOXIC_FLAME };
        for (int i = 0; i < mild.length; i++) {
            var tainted = BoardAtmosphere.lighting(settings(12, 0, Atmosphere.STANDARD, mild[i]));
            var stronger = BoardAtmosphere.lighting(settings(12, 0, Atmosphere.STANDARD, toxic[i]));
            assertTrue(distance(clear.horizon(), stronger.horizon()) > distance(clear.horizon(), tainted.horizon()));
            assertTrue(distance(clear.tint(), stronger.tint()) > distance(clear.tint(), tainted.tint()),
                  "A toxic atmosphere must grade the board more than its tainted counterpart");
        }
    }

    @Test
    void pressureScalesThePaletteAndSpaceSuppressesItWithoutCreatingWeather() {
        var conditions = new PlanetaryConditions();
        float previous = -1;
        for (Atmosphere pressure : new Atmosphere[] { Atmosphere.VACUUM, Atmosphere.TRACE, Atmosphere.THIN,
              Atmosphere.STANDARD }) {
            conditions.setAtmosphere(pressure);
            conditions.setAtmosphericTaint(AtmosphericTaint.BREATHABLE);
            var clear = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            conditions.setAtmosphericTaint(AtmosphericTaint.TOXIC_CAUSTIC);
            var tainted = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            assertEquals(0, tainted.fog());
            assertEquals(0, tainted.haze());
            assertEquals(clear.clouds(), tainted.clouds());
            assertEquals(clear.effects(), tainted.effects());
            assertEquals(clear.groundLayerHeight(), tainted.groundLayerHeight());
            float change = distance(BoardAtmosphere.lighting(clear).horizon(), BoardAtmosphere.lighting(tainted).horizon());
            assertTrue(change > previous);
            previous = change;
            if (pressure.isVacuum()) {
                assertSameLighting(BoardAtmosphere.lighting(clear), BoardAtmosphere.lighting(tainted),
                      "Airless worlds have no air to taint");
            }
        }
        var taintedSpace = BoardAtmosphere.fromScenario(conditions, true, 0.5);
        conditions.setAtmosphericTaint(AtmosphericTaint.BREATHABLE);
        assertSameLighting(BoardAtmosphere.lighting(BoardAtmosphere.fromScenario(conditions, true, 0.5)),
              BoardAtmosphere.lighting(taintedSpace), "Space has no air to taint");
    }

    @Test
    void manualWeatherEditsPreserveTaintAndScenarioChangesKeepVisualWeatherOverrides() {
        var conditions = new PlanetaryConditions();
        conditions.setAtmosphericTaint(AtmosphericTaint.TAINTED_FLAME);
        var original = BoardAtmosphere.fromScenario(conditions, false, 0.5);
        var fogPreview = new BoardAtmosphere.Settings(original.hour(), 0.5f, 0.6f, original.groundLayerHeight(), 0.6f,
              original.exposure(), original.effects(), original.pressure(), original.temperature(), original.moonlight(), original.taint());
        conditions.setAtmosphericTaint(AtmosphericTaint.TOXIC_POISON);
        var next = BoardAtmosphere.fromScenario(conditions, false, 0.5);
        var followed = BoardAtmosphere.followScenario(fogPreview, original, next);
        assertNotEquals(original.taint(), followed.taint());
        assertEquals(next.taint(), followed.taint());
        assertEquals(fogPreview.fog(), followed.fog());
        assertEquals(fogPreview.hour(), followed.hour());
        assertEquals(AtmosphericTaint.TOXIC_POISON, conditions.getAtmosphericTaint());
    }

    @Test
    void strengthControlRejectsNonFiniteValuesAndClampsToItsRange() {
        assertThrows(IllegalArgumentException.class, () -> options(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> options(Float.POSITIVE_INFINITY));
        assertEquals(0, options(-1).taintStrength());
        assertEquals(3, options(3).taintStrength());
        assertEquals(10, options(50).taintStrength(), "The knob's full travel must be usable");
    }

    private static GpuAtmosphere.Options options(float strength) {
        return new GpuAtmosphere.Options(0, false, 0, 0, 0, 0, 0, BoardAtmosphere.MOONLIGHT_SHADOW_CONTRAST, strength);
    }

    private static BoardAtmosphere.Settings settings(float hour, float clouds, Atmosphere pressure, AtmosphericTaint taint) {
        return new BoardAtmosphere.Settings(hour, clouds, 0, 2, 0, 0, BoardAtmosphere.Effects.NONE, pressure, 25, true, taint);
    }

    private static void assertBrightness(Color before, Color after) {
        assertLight(before, after);
        for (float channel : new float[] { after.r, after.g, after.b }) {
            assertTrue(channel <= 1, "Display colors stay within the display range");
        }
    }

    /** Fog is linear light and may exceed one; the palette only keeps its luminance. */
    private static void assertLight(Color before, Color after) {
        assertEquals(luminance(before), luminance(after), 0.000001f);
        for (float channel : new float[] { after.r, after.g, after.b }) {
            assertTrue(Float.isFinite(channel) && channel >= 0);
        }
    }

    private static float luminance(Color color) {
        return 0.2126f * color.r + 0.7152f * color.g + 0.0722f * color.b;
    }

    private static float distance(Color a, Color b) {
        return Math.abs(a.r - b.r) + Math.abs(a.g - b.g) + Math.abs(a.b - b.b);
    }
}
