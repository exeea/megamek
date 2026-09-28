/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Cosmetic scatter placement and the stones, shrubs and blades in scatter.glb. */
final class BoardScatter {
    /** 0 disables scatter, 1 is the baseline, 3 triples each biome's placement chance. */
    static final float DENSITY_MULTIPLIER = 3.0f;
    static final int BUSHES = 8;

    private BoardScatter() { }

    // Scene capture needs only placement; defer geometry loading until a renderer requests a shape.
    private static final class Kit {
        static volatile Map<String, List<BoardShape>> shapes = load();

        private static Map<String, List<BoardShape>> load() {
            Map<String, BoardShape> shapes = BoardShape.loadKit("scatter");
            Map<String, List<BoardShape>> levels = new HashMap<>();
            for (String name : shapes.keySet()) {
                if (!name.matches(".+-lod[0-2]")) { throw new IllegalArgumentException("Invalid kit LOD name: " + name); }
                String shape = name.substring(0, name.lastIndexOf("-lod"));
                levels.computeIfAbsent(shape, key -> MeshLod.load(key, 3, shapes::get));
            }
            return Map.copyOf(levels);
        }
    }

    static BoardShape shape(String name) {
        return Kit.shapes.get(name).getFirst();
    }

    /** Replace the complete immutable kit, including all authored levels of detail. */
    static void reload() { Kit.shapes = Kit.load(); }

    /** Cosmetic stones always use the dedicated eight-triangle open-base meshes. */
    static BoardShape rock(BoardScene.Surface surface, int variant, boolean slab) {
        return shape("stone-" + BoardRocks.name(slab || BoardRocks.blocks(surface), variant));
    }

    static BoardShape bush(int variant) {
        return shape("bush-" + Math.floorMod(variant, BUSHES));
    }

    /** Cosmetic clusters leave the unit centre clear; terrain updates never reshuffle neighboring details. */
    static void capture(Hex hex, Coords coords, List<BoardScene.Feature> result) {
        if (hex.containsAnyTerrainOf(Terrains.WATER, Terrains.ICE, Terrains.ROAD, Terrains.PAVEMENT,
              Terrains.BRIDGE, Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL, Terrains.FIELDS,
              Terrains.WOODS, Terrains.JUNGLE, Terrains.SPACE, Terrains.SKY, Terrains.MAGMA, Terrains.FIRE,
              Terrains.GEYSER, Terrains.SWAMP, Terrains.MUD, Terrains.HAZARDOUS_LIQUID, Terrains.FORTIFIED, Terrains.ROUGH)) {
            return;
        }
        BoardScene.Surface surface = BoardFeatures.surface(hex);
        float density = switch (surface) {
            case GRASS -> .16f;
            case ROCK -> .18f;
            case DIRT -> .12f;
            case SAND -> .10f;
            case SNOW -> .06f;
            case CONCRETE -> 0;
        };
        Random random = new Random(coords.getX() * 0x9E3779B97F4A7C15L
              ^ coords.getY() * 0xC2B2AE3D27D4EB4FL ^ 0x165667B19E3779F9L);
        if (random.nextFloat() >= density * DENSITY_MULTIPLIER) {
            return;
        }
        int count = 3 + random.nextInt(4);
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        boolean plants = !theme.contains("lunar") && !theme.contains("mars") && !theme.contains("volcan");
        for (int index = 0; index < count; index++) {
            int choice = random.nextInt(10);
            String asset = choice % 2 == 0 ? "scatter-rock" : "scatter-slab";
            if (plants && surface == BoardScene.Surface.GRASS) {
                if (choice < 5 || hex.containsTerrain(Terrains.TUNDRA) && choice < 8) {
                    asset = hex.containsTerrain(Terrains.TUNDRA) ? "scatter-dry-grass" : "scatter-grass";
                } else if (choice < 8) {
                    asset = "scatter-plant";
                }
            } else if (plants && surface == BoardScene.Surface.DIRT && choice < 4) {
                asset = "scatter-dry-grass";
            } else if (plants && surface == BoardScene.Surface.SAND && choice == 0) {
                asset = "scatter-plant";
            }
            double angle = random.nextDouble() * Math.PI * 2;
            float radius = 16 + 12 * (float) Math.sqrt(random.nextFloat());
            result.add(new BoardScene.Feature(asset, (float) Math.cos(angle) * radius,
                  (float) Math.sin(angle) * radius, random.nextFloat() * 360, .7f + random.nextFloat() * .5f,
                  .10f + random.nextFloat() * .13f, 0, BoardScene.FeatureKind.SCATTER));
        }
    }
}
