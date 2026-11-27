/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Pixmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The texture cache keeps what it encoded; a damaged file is encoded again; normal maps go to BC5 as unit normals. */
class GpuTextureCompressionTest {
    @TempDir static Path cache;

    @BeforeAll
    static void natives() {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        System.setProperty(GpuTextureCompression.DIRECTORY_PROPERTY, cache.toString());
    }

    @Test
    void encodesOnceThenReadsTheFile() throws IOException {
        var first = GpuTextureCompression.prepare(image(48, 48, texel -> new int[] { texel % 251, texel % 97, 40, 255 }),
              GpuTextureCompression.Kind.COLOR).levels();
        assertEquals(GpuTextureCompression.BC1_SRGB, first.format());
        assertEquals(6, first.data().length, "48, 24, 12, 6, 3 and 1 texels across");
        long reads = count("read");
        var again = GpuTextureCompression.prepare(image(48, 48, texel -> new int[] { texel % 251, texel % 97, 40, 255 }),
              GpuTextureCompression.Kind.COLOR).levels();
        assertEquals(reads + 1, count("read"), "the second preparation reads the file");
        assertEquals(List.of(first.data()), List.of(again.data()));
    }

    @Test
    void aDamagedFileIsEncodedAgain() throws IOException {
        Set<Path> before = files();
        var first = GpuTextureCompression.prepare(image(32, 16, texel -> new int[] { 9, texel % 200, 77, 255 }),
              GpuTextureCompression.Kind.DATA).levels();
        Set<Path> written = files();
        written.removeAll(before);
        assertEquals(1, written.size(), "one file kept");
        Path file = written.iterator().next();
        byte[] whole = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(whole, whole.length - 5));
        long encoded = count("encoded");
        var again = GpuTextureCompression.prepare(image(32, 16, texel -> new int[] { 9, texel % 200, 77, 255 }),
              GpuTextureCompression.Kind.DATA).levels();
        assertEquals(encoded + 1, count("encoded"));
        assertEquals(List.of(first.data()), List.of(again.data()));
        assertArrayEquals(whole, Files.readAllBytes(file), "the file is whole again");
    }

    @Test
    void normalMapsGoToBc5AsUnitNormals() {
        // x 0.57, y 0, z 1: 15 % too long, as one of the shipped maps; the programs read its direction.
        var normals = GpuTextureCompression.prepare(image(16, 16, texel -> new int[] { 200, 128, 255, 255 }),
              GpuTextureCompression.Kind.NORMAL).levels();
        assertEquals(GpuTextureCompression.BC5, normals.format());
        int x = bc4First(normals.data()[0], 0), y = bc4First(normals.data()[0], 8);
        float fx = x / 127.5f - 1, fy = y / 127.5f - 1;
        assertEquals(0.495f, fx, 0.01f, "x of the unit normal, not of the stored one");
        assertEquals(0f, fy, 0.01f);
        // A tree impostor's atlas: mean normals with depth in alpha stays RGBA8, untaken.
        var atlas = GpuTextureCompression.prepare(image(16, 16, texel -> new int[] { 128, 128, 255, texel % 255 }),
              GpuTextureCompression.Kind.NORMAL);
        assertNull(atlas.levels());
        atlas.image().dispose();
    }

    private interface Texel { int[] rgba(int texel); }

    private static Pixmap image(int width, int height, Texel texel) {
        Pixmap image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        ByteBuffer pixels = image.getPixels();
        for (int i = 0; i < width * height; i++) {
            for (int value : texel.rgba(i)) { pixels.put((byte) value); }
        }
        pixels.flip();
        return image;
    }

    private static Set<Path> files() throws IOException {
        try (Stream<Path> files = Files.list(cache)) {
            return files.filter(path -> path.toString().endsWith(".ktx")).collect(Collectors.toCollection(HashSet::new));
        }
    }

    /** The census count named {@code what} ("read" or "encoded"). */
    private static long count(String what) {
        Matcher matcher = Pattern.compile(what + " (\\d+)").matcher(GpuTextureCompression.census());
        assertTrue(matcher.find());
        return Long.parseLong(matcher.group(1));
    }

    /** The first texel's value in the BC4 block at {@code offset} (BC5: red at 0, green at 8). */
    private static int bc4First(ByteBuffer level, int offset) {
        int r0 = level.get(offset) & 255, r1 = level.get(offset + 1) & 255, index = level.get(offset + 2) & 7;
        if (index == 0) { return r0; }
        if (index == 1) { return r1; }
        if (r0 > r1) { return ((8 - index) * r0 + (index - 1) * r1) / 7; }
        if (index == 6) { return 0; }
        if (index == 7) { return 255; }
        return ((6 - index) * r0 + (index - 1) * r1) / 5;
    }
}
