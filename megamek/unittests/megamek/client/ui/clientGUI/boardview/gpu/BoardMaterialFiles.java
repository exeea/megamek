/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Test fixtures in the board's material format: Material Maker's Godot 4 Standard export, ShaderMaterials for maps it
 * has no slot for (GodotMaterial), and the textures folder's materials.json (GpuMaterialCatalog). Fixtures are given in the renderer's packed layouts
 * (GpuMaterialMaps.Layout), which these split into the Godot maps the loader packs them from again.
 */
final class BoardMaterialFiles {
    private BoardMaterialFiles() { }

    /** A sculpted terrain material: colour with height in A, the board's normal with occlusion in A. */
    static void sculpt(Path textures, String name, BufferedImage colorHeight, BufferedImage normalOcclusion, float metres)
          throws IOException {
        String material = "terrain/surfaces/" + name;
        Path folder = Files.createDirectories(textures.resolve("terrain/surfaces"));
        write(folder, name + "_albedo.png", channels(colorHeight, 0, 1, 2, -1));
        write(folder, name + "_heightmap.png", gray(colorHeight, 3));
        write(folder, name + "_normal.png", godotNormal(normalOcclusion));
        write(folder, name + "_orm.png", channels(normalOcclusion, 3, -1, -2, -3));
        tres(folder, name, "orm", "normal", "heightmap", "ao");
        loose(textures, material, colorHeight.getWidth(), metres);
    }

    /** A road or magma material from colour, normal and surface (height, roughness, occlusion, relief code). */
    static void relief(Path textures, String material, BufferedImage color, BufferedImage normal, BufferedImage surface)
          throws IOException {
        Path folder = Files.createDirectories(textures.resolve(material).getParent());
        String name = textures.resolve(material).getFileName().toString();
        // A Material Maker export: any earlier ShaderMaterial of this material is gone (see heat).
        Files.deleteIfExists(folder.resolve(name + ".gdshader"));
        write(folder, name + "_albedo.png", color);
        write(folder, name + "_normal.png", godotNormal(normal));
        write(folder, name + "_heightmap.png", gray(surface, 0));
        write(folder, name + "_orm.png", channels(surface, 2, 1, -2, -3));
        int relief = surface.getRGB(0, 0) >>> 24;
        tres(folder, name, "orm", "normal", "heightmap", "ao", "heightmap_scale=" + relief / 25.5);
        loose(textures, material, color.getWidth(), Float.NaN);
    }

    /**
     * Lava's heat, which turns a {@link #relief} material into the ShaderMaterial export.py makes of it: Material Maker's
     * emission map and the shader's extra map, molten RGB with its coverage or crust R with its glow.
     */
    static void heat(Path textures, String material, BufferedImage heat, float energy) throws IOException {
        Path folder = textures.resolve(material).getParent();
        String name = textures.resolve(material).getFileName().toString();
        boolean molten = name.equals("lava");
        String extra = molten ? "coverage" : "glow";
        write(folder, name + "_emission.png", molten ? channels(heat, 0, 1, 2, -1) : channels(heat, 0, 0, 0, -1));
        write(folder, name + "_" + extra + ".png", gray(heat, 3));
        float relief = GodotMaterial.read(folder.resolve(name + ".tres").toFile(), textures.toFile()).heightmapScale();
        shaderMaterial(folder, name, List.of("albedo", "orm", "normal", "heightmap", "emission", extra),
              Map.of("heightmap_scale", relief, "emission_energy", energy));
    }

    /**
     * A ShaderMaterial of a minimal {@code <name>.gdshader}: the standard parameters with Godot's defaults, and a
     * {@code texture_<map>} parameter assigned {@code <name>_<map>.png} for each of {@code maps}.
     */
    static void shaderMaterial(Path folder, String name, List<String> maps, Map<String, Float> numbers) throws IOException {
        var shader = new StringBuilder("shader_type spatial;\nuniform vec4 albedo : source_color = vec4(1.0);\n"
              + "uniform float roughness = 1.0;\nuniform float metallic = 0.0;\nuniform float normal_scale = 1.0;\n"
              + "uniform float heightmap_scale = 5.0;\nuniform float emission_energy = 1.0;\n");
        var tres = new StringBuilder("[gd_resource type=\"ShaderMaterial\" format=3]\n\n[ext_resource type=\"Shader\" path=\"")
              .append(name).append(".gdshader\" id=\"1_shader\"]\n");
        var parameters = new StringBuilder("[resource]\nshader = ExtResource(\"1_shader\")\n");
        for (String map : maps) {
            shader.append("uniform sampler2D texture_").append(map).append(";\n");
            tres.append("[ext_resource type=\"Texture2D\" path=\"").append(name).append('_').append(map).append(".png\" id=\"")
                  .append(map).append("\"]\n");
            parameters.append("shader_parameter/texture_").append(map).append(" = ExtResource(\"").append(map).append("\")\n");
        }
        numbers.forEach((parameter, value) -> parameters.append("shader_parameter/").append(parameter).append(" = ")
              .append(value).append('\n'));
        Files.writeString(folder.resolve(name + ".gdshader"), shader.append("void fragment() { }\n"));
        Files.writeString(folder.resolve(name + ".tres"), tres.append('\n').append(parameters));
    }

    /** A material of colour alone, constrained to its footprint. */
    static void plain(Path textures, String material, BufferedImage color) throws IOException {
        Path folder = Files.createDirectories(textures.resolve(material).getParent());
        String name = textures.resolve(material).getFileName().toString();
        write(folder, name + "_albedo.png", color);
        tres(folder, name);
        constrained(textures, material);
    }

    /** Material Maker's template: ExtResource ids 1 albedo, 2 ORM, 3 normal, 4 heightmap. */
    static void tres(Path folder, String name, String... features) throws IOException {
        var maps = List.of(features);
        var text = new StringBuilder("[gd_resource type=\"StandardMaterial3D\" load_steps=5 format=2]\n");
        text.append("[ext_resource path=\"").append(name).append("_albedo.png\" type=\"Texture\" id=1]\n");
        if (maps.contains("orm")) { text.append("[ext_resource path=\"").append(name).append("_orm.png\" type=\"Texture\" id=2]\n"); }
        if (maps.contains("normal")) {
            text.append("[ext_resource path=\"").append(name).append("_normal.png\" type=\"Texture\" id=3]\n");
        }
        if (maps.contains("heightmap")) {
            text.append("[ext_resource path=\"").append(name).append("_heightmap.png\" type=\"Texture\" id=4]\n");
        }
        text.append("[resource]\nalbedo_color = Color(1.0, 1.0, 1.0, 1.0)\nalbedo_texture = ExtResource( 1 )\n");
        text.append("metallic = 0.0\nroughness = 1.0\n");
        if (maps.contains("orm")) { text.append("roughness_texture = ExtResource( 2 )\nroughness_texture_channel = 1\n"); }
        if (maps.contains("normal")) { text.append("normal_enabled = true\nnormal_scale = 1.0\nnormal_texture = ExtResource( 3 )\n"); }
        if (maps.contains("ao")) { text.append("ao_enabled = true\nao_texture = ExtResource( 2 )\nao_texture_channel = 0\n"); }
        if (maps.contains("heightmap")) { text.append("heightmap_enabled = true\nheightmap_texture = ExtResource( 4 )\n"); }
        for (String feature : features) {
            if (feature.contains("=")) { text.append(feature.replace("=", " = ")).append('\n'); }
        }
        Files.writeString(folder.resolve(name + ".tres"), text);
    }

    /** Adds or replaces a loose material's catalogue entry; NaN {@code metres} for a renderer-owned repeat. */
    static void loose(Path textures, String material, int pixels, float metres) throws IOException {
        ObjectNode catalog = catalog(textures);
        ObjectNode entry = ((ObjectNode) catalog.get("loose")).putObject(material).put("pixels", pixels);
        if (!Float.isNaN(metres)) { entry.put("metres", metres); }
        removeConstrained(catalog, material);
        Files.writeString(textures.resolve("materials.json"), new ObjectMapper().writeValueAsString(catalog));
    }

    static void constrained(Path textures, String material) throws IOException {
        ObjectNode catalog = catalog(textures);
        ((ObjectNode) catalog.get("loose")).remove(material);
        removeConstrained(catalog, material);
        catalog.withArray("constrained").add(material);
        Files.writeString(textures.resolve("materials.json"), new ObjectMapper().writeValueAsString(catalog));
    }

    private static void removeConstrained(ObjectNode catalog, String material) {
        var kept = new ArrayList<String>();
        catalog.withArray("constrained").forEach(node -> { if (!node.asText().equals(material)) { kept.add(node.asText()); } });
        var array = catalog.putArray("constrained");
        kept.forEach(array::add);
    }

    private static ObjectNode catalog(Path textures) throws IOException {
        Files.createDirectories(textures);
        Path file = textures.resolve("materials.json");
        ObjectNode catalog = Files.isRegularFile(file) ? (ObjectNode) new ObjectMapper().readTree(file.toFile())
              : new ObjectMapper().createObjectNode();
        if (!catalog.has("loose")) { catalog.putObject("loose"); }
        if (!catalog.has("constrained")) { catalog.putArray("constrained"); }
        return catalog;
    }

    /** The board's normal (+G down the image) as Godot and glTF store it (+G up the image). */
    static BufferedImage godotNormal(BufferedImage normal) {
        var result = new BufferedImage(normal.getWidth(), normal.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < normal.getHeight(); y++) {
            for (int x = 0; x < normal.getWidth(); x++) {
                int argb = normal.getRGB(x, y);
                result.setRGB(x, y, (argb & 0xff00ff) | (255 - (argb >>> 8 & 255)) << 8);
            }
        }
        return result;
    }

    /**
     * An RGB(A) image of {@code source} channels: 0-3 pick R, G, B, A; -1 is opaque (255), -2 zero, -3 drops alpha.
     */
    static BufferedImage channels(BufferedImage source, int red, int green, int blue, int alpha) {
        boolean rgba = alpha != -3;
        var result = new BufferedImage(source.getWidth(), source.getHeight(),
              rgba ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int r = pick(argb, red), g = pick(argb, green), b = pick(argb, blue), a = rgba ? pick(argb, alpha) : 255;
                result.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
            }
        }
        return result;
    }

    private static int pick(int argb, int channel) {
        return switch (channel) {
            case 0 -> argb >>> 16 & 255;
            case 1 -> argb >>> 8 & 255;
            case 2 -> argb & 255;
            case 3 -> argb >>> 24;
            case -2 -> 0;
            default -> 255;
        };
    }

    static BufferedImage gray(BufferedImage source, int channel) {
        var result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        var raster = result.getRaster();
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) { raster.setSample(x, y, 0, pick(source.getRGB(x, y), channel)); }
        }
        return result;
    }

    static void write(Path folder, String file, BufferedImage image) throws IOException {
        if (!ImageIO.write(image, "png", folder.resolve(file).toFile())) { throw new IOException("No PNG writer"); }
    }
}
