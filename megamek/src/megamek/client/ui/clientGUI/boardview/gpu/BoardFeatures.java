/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Copies terrain appearance on the Swing thread; no game objects cross into the renderer. */
final class BoardFeatures {
    /** Width in tile pixels of a Rough boulder at feature scale one; placement and meshing share this size. */
    static final float ROUGH_BOULDER_WIDTH = 12;
    /** Tree species by where they grow; repeated names are the common ones. */
    private static final List<String> TEMPERATE = List.of("tree", "pine", "tree-broad", "birch", "tree-slender",
          "pine-tall", "pine-broad");
    private static final List<String> HIGHLAND = List.of("pine", "pine-tall", "pine-broad", "tree-slender", "pine-tall",
          "birch");
    private static final List<String> ROCKY = List.of("pine", "tree-dead", "pine-tall", "pine-broad");
    private static final List<String> WETLAND = List.of("willow", "tree-slender", "tree-dead", "willow", "tree");
    private static final List<String> BARREN = List.of("tree-dead");
    private static final List<String> PARK = List.of("tree-broad", "tree", "birch");
    private static final List<String> DESERT = List.of("cactus", "palm", "tree-dead", "cactus-flowers", "palm-bent",
          "cactus");
    private static final List<String> PALMS = List.of("palm", "palm-bent");
    private static final List<String> ORCHARD = List.of("orchard-round", "orchard-spreading", "orchard-upright",
          "orchard-vase", "orchard-leaning", "orchard-young");
    private BoardFeatures() { }

    /** The tileset's orchard marker changes woods appearance, never creates cover by itself. */
    static boolean orchard(Hex hex) {
        return hex.terrainLevel(Terrains.WOODS) > 0 && !hex.containsTerrain(Terrains.JUNGLE)
              && hex.terrainLevel(Terrains.FLUFF) == 12;
    }

    /** Use the terrain material where every marking is either base ground or represented by a captured model. */
    static boolean detailedGround(Hex hex, Map<Integer, String> structureModels) {
        return detailedGround(hex, structureModels, Set.of());
    }

    static boolean detailedGround(Hex hex, Map<Integer, String> structureModels, Set<Integer> blankTerrains) {
        for (int terrain : hex.getTerrainTypes()) {
            if (blankTerrains.contains(terrain) && switch (terrain) {
                case Terrains.FLUFF, Terrains.GROUND_FLUFF, Terrains.ROAD_FLUFF, Terrains.WATER_FLUFF -> true;
                default -> false;
            }) { continue; }
            boolean base = switch (terrain) {
                case Terrains.WOODS, Terrains.JUNGLE, Terrains.FOLIAGE_ELEV, Terrains.SAND, Terrains.TUNDRA,
                      Terrains.PAVEMENT, Terrains.SNOW, Terrains.WATER, Terrains.HAZARDOUS_LIQUID, Terrains.RAPIDS, Terrains.ROUGH,
                      Terrains.CLIFF_TOP, Terrains.CLIFF_BOTTOM, Terrains.INCLINE_TOP, Terrains.INCLINE_BOTTOM,
                      Terrains.INCLINE_HIGH_TOP, Terrains.INCLINE_HIGH_BOTTOM, Terrains.METAL_CONTENT,
                      Terrains.DEPLOYMENT_ZONE, Terrains.IMPASSABLE, Terrains.FIRE, Terrains.SMOKE,
                      Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV, Terrains.BRIDGE_REPAIRED,
                      Terrains.FIELDS, Terrains.SWAMP, Terrains.MUD, Terrains.ICE, Terrains.BLACK_ICE, Terrains.BLDG_BASE_COLLAPSED,
                      Terrains.ARMS, Terrains.LEGS, Terrains.WATER_FLUFF -> true;
                case Terrains.MAGMA -> hex.terrainLevel(Terrains.MAGMA) == 1 || hex.terrainLevel(Terrains.MAGMA) == 2;
                case Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV, Terrains.BLDG_CLASS,
                      Terrains.BLDG_ARMOR, Terrains.BLDG_BASEMENT_TYPE, Terrains.BLDG_FLUFF ->
                      structureModels.containsKey(Terrains.BUILDING);
                case Terrains.FUEL_TANK, Terrains.FUEL_TANK_CF, Terrains.FUEL_TANK_ELEV, Terrains.FUEL_TANK_MAGN ->
                      structureModels.containsKey(Terrains.FUEL_TANK);
                case Terrains.INDUSTRIAL -> structureModels.containsKey(Terrains.INDUSTRIAL);
                case Terrains.ROAD -> BoardRoad.capture(hex) != BoardRoad.Kind.NONE
                      && !hex.containsTerrain(Terrains.WATER);
                // Fluff 1 selects the standard road bend, already represented by the captured road exits.
                case Terrains.ROAD_FLUFF -> hex.terrainLevel(Terrains.ROAD_FLUFF) == 1
                      && BoardRoad.capture(hex) != BoardRoad.Kind.NONE;
                case Terrains.FLUFF -> orchard(hex) || BoardRough.variant(hex) != 0;
                default -> false;
            };
            if (!base) { return false; }
        }
        return true;
    }

    static BoardScene.Surface surface(Hex hex) {
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        if (hex.containsTerrain(Terrains.MAGMA)) { return BoardScene.Surface.ROCK; }
        if (hex.containsTerrain(Terrains.SNOW) || theme.contains("snow")) {
            return BoardScene.Surface.SNOW;
        }
        if (hex.containsTerrain(Terrains.PAVEMENT)) {
            return BoardScene.Surface.CONCRETE;
        }
        if (desert(hex)) {
            return BoardScene.Surface.SAND;
        }
        if (theme.contains("lunar") || theme.contains("rock") || theme.contains("volcan")) {
            return BoardScene.Surface.ROCK;
        }
        // Fields and reed marshes replace the flat cover in the biome shader. Their banks retain the theme's
        // grass/soil mantle; classifying the whole column as dirt leaves a bare cutout around every plantation.
        if (hex.containsTerrain(Terrains.MUD) || hex.terrainLevel(Terrains.SWAMP) > 1
              || theme.contains("dirt") || theme.contains("mars")) {
            return BoardScene.Surface.DIRT;
        }
        return BoardScene.Surface.GRASS;
    }

    static BoardScene.Biome biome(Hex hex) {
        if (hex.containsAnyTerrainOf(Terrains.ICE, Terrains.WATER, Terrains.HAZARDOUS_LIQUID, Terrains.PAVEMENT, Terrains.MAGMA)) {
            return BoardScene.Biome.NONE;
        }
        if (hex.containsTerrain(Terrains.SWAMP)) {
            return hex.terrainLevel(Terrains.SWAMP) == 1 ? BoardScene.Biome.MARSH : BoardScene.Biome.QUICKSAND;
        }
        if (hex.containsTerrain(Terrains.FIELDS)) { return BoardScene.Biome.FIELD; }
        return hex.containsTerrain(Terrains.MUD) ? BoardScene.Biome.MUD : BoardScene.Biome.NONE;
    }

    static List<BoardScene.Feature> capture(Hex hex, Coords coords, Map<Integer, String> structureModels) {
        return capture(hex, coords, structureModels, Set.of());
    }

    static List<BoardScene.Feature> capture(Hex hex, Coords coords, Map<Integer, String> structureModels,
          Set<Integer> blankTerrains) {
        List<BoardScene.Feature> result = new ArrayList<>();
        int variant = Math.floorMod(coords.getX() * 31 + coords.getY() * 17, 4);
        // Terrain levels are the game's collectable limb counts, not damage inferred from nearby units.
        for (int type = 0; type < 2; type++) {
            int count = Math.max(0, hex.terrainLevel(type == 0 ? Terrains.ARMS : Terrains.LEGS));
            for (int index = 0; index < count; index++) {
                int slot = index * 2 + type;
                double angle = slot * 2.399963 + variant;
                float radius = 10 + slot % 4 * 4;
                result.add(new BoardScene.Feature("Limb Club", (float) Math.cos(angle) * radius,
                      (float) Math.sin(angle) * radius, (float) Math.toDegrees(angle), 1, 1, 0,
                      BoardScene.FeatureKind.LIMB));
            }
        }
        for (var structure : structureModels.entrySet()) {
            int heightTerrain = switch (structure.getKey()) {
                case Terrains.BUILDING -> Terrains.BLDG_ELEV;
                case Terrains.FUEL_TANK -> Terrains.FUEL_TANK_ELEV;
                default -> Terrains.INDUSTRIAL;
            };
            result.add(new BoardScene.Feature(structure.getValue(), 0, 0, 0, 1,
                  Math.max(1, hex.terrainLevel(heightTerrain)), 0, structure.getKey() == Terrains.BUILDING
                        ? BoardScene.FeatureKind.BUILDING : BoardScene.FeatureKind.PROP));
        }
        if (hex.containsTerrain(Terrains.FIELDS) && !detailedGround(hex, structureModels, blankTerrains)) {
            add(result, "field", 1, 0, 0);
        }
        if (hex.containsTerrain(Terrains.BRIDGE)) {
            int exits = hex.getTerrain(Terrains.BRIDGE).getExits() & 63;
            result.add(new BoardScene.Feature("bridge", 0, 0, 0, 1, 1,
                  hex.terrainLevel(Terrains.BRIDGE_ELEV), BoardScene.FeatureKind.PROP, exits));
        }
        boolean jungle = hex.containsTerrain(Terrains.JUNGLE);
        BoardRoad road = BoardRoad.capture(hex) == BoardRoad.Kind.NONE ? null
              : BoardRoad.clearance(coords, hex.getTerrain(Terrains.ROAD).getExits());
        if (jungle || hex.containsTerrain(Terrains.WOODS)) {
            boolean orchard = orchard(hex);
            int density = hex.terrainLevel(jungle ? Terrains.JUNGLE : Terrains.WOODS);
            int count = density >= 3 ? 16 : density == 2 ? 9 : orchard ? 6 : 3;
            // Foliage reaches its rules height. Level-one cover uses proportioned shrubs; taller woods keep
            // broad tree crowns so the canopy reads as an obstacle. Light woods have the broadest tree crowns.
            float height = Math.max(1, hex.terrainLevel(Terrains.FOLIAGE_ELEV));
            float crown = orchard ? (density >= 3 ? .62f : .82f)
                  : density >= 3 ? 1.35f : density == 2 ? 1.45f : 1.7f;
            List<String> species = orchard ? orchardSpecies(hex)
                  : height == 1 ? List.of(shrub(hex, jungle)) : species(hex, jungle);
            for (int index = 0; index < count; index++) {
                double angle = index * (density >= 2 ? 2.399963 : 2 * Math.PI / count) + variant;
                // Space light foliage around the centre; dense foliage fills an equal-area spiral.
                float radius = density >= 2 ? 28 * (float) Math.sqrt(index / (count - 1f))
                      : 20 + index * 2;
                String tree = species.get(Math.floorMod(coords.getX() * 31 + coords.getY() * 17 + index, species.size()));
                float x = (float) Math.cos(angle) * radius, y = (float) Math.sin(angle) * radius;
                if (orchard) {
                    // Light orchard rows align across the 63x72 staggered hex lattice.
                    int columns = density >= 3 ? 4 : 3;
                    int rows = count / columns;
                    float spacing = density >= 3 ? 15 : 21;
                    x = (index % columns - (columns - 1) * .5f) * spacing;
                    y = (index / columns - (rows - 1) * .5f) * (density == 1 ? 36 : spacing);
                }
                // Preserve the authoritative woods density, relocating trunks to the verge instead of deleting trees.
                for (int attempt = 0; road != null && road.distance(x, y) < BoardRoad.SHOULDER + 2 && attempt < 24; attempt++) {
                    angle += 2.399963;
                    x = (float) Math.cos(angle) * 29;
                    y = (float) Math.sin(angle) * 29;
                }
                result.add(new BoardScene.Feature(tree, x,
                      y, index * 137.5f, crown * (0.9f + (index % 3) * 0.1f),
                      height * (1f + (index % 3) * 0.05f), 0, BoardScene.FeatureKind.TREE));
            }
        }
        rough(hex, coords, result);
        BoardScatter.capture(hex, coords, result);
        return List.copyOf(result);
    }

    /** Rough is actual terrain cover, independent of cosmetic scatter density, with larger cover for ultra rough. */
    private static void rough(Hex hex, Coords coords, List<BoardScene.Feature> result) {
        if (!hex.containsTerrain(Terrains.ROUGH)
              || hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL,
                    Terrains.SPACE, Terrains.SKY, Terrains.MAGMA)) { return; }
        Random random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L ^ 0xb01deL);
        boolean teeth = BoardRough.variant(hex) == 1;
        boolean felled = BoardRough.variant(hex) == 2;
        boolean ultra = hex.terrainLevel(Terrains.ROUGH) == 2;
        int count = teeth ? (ultra ? 25 : 15) : ultra ? 14 : 9;
        BoardRoad road = hex.containsTerrain(Terrains.ROAD)
              ? BoardRoad.clearance(coords, hex.getTerrain(Terrains.ROAD).getExits()) : null;
        int exits = hex.containsTerrain(Terrains.BRIDGE) ? hex.getTerrain(Terrains.BRIDGE).getExits() : 0;
        BoardRoad bridge = hex.containsTerrain(Terrains.BRIDGE) ? BoardRoad.clearance(coords, exits) : null;
        for (int i = 0; i < count; i++) {
            double angle = i * 2.399963 + random.nextFloat() * .45 + random.nextFloat();
            // Equal-area cover includes the centre and the slopes; units do not reserve an empty ring in Rough.
            float radius = 29 * (float) Math.sqrt(i / (count - 1f));
            float x = (float) Math.cos(angle) * radius, y = (float) Math.sin(angle) * radius;
            float size = .55f + random.nextFloat() * .55f;
            float height = .22f + random.nextFloat() * .32f;
            String asset = "rough-boulder";
            if (teeth) {
                // Identical concrete teeth, with alternate rows staggered by half a tooth spacing.
                x = (i % 5 - 2) * 11 + (i / 5 % 2 == 0 ? -2.75f : 2.75f);
                y = (i / 5 - (count / 5 - 1) * .5f) * 11;
                size = 1;
                height = .28f;
                asset = "rough/dragon-tooth";
            } else if (felled) {
                asset = "rough/felled-trunk";
                // Alternate large standing and toppled stumps among the smaller broken branches.
                if (i % 3 == 0) {
                    boolean fallen = i / 3 % 2 != 0;
                    asset = fallen ? "rough/fallen-stump" : "rough/charred-stump";
                    size = 3 + random.nextFloat() * .4f;
                    height = (1.8f + random.nextFloat() * .3f) * (fallen ? .5f : 1);
                    // Lay long pieces around the centre, leaving room for the standing trunk there.
                    if (fallen) { x = (float) Math.cos(angle) * 20; y = (float) Math.sin(angle) * 20; }
                } else {
                    height = .10f + random.nextFloat() * .07f;
                }
            }
            float clearance = size * (asset.equals("rough/charred-stump") ? 3 : ROUGH_BOULDER_WIDTH / 2);
            boolean blocked = road != null && road.distance(x, y) < BoardRoad.SHOULDER + clearance;
            // Bridge decks follow the complete road footprint, including dead ends and solid roundabout islands.
            blocked |= bridge != null && (bridge.distance(x, y) < BoardRoad.SHOULDER + clearance
                  || BoardRoad.roundabout(exits) && Math.hypot(x, y) < BoardRoad.ROUNDABOUT_RADIUS);
            if (blocked) { continue; }
            float rotation = teeth ? 0 : random.nextFloat() * 360;
            if (asset.equals("rough/fallen-stump")) { rotation = (float) Math.toDegrees(angle) + 90; }
            result.add(new BoardScene.Feature(asset, x, y, rotation, size, height, 0,
                  teeth || felled ? BoardScene.FeatureKind.ROUGH : BoardScene.FeatureKind.BOULDER));
        }
    }

    /**
     * The trees that grow on this hex's ground: palms in jungle; cacti, palms and dead trees in the desert; conifers
     * on rock and on highland meadows two levels up or more; willows on wet dirt; dead trees on Mars and the Moon;
     * park trees on pavement. Snowfields grow the highland's conifers and birches, and snow keeps every species in its
     * winter form.
     */
    private static List<String> species(Hex hex, boolean jungle) {
        BoardScene.Surface surface = surface(hex);
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        boolean snow = surface == BoardScene.Surface.SNOW;
        if (!snow && jungle) { return PALMS; }
        if (!snow && desert(hex)) { return DESERT; }
        List<String> trees = theme.contains("mars") || theme.contains("lunar") ? BARREN : switch (surface) {
            case ROCK -> ROCKY;
            case DIRT -> WETLAND;
            case CONCRETE -> PARK;
            case SNOW -> HIGHLAND;
            default -> hex.getLevel() >= 2 ? HIGHLAND : TEMPERATE;
        };
        return snow ? trees.stream().map(tree -> tree + "-snow").toList() : trees;
    }

    private static boolean desert(Hex hex) {
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        return hex.containsTerrain(Terrains.SAND) || theme.contains("desert") || theme.contains("sand");
    }

    private static List<String> orchardSpecies(Hex hex) {
        return surface(hex) == BoardScene.Surface.SNOW
              ? ORCHARD.stream().map(tree -> tree + "-snow").toList() : ORCHARD;
    }

    /** Level-one woods and jungle are understory, with their own low-growing geometry. */
    private static String shrub(Hex hex, boolean jungle) {
        BoardScene.Surface surface = surface(hex);
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        String family;
        if (surface == BoardScene.Surface.SNOW) {
            family = "snow";
        } else if (jungle) {
            family = "jungle";
        } else if (desert(hex)) {
            family = "desert";
        } else if (theme.contains("mars") || theme.contains("lunar")) {
            family = "barren";
        } else if (hex.containsAnyTerrainOf(Terrains.SWAMP, Terrains.MUD, Terrains.WATER)) {
            family = "wetland";
        } else {
            family = switch (surface) {
                case ROCK -> "rocky";
                case DIRT -> "wetland";
                case CONCRETE -> "temperate";
                default -> hex.getLevel() >= 2 || hex.containsTerrain(Terrains.TUNDRA) ? "highland" : "temperate";
            };
        }
        return "foliage-" + family;
    }

    private static void add(List<BoardScene.Feature> result, String asset, float height, float elevation, float rotation) {
        result.add(new BoardScene.Feature(asset, 0, 0, rotation, 1, height, elevation));
    }
}
