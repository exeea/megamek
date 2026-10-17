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
        // Deep water: λ = 2πg/ω². The wind band's peak remains finite even when its power is zero in calm air.
        assertTrue(peakWavelength(0) < 5, "Dormant wind band: " + peakWavelength(0));
        assertTrue(peakWavelength(1) > 55 && peakWavelength(1) < 85, "Gale: " + peakWavelength(1));
        assertTrue(GpuOcean.sea(1, 9.81f)[2] > GpuOcean.sea(0, 9.81f)[2], "The swell grows a little under wind");
        // JONSWAP's closed form for the unscaled sea: about 3 m in a gale over this fetch, a 0.3 m swell in calm.
        assertEquals(3, GpuOcean.significantHeight(1, 9.81f), .3);
        assertEquals(.3, GpuOcean.significantHeight(0, 9.81f), .05);
        assertEquals(1, GpuOcean.storm(0), 1e-6, "Calm water is never exaggerated");
        assertEquals(0, GpuOcean.sea(0, 9.81f)[1], "Still air must not generate a wind sea");
        assertTrue(GpuOcean.sea(.01f, 9.81f)[1] < GpuOcean.sea(.15f, 9.81f)[1] * .02,
              "The first trace of wind grows gently instead of restoring full ripple power");
    }

    @Test
    void lighterGravityRaisesLongerWavesUnderTheSameWind() {
        double previousHeight = Double.POSITIVE_INFINITY, previousLength = Double.POSITIVE_INFINITY;
        for (float gravity : new float[] { .01f, .16f, .5f, 1, 2, 10 }) {
            float acceleration = gravity * 9.81f;
            double omega = GpuOcean.sea(.7f, acceleration)[0];
            double length = 2 * Math.PI * acceleration / (omega * omega);
            double height = GpuOcean.significantHeight(.7f, acceleration);
            assertTrue(Double.isFinite(height) && height > 0 && height < previousHeight);
            assertTrue(Double.isFinite(length) && length > 0 && length < previousLength);
            previousHeight = height;
            previousLength = length;
        }
    }

    @Test
    void whitecapsCoverNothingInCalmAirAndTheConfiguredShareOfAGale() {
        assertEquals(0, GpuOcean.foamCover(0), 1e-6);
        assertEquals(0, GpuOcean.foamCover(GpuOcean.FOAM_ONSET), 1e-6, "The first whitecaps start at the onset");
        assertEquals(GpuOcean.FOAM_COVERAGE, GpuOcean.foamCover(1), 1e-6);
        for (float strength = GpuOcean.FOAM_ONSET; strength < 1; strength += .1f) {
            assertTrue(GpuOcean.foamCover(strength + .1f) > GpuOcean.foamCover(strength), "More wind, more whitecaps");
        }
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

    @Test
    void directDftAndChoppyNormalsMatchClosedFormPeriodicWaves() {
        assertEquals(45, normalAngle(1, 0, 0, 0), 1e-12, "Unit slope tilts a normal by 45 degrees");
        assertEquals(90, normalAngle(1, 0, -1, 0), 1e-12, "Opposing unit slopes meet at a right angle");
        double[][] trigonometry = trigonometry();
        int[][] modes = { { 1, 0 }, { 0, 25 }, { 17, 19 } };
        float[] spectrum = new float[SIZE * SIZE * GpuOcean.PATCHES.length * 4];
        for (int cascade = 0; cascade < modes.length; cascade++) {
            float amplitude = GpuOcean.PATCHES[cascade] / SIZE * .125f;
            addMode(spectrum, cascade, modes[cascade][0], modes[cascade][1], amplitude);
            double choppiness = 1.4, texel = GpuOcean.PATCHES[cascade] / SIZE;
            Surface surface = referenceSurface(spectrum, cascade, 0, 9.81f, choppiness, trigonometry);
            double kx = 2 * Math.PI * modes[cascade][0] / GpuOcean.PATCHES[cascade];
            double ky = 2 * Math.PI * modes[cascade][1] / GpuOcean.PATCHES[cascade];
            double magnitude = Math.hypot(kx, ky);
            // The exact Fourier response of the four-point stencil, distinct from storage/FFT rounding.
            double dx = (8 * Math.sin(kx * texel) - Math.sin(2 * kx * texel)) / (6 * texel);
            double dy = (8 * Math.sin(ky * texel) - Math.sin(2 * ky * texel)) / (6 * texel);
            double angularSquares = 0, angularMax = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int p = y * SIZE + x;
                    double phase = 2 * Math.PI * (modes[cascade][0] * x + modes[cascade][1] * y) / SIZE;
                    double cosine = Math.cos(phase), sine = Math.sin(phase);
                    double jacobian = 1 - choppiness * amplitude * (kx * dx + ky * dy) / magnitude * cosine;
                    assertTrue(jacobian > .3, "The closed-form control must not reach the crest clamp");
                    assertEquals(jacobian, surface.jacobian()[p], 1e-10, "Unclamped crest conditioning");
                    assertEquals(amplitude * cosine, surface.height()[p], 1e-10, "DFT amplitude and centering");
                    assertEquals(-amplitude * dx * sine / jacobian, surface.slopeX()[p], 1e-10,
                          "Physical X slope, including periodic boundaries and horizontal displacement");
                    assertEquals(-amplitude * dy * sine / jacobian, surface.slopeY()[p], 1e-10,
                          "Physical Y slope, including periodic boundaries and horizontal displacement");
                    double exactJacobian = 1 - choppiness * amplitude * magnitude * cosine;
                    double angle = normalAngle(surface.slopeX()[p], surface.slopeY()[p],
                          -amplitude * kx * sine / exactJacobian, -amplitude * ky * sine / exactJacobian);
                    angularSquares += angle * angle;
                    angularMax = Math.max(angularMax, angle);
                }
            }
            System.out.printf(java.util.Locale.ROOT,
                  "Double stencil only mode (%d,%d) normal RMS %.6f max %.6f degrees%n",
                  modes[cascade][0], modes[cascade][1], Math.sqrt(angularSquares / (SIZE * SIZE)), angularMax);
        }
    }

    @Test
    void finalHalfStoreControlsPreserveExactValuesAndDistinguishRounding() {
        double step = 1.0 / 1024, tiny = Math.scalb(1.0, -24);
        double[][] original = { { 0, 1, -1, 65504, -65504, 1 + .75 * step, -1 - .75 * step,
              .75 * tiny, -.75 * tiny } };
        double[][] nearest = halfFields(original, false), towardZero = halfFields(original, true);
        for (int p = 0; p < 5; p++) {
            assertEquals(original[0][p], nearest[0][p], "Exactly representable values remain exact");
            assertEquals(original[0][p], towardZero[0][p], "Truncation must not move exact values");
        }
        assertEquals(1 + step, nearest[0][5]);
        assertEquals(-1 - step, nearest[0][6]);
        assertEquals(1, towardZero[0][5]);
        assertEquals(-1, towardZero[0][6]);
        assertEquals(tiny, nearest[0][7]);
        assertEquals(-tiny, nearest[0][8]);
        assertEquals(0, towardZero[0][7]);
        assertEquals(-0.0, towardZero[0][8]);
        assertEquals(1 + .75 * step, original[0][5], "The unquantized reference is retained");
    }

    record Surface(double[] height, double[] slopeX, double[] slopeY, double[] jacobian) { }

    static double[][] trigonometry() {
        double[][] result = new double[2][SIZE * SIZE];
        for (int p = 0; p < SIZE; p++) {
            for (int k = 0; k < SIZE; k++) {
                double angle = 2 * Math.PI * p * k / SIZE;
                result[0][p * SIZE + k] = Math.cos(angle);
                result[1][p * SIZE + k] = Math.sin(angle);
            }
        }
        return result;
    }

    /** One travelling cosine; its two Hermitian coefficients sum to the requested height amplitude. */
    static void addMode(float[] spectrum, int cascade, int x, int y, float amplitude) {
        int width = SIZE * GpuOcean.PATCHES.length;
        int forward = ((SIZE / 2 + y) * width + cascade * SIZE + SIZE / 2 + x) * 4;
        int backward = ((SIZE / 2 - y) * width + cascade * SIZE + SIZE / 2 - x) * 4;
        spectrum[forward] = amplitude / 2;
        spectrum[backward + 2] = amplitude / 2;
    }

    /** Direct double DFT of three separate fields: no GPU butterflies or packed displacement arithmetic. */
    static Surface referenceSurface(float[] spectrum, int cascade, float time, float gravity, double choppiness,
          double[][] trigonometry) {
        return finishSurface(referenceFields(spectrum, cascade, time, gravity, trigonometry), cascade, choppiness);
    }

    /** Retain the three double spatial fields before the finish stencil or a final work-texture store. */
    static double[][] referenceFields(float[] spectrum, int cascade, float time, float gravity, double[][] trigonometry) {
        int texels = SIZE * SIZE, width = SIZE * GpuOcean.PATCHES.length;
        double[][] real = new double[3][texels], imaginary = new double[3][texels];
        // The public clock quantizes frequency; integration, transforms and surface geometry below use doubles.
        float loop = (float) (Math.PI * 2 / 1000);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int p = y * SIZE + x, source = (y * width + cascade * SIZE + x) * 4;
                double kx = (x - SIZE / 2) * Math.PI * 2 / GpuOcean.PATCHES[cascade];
                double ky = (y - SIZE / 2) * Math.PI * 2 / GpuOcean.PATCHES[cascade];
                double magnitude = Math.max(Math.hypot(kx, ky), 1e-6);
                double phase = Math.floor(Math.sqrt(gravity * magnitude) / loop) * loop * time;
                double c = Math.cos(phase), s = Math.sin(phase);
                double r = spectrum[source], i = spectrum[source + 1];
                double mirrorR = spectrum[source + 2], mirrorI = spectrum[source + 3];
                real[0][p] = (r + mirrorR) * c + (i - mirrorI) * s;
                imaginary[0][p] = (i + mirrorI) * c + (mirrorR - r) * s;
                real[1][p] = -imaginary[0][p] * kx / magnitude;
                imaginary[1][p] = real[0][p] * kx / magnitude;
                real[2][p] = -imaginary[0][p] * ky / magnitude;
                imaginary[2][p] = real[0][p] * ky / magnitude;
            }
        }
        double[][] fields = new double[3][];
        for (int field = 0; field < fields.length; field++) {
            fields[field] = inverseDft(real[field], imaginary[field], trigonometry);
        }
        return fields;
    }

    /**
     * One hypothetical final binary16 spatial store, after float32 conversion. Both rounding modes are sensitivity
     * controls, not a claim about the driver's framebuffer conversion. No butterfly or initial packing is repeated.
     */
    static double[][] halfFields(double[][] fields, boolean towardZero) {
        double[][] rounded = new double[fields.length][];
        for (int channel = 0; channel < fields.length; channel++) {
            rounded[channel] = new double[fields[channel].length];
            for (int p = 0; p < fields[channel].length; p++) {
                float value = (float) fields[channel][p];
                short bits = Float.floatToFloat16(value);
                float stored = Float.float16ToFloat(bits);
                assertTrue(Float.isFinite(stored), "The ideal spatial field must fit the existing half-float target");
                if (towardZero && Math.abs(stored) > Math.abs(value)) {
                    bits = (short) (bits - 1); // Binary16 magnitude decreases by one, for either sign, including subnormals.
                    stored = Float.float16ToFloat(bits);
                }
                rounded[channel][p] = stored;
            }
        }
        return rounded;
    }

    /** The same double surface reconstruction for independent DFT fields and read-back GPU spatial fields. */
    static Surface finishSurface(double[][] fields, int cascade, double choppiness) {
        int texels = SIZE * SIZE;
        double[] slopeX = new double[texels], slopeY = new double[texels], jacobian = new double[texels];
        double texel = GpuOcean.PATCHES[cascade] / SIZE;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double hx = derivative(fields[0], x, y, 1, 0, texel);
                double hy = derivative(fields[0], x, y, 0, 1, texel);
                double xx = 1 + choppiness * derivative(fields[1], x, y, 1, 0, texel);
                double xy = choppiness * derivative(fields[2], x, y, 1, 0, texel);
                double yx = choppiness * derivative(fields[1], x, y, 0, 1, texel);
                double yy = 1 + choppiness * derivative(fields[2], x, y, 0, 1, texel);
                jacobian[y * SIZE + x] = xx * yy - xy * yx;
                double vertical = Math.max(jacobian[y * SIZE + x], .3);
                slopeX[y * SIZE + x] = (hx * yy - xy * hy) / vertical;
                slopeY[y * SIZE + x] = (xx * hy - hx * yx) / vertical;
            }
        }
        return new Surface(fields[0], slopeX, slopeY, jacobian);
    }

    private static double[] inverseDft(double[] real, double[] imaginary, double[][] trigonometry) {
        double[] cosine = trigonometry[0], sine = trigonometry[1];
        double[] rowReal = new double[SIZE * SIZE], rowImaginary = new double[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int p = y * SIZE + x;
                for (int k = 0; k < SIZE; k++) {
                    int source = y * SIZE + k, wave = x * SIZE + k;
                    rowReal[p] += real[source] * cosine[wave] - imaginary[source] * sine[wave];
                    rowImaginary[p] += real[source] * sine[wave] + imaginary[source] * cosine[wave];
                }
            }
        }
        double[] result = new double[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int p = y * SIZE + x;
                for (int k = 0; k < SIZE; k++) {
                    int source = k * SIZE + x, wave = y * SIZE + k;
                    result[p] += rowReal[source] * cosine[wave] - rowImaginary[source] * sine[wave];
                }
                if (((x + y) & 1) != 0) { result[p] = -result[p]; }
            }
        }
        return result;
    }

    private static double derivative(double[] field, int x, int y, int dx, int dy, double texel) {
        return (8 * (sample(field, x + dx, y + dy) - sample(field, x - dx, y - dy))
              - (sample(field, x + 2 * dx, y + 2 * dy) - sample(field, x - 2 * dx, y - 2 * dy))) / (12 * texel);
    }

    private static double sample(double[] field, int x, int y) {
        return field[Math.floorMod(y, SIZE) * SIZE + Math.floorMod(x, SIZE)];
    }

    /** Angle between normalize(-slopeX, -slopeY, 1), in degrees; atan2 remains accurate near zero error. */
    static double normalAngle(double ax, double ay, double bx, double by) {
        double crossX = by - ay, crossY = ax - bx, crossZ = ax * by - ay * bx;
        return Math.toDegrees(Math.atan2(Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ),
              1 + ax * bx + ay * by));
    }

    private static double peakWavelength(float strength) {
        double omega = GpuOcean.sea(strength, 9.81f)[0];
        return 2 * Math.PI * 9.81 / (omega * omega);
    }
}
