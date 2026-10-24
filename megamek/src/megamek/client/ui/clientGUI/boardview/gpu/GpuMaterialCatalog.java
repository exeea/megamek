/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

/**
 * The board textures' {@code materials.json}: how each material meets the world.
 * <ul>
 * <li>A <b>loose</b> material repeats across the ground in world space, over many hexes. Its texel density is fixed:
 * {@code pixels} is the texture width its repeat was authored for, so a wider texture covers proportionally more ground
 * before it repeats (twice the width, twice the repeat), at unchanged detail. {@code metres}, given for sculpted
 * terrain and roads, is the ground one repeat covers at that width; other loose materials, and roads without it, keep
 * their renderer's own repeat.</li>
 * <li>A <b>constrained</b> material is fitted to a fixed footprint: a model's UVs, an atlas cell, a decal's size or the
 * hex artwork's density. A larger texture adds detail there and never changes what it covers.</li>
 * </ul>
 * Read once per asset owner; asset reload creates a fresh owner.
 */
final class GpuMaterialCatalog {
    /** {@code metres} is NaN where the renderer owns the repeat (ice, magma, industrial plants; roads without it). */
    record Loose(int pixels, float metres) { }

    private final File file;
    private final Map<String, Loose> loose;
    private final Set<String> constrained;

    private GpuMaterialCatalog(File file, Map<String, Loose> loose, Set<String> constrained) {
        this.file = file;
        this.loose = loose;
        this.constrained = constrained;
    }

    static GpuMaterialCatalog read(File file) {
        if (!file.isFile()) { throw new IllegalArgumentException("Missing required Enhanced terrain asset: " + file); }
        JsonValue root = new JsonReader().parse(new FileHandle(file));
        Map<String, Loose> loose = new HashMap<>();
        JsonValue entries = root.get("loose");
        for (JsonValue entry = entries == null ? null : entries.child; entry != null; entry = entry.next) {
            int pixels = entry.getInt("pixels", 0);
            float metres = entry.has("metres") ? entry.getFloat("metres") : Float.NaN;
            if (pixels <= 0 || entry.has("metres") && !(Float.isFinite(metres) && metres > 0)) {
                throw new IllegalArgumentException("A loose material needs positive pixels (and metres): " + entry.name + " in " + file);
            }
            loose.put(entry.name, new Loose(pixels, metres));
        }
        Set<String> constrained = new HashSet<>();
        JsonValue names = root.get("constrained");
        for (JsonValue name = names == null ? null : names.child; name != null; name = name.next) {
            if (loose.containsKey(name.asString()) || !constrained.add(name.asString())) {
                throw new IllegalArgumentException("A material is either loose or constrained, once: " + name.asString());
            }
        }
        return new GpuMaterialCatalog(file, Map.copyOf(loose), Set.copyOf(constrained));
    }

    Loose loose(String name) {
        Loose entry = loose.get(name);
        if (entry == null) { throw new IllegalArgumentException(name + " is not a loose material in " + file); }
        return entry;
    }

    boolean isLoose(String name) { return loose.containsKey(name); }

    Set<String> looseNames() { return loose.keySet(); }

    Set<String> constrainedNames() { return constrained; }

    /**
     * How many times its catalogued ground a loose material's texture covers, from its actual {@code width} and
     * {@code height}: 1 at the catalogued width. The renderer multiplies its repeat by this.
     */
    float coverage(String name, int width, int height) {
        if (width != height) {
            throw new IllegalArgumentException("A loose material repeats a square texture: " + name + " is " + width + "x" + height);
        }
        return width / (float) loose(name).pixels();
    }
}
