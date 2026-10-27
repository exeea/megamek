/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Classic tileset's level recipes ({@code tileset/levels.json}, optional). Its ground art is drawn once, for level
 * 0, and turned for a hex's level as the old tileset's per-level copies were: every pixel in HSV, the hue by a turn in
 * degrees, saturation and value along {@code 1 - (1 - x)^k} with an exponent each (above 1 raises, below 1 lowers; 0
 * and 1 stay). A recipe's table gives those three for its levels from {@code from}; its formula, as Enhanced eases its
 * height ({@code levelGrade}), gives their change per eased level {@code H(d) = ease * (1 - e^(-d / ease))} of distance
 * {@code d} from level 0: {@code up} above it, {@code down} below, a hue turn in degrees and the exponents' factors.
 * A level in the table is the table's; any other starts from the nearest table level (the highest above the table,
 * the lowest below it, level 0's identity without a table) and adds the formula's change from there. An image's
 * split grades its saturated layer (paint marks, cracks, grass tufts) apart: pixels below the split take the image's
 * recipe, those above it the split's recipe or none, blended across it.
 */
final class TilesetLevels {
    /** One side of a recipe's formula: per eased level, the hue turn and the exponents' factors. */
    private record Rates(float hue, float saturation, float value) { }

    /** A recipe's table, its levels from {@code from} (none when empty), and its formula. */
    private record Recipe(int from, float[] hue, float[] saturation, float[] value, Rates up, Rates down) { }

    /** Saturation from {@code from} to {@code to} blends into {@code saturated} (null: the pixel as it is). */
    private record Split(Recipe saturated, float from, float to) { }

    private record Grade(Recipe recipe, Split split) { }

    private record Key(String file, int level) { }

    private final float ease;
    private final Map<String, Grade> images = new HashMap<>();
    private final Map<Key, Image> graded = new HashMap<>();

    TilesetLevels(File root) {
        File file = new File(root, "tileset/levels.json");
        // A tileset without recipes draws its art as it is at every level.
        if (!file.isFile()) {
            ease = 1;
            return;
        }
        JsonNode json;
        try {
            json = new ObjectMapper().readTree(file);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the Classic tileset's level recipes " + file, failure);
        }
        ease = (float) json.get("ease").asDouble();
        Map<String, Recipe> recipes = new HashMap<>();
        for (var entry : json.get("recipes").properties()) {
            JsonNode recipe = entry.getValue();
            float[] hue = floats(recipe.path("hue")), saturation = floats(recipe.path("saturation"));
            float[] value = floats(recipe.path("value"));
            if (saturation.length != hue.length || value.length != hue.length) {
                throw new IllegalStateException("Level recipe " + entry.getKey() + " has tables of different lengths");
            }
            recipes.put(entry.getKey(), new Recipe(recipe.path("from").asInt(0), hue, saturation, value,
                  rates(recipe.get("up")), rates(recipe.get("down"))));
        }
        for (var entry : json.get("images").properties()) {
            JsonNode grade = entry.getValue();
            if (grade.isTextual()) {
                images.put(entry.getKey(), new Grade(recipe(recipes, grade.asText()), null));
                continue;
            }
            JsonNode split = grade.get("split"), saturated = grade.get("saturated");
            images.put(entry.getKey(), new Grade(recipe(recipes, grade.get("recipe").asText()),
                  new Split(saturated.isNull() ? null : recipe(recipes, saturated.asText()),
                        (float) split.get(0).asDouble(), (float) split.get(1).asDouble())));
        }
    }

    /** {@code image}, the tileset's {@code file}, as a hex at {@code level} shows it: itself without a recipe. */
    Image image(Image image, String file, int level) {
        Grade grade = images.get(file);
        if (grade == null) { return image; }
        float[] own = at(grade.recipe(), level);
        Split split = grade.split();
        float[] saturated = split == null || split.saturated() == null ? null : at(split.saturated(), level);
        if (identity(own) && (saturated == null || identity(saturated))) { return image; }
        return graded.computeIfAbsent(new Key(file, level), key -> grade(image, own, split, saturated));
    }

    /**
     * A recipe's hue turn and saturation and value exponents at {@code level}: its table's there, else the nearest
     * table level's (level 0's identity without a table) with the formula's change from that level added.
     */
    private float[] at(Recipe recipe, int level) {
        int count = recipe.hue().length;
        int anchor = count == 0 ? 0 : Math.clamp(level, recipe.from(), recipe.from() + count - 1);
        int index = anchor - recipe.from();
        float up = eased(Math.max(level, 0)) - eased(Math.max(anchor, 0));
        float down = eased(Math.max(-level, 0)) - eased(Math.max(-anchor, 0));
        return new float[] {
              (count == 0 ? 0 : recipe.hue()[index]) + recipe.up().hue() * up + recipe.down().hue() * down,
              (count == 0 ? 1 : recipe.saturation()[index]) * change(recipe.up().saturation(), up,
                    recipe.down().saturation(), down),
              (count == 0 ? 1 : recipe.value()[index]) * change(recipe.up().value(), up, recipe.down().value(), down) };
    }

    /** Enhanced's eased height ({@code levelGrade}) of {@code levels} from level 0. */
    private float eased(int levels) { return ease * (1 - (float) Math.exp(-levels / ease)); }

    private static float change(float up, float upLevels, float down, float downLevels) {
        return (float) (Math.pow(up, upLevels) * Math.pow(down, downLevels));
    }

    private static boolean identity(float[] turn) { return turn[0] == 0 && turn[1] == 1 && turn[2] == 1; }

    private static Image grade(Image source, float[] own, Split split, float[] saturated) {
        var result = new BufferedImage(source.getWidth(null), source.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        var graphics = result.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        float[] hsb = new float[3];
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                int argb = result.getRGB(x, y);
                if (argb >>> 24 == 0) { continue; }
                Color.RGBtoHSB(argb >> 16 & 255, argb >> 8 & 255, argb & 255, hsb);
                int rgb = turn(own, hsb);
                if (split != null) {
                    float t = Math.clamp((hsb[1] - split.from()) / (split.to() - split.from()), 0, 1);
                    t = t * t * (3 - 2 * t);
                    rgb = mix(rgb, saturated == null ? argb : turn(saturated, hsb), t);
                }
                result.setRGB(x, y, argb & 0xff000000 | rgb & 0xffffff);
            }
        }
        return result;
    }

    private static int turn(float[] recipe, float[] hsb) {
        return Color.HSBtoRGB(hsb[0] + recipe[0] / 360, curve(hsb[1], recipe[1]), curve(hsb[2], recipe[2]));
    }

    private static float curve(float x, float exponent) { return (float) (1 - Math.pow(1 - x, exponent)); }

    private static int mix(int a, int b, float t) {
        int result = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            result |= Math.round((a >> shift & 255) * (1 - t) + (b >> shift & 255) * t) << shift;
        }
        return result;
    }

    private static Rates rates(JsonNode side) {
        return new Rates((float) side.get("hue").asDouble(), (float) side.get("saturation").asDouble(),
              (float) side.get("value").asDouble());
    }

    private static Recipe recipe(Map<String, Recipe> recipes, String name) {
        return Objects.requireNonNull(recipes.get(name), () -> "No level recipe " + name);
    }

    private static float[] floats(JsonNode values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) { result[i] = (float) values.get(i).asDouble(); }
        return result;
    }
}
