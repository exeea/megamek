/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design;

/**
 * Color arithmetic for the design tokens, on packed 0xAARRGGBB ints so that Swing and libGDX can share it: WCAG 2
 * contrast, sRGB alpha compositing, color vision deficiency simulation (Machado, Oliveira and Fernandes 2009, full
 * severity) and the CIEDE2000 color difference. No AWT.
 */
public final class ColorMath {
    private ColorMath() {
    }

    /** Parses {@code #RRGGBB} (opaque) or {@code #RRGGBBAA}. */
    public static int parse(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (digits.length() == 6) {
            return 0xFF000000 | Integer.parseUnsignedInt(digits, 16);
        }
        if (digits.length() == 8) {
            int rgba = Integer.parseUnsignedInt(digits, 16);
            return (rgba << 24) | (rgba >>> 8);
        }
        throw new IllegalArgumentException("Not a #RRGGBB or #RRGGBBAA color: " + hex);
    }

    /** {@code #RRGGBB}, or {@code #RRGGBBAA} when not opaque. */
    public static String hex(int argb) {
        int alpha = argb >>> 24;
        String rgb = String.format("#%06X", argb & 0xFFFFFF);
        return alpha == 0xFF ? rgb : rgb + String.format("%02X", alpha);
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    /** The color with its alpha replaced, 0 to 255. */
    public static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /** Composites {@code foreground} (with its alpha) over an opaque {@code background}, in sRGB as both toolkits do. */
    public static int over(int foreground, int background) {
        float alpha = alpha(foreground) / 255f;
        int red = Math.round(channel(foreground, 16) * alpha + channel(background, 16) * (1 - alpha));
        int green = Math.round(channel(foreground, 8) * alpha + channel(background, 8) * (1 - alpha));
        int blue = Math.round(channel(foreground, 0) * alpha + channel(background, 0) * (1 - alpha));
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    /** WCAG 2 relative luminance of the color's RGB, 0 to 1. */
    public static double luminance(int argb) {
        return 0.2126 * linear(channel(argb, 16)) + 0.7152 * linear(channel(argb, 8))
              + 0.0722 * linear(channel(argb, 0));
    }

    /** WCAG 2 contrast ratio of two opaque colors, 1 to 21. */
    public static double contrast(int first, int second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** How the opaque color looks to someone with the given vision; alpha is kept. */
    public static int simulate(int argb, Vision vision) {
        double[][] matrix = vision.matrix();
        if (matrix == null) {
            return argb;
        }
        double red = linear(channel(argb, 16));
        double green = linear(channel(argb, 8));
        double blue = linear(channel(argb, 0));
        int result = argb & 0xFF000000;
        for (int row = 0; row < 3; row++) {
            double value = matrix[row][0] * red + matrix[row][1] * green + matrix[row][2] * blue;
            result |= encode(value) << (16 - 8 * row);
        }
        return result;
    }

    /** CIE L*a*b* (D65) of the opaque color. */
    public static double[] lab(int argb) {
        double red = linear(channel(argb, 16));
        double green = linear(channel(argb, 8));
        double blue = linear(channel(argb, 0));
        double x = (0.4124 * red + 0.3576 * green + 0.1805 * blue) / 0.95047;
        double y = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
        double z = (0.0193 * red + 0.1192 * green + 0.9505 * blue) / 1.08883;
        double fx = labCurve(x);
        double fy = labCurve(y);
        double fz = labCurve(z);
        return new double[] { 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz) };
    }

    /** CIEDE2000 difference of two opaque colors as seen with the given vision. */
    public static double deltaE(int first, int second, Vision vision) {
        return deltaE2000(lab(simulate(first, vision)), lab(simulate(second, vision)));
    }

    /** The smallest CIEDE2000 difference of two colors over every {@link Vision}. */
    public static double worstDeltaE(int first, int second) {
        double worst = Double.MAX_VALUE;
        for (Vision vision : Vision.values()) {
            worst = Math.min(worst, deltaE(first, second, vision));
        }
        return worst;
    }

    /** CIEDE2000 (Sharma, Wu and Dalal 2005) with unit weights. */
    public static double deltaE2000(double[] first, double[] second) {
        double l1 = first[0];
        double a1 = first[1];
        double b1 = first[2];
        double l2 = second[0];
        double a2 = second[1];
        double b2 = second[2];
        double chromaMean = (Math.hypot(a1, b1) + Math.hypot(a2, b2)) / 2;
        double chroma7 = Math.pow(chromaMean, 7);
        double g = 0.5 * (1 - Math.sqrt(chroma7 / (chroma7 + Math.pow(25, 7))));
        double a1p = (1 + g) * a1;
        double a2p = (1 + g) * a2;
        double c1p = Math.hypot(a1p, b1);
        double c2p = Math.hypot(a2p, b2);
        double h1p = hueDegrees(b1, a1p);
        double h2p = hueDegrees(b2, a2p);
        double deltaLp = l2 - l1;
        double deltaCp = c2p - c1p;
        double deltaHueP;
        if (c1p * c2p == 0) {
            deltaHueP = 0;
        } else if (Math.abs(h2p - h1p) <= 180) {
            deltaHueP = h2p - h1p;
        } else if (h2p - h1p > 180) {
            deltaHueP = h2p - h1p - 360;
        } else {
            deltaHueP = h2p - h1p + 360;
        }
        double deltaHp = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(deltaHueP / 2));
        double lMean = (l1 + l2) / 2;
        double cMean = (c1p + c2p) / 2;
        double hMean;
        if (c1p * c2p == 0) {
            hMean = h1p + h2p;
        } else if (Math.abs(h1p - h2p) <= 180) {
            hMean = (h1p + h2p) / 2;
        } else if (h1p + h2p < 360) {
            hMean = (h1p + h2p + 360) / 2;
        } else {
            hMean = (h1p + h2p - 360) / 2;
        }
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hMean - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hMean))
              + 0.32 * Math.cos(Math.toRadians(3 * hMean + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hMean - 63));
        double deltaTheta = 30 * Math.exp(-Math.pow((hMean - 275) / 25, 2));
        double cMean7 = Math.pow(cMean, 7);
        double rc = 2 * Math.sqrt(cMean7 / (cMean7 + Math.pow(25, 7)));
        double lOffset = Math.pow(lMean - 50, 2);
        double sl = 1 + 0.015 * lOffset / Math.sqrt(20 + lOffset);
        double sc = 1 + 0.045 * cMean;
        double sh = 1 + 0.015 * cMean * t;
        double rt = -Math.sin(Math.toRadians(2 * deltaTheta)) * rc;
        double lightness = deltaLp / sl;
        double chroma = deltaCp / sc;
        double hue = deltaHp / sh;
        return Math.sqrt(lightness * lightness + chroma * chroma + hue * hue + rt * chroma * hue);
    }

    private static double hueDegrees(double b, double a) {
        double degrees = Math.toDegrees(Math.atan2(b, a));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    private static double labCurve(double value) {
        return value > 216.0 / 24389 ? Math.cbrt(value) : (24389.0 / 27 * value + 16) / 116;
    }

    private static int channel(int argb, int shift) {
        return (argb >>> shift) & 0xFF;
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static int encode(double linear) {
        double clamped = Math.max(0, Math.min(1, linear));
        double value = clamped <= 0.0031308 ? 12.92 * clamped : 1.055 * Math.pow(clamped, 1 / 2.4) - 0.055;
        return (int) Math.round(value * 255);
    }
}
