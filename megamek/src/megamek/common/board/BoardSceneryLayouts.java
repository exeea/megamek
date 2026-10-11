/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Plain authored compositions of shared meshes ({@code data/models/board/scenery/layouts.json}); terrain rules and
 * artwork selection remain with their owners. The rows are the legacy conversion ({@link #convert}) and the editor's
 * stamps; the 3D board draws rows only for terrain-owned art (geysers, rubble, fortified, cleared-rubble paths and
 * gravel, elevator plates), which the conversion never takes. Image-free and thread-safe: the rows are read once and
 * only read after.
 */
public final class BoardSceneryLayouts {
    /** The pillar mesh of the legacy pillar art (pillars1-6); on a bridge hex that art is the bridge's Pillars toggle. */
    public static final String PILLAR = "scenery/parks/pillar";
    /** A hex in the rows' model px. */
    private static final float TILE_WIDTH = HexTile.WIDTH, TILE_HEIGHT = HexTile.HEIGHT;
    private static final Map<String, Layout> LAYOUTS = read();

    private BoardSceneryLayouts() { }

    /**
     * A parked car's place: slot {@code slot} ({@link BoardDecalArt.Footprint#slots}) of the layout's {@code plate}-th
     * decal row.
     */
    public record Seat(int plate, int slot) { }

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
     * A row (model px and degrees); {@code tree} marks the shared park tree slot, {@code connections} are a route
     * marker's sides (see {@link BoardDecoration#connections()}), {@code stretch} its optional per-axis factors on top of
     * {@code scale} (see {@link BoardDecoration#stretch()}), {@code colours} its mesh's replacement slot colours (see
     * {@link BoardDecoration#colours()}), such as a car's paint, and {@code seat} (or null) where the conversion parks it
     * instead of its legacy spot.
     */
    public record Component(String asset, boolean tree, float x, float y, float z,
          float rotation, float scale, int connections, BoardDecoration.Stretch stretch, BoardDecoration.Colours colours,
          Seat seat) {
        public Component(String asset, boolean tree, float x, float y, float z, float rotation, float scale) {
            this(asset, tree, x, y, z, rotation, scale, 0, BoardDecoration.Stretch.NONE, BoardDecoration.Colours.NONE);
        }

        public Component(String asset, boolean tree, float x, float y, float z, float rotation, float scale,
              int connections, BoardDecoration.Stretch stretch, BoardDecoration.Colours colours) {
            this(asset, tree, x, y, z, rotation, scale, connections, stretch, colours, null);
        }

        /** Paint on the receiver, such as a car park's row of bays, rather than a mesh. */
        public boolean decal() { return asset.startsWith("decal/"); }

        /** A route marker ({@link RailRoute}): its own object, never a group member. */
        public boolean route() { return RailRoute.Kind.of(asset) != null; }
    }

    public record Layout(List<Component> components) {
        /** The rows that are pieces of the artwork; two or more of them form a group. */
        public long pieces() { return components.stream().filter(component -> !component.route()).count(); }

        /** Pillar art (pillars1-6): every row is the pillar mesh. */
        public boolean pillars() { return components.stream().allMatch(component -> component.asset().equals(PILLAR)); }

        /** Paint only: every row is a decal, such as an elevator's level plate. */
        public boolean paint() { return components.stream().allMatch(Component::decal); }

        /**
         * The same rows as native objects on {@code hex}: XY in hex width/height units, ids in row order from
         * {@code ids}. Trees store their bare species; the renderer shows the winter form on snow. A route marker keeps
         * its sides and stays out of {@code group}: a maglev marker holds its support's level ({@link #routePlacement}), a
         * rail lies on the ground (the conversion puts it on a deck, {@link #railPlacement}). A route's pieces ride it
         * ({@link RailRoute#ride}: at the route's placement; a maglev train's ride height is in its meshes, the renderer
         * stands a rail vehicle on the drawn rail top): the layout's own marker of their kind, else the hex's marker of
         * that kind (a stamped train).
         * Other rows, such as parked cars, stand on the hex's support, trees beneath a bridge's deck
         * ({@link BoardSceneryLayouts#receiver}); an artwork with its own route leaves them out
         * where they would find no support: water or an industrial plant, which its fixed-level maglev crosses. A car park's
         * decal rows (the decals with car slots) stand beside its lane's road ({@code road}, as the data places them) or,
         * without one, back to back ({@link Plate#of}); a seated car parks in its slot of its decal row. Other decal rows
         * lie as the data places them.
         */
        public List<BoardDecoration> decorations(Coords coords, Hex hex, Supplier<String> ids, String group, boolean road) {
            List<BoardDecoration> result = new ArrayList<>();
            boolean ownRoute = components.stream().anyMatch(Component::route);
            // Each kind's marker placement: the layout's own marker, else the one already on the hex.
            Map<RailRoute.Kind, BoardDecoration.Placement> routes = new HashMap<>();
            for (RailRoute.Kind kind : RailRoute.Kind.values()) {
                var marker = RailRoute.find(hex, kind);
                if (marker != null) { routes.put(kind, marker.placement()); }
            }
            for (Component row : components) {
                var kind = RailRoute.Kind.of(row.asset());
                if (kind != null) {
                    routes.put(kind, kind == RailRoute.Kind.RAIL ? BoardDecoration.Placement.ground() : routePlacement(hex));
                }
            }
            // Only a car park's rows fold; other paint (a helipad, a level crossing) lies where the data places it.
            List<Plate> plates = components.stream().filter(Component::decal)
                  .map(row -> Plate.of(row, road || BoardDecalArt.footprint(row.asset()).slots().isEmpty())).toList();
            int decals = 0;
            for (Component row : chosen(coords, BoardTreeDistribution.PARK, BoardTreeDistribution.NATURAL)) {
                Plate own = row.decal() ? plates.get(decals++) : null;
                var marker = RailRoute.Kind.of(row.asset());
                var riding = marker != null ? null : RailRoute.rides(row.asset());
                boolean rides = marker != null || riding != null && routes.containsKey(riding);
                var placement = marker != null ? routes.get(marker)
                      : rides ? routes.get(riding) : receiver(hex, !row.tree());
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
                      x / TILE_WIDTH, y / TILE_HEIGHT, rotation,
                      own != null && own.mirror(), row.scale(), placement, 0, row.decal(), 0, 0,
                      row.route() ? null : group, false, row.connections(), row.stretch(), row.colours()));
            }
            return result;
        }

        /**
         * The rows with each broad park tree's species and heading drawn, in row order, from the hex's seed. The
         * conversion draws them, so a converted tree is the species the legacy render showed.
         */
        public List<Component> chosen(Coords coords, List<String> species, boolean natural) {
            var random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L);
            List<Component> result = new ArrayList<>(components.size());
            for (Component row : components) {
                if (!natural || !row.tree()) { result.add(row); continue; }
                String asset = BoardTreeDistribution.species(species, coords, row.x(), row.y(), random.nextLong());
                result.add(new Component(asset, row.tree(), row.x(), row.y(), row.z(), random.nextFloat() * 360, row.scale(),
                      row.connections(), row.stretch(), row.colours(), row.seat()));
            }
            return result;
        }
    }

    public static Layout layout(String asset) { return LAYOUTS.get(asset); }

    /** A composed artwork selection needs no baked per-layout mesh. */
    public static boolean hasLayout(String asset) { return LAYOUTS.containsKey(asset); }

    /**
     * Pillar art on a bridge hex is the bridge's Pillars toggle ({@link HexAppearance#PILLARS}), not objects: the one
     * decode of the conversion. On any other hex it stays plain pillars.
     */
    public static boolean bridgePillars(Hex hex, String key) {
        return hex.containsTerrain(Terrains.BRIDGE) && LAYOUTS.get(key).pillars();
    }

    /** A present piece of a 7-hex set that misses one on the board: the set's emblem on {@code centre}, in {@code group}. */
    private record Piece(String emblem, Coords centre, String group) { }

    /** Where {@code centre} lies from {@code coords}, in hex widths. */
    private static float offsetX(Coords coords, Coords centre) {
        return (HexTile.centerX(centre, TILE_WIDTH) - HexTile.centerX(coords, TILE_WIDTH)) / TILE_WIDTH;
    }

    /** Where {@code centre} lies from {@code coords}, in hex heights. */
    private static float offsetY(Coords coords, Coords centre) {
        return (HexTile.centerY(centre, TILE_HEIGHT) - HexTile.centerY(coords, TILE_HEIGHT)) / TILE_HEIGHT;
    }

    /**
     * The legacy decode's type ({@link BridgeSpan#decode}) for every bridge hex of {@code board} that stores none, decoded
     * once per span, with pillar art read from {@code scenery} (the hexes resolved so far). The conversion calls it before
     * it changes the board.
     */
    private static Map<Coords, HexAppearance> bridgeTypes(Board board, Map<Coords, TilesetScenery.Scenery> scenery) {
        Map<Coords, HexAppearance> types = new HashMap<>();
        Function<Coords, Hex> hexes = board::getHex;
        Predicate<Coords> pillarArt = at -> scenery.getOrDefault(at, TilesetScenery.Scenery.EMPTY).models()
              .stream().anyMatch(key -> bridgePillars(board.getHex(at), key));
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                if (!hex.containsTerrain(Terrains.BRIDGE) || types.containsKey(at)
                      || HexAppearance.bridgeBuilt(hex.getAppearance()) != null) { continue; }
                var span = BridgeSpan.of(hexes, at);
                var type = BridgeSpan.decode(hexes, pillarArt, span) ? HexAppearance.BUILT_BRIDGE : HexAppearance.NATURAL_BRIDGE;
                span.forEach(member -> types.put(member, type));
            }
        }
        return types;
    }

    /**
     * Gives each of {@code hexes} the designs that draw its legacy art tokens ({@link #designed}). The tokens stay, for
     * the 2D board.
     */
    private static void design(Board board, List<Coords> hexes) {
        Map<BoardLocation, Hex> designed = new HashMap<>();
        for (Coords coords : hexes) {
            Hex hex = board.getHex(coords);
            if (tokens(hex).isEmpty()) { continue; }
            var appearance = designed(hex.getAppearance(), designs(hex, layers(hex)));
            if (appearance.equals(hex.getAppearance())) { continue; }
            Hex next = hex.duplicate();
            next.setAppearance(appearance);
            designed.put(BoardLocation.of(coords, board.getBoardId()), next);
        }
        if (!designed.isEmpty()) { board.setHexes(designed); }
    }

    /**
     * {@code appearance} with each design of {@code designs} ({@link #designs}) whose owner has none: an owner's design,
     * such as a user's pick, wins over its token. A road design keeps the road's finish.
     */
    private static Map<String, HexAppearance> designed(Map<String, HexAppearance> appearance,
          Map<Integer, Map<String, HexAppearance>> designs) {
        Map<String, HexAppearance> result = new HashMap<>(appearance);
        for (var owners : designs.values()) {
            owners.forEach((owner, design) -> {
                HexAppearance current = result.get(owner);
                if (current == null) { result.put(owner, design); }
                else if (current.variant() == null && current.asset() == null) {
                    result.put(owner, new HexAppearance(design.variant(), null, current.material(), design.strength()));
                }
            });
        }
        return result;
    }

    /** Writes each of {@code types} on its hex, except where the hex has a type by now (pillar art made it PILLARS). */
    private static void typeBridges(Board board, Map<Coords, HexAppearance> types) {
        Map<BoardLocation, Hex> typed = new HashMap<>();
        types.forEach((at, type) -> {
            Hex hex = board.getHex(at);
            if (HexAppearance.bridgeBuilt(hex.getAppearance()) != null) { return; }
            Hex next = replacement(hex);
            Map<String, HexAppearance> appearance = new HashMap<>(next.getAppearance());
            appearance.put("bridge", type);
            next.setAppearance(appearance);
            typed.put(BoardLocation.of(at, board.getBoardId()), next);
        });
        if (!typed.isEmpty()) { board.setHexes(typed); }
    }

    /** The hex's appearance with its bridge's Pillars toggle on where pillar art turns it on: the conversion's. */
    private static Map<String, HexAppearance> appearance(Hex hex, TilesetScenery.Scenery scenery) {
        if (scenery.models().stream().noneMatch(key -> bridgePillars(hex, key))) { return hex.getAppearance(); }
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        appearance.put("bridge", HexAppearance.PILLARS);
        return appearance;
    }

    /**
     * The one legacy conversion, wherever a board can reach a 3D view. A board that is not native (not converted yet) has
     * every decodable scenery token decoded ({@link #convert(Board, Collection)} over all its hexes) and its one-sided
     * bridge exits made mutual ({@link #reciprocalExits}); every untyped bridge of any board gets its type; then the
     * board is native. So converting a converted board only types bridges that lack a type. Returns the issues of the
     * decode.
     */
    public static List<BoardIssue> convert(Board board) {
        List<Coords> hexes = new ArrayList<>();
        if (!board.isNativeFormat()) {
            reciprocalExits(board);
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) { hexes.add(new Coords(x, y)); }
            }
        }
        return convert(board, hexes);
    }

    /**
     * Writes the missing exit of every one-sided bridge exit ({@link BridgeSpan#oneSided}: a deck's exit points into a
     * neighbouring deck at the same level that does not point back), so the game rules of every player, the 2D board's
     * included, see the bridge the span unit joins. Decks at different levels keep their exits.
     */
    private static void reciprocalExits(Board board) {
        Map<BoardLocation, Hex> fixed = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                if (!hex.containsTerrain(Terrains.BRIDGE)) { continue; }
                Terrain bridge = hex.getTerrain(Terrains.BRIDGE);
                int exits = bridge.getExits();
                for (int d = 0; d < 6; d++) {
                    if (BridgeSpan.oneSided(hex, board.getHex(at.translated(d)), d)) { exits |= 1 << d; }
                }
                if (exits == bridge.getExits()) { continue; }
                Hex next = replacement(hex);
                next.addTerrain(new Terrain(Terrains.BRIDGE, bridge.getLevel(), true, exits));
                fixed.put(BoardLocation.of(at, board.getBoardId()), next);
            }
        }
        if (!fixed.isEmpty()) { board.setHexes(fixed); }
    }

    /**
     * Decodes the legacy scenery tokens of {@code hexes}, whether or not the board is native (the editor converts the hexes
     * whose token an edit changed), then types every untyped bridge and marks the board native. Each decodable token
     * ({@link #imported}) becomes the objects its artwork shows, one group per artwork that has several, with ids
     * {@code L<x>_<y>_<n>}; the token stays on the hex for the 2D board, which keeps drawing its sprite. Rule terrain is
     * kept, except that a car park on plain ground gets its lane as a road ({@code laneRoad}), which runs between its rows
     * of bays and may write a neighbour's road exits; where no road is drawn the rows stand back to back
     * ({@link #roadside}). Artwork resolves with the 3D board's tileset ({@link TilesetScenery#resolve}). A legacy decal
     * ({@link BoardDecalArt#legacy}) becomes its decal: a one-hex variant at its scale and turn, in its paint colour, and
     * the pieces of a 7-hex emblem set ONE emblem on the set's centre hex, spanning its neighbours; a set missing a piece
     * on the board paints exactly its pieces (each the emblem clipped to its hex, one group), and one cut by the board
     * edge gets the full emblem and an issue. Legacy rail art becomes a rail route marker with the art's sides, on the
     * ground or its bridge's deck ({@link #railPlacement}); art without sides joins the rails beside it ({@link #railJoins})
     * or places nothing. On a legacy (not native) board only, converted maglevs a level up or beside a row of pillar art
     * stand on Pillars ({@link #maglevPillars}) and a rail bridge carries its rail across its span ({@link #carryRails}). A token
     * that a hex design draws gets that design ({@link #design}). Every
     * bridge hex gets its type, decoded on the board as given ({@link #bridgeTypes}). Returns those issues. Thread-safe:
     * it changes only {@code board}.
     */
    public static List<BoardIssue> convert(Board board, Collection<Coords> hexes) {
        boolean nativeBoard = board.isNativeFormat();
        List<Coords> scanned = hexes.stream().filter(board::contains).distinct()
              .sorted(Comparator.comparingInt(Coords::getY).thenComparingInt(Coords::getX)).toList();
        // Resolve every hex first: a set piece names its centre, which may come later in the scan or have no piece.
        Map<Coords, TilesetScenery.Scenery> scenery = new LinkedHashMap<>();
        for (Coords coords : scanned) {
            Hex hex = board.getHex(coords);
            // Only these tokens can select importable artwork; most hexes need no tileset resolution.
            if (hex.containsAnyTerrainOf(Terrains.FLUFF, Terrains.ROAD_FLUFF) || hex.terrainLevel(Terrains.ROAD) == 2) {
                scenery.put(coords, TilesetScenery.resolve(hex, nativeBoard).scenery());
            }
        }
        // Every bridge's type, decoded on the board as loaded: the lane roads below must not retype a span. Only fluff
        // can be pillar art, and it counts only on an untyped span (a stored type decides the span).
        Map<Coords, TilesetScenery.Scenery> pillarArt = new HashMap<>(scenery);
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                if (hex.containsAllTerrainsOf(Terrains.BRIDGE, Terrains.FLUFF) && !pillarArt.containsKey(at)
                      && HexAppearance.bridgeBuilt(hex.getAppearance()) == null) {
                    pillarArt.put(at, TilesetScenery.resolve(hex, nativeBoard).scenery());
                }
            }
        }
        Map<Coords, HexAppearance> bridges = bridgeTypes(board, pillarArt);
        // The car parks that get their lane as a road (laneRoad), all known before the conversion changes a hex.
        Map<Coords, Integer> lanes = new HashMap<>();
        scenery.forEach((coords, artwork) -> {
            Hex hex = board.getHex(coords);
            if (artwork.cosmeticRoadExits() != 0 && !hex.containsTerrain(Terrains.ROAD) && plainGround(hex)
                  && !HexTile.liquid(hex)) {
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
        // A set missing a piece on the board paints exactly its pieces: each piece hex the set's emblem on the set's
        // centre, clipped to its own hex, one group per set. A piece beyond the board edge counts as there (a cut map or a
        // sheet seam, whose halves joinSheets merges), so a set cut by the edge keeps its one full emblem.
        Map<Coords, List<Piece>> pieces = new HashMap<>();
        sets.forEach((owner, emblems) -> emblems.entrySet().removeIf(set -> {
            Coords centre = centreOf.getOrDefault(owner, owner);
            List<Coords> present = new ArrayList<>();
            boolean missing = false;
            for (int direction = -1; direction < 6; direction++) {
                Coords member = direction < 0 ? centre : centre.translated(direction);
                if (set.getValue().contains(direction)) { present.add(member); }
                else { missing |= board.contains(member); }
            }
            if (missing) {
                String emblem = set.getKey(), group = "L" + owner.getX() + "_" + owner.getY() + "_set_"
                      + emblem.substring(emblem.lastIndexOf('/') + 1);
                present.forEach(at -> pieces.computeIfAbsent(at, key -> new ArrayList<>()).add(new Piece(emblem, centre, group)));
            }
            return missing;
        }));
        List<BoardIssue> issues = new ArrayList<>();
        // The maglev markers this conversion places, and the rows of pillar art along legacy maglev art (maglevPillars).
        Map<Coords, List<String>> maglevs = new HashMap<>();
        Set<Coords> pillarRows = nativeBoard ? Set.of() : pillarRows(board, scenery);
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords coords = new Coords(x, y);
                var artwork = scenery.getOrDefault(coords, TilesetScenery.Scenery.EMPTY);
                if (artwork == TilesetScenery.Scenery.EMPTY && !sets.containsKey(coords)) { continue; }
                Hex hex = board.getHex(coords);
                List<BoardDecoration> added = new ArrayList<>();
                // Whether a decodable token was taken, even when its artwork leaves no object (pillar art on a bridge).
                boolean decoded = false;
                String prefix = "L" + x + "_" + y + "_";
                int[] next = { 0 };
                int keys = 0;
                boolean overWoods = false, pillared = false;
                // A car park's rows stand beside the road along its lane (its own, or the lane the conversion gives it),
                // else back to back: no lane (2b/3b), off the ground, on a liquid, or a road level that is not drawn.
                boolean road = lanes.containsKey(coords) || artwork.cosmeticRoadExits() != 0 && roadside(hex);
                for (var model : artwork.sources()) {
                    if (!imported(model.terrains(), hex)) { continue; }
                    decoded |= model.terrains().contains(Terrains.FLUFF)
                          || model.terrains().contains(Terrains.ROAD_FLUFF) && hex.terrainLevel(Terrains.ROAD_FLUFF) == 3;
                    // Pillar art on a bridge turns on the bridge's Pillars toggle (below) and places no objects.
                    if (bridgePillars(hex, model.key())) { pillared = true; continue; }
                    // A row of pillar art along a legacy maglev is that maglev's Pillars (maglevPillars), no objects either.
                    if (pillarRows.contains(coords) && LAYOUTS.get(model.key()).pillars()) { continue; }
                    // Every model key has rows: TilesetScenery counts only layouts.json keys as models.
                    Layout rows = LAYOUTS.get(model.key());
                    String group = rows.pieces() > 1 ? prefix + "g" + keys : null;
                    keys++;
                    added.addAll(rows.decorations(coords, hex, () -> prefix + next[0]++, group, road));
                    // Artwork drawn over woods replaced the woods' trees in the legacy render (seaport yards).
                    overWoods |= model.terrains().contains(Terrains.WOODS);
                }
                for (int i = added.size() - 1; i >= 0; i--) {
                    var rail = added.get(i);
                    if (RailRoute.kind(rail) != RailRoute.Kind.RAIL) { continue; }
                    // Art without sides (rail00) joins the rails beside it, and places nothing beside none.
                    int sides = rail.connections() != 0 ? rail.connections() : railJoins(board, coords, scenery);
                    if (sides == 0) { added.remove(i); continue; }
                    added.set(i, rail.withConnections(sides).transform(rail.x(), rail.y(), rail.rotation(), rail.mirror(),
                          rail.scale(), railPlacement(board, coords, sides, scenery)));
                }
                for (var decal : artwork.decals()) {
                    if (!imported(decal.terrains(), hex)) { continue; }
                    // Every legacy decal token is decoded; a set piece's emblem is placed on its centre below.
                    decoded = true;
                    var legacy = BoardDecalArt.legacy(decal.key());
                    if (legacy.set()) { continue; }
                    var paint = new BoardDecoration(prefix + next[0]++, "decal", legacy.decal(), null, 0, 0, legacy.rotation(),
                          false, legacy.scale(), receiver(hex, true), 0, true);
                    added.add(legacy.colour() == null ? paint : paint.withColours(BoardDecoration.Colours.of(legacy.colour())));
                }
                for (var set : sets.getOrDefault(coords, Map.of()).entrySet()) {
                    Coords centre = centreOf.getOrDefault(coords, coords);
                    String id = prefix + next[0]++;
                    // One emblem over the centre and its six neighbours, unclipped, on the owner's support.
                    added.add(new BoardDecoration(id, "decal", set.getKey(), null, offsetX(coords, centre),
                          offsetY(coords, centre), 0, false, 1, receiver(hex, true), 0, false));
                    if (set.getValue().size() < 7) {
                        issues.add(new BoardIssue(coords, id, "Emblem set with " + set.getValue().size()
                              + " of 7 pieces: the full emblem is placed"));
                    }
                }
                for (Piece piece : pieces.getOrDefault(coords, List.of())) {
                    added.add(new BoardDecoration(prefix + next[0]++, "decal", piece.emblem(), null,
                          offsetX(coords, piece.centre()), offsetY(coords, piece.centre()), 0, false, 1, receiver(hex, true),
                          0, true, 0, 0, piece.group()));
                }
                if (added.isEmpty() && !decoded) { continue; }
                added.stream().filter(object -> RailRoute.kind(object) == RailRoute.Kind.MAGLEV)
                      .forEach(marker -> maglevs.computeIfAbsent(coords, key -> new ArrayList<>()).add(marker.id()));
                // The decoded tokens stay: the 2D board keeps drawing their sprites; the 3D board draws the objects.
                Hex result = replacement(hex);
                List<BoardDecoration> objects = new ArrayList<>(hex.getDecorations());
                objects.addAll(added);
                result.setDecorations(objects);
                if (overWoods && !result.getAppearance().containsKey("vegetation")) {
                    // The woods stay for the rules; their design draws no trees, as the legacy art showed none.
                    Map<String, HexAppearance> appearance = new HashMap<>(result.getAppearance());
                    appearance.put("vegetation", new HexAppearance(BoardTreeDistribution.NO_TREES, null, null, null));
                    result.setAppearance(appearance);
                }
                if (pillared) { result.setAppearance(appearance(result, artwork)); }
                laneRoad(board, coords, result, lanes);
                board.setHex(coords, result);
            }
        }
        // Fix-ups of legacy art: a legacy board's conversion only, never a native board's typed tokens (the editor's).
        if (!nativeBoard) {
            maglevPillars(board, maglevs, pillarRows);
            carryRails(board, scanned);
        }
        design(board, scanned);
        typeBridges(board, bridges);
        board.setNativeFormat(true);
        return List.copyOf(issues);
    }

    /**
     * The hexes whose pillar art is a row of posts along a legacy maglev line (Karkand 1208-1218): pillar art, not on a
     * bridge, beside legacy maglev art whose line runs past it (none of its sides points at the pillar hex, so not at the
     * line's end), with a neighbour of the same kind, so a row of two or more; a lone pillar hex, or pillar art where a
     * line ends (Ishiyama's pillar field), stays plain pillars. By the scan's {@code scenery}.
     */
    private static Set<Coords> pillarRows(Board board, Map<Coords, TilesetScenery.Scenery> scenery) {
        // The sides of a hex's legacy maglev art, 0 without one.
        java.util.function.ToIntFunction<Coords> maglev = at -> scenery.getOrDefault(at, TilesetScenery.Scenery.EMPTY)
              .models().stream().flatMap(key -> LAYOUTS.get(key).components().stream())
              .filter(row -> RailRoute.Kind.of(row.asset()) == RailRoute.Kind.MAGLEV)
              .mapToInt(Component::connections).reduce(0, (a, b) -> a | b);
        Set<Coords> beside = new HashSet<>();
        scenery.forEach((at, artwork) -> {
            if (artwork.models().stream().noneMatch(key -> LAYOUTS.get(key).pillars() && !bridgePillars(board.getHex(at), key))) {
                return;
            }
            for (int direction = 0; direction < 6; direction++) {
                int sides = maglev.applyAsInt(at.translated(direction));
                if (sides != 0 && (sides & RailRoute.side((direction + 3) % 6)) == 0) { beside.add(at); return; }
            }
        });
        Set<Coords> rows = new HashSet<>();
        for (Coords at : beside) {
            for (int direction = 0; direction < 6; direction++) {
                if (beside.contains(at.translated(direction))) { rows.add(at); break; }
            }
        }
        return rows;
    }

    /**
     * Converted maglevs on Pillars (batch-10 answer 7), set as the editor's toggle sets them, on a whole connected run
     * ({@link RailRoute#run}): a run with a converted marker a level or more above its hex's ground, and a run beside
     * a row of legacy pillar art ({@link #pillarRows}, posts along the line as on Karkand). The pillar art is read as the
     * posts that carry the line, so such a run's converted markers that follow the ground stand a level above it, with
     * the maglev pieces that ride them; without that the piers would have no room. {@code maglevs} are the converted
     * markers' ids by hex.
     */
    private static void maglevPillars(Board board, Map<Coords, List<String>> maglevs, Set<Coords> pillarRows) {
        Map<Coords, Hex> changed = new HashMap<>();
        Function<Coords, Hex> hexes = at -> changed.containsKey(at) ? changed.get(at) : board.contains(at) ? board.getHex(at) : null;
        Predicate<RailRoute.Member> converted = member -> maglevs.getOrDefault(member.at(), List.of()).contains(member.marker().id());
        Set<String> done = new HashSet<>();
        maglevs.forEach((at, ids) -> ids.forEach(id -> {
            if (done.contains(at + id)) { return; }
            var marker = hexes.apply(at).getDecorations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
            var run = RailRoute.run(hexes, at, marker);
            run.forEach(member -> done.add(member.at() + member.marker().id()));
            boolean posts = run.stream().anyMatch(member -> {
                for (int d = 0; d < 6; d++) {
                    if (pillarRows.contains(member.at().translated(d))) { return true; }
                }
                return false;
            });
            boolean elevated = run.stream().anyMatch(member -> converted.test(member)
                  && member.marker().placement().level(hexes.apply(member.at())) - hexes.apply(member.at()).getLevel() >= 1);
            if (!posts && !elevated) { return; }
            var raised = BoardDecoration.Placement.on("ground", 1);
            for (var member : run) {
                Hex hex = hexes.apply(member.at()).duplicate();
                var placement = member.marker().placement();
                boolean lift = posts && converted.test(member) && placement.equals(BoardDecoration.Placement.ground());
                hex.setDecorations(hex.getDecorations().stream().map(object -> {
                    if (object.id().equals(member.marker().id())) {
                        var pillared = object.withPillars(true);
                        return lift ? pillared.transform(object.x(), object.y(), object.rotation(), object.mirror(),
                              object.scale(), raised) : pillared;
                    }
                    // The pieces that ride the lifted marker, at its placement, rise with it.
                    return lift && RailRoute.rides(object.asset()) == RailRoute.Kind.MAGLEV && object.placement().equals(placement)
                          ? object.transform(object.x(), object.y(), object.rotation(), object.mirror(), object.scale(), raised)
                          : object;
                }).toList());
                changed.put(member.at(), hex);
            }
        }));
        Map<BoardLocation, Hex> result = new HashMap<>();
        changed.forEach((at, hex) -> result.put(BoardLocation.of(at, board.getBoardId()), hex));
        if (!result.isEmpty()) { board.setHexes(result); }
    }

    /**
     * A rail bridge carries its rail across the whole span (batch-10 answer 3): where the rail enters a bridge span
     * ({@link BridgeSpan#of}) along its deck at both of its two ends, every span hex rides that rail on its deck along all
     * its bridge exits, also where the legacy art left the middle hexes without rail (45x45 Desert Mesa Oasis). An end
     * enters along the deck where its hex's rail leads out that way on the deck, or, without a rail there, the rail of
     * the hex beyond leads onto it within a level of the deck. A span with a rail beneath its deck keeps its rails as
     * they are. Only spans with a hex in {@code hexes}, the hexes this conversion decodes.
     */
    private static void carryRails(Board board, List<Coords> hexes) {
        Set<Coords> seen = new HashSet<>();
        Map<BoardLocation, Hex> carried = new HashMap<>();
        for (Coords start : hexes) {
            if (!board.getHex(start).containsTerrain(Terrains.BRIDGE) || seen.contains(start)) { continue; }
            List<Coords> span = BridgeSpan.of(board::getHex, start);
            seen.addAll(span);
            int ends = 0, entering = 0;
            boolean beneath = false;
            for (Coords member : span) {
                Hex hex = board.getHex(member);
                var rail = RailRoute.find(hex, RailRoute.Kind.RAIL);
                beneath |= rail != null && !RailRoute.onDeck(rail);
                int exits = RailRoute.bridgeExits(hex);
                for (int d = 0; d < 6; d++) {
                    Coords next = member.translated(d);
                    if ((exits & RailRoute.side(d)) == 0 || span.contains(next)) { continue; }
                    ends++;
                    var bank = board.contains(next) ? RailRoute.find(board.getHex(next), RailRoute.Kind.RAIL) : null;
                    boolean enters = rail != null ? (rail.connections() & RailRoute.side(d)) != 0
                          : bank != null && (bank.connections() & RailRoute.side((d + 3) % 6)) != 0
                                && Math.abs(board.getHex(next).getLevel() - BridgeSpan.deck(hex)) <= 1;
                    if (enters) { entering++; }
                }
            }
            if (ends != 2 || entering != 2 || beneath) { continue; }
            for (Coords member : span) {
                Hex hex = board.getHex(member).duplicate();
                int exits = RailRoute.bridgeExits(hex) & 63;
                var rail = RailRoute.find(hex, RailRoute.Kind.RAIL);
                List<BoardDecoration> objects = new ArrayList<>(hex.getDecorations());
                if (rail == null) {
                    objects.add(new BoardDecoration("L" + member.getX() + "_" + member.getY() + "_rail", "prop",
                          RailRoute.Kind.RAIL.asset, null, 0, 0, 0, false, 1, BoardDecoration.Placement.on("bridge", 0), 0,
                          false).withConnections(exits));
                } else if ((rail.connections() & exits) != exits) {
                    objects.set(objects.indexOf(rail), rail.withConnections(rail.connections() | exits));
                } else { continue; }
                hex.setDecorations(objects);
                carried.put(BoardLocation.of(member, board.getBoardId()), hex);
            }
        }
        if (!carried.isEmpty()) { board.setHexes(carried); }
    }

    /**
     * The one pass after sheets become one board: {@code BoardUtilities.combine} (every game board assembly) and an old
     * save's boards after their conversion ({@code Game.initializeAfterLoad}). Sheets are converted one by one, before
     * they are flipped and combined, so a bridge or an emblem that crosses a seam was decoded in two halves:
     * <ul>
     *     <li>a bridge span ({@link BridgeSpan#of}) with a built hex makes its other hexes built, since the decode is an
     *     OR over a span's hexes ({@link BridgeSpan#decode}); a hex with piers keeps them, one without gets none;</li>
     *     <li>set emblems that coincide (the same emblem at the same world centre, turn, mirror and scale: each half of a
     *     set cut by a seam placed the full emblem on the set's centre) become one, the first in scan order.</li>
     * </ul>
     * Halves on a flipped sheet do not coincide and stay as they are.
     */
    public static void joinSheets(Board board) {
        Function<Coords, Hex> hexes = board::getHex;
        Set<Coords> spans = new HashSet<>();
        Map<BoardLocation, Hex> built = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                if (!board.getHex(at).containsTerrain(Terrains.BRIDGE) || spans.contains(at)) { continue; }
                List<Coords> span = BridgeSpan.of(hexes, at);
                spans.addAll(span);
                if (!Boolean.TRUE.equals(BridgeSpan.type(hexes, span))) { continue; }
                for (Coords member : span) {
                    Hex hex = board.getHex(member);
                    if (Boolean.TRUE.equals(HexAppearance.bridgeBuilt(hex.getAppearance()))) { continue; }
                    Hex next = replacement(hex);
                    Map<String, HexAppearance> appearance = new HashMap<>(next.getAppearance());
                    appearance.put("bridge", HexAppearance.BUILT_BRIDGE);
                    next.setAppearance(appearance);
                    built.put(BoardLocation.of(member, board.getBoardId()), next);
                }
            }
        }
        if (!built.isEmpty()) { board.setHexes(built); }
        // Each emblem kept so far: its object and its world centre, in hex widths and heights.
        List<Map.Entry<BoardDecoration, double[]>> emblems = new ArrayList<>();
        Map<BoardLocation, Hex> merged = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                List<BoardDecoration> kept = hex.getDecorations().stream().filter(object -> {
                    if (!object.kind().equals("decal") || object.clipToHex() || !BoardDecalArt.setEmblem(object.asset())) {
                        return true;
                    }
                    double[] centre = { HexTile.centerX(at, 1) + object.x(), HexTile.centerY(at, 1) + object.y() };
                    boolean twin = emblems.stream().anyMatch(other -> other.getKey().asset().equals(object.asset())
                          && other.getKey().rotation() == object.rotation() && other.getKey().scale() == object.scale()
                          && other.getKey().mirror() == object.mirror() && Math.abs(other.getValue()[0] - centre[0]) < 1e-3
                          && Math.abs(other.getValue()[1] - centre[1]) < 1e-3);
                    if (!twin) { emblems.add(Map.entry(object, centre)); }
                    return !twin;
                }).toList();
                if (kept.size() < hex.getDecorations().size()) {
                    Hex next = replacement(hex);
                    next.setDecorations(kept);
                    merged.put(BoardLocation.of(at, board.getBoardId()), next);
                }
            }
        }
        if (!merged.isEmpty()) { board.setHexes(merged); }
    }

    /**
     * Which layers become objects ({@link Taken#OBJECTS}): decodable FLUFF (not server rubble), roadside-tree
     * ROAD_FLUFF 3, and the legacy road-with-trees art of ROAD level 2. Everything else is terrain-owned or kept.
     */
    public static boolean imported(Set<Integer> terrains, Hex hex) {
        if (terrains.contains(Terrains.FLUFF)) {
            int level = hex.terrainLevel(Terrains.FLUFF);
            return level < SERVER;
        }
        if (terrains.contains(Terrains.ROAD_FLUFF)) { return hex.terrainLevel(Terrains.ROAD_FLUFF) == 3; }
        return terrains.contains(Terrains.ROAD) && hex.terrainLevel(Terrains.ROAD) == 2;
    }

    /** What the conversion does with a legacy art token it takes ({@link #takenTokens}). */
    public enum Taken {
        /** Its artwork became objects ({@link #imported}): rows, a legacy decal, pillar art's toggle. */
        OBJECTS,
        /** A hex design draws it ({@link #designs}). */
        DESIGN,
        /** It draws nothing: the tileset's transparent wildcard, or building art no design stands for. */
        DROP
    }

    /** Terrains that are legacy art and nothing else: the tokens the conversion takes ({@link #takenTokens}). */
    private static final int[] ART_TERRAINS = { Terrains.FLUFF, Terrains.GROUND_FLUFF, Terrains.ROAD_FLUFF,
          Terrains.WATER_FLUFF, Terrains.BLDG_FLUFF };
    /** FLUFF and GROUND_FLUFF from here up are game state written in play (cleared rubble), never legacy art. */
    private static final int SERVER = 2000;

    /** The hex's legacy art tokens, in {@link #ART_TERRAINS} order: every art terrain but a server value. */
    private static List<Integer> tokens(Hex hex) {
        List<Integer> result = new ArrayList<>(2);
        for (int type : ART_TERRAINS) {
            if (hex.containsTerrain(type) && (type == Terrains.ROAD_FLUFF || type == Terrains.WATER_FLUFF
                  || type == Terrains.BLDG_FLUFF || hex.terrainLevel(type) < SERVER)) { result.add(type); }
        }
        return result;
    }

    /** The hex's scenery layers, as a converted board resolves them, when it holds a scenery art token; else none. */
    private static List<TilesetScenery.Layer> layers(Hex hex) {
        if (!hex.containsAnyTerrainOf(Terrains.FLUFF, Terrains.GROUND_FLUFF, Terrains.ROAD_FLUFF, Terrains.WATER_FLUFF)) {
            return List.of();
        }
        // A converted board is native: road level 2 is the Alley finish, and it is never a token.
        return TilesetScenery.resolve(hex, true).layers();
    }

    /**
     * The one statement of what the conversion takes: each legacy art token of {@code hex} (FLUFF and GROUND_FLUFF
     * below the server values, ROAD_FLUFF, WATER_FLUFF, BLDG_FLUFF) that it takes, by terrain type, with what stands for
     * it, decided in this order:
     * <ol>
     *     <li>{@link Taken#OBJECTS}: a layer that matched it is taken ({@link TilesetScenery.Layer#taken}, the predicate
     *     of {@link #convert} and of the 3D board, which skips that layer): FLUFF, and ROAD_FLUFF level 3;</li>
     *     <li>{@link Taken#DESIGN}: a hex design draws exactly its art ({@link #designs}); {@link #convert} writes it
     *     unless the owner has a design by then, which wins, the token still being taken;</li>
     *     <li>{@link Taken#DROP}: it draws nothing, every layer that matched it being the transparent wildcard
     *     ({@link TilesetScenery.Layer#blank}); or it is building art that no design stands for.</li>
     * </ol>
     * Any other token is kept: on the shipped boards none.
     */
    public static Map<Integer, Taken> takenTokens(Hex hex) {
        if (tokens(hex).isEmpty()) { return Map.of(); }
        List<TilesetScenery.Layer> layers = layers(hex);
        return taken(hex, layers, designs(hex, layers));
    }

    /** {@link #takenTokens}, from the hex's {@link #layers} and {@link #designs}. */
    private static Map<Integer, Taken> taken(Hex hex, List<TilesetScenery.Layer> layers,
          Map<Integer, Map<String, HexAppearance>> designs) {
        Map<Integer, Taken> result = new TreeMap<>();
        for (var layer : layers) {
            if (!layer.taken()) { continue; }
            if (layer.terrains().contains(Terrains.FLUFF)) { result.put(Terrains.FLUFF, Taken.OBJECTS); }
            if (layer.terrains().contains(Terrains.ROAD_FLUFF) && hex.terrainLevel(Terrains.ROAD_FLUFF) == 3) {
                result.put(Terrains.ROAD_FLUFF, Taken.OBJECTS);
            }
        }
        designs.keySet().forEach(token -> result.putIfAbsent(token, Taken.DESIGN));
        for (int token : tokens(hex)) {
            List<TilesetScenery.Layer> drawn = layers.stream().filter(layer -> layer.terrains().contains(token)).toList();
            if (token == Terrains.BLDG_FLUFF || !drawn.isEmpty() && drawn.stream().allMatch(TilesetScenery.Layer::blank)) {
                result.putIfAbsent(token, Taken.DROP);
            }
        }
        return result;
    }

    /**
     * The hex designs that draw what the hex's legacy art tokens draw, by token and design owner: the blueprint variant
     * whose selector is the token ({@link BoardEditorBlueprint#reproducing}: transitions, kerbs and road pieces,
     * orchards, rough, building art), and for building art beside which the building's structure model was chosen
     * ({@link TilesetScenery.Kind#SKIPPED}), that model's design. Quay art maps to its quay design (a ground design), which
     * only the 2D board of a {@code .board2} draws: the 3D views derive the fitted quay wall. A token absent from the
     * result has no design. {@code layers} are the hex's ({@link #layers}).
     */
    private static Map<Integer, Map<String, HexAppearance>> designs(Hex hex, List<TilesetScenery.Layer> layers) {
        var blueprint = BoardEditorBlueprint.get();
        Map<Integer, Map<String, HexAppearance>> result = new LinkedHashMap<>();
        for (int token : tokens(hex)) {
            Map<String, HexAppearance> owners = new LinkedHashMap<>();
            boolean quay = false;
            for (var layer : layers) {
                if (token != Terrains.FLUFF || !layer.terrains().contains(Terrains.FLUFF)) { continue; }
                quay |= layer.kind() == TilesetScenery.Kind.QUAY;
                if (layer.kind() == TilesetScenery.Kind.SKIPPED && layer.terrains().contains(Terrains.BUILDING)) {
                    String model = TilesetScenery.structureModels(hex).get(Terrains.BUILDING);
                    var variant = model == null ? null : blueprint.variant(model);
                    if (variant != null && variant.owner().equals("building")) {
                        owners.put("building", new HexAppearance(variant.id(), null, null, null));
                    }
                }
            }
            HexAppearance design = blueprint.reproducing(hex, hex.getTerrain(token));
            if (design != null) { owners.putIfAbsent(blueprint.variant(design.variant()).owner(), design); }
            if (quay || !owners.isEmpty()) { result.put(token, owners); }
        }
        return result;
    }

    /**
     * {@code hex} as a {@code .board2} holds it: without the legacy art tokens the conversion took
     * ({@link #takenTokens}), whose objects and designs stand for them; it adds nothing. Returns {@code hex} itself when
     * it holds no taken token, else a changed copy; {@code hex} is never changed. Games and previews of a loaded
     * {@code .board} keep the tokens in memory, so their 2D views draw the old sprites; the {@code .board2} writer
     * ({@link BoardFile#write}) and the 3D editor's document drop them, so a {@code .board2} never holds one; and every 3D
     * view draws each hex this way (the GPU board's one capture), so the game and the previews draw exactly what the
     * editor and the saved {@code .board2} hold. A token a gamemaster types in a game gets its design on the edit
     * ({@link #designTypedTokens}).
     */
    public static Hex withoutTakenTokens(Hex hex) {
        if (tokens(hex).isEmpty()) { return hex; }
        List<TilesetScenery.Layer> layers = layers(hex);
        var taken = taken(hex, layers, designs(hex, layers));
        if (taken.isEmpty()) { return hex; }
        Hex result = hex.duplicate();
        taken.keySet().forEach(result::removeTerrain);
        return result;
    }

    /**
     * The gamemaster edit path's translation of legacy art tokens typed in a game (kept for backward compatibility):
     * {@code hex}, which the edit changed from {@code before}, loses the design each token stood for that the edit
     * removed or changed (unless another design was chosen since), and gets the design each of its tokens stands for
     * where its owner has none, as the conversion gives it ({@link #designs}, {@link #designed}). The tokens stay, for the
     * game's 2D board; the 3D board draws the designs. Building art has its own helper ({@link #setBuildingArt}).
     */
    public static void designTypedTokens(Hex before, Hex hex) {
        if (tokens(before).isEmpty() && tokens(hex).isEmpty()) { return; }
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        designs(before, layers(before)).forEach((token, owners) -> {
            if (String.valueOf(before.getTerrain(token)).equals(String.valueOf(hex.getTerrain(token)))) { return; }
            owners.forEach((owner, design) -> {
                HexAppearance current = appearance.get(owner);
                // A road design keeps the road's finish (designed): the design is its variant.
                if (current == null || !java.util.Objects.equals(current.variant(), design.variant())) { return; }
                if (current.material() == null) { appearance.remove(owner); }
                else { appearance.put(owner, new HexAppearance(null, null, current.material(), null)); }
            });
        });
        hex.setAppearance(designed(appearance, designs(hex, layers(hex))));
    }

    /**
     * The building's legacy art level (its {@code bldg_fluff} value, 0 for its ordinary artwork): the token a game of a
     * loaded {@code .board} keeps, else the level the hex's building design selects ({@code building/legacy-art-N}).
     */
    public static int buildingArt(Hex hex) {
        if (hex.containsTerrain(Terrains.BLDG_FLUFF)) { return Math.max(0, hex.terrainLevel(Terrains.BLDG_FLUFF)); }
        HexAppearance design = hex.getAppearance().get("building");
        var variant = design == null || design.variant() == null ? null : BoardEditorBlueprint.get().variant(design.variant());
        if (variant == null || variant.selectors().isBlank() || variant.selectors().contains(";")) { return 0; }
        Terrain selector = new Terrain(variant.selectors());
        return selector.getType() == Terrains.BLDG_FLUFF ? selector.getLevel() : 0;
    }

    /**
     * Sets the building's legacy art level ({@link #buildingArt}) on {@code hex}, which holds its building: the token,
     * which a game's 2D board draws, and the design that stands for it ({@link #designs}), which the 3D board draws and a
     * {@code .board2} keeps; level 0 removes both, giving the building its ordinary artwork.
     */
    public static void setBuildingArt(Hex hex, int level) {
        hex.removeTerrain(Terrains.BLDG_FLUFF);
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        appearance.remove("building");
        if (level > 0) {
            Terrain token = new Terrain(Terrains.BLDG_FLUFF, level);
            hex.addTerrain(token);
            HexAppearance design = BoardEditorBlueprint.get().reproducing(hex, token);
            if (design != null) { appearance.put("building", design); }
        }
        hex.setAppearance(appearance);
    }

    /**
     * A copy of a board's {@code hex} for the conversion to change and install in its place: {@link Hex#duplicate} with
     * the hex's fire turn, which a burning hex of an old save converted at load ({@code Game.initializeAfterLoad}) keeps.
     */
    private static Hex replacement(Hex hex) {
        Hex copy = hex.duplicate();
        for (int turn = 0; turn < hex.getFireTurn(); turn++) { copy.incrementFireTurn(); }
        return copy;
    }

    /**
     * A stamp's members on {@code hex} at {@code at}: fresh ids and one fresh group. {@code {exits}} in the layout key
     * is the hex's road exits as two digits.
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
     * The surface a converted or stamped object stands on: the hex's roof, deck, industrial top, fuel tank or ice, else
     * its ground. Without {@code deck} it stands beneath a bridge, on what lies under the deck (trees: the legacy
     * road-with-trees art of a road passing under the bridge).
     */
    public static BoardDecoration.Placement receiver(Hex hex, boolean deck) {
        String support = hex.containsTerrain(Terrains.BUILDING) ? "building"
              : hex.containsTerrain(Terrains.BRIDGE) && deck ? "bridge"
              : hex.containsTerrain(Terrains.INDUSTRIAL) ? "industrial"
              : hex.containsTerrain(Terrains.FUEL_TANK) ? "fuelTank"
              : hex.containsTerrain(Terrains.ICE) ? "ice" : "ground";
        return BoardDecoration.Placement.on(support, 0);
    }

    /** The hex's own dry ground carries its objects: no roof, deck, industrial top, fuel tank, ice or water. */
    public static boolean plainGround(Hex hex) {
        return !hex.containsTerrain(Terrains.WATER) && receiver(hex, true).receiver().terrain().equals("ground");
    }

    /**
     * The renderer draws a road on the hex's own dry ground (its road kind, no liquid): a car park's decal rows stand
     * beside it rather than back to back.
     */
    public static boolean roadside(Hex hex) {
        return HexTile.road(hex) && plainGround(hex) && !HexTile.liquid(hex);
    }

    /**
     * The conversion's one rule-terrain change: a car park on plain ground without a road gets the lane the legacy render
     * paved through its sprite ({@link TilesetScenery.Scenery#cosmeticRoadExits}) as a paved road with exactly those
     * exits, so it runs edge to edge between its two rows of bays and joins only the roads that point along it. A car park
     * on a roof, deck, industrial top, fuel tank, ice or water keeps no road (its sprite was decoration there), nor does
     * one on another liquid, where no road is drawn; an authored road is kept. {@code lanes} holds every car park that
     * gets its lane. An automatic neighbour road on the lane stays automatic and gains its exit toward the car park. One
     * off the lane is written out with the exits it loaded with plus one toward each lane that points at it, so it grows
     * no arm toward a car park and joins every lane along it, whichever car park the scan meets first. Call before the
     * car park's own {@code board.setHex}.
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
            Hex kept = replacement(board.getHex(next));
            kept.addTerrain(new Terrain(Terrains.ROAD, road.getLevel(), true, exits));
            board.setHex(next, kept);
        }
    }

    /**
     * A route marker follows plain ground; on water, ice, a deck, roof, industrial top or fuel tank it holds that
     * support's level, since a route either follows the ground or keeps a fixed level. A support without its height
     * (a loadable but invalid hex) leaves the marker on the ground.
     */
    public static BoardDecoration.Placement routePlacement(Hex hex) {
        var support = receiver(hex, true);
        if (plainGround(hex)) { return support; }
        double level = support.level(hex);
        return Double.isFinite(level) ? BoardDecoration.Placement.absolute(level) : BoardDecoration.Placement.ground();
    }

    /**
     * Where a converted rail with {@code sides} on {@code at} lies: on its bridge's deck when one of its sides runs along
     * the bridge ({@link RailRoute#alongDeck}) and the hex is water, or the rail leaves the bridge's span
     * ({@link BridgeSpan#of}) along the deck (a rail side of a span hex that is a bridge exit) off the board or onto a hex
     * whose ground is within one level of that deck, as a road meets a deck; otherwise on the ground, beneath any bridge
     * (a rail crossing under it on water lies on the bed, as its neighbours). Never on a roof, industrial top or fuel
     * tank: there it runs on the ground inside them. Each span hex's rail sides are its sides as converted
     * ({@link #convertedSides}): rail00 art anywhere on the span counts the sides it joins.
     */
    private static BoardDecoration.Placement railPlacement(Board board, Coords at, int sides,
          Map<Coords, TilesetScenery.Scenery> scenery) {
        Hex hex = board.getHex(at);
        if (!RailRoute.alongDeck(sides, RailRoute.bridgeExits(hex))) { return BoardDecoration.Placement.ground(); }
        var deck = BoardDecoration.Placement.on("bridge", 0);
        if (hex.containsTerrain(Terrains.WATER)) { return deck; }
        List<Coords> span = BridgeSpan.of(board::getHex, at);
        for (Coords member : span) {
            Hex bridge = board.getHex(member);
            int along = convertedSides(board, member, scenery) & bridge.getTerrain(Terrains.BRIDGE).getExits();
            for (int direction = 0; direction < 6; direction++) {
                Coords next = member.translated(direction);
                if ((along & RailRoute.side(direction)) == 0 || span.contains(next)) { continue; }
                if (!board.contains(next) || Math.abs(board.getHex(next).getLevel() - BridgeSpan.deck(bridge)) <= 1) {
                    return deck;
                }
            }
        }
        return BoardDecoration.Placement.ground();
    }

    /** The sides of {@code at}'s rail: its legacy art's, where the scan resolved it, else its rail markers'. */
    private static int railSides(Board board, Coords at, Map<Coords, TilesetScenery.Scenery> scenery) {
        var artwork = scenery.get(at);
        int sides = 0;
        if (artwork == null) {
            for (var marker : RailRoute.markers(board.getHex(at), RailRoute.Kind.RAIL)) { sides |= marker.connections(); }
            return sides;
        }
        for (String key : artwork.models()) {
            for (Component row : LAYOUTS.get(key).components()) {
                if (RailRoute.Kind.of(row.asset()) == RailRoute.Kind.RAIL) { sides |= row.connections(); }
            }
        }
        return sides;
    }

    /**
     * The sides of {@code at}'s rail as the conversion gives them: its art's ({@link #railSides}), and where that art has
     * no sides (rail00) the sides it joins ({@link #railJoins}).
     */
    private static int convertedSides(Board board, Coords at, Map<Coords, TilesetScenery.Scenery> scenery) {
        int sides = railSides(board, at, scenery);
        return sideless(at, scenery) ? sides | railJoins(board, at, scenery) : sides;
    }

    /** Whether {@code at}'s legacy art in {@code scenery} holds rail art without sides (rail00). */
    private static boolean sideless(Coords at, Map<Coords, TilesetScenery.Scenery> scenery) {
        return scenery.getOrDefault(at, TilesetScenery.Scenery.EMPTY).models().stream()
              .anyMatch(key -> LAYOUTS.get(key).components().stream().anyMatch(row ->
                    RailRoute.Kind.of(row.asset()) == RailRoute.Kind.RAIL && row.connections() == 0));
    }

    /**
     * The sides legacy rail art without sides (rail00, drawn as a closed star) takes: toward each neighbour whose rail
     * leaves back ({@link #railSides}) or that is such art too.
     */
    private static int railJoins(Board board, Coords at, Map<Coords, TilesetScenery.Scenery> scenery) {
        int sides = 0;
        for (int direction = 0; direction < 6; direction++) {
            Coords next = at.translated(direction);
            if (!board.contains(next)) { continue; }
            if (sideless(next, scenery)
                  || (railSides(board, next, scenery) & RailRoute.side((direction + 3) % 6)) != 0) {
                sides |= RailRoute.side(direction);
            }
        }
        return sides;
    }

    private static Map<String, Layout> read() {
        var file = new File(Configuration.dataDir(), "models/board/scenery/layouts.json");
        // Legacy scenery keys decode through these rows; there are no baked composite meshes to fall back to.
        if (!file.isFile()) { throw new IllegalStateException("Cannot load the 3D board's scenery layouts"); }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(file);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the 3D board's scenery layouts " + file, failure);
        }
        var result = new HashMap<String, Layout>();
        root.fields().forEachRemaining(entry -> {
            var components = new ArrayList<Component>();
            for (JsonNode component : entry.getValue()) {
                JsonNode position = component.get("position");
                String asset = component.get("asset").asText();
                // The shared park tree is the only tree slot; every other row is scenery. A stored "kind" is ignored.
                boolean tree = asset.equals("tree-broad");
                JsonNode stretch = component.get("stretch");
                JsonNode seat = component.get("seat");
                var colours = new ArrayList<String>();
                if (component.has("colours")) {
                    for (JsonNode colour : component.get("colours")) { colours.add(colour.isNull() ? null : colour.asText()); }
                }
                components.add(new Component(asset, tree,
                      (float) position.get(0).asDouble(), (float) position.get(1).asDouble(), (float) position.get(2).asDouble(),
                      (float) component.get("rotation").asDouble(), (float) component.get("scale").asDouble(),
                      component.path("connections").asInt(0),
                      stretch == null ? BoardDecoration.Stretch.NONE
                            : new BoardDecoration.Stretch(stretch.get(0).asDouble(), stretch.get(1).asDouble(), stretch.get(2).asDouble()),
                      new BoardDecoration.Colours(colours), seat == null ? null : new Seat(seat.get(0).asInt(), seat.get(1).asInt())));
            }
            result.put(entry.getKey(), new Layout(List.copyOf(components)));
        });
        return Map.copyOf(result);
    }
}
