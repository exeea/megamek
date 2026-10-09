/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSnapping;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.MaglevRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Plain authored compositions of shared meshes; terrain rules and artwork selection remain with their owners. The same
 * rows drive the legacy render ({@link Layout#features}) and the editor's import and stamps ({@link Layout#decorations}).
 */
public final class BoardSceneryLayouts {
    /** The pillar mesh of the legacy pillar art (pillars1-6); on a bridge hex that art is the bridge's Pillars toggle. */
    static final String PILLAR = "scenery/parks/pillar";
    /** The maglev pieces (route, platform, wagon, cab and coupler), which ride their route marker. */
    private static final String MAGLEV = "scenery/maglev/";
    private static final Map<String, Layout> LAYOUTS = read();
    /** The 3D board's import and stamps, for the editor session. */
    public static final BoardEditorSession.LegacyDecoder DECODER = new BoardEditorSession.LegacyDecoder() {
        @Override public List<BoardEditorSession.Issue> importBoard(Board board) {
            return BoardSceneryLayouts.importBoard(board);
        }
        @Override public List<BoardDecoration> stamp(String layout, Hex hex, Coords at) {
            return BoardSceneryLayouts.stamp(layout, hex, at);
        }
        @Override public void typeBridges(Board board) {
            // Only fluff can be pillar art, and it counts only on an untyped span (a stored type decides the span);
            // most boards, and every board the 3D editor saved, need no tileset resolution.
            List<Coords> fluff = new ArrayList<>();
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    Hex hex = board.getHex(x, y);
                    if (hex.containsAllTerrainsOf(Terrains.BRIDGE, Terrains.FLUFF)
                          && HexAppearance.bridgeBuilt(hex.getAppearance()) == null) { fluff.add(new Coords(x, y)); }
                }
            }
            Map<Coords, BoardArtwork.Scenery> scenery = new HashMap<>();
            if (!fluff.isEmpty()) {
                try (var artwork = new BoardArtwork()) { fluff.forEach(at -> scenery.put(at, artwork.scenery(board, at))); }
            }
            BoardSceneryLayouts.typeBridges(board, bridgeTypes(board, scenery));
        }
    };

    private BoardSceneryLayouts() { }

    /**
     * A parked car's place: slot {@code slot} ({@link BoardDecalArt.Footprint#slots}) of the layout's {@code plate}-th
     * decal row.
     */
    record Seat(int plate, int slot) { }

    /** A car park's decal row as placed (model px), the frame of its seats; {@code mirror} flips its art along the row. */
    private record Plate(Component row, float x, float y, float rotation, boolean mirror) {
        /**
         * Beside a road, the row as the data places it: its open side on the road's edge. Without one, the row folded
         * back to back on the line through the hex centre along it: turned to open away from that line, its closed side
         * on it, and mirrored so its slots keep their order along the lane (each car keeps its side and its order).
         */
        static Plate of(Component row, boolean road) {
            if (road) { return new Plate(row, row.x(), row.y(), row.rotation(), false); }
            // The row's back (its +y) points away from its lane; move it so its back edge lies on the centre line.
            double turn = Math.toRadians(row.rotation()), backX = -Math.sin(turn), backY = Math.cos(turn);
            double half = BoardDecalArt.footprint(row.asset()).height() * row.scale() / 2;
            double shift = half - (row.x() * backX + row.y() * backY);
            return new Plate(row, (float) (row.x() + shift * backX), (float) (row.y() + shift * backY),
                  row.rotation() + 180, true);
        }
    }

    /**
     * A row; {@code connections} are a maglev route marker's sides (see {@link BoardDecoration#connections()}),
     * {@code stretch} its optional per-axis factors on top of {@code scale} (see {@link BoardDecoration#stretch()}),
     * {@code colours} its mesh's replacement slot colours (see {@link BoardDecoration#colours()}), such as a car's paint,
     * and {@code seat} (or null) where import parks it instead of its legacy spot.
     */
    record Component(String asset, BoardScene.FeatureKind kind, float x, float y, float z,
          float rotation, float scale, int connections, BoardDecoration.Stretch stretch, BoardDecoration.Colours colours,
          Seat seat) {
        Component(String asset, BoardScene.FeatureKind kind, float x, float y, float z, float rotation, float scale) {
            this(asset, kind, x, y, z, rotation, scale, 0, BoardDecoration.Stretch.NONE, BoardDecoration.Colours.NONE);
        }

        Component(String asset, BoardScene.FeatureKind kind, float x, float y, float z, float rotation, float scale,
              int connections, BoardDecoration.Stretch stretch, BoardDecoration.Colours colours) {
            this(asset, kind, x, y, z, rotation, scale, connections, stretch, colours, null);
        }

        /** Paint on the receiver, such as a car park's row of bays, rather than a mesh. */
        boolean decal() { return asset.startsWith("decal/"); }

        /** A maglev route marker: its own object, never a group member. */
        boolean route() { return asset.equals(MaglevRoute.ASSET); }
    }

    record Layout(List<Component> components) {
        /** The rows that are pieces of the artwork; two or more of them form a group. */
        long pieces() { return components.stream().filter(component -> !component.route()).count(); }

        /** Pillar art (pillars1-6): every row is the pillar mesh. */
        boolean pillars() { return components.stream().allMatch(component -> component.asset().equals(PILLAR)); }

        /** The rows as the legacy render's features on {@code hex}; trees take their winter form as placed trees do. */
        List<BoardScene.Feature> features(Coords coords, Hex hex, boolean natural) {
            var result = new ArrayList<BoardScene.Feature>();
            for (Component component : chosen(coords, BoardFeatures.parkSpecies(), natural)) {
                // The legacy render paves a car park's lane itself (cosmetic road exits); only import places the decal.
                if (component.decal()) { continue; }
                boolean tree = component.kind() == BoardScene.FeatureKind.TREE;
                float height = tree ? component.scale() * 30 / BoardGeometry.MODEL_LEVEL_HEIGHT : 1;
                String asset = tree ? BoardFeatures.snowForm(hex, component.asset(), false) : component.asset();
                result.add(new BoardScene.Feature(asset, component.x(), component.y(), component.rotation(),
                      component.scale(), height, component.z() / BoardGeometry.MODEL_LEVEL_HEIGHT, component.kind(), 0, true,
                      null, component.stretch(), component.colours()));
            }
            return List.copyOf(result);
        }

        /**
         * The same rows as native objects on {@code hex}: XY in hex width/height units, ids in row order from
         * {@code ids}. Trees store their bare species; the renderer shows the winter form on snow. A route marker keeps
         * its sides and stays out of {@code group}. The maglev pieces share the route's placement, so a train rides the
         * rail (their ride height is in their meshes): the layout's own marker, else the marker already on the hex (a
         * stamped train). Other rows, such as parked cars, stand on the hex's support, trees beneath a bridge's deck
         * ({@link BoardSceneryLayouts#receiver}); a maglev artwork leaves them out
         * where its fixed-level route crosses water or an industrial plant, which give them no support. A car park's
         * decal rows stand beside its lane's road ({@code road}, as the data places them) or, without one, back to back
         * ({@link Plate#of}); a seated car parks in its slot of its decal row; the legacy render keeps its legacy spot.
         */
        List<BoardDecoration> decorations(Coords coords, Hex hex, Supplier<String> ids, String group, boolean road) {
            List<BoardDecoration> result = new ArrayList<>();
            boolean ownRoute = components.stream().anyMatch(Component::route);
            var marker = MaglevRoute.find(hex);
            var ride = ownRoute ? routePlacement(hex) : marker == null ? null : marker.placement();
            List<Plate> plates = components.stream().filter(Component::decal).map(row -> Plate.of(row, road)).toList();
            int decals = 0;
            for (Component row : chosen(coords, BoardFeatures.parkSpecies(), BoardFeatures.NATURAL_TREE_DISTRIBUTION)) {
                Plate own = row.decal() ? plates.get(decals++) : null;
                boolean rides = ride != null && row.asset().startsWith(MAGLEV);
                var placement = rides ? ride : receiver(hex, row.kind() != BoardScene.FeatureKind.TREE);
                String support = placement.mode().equals("absolute") ? "" : placement.receiver().terrain();
                if (ownRoute && !rides && (support.equals("industrial") || support.equals("ground") && hex.containsTerrain(Terrains.WATER))) {
                    continue;
                }
                float x = own == null ? row.x() : own.x(), y = own == null ? row.y() : own.y();
                float rotation = row.route() ? 0 : own == null ? row.rotation() : own.rotation();
                if (row.seat() != null) {
                    // The slot as the plate paints it: a mirrored plate flips its art along the row.
                    Plate plate = plates.get(row.seat().plate());
                    var slot = BoardDecalArt.footprint(plate.row().asset()).slots().get(row.seat().slot());
                    double mirror = plate.mirror() ? -1 : 1, scale = plate.row().scale();
                    double turn = Math.toRadians(plate.rotation()), sx = mirror * slot.x() * scale, sy = slot.y() * scale;
                    x = (float) (plate.x() + sx * Math.cos(turn) - sy * Math.sin(turn));
                    y = (float) (plate.y() + sx * Math.sin(turn) + sy * Math.cos(turn));
                    rotation = (float) (plate.rotation() + mirror * slot.heading());
                }
                result.add(new BoardDecoration(ids.get(), row.decal() ? "decal" : "prop", row.asset(), null,
                      x / BoardGeometry.TILE_WIDTH, y / BoardGeometry.TILE_HEIGHT, rotation,
                      own != null && own.mirror(), row.scale(), placement, 0, row.decal(), 0, 0,
                      row.route() ? null : group, false, row.connections(), row.stretch(), row.colours()));
            }
            return result;
        }

        /**
         * The rows with each broad park tree's species and heading drawn, in row order, from the hex's seed. Both
         * adapters share it, so an imported tree is the species the legacy render showed.
         */
        private List<Component> chosen(Coords coords, List<String> species, boolean natural) {
            var random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L);
            List<Component> result = new ArrayList<>(components.size());
            for (Component row : components) {
                if (!natural || row.kind() != BoardScene.FeatureKind.TREE) { result.add(row); continue; }
                String asset = BoardTreeDistribution.species(species, coords, row.x(), row.y(), random.nextLong());
                result.add(new Component(asset, row.kind(), row.x(), row.y(), row.z(), random.nextFloat() * 360, row.scale(),
                      row.connections(), row.stretch(), row.colours(), row.seat()));
            }
            return result;
        }
    }

    static Layout layout(String asset) { return LAYOUTS.get(asset); }

    /** A composed artwork selection needs no baked per-layout mesh. */
    public static boolean hasLayout(String asset) { return LAYOUTS.containsKey(asset); }

    /**
     * Pillar art on a bridge hex is the bridge's Pillars toggle ({@link HexAppearance#PILLARS}), not objects: the one
     * decode of import and of the legacy render. On any other hex it stays plain pillars.
     */
    static boolean bridgePillars(Hex hex, String key) {
        return hex.containsTerrain(Terrains.BRIDGE) && LAYOUTS.get(key).pillars();
    }

    /**
     * The legacy decode's type ({@link BoardBridge#built}) for every bridge hex of {@code board} that stores none, decoded
     * once per span, with pillar art read from {@code scenery} (the hexes resolved so far). Import calls it before it
     * changes the board.
     */
    static Map<Coords, HexAppearance> bridgeTypes(Board board, Map<Coords, BoardArtwork.Scenery> scenery) {
        Map<Coords, HexAppearance> types = new HashMap<>();
        java.util.function.Function<Coords, Hex> hexes = board::getHex;
        java.util.function.Predicate<Coords> pillarArt = at -> scenery.getOrDefault(at, BoardArtwork.Scenery.EMPTY).models()
              .stream().anyMatch(key -> bridgePillars(board.getHex(at), key));
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                if (!hex.containsTerrain(Terrains.BRIDGE) || types.containsKey(at)
                      || HexAppearance.bridgeBuilt(hex.getAppearance()) != null) { continue; }
                var span = megamek.common.board.BridgeSpan.of(hexes, at);
                var type = BoardBridge.built(hexes, pillarArt, span) ? HexAppearance.BUILT_BRIDGE : HexAppearance.NATURAL_BRIDGE;
                span.forEach(member -> types.put(member, type));
            }
        }
        return types;
    }

    /** Writes each of {@code types} on its hex, except where the hex has a type by now (pillar art made it PILLARS). */
    static void typeBridges(Board board, Map<Coords, HexAppearance> types) {
        Map<megamek.common.board.BoardLocation, Hex> typed = new HashMap<>();
        types.forEach((at, type) -> {
            Hex hex = board.getHex(at);
            if (HexAppearance.bridgeBuilt(hex.getAppearance()) != null) { return; }
            Hex next = hex.duplicate();
            Map<String, HexAppearance> appearance = new HashMap<>(next.getAppearance());
            appearance.put("bridge", type);
            next.setAppearance(appearance);
            typed.put(megamek.common.board.BoardLocation.of(at, board.getBoardId()), next);
        });
        if (!typed.isEmpty()) { board.setHexes(typed); }
    }

    /** The hex's appearance as the legacy render draws it: with its bridge's Pillars toggle where pillar art turns it on. */
    static Map<String, HexAppearance> appearance(Hex hex, BoardArtwork.Scenery scenery) {
        if (scenery.models().stream().noneMatch(key -> bridgePillars(hex, key))) { return hex.getAppearance(); }
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        appearance.put("bridge", HexAppearance.PILLARS);
        return appearance;
    }

    /**
     * Editor import of a legacy board: each decodable scenery token becomes the objects its artwork shows, one group
     * per artwork that has several, with ids {@code L<x>_<y>_<n>}; the board becomes native. Rule terrain is kept, except
     * that a car park on plain ground gets its lane as a road ({@code laneRoad}), which runs between its rows of bays;
     * where no road is drawn the rows stand back to back ({@link #roadside}). Artwork resolves with the 3D board's
     * tileset, exactly as the legacy render resolves it. A legacy decal
     * ({@link BoardDecalArt#legacy}) becomes its decal: a one-hex variant at its scale and turn, and the pieces of a 7-hex
     * emblem set ONE emblem on the set's centre hex, spanning its neighbours; a set with pieces missing still gets the
     * full emblem and an issue. Every bridge hex gets its type, decoded on the board as loaded ({@link #bridgeTypes}).
     * Returns those issues. EDT only.
     */
    public static List<BoardEditorSession.Issue> importBoard(Board board) {
        // Resolve every hex first: a set piece names its centre, which may come later in the scan or have no piece.
        Map<Coords, BoardArtwork.Scenery> scenery = new LinkedHashMap<>();
        try (var artwork = new BoardArtwork()) {
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    Coords coords = new Coords(x, y);
                    Hex hex = board.getHex(coords);
                    // Only these tokens can select importable artwork; most hexes need no tileset resolution.
                    if (hex.containsAnyTerrainOf(Terrains.FLUFF, Terrains.ROAD_FLUFF) || hex.terrainLevel(Terrains.ROAD) == 2) {
                        scenery.put(coords, artwork.scenery(board, coords));
                    }
                }
            }
        }
        // Every bridge's type, decoded on the board as loaded: the lane roads below must not retype a span.
        Map<Coords, HexAppearance> bridges = bridgeTypes(board, scenery);
        // The car parks that get their lane as a road (laneRoad), all known before import changes a hex.
        Map<Coords, Integer> lanes = new HashMap<>();
        scenery.forEach((coords, artwork) -> {
            Hex hex = board.getHex(coords);
            if (artwork.cosmeticRoadExits() != 0 && !hex.containsTerrain(Terrains.ROAD) && plainGround(hex)
                  && !BoardLiquid.capture(hex).present()) {
                lanes.put(coords, artwork.cosmeticRoadExits());
            }
        });
        // Emblem sets: owner hex -> emblem -> the piece directions found (-1 the centre piece). The owner is the set's
        // centre; a centre beyond the board edge (a cut set) is owned by its first piece's hex, at the centre's offset.
        Map<Coords, Map<String, Set<Integer>>> sets = new HashMap<>();
        Map<Coords, Coords> centres = new HashMap<>();
        scenery.forEach((coords, artwork) -> {
            for (var decal : artwork.decals()) {
                var legacy = BoardDecalArt.legacy(decal.key());
                if (!legacy.set() || !imported(decal.terrains(), board.getHex(coords))) { continue; }
                Coords centre = legacy.centre(coords);
                Coords owner = board.contains(centre) ? centre : centres.computeIfAbsent(centre, key -> coords);
                sets.computeIfAbsent(owner, key -> new TreeMap<>()).computeIfAbsent(legacy.decal(), key -> new TreeSet<>())
                      .add(legacy.direction());
            }
        });
        Map<Coords, Coords> centreOf = new HashMap<>();
        centres.forEach((centre, owner) -> centreOf.put(owner, centre));
        List<BoardEditorSession.Issue> issues = new ArrayList<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords coords = new Coords(x, y);
                var artwork = scenery.getOrDefault(coords, BoardArtwork.Scenery.EMPTY);
                if (artwork == BoardArtwork.Scenery.EMPTY && !sets.containsKey(coords)) { continue; }
                Hex hex = board.getHex(coords);
                List<BoardDecoration> added = new ArrayList<>();
                Set<Integer> decoded = new HashSet<>();
                String prefix = "L" + x + "_" + y + "_";
                int[] next = { 0 };
                int keys = 0;
                boolean overWoods = false, pillared = false;
                // A car park's rows stand beside the road along its lane (its own, or the lane import gives it), else
                // back to back: no lane (2b/3b), off the ground, on a liquid, or a road level that is not drawn.
                boolean road = lanes.containsKey(coords) || artwork.cosmeticRoadExits() != 0 && roadside(hex);
                for (var model : artwork.sources()) {
                    if (!imported(model.terrains(), hex)) { continue; }
                    if (model.terrains().contains(Terrains.FLUFF)) { decoded.add(Terrains.FLUFF); }
                    if (model.terrains().contains(Terrains.ROAD_FLUFF) && hex.terrainLevel(Terrains.ROAD_FLUFF) == 3) {
                        decoded.add(Terrains.ROAD_FLUFF);
                    }
                    // Pillar art on a bridge turns on the bridge's Pillars toggle (below) and places no objects.
                    if (bridgePillars(hex, model.key())) { pillared = true; continue; }
                    // Every model key has rows: BoardArtwork counts only layouts.json keys as models.
                    Layout rows = LAYOUTS.get(model.key());
                    String group = rows.pieces() > 1 ? prefix + "g" + keys : null;
                    keys++;
                    added.addAll(rows.decorations(coords, hex, () -> prefix + next[0]++, group, road));
                    // Artwork drawn over woods replaced the woods' trees in the legacy render (seaport yards).
                    overWoods |= model.terrains().contains(Terrains.WOODS);
                }
                for (var decal : artwork.decals()) {
                    if (!imported(decal.terrains(), hex)) { continue; }
                    // Every legacy decal token is decoded; a set piece's emblem is placed on its centre below.
                    decoded.add(Terrains.FLUFF);
                    var legacy = BoardDecalArt.legacy(decal.key());
                    if (legacy.set()) { continue; }
                    added.add(new BoardDecoration(prefix + next[0]++, "decal", legacy.decal(), null, 0, 0, legacy.rotation(),
                          false, legacy.scale(), receiver(hex, true), 0, true));
                }
                for (var set : sets.getOrDefault(coords, Map.of()).entrySet()) {
                    Coords centre = centreOf.getOrDefault(coords, coords);
                    float dx = (BoardGeometry.centerX(centre) - BoardGeometry.centerX(coords)) / BoardGeometry.width();
                    float dy = (BoardGeometry.centerY(centre) - BoardGeometry.centerY(coords)) / BoardGeometry.height();
                    String id = prefix + next[0]++;
                    // One emblem over the centre and its six neighbours, unclipped, on the owner's support.
                    added.add(new BoardDecoration(id, "decal", set.getKey(), null, dx, dy, 0, false, 1,
                          receiver(hex, true), 0, false));
                    if (set.getValue().size() < 7) {
                        issues.add(new BoardEditorSession.Issue(coords, id, "Emblem set with " + set.getValue().size()
                              + " of 7 pieces: the full emblem is placed"));
                    }
                }
                // A decoded token goes even when its artwork leaves no object (pillar art on a bridge).
                if (added.isEmpty() && decoded.isEmpty()) { continue; }
                Hex result = hex.duplicate();
                decoded.forEach(result::removeTerrain);
                List<BoardDecoration> objects = new ArrayList<>(hex.getDecorations());
                objects.addAll(added);
                result.setDecorations(objects);
                if (overWoods && !result.getAppearance().containsKey("vegetation")) {
                    // The woods stay for the rules; their design draws no trees, as the legacy art showed none.
                    Map<String, HexAppearance> appearance = new HashMap<>(result.getAppearance());
                    appearance.put("vegetation", new HexAppearance(BoardFeatures.NO_TREES, null, null, null));
                    result.setAppearance(appearance);
                }
                if (pillared) { result.setAppearance(appearance(result, artwork)); }
                laneRoad(board, coords, result, lanes);
                board.setHex(coords, result);
            }
        }
        typeBridges(board, bridges);
        board.setNativeFormat(true);
        return List.copyOf(issues);
    }

    /**
     * The only statement of what import takes: decodable FLUFF (not server rubble), roadside-tree
     * ROAD_FLUFF 3, and the legacy road-with-trees art of ROAD level 2. Everything else is terrain-owned or kept.
     */
    static boolean imported(Set<Integer> terrains, Hex hex) {
        if (terrains.contains(Terrains.FLUFF)) {
            int level = hex.terrainLevel(Terrains.FLUFF);
            return level < 2000;
        }
        if (terrains.contains(Terrains.ROAD_FLUFF)) { return hex.terrainLevel(Terrains.ROAD_FLUFF) == 3; }
        return terrains.contains(Terrains.ROAD) && hex.terrainLevel(Terrains.ROAD) == 2;
    }

    /**
     * A stamp's members on {@code hex} at {@code at}: fresh ids and one fresh group. {@code {exits}} in the layout key
     * is the hex's road exits as two digits. EDT only.
     */
    public static List<BoardDecoration> stamp(String layout, Hex hex, Coords at) {
        String key = layout;
        if (key.contains("{exits}")) {
            if (!hex.containsTerrain(Terrains.ROAD)) { throw new IllegalArgumentException("Paint a road first"); }
            key = key.replace("{exits}", String.format(Locale.ROOT, "%02d", hex.getTerrain(Terrains.ROAD).getExits() & 63));
        }
        Layout rows = LAYOUTS.get(key);
        if (rows == null) { throw new IllegalArgumentException("Unknown stamp layout " + key); }
        String group = rows.pieces() > 1 ? UUID.randomUUID().toString() : null;
        return rows.decorations(at, hex, () -> UUID.randomUUID().toString(), group, roadside(hex));
    }

    /**
     * The surface an imported or stamped object stands on: the hex's roof, deck, industrial top, fuel tank or ice, else
     * its ground. Without {@code deck} it stands beneath a bridge, on what lies under the deck (trees: the legacy
     * road-with-trees art of a road passing under the bridge).
     */
    static BoardDecoration.Placement receiver(Hex hex, boolean deck) {
        String support = hex.containsTerrain(Terrains.BUILDING) ? "building"
              : hex.containsTerrain(Terrains.BRIDGE) && deck ? "bridge"
              : hex.containsTerrain(Terrains.INDUSTRIAL) ? "industrial"
              : hex.containsTerrain(Terrains.FUEL_TANK) ? "fuelTank"
              : hex.containsTerrain(Terrains.ICE) ? "ice" : "ground";
        return BoardDecoration.Placement.on(support, 0);
    }

    /** The hex's own dry ground carries its objects: no roof, deck, industrial top, fuel tank, ice or water. */
    static boolean plainGround(Hex hex) {
        return !hex.containsTerrain(Terrains.WATER) && receiver(hex, true).receiver().terrain().equals("ground");
    }

    /**
     * The renderer draws a road on the hex's own dry ground (its road kind, no liquid): a car park's decal rows stand
     * beside it rather than back to back.
     */
    static boolean roadside(Hex hex) {
        return BoardRoad.capture(hex) != BoardRoad.Kind.NONE && plainGround(hex) && !BoardLiquid.capture(hex).present();
    }

    /**
     * Import's one rule-terrain change: a car park on plain ground without a road gets the lane the legacy render paves
     * through its sprite ({@link BoardArtwork.Scenery#cosmeticRoadExits}) as a paved road with exactly those exits, so it
     * runs edge to edge between its two rows of bays and joins only the roads that point along it. A car park on a roof,
     * deck, industrial top, fuel tank, ice or water keeps no road (its sprite was decoration there), nor does one on another
     * liquid, where no road is drawn; an authored road is kept. {@code lanes} holds every car park that gets its lane.
     * An automatic neighbour road on the lane stays automatic and gains its exit toward the car park. One off the lane
     * is written out with the exits it loaded with plus one toward each lane that points at it, so it grows no arm
     * toward a car park and joins every lane along it, whichever car park the scan meets first. Call before the car
     * park's own {@code board.setHex}.
     */
    private static void laneRoad(Board board, Coords coords, Hex result, Map<Coords, Integer> lanes) {
        int lane = lanes.getOrDefault(coords, 0);
        if (lane == 0) { return; }
        result.addTerrain(new Terrain(Terrains.ROAD, 1, true, lane));
        for (int direction = 0; direction < 6; direction++) {
            Coords next = coords.translated(direction);
            Terrain road = board.contains(next) ? board.getHex(next).getTerrain(Terrains.ROAD) : null;
            if (road == null || road.hasExitsSpecified() || (lane & 1 << direction) != 0
                  || (road.getExits() & 1 << (direction + 3) % 6) != 0) { continue; }
            int exits = road.getExits();
            for (int toward = 0; toward < 6; toward++) {
                if ((lanes.getOrDefault(next.translated(toward), 0) & 1 << (toward + 3) % 6) != 0) { exits |= 1 << toward; }
            }
            Hex kept = board.getHex(next).duplicate();
            kept.addTerrain(new Terrain(Terrains.ROAD, road.getLevel(), true, exits));
            board.setHex(next, kept);
        }
    }

    /**
     * A route marker follows plain ground; on water, ice, a deck, roof, industrial top or fuel tank it holds that
     * support's level, since a route either follows the ground or keeps a fixed level. A support without its height
     * (a loadable but invalid hex) leaves the marker on the ground.
     */
    static BoardDecoration.Placement routePlacement(Hex hex) {
        var support = receiver(hex, true);
        if (plainGround(hex)) { return support; }
        double level = BoardEditorSnapping.level(hex, support);
        return Double.isFinite(level) ? BoardDecoration.Placement.absolute(level) : BoardDecoration.Placement.ground();
    }

    private static Map<String, Layout> read() {
        var file = new FileHandle(new File(Configuration.dataDir(), "models/board/scenery/layouts.json"));
        // Legacy scenery keys decode through these rows; there are no baked composite meshes to fall back to.
        if (!file.exists()) { throw new IllegalStateException("Cannot load the 3D board's scenery layouts"); }
        var result = new HashMap<String, Layout>();
        for (var entry : new JsonReader().parse(file)) {
            var components = new ArrayList<Component>();
            for (var component : entry) {
                var position = component.get("position");
                String asset = component.getString("asset");
                // The shared park tree is the only tree slot; every other row is scenery. A stored "kind" is ignored.
                var kind = asset.equals("tree-broad") ? BoardScene.FeatureKind.TREE : BoardScene.FeatureKind.SCENERY;
                var stretch = component.get("stretch");
                var seat = component.get("seat");
                var colours = new ArrayList<String>();
                if (component.has("colours")) {
                    for (var colour : component.get("colours")) { colours.add(colour.isNull() ? null : colour.asString()); }
                }
                components.add(new Component(asset, kind,
                      position.getFloat(0), position.getFloat(1), position.getFloat(2),
                      component.getFloat("rotation"), component.getFloat("scale"), component.getInt("connections", 0),
                      stretch == null ? BoardDecoration.Stretch.NONE
                            : new BoardDecoration.Stretch(stretch.getDouble(0), stretch.getDouble(1), stretch.getDouble(2)),
                      new BoardDecoration.Colours(colours), seat == null ? null : new Seat(seat.getInt(0), seat.getInt(1))));
            }
            result.put(entry.name, new Layout(List.copyOf(components)));
        }
        return Map.copyOf(result);
    }
}
