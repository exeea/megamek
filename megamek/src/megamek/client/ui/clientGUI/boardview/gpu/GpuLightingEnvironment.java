/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

import com.badlogic.gdx.graphics.Color;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

/** A window's lighting panorama. Imported images filter off-thread; small generated skies rebuild on control changes. */
final class GpuLightingEnvironment {
    static final int ATLAS_SIZE = 512;
    static final int WIDTH = 128, HEIGHT = 64, LEVELS = 6, TOP = 130;
    private static final int SAMPLES = 256;
    private final float[] pixels;
    private final Color upward;
    final String name;
    /** Generated skies leave the lower hemisphere to the receiving material's existing ground bounce. */
    final boolean generated;

    private GpuLightingEnvironment(String name, float[] source, int width, int height) {
        this(name, source, width, height, false, null);
    }

    private GpuLightingEnvironment(String name, float[] source, int width, int height, boolean generated, Color diffuse) {
        this.name = name;
        this.generated = generated;
        pixels = filter(source, width, height, generated, diffuse);
        upward = new Color();
        int at = ((TOP + (LEVELS - 1) / 3 * (HEIGHT + 2) + 1) * ATLAS_SIZE
              + (LEVELS - 1) % 3 * (WIDTH + 2) + 1) * 4;
        upward.r = pixels[at]; upward.g = pixels[at + 1]; upward.b = pixels[at + 2]; upward.a = 1;
        if (diffuse != null) { upward.set(diffuse); }
    }

    static GpuLightingEnvironment generated(BoardLightingSky.Result sky) {
        return new GpuLightingEnvironment("Generated atmospheric lighting", sky.radiance(),
              BoardLightingSky.WIDTH, BoardLightingSky.HEIGHT, true, sky.upward());
    }

    static GpuLightingEnvironment load(Path path) throws IOException {
        if (path.toString().toLowerCase(Locale.ROOT).endsWith(".hdr")) {
            try (var stack = MemoryStack.stackPush()) {
                var width = stack.mallocInt(1); var height = stack.mallocInt(1); var channels = stack.mallocInt(1);
                if (!STBImage.stbi_info(path.toString(), width, height, channels)) {
                    throw new IOException("Cannot read HDR image: " + STBImage.stbi_failure_reason());
                }
                checkSize(width.get(0), height.get(0));
                FloatBuffer data = STBImage.stbi_loadf(path.toString(), width, height, channels, 3);
                if (data == null) { throw new IOException("Cannot read HDR image: " + STBImage.stbi_failure_reason()); }
                try {
                    float[] linear = new float[data.remaining()];
                    data.get(linear);
                    for (float value : linear) {
                        if (!Float.isFinite(value) || value < 0 || value > 65504) {
                            throw new IOException("HDR radiance must be finite, nonnegative and fit RGBA16F (maximum 65504).");
                        }
                    }
                    return new GpuLightingEnvironment(path.getFileName().toString(), linear, width.get(0), height.get(0));
                } finally { STBImage.stbi_image_free(data); }
            }
        }
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) { throw new IOException("Use a 2:1 panoramic PNG, JPEG or Radiance HDR image."); }
        checkSize(image.getWidth(), image.getHeight());
        float[] linear = new float[image.getWidth() * image.getHeight() * 3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y), at = (y * image.getWidth() + x) * 3;
                linear[at] = GpuColor.linear((rgb >> 16 & 255) / 255f);
                linear[at + 1] = GpuColor.linear((rgb >> 8 & 255) / 255f);
                linear[at + 2] = GpuColor.linear((rgb & 255) / 255f);
            }
        }
        return new GpuLightingEnvironment(path.getFileName().toString(), linear, image.getWidth(), image.getHeight());
    }

    private static void checkSize(int width, int height) throws IOException {
        if (width != height * 2 || height < 2 || width > 8192) {
            throw new IOException("Use a 2:1 equirectangular panorama, up to 8192 × 4096 pixels.");
        }
    }

    Color upwardIrradiance() { return upward; }

    /** Leave the BRDF table in rows 0–128 intact. Each roughness tile has its own seam/pole gutters. */
    void write(FloatBuffer destination, int stride) {
        for (int y = TOP; y < TOP + 2 * (HEIGHT + 2); y++) {
            for (int x = 0; x < 3 * (WIDTH + 2); x++) {
                int from = (y * ATLAS_SIZE + x) * 4, to = (y * stride + x) * 4;
                for (int c = 0; c < 4; c++) { destination.put(to + c, pixels[from + c]); }
            }
        }
    }

    private static float[] filter(float[] source, int width, int height, boolean generated, Color diffuse) {
        float[] result = new float[ATLAS_SIZE * ATLAS_SIZE * 4];
        float[] hemisphere = generated ? hemisphere(source, width, height) : null;
        for (int level = 0; level < LEVELS; level++) {
            // This sky has smooth scattering lobes and no disks. Filter its rough levels on a small grid,
            // then interpolate into the same atlas; imported panoramas retain their full-resolution filtering.
            boolean compact = generated && level > 0;
            int columns = compact ? 32 : WIDTH, rows = compact ? 16 : HEIGHT;
            int sampleCount = generated ? 32 : SAMPLES;
            float[] filtered = compact ? new float[columns * rows * 3] : result;
            double a2 = Math.pow((double) level / (LEVELS - 1), 4);
            double[] lx = new double[sampleCount], ly = new double[sampleCount], lz = new double[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                double u = (i + .5) / sampleCount, phi = 2 * Math.PI * (Integer.reverse(i) & 0xffffffffL) / 4294967296.0;
                double hz = Math.sqrt((1 - u) / (1 + (a2 - 1) * u));
                double radial = Math.sqrt(Math.max(0, 1 - hz * hz));
                lx[i] = 2 * hz * radial * Math.cos(phi); ly[i] = 2 * hz * radial * Math.sin(phi);
                lz[i] = 2 * hz * hz - 1;
            }
            for (int y = 0; y < rows; y++) {
                double theta = Math.PI * (y + .5) / rows, nz = Math.cos(theta), radial = Math.sin(theta);
                for (int x = 0; x < columns; x++) {
                    double phi = 2 * Math.PI * ((x + .5) / columns - .5), cp = Math.cos(phi), sp = Math.sin(phi);
                    int at = compact ? (y * columns + x) * 3
                          : ((TOP + level / 3 * (HEIGHT + 2) + y + 1) * ATLAS_SIZE
                                + level % 3 * (WIDTH + 2) + x + 1) * 4;
                    double weight = 0;
                    int samples = level == 0 ? 1 : sampleCount;
                    if (generated && level == LEVELS - 1) {
                        // At roughness one GGX's normalized environment kernel is the diffuse cosine kernel.
                        // Direct quadrature avoids a sparse stochastic lobe bias in the map's diffuse light.
                        for (int i = 0; i < hemisphere.length; i += 6) {
                            float cosine = (float) (radial * cp * hemisphere[i] + radial * sp * hemisphere[i + 1]
                                  + nz * hemisphere[i + 2]);
                            if (cosine <= 0) { continue; }
                            for (int c = 0; c < 3; c++) { filtered[at + c] += cosine * hemisphere[i + 3 + c]; }
                        }
                    } else {
                        for (int i = 0; i < samples; i++) {
                            if (lz[i] <= 0) { continue; }
                            sample(source, width, height, radial * cp * lz[i] - sp * lx[i] - nz * cp * ly[i],
                                  radial * sp * lz[i] + cp * lx[i] - nz * sp * ly[i], nz * lz[i] + radial * ly[i],
                                  (float) lz[i], filtered, at);
                            weight += lz[i];
                        }
                        for (int c = 0; c < 3; c++) { filtered[at + c] /= (float) weight; }
                    }
                    if (!compact) { result[at + 3] = 1; }
                }
            }
            if (compact) {
                for (int y = 0; y < HEIGHT; y++) {
                    double theta = Math.PI * (y + .5) / HEIGHT, z = Math.cos(theta), radial = Math.sin(theta);
                    for (int x = 0; x < WIDTH; x++) {
                        double phi = 2 * Math.PI * ((x + .5) / WIDTH - .5);
                        int at = ((TOP + level / 3 * (HEIGHT + 2) + y + 1) * ATLAS_SIZE
                              + level % 3 * (WIDTH + 2) + x + 1) * 4;
                        sample(filtered, columns, rows, radial * Math.cos(phi), radial * Math.sin(phi), z, 1, result, at);
                        result[at + 3] = 1;
                    }
                }
            }
            if (generated && level == LEVELS - 1) {
                // Longitude is undefined at a pole. Use its exact integral throughout the first half-pixel cap,
                // rather than letting the small filter grid tilt level-ground illumination with its azimuth.
                for (int x = 0; x < WIDTH; x++) {
                    int top = ((TOP + level / 3 * (HEIGHT + 2) + 1) * ATLAS_SIZE
                          + level % 3 * (WIDTH + 2) + x + 1) * 4;
                    int bottom = top + (HEIGHT - 1) * ATLAS_SIZE * 4;
                    result[top] = diffuse.r; result[top + 1] = diffuse.g; result[top + 2] = diffuse.b;
                    result[bottom] = 0; result[bottom + 1] = 0; result[bottom + 2] = 0;
                }
            }
            for (int y = 0; y < HEIGHT + 2; y++) {
                for (int x = 0; x < WIDTH + 2; x++) {
                    if (x > 0 && x <= WIDTH && y > 0 && y <= HEIGHT) { continue; }
                    int startX = level % 3 * (WIDTH + 2), startY = TOP + level / 3 * (HEIGHT + 2);
                    int from = ((startY + Math.clamp(y, 1, HEIGHT)) * ATLAS_SIZE
                          + startX + (x == 0 ? WIDTH : x == WIDTH + 1 ? 1 : x)) * 4;
                    int to = ((startY + y) * ATLAS_SIZE + startX + x) * 4;
                    System.arraycopy(result, from, result, to, 4);
                }
            }
        }
        return result;
    }

    /** Upper-hemisphere quadrature directions and RGB radiance times solid angle / pi. */
    private static float[] hemisphere(float[] source, int width, int height) {
        float[] samples = new float[width * height / 2 * 6];
        for (int y = 0; y < height / 2; y++) {
            double theta = Math.PI * (y + .5) / height, z = Math.cos(theta), radial = Math.sin(theta);
            double weight = 2.0 / width * (Math.cos(Math.PI * y / height) - Math.cos(Math.PI * (y + 1) / height));
            for (int x = 0; x < width; x++) {
                double phi = 2 * Math.PI * ((x + .5) / width - .5);
                int at = (y * width + x) * 6;
                samples[at] = (float) (radial * Math.cos(phi)); samples[at + 1] = (float) (radial * Math.sin(phi));
                samples[at + 2] = (float) z;
                for (int c = 0; c < 3; c++) { samples[at + 3 + c] = (float) (source[(y * width + x) * 3 + c] * weight); }
            }
        }
        return samples;
    }

    private static void sample(float[] source, int width, int height, double x, double y, double z,
          float weight, float[] result, int at) {
        double u = (Math.atan2(y, x) / (2 * Math.PI) + .5) * width - .5;
        double v = Math.acos(Math.clamp(z, -1, 1)) / Math.PI * height - .5;
        int left = (int) Math.floor(u), top = (int) Math.floor(v);
        float fx = (float) (u - left), fy = (float) (v - top);
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int offset = (Math.clamp(top + dy, 0, height - 1) * width + Math.floorMod(left + dx, width)) * 3;
                float w = weight * (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
                for (int c = 0; c < 3; c++) { result[at + c] += source[offset + c] * w; }
            }
        }
    }
}
