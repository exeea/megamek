/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.BlowingSand;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.planetaryConditions.Wind;
import megamek.common.planetaryConditions.WindDirection;
import org.junit.jupiter.api.Test;

class BoardAtmosphereTest {
    @Test
    void sunCrossesTheBoardAndNightKeepsCoolReadableLighting() {
        var morning = BoardAtmosphere.lighting(at(9));
        var evening = BoardAtmosphere.lighting(at(15));
        var noon = BoardAtmosphere.lighting(at(12));
        var night = BoardAtmosphere.lighting(at(0));
        assertTrue(morning.direction().x < 0 && evening.direction().x > 0);
        assertTrue(noon.direction().z < morning.direction().z, "Noon casts shorter shadows");
        assertTrue(night.ambient().b > night.ambient().r, "Night has a cool ambient fill");
        assertTrue(night.ambient().r > 0 && night.direct().b > 0, "Night retains ambient and moonlight");
        assertTrue(noon.direct().r > night.direct().r, "Daylight has a stronger warm directional component");
        assertSameLighting(BoardAtmosphere.lighting(at(0)), BoardAtmosphere.lighting(at(24)),
              "The clock wraps at midnight");
    }

    @Test
    void clearNoonIsTheLightUnitWithAWarmSunAndABlueSkyFill() {
        // Clear noon retains unit luminance. Its mildly warm combined white point is a declared lighting
        // choice; the illustrative sky must not wash every material with its much stronger blue chroma.
        var noon = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0, 2, 0, 0));
        for (float hour : new float[] { 6, 12, 18 }) {
            var light = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour, 0, 0, 2, 0, 0));
            String[] names = { "direct", "ambient", "sky", "horizon", "environmentSky", "environmentHorizon" };
            Color[] colors = { light.direct(), light.ambient(), light.sky(), light.horizon(),
                  light.environmentSky(), light.environmentHorizon() };
            for (int index = 0; index < names.length; index++) {
                Color color = colors[index];
                System.out.printf(java.util.Locale.ROOT,
                      "LIGHTING_PROBE hour=%.2f source=%s rgb=%.9f,%.9f,%.9f gain=%.9f%n",
                      hour, names[index], color.r, color.g, color.b, light.adaptation());
            }
        }
        assertEquals(1, luminance(noon.groundLight()), 0.005f);
        assertEquals(1.0186106f, noon.groundLight().r, .00001f);
        assertEquals(.9986379f, noon.groundLight().g, .00001f);
        assertEquals(.9586923f, noon.groundLight().b, .00001f);
        // This is a perceptual color check: a linear-channel ratio is not a display saturation threshold.
        float sun = GpuColor.display(noon.direct().b) / GpuColor.display(noon.direct().r);
        assertTrue(sun > .8f && sun < .95f, "The noon sun remains warm white in display color: B/R " + sun);
        assertTrue(noon.direct().r > noon.direct().g && noon.direct().g > noon.direct().b,
              "The directional source stays warm while the sky supplies the complementary blue fill");
        float fill = blueToRed(noon.ambient());
        assertTrue(fill > 1 && fill < 2, "Diffuse sky fill is blue without inheriting the backdrop's extreme cast: B/R " + fill);
    }

    @Test
    void daylightWhitePointIsSharedByIncidentSourcesAndLeavesTheNightPaletteUntouched() throws ReflectiveOperationException {
        for (float hour : new float[] { 0, 6, 9, 12, 18, 21 }) {
            for (float clouds : new float[] { 0, .5f, 1 }) {
                var settings = new BoardAtmosphere.Settings(hour, clouds, 0, 2, 0, 0);
                var before = uncalibrated(settings);
                var after = BoardAtmosphere.lighting(settings);
                if (before.daylight() == 0) {
                    assertSameLighting(before, after, "The established night palette stays unchanged");
                }
                Color[] original = { before.direct(), before.ambient(), before.environmentSky(), before.environmentHorizon() };
                Color[] calibrated = { after.direct(), after.ambient(), after.environmentSky(), after.environmentHorizon() };
                for (int source = 1; source < original.length; source++) {
                    assertEquals(calibrated[0].r / original[0].r, calibrated[source].r / original[source].r, .00001f,
                          "Direct, diffuse and reflected sky share one red calibration at " + hour);
                    assertEquals(calibrated[0].g / original[0].g, calibrated[source].g / original[source].g, .00001f);
                    assertEquals(calibrated[0].b / original[0].b, calibrated[source].b / original[source].b, .00001f);
                }
                // A white-point correction must not be repurposed as an exposure change away from noon.
                assertEquals(luminance(before.groundLight()), luminance(after.groundLight()),
                      luminance(before.groundLight()) * .015f, "Off-noon luminance remains close to the canonical source");
            }
        }
    }

    private static BoardAtmosphere.Lighting uncalibrated(BoardAtmosphere.Settings settings)
          throws ReflectiveOperationException {
        var lighting = BoardAtmosphere.class.getDeclaredMethod("lighting", BoardAtmosphere.Settings.class,
              float.class, float.class, boolean.class);
        lighting.setAccessible(true);
        return (BoardAtmosphere.Lighting) lighting.invoke(null, settings,
              BoardAtmosphere.MOONLIGHT_SHADOW_CONTRAST, BoardAtmosphere.DEFAULT_TAINT_STRENGTH, false);
    }

    @Test
    void aSinkingSunDimsAndWarmsTheLightUntilDusk() {
        var previous = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0, 2, 0, 0));
        for (int quarter = 49; quarter <= 72; quarter++) {
            float hour = quarter / 4f;
            var light = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour, 0, 0, 2, 0, 0));
            assertTrue(blueToRed(light.direct()) < blueToRed(previous.direct()),
                  "The sun warms as it sinks, at " + hour);
            assertTrue(luminance(light.groundLight()) <= luminance(previous.groundLight()),
                  "Level ground dims as the sun sinks, at " + hour);
            previous = light;
        }
        float dusk = blueToRed(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(17.5f, 0, 0, 2, 0, 0)).direct());
        assertTrue(dusk < 0.5f, "Dusk sunlight is golden: B/R " + dusk);
    }

    @Test
    void fullMoonIsSlightlyBrighterAndBlueWhileMoonlessRemainsReadableBelowIt() {
        var noon = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0, 2, 0, 0));
        var moon = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(0, 0, 0, 2, 0, 0));
        assertEquals(.04819409f, luminance(moon.groundLight()), .000001f,
              "The source is distinct from its adapted display level");
        assertEquals(6.168843f, moon.adaptation(), .00001f);
        float stops = log2(adaptedGround(moon) / adaptedGround(noon));
        assertEquals(-1.75f, stops, .00001f, "Full moon is one quarter-stop brighter than the former two-stop target");
        float blue = blueToRed(moon.direct());
        assertTrue(blue > 1.8f && blue < 1.9f, "Full moon has a deliberate blue directional source: B/R " + blue);
        assertTrue(blueToRed(moon.groundLight()) > 1.62f && blueToRed(moon.groundLight()) < 1.9f,
              "Combined moon and sky are bluer than the former full-moon B/R of 1.62");
        // The composite applies the scenario's exposure compensation on top of the light.
        var conditions = new PlanetaryConditions();
        conditions.setLight(Light.FULL_MOON);
        float fullMoon = displayedGround(BoardAtmosphere.fromScenario(conditions, false, 0.5));
        conditions.setLight(Light.MOONLESS);
        float moonless = displayedGround(BoardAtmosphere.fromScenario(conditions, false, 0.5));
        assertTrue(fullMoon > .25f && fullMoon < .32f, "Full moon is slightly brighter than its former .25 output");
        assertTrue(moonless > .23f && moonless < .25f,
              "Moonless is readable just below the former full moon, rather than the former .049 moonless level");
        assertTrue(moonless < fullMoon);
        conditions.setLight(Light.PITCH_BLACK);
        float pitch = displayedGround(BoardAtmosphere.fromScenario(conditions, false, 0.5));
        assertTrue(pitch > .04944679f && pitch < moonless, "Pitch black is above former moonless and below new moonless");
    }

    @Test
    void nightPresetsKeepASharedDimSkyWithoutInventingDirectionalLight() {
        var conditions = new PlanetaryConditions();
        for (Atmosphere pressure : new Atmosphere[] { Atmosphere.STANDARD, Atmosphere.THIN, Atmosphere.VACUUM }) {
            conditions.setAtmosphere(pressure);
            for (double time : new double[] { 0, .25, .5, .75, .999 }) {
                conditions.setLight(Light.FULL_MOON);
                var fullSettings = BoardAtmosphere.fromScenario(conditions, false, time);
                var full = BoardAtmosphere.lighting(fullSettings);
                conditions.setLight(Light.MOONLESS);
                var moonlessSettings = BoardAtmosphere.fromScenario(conditions, false, time);
                var moonless = BoardAtmosphere.lighting(moonlessSettings);
                conditions.setLight(Light.PITCH_BLACK);
                var blackSettings = BoardAtmosphere.fromScenario(conditions, false, time);
                var black = BoardAtmosphere.lighting(blackSettings);
                assertTrue(full.hasDirectLight());
                assertFalse(moonless.hasDirectLight());
                assertFalse(black.hasDirectLight());
                assertSameLighting(moonless, black, "Pitch black changes presentation, never the source");
                float fullGain = full.adaptation() * (float) Math.pow(2, fullSettings.exposure());
                float moonlessGain = moonless.adaptation() * (float) Math.pow(2, moonlessSettings.exposure());
                float blackGain = black.adaptation() * (float) Math.pow(2, blackSettings.exposure());
                assertTrue(displayedGround(fullSettings) > displayedGround(moonlessSettings));
                assertTrue(luminance(full.sky()) * fullGain > luminance(moonless.sky()) * moonlessGain,
                      "Removing the moon must not brighten the default visible sky at " + fullSettings.hour());
                assertTrue(luminance(full.horizon()) * fullGain > luminance(moonless.horizon()) * moonlessGain);
                assertEquals(moonlessGain * .5f, blackGain, .000001f,
                      "Pitch black retains a distinct one-stop presentation below moonless");
                if (pressure == Atmosphere.STANDARD && time == .5) {
                    assertEquals(.29730178, displayedGround(fullSettings), .00001);
                    assertEquals(.06370752, luminance(full.ambient()) * fullGain, .00001,
                          "Readable full-moon shadow fill retains directional contrast");
                    assertEquals(.24235836, displayedGround(moonlessSettings), .00001);
                    assertEquals(.12117918, displayedGround(blackSettings), .00001);
                }
            }
        }
    }

    @Test
    void moonlessFillRemainsContinuousThroughTwilight() {
        float previous = 0;
        for (int minute = 0; minute <= 24 * 60; minute++) {
            var settings = new BoardAtmosphere.Settings(minute / 60f, 0, 0, 2, 0, 0,
                  BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25, false);
            var light = BoardAtmosphere.lighting(settings);
            float fill = luminance(light.ambient());
            assertTrue(fill > 0 && Float.isFinite(fill));
            if (minute > 0) {
                assertTrue(Math.abs(log2(fill / previous)) < .12f,
                      "No ambient step on the one-minute tuning grid at " + settings.hour());
            }
            if (!light.sunlight()) { assertFalse(light.hasDirectLight()); }
            previous = fill;
        }
    }

    @Test
    void aLowMoonLightsWallsTurnedToItLessThanTheNoonSunDoes() {
        // The view adapts to the dim level ground under a low moon; walls facing it must still read as night.
        float noon = wallTurnedToTheLight(BoardAtmosphere.lighting(at(13)));
        for (int minute = 0; minute < 24 * 60; minute += 5) {
            var light = BoardAtmosphere.lighting(at(minute / 60f));
            if (!light.sunlight()) {
                assertTrue(wallTurnedToTheLight(light) < noon, "Moonlit walls outshine sunlit ones at " + minute / 60f);
            }
        }
    }

    /** A vertical wall facing the light's direction: half the sky and the whole beam. */
    private static float wallTurnedToTheLight(BoardAtmosphere.Lighting light) {
        float across = (float) Math.hypot(light.direction().x, light.direction().y);
        return (0.5f * luminance(light.ambient()) + across * luminance(light.direct())) * light.adaptation();
    }

    @Test
    void airlessWorldsKeepSunlightButHaveNoAtmosphericSkyFill() {
        var standard = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0, 2, 0, 0));
        for (Atmosphere pressure : Atmosphere.values()) {
            var light = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0, 2, 0, 0,
                  BoardAtmosphere.Effects.NONE, pressure));
            assertSameColor(standard.direct(), light.direct(), "Sunlight at 13:00, " + pressure.name());
            if (pressure.isVacuum()) {
                assertTrue(luminance(light.ambient()) < .001f, "A black airless sky cannot provide atmospheric fill");
            } else {
                assertSameColor(standard.ambient(), light.ambient(), "Sky fill at 13:00, " + pressure.name());
            }
        }
    }

    @Test
    void diffuseSkyMatchesTheCosineIntegralOfItsLightingEnvironment() {
        // Numerical hemisphere quadrature independent of the analytic .2/.8 coefficients used in production.
        for (float hour : new float[] { 0, 6, 9, 12, 18, 21 }) {
            for (float clouds : new float[] { 0, .5f, 1 }) {
                var light = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour, clouds, 0, 2, 0, 0));
                float[] sum = new float[3];
                for (int step = 0; step < 4096; step++) {
                    float z = (step + .5f) / 4096;
                    // Color.lerp clamps to one; radiance can exceed one, so integrate its channels directly.
                    float blend = (float) Math.sqrt(z);
                    Color sky = light.environmentSky(), horizon = light.environmentHorizon();
                    sum[0] += (horizon.r + (sky.r - horizon.r) * blend) * 2 * z / 4096;
                    sum[1] += (horizon.g + (sky.g - horizon.g) * blend) * 2 * z / 4096;
                    sum[2] += (horizon.b + (sky.b - horizon.b) * blend) * 2 * z / 4096;
                }
                assertEquals(sum[0], light.ambient().r, 0.000002f);
                assertEquals(sum[1], light.ambient().g, 0.000002f);
                assertEquals(sum[2], light.ambient().b, 0.000002f);
            }
        }
    }

    @Test
    void previewTuningAndTacticalKeepTheBoundedLightingEnvironment() {
        for (float hour : new float[] { 0, 6, 12, 18 }) {
            var original = BoardAtmosphere.lighting(at(hour));
            var tuned = original.tuned(.5f, BoardAtmosphere.DEFAULT_AMBIENT_SHARE, .4f, null);
            assertSameColor(original.sky(), tuned.sky(), "Lighting contribution does not recolor the visible sky");
            assertSameColor(original.horizon(), tuned.horizon(), "Lighting contribution does not recolor the horizon");
            assertSameColor(original.direct(), tuned.direct(), "Sky contribution leaves the directional source alone");
            Color[] before = { original.ambient(), original.environmentSky(), original.environmentHorizon() };
            Color[] after = { tuned.ambient(), tuned.environmentSky(), tuned.environmentHorizon() };
            for (int index = 0; index < before.length; index++) {
                assertEquals(before[index].r * .4f, after[index].r, .000001f);
                assertEquals(before[index].g * .4f, after[index].g, .000001f);
                assertEquals(before[index].b * .4f, after[index].b, .000001f);
            }
            assertEquals(.4f, tuned.environmentGain());
            assertEquals(.5f, tuned.shadowStrength());
        }
        var defaults = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        assertSameColor(defaults.environmentSky(), BoardAtmosphere.TACTICAL.environmentSky(),
              "Tactical must not replace bounded incident radiance with the illustrative backdrop");
        assertSameColor(defaults.environmentHorizon(), BoardAtmosphere.TACTICAL.environmentHorizon(),
              "Tactical preserves the same environment endpoints as its source lighting");
    }

    @Test
    void fogAndDustScatterTheLightThatReachesTheGround() {
        for (int quarter = 0; quarter < 96; quarter++) {
            var light = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(quarter / 4f, 0, 0, 2, 0, 0));
            Color ground = light.groundLight(), fog = light.fog();
            String at = "Fog takes the ground light's color at " + quarter / 4f;
            assertEquals(fog.r / ground.r, fog.b / ground.b, 0.0001f, at);
            assertEquals(fog.g / ground.g, fog.b / ground.b, 0.0001f, at);
            assertTrue(luminance(fog) < luminance(ground), "Fog is dimmer than the lit ground it veils");
        }
        var clear = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0.05f, 2, 0, 0));
        var sand = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0.05f, 2, 0, 0,
              new BoardAtmosphere.Effects(0, 0, 0, 0.6f, 0, 0, 0)));
        assertTrue(blueToRed(sand.fog()) < blueToRed(clear.fog()), "Blowing sand browns the fog");
    }

    @Test
    void cameraRelativeLightKeepsItsScreenDirectionAndLevelGroundBrightness() {
        GdxNativesLoader.load();
        for (float hour : new float[] { 0, 4, 6, 8, 12, 17, 18, 20 }) {
            var world = BoardAtmosphere.lighting(at(hour));
            Vector3 originalDirection = world.direction().cpy();
            Vector3 screenDirection = null;
            for (float tilt : new float[] { 0, 55, BoardCamera.MAX_TILT }) {
                for (int bearing = 0; bearing < 360; bearing += 60) {
                    var camera = new BoardCamera();
                    camera.camera.viewportWidth = 1280;
                    camera.camera.viewportHeight = 800;
                    camera.orbit(bearing, tilt);
                    var fixed = world.relativeTo(camera.camera);
                    Vector3 screen = fixed.direction().cpy().rot(camera.camera.view);
                    if (screenDirection == null) { screenDirection = screen; }
                    assertTrue(screenDirection.epsilonEquals(screen, 0.00001f),
                          "The light must stay fixed on screen through rotation, tilt and the overhead pole");
                    assertTrue(fixed.direction().z < -0.05f, "The source must remain above the terrain");
                    assertEquals(1, fixed.direction().len(), 0.00001f);
                    for (int channel = 0; channel < 3; channel++) {
                        float before = new float[] { world.direct().r, world.direct().g, world.direct().b }[channel];
                        float after = new float[] { fixed.direct().r, fixed.direct().g, fixed.direct().b }[channel];
                        assertEquals(before * -world.direction().z, after * -fixed.direction().z, 0.00001f);
                        assertTrue(Float.isFinite(after) && after >= 0,
                              "A steeper incidence raises direct light, unclipped");
                    }
                    assertSameColor(world.ambient(), fixed.ambient(), "The fixed light moves no fill");
                    assertEquals(world.adaptation(), fixed.adaptation(), .00001f, "Camera pose does not change exposure");
                    camera.pan(120, -90);
                    camera.zoom(0.8f);
                    assertSameLighting(fixed, world.relativeTo(camera.camera), "Pan and zoom must not move the light");
                }
            }
            assertEquals(originalDirection, world.direction(), "Camera presentation must not mutate the world lighting");
        }
    }

    @Test
    void cameraRelativeLightRemainsFiniteWhenFreeFlightLooksAboveTheHorizon() {
        GdxNativesLoader.load();
        for (float hour : new float[] { 0, 6, 12, 18 }) {
            var world = BoardAtmosphere.lighting(at(hour));
            var camera = new BoardCamera();
            camera.camera.viewportWidth = 1280;
            camera.camera.viewportHeight = 800;
            camera.setFirstPerson(true);
            for (int pitch = 0; pitch < 180; pitch++) {
                camera.look(13, 1);
                var light = world.relativeTo(camera.camera);
                assertTrue(light.direction().z <= -.04999f);
                assertEquals(1, light.direction().len(), .00001f);
                assertTrue(Float.isFinite(light.direct().r) && light.direct().r >= 0);
                assertTrue(Float.isFinite(light.direct().g) && light.direct().g >= 0);
                assertTrue(Float.isFinite(light.direct().b) && light.direct().b >= 0);
                assertSameColor(world.groundLight(), light.groundLight(), "Free flight preserves ground illumination");
                assertEquals(world.adaptation(), light.adaptation(), .00001f);
            }
        }
    }

    @Test
    void onlyFullMoonNightsHaveDirectionalMoonlightAndManualClockChangesPreserveThatChoice() {
        var conditions = new PlanetaryConditions();
        assertTrue(BoardAtmosphere.lighting(at(0)).hasDirectLight(), "Unspecified night previews default to full moon");
        for (Light category : new Light[] { Light.FULL_MOON, Light.MOONLESS, Light.PITCH_BLACK }) {
            conditions.setLight(category);
            var settings = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            boolean moon = category == Light.FULL_MOON;
            assertEquals(moon, settings.moonlight());
            assertEquals(moon, BoardAtmosphere.lighting(settings).hasDirectLight());
            var manualDay = new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, settings.exposure(),
                  settings.effects(), settings.pressure(), settings.temperature(), settings.moonlight());
            assertTrue(BoardAtmosphere.lighting(manualDay).hasDirectLight(), "Suppressing moonlight must not suppress the sun");
            var day = BoardAtmosphere.DEFAULTS;
            var followed = BoardAtmosphere.followScenario(day, day, settings);
            assertEquals(moon, BoardAtmosphere.lighting(followed).hasDirectLight());
            assertEquals(category, conditions.getLight());
        }
    }

    @Test
    void allClockPositionsHaveFiniteDownwardLightingWithoutAShadowCameraPole() {
        for (float clouds : new float[] { 0, 0.5f, 1 }) {
            for (int quarter = 0; quarter <= 96; quarter++) {
                var light = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(quarter / 4f, clouds, 0, 2, 0, 0));
                assertEquals(1, light.direction().len(), 0.0001);
                assertTrue(light.direction().z < -0.05f);
                assertTrue(Math.abs(light.direction().y) > 0.05f);
                String at = " at " + quarter / 4f + ", clouds " + clouds;
                for (float channel : new float[] { light.direct().r, light.direct().g, light.direct().b }) {
                    assertTrue(Float.isFinite(channel) && channel >= 0, "Direct light is finite" + at);
                }
                // AmbientCubemap clamps its colors to one, so a brighter fill would clip silently.
                for (float channel : new float[] { light.ambient().r, light.ambient().g, light.ambient().b }) {
                    assertTrue(Float.isFinite(channel) && channel >= 0 && channel < 1,
                          "Ambient fill stays below one" + at);
                }
            }
        }
    }

    @Test
    void everyScenarioTwilightChoiceHasAWarmHorizonAndWarmReadableGround() {
        var conditions = new PlanetaryConditions();
        conditions.setLight(Light.DUSK_DAWN);
        for (int sample = 0; sample < 256; sample++) {
            var settings = BoardAtmosphere.fromScenario(conditions, false, sample / 256.0);
            var light = BoardAtmosphere.lighting(settings);
            assertTrue(light.sunlight() && light.hasDirectLight(), "Every dawn/dusk choice uses a visible sun");
            assertTrue(light.horizon().r > light.horizon().b * 1.8f, "Dawn and dusk both need a warm horizon");
            assertTrue(light.sky().b > light.sky().r, "The upper sky retains its cool twilight gradient");
            float red = light.ambient().r - light.direction().z * light.direct().r;
            float blue = light.ambient().b - light.direction().z * light.direct().b;
            assertTrue(red > blue * 1.1f, "The board must share the horizon's warm light at " + settings.hour());
        }
    }

    @Test
    void displayedSkyColorsChangeContinuouslyThroughDayTwilightAndNight() {
        var previous = BoardAtmosphere.lighting(at(0));
        // Resolve the short source handover at ten-second intervals, as well as the broader sky palette.
        for (int step = 1; step <= 24 * 360; step++) {
            float hour = step / 360f;
            var next = BoardAtmosphere.lighting(at(hour));
            assertTrue(displayDistance(previous.sky(), previous.adaptation(), next.sky(), next.adaptation()) < 0.01f, "Sky discontinuity at " + hour);
            assertTrue(displayDistance(previous.horizon(), previous.adaptation(), next.horizon(), next.adaptation()) < 0.01f, "Horizon discontinuity at " + hour);
            assertTrue(colorDistance(previous.ambient(), next.ambient()) < 0.01f, "Ambient discontinuity at " + hour);
            previous = next;
        }
    }

    @Test
    void cloudCoverTintsTheSkyFromTheTopAtEveryTimeOfDay() throws ReflectiveOperationException {
        for (float hour : new float[] { 0, 5, 6, 6.75f, 12, 17.25f, 18, 19 }) {
            var clear = BoardAtmosphere.lighting(at(hour));
            var clearEnergy = uncalibrated(at(hour));
            float lastChange = 0;
            for (float cover : new float[] { 0.25f, 0.6f, 1 }) {
                var settings = new BoardAtmosphere.Settings(hour, cover, 0, 2.5f, 0, 0);
                var cloudy = BoardAtmosphere.lighting(settings);
                float overheadChange = colorDistance(clear.sky(), cloudy.sky());
                assertTrue(overheadChange > lastChange, "Every increase in cover must affect the sky");
                // Compare source energy before white-point calibration: its common RGB gains act on different
                // cloud chromaticities, so their final luminances need not be identical.
                assertEquals(luminance(clearEnergy.ambient()), luminance(uncalibrated(settings).ambient()), .000001f,
                      "Cloud palette changes redistribute the same canonical source energy at " + hour);
                lastChange = overheadChange;
            }
        }
        var vacuum = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(6, 1, 0, 2.5f, 0, 0,
              BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM));
        assertEquals(vacuum.sky(), vacuum.horizon(), "Airless worlds have no atmospheric twilight gradient");
        assertTrue(vacuum.sky().r < 0.01f && vacuum.sky().g < 0.01f && vacuum.sky().b < 0.01f);
    }

    private static float colorDistance(Color first, Color second) {
        return Math.abs(first.r - second.r) + Math.abs(first.g - second.g) + Math.abs(first.b - second.b);
    }

    private static float displayDistance(Color first, float firstGain, Color second, float secondGain) {
        return Math.max(Math.abs(displayChannel(first.r * firstGain) - displayChannel(second.r * secondGain)),
              Math.max(Math.abs(displayChannel(first.g * firstGain) - displayChannel(second.g * secondGain)),
                    Math.abs(displayChannel(first.b * firstGain) - displayChannel(second.b * secondGain))));
    }

    private static float displayChannel(float radiance) {
        // Compare visible continuity after the shared HDR shoulder; raw horizon radiance can exceed one.
        return GpuColor.display(Math.min(radiance, .6f)
              + .4f * (1 - (float) Math.exp(-Math.max(radiance - .6f, 0) / .4f)));
    }

    @Test
    void sourceDirectionColorAndVisibilityAgreeThroughoutTheSunMoonHandover() {
        var previous = BoardAtmosphere.lighting(at(0));
        int handovers = 0;
        for (int minute = 1; minute <= 24 * 60; minute++) {
            var settings = at(minute / 60f);
            var light = BoardAtmosphere.lighting(settings);
            var moonless = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(settings.hour(), settings.clouds(),
                  settings.fog(), settings.groundLayerHeight(), settings.haze(), settings.exposure(), settings.effects(),
                  settings.pressure(), settings.temperature(), false));
            if (light.sunlight()) {
                // A moon still up at dawn or dusk adds fill only; late view adaptation cannot alter the sun source.
                assertTrue(light.direction().epsilonEquals(moonless.direction(), 0.000001f));
                assertSameColor(light.direct(), moonless.direct(), "A moon does not alter canonical sunlight");
                assertTrue(luminance(light.groundLight()) >= luminance(moonless.groundLight()), "A moon adds light");
            } else {
                assertFalse(moonless.hasDirectLight(), "Afterglow must not invent sunlight or moonlight");
                assertTrue(light.direct().b >= light.direct().r, "A directional moon must use its cool light color");
            }
            if (light.sunlight() != previous.sunlight()) {
                handovers++;
                assertTrue(shadowStrength(light) < 0.02f && shadowStrength(previous) < 0.02f,
                      "Both sources must fade out before the shadow direction flips at " + minute / 60f);
            }
            previous = light;
        }
        assertEquals(2, handovers);
    }

    @Test
    void shadowsFadeOverTensOfMinutesOnBothSidesOfTheSunMoonHandover() {
        // The tuning slider steps by one minute: neither the shadows nor the brightness may jump between steps.
        var previous = BoardAtmosphere.lighting(at(0));
        List<Float> flips = new ArrayList<>();
        for (int minute = 1; minute <= 24 * 60; minute++) {
            float hour = minute / 60f;
            var light = BoardAtmosphere.lighting(at(hour));
            assertTrue(Math.abs(shadowStrength(light) - shadowStrength(previous)) < 0.03f,
                  "Shadows strengthen and weaken gradually at " + hour);
            assertTrue(Math.abs(log2(adaptedGround(light) / adaptedGround(previous))) < 0.05f,
                  "Level ground brightens and dims gradually at " + hour);
            if (light.sunlight() != previous.sunlight()) { flips.add(hour); }
            previous = light;
        }
        assertEquals(2, flips.size());
        for (float flip : flips) {
            for (int side : new int[] { -1, 1 }) {
                // Faint ten minutes from the flip and clear half an hour from it, from the sun and from the moon.
                float near = shadowStrength(BoardAtmosphere.lighting(at(flip + side * 10 / 60f)));
                float far = shadowStrength(BoardAtmosphere.lighting(at(flip + side * 30 / 60f)));
                assertTrue(near > 0.05f && near < 0.25f, "Faint shadows ten minutes from the flip at " + flip);
                assertTrue(far > 0.4f, "Clear shadows half an hour from the flip at " + flip);
            }
        }
    }

    @Test
    void daylightAndBothTwilightWindowsHaveDefinedShadowsWhileNightRetainsDiffuseFill() {
        for (float hour : new float[] { 9, 12, 13, 15.5f }) {
            float clear = shadowShare(BoardAtmosphere.lighting(at(hour)));
            assertTrue(clear > 0.15f && clear < 0.30f,
                  "Clear daylight keeps deep shadows with a readable sky fill at " + hour + "; shadow/lit = " + clear);
        }
        for (float hour : new float[] { 6, 6.25f, 6.5f, 6.75f, 17.25f, 17.5f, 17.75f, 18 }) {
            var light = BoardAtmosphere.lighting(at(hour));
            float shade = shadowShare(light);
            // The scenario's windows reach the horizon, where the sun's shadows begin to fade into the handover.
            float deepest = hour == 6 || hour == 18 ? 0.7f : 0.55f;
            assertTrue(shade > 0.30f && shade < deepest,
                  "Warm twilight needs visible cast shadows and readable fill at " + hour + "; shadow/lit = " + shade);
            float length = (float) Math.hypot(light.direction().x, light.direction().y) / -light.direction().z;
            assertTrue(length > 4, "Twilight shadows must stretch beyond four times the caster height");
            assertTrue(hour < 12 ? light.direction().x < 0 : light.direction().x > 0,
                  "Dawn and dusk must cast shadows in opposite directions");
        }
        float moonShade = shadowShare(BoardAtmosphere.lighting(at(0)));
        assertEquals(3f / 14, moonShade, .00001f, "Full Moon keeps readable fill and a stronger directional beam");
    }

    @Test
    void moonContrastPreservesLitGroundAndOnlyAffectsTheVisibleMoon() {
        for (float hour : new float[] { 0, 4, 5.5f, 6, 12, 18, 18.5f, 20, 23 }) {
            var settings = at(hour);
            var original = BoardAtmosphere.lighting(settings, 0);
            float previousShade = shadowShare(original);
            for (float contrast : new float[] { BoardAtmosphere.MOONLIGHT_SHADOW_CONTRAST, 0.5f, 1 }) {
                var light = BoardAtmosphere.lighting(settings, contrast);
                if (light.sunlight()) {
                    assertSameLighting(original, light, "Moon contrast must not change daylight or dawn/dusk");
                } else {
                    assertTrue(shadowShare(light) <= previousShade + 0.00001f, "Increasing contrast must strengthen shadows");
                    assertTrue(shadowShare(light) < shadowShare(original) - 0.02f);
                    previousShade = shadowShare(light);
                    assertTrue(light.direct().b > light.direct().r, "Moonlight retains its cool color");
                }
                assertEquals(luminance(original.groundLight()), luminance(light.groundLight()), 0.00001f,
                      "Moving energy from sky to moon keeps lit-ground luminance");
                if (luminance(light.ambient()) > 0) {
                    assertEquals(blueToRed(original.sky()), blueToRed(light.sky()), 0.00001f,
                          "The shared sky becomes dimmer without changing palette chromaticity");
                    assertEquals(blueToRed(original.horizon()), blueToRed(light.horizon()), 0.00001f);
                } else {
                    assertEquals(0, luminance(light.sky()));
                    assertEquals(0, luminance(light.horizon()));
                }
                for (float channel : new float[] { light.direct().r, light.direct().g, light.direct().b }) {
                    assertTrue(Float.isFinite(channel) && channel >= 0);
                }
                for (float channel : new float[] { light.ambient().r, light.ambient().g, light.ambient().b }) {
                    assertTrue(Float.isFinite(channel) && channel >= 0 && channel < 1);
                }
                var moonless = new BoardAtmosphere.Settings(hour, 0, 0, 2, 0, 0,
                      BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25, false);
                assertSameLighting(BoardAtmosphere.lighting(moonless, 0), BoardAtmosphere.lighting(moonless, contrast),
                      "Moonless and Pitch Black must retain their original lighting");
            }
        }
    }

    private static float shadowShare(BoardAtmosphere.Lighting light) {
        return luminance(light.ambient()) / luminance(light.groundLight());
    }

    /** The share of the light on lit level ground that casts shadows. */
    private static float shadowStrength(BoardAtmosphere.Lighting light) {
        return 1 - shadowShare(light);
    }

    @Test
    void levelGroundStaysWithinThreeStopsOfNoonAroundTheClock() {
        // Cloud cover leaves this light alone (cloudOpenings...); GpuAtmosphereSmokeTest renders overcast twilight.
        for (int quarter = 0; quarter < 96; quarter++) {
            var light = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(quarter / 4f, 0, 0, 2, 0, 0));
            float stops = log2(adaptedGround(light));
            assertTrue(stops >= -3, "No dark gap in the sun/moon handover at " + quarter / 4f + ": level ground "
                  + stops + " stops below noon");
        }
    }

    @Test
    void weatherPresetsUseRealScenarioFogAndCloudCoverage() {
        var clear = AtmospherePreset.CLEAR.settings(0.5);
        assertEquals(0, clear.clouds(), "Clear must remove all cloud cover");
        assertEquals(0, clear.fog());
        assertEquals(0, clear.haze());
        var conditions = new PlanetaryConditions();
        conditions.setFog(Fog.FOG_LIGHT);
        assertEquals(BoardAtmosphere.fromScenario(conditions, false, 0.5), AtmospherePreset.LIGHT_FOG.settings(0.5));
        conditions.setFog(Fog.FOG_HEAVY);
        assertEquals(BoardAtmosphere.fromScenario(conditions, false, 0.5), AtmospherePreset.HEAVY_FOG.settings(0.5));
    }

    @Test
    void cloudOpeningsRetainClearSkyIncidentLightWithoutGlobalDoubleAttenuation() throws ReflectiveOperationException {
        for (int hour = 0; hour < 24; hour++) {
            var settings = new BoardAtmosphere.Settings(hour, 0, 0, 2.5f, 0, 0);
            var clear = BoardAtmosphere.lighting(settings);
            var clearEnergy = uncalibrated(settings);
            for (float cover : new float[] { 0.25f, 0.6f, 1 }) {
                var cloudySettings = new BoardAtmosphere.Settings(hour, cover, 0, 2.5f, 0, 0);
                var cloudy = BoardAtmosphere.lighting(cloudySettings);
                assertSameColor(clear.direct(), cloudy.direct(),
                      "The spatial cloud integral handles sun/moon attenuation");
                assertEquals(luminance(clearEnergy.ambient()), luminance(uncalibrated(cloudySettings).ambient()),
                      .000001f, "Clouds retain canonical source energy before the shared white-point calibration");
                assertSameColor(clear.tint(), cloudy.tint(), "Cloud cover does not grade the board");
                assertEquals(clear.saturation(), cloudy.saturation());
            }
        }
    }

    @Test
    void liquidRainDrivesWetnessOnlyInAppropriatePlanetaryConditions() {
        PlanetaryConditions conditions = new PlanetaryConditions();
        conditions.setWeather(Weather.DOWNPOUR);
        var rainy = BoardAtmosphere.fromScenario(conditions, false, 0.5);
        assertEquals(1, BoardAtmosphere.wetness(rainy));
        conditions.setTemperature(-5);
        var frozen = BoardAtmosphere.fromScenario(conditions, false, 0.5);
        assertEquals(0, BoardAtmosphere.wetness(frozen));
        assertFalse(BoardAtmosphere.permitsWetness(frozen));
        assertEquals(-5, BoardAtmosphere.followScenario(rainy, rainy, frozen).temperature());
        conditions.setTemperature(25);
        for (Atmosphere pressure : new Atmosphere[] { Atmosphere.VACUUM, Atmosphere.TRACE, Atmosphere.THIN }) {
            conditions.setAtmosphere(pressure);
            assertEquals(0, BoardAtmosphere.wetness(BoardAtmosphere.fromScenario(conditions, false, 0.5)));
        }
        conditions.setAtmosphere(Atmosphere.STANDARD);
        assertEquals(0, BoardAtmosphere.wetness(BoardAtmosphere.fromScenario(conditions, true, 0.5)));
        conditions.setWeather(Weather.HEAVY_SNOW);
        assertEquals(0, BoardAtmosphere.wetness(BoardAtmosphere.fromScenario(conditions, false, 0.5)));
    }

    @Test
    void settingsRejectNonFiniteValuesAndBoundShaderInputs() {
        assertThrows(IllegalArgumentException.class,
              () -> new BoardAtmosphere.Settings(Float.NaN, 0, 0, 1, 0, 0));
        var bounded = new BoardAtmosphere.Settings(-1, 2, -1, 0, 3, 8);
        assertEquals(23, bounded.hour());
        assertEquals(1, bounded.clouds());
        assertEquals(0, bounded.fog());
        assertEquals(1.0f, bounded.groundLayerHeight());
        assertEquals(2, bounded.exposure());
        assertThrows(IllegalArgumentException.class,
              () -> new BoardAtmosphere.Effects(0, 0, 0, 0, 0, Float.POSITIVE_INFINITY, 0));
        var effects = new BoardAtmosphere.Effects(2, -1, 3, -4, 5, 6, -60);
        assertEquals(new BoardAtmosphere.Effects(1, 0, 1, 0, 1, 1, 300), effects);
    }

    @Test
    void scenarioLightCategoriesChooseDayTwilightOrReadableNight() {
        PlanetaryConditions conditions = new PlanetaryConditions();
        var initial = BoardAtmosphere.fromScenario(conditions, false, 0.5);
        assertEquals(initial, AtmospherePreset.CLEAR.settings(0.5));
        for (Light light : Light.values()) {
            conditions.setLight(light);
            var settings = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            float daylight = BoardAtmosphere.lighting(settings).daylight();
            if (light == Light.DUSK_DAWN) {
                assertTrue(daylight > 0 && daylight < 1, "Dusk must use the twilight transition");
            } else if (light.isDuskOrFullMoonOrMoonlessOrPitchBack()) {
                assertEquals(0, daylight);
                float expectedExposure = switch (light) {
                    case MOONLESS -> 0;
                    case PITCH_BLACK -> -1.0f;
                    default -> 0;
                };
                assertEquals(expectedExposure, settings.exposure(), "Scenario exposure for " + light);
            } else {
                assertEquals(1, daylight);
            }
            assertEquals(light, conditions.getLight(), "Visual initialization must not alter game conditions");
        }
    }

    @Test
    void sampledTimesSpanTheConditionWindowOnTheTuningGridAndRetainItsLighting() {
        var conditions = new PlanetaryConditions();
        for (Light category : Light.values()) {
            conditions.setLight(category);
            var hours = new HashSet<Float>();
            for (int index = 0; index < 256; index++) {
                double sample = index / 256.0;
                var settings = BoardAtmosphere.fromScenario(conditions, false, sample);
                float hour = settings.hour();
                hours.add(hour);
                assertEquals(Math.round(hour * 60), hour * 60, .001f, "Chosen times must fit the slider's one-minute grid");
                float daylight = BoardAtmosphere.lighting(settings).daylight();
                if (category.isDuskDawn()) {
                    assertTrue((hour >= 6 && hour <= 6.75f) || (hour >= 17.25f && hour <= 18),
                          "Twilight must use the dawn or sunset window, but was " + hour);
                    assertTrue(daylight > 0 && daylight < 1, "Every twilight choice stays within the transition");
                } else if (category.isFullMoonOrMoonlessOrPitchBack()) {
                    assertTrue(hour >= 20 || hour <= 4, "The night window crosses midnight");
                    assertEquals(0, daylight);
                } else {
                    assertTrue(hour >= 9 && hour <= 15.5f, "Daylight must use the 09:00-15:30 window, but was " + hour);
                    assertEquals(1, daylight);
                }
                assertEquals(settings, BoardAtmosphere.fromScenario(conditions, false, sample),
                      "Reusing the view's sample must not reroll the time");
            }
            assertTrue(hours.size() > 2, "Each lighting category must offer varied times");
            if (category.isDuskDawn()) {
                assertTrue(hours.stream().anyMatch(value -> value < 12), "Dawn must be reachable");
                assertTrue(hours.stream().anyMatch(value -> value > 12), "Sunset must be reachable");
            }
            if (category.isFullMoonOrMoonlessOrPitchBack()) {
                // The window's first minute, its middle and its last minute: 20:00, midnight and 04:00.
                assertEquals(20, BoardAtmosphere.fromScenario(conditions, false, 0).hour(), .001f);
                assertEquals(0, BoardAtmosphere.fromScenario(conditions, false, .5).hour(), .001f);
                assertEquals(4, BoardAtmosphere.fromScenario(conditions, false, Math.nextDown(1.0)).hour(), .001f);
            }
        }
        for (double invalid : new double[] { -0.1, 1, Double.NaN, Double.POSITIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> BoardAtmosphere.fromScenario(conditions, false, invalid));
        }
    }

    @Test
    void everyScenarioWeatherSelectsTheAppropriateEffectsAndClearRemovesThem() {
        PlanetaryConditions conditions = new PlanetaryConditions();
        for (Weather weather : Weather.values()) {
            conditions.setWeather(weather);
            var settings = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            var effects = settings.effects();
            assertEquals(EnumSet.of(Weather.LIGHT_RAIN, Weather.MOD_RAIN, Weather.HEAVY_RAIN, Weather.GUSTING_RAIN,
                        Weather.DOWNPOUR, Weather.SLEET, Weather.ICE_STORM, Weather.LIGHTNING_STORM).contains(weather),
                  effects.rain() > 0, "Rain for " + weather);
            assertEquals(EnumSet.of(Weather.LIGHT_SNOW, Weather.MOD_SNOW, Weather.SNOW_FLURRIES, Weather.HEAVY_SNOW,
                  Weather.SLEET).contains(weather), effects.snow() > 0, "Snow for " + weather);
            assertEquals(weather.isLightHail() || weather.isHeavyHail() || weather.isIceStorm(), effects.hail() > 0,
                  "Hail for " + weather);
            assertEquals(weather.isLightningStorm(), effects.lightning() > 0);
            assertEquals(!weather.isClear(), settings.clouds() > 0);
            assertFalse(AtmospherePreset.CLEAR.settings(0.5).effects().hasParticles());
            assertEquals(0, AtmospherePreset.CLEAR.settings(0.5).effects().lightning());
            assertEquals(weather, conditions.getWeather());
        }
        conditions.setWeather(Weather.LIGHT_RAIN);
        float lightRain = BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().rain();
        conditions.setWeather(Weather.DOWNPOUR);
        assertTrue(BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().rain() > lightRain);
        assertEquals(1, BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().rain(), "Downpour uses full density");
        conditions.setWeather(Weather.LIGHT_SNOW);
        float lightSnow = BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().snow();
        conditions.setWeather(Weather.HEAVY_SNOW);
        assertTrue(BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().snow() > lightSnow);
        assertEquals(1, BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().snow());
        conditions.setWeather(Weather.HEAVY_HAIL);
        assertEquals(1, BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().hail());
    }

    @Test
    void pressureSpaceFogAndEffectiveWindConstrainScenarioWeather() {
        PlanetaryConditions conditions = new PlanetaryConditions();
        conditions.setWeather(Weather.HEAVY_RAIN);
        conditions.setFog(Fog.FOG_HEAVY);
        conditions.setWind(Wind.STORM);
        conditions.setWindDirection(WindDirection.SOUTHWEST);
        conditions.setBlowingSand(BlowingSand.BLOWING_SAND);
        for (Atmosphere pressure : Atmosphere.values()) {
            conditions.setAtmosphere(pressure);
            var settings = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            boolean wet = pressure.isDenserThan(Atmosphere.THIN);
            assertEquals(wet, settings.effects().rain() > 0);
            assertEquals(wet, settings.fog() > 0);
            assertEquals(!pressure.isVacuum(), settings.effects().sand() > 0);
            assertEquals(!pressure.isVacuum(), settings.effects().wind() > 0);
            var space = BoardAtmosphere.fromScenario(conditions, true, 0.5);
            assertFalse(space.effects().hasParticles());
            assertEquals(0, space.clouds());
            assertEquals(0, space.fog());
            assertEquals(0, space.haze());
            assertEquals(0, space.effects().sand());
            assertEquals(0, space.effects().wind());
        }
        conditions.setAtmosphere(Atmosphere.STANDARD);
        assertEquals(60, BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().windDirection());
        conditions.setWind(Wind.LIGHT_GALE);
        assertEquals(0, BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().sand(),
              "Use the game's effective blowing-sand state, not the enabled flag alone");
        conditions.setWindDirection(WindDirection.RANDOM);
        assertEquals(0, BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().wind(),
              "Cloud cover must not introduce wind when the scenario direction is unresolved");
        assertEquals(WindDirection.RANDOM, conditions.getWindDirection(), "Visual drift must not resolve game wind rolls");
        conditions.setWeather(Weather.CLEAR);
        conditions.setFog(Fog.FOG_NONE);
        assertEquals(0, BoardAtmosphere.fromScenario(conditions, false, 0.5).effects().wind());
        conditions.setWind(Wind.STORM);
        var blowingSand = BoardAtmosphere.fromScenario(conditions, false, 0.5);
        assertTrue(blowingSand.effects().sand() > 0);
        assertEquals(BoardAtmosphere.MIN_SAND_FOG, blowingSand.fog(),
              "Blowing sand must keep a minimum ground fog even with fog none");
        assertEquals(0, blowingSand.haze(), "Sand has its own bounded veil and adds no haze");
    }

    @Test
    void zeroGravityOverridesPressureAndAllWeatherInLocalPreviews() {
        var effects = new BoardAtmosphere.Effects(1, 1, 1, 1, 1, 1, 135);
        for (Atmosphere pressure : Atmosphere.values()) {
            var settings = new BoardAtmosphere.Settings(13, 1, 1, 2, 1, .5f, effects, pressure, 25, true,
                  AtmosphericTaint.TOXIC_POISON, 0);
            assertVacuumWeather(settings);
            assertEquals(13, settings.hour());
            assertEquals(.5f, settings.exposure());
        }
        var positive = new BoardAtmosphere.Settings(13, 1, 1, 2, 1, .5f, effects, Atmosphere.STANDARD, 25, true,
              AtmosphericTaint.TOXIC_POISON, .01f);
        assertEquals(Atmosphere.STANDARD, positive.pressure(), "Positive gravity still permits an atmosphere");
        assertEquals(effects, positive.effects());
        assertEquals(1, positive.clouds());
        assertEquals(1, positive.fog());
        assertEquals(1, positive.haze());
        assertEquals(1, BoardAtmosphere.wetness(positive));
    }

    @Test
    void zeroGravitySuppressesEveryScenarioWeatherWithoutChangingTheScenario() {
        var conditions = new PlanetaryConditions();
        conditions.setFog(Fog.FOG_HEAVY);
        conditions.setWind(Wind.STORM);
        conditions.setWindDirection(WindDirection.SOUTHWEST);
        conditions.setBlowingSand(BlowingSand.BLOWING_SAND);
        for (Weather weather : Weather.values()) {
            conditions.setWeather(weather);
            conditions.setGravity(1);
            var before = BoardAtmosphere.fromScenario(conditions, false, .5);
            conditions.setGravity(0);
            var zero = BoardAtmosphere.fromScenario(conditions, false, .5);
            assertVacuumWeather(zero);
            assertEquals(zero, BoardAtmosphere.followScenario(before, before, zero));
            assertEquals(Atmosphere.STANDARD, conditions.getAtmosphere());
            assertEquals(weather, conditions.getWeather());
            assertEquals(Fog.FOG_HEAVY, conditions.getFog());
            assertEquals(Wind.STORM, conditions.getWind());
            conditions.setGravity(1);
            var restored = BoardAtmosphere.fromScenario(conditions, false, .5);
            assertEquals(before, restored);
            assertEquals(restored, BoardAtmosphere.followScenario(zero, zero, restored));
        }
    }

    private static void assertVacuumWeather(BoardAtmosphere.Settings settings) {
        assertEquals(Atmosphere.VACUUM, settings.pressure());
        assertEquals(BoardAtmosphere.Effects.NONE, settings.effects());
        assertEquals(0, settings.clouds());
        assertEquals(0, settings.fog());
        assertEquals(0, settings.haze());
        assertEquals(0, BoardAtmosphere.wetness(settings));
        assertFalse(BoardAtmosphere.permitsWetness(settings));
    }

    @Test
    void higherPressureLowersBothFogLayersWithoutChangingWeatherOrLighting() {
        PlanetaryConditions conditions = new PlanetaryConditions();
        conditions.setLight(Light.DUSK_DAWN);
        conditions.setWeather(Weather.HEAVY_RAIN);
        for (Fog fog : new Fog[] { Fog.FOG_LIGHT, Fog.FOG_HEAVY }) {
            conditions.setFog(fog);
            conditions.setAtmosphere(Atmosphere.STANDARD);
            var standard = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            conditions.setAtmosphere(Atmosphere.HIGH);
            var high = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            conditions.setAtmosphere(Atmosphere.VERY_HIGH);
            var veryHigh = BoardAtmosphere.fromScenario(conditions, false, 0.5);
            assertEquals(BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, standard.groundLayerHeight());
            assertEquals(standard.groundLayerHeight() - 0.75f, high.groundLayerHeight());
            assertEquals(Math.max(BoardAtmosphere.MIN_GROUND_LAYER_HEIGHT, high.groundLayerHeight() - 0.75f),
                  veryHigh.groundLayerHeight());
            assertEquals(BoardAtmosphere.MIN_GROUND_LAYER_HEIGHT, veryHigh.groundLayerHeight());
            for (var compressed : new BoardAtmosphere.Settings[] { high, veryHigh }) {
                assertEquals(standard.fog(), compressed.fog());
                assertEquals(standard.haze(), compressed.haze());
                assertEquals(standard.hour(), compressed.hour());
                assertEquals(standard.exposure(), compressed.exposure());
                assertEquals(standard.clouds(), compressed.clouds());
                assertEquals(standard.effects(), compressed.effects());
            }
        }
    }

    @Test
    void fogAddsCloudCoverWithoutReplacingPrecipitationClouds() {
        PlanetaryConditions conditions = new PlanetaryConditions();
        assertEquals(0, BoardAtmosphere.fromScenario(conditions, false, 0.5).clouds());
        conditions.setFog(Fog.FOG_LIGHT);
        float lightCover = BoardAtmosphere.fromScenario(conditions, false, 0.5).clouds();
        assertTrue(lightCover > 0);
        conditions.setFog(Fog.FOG_HEAVY);
        float heavyCover = BoardAtmosphere.fromScenario(conditions, false, 0.5).clouds();
        assertTrue(heavyCover > lightCover);
        conditions.setWeather(Weather.HEAVY_RAIN);
        conditions.setFog(Fog.FOG_NONE);
        float rainCover = BoardAtmosphere.fromScenario(conditions, false, 0.5).clouds();
        assertTrue(rainCover > heavyCover);
        conditions.setFog(Fog.FOG_HEAVY);
        assertEquals(rainCover, BoardAtmosphere.fromScenario(conditions, false, 0.5).clouds(),
              "Denser precipitation clouds still win over fog clouds");
        conditions.setAtmosphere(Atmosphere.THIN);
        assertEquals(0, BoardAtmosphere.fromScenario(conditions, false, 0.5).clouds(),
              "Thin atmospheres suppress fog and its cloud cover");
    }

    @Test
    void adaptationMetersTheFinalVacuumMixtureInsteadOfDiscardedAtmosphericFill() {
        var day = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0, 2, 0, 0));
        var vacuumDay = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0, 2, 0, 0,
              BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM));
        assertSameColor(day.direct(), vacuumDay.direct(), "Pressure does not change canonical noon sunlight");
        assertEquals(1, day.adaptation(), .000001f);
        assertTrue(luminance(vacuumDay.ambient()) < .001f, "Metering adds no atmospheric light in vacuum");
        assertTrue(vacuumDay.adaptation() > day.adaptation(), "The final airless mixture has less sky light");
        assertEquals(Math.pow(luminance(vacuumDay.groundLight()), -.6), vacuumDay.adaptation(), .000001,
              "Exposure is based on the final vacuum source, including its actual sky and white point");
        assertTrue(adaptedGround(vacuumDay) < adaptedGround(day), "Partial adaptation retains the darker mixture");

        var conditions = new PlanetaryConditions();
        conditions.setAtmosphere(Atmosphere.VACUUM);
        for (Light category : new Light[] { Light.FULL_MOON, Light.MOONLESS, Light.PITCH_BLACK }) {
            conditions.setLight(category);
            var settings = BoardAtmosphere.fromScenario(conditions, false, .5);
            var lighting = BoardAtmosphere.lighting(settings);
            assertEquals(category == Light.FULL_MOON, lighting.hasDirectLight());
            assertTrue(lighting.adaptation() > 1 && lighting.adaptation() <= Math.pow(112, .6) + .0001);
            if (category != Light.FULL_MOON) {
                assertEquals(Math.pow(112, .6), lighting.adaptation(), .0001,
                      "Almost-black vacuum is bounded by the established moonless exposure range");
                assertTrue(displayedGround(settings) < .004, "The meter floor does not invent sky fill");
            }
        }
    }

    @Test
    void zeroLightHasFiniteBoundedAdaptationWithoutChangingAnySourceChannel() {
        // Keep the established 1/112-noon metering floor even when the physical night source is dimmer.
        double maximum = Math.pow(112, .6);
        for (float value : new float[] { 0, Float.MIN_NORMAL, .000001f, .005f }) {
            Color source = new Color(value, value, value, .25f);
            float gain = BoardAtmosphere.adaptation(source);
            assertEquals(maximum, gain, .0001);
            assertTrue(Float.isFinite(gain));
            assertEquals(value, source.r);
            assertEquals(value, source.g);
            assertEquals(value, source.b);
            assertEquals(.25f, source.a, "Adaptation never mutates coverage or stored source light");
        }
        assertEquals(Math.pow(.7, -.6), BoardAtmosphere.adaptation(new Color(.7f, .7f, .7f, 1)), .000001,
              "A manual neutral environment is metered from its own ambient level, not default daylight");
        assertEquals(1, BoardAtmosphere.adaptation(Color.WHITE), .000001);
        // A bound on output error, not a nearest-rounding assumption: one adjacent RGBA16F subnormal step.
        double maximumDisplayError = Math.scalb(1.0, -19) * maximum * 4 * 12.92 * 255;
        assertTrue(maximumDisplayError < .5, "The adaptation cap plus maximum EV preserves dark half-float resolution");
    }

    @Test
    void exposureCompensationChangesOnlyTheCompletedSceneNotItsSourcesOrAdaptation() {
        for (Atmosphere pressure : new Atmosphere[] { Atmosphere.STANDARD, Atmosphere.VACUUM }) {
            for (float hour : new float[] { 0, 6, 12, 18 }) {
                var referenceSettings = new BoardAtmosphere.Settings(hour, .7f, 0, 2, 0, 0,
                      BoardAtmosphere.Effects.NONE, pressure);
                var reference = BoardAtmosphere.lighting(referenceSettings);
                for (float ev : new float[] { -2, -.6f, 1, 2 }) {
                    var settings = new BoardAtmosphere.Settings(hour, .7f, 0, 2, 0, ev,
                          BoardAtmosphere.Effects.NONE, pressure);
                    var lighting = BoardAtmosphere.lighting(settings);
                    assertSameLighting(reference, lighting, "EV must not alter a source or its metering");
                    assertEquals(reference.adaptation(), lighting.adaptation());
                    assertEquals(Math.pow(2, ev), displayedGround(settings) / displayedGround(referenceSettings), .000001);
                }
            }
        }
        var defaultLight = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        assertSameColor(defaultLight.groundLight(), BoardAtmosphere.TACTICAL.groundLight(),
              "The tactical direction preserves its source illumination");
        assertEquals(defaultLight.adaptation(), BoardAtmosphere.TACTICAL.adaptation(), .000001f);
    }

    @Test
    void finalSourceAdaptationRemainsBoundedAndContinuousAcrossAirlessSunMoonHandover() {
        double maximum = Math.pow(112, .6);
        for (boolean moon : new boolean[] { false, true }) {
            for (int minute = 0; minute <= 24 * 60; minute++) {
                var settings = new BoardAtmosphere.Settings(minute / 60f, 0, 0, 2, 0, 0,
                      BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM, 25, moon);
                var light = BoardAtmosphere.lighting(settings);
                float gain = light.adaptation();
                assertTrue(Float.isFinite(gain) && gain > 0 && gain <= maximum + .0001);
            }
            // Both source directions flip at altitude -.09. Test convergence around that boundary,
            // rather than requiring a slow visible fade when the last real light disappears in vacuum.
            float offset = (float) (Math.asin(.09) * 12 / Math.PI);
            for (float handover : new float[] { 6 - offset, 18 + offset }) {
                float wide = vacuumGainDifference(handover, .001f, moon);
                float narrow = vacuumGainDifference(handover, .0001f, moon);
                assertTrue(narrow <= wide * .2f + .00001f,
                      "Exposure converges across the source handover rather than jumping between directions");
            }
        }
    }

    private static float vacuumGainDifference(float hour, float halfWidth, boolean moon) {
        var before = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour - halfWidth, 0, 0, 2, 0, 0,
              BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM, 25, moon));
        var after = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour + halfWidth, 0, 0, 2, 0, 0,
              BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM, 25, moon));
        return Math.abs(after.adaptation() - before.adaptation());
    }


    private static BoardAtmosphere.Settings at(float hour) {
        return new BoardAtmosphere.Settings(hour, 0.15f, 0.08f, 2.5f, 0.2f, 0);
    }

    private static float adaptedGround(BoardAtmosphere.Lighting lighting) {
        return luminance(lighting.groundLight()) * lighting.adaptation();
    }

    /** Linear output before the shoulder: shared adaptation followed by the scenario's EV compensation. */
    private static float displayedGround(BoardAtmosphere.Settings settings) {
        return adaptedGround(BoardAtmosphere.lighting(settings)) * (float) Math.pow(2, settings.exposure());
    }

    /** Light compared as floats: Color equality rounds to 8 bits and overflows above one. */
    static void assertSameColor(Color expected, Color actual, String message) {
        assertEquals(expected.r, actual.r, 0.000001f, message);
        assertEquals(expected.g, actual.g, 0.000001f, message);
        assertEquals(expected.b, actual.b, 0.000001f, message);
    }

    static void assertSameLighting(BoardAtmosphere.Lighting expected, BoardAtmosphere.Lighting actual, String message) {
        assertTrue(expected.direction().epsilonEquals(actual.direction(), 0.000001f), message);
        assertSameColor(expected.direct(), actual.direct(), message);
        assertSameColor(expected.ambient(), actual.ambient(), message);
        assertSameColor(expected.fog(), actual.fog(), message);
        assertSameColor(expected.sky(), actual.sky(), message);
        assertSameColor(expected.horizon(), actual.horizon(), message);
        assertSameColor(expected.tint(), actual.tint(), message);
        assertSameColor(expected.environmentSky(), actual.environmentSky(), message);
        assertSameColor(expected.environmentHorizon(), actual.environmentHorizon(), message);
        assertEquals(expected.environmentImage(), actual.environmentImage(), message);
        assertEquals(expected.environmentGain(), actual.environmentGain(), message);
        assertEquals(expected.shadowStrength(), actual.shadowStrength(), message);
        assertEquals(expected.saturation(), actual.saturation(), message);
        assertEquals(expected.daylight(), actual.daylight(), message);
        assertEquals(expected.sunlight(), actual.sunlight(), message);
    }

    private static float luminance(Color color) {
        return 0.2126f * color.r + 0.7152f * color.g + 0.0722f * color.b;
    }

    private static float blueToRed(Color color) {
        return color.b / color.r;
    }

    private static float log2(float value) {
        return (float) (Math.log(value) / Math.log(2));
    }
}
