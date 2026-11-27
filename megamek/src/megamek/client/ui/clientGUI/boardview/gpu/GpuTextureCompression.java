/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.Configuration;
import megamek.logging.MMLogger;
import org.lwjgl.stb.STBDXT;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Model textures stored block-compressed: colour maps BC1 or BC3 in sRGB, normal maps BC5 (x and y; model-surface.glsl and
 * terrain-foliage.frag rebuild z where a normal map reads blue 0), finish maps BC1 or BC3, heightmaps BC4; nothing needs
 * GL 4.2's BPTC, so macOS's 4.1 takes the same formats. An image is encoded once, its mip levels made as the uncompressed
 * texture's (GpuColorTexture's alpha-weighted colour levels; a box filter for data, as glGenerateMipmap's, each level's
 * normals then made unit as the programs normalise them), by LWJGL's stb_dxt, and kept as a KTX file in the texture cache
 * beside the program cache, keyed by its pixels, its format and the encoder: a later load reads the file instead of
 * encoding. Where the driver lacks a format, or {@code -Dmegamek.gpu.compressedTextures=false} is given, the texture is
 * RGBA8 as before.
 */
final class GpuTextureCompression {
    private static final MMLogger LOGGER = MMLogger.create(GpuTextureCompression.class);
    /** {@code -Dmegamek.gpu.compressedTextures=false} makes every model texture RGBA8, as a driver without the formats has it. */
    static final boolean ON = Boolean.parseBoolean(System.getProperty("megamek.gpu.compressedTextures", "true"));
    /** {@code -Dmegamek.gpu.textureCacheDir=...} keeps the files elsewhere, as the program cache's property does. */
    static final String DIRECTORY_PROPERTY = "megamek.gpu.textureCacheDir";
    /** Part of every file's key: files another encoder or setting made are not read. */
    private static final String ENCODER = "stb_dxt highqual 2 unit normals";

    static final int BC1_SRGB = 0x8C4C, BC3_SRGB = 0x8C4F, BC1 = 0x83F0, BC3 = 0x83F3, BC4 = 0x8DBB, BC5 = 0x8DBD;
    private static final int GL_RED = 0x1903, GL_RG = 0x8227, GL_RGB = 0x1907, GL_RGBA = 0x1908;
    private static final byte[] IDENTIFIER = { (byte) 0xAB, 'K', 'T', 'X', ' ', '1', '1', (byte) 0xBB, '\r', '\n', 0x1A, '\n' };

    /** How a model image's role is stored: its colour (sRGB) with alpha as coverage or paint, a normal, finish data, height. */
    enum Kind { COLOR, PAINT, NORMAL, DATA, HEIGHT }

    /** An image's compressed mip levels, level 0 first. */
    record Levels(int format, int width, int height, ByteBuffer[] data) { }

    /** {@link #prepare}'s result: the levels, or the image itself where it stays as today. */
    record Prepared(Levels levels, Pixmap image) { }

    private static final AtomicLong HITS = new AtomicLong(), ENCODED = new AtomicLong(), ENCODING = new AtomicLong();
    private static Boolean s3tc, srgb;

    private GpuTextureCompression() { }

    /**
     * The storage of a model image read in glTF role {@code usage} ({@code paint}: an albedo whose alpha is a paint mask),
     * or null where it stays as today: the property is off, the role has no compressed form, or the driver lacks it.
     * Call on the GL thread.
     */
    static Kind kind(int usage, boolean paint) {
        if (!ON) { return null; }
        if (s3tc == null) {
            s3tc = Gdx.graphics.supportsExtension("GL_EXT_texture_compression_s3tc");
            srgb = s3tc && Gdx.graphics.supportsExtension("GL_EXT_texture_sRGB");
            // stb_dxt's first call fills its tables: here, before the pool's threads call it together.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                STBDXT.stb_compress_dxt_block(stack.calloc(16), stack.calloc(64), true, STBDXT.STB_DXT_HIGHQUAL);
            }
            LOGGER.info("Compressed model textures: S3TC {}, its sRGB forms {}, RGTC core", s3tc, srgb);
        }
        // RGTC (BC4, BC5) is core since GL 3.0.
        return switch (usage) {
            case ModelTexture.USAGE_DIFFUSE -> srgb ? paint ? Kind.PAINT : Kind.COLOR : null;
            case ModelTexture.USAGE_EMISSIVE -> srgb ? Kind.COLOR : null;
            case ModelTexture.USAGE_NORMAL -> Kind.NORMAL;
            case ModelTexture.USAGE_AMBIENT, GpuMaterialMaps.METALLIC_ROUGHNESS_USAGE -> s3tc ? Kind.DATA : null;
            case GpuMaterialMaps.HEIGHT_USAGE -> Kind.HEIGHT;
            default -> null;
        };
    }

    /**
     * {@code image}'s levels in {@code kind}'s format, read from the cache or encoded and kept there; it takes the image.
     * A normal map whose alpha carries data or whose texels do not face out of the surface (a tree impostor's mean normals
     * and depth) comes back as it is, untaken.
     */
    static Prepared prepare(Pixmap image, Kind kind) {
        byte[] rgba;
        try {
            rgba = rgba(image);
        } catch (RuntimeException | Error failure) { image.dispose(); throw failure; }
        int width = image.getWidth(), height = image.getHeight();
        boolean opaque = opaque(rgba);
        int format = switch (kind) {
            case COLOR -> opaque ? BC1_SRGB : BC3_SRGB;
            case PAINT -> BC3_SRGB;
            case NORMAL -> opaque && facesOut(rgba) ? BC5 : 0;
            case DATA -> opaque ? BC1 : BC3;
            case HEIGHT -> BC4;
        };
        if (format == 0) { return new Prepared(null, image); }
        String key = key(kind, format, width, height, rgba);
        Levels cached = read(key, format, width, height);
        if (cached != null) {
            image.dispose();
            HITS.incrementAndGet();
            return new Prepared(cached, null);
        }
        long start = System.nanoTime();
        byte[][] levels = switch (kind) {
            case COLOR, PAINT -> colorLevels(image, rgba, kind == Kind.PAINT);
            case NORMAL -> unit(dataLevels(image, rgba));
            case DATA, HEIGHT -> dataLevels(image, rgba);
        };
        ByteBuffer[] data = new ByteBuffer[levels.length];
        for (int level = 0, w = width, h = height; level < levels.length; level++, w = Math.max(1, w / 2), h = Math.max(1, h / 2)) {
            data[level] = encode(levels[level], w, h, format);
        }
        ENCODING.addAndGet(System.nanoTime() - start);
        ENCODED.incrementAndGet();
        var result = new Levels(format, width, height, data);
        write(key, result);
        return new Prepared(result, null);
    }

    /** {@link #prepare}'s levels of an image whose first preparation compressed it (a managed texture's reload). */
    static Levels levels(Pixmap image, Kind kind) {
        var prepared = prepare(image, kind);
        if (prepared.levels() == null) {
            prepared.image().dispose();
            throw new IllegalStateException("A compressed texture's image no longer compresses");
        }
        return prepared.levels();
    }

    /** A managed texture of {@code first}, prepared again from {@code again} if the context is restored. */
    static Texture texture(Levels first, Supplier<Levels> again, boolean color) {
        return new Texture(new Data(first, again, color));
    }

    /** For development runs: files read, images encoded and the encoding's summed thread time. */
    static String census() {
        return "texture cache read " + HITS.get() + " encoded " + ENCODED.get() + " encoding ms " + ENCODING.get() / 1_000_000;
    }

    /** The image's texels as RGBA8, as today's upload expands other layouts. */
    private static byte[] rgba(Pixmap image) {
        Pixmap source = image;
        if (image.getFormat() != Pixmap.Format.RGBA8888) {
            source = new Pixmap(image.getWidth(), image.getHeight(), Pixmap.Format.RGBA8888);
            source.setBlending(Pixmap.Blending.None);
            source.drawPixmap(image, 0, 0);
        }
        try {
            byte[] bytes = new byte[image.getWidth() * image.getHeight() * 4];
            source.getPixels().get(0, bytes);
            return bytes;
        } finally {
            if (source != image) { source.dispose(); }
        }
    }

    private static boolean opaque(byte[] rgba) {
        for (int i = 3; i < rgba.length; i += 4) {
            if (rgba[i] != (byte) 255) { return false; }
        }
        return true;
    }

    /** Whether all but 1% of the texels are normals facing out of the surface, at least half a unit long. */
    private static boolean facesOut(byte[] rgba) {
        int off = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            float x = (rgba[i] & 255) / 127.5f - 1, y = (rgba[i + 1] & 255) / 127.5f - 1, z = (rgba[i + 2] & 255) / 127.5f - 1;
            if (x * x + y * y + z * z < .25f || z < 0) { off++; }
        }
        return off <= rgba.length / 400;
    }

    /**
     * Each level's normals made unit in place (RGB, alpha kept): the direction the programs read after normalising, so the
     * z they rebuild from BC5's x and y is today's.
     */
    private static byte[][] unit(byte[][] levels) {
        for (byte[] level : levels) {
            for (int i = 0; i < level.length; i += 4) {
                float x = (level[i] & 255) / 127.5f - 1, y = (level[i + 1] & 255) / 127.5f - 1, z = (level[i + 2] & 255) / 127.5f - 1;
                float length = (float) Math.sqrt(x * x + y * y + z * z);
                if (length < 1e-6f) { continue; }
                level[i] = (byte) Math.round(Math.clamp(x / length * 127.5f + 127.5f, 0f, 255f));
                level[i + 1] = (byte) Math.round(Math.clamp(y / length * 127.5f + 127.5f, 0f, 255f));
                level[i + 2] = (byte) Math.round(Math.clamp(z / length * 127.5f + 127.5f, 0f, 255f));
            }
        }
        return levels;
    }

    /** Level 0 and the colour levels below it as today's texture makes them (GpuColorTexture#allLevels); takes the image. */
    private static byte[][] colorLevels(Pixmap image, byte[] rgba, boolean paint) {
        var cutout = GpuColorTexture.allLevels(image, paint);
        try {
            byte[][] levels = new byte[cutout.levels().length + 1][];
            levels[0] = rgba;
            for (int level = 1; level < levels.length; level++) {
                ByteBuffer below = cutout.levels()[level - 1];
                levels[level] = new byte[below.remaining()];
                below.get(below.position(), levels[level]);
            }
            return levels;
        } finally { cutout.image().dispose(); }
    }

    /** Level 0 and box-filtered levels below it, as glGenerateMipmap makes them; takes the image. */
    private static byte[][] dataLevels(Pixmap image, byte[] rgba) {
        int width = image.getWidth(), height = image.getHeight();
        image.dispose();
        var levels = new ArrayList<byte[]>();
        levels.add(rgba);
        while (width > 1 || height > 1) {
            int w = Math.max(1, width / 2), h = Math.max(1, height / 2);
            byte[] from = levels.getLast(), next = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                int y0 = Math.min(2 * y, height - 1) * width, y1 = Math.min(2 * y + 1, height - 1) * width;
                for (int x = 0; x < w; x++) {
                    int x0 = Math.min(2 * x, width - 1), x1 = Math.min(2 * x + 1, width - 1);
                    for (int c = 0; c < 4; c++) {
                        next[(y * w + x) * 4 + c] = (byte) (((from[(y0 + x0) * 4 + c] & 255) + (from[(y0 + x1) * 4 + c] & 255)
                              + (from[(y1 + x0) * 4 + c] & 255) + (from[(y1 + x1) * 4 + c] & 255) + 2) >> 2);
                    }
                }
            }
            levels.add(next);
            width = w;
            height = h;
        }
        return levels.toArray(byte[][]::new);
    }

    private static int blockBytes(int format) {
        return format == BC1 || format == BC1_SRGB || format == BC4 ? 8 : 16;
    }

    private static int size(int width, int height, int format) {
        return ((width + 3) / 4) * ((height + 3) / 4) * blockBytes(format);
    }

    /** One level's 4x4 blocks; a partial block repeats the level's last column and row. */
    private static ByteBuffer encode(byte[] rgba, int width, int height, int format) {
        int across = (width + 3) / 4, down = (height + 3) / 4, bytes = blockBytes(format);
        ByteBuffer out = BufferUtils.newByteBuffer(across * down * bytes);
        long address = MemoryUtil.memAddress(out);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer block = stack.malloc(64);
            long source = MemoryUtil.memAddress(block);
            for (int by = 0; by < down; by++) {
                for (int bx = 0; bx < across; bx++) {
                    for (int py = 0; py < 4; py++) {
                        int y = Math.min(by * 4 + py, height - 1);
                        for (int px = 0; px < 4; px++) {
                            int from = (y * width + Math.min(bx * 4 + px, width - 1)) * 4, texel = py * 4 + px;
                            switch (format) {
                                case BC4 -> block.put(texel, rgba[from]);
                                case BC5 -> block.put(texel * 2, rgba[from]).put(texel * 2 + 1, rgba[from + 1]);
                                default -> block.put(texel * 4, rgba[from]).put(texel * 4 + 1, rgba[from + 1])
                                      .put(texel * 4 + 2, rgba[from + 2]).put(texel * 4 + 3, rgba[from + 3]);
                            }
                        }
                    }
                    long destination = address + (long) (by * across + bx) * bytes;
                    switch (format) {
                        case BC4 -> STBDXT.nstb_compress_bc4_block(destination, source);
                        case BC5 -> STBDXT.nstb_compress_bc5_block(destination, source);
                        default -> STBDXT.nstb_compress_dxt_block(destination, source, bytes == 16 ? 1 : 0, STBDXT.STB_DXT_HIGHQUAL);
                    }
                }
            }
        }
        return out;
    }

    private static Path directory() {
        String chosen = System.getProperty(DIRECTORY_PROPERTY, "");
        return chosen.isBlank() ? new File(Configuration.configDir(), "gpu-texture-cache").toPath() : Path.of(chosen);
    }

    private static String key(Kind kind, int format, int width, int height, byte[] rgba) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((ENCODER + '\n' + kind + '\n' + Integer.toHexString(format) + '\n' + width + 'x' + height + '\n')
                  .getBytes(StandardCharsets.UTF_8));
            digest.update(rgba);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("Every Java platform provides SHA-256", missing);
        }
    }

    /** A KTX 1.1 file (Khronos): its 64-byte header, no key/value data, each level's size and blocks. */
    private static void write(String key, Levels levels) {
        Path temporary = null;
        try {
            Path directory = directory();
            Files.createDirectories(directory);
            int total = 64;
            for (ByteBuffer level : levels.data()) { total += 4 + level.remaining(); }
            int base = switch (levels.format()) {
                case BC1, BC1_SRGB -> GL_RGB;
                case BC4 -> GL_RED;
                case BC5 -> GL_RG;
                default -> GL_RGBA;
            };
            ByteBuffer file = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
            file.put(IDENTIFIER).putInt(0x04030201).putInt(0).putInt(1).putInt(0).putInt(levels.format()).putInt(base)
                  .putInt(levels.width()).putInt(levels.height()).putInt(0).putInt(0).putInt(1).putInt(levels.data().length)
                  .putInt(0);
            for (ByteBuffer level : levels.data()) { file.putInt(level.remaining()).put(level.duplicate()); }
            temporary = Files.createTempFile(directory, key, ".tmp");
            Files.write(temporary, file.array());
            Path target = directory.resolve(key + ".ktx");
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException plain) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | RuntimeException failure) {
            LOGGER.debug(failure, "Could not keep a compressed texture in the cache");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    /** The cached file's levels, or null: none, or one that does not match (deleted). */
    private static Levels read(String key, int format, int width, int height) {
        Path file = directory().resolve(key + ".ktx");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for (byte expected : IDENTIFIER) {
                if (in.get() != expected) { return damaged(file); }
            }
            if (in.getInt() != 0x04030201 || in.getInt() != 0 || in.getInt() != 1 || in.getInt() != 0 || in.getInt() != format) {
                return damaged(file);
            }
            in.getInt();
            if (in.getInt() != width || in.getInt() != height || in.getInt() != 0 || in.getInt() != 0 || in.getInt() != 1) {
                return damaged(file);
            }
            int count = in.getInt(), keyValues = in.getInt();
            int expectedCount = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
            if (count != expectedCount || keyValues < 0) { return damaged(file); }
            in.position(in.position() + keyValues);
            ByteBuffer[] data = new ByteBuffer[count];
            for (int level = 0, w = width, h = height; level < count; level++, w = Math.max(1, w / 2), h = Math.max(1, h / 2)) {
                int size = in.getInt();
                if (size != size(w, h, format)) { return damaged(file); }
                data[level] = BufferUtils.newByteBuffer(size).put(bytes, in.position(), size).flip();
                in.position(in.position() + size);
            }
            return new Levels(format, width, height, data);
        } catch (BufferUnderflowException | IllegalArgumentException | IndexOutOfBoundsException truncated) {
            return damaged(file);
        }
    }

    private static Levels damaged(Path file) {
        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        return null;
    }

    /** Compressed levels as a texture's data: uploaded level by level, read again from the cache on a context restore. */
    static final class Data implements TextureData {
        private final Supplier<Levels> source;
        /** sRGB colour, as GpuColorTexture#isColor answers. */
        final boolean color;
        private final int width, height;
        private Levels levels;

        Data(Levels first, Supplier<Levels> source, boolean color) {
            this.levels = first;
            this.source = source;
            this.color = color;
            this.width = first.width();
            this.height = first.height();
        }

        @Override public TextureDataType getType() { return TextureDataType.Custom; }
        @Override public boolean isPrepared() { return levels != null; }
        @Override public void prepare() {
            if (levels != null) { throw new IllegalStateException("Compressed texture is already prepared"); }
            levels = source.get();
        }
        @Override public void consumeCustomData(int target) {
            if (levels == null) { throw new IllegalStateException("Compressed texture is not prepared"); }
            try {
                ByteBuffer[] data = levels.data();
                for (int level = 0, w = width, h = height; level < data.length; level++, w = Math.max(1, w / 2), h = Math.max(1, h / 2)) {
                    Gdx.gl.glCompressedTexImage2D(target, level, levels.format(), w, h, 0, data[level].remaining(), data[level]);
                }
            } finally { levels = null; }
        }
        @Override public Pixmap consumePixmap() { throw new UnsupportedOperationException("Compressed texture"); }
        @Override public boolean disposePixmap() { return false; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public Pixmap.Format getFormat() { return Pixmap.Format.RGBA8888; }
        @Override public boolean useMipMaps() { return true; }
        @Override public boolean isManaged() { return true; }
    }
}
