/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrains;

/**
 * The authored deck shape and its presentation: its type (built or natural, {@link HexAppearance#bridgeBuilt}) and the
 * materials of connected roads.
 */
final class BoardBridge {
    static final float NATURAL_HALF_WIDTH = 4;
    /** Half the passage both deck styles reserve, in model px: a natural mouth or a paved lane and its shoulder. */
    static final float PASSAGE_HALF_WIDTH = Math.max(NATURAL_HALF_WIDTH * 1.04f * BoardGeometry.TILE_WIDTH / 30,
          BoardRoad.Kind.PAVED.halfWidth + BoardRoad.SHOULDER);
    /** The bridge GLBs' slab beneath the deck surface, model px (deck top Z = 0, slab bottom Z = -1.5). */
    static final float SLAB = 1.5f;

    private BoardBridge() { }

    /** The drawn top of a built deck at {@code elevation} levels: every bridge GLB, graded slab, landing and pier. */
    static float deckZ(float elevation) {
        return elevation * BoardGeometry.level() + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
    }

    /** The drawn top of the tile's built deck, at its hex level plus its bridge's height. */
    static float deckZ(BoardScene.Tile tile) { return deckZ(tile.elevation() + feature(tile).elevation()); }

    /** {@code piers}: the hex's Pillars toggle ({@link HexAppearance#PILLARS}), see {@link BoardBridgeFooting#pier}. */
    record Deck(BoardRoad.Kind kind, int exits, List<BoardRoad.Kind> neighbors, BoardScene.Surface surface,
          List<Float> rises, boolean piers) {
        BoardRoad road(Coords coords) { return BoardRoad.layout(coords, exits, kind, neighbors::get); }
        boolean natural() { return kind == BoardRoad.Kind.NONE; }
        boolean sloped() { return rises.stream().anyMatch(rise -> rise != 0); }
    }

    private record Material(BoardRoad.Kind kind, BoardScene.Surface surface) { }

    /** PIER: the concrete piers under the deck's joints ({@link BoardBridgeFooting#pier}), never a walkable level. */
    enum Part { TOP, RIM, SIDE, SOFFIT, STRUCTURE, PIER }

    record Facet(Vector3 a, Vector3 b, Vector3 c, Vector3 normal, Part part) { }

    /** Immutable CPU geometry, owned by the installed terrain chunk and shared by drawing and picking. */
    record Shape(BoardScene.Surface surface, float level, List<Facet> facets, BoundingBox bounds) {
        float hit(Ray ray) { return hit(ray, true); }

        /** The squared distance to the nearest facet the ray meets; without {@code piers}, rays pass the piers. */
        float hit(Ray ray, boolean piers) {
            if (!Intersector.intersectRayBoundsFast(ray, bounds)) { return Float.POSITIVE_INFINITY; }
            float distance = Float.POSITIVE_INFINITY;
            var hit = new Vector3();
            for (var face : facets) {
                if ((piers || face.part() != Part.PIER)
                      && Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)) {
                    distance = Math.min(distance, ray.origin.dst2(hit));
                }
            }
            return distance;
        }
    }

    static Shape shape(BoardScene.Surface surface, float level, List<Facet> facets) {
        var bounds = new BoundingBox().inf();
        facets.forEach(face -> bounds.ext(face.a()).ext(face.b()).ext(face.c()));
        return new Shape(surface, level, List.copyOf(facets), bounds);
    }

    static void triangle(List<Facet> faces, Vector3 a, Vector3 b, Vector3 c, Part part) {
        var normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a));
        if (normal.len2() > 1e-8f) { faces.add(new Facet(a, b, c, normal.nor(), part)); }
    }

    static String asset(int exits) {
        return exits == 9 ? "bridge" : "bridges/bridge-exits-" + String.format(java.util.Locale.ROOT, "%02d", exits);
    }

    static BoardScene.Feature feature(BoardScene.Tile tile) {
        return tile == null ? null : tile.features().stream().filter(f -> f.asset().equals("bridge")).findFirst().orElse(null);
    }

    static Deck deck(BoardScene scene, BoardScene.Tile tile) {
        var bridge = feature(tile);
        if (bridge == null) { return null; }
        var material = material(scene, tile);
        var kind = material.kind();
        var neighbors = new ArrayList<BoardRoad.Kind>();
        var rises = new ArrayList<Float>();
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            neighbors.add(connected(tile, next, d) ? kind
                  : road(tile, next, d) ? next.road() : BoardRoad.Kind.NONE);
            rises.add(edgeElevation(tile, next, d) - tile.elevation() - bridge.elevation());
        }
        return new Deck(kind, bridge.bridgeExits(), List.copyOf(neighbors), material.surface(), List.copyOf(rises),
              HexAppearance.pillars(tile.appearance()));
    }

    /**
     * NONE for a natural span; a built one inherits its best road approach (asphalt, then gravel, then dirt), or without
     * one is plain unmarked asphalt (ALLEY).
     */
    static BoardRoad.Kind kind(BoardScene scene, BoardScene.Tile tile) {
        return material(scene, tile).kind();
    }

    /**
     * The connected span's type and materials. Every captured bridge tile carries a type, stored or decoded
     * ({@link #typed}); where its hexes differ, built wins. Only a hand-made scene without any type keeps the rule that a
     * road approach makes a span built.
     */
    private static Material material(BoardScene scene, BoardScene.Tile tile) {
        var pending = new ArrayDeque<BoardScene.Tile>();
        var visited = new HashSet<Coords>();
        var banks = new HashSet<Coords>();
        var surfaces = BoardScene.Surface.values();
        int[] votes = new int[surfaces.length];
        pending.add(tile);
        visited.add(tile.coords());
        BoardRoad.Kind found = BoardRoad.Kind.NONE;
        Boolean built = null;
        while (!pending.isEmpty()) {
            var current = pending.remove();
            Boolean type = HexAppearance.bridgeBuilt(current.appearance());
            if (type != null && (built == null || type)) { built = type; }
            for (int d = 0; d < 6; d++) {
                var next = scene.tile(current.coords().translated(d));
                if (connected(current, next, d)) {
                    if (visited.add(next.coords())) { pending.add(next); }
                } else if (road(current, next, d)) {
                    var kind = next.road() == BoardRoad.Kind.NONE ? BoardRoad.Kind.PAVED : next.road();
                    if (priority(kind) > priority(found)) { found = kind; }
                } else if (abutment(current, next, d) && next.surface() != BoardScene.Surface.CONCRETE && banks.add(next.coords())) {
                    votes[next.surface().ordinal()]++;
                }
            }
        }
        if (built == null ? found != BoardRoad.Kind.NONE : built) {
            return new Material(found == BoardRoad.Kind.NONE ? BoardRoad.Kind.ALLEY : found, BoardScene.Surface.ROCK);
        }
        var surface = tile.surface() == BoardScene.Surface.CONCRETE ? BoardScene.Surface.ROCK : tile.surface();
        int count = 0;
        for (var candidate : surfaces) {
            if (votes[candidate.ordinal()] > count) {
                surface = candidate;
                count = votes[candidate.ordinal()];
            }
        }
        return new Material(BoardRoad.Kind.NONE, surface);
    }

    private static int priority(BoardRoad.Kind kind) {
        return switch (kind) {
            case NONE -> 0;
            case DIRT -> 1;
            case GRAVEL -> 2;
            case ALLEY -> 3;
            case PAVED -> 4;
        };
    }

    static boolean road(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return next != null && next.roadExits() != 0
              && BoardSurface.connectingBridge(next, tile, (direction + 3) % 6) != null;
    }

    static boolean bank(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return abutment(tile, next, direction)
              && next.elevation() <= tile.elevation() + feature(tile).elevation() + 1;
    }

    /** Solid ground that can anchor a span, including a cliff rising above its deck. */
    static boolean abutment(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        var bridge = feature(tile);
        return bridge != null && next != null && !next.liquid().present() && feature(next) == null
              && (bridge.bridgeExits() & (1 << direction)) != 0
              && next.elevation() >= tile.elevation() + bridge.elevation() - 1;
    }

    /** A bridge's passage at deck height, through a bank or along the deck; what lies well below it is no obstruction. */
    record Approach(float x, float y, float nx, float ny, float length, float width, float level) {
        float lateral(Vector3 point) { return (point.y - y) * nx - (point.x - x) * ny; }

        boolean obstructs(Vector3 point, float radius, float height) {
            float along = (point.x - x) * nx + (point.y - y) * ny;
            return point.z + height >= level - BoardRelief.metres(.5f) && along > -radius && along < length + radius
                  && Math.abs(lateral(point)) < width + radius;
        }

        Vector3 beside(Vector3 point, float radius, int side) {
            float shift = side * (width + radius + BoardRelief.metres(.15f)) - lateral(point);
            return new Vector3(point).add(-ny * shift, nx * shift, 0);
        }
    }

    /** The passages through this hex: towards each bridge it is a bank of, and along its own bridge's deck. */
    static List<Approach> approaches(BoardScene scene, BoardScene.Tile tile) {
        var result = new ArrayList<Approach>();
        var center = BoardGeometry.center(tile.coords(), tile.elevation());
        var own = feature(tile);
        for (int d = 0; d < 6; d++) {
            boolean span = own != null && (own.bridgeExits() & (1 << d)) != 0;
            if (!span && !bank(scene.tile(tile.coords().translated(d)), tile, (d + 3) % 6)) { continue; }
            var direction = BoardGeometry.center(tile.coords().translated(d), tile.elevation()).sub(center);
            float length = direction.len();
            direction.scl(1 / length);
            // Both styles reserve the usable passage. A distant road edit must not change the bank's ground mesh.
            float width = PASSAGE_HALF_WIDTH * BoardGeometry.hexScale();
            float level = span ? Math.min(tile.elevation() + own.elevation(),
                  edgeElevation(tile, scene.tile(tile.coords().translated(d)), d)) * BoardGeometry.level() : center.z;
            result.add(new Approach(center.x, center.y, direction.x, direction.y, length, width, level));
        }
        return List.copyOf(result);
    }

    /** The decks join ({@link BridgeSpan#joined}): one span, one type, a joint the piers may stand under. */
    static boolean connected(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        var bridge = feature(tile);
        var other = feature(next);
        return bridge != null && other != null && BridgeSpan.joined(bridge.bridgeExits(), tile.elevation() + bridge.elevation(),
              other.bridgeExits(), next.elevation() + other.elevation(), direction);
    }

    /**
     * The legacy decode of the type of {@code span} (a {@link BridgeSpan}), the one rule of import, of the 3D editor's
     * typing of untyped bridges and of their render ({@link #typed}): a type stored on any of its hexes (built wins);
     * else built when one of its hexes has a road attached (ROAD on it at any level, or a deck end on a road hex), rail
     * or maglev art (FLUFF 10 or 9) on it or at a deck end, pillar art ({@code pillarArt}), PAVEMENT terrain (snow on it
     * too) on it or at a deck end, or a BUILDING on it or beside it; otherwise natural. {@code hexes} is null off the board.
     */
    static boolean built(Function<Coords, Hex> hexes, Predicate<Coords> pillarArt, List<Coords> span) {
        Boolean stored = BridgeSpan.type(hexes, span);
        if (stored != null) { return stored; }
        var members = new HashSet<>(span);
        for (Coords at : span) {
            Hex hex = hexes.apply(at);
            if (pillarArt.test(at) || artificial(hex) || hex.containsTerrain(Terrains.ROAD)) { return true; }
            int exits = hex.getTerrain(Terrains.BRIDGE).getExits();
            for (int d = 0; d < 6; d++) {
                Coords next = at.translated(d);
                Hex beside = hexes.apply(next);
                if (beside == null || members.contains(next)) { continue; }
                if (beside.containsTerrain(Terrains.BUILDING)) { return true; }
                // A deck end: an exit onto ground, not onto another deck.
                if ((exits & (1 << d)) != 0 && !beside.containsTerrain(Terrains.BRIDGE)
                      && (artificial(beside) || beside.containsTerrain(Terrains.ROAD))) { return true; }
            }
        }
        return false;
    }

    /** Pavement (under snow too), a building, or rail or maglev art (FLUFF 10 or 9): what a built bridge leads to. */
    private static boolean artificial(Hex hex) {
        int fluff = hex.containsTerrain(Terrains.FLUFF) ? hex.terrainLevel(Terrains.FLUFF) : -1;
        return hex.containsAnyTerrainOf(Terrains.PAVEMENT, Terrains.BUILDING) || fluff == 9 || fluff == 10;
    }

    /**
     * {@code appearance}, the hex's as the 3D board draws it, with its bridge's type: the stored one, else the legacy
     * decode's ({@link #built}) over its span on {@code board} (null off the board). A neighbour's pillar art is its own
     * tile's PILLARS, and a span's built tile wins ({@link #material}). {@code decoded} keeps each decode for the
     * caller's board, so a later capture (a building beside the span collapsing) does not retype the bridge.
     */
    static Map<String, HexAppearance> typed(Hex hex, Coords coords, Map<String, HexAppearance> appearance,
          Function<Coords, Hex> board, Map<Coords, HexAppearance> decoded) {
        if (!hex.containsTerrain(Terrains.BRIDGE) || HexAppearance.bridgeBuilt(appearance) != null) { return appearance; }
        Function<Coords, Hex> hexes = at -> at.equals(coords) ? hex : board.apply(at);
        Map<String, HexAppearance> typed = new HashMap<>(appearance);
        typed.put("bridge", decoded.computeIfAbsent(coords, at -> built(hexes, c -> false, BridgeSpan.of(hexes, at))
              ? HexAppearance.BUILT_BRIDGE : HexAppearance.NATURAL_BRIDGE));
        return typed;
    }

    /** Connected decks and road approaches each own half the grade; bare banks keep the authored deck height. */
    static float edgeElevation(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        float level = tile.elevation() + feature(tile).elevation();
        if (connected(tile, next, direction)) { return (level + next.elevation() + feature(next).elevation()) / 2; }
        return road(tile, next, direction) ? (level + next.elevation()) / 2 : level;
    }
}
