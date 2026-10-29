/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The lobby's backdrop: the start screen's art, cover-fitted, softly blurred and mostly covered by the surface, darker
 * towards the edges. It is painted at a quarter of the window's size, where a few box blur passes (close to a Gaussian)
 * are cheap, and the GPU stretches it back with linear filtering, which keeps it smooth at any size. Painted once per
 * window size, in AWT, so it needs no OpenGL.
 */
final class LobbyBackdrop {
    /** How much smaller than the window the backdrop is painted. */
    static final int DOWNSCALE = 4;
    /** How much of the art the surface covers. */
    static final float DIM = 0.85f;
    /** How much more of the art the surface covers in the corners. */
    static final float VIGNETTE = 0.6f;
    /** Box blur passes; three come close to a Gaussian. */
    private static final int PASSES = 3;
    /** The blur radius at the painted size, as a share of its width. */
    private static final float BLUR_SHARE = 1 / 160f;

    private LobbyBackdrop() { }

    /**
     * The backdrop for a {@code width} by {@code height} window, at a {@link #DOWNSCALE}th of that size: {@code art}
     * (or nothing when null) under {@code base}, the surface's color as RGB.
     */
    static BufferedImage paint(BufferedImage art, int width, int height, int base) {
        int w = Math.max(1, (width + DOWNSCALE - 1) / DOWNSCALE);
        int h = Math.max(1, (height + DOWNSCALE - 1) / DOWNSCALE);
        int[] pixels = new int[w * h];
        if (art != null) {
            BufferedImage fitted = cover(art, w, h);
            fitted.getRGB(0, 0, w, h, pixels, 0, w);
            int radius = Math.max(1, Math.round(w * BLUR_SHARE));
            int[] scratch = new int[pixels.length];
            for (int pass = 0; pass < PASSES; pass++) {
                boxBlur(pixels, scratch, w, h, radius);
                boxBlur(scratch, pixels, h, w, radius);
            }
        } else {
            java.util.Arrays.fill(pixels, base);
        }
        shade(pixels, w, h, base);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, pixels, 0, w);
        return result;
    }

    /** {@code art} covering a {@code w} by {@code h} image, halved step by step so the shrinking skips no detail. */
    private static BufferedImage cover(BufferedImage art, int w, int h) {
        double cover = Math.max((double) w / art.getWidth(), (double) h / art.getHeight());
        int coverWidth = Math.max(w, (int) Math.ceil(art.getWidth() * cover));
        int coverHeight = Math.max(h, (int) Math.ceil(art.getHeight() * cover));
        BufferedImage source = art;
        while (source.getWidth() / 2 >= coverWidth && source.getHeight() / 2 >= coverHeight) {
            source = scaled(source, source.getWidth() / 2, source.getHeight() / 2, 0, 0, source.getWidth() / 2,
                  source.getHeight() / 2);
        }
        return scaled(source, w, h, (w - coverWidth) / 2, (h - coverHeight) / 2, coverWidth, coverHeight);
    }

    private static BufferedImage scaled(BufferedImage source, int w, int h, int x, int y, int drawWidth,
          int drawHeight) {
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, x, y, drawWidth, drawHeight, null);
        g.dispose();
        return result;
    }

    /**
     * One box blur pass along the rows of {@code from}, {@code w} by {@code h}, written transposed into {@code to}, so
     * that a second call with the sizes swapped blurs the columns and turns the image back. Edges repeat.
     */
    static void boxBlur(int[] from, int[] to, int w, int h, int radius) {
        int span = 2 * radius + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int r = 0;
            int g = 0;
            int b = 0;
            for (int i = -radius; i <= radius; i++) {
                int pixel = from[row + Math.clamp(i, 0, w - 1)];
                r += (pixel >> 16) & 0xFF;
                g += (pixel >> 8) & 0xFF;
                b += pixel & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                to[x * h + y] = ((r / span) << 16) | ((g / span) << 8) | (b / span);
                int out = from[row + Math.max(x - radius, 0)];
                int in = from[row + Math.min(x + radius + 1, w - 1)];
                r += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    /** Covers the art with {@code base} by {@link #DIM}, and by up to {@link #VIGNETTE} more towards the corners. */
    private static void shade(int[] pixels, int w, int h, int base) {
        int baseR = (base >> 16) & 0xFF;
        int baseG = (base >> 8) & 0xFF;
        int baseB = base & 0xFF;
        float halfW = w / 2f;
        float halfH = h / 2f;
        for (int y = 0; y < h; y++) {
            float dy = (y + 0.5f - halfH) / halfH;
            for (int x = 0; x < w; x++) {
                float dx = (x + 0.5f - halfW) / halfW;
                float edge = Math.clamp((dx * dx + dy * dy) / 2, 0, 1);
                float shown = (1 - DIM) * (1 - VIGNETTE * edge * edge * (3 - 2 * edge));
                int i = y * w + x;
                int pixel = pixels[i];
                int r = Math.round(baseR + (((pixel >> 16) & 0xFF) - baseR) * shown);
                int g = Math.round(baseG + (((pixel >> 8) & 0xFF) - baseG) * shown);
                int b = Math.round(baseB + ((pixel & 0xFF) - baseB) * shown);
                pixels[i] = (r << 16) | (g << 8) | b;
            }
        }
    }
}
