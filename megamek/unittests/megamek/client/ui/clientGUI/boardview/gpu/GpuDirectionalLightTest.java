/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;

import com.badlogic.gdx.graphics.OrthographicCamera;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import org.junit.jupiter.api.Test;

/** Finite source range bounds in the renderer's linear units, independent of any framebuffer precision. */
class GpuDirectionalLightTest {
    @Test
    void finiteDirectionalLobeFitsHalfFloatAcrossConfiguredLightAndMaterialSweep() {
        double source = maximumSource();
        FloatBuffer table = GpuEnvironmentLighting.arrayPixels(GpuEnvironmentLighting.WIDTH,
              GpuEnvironmentLighting.HEIGHT);
        FloatBuffer conservative = GpuEnvironmentLighting.arrayPixels(GpuEnvironmentLighting.WIDTH,
              GpuEnvironmentLighting.HEIGHT);
        double original = maximumResponse(table);
        for (int i = 0; i < table.capacity(); i++) {
            float value = table.get(i);
            table.put(i, Float.float16ToFloat(Float.floatToFloat16(value)));
            int column = (i / 4) % GpuEnvironmentLighting.WIDTH, channel = i % 4;
            // GL may choose either adjacent representable value independently for every channel. Maximize
            // the point-DFG numerator and minimize the filtered-DFG denominator before bilinear sampling.
            if (column < 128 && channel >= 2) { conservative.put(i, adjacentHalf(value, true)); }
            else if (column >= 128 && channel < 2) { conservative.put(i, adjacentHalf(value, false)); }
        }
        double nearest = maximumResponse(table), maximum = maximumResponse(conservative);
        double stored = maximum * source * GpuSceneRadiance.SCALE;
        System.out.println("Finite directional bound: float coefficients=" + original + ", nearest half=" + nearest
              + ", conservative adjacent half=" + maximum + ", source=" + source + ", stored radiance=" + stored);
        assertTrue(maximum >= Math.max(original, nearest), "Independent rounding must bound float and nearest coefficients");
        assertTrue(Double.isFinite(stored) && stored < 65504,
              "The all-direction/F0 bound for either adjacent half-float coefficient must fit RGBA16F: " + stored);
    }

    private static float adjacentHalf(float value, boolean upper) {
        assertTrue(Float.isFinite(value) && value >= 0 && value <= 65504, "DFG coefficients must be finite and nonnegative");
        short bits = Float.floatToFloat16(value);
        float nearest = Float.float16ToFloat(bits);
        // Nonnegative finite half-floats are ordered by their bit pattern; exact values need no widening.
        if (upper && nearest < value) { bits++; }
        else if (!upper && nearest > value) { bits--; }
        return Float.float16ToFloat(bits);
    }

    @Test
    void normalizationBoundIncludesInteriorClampAndFloorExtrema() {
        // Actual integrated table coefficients at NoV=.0001, roughness=126/127: numerator saturation
        // creates an interior maximum about 1.5% above both endpoint ratios.
        double pointA = .9689711928367615, pointB = .046269871294498444;
        double filteredA = .9322469890117645, filteredB = .06600695550441742;
        double saturation = (1 - pointB) / pointA;
        assertEquals(1 / (filteredA * saturation + filteredB),
              maximumCorrection(pointA, pointB, filteredA, filteredB), 1e-12);
        assertTrue(maximumCorrection(pointA, pointB, filteredA, filteredB)
              > Math.max(pointB / filteredB, 1 / (filteredA + filteredB)));
        // A denominator floor can likewise change an increasing ratio into a decreasing one.
        assertEquals(.595, maximumCorrection(.5, 1e-8, 1, 1e-9), 1e-12);
    }

    private static double maximumCorrection(double pointA, double pointB, double filteredA, double filteredB) {
        double maximum = Math.max(correction(0, pointA, pointB, filteredA, filteredB),
              correction(1, pointA, pointB, filteredA, filteredB));
        // Between the clamp/floor transitions a ratio of affine functions is monotone. Its maximum
        // therefore lies at an endpoint or one of these two transitions, for every F0 channel in [0,1].
        if (pointA > 0) {
            maximum = Math.max(maximum, correction(Math.clamp((1 - pointB) / pointA, 0, 1),
                  pointA, pointB, filteredA, filteredB));
        }
        if (filteredA > 0) {
            maximum = Math.max(maximum, correction(Math.clamp((1e-7 - filteredB) / filteredA, 0, 1),
                  pointA, pointB, filteredA, filteredB));
        }
        return maximum;
    }

    private static double correction(double f0, double pointA, double pointB, double filteredA, double filteredB) {
        return Math.min(1, pointA * f0 + pointB) / Math.max(1e-7, filteredA * f0 + filteredB);
    }

    private static double maximumResponse(FloatBuffer table) {
        double maximum = 0;
        double radius2 = Math.pow(GpuEnvironmentLighting.SOURCE_RADIUS_SINE, 2) * .25;
        // Bound every incident direction analytically: D <= max(1,ax²,ay²)^2/(pi*ax*ay), and
        // Smith*NoL <= 1/(2*viewLength). The angular filter can make broad near-horizon axes exceed one.
        // Sweep both logarithmic near-horizon and uniform view grids; roughness includes every table row.
        for (int y = 0; y < 128; y++) {
            double roughness = Math.max(.045, y / 127.0), a2 = Math.pow(roughness, 4);
            for (int x = 0; x <= 2048; x++) {
                for (double nv : new double[] { x / 2048.0, Math.pow(10, -9 + x * 9.0 / 2048) }) {
                    double ax2 = a2 + radius2, ay2 = a2 + radius2 / (nv * nv + radius2);
                    double viewLength = Math.sqrt(nv * nv + ax2 * (1 - nv * nv));
                    double pointA = sample(table, nv * 127, roughness * 127, 2);
                    double pointB = sample(table, nv * 127, roughness * 127, 3);
                    double filteredA = sample(table, 128 + Math.pow(nv, .25) * 127, roughness * 127, 0);
                    double filteredB = sample(table, 128 + Math.pow(nv, .25) * 127, roughness * 127, 1);
                    double correction = maximumCorrection(pointA, pointB, filteredA, filteredB);
                    double largestAxis = Math.max(1, Math.max(ax2, ay2));
                    maximum = Math.max(maximum, correction * largestAxis * largestAxis
                          / (2 * Math.sqrt(ax2 * ay2) * viewLength));
                }
            }
        }
        return maximum;
    }

    static double maximumSource() {
        double maximum = 0;
        var cameras = new OrthographicCamera[37];
        for (int i = 0; i < cameras.length; i++) {
            double tilt = Math.min(179.5, i * 5.0);
            float sine = (float) Math.sin(Math.toRadians(tilt)), cosine = (float) Math.cos(Math.toRadians(tilt));
            cameras[i] = new OrthographicCamera();
            cameras[i].direction.set(0, sine, -cosine); cameras[i].up.set(0, cosine, sine);
        }
        for (Atmosphere pressure : Atmosphere.values()) {
            for (AtmosphericTaint taint : AtmosphericTaint.values()) {
                for (int minute = 0; minute < 24 * 60; minute += 5) {
                    for (float clouds : new float[] { 0, 1 }) {
                        for (boolean moon : new boolean[] { false, true }) {
                            var settings = new BoardAtmosphere.Settings(minute / 60f, clouds, 0,
                                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 2, BoardAtmosphere.Effects.NONE,
                                  pressure, 25, moon, taint);
                            var lighting = BoardAtmosphere.lighting(settings);
                            var direct = lighting.direct();
                            maximum = Math.max(maximum, Math.max(direct.r, Math.max(direct.g, direct.b)));
                            for (var camera : cameras) {
                                direct = lighting.relativeTo(camera).direct();
                                maximum = Math.max(maximum, Math.max(direct.r, Math.max(direct.g, direct.b)));
                            }
                        }
                    }
                }
            }
        }
        return maximum;
    }

    private static double sample(FloatBuffer pixels, double x, double y, int channel) {
        int left = (int) x, bottom = (int) y;
        int right = Math.min(left + 1, GpuEnvironmentLighting.WIDTH - 1), top = Math.min(bottom + 1, 127);
        int stride = GpuEnvironmentLighting.WIDTH;
        double a = pixels.get((bottom * stride + left) * 4 + channel);
        double b = pixels.get((bottom * stride + right) * 4 + channel);
        double c = pixels.get((top * stride + left) * 4 + channel);
        double d = pixels.get((top * stride + right) * 4 + channel);
        return (a + (b - a) * (x - left)) * (1 - y + bottom)
              + (c + (d - c) * (x - left)) * (y - bottom);
    }

    /** Independent solid-angle quadrature of a uniform circular source using the original physical point BRDF. */
    static double capReference(double nv, double roughness, double offsetRadii, double f0) {
        double radius = Math.asin(GpuEnvironmentLighting.SOURCE_RADIUS_SINE), cosine = Math.cos(radius);
        double vx = Math.sqrt(1 - nv * nv), angle = Math.acos(nv) + offsetRadii * radius;
        double lx = -Math.sin(angle), lz = Math.cos(angle), total = 0;
        int samples = 65536;
        for (int i = 0; i < samples; i++) {
            double z = 1 - (1 - cosine) * (i + .5) / samples;
            double radial = Math.sqrt(1 - z * z), phi = 2 * Math.PI * ((i + .5) * .6180339887498949 % 1);
            double x = radial * Math.cos(phi), y = radial * Math.sin(phi);
            double sx = lx * z + lz * x, sy = y, sz = lz * z - lx * x;
            if (sz <= 0) { continue; }
            double hx = vx + sx, hy = sy, hz = nv + sz;
            double length = Math.sqrt(hx * hx + hy * hy + hz * hz);
            hx /= length; hy /= length; hz /= length;
            double a2 = Math.pow(roughness, 4), denominator = hx * hx + hy * hy + a2 * hz * hz;
            double visibility = .5 / (sz * Math.sqrt(nv * nv * (1 - a2) + a2)
                  + nv * Math.sqrt(sz * sz * (1 - a2) + a2));
            double fresnel = f0 + (1 - f0) * Math.pow(1 - (vx * hx + nv * hz), 5);
            total += a2 / (denominator * denominator) * visibility * fresnel * sz;
        }
        // Preserve irradiance on a receiver facing the source center, rather than keeping source radiance fixed.
        return total / samples / (.5 * (1 + cosine));
    }
}
