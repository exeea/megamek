/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.TextureArrayData;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL11;

/** Actual texture filtering, upload domains and atlas updates, read back as floating-point radiance. */
@Tag("on-demand")
class GpuColorTextureSmokeTest {
    @TempDir
    Path directory;

    @Test
    void filteringAndGeneratedMipsAverageLinearRgbWithoutTransformingAlphaOrData() {
        nativeCheck(() -> {
            Pixmap pixels = pattern();
            Texture color = GpuColorTexture.create(pixels, true);
            pixels.dispose();
            pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixels.setBlending(Pixmap.Blending.None);
            pixels.drawPixel(0, 0, 0x80808080);
            Texture data = new Texture(pixels);
            pixels.dispose();
            var radiance = BufferUtils.newFloatBuffer(4);
            radiance.put(new float[] { 2, .18f, .5f, .25f }).flip();
            Texture hdr = GpuColorTexture.linear(radiance, 1, 1, false);
            try (var sample = new Sampler(false)) {
                color.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                assertPixel(sample.read(color, .5f, .5f, 0, 0), .5, .5, .5, 128.0 / 255, .001,
                      "Bilinear color filtering must decode before averaging");
                assertPixel(sample.read(color, .5f, .5f, 0, 1), .5, .5, .5, 128.0 / 255, .004,
                      "Generated sRGB mips average linear light, with only storage quantization");
                assertPixel(sample.read(data, .5f, .5f, 0, 0), 128.0 / 255, 128.0 / 255, 128.0 / 255,
                      128.0 / 255, .001, "Data textures retain all numerical channels");
                assertTrue(GpuColorTexture.isColor(hdr), "Direct linear uploads share the scene color contract");
                assertPixel(sample.read(hdr, .5f, .5f, 0, 0), 2, .18, .5, .25, .001,
                      "CPU linear radiance uploads preserve HDR without encoding or alpha conversion");
                // Grayscale source art expands channel layout before its core-GL sRGB upload.
                Pixmap grey = new Pixmap(1, 1, Pixmap.Format.LuminanceAlpha);
                // Seed the file-decoder's L/A byte layout directly: this libGDX version's drawPixel conversion
                // swaps these channels, which would test a malformed fixture instead of the texture uploader.
                grey.getPixels().put(0, (byte) 128).put(1, (byte) 64);
                assertEquals(0x80808040, grey.getPixel(0, 0), "The fixture contains gray RGB and independent alpha");
                Texture greyColor = GpuColorTexture.create(grey, false);
                try {
                    assertPixel(sample.read(greyColor, .5f, .5f, 0, 0), .21586050, .21586050, .21586050,
                          64.0 / 255, .001, "Grayscale artwork keeps its decoded RGB and raw coverage");
                } finally { greyColor.dispose(); grey.dispose(); }
                Pixmap mask = new Pixmap(1, 1, Pixmap.Format.Alpha);
                mask.getPixels().put(0, (byte) 64);
                Texture maskColor = GpuColorTexture.create(mask, false);
                try {
                    assertPixel(sample.read(maskColor, .5f, .5f, 0, 0), 1, 1, 1, 64.0 / 255, .001,
                          "Caller-created alpha-only artwork remains a coverage mask");
                } finally { maskColor.dispose(); mask.dispose(); }
            } finally {
                hdr.dispose(); data.dispose(); color.dispose();
            }
        });
    }

    @Test
    void grayscaleModelImagesPreserveColorAndDataForEmbeddedFilesAndManagedReloads() {
        nativeCheck(() -> {
            try (var sample = new Sampler(false)) {
                for (String name : List.of("gray.png", "gray-alpha.png", "gray.jpg")) {
                    boolean alpha = name.contains("alpha");
                    String format = name.endsWith("png") ? "png" : "jpg";
                    byte[] encoded = grayscaleImage(format, alpha);
                    Pixmap decoded = new Pixmap(encoded, 0, encoded.length);
                    try {
                        assertEquals(alpha ? Pixmap.Format.LuminanceAlpha : Pixmap.Format.Alpha, decoded.getFormat(),
                              "The encoded fixture really exercises a grayscale decoder layout");
                        assertEquals(128, Byte.toUnsignedInt(decoded.getPixels().get(0)));
                    } finally { decoded.dispose(); }
                    for (boolean external : new boolean[] { false, true }) {
                        var fixture = new RigidGlbTest();
                        fixture.directory = Files.createDirectories(directory.resolve(name + (external ? "-file" : "-embedded")));
                        var data = (RigidGlb.Data) RigidGlb.loadLods(fixture.embedded(format, encoded)).getFirst();
                        if (external) {
                            Path image = fixture.directory.resolve(name);
                            Files.write(image, encoded);
                            data.images.replaceAll((key, value) -> new RigidGlb.Image(key, null, image.toString(),
                                  value.minFilter(), value.magFilter(), value.wrapS(), value.wrapT()));
                        }
                        var cache = new HashMap<String, Texture>();
                        var model = ModelTextures.create(data, cache, (key, color) -> {
                            throw new AssertionError("The GLB image descriptor resolves its own file or embedded bytes");
                        });
                        try {
                            Texture color = model.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse)
                                  .textureDescription.texture;
                            Texture numerical = model.materials.first().get(GpuModelMaterial.class, GpuModelMaterial.TYPE).map;
                            double coverage = alpha ? 64.0 / 255 : 1;
                            for (int upload = 0; upload < 2; upload++) {
                                assertTrue(GpuColorTexture.isColor(color));
                                assertFalse(GpuColorTexture.isColor(numerical));
                                for (int mip = 0; mip < 2; mip++) {
                                    assertPixel(sample.read(color, .5f, .5f, 0, mip), .21586050, .21586050, .21586050,
                                          coverage, .001, name + " color luminance and coverage");
                                    assertPixel(sample.read(numerical, .5f, .5f, 0, mip), 128.0 / 255, 128.0 / 255, 128.0 / 255,
                                          coverage, .001, name + " numerical luminance and coverage");
                                }
                                if (upload == 0) {
                                    assertTrue(color.isManaged());
                                    assertTrue(numerical.isManaged());
                                    color.load(color.getTextureData());
                                    numerical.load(numerical.getTextureData());
                                }
                            }
                        } finally {
                            model.dispose();
                            cache.values().forEach(Texture::dispose);
                        }
                    }
                }
            }
        });
    }

    private static byte[] grayscaleImage(String format, boolean alpha) throws Exception {
        var model = new java.awt.image.ComponentColorModel(java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY),
              alpha, false, alpha ? java.awt.Transparency.TRANSLUCENT : java.awt.Transparency.OPAQUE, java.awt.image.DataBuffer.TYPE_BYTE);
        var raster = java.awt.image.Raster.createInterleavedRaster(java.awt.image.DataBuffer.TYPE_BYTE, 2, 2, alpha ? 2 : 1, null);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                raster.setSample(x, y, 0, 128);
                if (alpha) { raster.setSample(x, y, 1, 64); }
            }
        }
        var image = new BufferedImage(model, raster, false, null);
        var encoded = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, format, encoded));
        return encoded.toByteArray();
    }

    @Test
    void oddSizedPaintMipsPreserveEdgeColorAndCoverage() {
        nativeCheck(() -> {
            var load = GpuAssets.class.getDeclaredMethod("paintTexture", java.io.File.class);
            load.setAccessible(true);
            try (var sample = new Sampler(false)) {
                for (boolean vertical : new boolean[] { false, true }) {
                    for (boolean transparent : new boolean[] { false, true }) {
                        var pixels = new Pixmap(vertical ? 1 : 3, vertical ? 3 : 1, Pixmap.Format.RGBA8888);
                        pixels.setBlending(Pixmap.Blending.None);
                        pixels.setColor(transparent ? 0x00000000 : 0x000000FF);
                        pixels.fill();
                        pixels.drawPixel(vertical ? 0 : 2, vertical ? 2 : 0, 0xFFFFFFFF);
                        var file = directory.resolve("edge-" + vertical + "-" + transparent + ".png").toFile();
                        try { PixmapIO.writePNG(new FileHandle(file), pixels); } finally { pixels.dispose(); }
                        Texture texture = (Texture) load.invoke(null, file);
                        try {
                            double color = transparent ? 1 : 1.0 / 3, alpha = transparent ? 1.0 / 3 : 1;
                            assertPixel(sample.read(texture, .5f, .5f, 0, 1), color, color, color, alpha, .004,
                                  "The odd edge contributes its full area to straight-alpha linear paint mips");
                        } finally { texture.dispose(); }
                    }
                }
            }
        });
    }

    @Test
    void sceneAtlasUpdatesAndReloadsKeepSrgbWhileUiAndNormalPagesStayNumeric() {
        nativeCheck(() -> {
            var scene = new GpuTextures<String>(true, true);
            var ui = new GpuTextures<String>(true);
            var first = pixels(0x80808080);
            var second = pixels(0x40404040);
            var normal = pixels(0x4080C080);
            try (var sample = new Sampler(false)) {
                var uploads = new java.util.ArrayList<Integer>();
                int[] mipBuilds = { 0 };
                var originalGl = Gdx.gl20;
                try (var watch = new GpuGlWatch((proxy, method, arguments) -> {
                    if (method.getName().equals("glTexImage2D")) { uploads.add((Integer) arguments[2]); }
                    if (method.getName().equals("glGenerateMipmap")) { mipBuilds[0]++; }
                    return method.invoke(originalGl, arguments);
                })) {
                    scene.update(Map.of("a", first), Map.of("a", normal));
                }
                assertEquals(2, uploads.size(), "One color and one normal page need one initial upload each");
                assertEquals(1, uploads.stream().filter(format -> format == com.badlogic.gdx.graphics.GL30.GL_SRGB8_ALPHA8).count(),
                      "Only the color page uses sRGB storage, from its very first upload");
                assertEquals(2, mipBuilds[0], "Generate the two final page mip chains once each");
                ui.update(Map.of("a", first));
                TextureRegion region = scene.region("a");
                assertTrue(GpuColorTexture.isColor(region.getTexture()));
                assertFalse(GpuColorTexture.isColor(ui.region("a").getTexture()));
                assertEquals(.21586050, sample.read(region)[0], .001);
                assertEquals(128.0 / 255, sample.read(ui.region("a"))[0], .001);
                Texture normalPage = scene.normal(region.getTexture());
                assertFalse(GpuColorTexture.isColor(normalPage));
                assertPixel(sample.read(normalPage, centerU(region), centerV(region), 0, 0),
                      64.0 / 255, 128.0 / 255, 192.0 / 255, 128.0 / 255, .001,
                      "Normal atlas channels are data, including alpha");

                assertFalse(scene.update(Map.of("a", second), Map.of("a", normal)), "An in-place repaint keeps its UVs");
                assertSame(region, scene.region("a"));
                assertPixel(sample.read(region), .05126946, .05126946, .05126946, 64.0 / 255, .001,
                      "Partial page upload retains sRGB storage and straight alpha");
                region.getTexture().load(region.getTexture().getTextureData());
                assertEquals(.05126946, sample.read(region)[0], .001, "Managed reupload uses the updated page backing pixels");

                // A new slot shares the existing page; its upload must retain the color storage contract.
                scene.update(Map.of("a", second, "b", first), Map.of("a", normal, "b", normal));
                assertEquals(.05126946, sample.read(scene.region("a"))[0], .001);
                assertEquals(.21586050, sample.read(scene.region("b"))[0], .001);
                assertTrue(GpuColorTexture.isColor(scene.region("b").getTexture()));
            } finally {
                ui.dispose(); scene.dispose();
            }
        });
    }

    @Test
    void appendedAtlasPagesKeepManagedPixelsUntilReplacementPublishes() {
        nativeCheck(() -> {
            var scene = new GpuTextures<String>(true, true);
            scene.retainReplacedPages();
            var first = pixels(0x80808080, 100);
            var second = pixels(0x40404040, 100);
            var normal = pixels(0x4080C080, 100);
            try (var sample = new Sampler(false)) {
                scene.update(Map.of("a", first), Map.of("a", normal));
                scene.publish();
                TextureRegion original = scene.region("a");
                // Two 100px slots cannot share the original 128px page. Adding a key appends a page,
                // rather than repainting or rebuilding the page already borrowed by displayed meshes.
                scene.update(Map.of("a", first, "b", second), Map.of("a", normal, "b", normal));
                assertSame(original, scene.region("a"));
                TextureRegion added = scene.region("b");
                assertTrue(original.getTexture() != added.getTexture(), "Exercise a genuinely new atlas page");
                var oldPages = List.of(original.getTexture(), added.getTexture(),
                      scene.normal(original.getTexture()), scene.normal(added.getTexture()));
                var handles = oldPages.stream().map(Texture::getTextureObjectHandle).toList();
                // An oversized replacement forces a new atlas pair while published old pages stay alive.
                scene.update(Map.of("a", pixels(0x80808080, 130), "b", second),
                      Map.of("a", pixels(0x4080C080, 130), "b", normal));
                for (Texture texture : oldPages) { texture.load(texture.getTextureData()); }
                assertEquals(.21586050, sample.read(original)[0], .001,
                      "Retired original page still owns its managed backing pixels");
                assertEquals(.05126946, sample.read(added)[0], .001,
                      "Retired appended page still owns its managed backing pixels");
                scene.releaseRetiredPages();
                for (int handle : handles) { assertTrue(org.lwjgl.opengl.GL11.glIsTexture(handle)); }
                scene.publish();
                scene.releaseRetiredPages();
                for (Texture texture : oldPages) { assertEquals(0, texture.getTextureObjectHandle()); }
                for (int handle : handles) { assertFalse(org.lwjgl.opengl.GL11.glIsTexture(handle)); }
                TextureRegion current = scene.region("a");
                current.getTexture().load(current.getTexture().getTextureData());
                assertEquals(.21586050, sample.read(current)[0], .001);
                assertPixel(sample.read(scene.normal(current.getTexture()), centerU(current), centerV(current), 0, 0),
                      64.0 / 255, 128.0 / 255, 192.0 / 255, 128.0 / 255, .001,
                      "Replacement normal page keeps raw channels and matching UVs");
            } finally { scene.dispose(); }
        });
    }

    @Test
    void mixedMaterialArraysDecodeOnlyColorRgbAndResizeInLinearSpace() {
        nativeCheck(() -> {
            FileHandle constant = new FileHandle(directory.resolve("constant.png").toFile());
            FileHandle varying = new FileHandle(directory.resolve("varying.png").toFile());
            FileHandle gray = new FileHandle(directory.resolve("gray.png").toFile());
            Files.write(gray.file().toPath(), grayscaleImage("png", false));
            Pixmap pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixels.setBlending(Pixmap.Blending.None);
            pixels.drawPixel(0, 0, 0x80808080);
            try { PixmapIO.writePNG(constant, pixels); } finally { pixels.dispose(); }
            pixels = pattern();
            try { PixmapIO.writePNG(varying, pixels); } finally { pixels.dispose(); }
            // Exercise the real array loader with differing source sizes without replacing installed assets.
            var constructor = Class.forName(GpuAssets.class.getName() + "$SculptArrayData")
                  .getDeclaredConstructor(List.class, int.class, boolean.class);
            constructor.setAccessible(true);
            var upload = (TextureArrayData) constructor.newInstance(List.of(constant, constant, varying, varying, gray, gray), 2, false);
            TextureArray array = new TextureArray(upload);
            try (var sample = new Sampler(true)) {
                assertPixel(sample.read(array, .5f, .5f, 0, 0), .21586050, .21586050, .21586050,
                      128.0 / 255, .001, "Only RGB in each color layer is decoded");
                assertPixel(sample.read(array, .5f, .5f, 1, 0), 128.0 / 255, 128.0 / 255, 128.0 / 255,
                      128.0 / 255, .001, "Normal/data layers retain their numerical values");
                assertPixel(sample.read(array, .5f, .5f, 2, 0), .5, .5, .5, 128.0 / 255, .001,
                      "Resizing a black/white color layer averages linear RGB and untouched alpha");
                assertPixel(sample.read(array, .5f, .5f, 3, 0), .5, .5, .5, 128.0 / 255, .001,
                      "Resizing a data layer never applies a color transfer");
                assertPixel(sample.read(array, .5f, .5f, 4, 0), .21586050, .21586050, .21586050, 1, .001,
                      "File-decoded one-channel artwork is opaque grayscale in color array layers");
                assertPixel(sample.read(array, .5f, .5f, 5, 0), 128.0 / 255, 128.0 / 255, 128.0 / 255, 1, .001,
                      "File-decoded one-channel data remains grayscale in numerical array layers");
            } finally { array.dispose(); }
        });
    }

    private static Pixmap pattern() {
        Pixmap pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < 2; y++) {
            pixels.drawPixel(0, y, 0x00000040);
            pixels.drawPixel(1, y, 0xFFFFFFC0);
        }
        return pixels;
    }

    @Test
    void composedArtworkResizesLinearRgbAndPreservesCoverage() {
        nativeCheck(() -> {
            String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
            System.out.println("Material storage renderer: " + renderer);
            String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer", "");
            assertTrue(renderer.toLowerCase(java.util.Locale.ROOT)
                  .contains(expectedRenderer.toLowerCase(java.util.Locale.ROOT)), renderer);
            Pixmap pixels = new Pixmap(2, 1, Pixmap.Format.RGBA8888);
            pixels.setBlending(Pixmap.Blending.None);
            pixels.drawPixel(0, 0, 0x00000000);
            pixels.drawPixel(1, 0, 0xFFFFFFFF);
            var source = new GpuLinearImage(pixels);
            pixels.dispose();
            var resized = new GpuLinearImage(1, 1);
            resized.draw(source, 0, 0, 2, 1, 0, 0, 1, 1);
            Texture texture = resized.texture(false);
            Texture mipmapped = source.texture(true);
            try (var sample = new Sampler(false)) {
                for (Texture composed : new Texture[] { texture, mipmapped }) {
                    assertTrue(GpuColorTexture.isColor(composed));
                    composed.bind();
                    assertEquals(GL30.GL_SRGB8_ALPHA8,
                          GL11.glGetTexLevelParameteri(GL20.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT),
                          "Bounded composed artwork occupies four bytes per texel");
                }
                var linear = resized.buffer();
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(.5f, linear.get(channel), "Composition averages linear RGB and independent coverage");
                }
                // Linear .5 rounds to sRGB byte 188; coverage .5 rounds independently to byte 128.
                texture.bind();
                var stored = BufferUtils.newByteBuffer(4);
                GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, stored);
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(channel == 3 ? 128 : 188, Byte.toUnsignedInt(stored.get(channel)),
                          "CPU composition precedes the one sRGB storage encode channel=" + channel);
                }
                // Compare identical stored bytes through the same hardware decoder and RGBA16F target.
                Pixmap referencePixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
                Texture reference;
                try {
                    referencePixels.setBlending(Pixmap.Blending.None);
                    referencePixels.drawPixel(0, 0, 0xBCBCBC80);
                    reference = GpuColorTexture.create(referencePixels, false);
                } finally { referencePixels.dispose(); }
                try {
                    float[] expected = sample.read(reference, .5f, .5f, 0, 0);
                    float[] actual = sample.read(texture, .5f, .5f, 0, 0);
                    assertPixel(actual, expected[0], expected[1], expected[2], expected[3], 0,
                          "Composed and fixed-byte artwork have exactly the same sampling result");
                    assertEquals(128.0 / 255, actual[3], .001, "Sampling preserves independent coverage");
                } finally { reference.dispose(); }
                mipmapped.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                assertPixel(sample.read(mipmapped, .5f, .5f, 0, 1), .5, .5, .5, .5, .004,
                      "Composed texture mips still average linear RGB and independent alpha within storage quantization");
            } finally { mipmapped.dispose(); texture.dispose(); }
        });
    }

    @Test
    void sculptRoughnessKeepsRawValuesMetadataAndOwnership() {
        nativeCheck(() -> {
            var previous = megamek.common.Configuration.dataDir();
            megamek.common.Configuration.setDataDir(directory.toFile());
            var assets = new GpuAssets();
            int unpack = GL11.glGetInteger(GL20.GL_UNPACK_ALIGNMENT);
            try (var sample = new Sampler(true)) {
                Path sculpt = Files.createDirectories(directory.resolve("models/board/textures/terrain/surfaces"));
                for (String name : List.of("mapped", "scalar", "legacy")) { sculptPair(sculpt, name); }
                Files.writeString(sculpt.resolve("mapped.json"), """
                      {"tile":3,"roughness":0.93,"roughness_map":"roughness.png"}
                      """);
                Files.writeString(sculpt.resolve("scalar.json"), "{\"tile\":3,\"roughness\":0.27}");
                Files.writeString(sculpt.resolve("legacy.json"), "{\"tile\":3}");
                int[] values = { 17, 61, 103, 149, 193, 239 };
                scalarPng(sculpt.resolve("roughness.png"), 3, 2, values);
                Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 8);
                var material = assets.sculptRoughness(List.of("mapped", "scalar", "legacy"));
                assertEquals(8, GL11.glGetInteger(GL20.GL_UNPACK_ALIGNMENT), "Odd rows must restore unpack state");
                assertSame(material, assets.sculptRoughness(List.of("mapped", "scalar", "legacy")));
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                      new float[] { .93f, 0, .27f, -1, -1, -1 }, material.metadata());
                material.maps().bind();
                assertEquals(GL30.GL_R8, GL11.glGetTexLevelParameteri(GL30.GL_TEXTURE_2D_ARRAY, 0,
                      GL11.GL_TEXTURE_INTERNAL_FORMAT), "Roughness must use compact numerical storage");
                assertEquals(3, material.maps().getWidth());
                assertEquals(2, material.maps().getHeight());
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 3; x++) {
                        assertEquals(values[y * 3 + x] / 255.0,
                              sample.read(material.maps(), (x + .5f) / 3, (y + .5f) / 2, 0, 0)[0], .0006,
                              "Raw roughness must not be sRGB-decoded, resized or row-padded");
                    }
                }
                assertAuthoredRoughness(material);
                int handle = material.maps().getTextureObjectHandle();
                assertTrue(GL11.glIsTexture(handle));
                assets.dispose();
                assertEquals(0, material.maps().getTextureObjectHandle());
                assertFalse(GL11.glIsTexture(handle), "The asset owner releases the array");
                var reloaded = assets.sculptRoughness(List.of("mapped", "scalar", "legacy"));
                org.junit.jupiter.api.Assertions.assertNotSame(material, reloaded);
                assertEquals(103 / 255.0, sample.read(reloaded.maps(), 2.5f / 3, .25f, 0, 0)[0], .0006);
            } finally {
                assets.dispose();
                Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, unpack);
                megamek.common.Configuration.setDataDir(previous);
            }
        });
    }

    /** Use the actual shared shader with provider metadata, including a map and scalar on the same material. */
    private static void assertAuthoredRoughness(GpuAssets.SculptRoughness material) {
        var quad = GpuAtmosphere.screenQuad();
        var shader = GpuGlsl.compile("Authored roughness contract", GpuShaderSource.read("atmosphere.vert"),
              "#version 330 core\n#define SCULPT_MATERIAL_COUNT 3\n"
                    + GpuShaderSource.read("terrain-roughness.glsl") + """
                    out vec4 fragColor;
                    uniform float u_material;
                    void main() {
                        float roughness = authoredRoughness(u_material, vec2(.5 / 3.0, .25), mat2(0.0), .81);
                        fragColor = vec4(vec3(roughness), 1.0);
                    }
                    """);
        try {
            GpuAtmosphere.screenState();
            material.maps().bind(0);
            shader.bind();
            shader.setUniformi("u_roughnessLayers", 0);
            shader.setUniform2fv("u_sculptRoughness[0]", material.metadata(), 0, material.metadata().length);
            float[] expected = { 17 / 255f, .27f, .81f };
            for (int i = 0; i < expected.length; i++) {
                shader.setUniformf("u_material", i * 2f);
                quad.render(shader, GL20.GL_TRIANGLES);
                var actual = BufferUtils.newByteBuffer(4);
                Gdx.gl.glReadPixels(1, 1, 1, 1, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, actual);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(expected[i] * 255, Byte.toUnsignedInt(actual.get(channel)), 1,
                          "Actual shader precedence: authored map, scalar, then legacy fallback");
                }
            }
        } finally { shader.dispose(); quad.dispose(); }
    }

    @Test
    void sculptRoughnessRejectsAmbiguousOrIncompleteAuthoring() {
        nativeCheck(() -> {
            var previous = megamek.common.Configuration.dataDir();
            megamek.common.Configuration.setDataDir(directory.toFile());
            try {
                Path sculpt = Files.createDirectories(directory.resolve("models/board/textures/terrain/surfaces"));
                sculptPair(sculpt, "first");
                sculptPair(sculpt, "second");
                Files.writeString(sculpt.resolve("first.json"), """
                      {"tile":3,"roughness_map":"first-roughness.png"}
                      """);
                Files.writeString(sculpt.resolve("second.json"), """
                      {"tile":3,"roughness_map":"second-roughness.png"}
                      """);
                scalarPng(sculpt.resolve("first-roughness.png"), 3, 2, new int[] { 1, 2, 3, 4, 5, 6 });
                scalarPng(sculpt.resolve("second-roughness.png"), 2, 2, new int[] { 1, 2, 3, 4 });
                assertRoughnessRejected("no implicit resize");
                var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
                image.setRGB(1, 1, 0x204080);
                javax.imageio.ImageIO.write(image, "png", sculpt.resolve("second-roughness.png").toFile());
                assertRoughnessRejected("scalar data");
                image = new BufferedImage(3, 2, BufferedImage.TYPE_USHORT_GRAY);
                image.getRaster().setSample(1, 1, 0, 32768);
                javax.imageio.ImageIO.write(image, "png", sculpt.resolve("second-roughness.png").toFile());
                assertRoughnessRejected("non-indexed PNG8");
                Files.delete(sculpt.resolve("second-roughness.png"));
                assertRoughnessRejected("Missing sculpt roughness map");
                scalarPng(sculpt.resolve("second-roughness.png"), 3, 2, new int[] { 1, 2, 3, 4, 5, 6 });
                Files.delete(sculpt.resolve("first-normal.png"));
                assertRoughnessRejected("first-normal.png");
            } finally { megamek.common.Configuration.setDataDir(previous); }
        });
    }

    private static void assertRoughnessRejected(String message) {
        var assets = new GpuAssets();
        try {
            var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                  () -> assets.sculptRoughness(List.of("first", "second")));
            assertTrue(error.getMessage().contains(message), error.getMessage());
        } finally { assets.dispose(); }
    }

    @Test
    void arrayBackedEarthMatchesTheCompleteSeparateMaterialAndRejectsMissingFiles() {
        nativeCheck(() -> {
            var previous = megamek.common.Configuration.dataDir();
            megamek.common.Configuration.setDataDir(directory.toFile());
            try (var array = new Sampler(true); var texture = new Sampler(false)) {
                Path sculpt = Files.createDirectories(directory.resolve("models/board/textures/terrain/surfaces"));
                for (String missing : List.of("none", "earth.png", "earth-normal.png", "earth.json")) {
                    sculptPair(sculpt, "earth");
                    Files.writeString(sculpt.resolve("earth.json"), "{\"tile\":3}");
                    if (!missing.equals("none")) { Files.delete(sculpt.resolve(missing)); }
                    var assets = new GpuAssets();
                    try {
                        if (!missing.equals("none")) {
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                  () -> assets.sculpt("earth"));
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                  () -> assets.sculptArray(List.of("earth")));
                            continue;
                        }
                        var separate = assets.sculpt("earth");
                        var combined = assets.sculptArray(List.of("earth"));
                        float[] color = texture.read(separate.color(), .5f, .5f, 0, 0);
                        float[] normal = texture.read(separate.normal(), .5f, .5f, 0, 0);
                        org.junit.jupiter.api.Assertions.assertArrayEquals(color,
                              array.read(combined, .5f, .5f, 0, 0), .001f, "Complete color pair");
                        org.junit.jupiter.api.Assertions.assertArrayEquals(normal,
                              array.read(combined, .5f, .5f, 1, 0), .001f, "Complete normal pair");
                        assertEquals(3, assets.sculptTile("earth"));
                    } finally { assets.dispose(); }
                }
            } finally { megamek.common.Configuration.setDataDir(previous); }
        });
    }

    private static void sculptPair(Path sculpt, String name) throws Exception {
        GpuAssetReloadTest.png(sculpt.resolve(name + ".png"), 0x401080e0);
        GpuAssetReloadTest.png(sculpt.resolve(name + "-normal.png"), 0x6040c0f0);
    }

    private static void scalarPng(Path path, int width, int height, int[] values) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) { image.setRGB(x, y, values[y * width + x] * 0x010101); }
        }
        assertTrue(javax.imageio.ImageIO.write(image, "png", path.toFile()));
    }


    private static BoardScene.Pixels pixels(int rgba) { return pixels(rgba, 4); }

    private static BoardScene.Pixels pixels(int rgba, int size) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int argb = rgba >>> 8 | (rgba & 255) << 24;
        for (int y = 0; y < size; y++) { for (int x = 0; x < size; x++) { image.setRGB(x, y, argb); } }
        return new BoardScene.Pixels(image);
    }

    private static float centerU(TextureRegion region) { return (region.getU() + region.getU2()) * .5f; }
    private static float centerV(TextureRegion region) { return (region.getV() + region.getV2()) * .5f; }

    private static void assertPixel(float[] pixel, double r, double g, double b, double a, double tolerance, String message) {
        double[] expected = { r, g, b, a };
        for (int channel = 0; channel < 4; channel++) { assertEquals(expected[channel], pixel[channel], tolerance, message + " channel=" + channel); }
    }

    private static final class Sampler implements AutoCloseable {
        private final FrameBuffer target = GpuHeatGlow.buffer(4, 4);
        private final Mesh quad = GpuAtmosphere.screenQuad();
        private final ShaderProgram shader;

        Sampler(boolean array) {
            String fragment = """
                  #version 330 core
                  layout(location = 0) out vec4 fragColor;
                  uniform SAMPLER u_texture;
                  uniform vec3 u_uv;
                  uniform float u_lod;
                  void main() { fragColor = textureLod(u_texture, COORDINATE, u_lod); }
                  """.replace("SAMPLER", array ? "sampler2DArray" : "sampler2D")
                  .replace("COORDINATE", array ? "u_uv" : "u_uv.xy");
            shader = GpuGlsl.compile("Color texture sampling reference", GpuShaderSource.read("atmosphere.vert"), fragment);
        }

        float[] read(TextureRegion region) { return read(region.getTexture(), centerU(region), centerV(region), 0, 0); }

        float[] read(GLTexture texture, float u, float v, float layer, float lod) {
            target.begin();
            try {
                GpuAtmosphere.screenState();
                texture.bind(0);
                shader.bind();
                shader.setUniformi("u_texture", 0);
                shader.setUniformf("u_uv", u, v, layer);
                shader.setUniformf("u_lod", lod);
                quad.render(shader, GL20.GL_TRIANGLES);
                var pixels = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(2, 2, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
                float[] result = new float[4]; pixels.get(result); return result;
            } finally { target.end(); }
        }

        @Override public void close() { shader.dispose(); quad.dispose(); target.dispose(); }
    }

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    private static void nativeCheck(Check check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(320, 240);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { check.run(); assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError()); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Color texture integration", failure.get()); }
    }
}
