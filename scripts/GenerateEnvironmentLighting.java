/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Offline environment LUT bake. Run with JDK25 from the repository root:
 * java scripts/GenerateEnvironmentLighting.java output/environment-lighting.bin
 * Copy the reviewed output to megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/.
 * The runtime validates dimensions and solar radius. Big-endian float32 retains the original CPU table
 * bits before the unchanged RGBA16F upload; this tool refuses to overwrite an existing output.
 */
public final class GenerateEnvironmentLighting {
    private static final int SIZE = 128;
    private static final int WIDTH = SIZE * 2;
    private static final int HEIGHT = SIZE + 1;
    private static final float SOURCE_RADIUS_SINE = (float) Math.sin(Math.toRadians(.266));
    private static final int SAMPLES = 1024;

    private GenerateEnvironmentLighting() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { throw new IllegalArgumentException("Expected one new output file"); }
        Path output = Path.of(args[0]);
        if (Files.exists(output)) { throw new IllegalArgumentException("Preserve existing output: " + output); }
        float[] pixels = integrate();
        try (var data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output,
              StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
            data.writeInt(0x454C5554); // ELUT
            data.writeInt(WIDTH); data.writeInt(HEIGHT); data.writeFloat(SOURCE_RADIUS_SINE);
            for (float value : pixels) {
                if (!Float.isFinite(value)) { throw new IllegalStateException("Nonfinite environment coefficient"); }
                data.writeFloat(value);
            }
        }
        System.out.println("Wrote " + pixels.length + " float32 coefficients to " + output);
    }

    /** RG: normalized GGX sky basis. BA: the same Smith GGX's integrated Schlick scale and bias. */
    private static float[] integrate() {
        float[] result = new float[WIDTH * HEIGHT * 4];
        for (int y = 0; y < SIZE; y++) {
            double roughness = (double) y / (SIZE - 1), a2 = Math.pow(roughness, 4);
            double[] hx = new double[SAMPLES], hz = new double[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                double u = (i + .5) / SAMPLES;
                double phi = 2 * Math.PI * (Integer.reverse(i) & 0xffffffffL) / 4294967296.0;
                hz[i] = Math.sqrt((1 - u) / (1 + (a2 - 1) * u));
                double radial = Math.sqrt(Math.max(0, 1 - hz[i] * hz[i]));
                hx[i] = radial * Math.cos(phi);
            }
            for (int x = 0; x < SIZE; x++) {
                double nv = Math.max(.0001, (double) x / (SIZE - 1));
                double vx = Math.sqrt(1 - nv * nv);
                double rz = 2.0 * x / (SIZE - 1) - 1, rx = Math.sqrt(Math.max(0, 1 - rz * rz));
                double upper = 0, gradient = 0, weight = 0, scale = 0, bias = 0;
                for (int i = 0; i < SAMPLES; i++) {
                    // Prefilter with N=V=R, weighting incident radiance by NoL as in the split-sum approximation.
                    double nl = 2 * hz[i] * hz[i] - 1;
                    if (nl > 0) {
                        double z = rz * nl + rx * (2 * hz[i] * hx[i]);
                        if (z > 0) { upper += nl; gradient += Math.sqrt(z) * nl; }
                        weight += nl;
                    }
                    double vh = vx * hx[i] + nv * hz[i];
                    double lightZ = 2 * vh * hz[i] - nv;
                    if (lightZ <= 0 || vh <= 0) { continue; }
                    double visible = visibility(nv, lightZ, a2);
                    double contribution = 4 * lightZ * visible * vh / hz[i];
                    double fresnel = Math.pow(1 - vh, 5);
                    scale += (1 - fresnel) * contribution; bias += fresnel * contribution;
                }
                int at = (y * WIDTH + x) * 4;
                result[at] = (float) (upper / weight); result[at + 1] = (float) (gradient / weight);
                result[at + 2] = (float) (scale / SAMPLES); result[at + 3] = (float) (bias / SAMPLES);
            }
        }
        // Separate row: cosine-weighted average directional albedo, needed for reciprocal diffuse coupling.
        for (int x = 0; x < SIZE; x++) {
            double scale = 0, bias = 0;
            for (int i = 0; i < SAMPLES; i++) {
                float nv = (i + .5f) / SAMPLES;
                scale += sample(result, nv * (SIZE - 1), x, 2) * 2 * nv / SAMPLES;
                bias += sample(result, nv * (SIZE - 1), x, 3) * 2 * nv / SAMPLES;
            }
            int at = (SIZE * WIDTH + x) * 4;
            result[at] = (float) scale; result[at + 1] = (float) bias;
        }
        // Right half: the finite-source lobe's directional Schlick albedo, for energy normalization back to the
        // material's point-light budget. Fourth-power NoV allocates precision to the rapidly changing horizon.
        for (int y = 0; y < SIZE; y++) {
            double roughness = Math.max(.045, (double) y / (SIZE - 1));
            for (int x = 0; x < SIZE; x++) {
                double nv = Math.max(1e-7, Math.pow((double) x / (SIZE - 1), 4));
                double[] albedo = filteredAlbedo(nv, roughness);
                int at = (y * WIDTH + SIZE + x) * 4;
                result[at] = (float) albedo[0]; result[at + 1] = (float) albedo[1];
            }
        }
        return result;
    }

    /** Anisotropic GGX VNDF integration (Heitz 2018): bounded weights remain stable at grazing views. */
    private static double[] filteredAlbedo(double nv, double roughness) {
        double a2 = Math.pow(roughness, 4), radius2 = SOURCE_RADIUS_SINE * SOURCE_RADIUS_SINE * .25;
        double ax2 = a2 + radius2, ay2 = a2 + radius2 / (nv * nv + radius2);
        double ax = Math.sqrt(ax2), ay = Math.sqrt(ay2), vx = Math.sqrt(1 - nv * nv);
        double viewLength = Math.sqrt(ax2 * vx * vx + nv * nv);
        double viewX = ax * vx / viewLength, viewZ = nv / viewLength;
        double scale = 0, bias = 0;
        for (int i = 0; i < SAMPLES; i++) {
            double radius = Math.sqrt((i + .5) / SAMPLES);
            double angle = 2 * Math.PI * (Integer.reverse(i) & 0xffffffffL) / 4294967296.0;
            double t1 = radius * Math.cos(angle), t2 = radius * Math.sin(angle), blend = .5 * (1 + viewZ);
            t2 = (1 - blend) * Math.sqrt(1 - t1 * t1) + blend * t2;
            double z = Math.sqrt(Math.max(0, 1 - t1 * t1 - t2 * t2));
            double hx = ax * (-viewZ * t2 + viewX * z), hy = ay * t1;
            double hz = Math.max(0, viewX * t2 + viewZ * z);
            double length = Math.sqrt(hx * hx + hy * hy + hz * hz);
            hx /= length; hy /= length; hz /= length;
            double vh = vx * hx + nv * hz;
            double lx = 2 * vh * hx - vx, ly = 2 * vh * hy, nl = 2 * vh * hz - nv;
            if (nl <= 0) { continue; }
            double lightLength = Math.sqrt(nl * nl + ax2 * lx * lx + ay2 * ly * ly);
            double weight = nl * (nv + viewLength) / (nl * viewLength + nv * lightLength);
            double fresnel = Math.pow(Math.max(0, 1 - vh), 5);
            scale += (1 - fresnel) * weight; bias += fresnel * weight;
        }
        return new double[] { scale / SAMPLES, bias / SAMPLES };
    }

    private static double visibility(double nv, double nl, double a2) {
        return .5 / Math.max(1e-12, nl * Math.sqrt(nv * nv * (1 - a2) + a2)
              + nv * Math.sqrt(nl * nl * (1 - a2) + a2));
    }

    private static float sample(float[] pixels, float x, float y, int channel) {
        x = Math.clamp(x, 0, SIZE - 1); y = Math.clamp(y, 0, HEIGHT - 1);
        int left = (int) x, bottom = (int) y, right = Math.min(left + 1, SIZE - 1), top = Math.min(bottom + 1, HEIGHT - 1);
        float a = pixels[(bottom * WIDTH + left) * 4 + channel], b = pixels[(bottom * WIDTH + right) * 4 + channel];
        float c = pixels[(top * WIDTH + left) * 4 + channel], d = pixels[(top * WIDTH + right) * 4 + channel];
        float low = a + (b - a) * (x - left), high = c + (d - c) * (x - left);
        return low + (high - low) * (y - bottom);
    }

}
