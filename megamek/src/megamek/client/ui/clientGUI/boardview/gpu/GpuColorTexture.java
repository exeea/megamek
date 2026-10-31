/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.Supplier;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;

/** Core-GL image uploads: scene RGB uses sRGB storage, numerical maps and all alpha channels keep their values. */
final class GpuColorTexture {
    private GpuColorTexture() { }

    static boolean isColor(Texture texture) {
        return texture.getTextureData() instanceof ImageData data && data.color
              || texture.getTextureData() instanceof LinearData;
    }

    static Texture create(FileHandle file, boolean mipmaps) {
        return new Texture(new ImageData(() -> decode(file), mipmaps, true, true, true));
    }

    static Texture create(byte[] encoded, boolean mipmaps) {
        return new Texture(new ImageData(() -> decode(encoded), mipmaps, true, true, true));
    }

    /** Numerical maps share core-GL channel layout handling, without applying an RGB transfer function. */
    static Texture createData(FileHandle file, boolean mipmaps) {
        return new Texture(new ImageData(() -> decode(file), mipmaps, true, true, false));
    }

    static Texture createData(byte[] encoded, boolean mipmaps) {
        return new Texture(new ImageData(() -> decode(encoded), mipmaps, true, true, false));
    }

    /**
     * A managed texture of pixels made on demand, such as a board material's packed layout (GpuMaterialMaps): made
     * again, not kept, when the context is restored. {@code color} selects sRGB storage for RGB.
     */
    static Texture create(Supplier<Pixmap> pixels, boolean mipmaps, boolean color) {
        return new Texture(new ImageData(pixels, mipmaps, true, true, color));
    }

    /** File grayscale means opaque luminance; the caller owns the returned pixels. */
    static Pixmap decode(FileHandle file) { return decodedImage(new Pixmap(file)); }

    private static Pixmap decode(byte[] encoded) { return decodedImage(new Pixmap(encoded, 0, encoded.length)); }

    private static Pixmap decodedImage(Pixmap pixels) {
        if (pixels.getFormat() != Pixmap.Format.Alpha) { return pixels; }
        // libGDX names decoded one-channel PNG/JPEG grayscale "Alpha". Resolve that file-format ambiguity here:
        // file samples are opaque luminance, while caller-created Alpha Pixmaps still represent coverage.
        Pixmap expanded = null;
        try {
            // Heap arrays and bulk copies: per-texel buffer calls are slow until the JIT compiles them.
            byte[] gray = new byte[pixels.getWidth() * pixels.getHeight()], rgba = new byte[gray.length * 4];
            expanded = new Pixmap(pixels.getWidth(), pixels.getHeight(), Pixmap.Format.RGBA8888);
            pixels.getPixels().get(0, gray);
            for (int i = 0, at = 0; i < gray.length; i++, at += 4) {
                rgba[at] = rgba[at + 1] = rgba[at + 2] = gray[i];
                rgba[at + 3] = (byte) 255;
            }
            expanded.getPixels().put(0, rgba);
            return expanded;
        } catch (RuntimeException failure) {
            if (expanded != null) { expanded.dispose(); }
            throw failure;
        } finally { pixels.dispose(); }
    }

    /** The caller still owns the Pixmap; the upload completes before this method returns. */
    static Texture create(Pixmap pixels, boolean mipmaps) {
        return new Texture(data(pixels, mipmaps, false));
    }

    /** A packer's managed texture borrows its page Pixmap for context restoration and future page updates. */
    static TextureData data(Pixmap pixels, boolean mipmaps, boolean managed) {
        return new ImageData(() -> pixels, mipmaps, managed, false, true);
    }

    /** Bounded composed RGB is encoded once for sRGB8 storage; alpha remains independent eight-bit coverage. */
    static Texture ldr(float[] linear, int width, int height, boolean mipmaps) {
        var pixels = ldrPixels(linear, width, height);
        try {
            return create(pixels, mipmaps);
        } finally { pixels.dispose(); }
    }

    /** {@link #ldr}'s pixels, encoded without GL; the caller owns them. */
    static Pixmap ldrPixels(float[] linear, int width, int height) {
        if (linear.length != width * height * 4) { throw new IllegalArgumentException("RGBA image dimensions"); }
        var pixels = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            var rgba = pixels.getPixels();
            for (int index = 0; index < linear.length; index++) {
                float value = (index & 3) == 3 ? linear[index] : GpuColor.display(linear[index]);
                rgba.put(index, (byte) Math.round(Math.clamp(value, 0f, 1f) * 255));
            }
            return pixels;
        } catch (RuntimeException | Error failure) { pixels.dispose(); throw failure; }
    }

    /** CPU-composited light/color is uploaded directly, without an intermediate display encoding. */
    static Texture linear(FloatBuffer pixels, int width, int height, boolean mipmaps) {
        Texture texture = new Texture(new LinearData(width, height));
        try {
            texture.bind();
            try {
                Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, width, height, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
            } finally { Reference.reachabilityFence(pixels); }
            if (mipmaps) { Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D); }
            return texture;
        } catch (RuntimeException failure) { texture.dispose(); throw failure; }
    }

    /** Identifies linear color independently of the numerical float textures used for depth and wave fields. */
    private static final class LinearData extends GLOnlyTextureData {
        LinearData(int width, int height) { super(width, height, 0, GL30.GL_RGBA16F, GL20.GL_RGBA, GL20.GL_FLOAT); }
    }

    private static final class ImageData implements TextureData {
        private final Supplier<Pixmap> source;
        private final boolean mipmaps, managed, dispose, color;
        private Pixmap pixels;
        private int width, height;

        ImageData(Supplier<Pixmap> source, boolean mipmaps, boolean managed, boolean dispose, boolean color) {
            this.source = source;
            this.mipmaps = mipmaps;
            this.managed = managed;
            this.dispose = dispose;
            this.color = color;
        }

        @Override public TextureDataType getType() { return TextureDataType.Custom; }
        @Override public boolean isPrepared() { return pixels != null; }
        @Override public void prepare() {
            if (pixels != null) { throw new IllegalStateException("Image texture is already prepared"); }
            pixels = source.get();
            width = pixels.getWidth();
            height = pixels.getHeight();
        }
        @Override public void consumeCustomData(int target) {
            if (pixels == null) { throw new IllegalStateException("Image texture is not prepared"); }
            Pixmap upload = pixels;
            try {
                // Grayscale/alpha Pixmaps use legacy LUMINANCE/ALPHA external formats, unavailable in core GL.
                // Expand channel layout without resizing or changing the authored RGB transfer function.
                if (pixels.getFormat() != Pixmap.Format.RGB888 && pixels.getFormat() != Pixmap.Format.RGBA8888) {
                    upload = new Pixmap(width, height, Pixmap.Format.RGBA8888);
                    upload.setBlending(Pixmap.Blending.None);
                    upload.drawPixmap(pixels, 0, 0);
                }
                Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
                Gdx.gl.glTexImage2D(target, 0, color ? GL30.GL_SRGB8_ALPHA8 : GL30.GL_RGBA8, width, height, 0,
                      upload.getGLFormat(), upload.getGLType(), upload.getPixels());
                if (mipmaps) { Gdx.gl.glGenerateMipmap(target); }
            } finally {
                if (upload != pixels) { upload.dispose(); }
                if (dispose) { pixels.dispose(); }
                pixels = null;
            }
        }
        @Override public Pixmap consumePixmap() { throw new UnsupportedOperationException("Image texture"); }
        @Override public boolean disposePixmap() { return false; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public Pixmap.Format getFormat() { return Pixmap.Format.RGBA8888; }
        @Override public boolean useMipMaps() { return mipmaps; }
        @Override public boolean isManaged() { return managed; }
    }

    /**
     * Raw RGBA8 storage; only a required resize decodes, filters and re-encodes colour RGB.
     * Exact RGBA images borrow the Pixmap's bytes until the synchronous upload completes.
     */
    static ByteBuffer arrayPixels(Pixmap source, int width, int height, boolean color) {
        if (source.getWidth() == width && source.getHeight() == height
              && source.getFormat() == Pixmap.Format.RGBA8888) {
            var upload = source.getPixels().duplicate();
            upload.position(0);
            upload.limit(width * height * 4);
            return upload;
        }
        var upload = com.badlogic.gdx.utils.BufferUtils.newByteBuffer(width * height * 4);
        if (source.getWidth() == width && source.getHeight() == height) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgba = source.getPixel(x, y);
                    upload.put((byte) (rgba >>> 24)).put((byte) (rgba >>> 16))
                          .put((byte) (rgba >>> 8)).put((byte) rgba);
                }
            }
        } else {
            var decoded = new GpuLinearImage(source, color);
            var resized = new GpuLinearImage(width, height);
            resized.draw(decoded, 0, 0, decoded.width, decoded.height, 0, 0, width, height);
            var values = resized.buffer();
            for (int index = 0; index < width * height * 4; index++) {
                float value = values.get(index);
                if (color && (index & 3) != 3) { value = GpuColor.display(value); }
                upload.put((byte) Math.round(Math.clamp(value, 0f, 1f) * 255));
            }
        }
        upload.flip();
        return upload;
    }
}
