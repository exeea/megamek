/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;

import com.badlogic.gdx.graphics.Color;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import org.junit.jupiter.api.Test;

class BoardLightingSkyTest {
    @Test
    void actualDiffuseAtlasRetainsTheBudgetAndMatchesCosineIntegrationOnSlopes() {
        for (float hour : new float[] { 0, 6, 12, 18 }) {
            var sky = BoardLightingSky.generate(BoardAtmosphere.lighting(
                  settings(hour, true, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE)).skySource());
            var image = GpuLightingEnvironment.generated(sky);
            var atlas = FloatBuffer.allocate(512 * 512 * 4);
            image.write(atlas, 512);
            float[] budget = { sky.upward().r, sky.upward().g, sky.upward().b };
            for (int x = 0; x < 128; x++) {
                for (int c = 0; c < 3; c++) {
                    assertEquals(budget[c], atlas.get(((197 * 512) + 261 + x) * 4 + c), 1e-7f);
                }
            }
            for (int x : new int[] { 16, 48, 80, 112 }) {
                for (int y : new int[] { 16, 31, 47 }) {
                    double theta = Math.PI * (y + .5) / 64, phi = 2 * Math.PI * ((x + .5) / 128 - .5);
                    double nx = Math.sin(theta) * Math.cos(phi), ny = Math.sin(theta) * Math.sin(phi), nz = Math.cos(theta);
                    double[] expected = new double[3];
                    for (int sy = 0; sy < 32; sy++) {
                        double st = Math.PI * (sy + .5) / 64;
                        double area = 2.0 / 128 * (Math.cos(Math.PI * sy / 64) - Math.cos(Math.PI * (sy + 1) / 64));
                        for (int sx = 0; sx < 128; sx++) {
                            double sp = 2 * Math.PI * ((sx + .5) / 128 - .5);
                            double weight = Math.max(0, nx * Math.sin(st) * Math.cos(sp)
                                  + ny * Math.sin(st) * Math.sin(sp) + nz * Math.cos(st)) * area;
                            for (int c = 0; c < 3; c++) { expected[c] += sky.radiance()[(sy * 128 + sx) * 3 + c] * weight; }
                        }
                    }
                    for (int c = 0; c < 3; c++) {
                        double actual = atlas.get((((197 + y) * 512) + 261 + x) * 4 + c);
                        assertEquals(expected[c], actual, Math.max(1e-7, budget[c] * .015),
                              "Compact diffuse atlas deviates from direct cosine quadrature at " + hour + "/" + x + "/" + y);
                    }
                }
            }
        }
    }

    @Test
    void clearSkyKeepsItsDiffuseBudgetWithoutPuttingTheDirectBeamInTheImage() {
        for (float hour : new float[] { 0, 6, 12, 18 }) {
            var light = BoardAtmosphere.lighting(settings(hour, true, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE));
            var result = BoardLightingSky.generate(light.skySource());
            assertColor(light.ambient(), result.upward(), 2e-6f);
            assertTrue(luminance(result.upward()) < luminance(light.groundLight()), "Direct light remains separate");
            for (int i = 0; i < result.radiance().length; i++) {
                assertTrue(Float.isFinite(result.radiance()[i]) && result.radiance()[i] >= 0);
                if (i >= result.radiance().length / 2) { assertEquals(0, result.radiance()[i]); }
            }
        }
    }

    @Test
    void vacuumContainsOnlyExplicitReadabilityAndMoonlessHasNoDirectionalLobe() {
        for (boolean moon : new boolean[] { false, true }) {
            var vacuum = BoardAtmosphere.lighting(settings(0, moon, Atmosphere.VACUUM, AtmosphericTaint.TOXIC_FLAME));
            var result = BoardLightingSky.generate(vacuum.skySource());
            assertColor(vacuum.skySource().readability(), result.upward(), 1e-7f);
        }
        var night = BoardAtmosphere.lighting(settings(0, false, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE));
        assertFalse(night.hasDirectLight());
        var pixels = BoardLightingSky.generate(night.skySource()).radiance();
        for (int y = 0; y < BoardLightingSky.HEIGHT / 2; y++) {
            for (int x = 1; x < BoardLightingSky.WIDTH; x++) {
                for (int c = 0; c < 3; c++) {
                    assertEquals(pixels[(y * BoardLightingSky.WIDTH) * 3 + c],
                          pixels[(y * BoardLightingSky.WIDTH + x) * 3 + c]);
                }
            }
        }
    }

    @Test
    void taintChangesBothAngularDistributionAndChromaWithoutChangingTheCalibratedBudget() {
        var settings = settings(13, true, Atmosphere.STANDARD, AtmosphericTaint.TOXIC_CAUSTIC);
        var clear = BoardLightingSky.generate(BoardAtmosphere.lighting(settings, .3f, 0).skySource());
        var tainted = BoardLightingSky.generate(BoardAtmosphere.lighting(settings, .3f, 3).skySource());
        assertEquals(luminance(clear.upward()), luminance(tainted.upward()), 2e-6);
        assertTrue(Math.abs(clear.upward().b / clear.upward().r - tainted.upward().b / tainted.upward().r) > .02);
        int first = (8 * BoardLightingSky.WIDTH + 24) * 3, second = (8 * BoardLightingSky.WIDTH + 88) * 3;
        float clearRatio = clear.radiance()[first] / clear.radiance()[second];
        float taintedRatio = tainted.radiance()[first] / tainted.radiance()[second];
        assertTrue(Math.abs(clearRatio - taintedRatio) > .01, "Taint is not a final uniform color multiplier");
    }

    @Test
    void moonlessReadabilityUsesOpticalTaintWithoutInventingMoonlightOrLosingItsBudget() {
        var clearLight = BoardAtmosphere.lighting(settings(0, false, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE));
        var clear = BoardLightingSky.generate(clearLight.skySource());
        for (var taint : new AtmosphericTaint[] { AtmosphericTaint.TOXIC_CAUSTIC,
              AtmosphericTaint.TOXIC_POISON, AtmosphericTaint.TOXIC_FLAME }) {
            var light = BoardAtmosphere.lighting(settings(0, false, Atmosphere.STANDARD, taint), .3f, 3);
            assertFalse(light.hasDirectLight(), "Optical treatment of a diffuse allowance does not create a moon");
            assertEquals(0, luminance(light.skySource().diffuse()));
            var tinted = BoardLightingSky.generate(light.skySource());
            assertEquals(luminance(clear.upward()), luminance(tinted.upward()), 2e-7);
            assertTrue(Math.abs(clear.upward().r - tinted.upward().r)
                  + Math.abs(clear.upward().g - tinted.upward().g)
                  + Math.abs(clear.upward().b - tinted.upward().b) > .0001, "Every taint family colors the actual night environment");
            for (int y = 0; y < BoardLightingSky.HEIGHT / 2; y++) {
                for (int x = 1; x < BoardLightingSky.WIDTH; x++) {
                    for (int c = 0; c < 3; c++) {
                        assertEquals(tinted.radiance()[y * BoardLightingSky.WIDTH * 3 + c],
                              tinted.radiance()[(y * BoardLightingSky.WIDTH + x) * 3 + c]);
                    }
                }
            }
            var disabled = BoardAtmosphere.lighting(settings(0, false, Atmosphere.STANDARD, taint), .3f, 0);
            assertArrayEquals(clear.radiance(), BoardLightingSky.generate(disabled.skySource()).radiance(),
                  "Zero taint strength retains the original clear-night fill exactly");
            var tiny = BoardLightingSky.generate(BoardAtmosphere.lighting(
                  settings(0, false, Atmosphere.STANDARD, taint), .3f, .00001f).skySource());
            float peak = Math.max(clear.upward().r, Math.max(clear.upward().g, clear.upward().b));
            for (int i = 0; i < clear.radiance().length; i++) {
                assertEquals(clear.radiance()[i], tiny.radiance()[i], peak * .0001f,
                      "Infinitesimal taint must not switch uniform moonless fill into a slab-shaped sky");
            }
            var vacuum = BoardAtmosphere.lighting(settings(0, false, Atmosphere.VACUUM, taint), .3f, 3);
            var clearVacuum = BoardAtmosphere.lighting(settings(0, false, Atmosphere.VACUUM, AtmosphericTaint.BREATHABLE));
            assertArrayEquals(BoardLightingSky.generate(clearVacuum.skySource()).radiance(),
                  BoardLightingSky.generate(vacuum.skySource()).radiance(), "Vacuum has no medium to color the allowance");
        }
    }

    @Test
    void diffuseTwilightContinuesAcrossTheOneLightHandover() {
        float handover = (float) (6 + Math.asin(-.09) * 12 / Math.PI);
        var before = BoardAtmosphere.lighting(settings(handover - .0001f, true, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE));
        var after = BoardAtmosphere.lighting(settings(handover + .0001f, true, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE));
        assertFalse(before.sunlight());
        assertTrue(after.sunlight());
        var a = BoardLightingSky.generate(before.skySource()).radiance();
        var b = BoardLightingSky.generate(after.skySource()).radiance();
        double maximum = 0, peak = 0;
        for (int i = 0; i < a.length; i++) {
            maximum = Math.max(maximum, Math.abs(a[i] - b[i])); peak = Math.max(peak, Math.max(a[i], b[i]));
        }
        assertTrue(peak > 0, "Below-horizon twilight must not disappear when the direct source crosses over");
        assertTrue(maximum / peak < .001, "A shadow-direction flip must not flip the diffuse sky");
    }

    @Test
    void exposureAndFogDoNotChangeTheGeneratedImageInputs() {
        var base = settings(13, true, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE);
        var changed = new BoardAtmosphere.Settings(base.hour(), base.clouds(), .7f, 4, base.haze(), 1.5f,
              base.effects(), base.pressure(), base.temperature(), base.moonlight(), base.taint());
        assertTrue(BoardAtmosphere.lighting(base).skySource().same(BoardAtmosphere.lighting(changed).skySource()));
        var night = settings(0, true, Atmosphere.STANDARD, AtmosphericTaint.BREATHABLE);
        var a = BoardAtmosphere.lighting(night, .3f).skySource();
        var b = BoardAtmosphere.lighting(night, .3001f).skySource();
        assertTrue(Math.abs(a.diffuse().r - b.diffuse().r) < 1f / 255);
        assertTrue(a.diffuse().r != b.diffuse().r);
        assertFalse(a.same(b), "A sub-eight-bit night-budget change must not reuse stale HDR radiance");
    }

    @Test
    void closedPathMatchesIndependentNumericalIntegrationIncludingEqualDirections() {
        for (double muSource : new double[] { .05, .4, 1 }) {
            for (double muView : new double[] { .05, .4, 1 }) {
                double tau = .35, step = tau / 32768, sum = 0;
                for (int i = 0; i < 32768; i++) {
                    double t = (i + .5) * step;
                    sum += Math.exp(-t / muView) * Math.exp(-(tau - t) / muSource) / muView * step;
                }
                assertEquals(sum, BoardLightingSky.pathIntegral(tau, muView, muSource), 1e-8);
            }
        }
    }

    private static BoardAtmosphere.Settings settings(float hour, boolean moon, Atmosphere pressure, AtmosphericTaint taint) {
        return new BoardAtmosphere.Settings(hour, 0, 0, 2, 0, 0,
              BoardAtmosphere.Effects.NONE, pressure, 25, moon, taint);
    }

    private static double luminance(Color c) { return .2126 * c.r + .7152 * c.g + .0722 * c.b; }

    private static void assertColor(Color expected, Color actual, float tolerance) {
        assertEquals(expected.r, actual.r, tolerance);
        assertEquals(expected.g, actual.g, tolerance);
        assertEquals(expected.b, actual.b, tolerance);
    }
}
