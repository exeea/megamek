/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrains;

/**
 * The 3D board's tileset art of a hex, as text: which images of its own tileset copy
 * ({@code data/models/board/tileset/saxarba.tileset}, used whatever the player's 2D tileset, parsed once per JVM) a hex
 * selects, how the 3D board treats each scenery layer, and which layers the legacy conversion takes. It reads no image,
 * so the conversion runs headless on the server; the 3D board's paint ({@code BoardArtwork}) paints from the same result.
 * Thread-safe: the parsed tileset is shared and only read.
 */
public final class TilesetScenery {
    /** The parsed tileset of the data directory it was read from ({@link TilesetMatcher#root}). */
    private static volatile TilesetMatcher tileset;

    /** The terrains whose tileset art is scenery: a layer matching one of them is classified ({@link #resolve}). */
    public static final List<Integer> SCENERY_TERRAINS = List.of(Terrains.FLUFF, Terrains.GROUND_FLUFF,
          Terrains.ROAD_FLUFF, Terrains.WATER_FLUFF, Terrains.FORTIFIED, Terrains.GEYSER, Terrains.SOLARIS_ELEVATOR,
          Terrains.INDUSTRIAL_ELEVATOR, Terrains.RUBBLE);

    private TilesetScenery() { }

    /**
     * A hex's 3D scenery: each model keeps the terrains its artwork matched, which tell the conversion which legacy token
     * it decodes. {@code decals} are the painted layers that are legacy decal ids ({@link BoardDecalArt#legacy}), which
     * the conversion turns into decal objects.
     */
    public record Scenery(List<Model> sources, Set<Integer> terrains, int cosmeticRoadExits, List<Model> decals) {
        /** A 3D scenery key and the hex terrains its tileset image matched. */
        public record Model(String key, Set<Integer> terrains) {
            public Model { terrains = Set.copyOf(terrains); }
        }
        public static final Scenery EMPTY = new Scenery(List.of(), Set.of(), 0);
        public Scenery {
            sources = List.copyOf(sources);
            terrains = Set.copyOf(terrains);
            decals = List.copyOf(decals);
        }
        public Scenery(List<Model> sources, Set<Integer> terrains, int cosmeticRoadExits) {
            this(sources, terrains, cosmeticRoadExits, List.of());
        }
        public List<String> models() { return sources.stream().map(Model::key).toList(); }
    }

    /** How the 3D board treats a scenery layer. */
    public enum Kind {
        /** Drawn by its {@code layouts.json} rows: {@link Layer#key} is the model key. */
        MODEL,
        /** A road-fluff surface variant, which the road's own material draws. */
        ROAD_SURFACE,
        /** Painted on the surface; a legacy decal id when {@link Layer#key} is set. */
        PAINT,
        /**
         * Neither drawn nor converted: a structure's model, a road-fluff kerb, rough or a vegetation design's woods art
         * (orchard, container yard) covers it.
         */
        SKIPPED,
        /** The old perspective quay sprite, which the fitted quay wall replaces. */
        QUAY
    }

    /**
     * One scenery layer of a hex: its picked image, the hex terrains it matched, its kind, its key (a MODEL's model key
     * {@code scenery/<file>}, or a PAINT layer's legacy decal id {@code decal/<file>}, else null), and whether the legacy
     * conversion takes it. Taken are exactly the model layers and legacy decals whose token is decodable
     * ({@link BoardSceneryLayouts#imported}), pillar art on a bridge included; the 3D board must not draw those again.
     */
    public record Layer(TilesetMatcher.Pick pick, Set<Integer> terrains, Kind kind, String key, boolean taken) {
        public Layer { terrains = Set.copyOf(terrains); }

        /** The picked file with '/' separators. */
        public String file() { return pick.source(); }

        /** The tileset's transparent wildcard image, which a token value without art selects: it draws nothing. */
        public boolean blank() { return file().equals(BLANK); }
    }

    /** The transparent image of the tileset's wildcard entries ({@code fluff:*}, {@code road_fluff:*} and the like). */
    private static final String BLANK = "saxarba/misc/blank.png";

    /** A hex's scenery layers in drawing order, and the {@link Scenery} they give. */
    public record Resolved(List<Layer> layers, Scenery scenery) {
        public Resolved { layers = List.copyOf(layers); }
    }

    /**
     * The 3D board's tileset, parsed on first use and again when the data directory changed since
     * ({@link Configuration#setDataDir}), so its definitions and the images read beside them ({@link TilesetMatcher#root})
     * always come from the current one.
     */
    public static TilesetMatcher tileset() {
        TilesetMatcher result = tileset;
        if (result == null || !result.root().equals(root())) {
            synchronized (TilesetScenery.class) {
                if (tileset == null || !tileset.root().equals(root())) { tileset = load(); }
                result = tileset;
            }
        }
        return result;
    }

    /** Rereads the 3D board's tileset after an artist edits it (the 3D views' asset reload); returns the new one. */
    public static synchronized TilesetMatcher reload() {
        tileset = load();
        return tileset;
    }

    private static File root() { return new File(Configuration.dataDir(), "models/board/tileset"); }

    private static TilesetMatcher load() {
        var result = new TilesetMatcher(root());
        try {
            result.load("saxarba.tileset");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the 3D board's Saxarba tileset", exception);
        }
        return result;
    }

    /** New kits use family/name paths; the selected legacy asset keeps its tileset provenance for fallback. */
    public static File customBuildingFile(String asset) {
        String relative = asset.substring("buildings/".length());
        if (relative.startsWith("saxarba/")) { relative = relative.substring("saxarba/".length()); }
        return new File(Configuration.dataDir(), "models/buildings/" + relative + ".glb");
    }

    /**
     * The hex's super images (and orthographic ones), each appearance resolved in isolation: an appearance owner's
     * layers come from its designed artwork hex, in place of the hex's own, and unrelated shared-fluff art stays. They are
     * drawn in the tileset's order ({@link TilesetMatcher#order}), as the token the design stands for would be.
     */
    public static List<TilesetMatcher.Pick> appearanceLayers(Hex hex, boolean orthographic) {
        return appearanceLayers(tileset(), hex, orthographic);
    }

    private static List<TilesetMatcher.Pick> appearanceLayers(TilesetMatcher definitions, Hex hex, boolean orthographic) {
        var selection = definitions.select(hex);
        List<TilesetMatcher.Pick> images = new ArrayList<>(selection.supers());
        if (orthographic) { images.addAll(selection.orthographic()); }
        if (hex.getAppearance().isEmpty()) { return images; }
        var blueprint = BoardEditorBlueprint.get();
        for (String owner : hex.getAppearance().keySet().stream().sorted().toList()) {
            Hex query = blueprint.artwork(hex, owner);
            if (query == hex) { continue; }
            List<Integer> types = owner.equals("rough") ? List.of(Terrains.ROUGH)
                  : blueprint.components().stream().filter(c -> c.id().equals(owner)).flatMap(c -> c.fields().stream())
                        .map(f -> Terrains.getType(f.terrain())).toList();
            Predicate<TilesetMatcher.Pick> belongs = image -> types.stream().anyMatch(image::has);
            images.removeIf(belongs);
            var designed = definitions.select(query);
            List<TilesetMatcher.Pick> replacement = new ArrayList<>(designed.supers());
            if (orthographic) { replacement.addAll(designed.orthographic()); }
            replacement.removeIf(belongs.negate());
            images.addAll(replacement);
        }
        images.sort(Comparator.comparingInt(definitions::order));
        return images;
    }

    /**
     * The hex's structure models by structure terrain: a designed building's model, else the model beside the tileset
     * art a structure's image selects; only models whose GLB file exists.
     */
    public static Map<Integer, String> structureModels(Hex hex) {
        return structureModels(tileset(), hex);
    }

    private static Map<Integer, String> structureModels(TilesetMatcher definitions, Hex hex) {
        Map<Integer, String> models = new HashMap<>();
        var building = hex.getAppearance().get("building");
        if (building != null && hex.containsTerrain(Terrains.BUILDING)) {
            var variant = BoardEditorBlueprint.get().variant(building.variant());
            String asset = building.asset() != null ? building.asset() : variant == null ? null : variant.model(hex);
            if (asset != null && !asset.isBlank() && (new File(Configuration.dataDir(), "models/board/" + asset + ".glb").isFile()
                  || asset.startsWith("buildings/") && customBuildingFile(asset).isFile())) { models.put(Terrains.BUILDING, asset); }
        }
        if (hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL)) {
            List<TilesetMatcher.Pick> images = new ArrayList<>(appearanceLayers(definitions, hex, false));
            images.add(definitions.select(hex).base());
            for (TilesetMatcher.Pick image : images) {
                String source = image == null ? "" : image.source();
                int extension = source.lastIndexOf('.');
                if (extension > 0) {
                    String model = "buildings/" + source.substring(0, extension);
                    boolean legacy = new File(Configuration.dataDir(), "models/board/" + model + ".glb").isFile();
                    boolean custom = customBuildingFile(model).isFile();
                    for (int terrain : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL }) {
                        if ((legacy || custom) && hex.containsTerrain(terrain) && image.has(terrain)) {
                            models.putIfAbsent(terrain, model);
                        }
                    }
                }
            }
        }
        return Map.copyOf(models);
    }

    /** {@link #resolve(Hex, Map, boolean)} with the hex's own {@link #structureModels}. */
    public static Resolved resolve(Hex hex, boolean nativeBoard) {
        TilesetMatcher definitions = tileset();
        return resolve(definitions, hex, structureModels(definitions, hex), nativeBoard);
    }

    /**
     * The hex's scenery layers, from its original selected layers: matching a hex after removing its roads, woods or
     * buildings would lose composite rules (parked cars, port containers, gardens). A layer is scenery when it matched a
     * {@link #SCENERY_TERRAINS} terrain; pavement variants belong to the ground. A sibling scenery model replaces only its
     * layer; flat art stays paint on the existing surface. On a native board, ROAD level 2 is the Alley finish, not the
     * legacy road-with-trees art. {@code structures} are the hex's {@link #structureModels}.
     */
    public static Resolved resolve(Hex hex, Map<Integer, String> structures, boolean nativeBoard) {
        return resolve(tileset(), hex, structures, nativeBoard);
    }

    private static Resolved resolve(TilesetMatcher definitions, Hex hex, Map<Integer, String> structures,
          boolean nativeBoard) {
        List<Scenery.Model> models = new ArrayList<>(), legacyDecals = new ArrayList<>();
        List<Layer> result = new ArrayList<>();
        Set<Integer> covered = new HashSet<>();
        int cosmeticRoadExits = 0;
        List<TilesetMatcher.Pick> layers = new ArrayList<>(appearanceLayers(definitions, hex, true));
        var base = definitions.select(hex).base();
        if (base != null) { layers.add(base); }
        Set<Integer> queryTypes = new HashSet<>();
        for (int type : hex.getTerrainTypes()) { queryTypes.add(type); }
        if (!hex.getAppearance().isEmpty()) {
            for (String owner : hex.getAppearance().keySet()) {
                for (int type : BoardEditorBlueprint.get().artwork(hex, owner).getTerrainTypes()) {
                    queryTypes.add(type);
                }
            }
        }
        // The kerb and woods art (orchard, container yard) a design stands for is skipped as its token's is: the design
        // draws it (rough reads its design itself).
        Hex road = hex, vegetation = hex;
        if (!hex.getAppearance().isEmpty()) {
            road = BoardEditorBlueprint.get().artwork(hex, "road");
            vegetation = BoardEditorBlueprint.get().artwork(hex, "vegetation");
        }
        for (TilesetMatcher.Pick layer : layers) {
            Set<Integer> types = new HashSet<>();
            for (int type : queryTypes) {
                if (layer.has(type)) { types.add(type); }
            }
            boolean decoration = false;
            for (int type : SCENERY_TERRAINS) { decoration |= types.contains(type); }
            decoration |= !nativeBoard && types.contains(Terrains.ROAD) && hex.terrainLevel(Terrains.ROAD) == 2;
            // Pavement variants belong to the ground; repainting them as scenery hides the native concrete.
            if (!decoration) { continue; }
            covered.addAll(types);
            if (types.stream().anyMatch(structures::containsKey)
                  || types.contains(Terrains.ROAD_FLUFF) && road.terrainLevel(Terrains.ROAD_FLUFF) == 1
                        && types.contains(Terrains.ROAD)
                  || types.contains(Terrains.FLUFF) && (HexTile.roughVariant(hex) != 0
                        || types.contains(Terrains.WOODS) && BoardEditorBlueprint.get().vegetationArt(vegetation))) {
                result.add(new Layer(layer, types, Kind.SKIPPED, null, false));
                continue;
            }
            String source = layer.source();
            // Both GPU views use the fitted quay wall. Its old perspective sprite must not also be
            // projected onto the seabed or baked into the Tactical View's ground artwork.
            if (source.startsWith("Structured_Pavement/Fluff/quay_fluff")) {
                result.add(new Layer(layer, types, Kind.QUAY, null, false));
                continue;
            }
            int extension = source.lastIndexOf('.');
            String asset = extension < 0 ? "" : "scenery/" + source.substring(0, extension);
            // layouts.json is the one legacy key -> mesh table; no mesh sits at a legacy key.
            if (!asset.isEmpty() && BoardSceneryLayouts.hasLayout(asset)) {
                models.add(new Scenery.Model(asset, types));
                // The selected parking sprite specifies a visual route even without gameplay ROAD terrain.
                // These are ordinary hex-direction bits consumed by the existing road engine.
                cosmeticRoadExits |= switch (source) {
                    case "fluff/cars_1.gif", "fluff/cars_4.gif" -> 18;
                    case "fluff/cars_2.gif", "fluff/cars_5.gif", "fluff/cars_7.gif" -> 36;
                    case "fluff/cars_3.gif", "fluff/cars_6.gif", "fluff/cars_8.gif" -> 9;
                    default -> 0; // The 2b/3b variants intentionally omit the road.
                };
                result.add(new Layer(layer, types, Kind.MODEL, asset, BoardSceneryLayouts.imported(types, hex)));
            } else if (types.contains(Terrains.ROAD) && hex.terrainLevel(Terrains.ROAD) >= 1
                  && hex.terrainLevel(Terrains.ROAD) <= Terrains.ROAD_LVL_GRAVEL) {
                // Road-fluff surface variants use the same material, joins and relief as every other road.
                result.add(new Layer(layer, types, Kind.ROAD_SURFACE, null, false));
            } else {
                String decal = extension < 0 ? "" : "decal/" + source.substring(0, extension);
                boolean legacy = BoardDecalArt.legacy(decal) != null;
                if (legacy) { legacyDecals.add(new Scenery.Model(decal, types)); }
                result.add(new Layer(layer, types, Kind.PAINT, legacy ? decal : null,
                      legacy && BoardSceneryLayouts.imported(types, hex)));
            }
        }
        return new Resolved(result, new Scenery(models, covered, cosmeticRoadExits, legacyDecals));
    }
}
