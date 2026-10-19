/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A board material: a Godot 4 {@code StandardMaterial3D} text resource ({@code <name>.tres}) and the maps it names, as
 * Material Maker's "Godot/Godot 4 Standard" export writes them ({@code <name>_albedo.png}, {@code _orm.png},
 * {@code _normal.png}, {@code _heightmap.png}, {@code _emission.png}, {@code _sss.png}). Both Material Maker's
 * {@code format=2} spelling and Godot's own {@code format=3} files are read. A map path is relative to the resource's
 * folder, or a {@code res://} path relative to the board's textures folder. Only the properties the board renders are
 * read (see docs/gpu-board-materials.md); an omitted property keeps Godot's default. CPU-only: no graphics context.
 */
final class GodotMaterial {
    /** Godot's BaseMaterial3D.TextureChannel order. */
    static final int RED = 0, GREEN = 1, BLUE = 2, ALPHA = 3, GRAYSCALE = 4;
    private static final Pattern SECTION = Pattern.compile("\\[(\\w+)((?:\\s+\\w+=(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s\\]]+))*)\\s*]");
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)=(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s\\]]+)");
    private static final Pattern EXTERNAL = Pattern.compile("ExtResource\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\d+)\\s*\\)");

    final File file;
    private final Map<String, String> properties;
    private final Map<String, File> resources;

    private GodotMaterial(File file, Map<String, String> properties, Map<String, File> resources) {
        this.file = file;
        this.properties = properties;
        this.resources = resources;
    }

    /** {@code textures}: the folder {@code res://} paths resolve against. */
    static GodotMaterial read(File file, File textures) {
        String text;
        try {
            text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read board material " + file, error);
        }
        Map<String, String> properties = new HashMap<>();
        Map<String, File> resources = new HashMap<>();
        String section = null;
        String key = null;
        StringBuilder value = new StringBuilder();
        boolean header = false;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (key != null) {
                // A multi-line value (an array or dictionary) continues until its brackets close.
                value.append('\n').append(trimmed);
                if (balanced(value)) { properties.put(key, value.toString()); key = null; }
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith(";")) { continue; }
            Matcher match = SECTION.matcher(trimmed);
            if (match.matches()) {
                section = match.group(1);
                Map<String, String> attributes = attributes(match.group(2));
                if (section.equals("gd_resource")) {
                    if (!"StandardMaterial3D".equals(attributes.get("type"))) {
                        throw new IllegalArgumentException("Board materials are Godot 4 StandardMaterial3D resources"
                              + " (Material Maker's Godot/Godot 4 Standard export): " + file);
                    }
                    header = true;
                } else if (section.equals("ext_resource")) {
                    String id = attributes.get("id"), path = attributes.get("path");
                    if (id == null || path == null) { throw new IllegalArgumentException("Incomplete ext_resource in " + file); }
                    resources.put(id, resolve(file, textures, path));
                }
                continue;
            }
            if (!header) { throw new IllegalArgumentException("Not a Godot text resource: " + file); }
            if (!"resource".equals(section)) { continue; }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) { throw new IllegalArgumentException("Unreadable line in " + file + ": " + trimmed); }
            key = trimmed.substring(0, equals).strip();
            value.setLength(0);
            value.append(trimmed.substring(equals + 1).strip());
            if (balanced(value)) { properties.put(key, value.toString()); key = null; }
        }
        if (!header) { throw new IllegalArgumentException("Not a Godot text resource: " + file); }
        if (key != null) { throw new IllegalArgumentException("Unterminated value of " + key + " in " + file); }
        return new GodotMaterial(file, properties, resources);
    }

    private static boolean balanced(CharSequence value) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && quoted) { i++; }
            else if (c == '"') { quoted = !quoted; }
            else if (!quoted && (c == '(' || c == '[' || c == '{')) { depth++; }
            else if (!quoted && (c == ')' || c == ']' || c == '}')) { depth--; }
        }
        return depth <= 0 && !quoted;
    }

    private static Map<String, String> attributes(String text) {
        Map<String, String> result = new HashMap<>();
        Matcher match = ATTRIBUTE.matcher(text);
        while (match.find()) { result.put(match.group(1), unquote(match.group(2))); }
        return result;
    }

    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '"') { return value; }
        return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static File resolve(File file, File textures, String path) {
        File resolved = path.startsWith("res://") ? new File(textures, path.substring("res://".length()))
              : new File(file.getParentFile(), path);
        // Maps belong to the board's data: a path may not leave the textures folder.
        if (!resolved.toPath().toAbsolutePath().normalize().startsWith(textures.toPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Board material maps must lie in the textures folder: " + path + " in " + file);
        }
        return resolved;
    }

    /** The name of the resource without its folder or extension: Material Maker's export prefix. */
    String name() {
        String name = file.getName();
        return name.endsWith(".tres") ? name.substring(0, name.length() - ".tres".length()) : name;
    }

    boolean has(String property) { return properties.containsKey(property); }

    /** The file a texture property names, or null when the property is absent. */
    File texture(String property) {
        String value = properties.get(property);
        if (value == null) { return null; }
        Matcher match = EXTERNAL.matcher(value);
        if (!match.matches()) {
            throw new IllegalArgumentException(property + " must name an ext_resource image, not " + value + ": " + file);
        }
        File texture = resources.get(unquote(match.group(1)));
        if (texture == null) { throw new IllegalArgumentException("Unknown ext_resource " + value + " in " + file); }
        return texture;
    }

    float number(String property, float fallback) {
        String value = properties.get(property);
        if (value == null) { return fallback; }
        try {
            float number = Float.parseFloat(value);
            if (!Float.isFinite(number)) { throw new NumberFormatException(); }
            return number;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(property + " must be a finite number, not " + value + ": " + file);
        }
    }

    int integer(String property, int fallback) {
        String value = properties.get(property);
        if (value == null) { return fallback; }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(property + " must be an integer, not " + value + ": " + file);
        }
    }

    boolean flag(String property, boolean fallback) {
        String value = properties.get(property);
        if (value == null) { return fallback; }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(property + " must be true or false, not " + value + ": " + file);
        };
    }

    /** A {@code Color(r, g, b, a)} value. */
    float[] color(String property, float... fallback) {
        String value = properties.get(property);
        if (value == null) { return fallback; }
        if (!value.startsWith("Color(") || !value.endsWith(")")) {
            throw new IllegalArgumentException(property + " must be a Color, not " + value + ": " + file);
        }
        String[] parts = value.substring("Color(".length(), value.length() - 1).split(",");
        float[] result = { 1, 1, 1, 1 };
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException(property + " must be a Color, not " + value + ": " + file);
        }
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Float.parseFloat(parts[i].strip());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(property + " must be a Color, not " + value + ": " + file);
            }
        }
        return result;
    }

    /** A texture channel property: Godot's BaseMaterial3D.TextureChannel, red by default. */
    int channel(String property) {
        int channel = integer(property, RED);
        if (channel < RED || channel > GRAYSCALE) {
            throw new IllegalArgumentException(property + " must be a texture channel 0-4: " + file);
        }
        return channel;
    }

    // The properties the board renders, with Godot's defaults and enable flags.

    File albedo() {
        File albedo = texture("albedo_texture");
        if (albedo == null) { throw new IllegalArgumentException("A board material needs an albedo_texture: " + file); }
        return albedo;
    }

    float[] albedoColor() { return color("albedo_color", 1, 1, 1, 1); }

    File normal() { return flag("normal_enabled", false) ? texture("normal_texture") : null; }

    float normalScale() { return number("normal_scale", 1); }

    File occlusion() { return flag("ao_enabled", false) ? texture("ao_texture") : null; }

    File roughnessMap() { return texture("roughness_texture"); }

    float roughness() { return number("roughness", 1); }

    File metallicMap() { return texture("metallic_texture"); }

    float metallic() { return number("metallic", 0); }

    /** Material Maker's tessellated material names the heightmap {@code depth_texture}; Godot 4 names it heightmap. */
    File heightmap() {
        if (!flag("heightmap_enabled", false)) { return null; }
        File heightmap = texture("heightmap_texture");
        return heightmap != null ? heightmap : texture("depth_texture");
    }

    /** Godot's heightmap depth in texture UV units, times 100. */
    float heightmapScale() { return number("heightmap_scale", 5); }

    boolean heightmapFlipped() { return flag("heightmap_flip_texture", false); }

    File emission() { return flag("emission_enabled", false) ? texture("emission_texture") : null; }

    /** Godot 4 renamed emission_energy to emission_energy_multiplier; Material Maker writes the former. */
    float emissionEnergy() {
        return has("emission_energy_multiplier") ? number("emission_energy_multiplier", 1) : number("emission_energy", 1);
    }

    File subsurface() { return flag("subsurf_scatter_enabled", false) ? texture("subsurf_scatter_texture") : null; }

    float subsurfaceStrength() { return number("subsurf_scatter_strength", 0); }

    /**
     * An additional map of this material with no StandardMaterial3D property, saved beside it by a Material Maker
     * Export node as {@code <name>_<suffix>.png} (docs/gpu-board-materials.md lists them).
     */
    File extra(String suffix) {
        File extra = new File(file.getParentFile(), name() + "_" + suffix + ".png");
        if (!extra.isFile()) { throw new IllegalArgumentException("Missing required board material map: " + extra); }
        return extra;
    }
}
