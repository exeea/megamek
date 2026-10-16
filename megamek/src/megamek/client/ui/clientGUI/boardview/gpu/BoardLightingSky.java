/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import megamek.common.planetaryConditions.AtmosphericTaint;

/** Incident sky distribution, independent of the illustrative backdrop and the one shadow-casting light. */
final class BoardLightingSky {
    static final int WIDTH = 128, HEIGHT = 64;
    private static final double[] RAYLEIGH = { .055, .115, .255 };

    /** Immutable-by-ownership snapshot: no camera, exposure, fog animation or visible-sky colors. */
    record Source(Vector3 direction, Vector3 sunDirection, float sunAltitude, float daylight, float directional,
          float density, float haze, float clouds, AtmosphericTaint taint, float taintStrength,
          Color diffuse, Color readability) {
        Source oriented(Vector3 direction) {
            return new Source(new Vector3(direction), sunDirection, sunAltitude, daylight, directional,
                  density, haze, clouds, taint, taintStrength, diffuse, readability);
        }

        /** Color.equals packs to eight bits; a sub-code night-light adjustment must still rebuild the HDR sky. */
        boolean same(Source other) {
            return other != null && direction.equals(other.direction) && sunDirection.equals(other.sunDirection)
                  && sunAltitude == other.sunAltitude && daylight == other.daylight && directional == other.directional
                  && density == other.density && haze == other.haze && clouds == other.clouds
                  && taint == other.taint && taintStrength == other.taintStrength
                  && sameColor(diffuse, other.diffuse) && sameColor(readability, other.readability);
        }

        private static boolean sameColor(Color a, Color b) { return a.r == b.r && a.g == b.g && a.b == b.b; }
    }

    record Result(float[] radiance, Color upward) { }

    /**
     * A calibrated single-scattering distribution, not an absolute atmospheric energy model. Clear air retains
     * the owner's diffuse RGB budget. Taint changes extinction and phase, then retains that budget's luminance.
     * Explicit gameplay fill is added afterwards; in tainted air it uses the same optical medium with an isotropic
     * source. Its brightness remains a gameplay allowance, not a claim about astronomical starlight intensity.
     * Neither a source disk nor direct irradiance is put in the image.
     */
    static Result generate(Source source) {
        double[] budget = { source.diffuse.r, source.diffuse.g, source.diffuse.b };
        float[] pixels = calibratedDistribution(source, budget, false);
        if (source.density > 0 && !source.taint.isBreathable() && source.taintStrength > 0
              && source.readability.r + source.readability.g + source.readability.b > 0) {
            // Taint also colors a moonless sky. This diffuse allowance has no source bearing or shadow direction.
            float[] fill = calibratedDistribution(source,
                  new double[] { source.readability.r, source.readability.g, source.readability.b }, true);
            for (int i = 0; i < pixels.length; i++) { pixels[i] += fill[i]; }
        } else {
            // Clear air and vacuum retain their existing uniform allowance exactly.
            for (int i = 0; i < WIDTH * HEIGHT / 2; i++) {
                pixels[3 * i] += source.readability.r;
                pixels[3 * i + 1] += source.readability.g;
                pixels[3 * i + 2] += source.readability.b;
            }
        }
        double[] integrated = upward(pixels);
        Color diffuse = new Color();
        diffuse.r = (float) integrated[0]; diffuse.g = (float) integrated[1]; diffuse.b = (float) integrated[2]; diffuse.a = 1;
        return new Result(pixels, diffuse);
    }

    private static float[] calibratedDistribution(Source source, double[] budget, boolean isotropic) {
        float[] pixels = new float[WIDTH * HEIGHT * 3];
        if (source.density > 0 && budget[0] + budget[1] + budget[2] > 0) {
            float[] clear = distribution(source, 0, isotropic);
            double[] clearUpward = upward(clear);
            pixels = source.taint.isBreathable() || source.taintStrength == 0
                  ? clear : distribution(source, source.taintStrength, isotropic);
            for (int i = 0; i < pixels.length; i++) {
                // The isotropic gameplay allowance starts uniform. Apply only the optical change relative to
                // clear air at this direction; using the clear slab itself would jump at infinitesimal taint.
                double reference = isotropic ? clear[i] : clearUpward[i % 3];
                pixels[i] *= (float) (budget[i % 3] / Math.max(1e-12, reference));
            }
            double[] tinted = upward(pixels);
            double gain = luminance(budget) / Math.max(1e-12, luminance(tinted));
            for (int i = 0; i < pixels.length; i++) { pixels[i] *= (float) gain; }
        }
        return pixels;
    }

    private static float[] distribution(Source source, float strength, boolean isotropicSource) {
        double[][] medium = medium(source, strength);
        float[] pixels = new float[WIDTH * HEIGHT * 3];
        // The one-light handover has no directional lobe at its crossover; its diffuse distribution stays continuous.
        double amount = isotropicSource ? 0 : source.directional;
        double muSource = 1 + (Math.max(.05, -source.direction.z) - 1) * amount;
        double sunLength = Math.hypot(source.sunDirection.x, source.sunDirection.y);
        double twilight = isotropicSource ? 0 : source.daylight * (1 - smooth(.05, .3, source.sunAltitude));
        for (int y = 0; y < HEIGHT / 2; y++) {
            double theta = Math.PI * (y + .5) / HEIGHT, mu = Math.cos(theta), radial = Math.sin(theta);
            double[] path = new double[3];
            for (int c = 0; c < 3; c++) { path[c] = pathIntegral(medium[4][c], mu, muSource); }
            for (int x = 0; x < WIDTH; x++) {
                double phi = 2 * Math.PI * ((x + .5) / WIDTH - .5), vx = radial * Math.cos(phi), vy = radial * Math.sin(phi);
                double cosine = -vx * source.direction.x - vy * source.direction.y - mu * source.direction.z;
                double isotropic = 1 / (4 * Math.PI);
                double ray = isotropic + amount * (3 * (1 + cosine * cosine) / (16 * Math.PI) - isotropic);
                double aerosol = isotropic + amount * (phase(cosine, .72) - isotropic);
                double taint = isotropic + amount * (phase(cosine, asymmetry(source.taint)) - isotropic);
                double cloud = isotropic + amount * (phase(cosine, .35) - isotropic);
                // Bounded diffuse horizon glow extends through the existing below-horizon twilight budget.
                // Its true solar bearing never becomes another direct source or a shadow-casting moon.
                double bearing = sunLength > 0 ? -(vx * source.sunDirection.x + vy * source.sunDirection.y) / sunLength : 0;
                double horizon = twilight * Math.exp(-mu * 5) * (.5 + .5 * Math.max(0, bearing));
                for (int c = 0; c < 3; c++) {
                    double weighted = medium[0][c] * ray + medium[1][c] * aerosol
                          + medium[2][c] * taint + medium[3][c] * cloud;
                    pixels[(y * WIDTH + x) * 3 + c] = (float) (Math.PI * weighted / medium[4][c] * path[c]
                          + horizon * Math.exp(-medium[4][c] / Math.max(.12, mu)));
                }
            }
        }
        return pixels;
    }

    /** Molecular, aerosol, taint and cloud scattering followed by total extinction; all are optical depths. */
    private static double[][] medium(Source source, float strength) {
        double[] scatter, absorb;
        // Hazard categories do not specify chemistry. These optical surrogates are explicit authoring choices.
        switch (source.taint) {
            case TAINTED_CAUSTIC, TOXIC_CAUSTIC -> {
                scatter = new double[] { .100, .095, .035 }; absorb = new double[] { .010, .012, .060 };
            }
            case TAINTED_POISON, TOXIC_POISON -> {
                scatter = new double[] { .075, .040, .090 }; absorb = new double[] { .020, .060, .015 };
            }
            case TAINTED_FLAME, TOXIC_FLAME -> {
                scatter = new double[] { .110, .060, .025 }; absorb = new double[] { .010, .025, .080 };
            }
            default -> { scatter = new double[3]; absorb = new double[3]; }
        }
        double taint = strength * (source.taint.isToxic() ? 1 : .45);
        double aerosol = .065 + .15 * source.haze, cloud = .6 * source.clouds;
        double[][] result = new double[5][3];
        for (int c = 0; c < 3; c++) {
            result[0][c] = source.density * RAYLEIGH[c];
            result[1][c] = source.density * aerosol * .9;
            result[2][c] = source.density * taint * scatter[c];
            result[3][c] = source.density * cloud * .98;
            result[4][c] = source.density * (RAYLEIGH[c] + aerosol + cloud + taint * (scatter[c] + absorb[c]));
        }
        return result;
    }

    private static double asymmetry(AtmosphericTaint taint) {
        return switch (taint) {
            case TAINTED_POISON, TOXIC_POISON -> .35;
            case TAINTED_FLAME, TOXIC_FLAME -> .65;
            default -> .5;
        };
    }

    private static double smooth(double low, double high, double value) {
        double t = Math.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double phase(double cosine, double g) {
        double d = 1 + g * g - 2 * g * Math.clamp(cosine, -1, 1);
        return (1 - g * g) / (4 * Math.PI * d * Math.sqrt(d));
    }

    /** Closed-form slab integral; the symmetric divided difference stays finite near equal directions. */
    static double pathIntegral(double tau, double muView, double muSource) {
        if (tau == 0) { return 0; }
        double a = tau / muSource, b = tau / muView, delta = Math.abs(a - b);
        double divided = delta < 1e-7 ? 1 - delta * .5 + delta * delta / 6 : -Math.expm1(-delta) / delta;
        return b * Math.exp(-Math.min(a, b)) * divided;
    }

    static double[] upward(float[] pixels) {
        double[] result = new double[3];
        for (int y = 0; y < HEIGHT / 2; y++) {
            // Exact integral of cos(theta) over this pixel row, divided by pi.
            double low = Math.sin(Math.PI * y / HEIGHT), high = Math.sin(Math.PI * (y + 1) / HEIGHT);
            double weight = (high * high - low * low) / WIDTH;
            for (int x = 0; x < WIDTH; x++) {
                for (int c = 0; c < 3; c++) { result[c] += pixels[(y * WIDTH + x) * 3 + c] * weight; }
            }
        }
        return result;
    }

    private static double luminance(double[] color) { return .2126 * color[0] + .7152 * color[1] + .0722 * color[2]; }
}
