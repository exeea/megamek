/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.BufferUtils;

/** CPU sprite composition in linear RGB. Alpha is independent coverage, matching the cutout atlases' sampling. */
final class GpuLinearImage {
    private static final float[] LINEAR = new float[256];
    static {
        for (int i = 0; i < LINEAR.length; i++) { LINEAR[i] = GpuColor.linear(i / 255f); }
    }

    final int width, height;
    private final float[] pixels;

    GpuLinearImage(int width, int height) {
        this.width = width;
        this.height = height;
        pixels = new float[width * height * 4];
    }

    /** Decode the authored image once; the caller retains ownership of the source Pixmap. */
    GpuLinearImage(Pixmap source) {
        this(source, true);
    }

    /** Numerical array layers share resizing but bypass every RGB conversion. */
    GpuLinearImage(Pixmap source, boolean color) {
        this(source.getWidth(), source.getHeight());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = source.getPixel(x, y), index = (y * width + x) * 4;
                for (int c = 0; c < 3; c++) {
                    int value = rgba >>> (24 - c * 8) & 255;
                    pixels[index + c] = color ? LINEAR[value] : value / 255f;
                }
                pixels[index + 3] = (rgba & 255) / 255f;
            }
        }
    }

    void fill(float r, float g, float b, float a) {
        for (int i = 0; i < pixels.length; i += 4) {
            pixels[i] = r; pixels[i + 1] = g; pixels[i + 2] = b; pixels[i + 3] = a;
        }
    }

    /** Bilinear crop/resize with overwrite, preserving the atlases' original no-blending placement rule. */
    void draw(GpuLinearImage source, int sx, int sy, int sw, int sh, int dx, int dy, int dw, int dh) {
        for (int y = Math.max(0, dy); y < Math.min(height, dy + dh); y++) {
            float py = Math.clamp(sy + (y - dy + .5f) * sh / dh - .5f, sy, sy + sh - 1);
            int y0 = (int) py, y1 = Math.min(y0 + 1, sy + sh - 1);
            for (int x = Math.max(0, dx); x < Math.min(width, dx + dw); x++) {
                float px = Math.clamp(sx + (x - dx + .5f) * sw / dw - .5f, sx, sx + sw - 1);
                int x0 = (int) px, x1 = Math.min(x0 + 1, sx + sw - 1);
                for (int c = 0; c < 4; c++) {
                    float a = source.pixels[(y0 * source.width + x0) * 4 + c];
                    float b = source.pixels[(y0 * source.width + x1) * 4 + c];
                    float d = source.pixels[(y1 * source.width + x0) * 4 + c];
                    float e = source.pixels[(y1 * source.width + x1) * 4 + c];
                    float top = a + (b - a) * (px - x0), bottom = d + (e - d) * (px - x0);
                    pixels[(y * width + x) * 4 + c] = top + (bottom - top) * (py - y0);
                }
            }
        }
    }

    /** Repeat a swatch's edge texels into its atlas gutter. The interior is already in this image. */
    void extrude(int left, int top, int sw, int sh, int border) {
        for (int y = top - border; y < top + sh + border; y++) {
            for (int x = left - border; x < left + sw + border; x++) {
                int sx = Math.clamp(x, left, left + sw - 1), sy = Math.clamp(y, top, top + sh - 1);
                System.arraycopy(pixels, (sy * width + sx) * 4, pixels, (y * width + x) * 4, 4);
            }
        }
    }

    void opaque() {
        for (int i = 3; i < pixels.length; i += 4) { pixels[i] = 1; }
    }

    /** Give nearly empty cutout texels the region's average visible RGB, without changing their coverage. */
    void bleed(int top, int bottom) {
        double r = 0, g = 0, b = 0;
        int count = 0;
        for (int i = top * width * 4; i < bottom * width * 4; i += 4) {
            if (pixels[i + 3] < 128 / 255f) { continue; }
            r += pixels[i]; g += pixels[i + 1]; b += pixels[i + 2]; count++;
        }
        if (count == 0) { return; }
        for (int i = top * width * 4; i < bottom * width * 4; i += 4) {
            if (pixels[i + 3] >= 32 / 255f) { continue; }
            pixels[i] = (float) (r / count); pixels[i + 1] = (float) (g / count); pixels[i + 2] = (float) (b / count);
        }
    }

    FloatBuffer buffer() {
        var data = BufferUtils.newFloatBuffer(pixels.length);
        return data.put(pixels).flip();
    }

    /** Upload bounded sprite color; numerical array layers use buffer() instead. */
    Texture texture(boolean mipmaps) {
        return GpuColorTexture.ldr(pixels, width, height, mipmaps);
    }
}
