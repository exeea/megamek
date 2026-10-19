/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.function.Supplier;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import megamek.common.Configuration;

/**
 * The renderer's GPU layouts of a board material, packed on the CPU from its Godot 4 Standard maps
 * ({@link GodotMaterial}). Files keep Godot's layout; the shaders keep their compact one: height beside colour,
 * occlusion beside the normal, one surface map per material. Packing moves bytes between channels unchanged, with two
 * exceptions: a normal's green turns from Godot and glTF's "up the image" to the board's "down the image" (+V), and
 * Godot's material factors (albedo colour, roughness, metallic, normal scale, subsurface strength) apply.
 */
final class GpuMaterialMaps {
    enum Layout {
        /** Albedo with its alpha coverage. */
        COLOR,
        /** Albedo RGB with height in A: sculpted terrain. */
        COLOR_HEIGHT,
        /** The board's tangent normal (+R along +U, +G along +V) with opaque A. */
        NORMAL,
        /** Normal with ambient occlusion in A: sculpted terrain. */
        NORMAL_OCCLUSION,
        /** Normal XY, roughness in B, ambient occlusion in A: mud and scorch, whose shaders rebuild Z. */
        NORMAL_ROUGHNESS,
        /** Height, roughness, ambient occlusion, relief UV / .1 (Godot's heightmap_scale / 100): roads and magma. */
        RELIEF,
        /** Grain height, roughness, cracks and bubbles ({@code texture_cracks}), cloudiness (subsurface scattering): ice. */
        ICE,
        /** Ambient occlusion, roughness, thin-tissue transmission (subsurface scattering): a glTF occlusion map. */
        OCCLUSION,
        /** glTF metallic-roughness: G roughness, B metallic. */
        METALLIC_ROUGHNESS,
        /** Linear emission RGB with exposed-melt coverage ({@code texture_coverage}) in A: molten lava. */
        MOLTEN_HEAT,
        /** Heat (emission) in R, crack glow ({@code texture_glow}) in A: solid crust. */
        CRUST_HEAT
    }

    /** Mud and scorch keep roughness in their normal map's blue channel; their shaders rebuild the normal's Z. */
    static final Set<String> PACKED_ROUGHNESS = Set.of("terrain/surfaces/mud", "terrain/surfaces/scorched");
    /** The glTF metallic-roughness role, which libGDX's ModelTexture usages lack. */
    static final int METALLIC_ROUGHNESS_USAGE = -1;

    private GpuMaterialMaps() { }

    /** The board's material folder: {@code <name>.tres} resources, their maps and materials.json. */
    static File boardTextures() { return new File(Configuration.dataDir(), "models/board/textures"); }

    /** A board material's definition, for renderers outside an asset owner that compose their own textures. */
    static GodotMaterial board(String name) {
        File file = new File(boardTextures(), name + ".tres");
        if (!file.isFile()) { throw new IllegalArgumentException("Missing required Enhanced terrain asset: " + file); }
        return GodotMaterial.read(file, boardTextures());
    }

    /** A board material's colour (its albedo times its albedo colour, with its alpha); the caller owns the pixels. */
    static Pixmap color(String name) { return pack(board(name), Layout.COLOR); }

    /** Sculpted terrain keeps height beside its colour and occlusion beside its normal. */
    static boolean sculpted(String name) { return name.startsWith("terrain/surfaces/"); }

    static Layout colorLayout(String name) { return sculpted(name) ? Layout.COLOR_HEIGHT : Layout.COLOR; }

    static Layout normalLayout(String name) {
        return PACKED_ROUGHNESS.contains(name) ? Layout.NORMAL_ROUGHNESS : sculpted(name) ? Layout.NORMAL_OCCLUSION : Layout.NORMAL;
    }

    /** A material's name: its resource's path below the textures folder, without extension. */
    static String name(GodotMaterial material, File textures) {
        String relative = textures.toPath().toAbsolutePath().normalize()
              .relativize(material.file.toPath().toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
        return relative.endsWith(".tres") ? relative.substring(0, relative.length() - ".tres".length()) : relative;
    }

    /** A model's image as one of a board material's maps: the pixels of its GPU layout, and its cache key suffix. */
    record ModelMap(Supplier<Pixmap> pixels, String suffix) { }

    /**
     * When a model's image is a map of a board material in {@code textures} (Material Maker names it
     * {@code <name>_<map>.png} beside {@code <name>.tres}), the layout its glTF role ({@code usage}) takes, exactly as
     * the board's own draws take it; otherwise null, and the image loads as it is.
     */
    static ModelMap modelMap(File image, int usage, File textures) {
        File path = image.getAbsoluteFile();
        if (!path.toPath().normalize().startsWith(textures.toPath().toAbsolutePath().normalize())) { return null; }
        String file = path.getName();
        int split = file.lastIndexOf('_');
        if (split <= 0 || !file.endsWith(".png")) { return null; }
        File resource = new File(path.getParentFile(), file.substring(0, split) + ".tres");
        if (!resource.isFile()) { return null; }
        GodotMaterial material = GodotMaterial.read(resource, textures);
        String name = name(material, textures);
        Layout layout = switch (usage) {
            case ModelTexture.USAGE_DIFFUSE -> same(path, material.albedo()) ? colorLayout(name) : null;
            case ModelTexture.USAGE_NORMAL -> same(path, material.normal()) ? normalLayout(name) : null;
            case ModelTexture.USAGE_AMBIENT -> same(path, material.occlusion()) || same(path, material.roughnessMap())
                  ? Layout.OCCLUSION : null;
            case METALLIC_ROUGHNESS_USAGE -> same(path, material.roughnessMap()) || same(path, material.metallicMap())
                  ? Layout.METALLIC_ROUGHNESS : null;
            default -> null;
        };
        if (layout == null) { return null; }
        return new ModelMap(() -> pack(material, layout), cacheSuffix(layout));
    }

    /**
     * Texture caches key a layout by its main map's path and this suffix. Colour and normal layouts have none, so the
     * board's own draws of a material (GpuAssets) and a model naming the same map share one texture.
     */
    static String cacheSuffix(Layout layout) {
        return switch (layout) {
            case COLOR, COLOR_HEIGHT, NORMAL, NORMAL_OCCLUSION, NORMAL_ROUGHNESS -> "";
            default -> "#" + layout.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static boolean same(File path, File map) {
        return map != null && map.toPath().toAbsolutePath().normalize().equals(path.toPath().normalize());
    }

    /** A packed layout; the caller owns the returned RGBA8888 pixels. */
    static Pixmap pack(GodotMaterial material, Layout layout) {
        Packer packer = new Packer(material, layout);
        byte[] rgba = packer.pack();
        Pixmap pixels = new Pixmap(packer.width, packer.height, Pixmap.Format.RGBA8888);
        pixels.getPixels().put(rgba).position(0);
        return pixels;
    }

    /** The map whose dimensions a layout takes, before any decoding. */
    static File primary(GodotMaterial material, Layout layout) {
        return switch (layout) {
            case COLOR, COLOR_HEIGHT -> material.albedo();
            case NORMAL, NORMAL_OCCLUSION, NORMAL_ROUGHNESS -> required(material, material.normal(), "normal map");
            case RELIEF, ICE -> required(material, material.heightmap(), "heightmap");
            case OCCLUSION, METALLIC_ROUGHNESS -> material.occlusion() != null ? material.occlusion()
                  : required(material, material.roughnessMap(), "ORM map");
            case MOLTEN_HEAT, CRUST_HEAT -> required(material, material.emission(), "emission map");
        };
    }

    /** Every file a layout reads, so a missing one fails before any upload. */
    static java.util.List<File> maps(GodotMaterial material, Layout layout) {
        var maps = new java.util.ArrayList<File>();
        maps.add(primary(material, layout));
        switch (layout) {
            case COLOR -> { }
            case COLOR_HEIGHT -> maps.add(required(material, material.heightmap(), "heightmap"));
            case NORMAL -> { }
            case NORMAL_OCCLUSION -> maps.add(material.occlusion());
            case NORMAL_ROUGHNESS -> { maps.add(material.occlusion()); maps.add(material.roughnessMap()); }
            case RELIEF -> { maps.add(material.roughnessMap()); maps.add(material.occlusion()); }
            case ICE -> { maps.add(material.roughnessMap()); maps.add(material.subsurface()); maps.add(material.extra("cracks")); }
            case OCCLUSION -> { maps.add(material.roughnessMap()); maps.add(material.subsurface()); }
            case METALLIC_ROUGHNESS -> { maps.add(material.roughnessMap()); maps.add(material.metallicMap()); }
            case MOLTEN_HEAT -> maps.add(material.extra("coverage"));
            case CRUST_HEAT -> maps.add(material.extra("glow"));
        }
        maps.removeIf(java.util.Objects::isNull);
        return maps;
    }

    /** One channel of a map exactly as stored (Godot's TextureChannel), with its dimensions. */
    record Channel(byte[] values, int width, int height) { }

    static Channel channel(File map, int channel) {
        Map8 decoded = Map8.decode(map);
        byte[] values = new byte[decoded.width * decoded.height];
        for (int pixel = 0; pixel < values.length; pixel++) { values[pixel] = (byte) decoded.value(pixel, channel); }
        return new Channel(values, decoded.width, decoded.height);
    }

    private static File required(GodotMaterial material, File map, String what) {
        if (map == null) { throw new IllegalArgumentException("Board material needs its " + what + ": " + material.file); }
        return map;
    }

    /** A PNG's width and height from its header. */
    static int[] size(File png) {
        if (!png.isFile()) { throw new IllegalArgumentException("Missing required board material map: " + png); }
        try (var input = new DataInputStream(Files.newInputStream(png.toPath()))) {
            byte[] header = input.readNBytes(24);
            if (header.length < 24 || header[0] != (byte) 137 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G'
                  || header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
                throw new IllegalArgumentException("Board material maps are PNG images: " + png);
            }
            return new int[] { readInt(header, 16), readInt(header, 20) };
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read board material map: " + png, error);
        }
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 255) << 24 | (bytes[at + 1] & 255) << 16 | (bytes[at + 2] & 255) << 8 | bytes[at + 3] & 255;
    }

    /** One decoded map's RGBA bytes. Single-channel PNGs decode as opaque grey (GpuColorTexture.decode). */
    private record Map8(File file, int width, int height, byte[] rgba) {
        static Map8 decode(File file) {
            if (!file.isFile()) { throw new IllegalArgumentException("Missing required board material map: " + file); }
            Pixmap pixels = GpuColorTexture.decode(new FileHandle(file));
            try {
                int width = pixels.getWidth(), height = pixels.getHeight();
                byte[] rgba = new byte[width * height * 4];
                if (pixels.getFormat() == Pixmap.Format.RGBA8888) {
                    pixels.getPixels().duplicate().position(0).get(rgba);
                } else {
                    for (int y = 0, i = 0; y < height; y++) {
                        for (int x = 0; x < width; x++, i += 4) {
                            int value = pixels.getPixel(x, y);
                            rgba[i] = (byte) (value >>> 24);
                            rgba[i + 1] = (byte) (value >>> 16);
                            rgba[i + 2] = (byte) (value >>> 8);
                            rgba[i + 3] = (byte) value;
                        }
                    }
                }
                return new Map8(file, width, height, rgba);
            } finally { pixels.dispose(); }
        }

        int value(int pixel, int channel) {
            int at = pixel * 4;
            if (channel == GodotMaterial.GRAYSCALE) {
                return Math.round(((rgba[at] & 255) + (rgba[at + 1] & 255) + (rgba[at + 2] & 255)) / 3f);
            }
            return rgba[at + channel] & 255;
        }
    }

    private static final class Packer {
        private final GodotMaterial material;
        private final Layout layout;
        private final java.util.Map<File, Map8> decoded = new java.util.HashMap<>();
        private int width, height;

        Packer(GodotMaterial material, Layout layout) {
            this.material = material;
            this.layout = layout;
        }

        /** A map combined into this layout: every combined map shares the layout's dimensions, without resampling. */
        private Map8 map(File file) {
            if (file == null) { return null; }
            Map8 map = decoded.get(file);
            if (map != null) { return map; }
            map = Map8.decode(file);
            decoded.put(file, map);
            if (width == 0) {
                width = map.width;
                height = map.height;
            } else if (map.width != width || map.height != height) {
                throw new IllegalArgumentException("Maps packed into one GPU texture must share dimensions: " + file + " is "
                      + map.width + "x" + map.height + ", " + primary(material, layout) + " is " + width + "x" + height);
            }
            return map;
        }

        byte[] pack() {
            Map8 primary = map(primary(material, layout));
            byte[] out = new byte[width * height * 4];
            switch (layout) {
                case COLOR, COLOR_HEIGHT -> {
                    Map8 heights = layout == Layout.COLOR_HEIGHT ? map(required(material, material.heightmap(), "heightmap")) : null;
                    float[] tint = material.albedoColor();
                    boolean tinted = tint[0] != 1 || tint[1] != 1 || tint[2] != 1 || tint[3] != 1;
                    float[] linear = { GpuColor.linear(tint[0]), GpuColor.linear(tint[1]), GpuColor.linear(tint[2]) };
                    for (int pixel = 0, at = 0; pixel < width * this.height; pixel++, at += 4) {
                        for (int c = 0; c < 3; c++) {
                            int value = primary.value(pixel, c);
                            // Godot multiplies the linear texel by the linear albedo colour.
                            out[at + c] = (byte) (tint[c] != 1
                                  ? Math.round(GpuColor.display(GpuColor.linear(value / 255f) * linear[c]) * 255) : value);
                        }
                        out[at + 3] = (byte) (heights != null ? height(heights, pixel)
                              : tinted ? Math.round(primary.value(pixel, 3) * tint[3]) : primary.value(pixel, 3));
                    }
                }
                case NORMAL, NORMAL_OCCLUSION, NORMAL_ROUGHNESS -> {
                    Surface surface = new Surface(this);
                    float scale = material.normalScale();
                    for (int pixel = 0, at = 0; pixel < width * height; pixel++, at += 4) {
                        int x = primary.value(pixel, 0), y = 255 - primary.value(pixel, 1), z = primary.value(pixel, 2);
                        if (scale != 1) {
                            // Godot scales the normal's tangent components and rebuilds its height.
                            float nx = (x / 255f * 2 - 1) * scale, ny = (y / 255f * 2 - 1) * scale;
                            float nz = (float) Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                            x = encode(nx);
                            y = encode(ny);
                            z = encode(nz);
                        }
                        out[at] = (byte) x;
                        out[at + 1] = (byte) y;
                        out[at + 2] = (byte) (layout == Layout.NORMAL_ROUGHNESS ? surface.roughness(pixel) : z);
                        out[at + 3] = (byte) (layout == Layout.NORMAL ? 255 : surface.occlusion(pixel));
                    }
                }
                case RELIEF, ICE -> {
                    Surface surface = new Surface(this);
                    Map8 cracks = layout == Layout.ICE ? map(material.extra("cracks")) : null;
                    int relief = Math.clamp(Math.round(material.heightmapScale() * 25.5f), 0, 255);
                    for (int pixel = 0, at = 0; pixel < width * height; pixel++, at += 4) {
                        out[at] = (byte) height(primary, pixel);
                        out[at + 1] = (byte) surface.roughness(pixel);
                        out[at + 2] = (byte) (cracks != null ? cracks.value(pixel, 0) : surface.occlusion(pixel));
                        out[at + 3] = (byte) (cracks != null ? surface.subsurface(pixel) : relief);
                    }
                }
                case OCCLUSION, METALLIC_ROUGHNESS -> {
                    Surface surface = new Surface(this);
                    boolean occlusion = layout == Layout.OCCLUSION;
                    for (int pixel = 0, at = 0; pixel < width * height; pixel++, at += 4) {
                        out[at] = (byte) (occlusion ? surface.occlusion(pixel) : 255);
                        out[at + 1] = (byte) surface.roughness(pixel);
                        out[at + 2] = (byte) (occlusion ? surface.subsurface(pixel) : surface.metallic(pixel));
                        out[at + 3] = (byte) 255;
                    }
                }
                case MOLTEN_HEAT, CRUST_HEAT -> {
                    boolean molten = layout == Layout.MOLTEN_HEAT;
                    Map8 mask = map(material.extra(molten ? "coverage" : "glow"));
                    for (int pixel = 0, at = 0; pixel < width * height; pixel++, at += 4) {
                        out[at] = (byte) primary.value(pixel, 0);
                        // The crust's renderer reads heat (R) and glow (A); its G and B are unused and stay neutral.
                        out[at + 1] = (byte) (molten ? primary.value(pixel, 1) : 128);
                        out[at + 2] = (byte) (molten ? primary.value(pixel, 2) : 128);
                        out[at + 3] = (byte) mask.value(pixel, 0);
                    }
                }
            }
            return out;
        }

        private int height(Map8 heightmap, int pixel) {
            int value = heightmap.value(pixel, 0);
            return material.heightmapFlipped() ? 255 - value : value;
        }
    }

    /**
     * Godot's scalar surface properties, each from its channel of a map times its factor, or the factor alone. A map is
     * decoded when a layout first reads it, so a layout never depends on maps it does not pack.
     */
    private static final class Surface {
        private final Packer packer;
        private final GodotMaterial material;
        private final int occlusionChannel, roughnessChannel, metallicChannel;
        private final float roughnessFactor, metallicFactor, subsurfaceStrength;
        private Map8 occlusion, roughness, metallic, subsurface;
        private boolean occlusionRead, roughnessRead, metallicRead, subsurfaceRead;

        Surface(Packer packer) {
            this.packer = packer;
            material = packer.material;
            occlusionChannel = material.occlusionChannel();
            roughnessChannel = material.roughnessChannel();
            metallicChannel = material.metallicChannel();
            roughnessFactor = material.roughness();
            metallicFactor = material.metallic();
            subsurfaceStrength = material.subsurfaceStrength();
        }

        int occlusion(int pixel) {
            if (!occlusionRead) { occlusion = packer.map(material.occlusion()); occlusionRead = true; }
            return occlusion == null ? 255 : occlusion.value(pixel, occlusionChannel);
        }

        int roughness(int pixel) {
            if (!roughnessRead) { roughness = packer.map(material.roughnessMap()); roughnessRead = true; }
            return scaled(roughness, pixel, roughnessChannel, roughnessFactor);
        }

        int metallic(int pixel) {
            if (!metallicRead) { metallic = packer.map(material.metallicMap()); metallicRead = true; }
            return scaled(metallic, pixel, metallicChannel, metallicFactor);
        }

        int subsurface(int pixel) {
            if (!subsurfaceRead) { subsurface = packer.map(material.subsurface()); subsurfaceRead = true; }
            return scaled(subsurface, pixel, GodotMaterial.RED, subsurfaceStrength);
        }

        private static int scaled(Map8 map, int pixel, int channel, float factor) {
            if (map == null) { return Math.clamp(Math.round(factor * 255), 0, 255); }
            int value = map.value(pixel, channel);
            return factor == 1 ? value : Math.clamp(Math.round(value * factor), 0, 255);
        }
    }

    private static int encode(float component) { return Math.clamp(Math.round((component * .5f + .5f) * 255), 0, 255); }
}
