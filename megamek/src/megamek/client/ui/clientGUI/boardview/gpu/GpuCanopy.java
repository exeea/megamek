/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.BufferUtils;
import org.lwjgl.opengl.GL11C;

/**
 * A forest seen from far above, where its trees are too small to draw: per species the colour and cover of its crown
 * as its impostor shows it from straight above (the atlas's last row, impostor-frames.glsl), and per hex the sum over
 * its standing trees. Nothing is authored: a species' values are read once from its own impostor.
 */
final class GpuCanopy {
    /** A crown from above: its mean colour (linear) and the share of its frame's square it covers. */
    record Species(float red, float green, float blue, float cover) { }

    /** The impostor atlas's frames: azimuths a row, rows of elevations, the top view last (impostor-frames.glsl). */
    private static final int AZIMUTHS = 8, ROWS = 4;
    /** Texels across a frame at the mip level read: enough for a mean, and a few kilobytes a species. */
    private static final int FRAME_TEXELS = 16;

    private final Map<String, Species> species = new HashMap<>();

    /** A species' crown from above, read from its impostor's atlas the first time it is asked. */
    Species species(String asset, Texture atlas) {
        return species.computeIfAbsent(asset, key -> read(atlas));
    }

    /**
     * The mean of the atlas's top-view row at the mip level whose frames are about {@link #FRAME_TEXELS} across,
     * weighted by alpha; the cover is the row's mean alpha. The driver decodes a compressed atlas for the read.
     */
    private static Species read(Texture atlas) {
        int level = 0, width = atlas.getWidth(), height = atlas.getHeight();
        while (width / AZIMUTHS > FRAME_TEXELS && width > 1 && height > 1) {
            level++;
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
        atlas.bind();
        // The level may be missing from a chain cut short: its size says so.
        while (level > 0 && GL11C.glGetTexLevelParameteri(GL20.GL_TEXTURE_2D, level, GL11C.GL_TEXTURE_WIDTH) == 0) {
            level--;
        }
        width = GL11C.glGetTexLevelParameteri(GL20.GL_TEXTURE_2D, level, GL11C.GL_TEXTURE_WIDTH);
        height = GL11C.glGetTexLevelParameteri(GL20.GL_TEXTURE_2D, level, GL11C.GL_TEXTURE_HEIGHT);
        ByteBuffer pixels = BufferUtils.newByteBuffer(width * height * 4);
        GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
        GL11C.glGetTexImage(GL20.GL_TEXTURE_2D, level, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
        double red = 0, green = 0, blue = 0, alpha = 0;
        int first = height * (ROWS - 1) / ROWS;
        for (int y = first; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = (y * width + x) * 4;
                double a = (pixels.get(at + 3) & 255) / 255.0;
                red += linear(pixels.get(at) & 255) * a;
                green += linear(pixels.get(at + 1) & 255) * a;
                blue += linear(pixels.get(at + 2) & 255) * a;
                alpha += a;
            }
        }
        if (alpha == 0) { return new Species(0, 0, 0, 0); }
        return new Species((float) (red / alpha), (float) (green / alpha), (float) (blue / alpha),
              (float) (alpha / ((height - first) * (double) width)));
    }

    /** The sRGB decoding of a stored byte. */
    private static double linear(int value) {
        double v = value / 255.0;
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }

    /** A hex's canopy: its trees' crowns summed, colour weighted by area. */
    static final class Hex {
        private double red, green, blue, area;

        /** A tree of this species whose frame's square is {@code side} world units across. */
        void add(Species crown, float side) {
            double covered = crown.cover() * side * side;
            red += crown.red() * covered;
            green += crown.green() * covered;
            blue += crown.blue() * covered;
            area += covered;
        }

        /** Red, green, blue (linear) and the share of {@code hexArea} the crowns cover, at most 1, into {@code out}. */
        void write(float hexArea, float[] out, int at) {
            if (area == 0) { return; }
            out[at] = (float) (red / area);
            out[at + 1] = (float) (green / area);
            out[at + 2] = (float) (blue / area);
            out[at + 3] = (float) Math.min(1, area / hexArea);
        }
    }
}
