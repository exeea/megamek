/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;

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

    /** The highest TOP facet of an installed deck shape at (x, y), or negative infinity where it has none there. */
    static float top(Shape shape, float x, float y) {
        float height = Float.NEGATIVE_INFINITY;
        if (shape == null) { return height; }
        for (var facet : shape.facets()) {
            if (facet.part() == Part.TOP) {
                height = Math.max(height, new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP)
                      .height(x, y));
            }
        }
        return height;
    }

    /**
     * The drawn deck at (x, y): its installed shape's top ({@link #top}, a sloped or fitted deck), else the plane of the
     * tile's flat deck, which its GLB draws alone ({@link #deckZ}). What lies on a deck stands on it, a rail too.
     */
    static float drawnTop(Shape shape, BoardScene.Tile tile, float x, float y) {
        float height = top(shape, x, y);
        return Float.isFinite(height) || feature(tile) == null ? height : deckZ(tile);
    }

    /**
     * {@code piers}: the hex's Pillars toggle ({@link HexAppearance#PILLARS}), see {@link BoardBridgeFooting#pier}.
     * {@code colours}: the span's truss colours ({@link BridgeSpan#truss}), null for the standard design or a natural
     * span. {@code joints}: the exits whose neighbour is a joined deck ({@link #connected}); a truss continues there
     * and ends with an end post at every other exit ({@link #trussNodes}). Any change rebuilds the hex.
     */
    record Deck(BoardRoad.Kind kind, int exits, List<BoardRoad.Kind> neighbors, BoardScene.Surface surface,
          List<Float> rises, boolean piers, BoardDecoration.Colours colours, int joints) {
        BoardRoad road(Coords coords) { return BoardRoad.layout(coords, exits, kind, neighbors::get); }
        boolean natural() { return kind == BoardRoad.Kind.NONE; }
        boolean sloped() { return rises.stream().anyMatch(rise -> rise != 0); }
        boolean truss() { return colours != null; }
        /** The deck's GLB: the standard kit or the truss kit of its exits. */
        String asset() { return BoardBridge.asset(exits, truss()); }
    }

    private record Material(BoardRoad.Kind kind, BoardScene.Surface surface, BoardDecoration.Colours truss) { }

    /**
     * PIER: the concrete piers under the deck's joints ({@link BoardBridgeFooting#pier}), never a walkable level. STEEL:
     * a sloped truss's steel members, drawn in their facets' {@code colour} (the span's recoloured Body).
     */
    enum Part { TOP, RIM, SIDE, SOFFIT, STRUCTURE, PIER, STEEL }

    /** {@code colour}: a STEEL facet's own colour; null for every other part, which its material colours. */
    record Facet(Vector3 a, Vector3 b, Vector3 c, Vector3 normal, Part part, Color colour) {
        Facet(Vector3 a, Vector3 b, Vector3 c, Vector3 normal, Part part) { this(a, b, c, normal, part, null); }
    }

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
        triangle(faces, a, b, c, part, null);
    }

    static void triangle(List<Facet> faces, Vector3 a, Vector3 b, Vector3 c, Part part, Color colour) {
        var normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a));
        if (normal.len2() > 1e-8f) { faces.add(new Facet(a, b, c, normal.nor(), part, colour)); }
    }

    static String asset(int exits) { return asset(exits, false); }

    /** One GLB per exit mask: the standard kit ({@code bridge} for a straight deck) or the truss kit. */
    static String asset(int exits, boolean truss) {
        String mask = String.format(java.util.Locale.ROOT, "%02d", exits);
        return truss ? "bridges/truss-exits-" + mask : exits == 9 ? "bridge" : "bridges/bridge-exits-" + mask;
    }

    /**
     * The nodes of a truss deck's GLB that draw: its body and, per exit, exactly one of its two alternatives, the half
     * panel that continues the truss across a joint ({@code truss-joint-D}) or the inclined end post
     * ({@code truss-end-D}) at a bank, a road landing or an open end. Drawing both or neither would be wrong.
     */
    static List<String> trussNodes(Deck deck) {
        var nodes = new ArrayList<String>();
        nodes.add("truss-exits-" + String.format(java.util.Locale.ROOT, "%02d", deck.exits()));
        for (int d = 0; d < 6; d++) {
            if ((deck.exits() & (1 << d)) != 0) { nodes.add(((deck.joints() & (1 << d)) != 0 ? "truss-joint-" : "truss-end-") + d); }
        }
        return List.copyOf(nodes);
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
        int joints = 0;
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            boolean joined = connected(tile, next, d);
            if (joined) { joints |= 1 << d; }
            neighbors.add(joined ? kind : road(tile, next, d) ? next.road() : BoardRoad.Kind.NONE);
            rises.add(edgeElevation(tile, next, d) - tile.elevation() - bridge.elevation());
        }
        return new Deck(kind, bridge.bridgeExits(), List.copyOf(neighbors), material.surface(), List.copyOf(rises),
              HexAppearance.pillars(tile.appearance()), material.truss(), joints);
    }

    /**
     * NONE for a natural span; a built one inherits its best road approach (asphalt, then gravel, then dirt), or without
     * one is plain unmarked asphalt (ALLEY).
     */
    static BoardRoad.Kind kind(BoardScene scene, BoardScene.Tile tile) {
        return material(scene, tile).kind();
    }

    /**
     * The connected span's type and materials. Every bridge of a converted board stores its type; where its hexes
     * differ, built wins. A span without any type (a transient editor state, a hand-made scene) is built, as the
     * editor's stroke types a new bridge. A built span's truss design and colours follow {@link BridgeSpan#truss}.
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
                } else if (next != null && next.roadExits() != 0 && road(current, next, d)) {
                    // Only a road votes for the deck's material: a span that only rails reach stays plain asphalt.
                    var kind = next.road() == BoardRoad.Kind.NONE ? BoardRoad.Kind.PAVED : next.road();
                    if (priority(kind) > priority(found)) { found = kind; }
                } else if (abutment(current, next, d) && next.surface() != BoardScene.Surface.CONCRETE && banks.add(next.coords())) {
                    votes[next.surface().ordinal()]++;
                }
            }
        }
        if (built == null || built) {
            return new Material(found == BoardRoad.Kind.NONE ? BoardRoad.Kind.ALLEY : found, BoardScene.Surface.ROCK,
                  BridgeSpan.truss(at -> scene.tile(at).appearance(), visited));
        }
        var surface = tile.surface() == BoardScene.Surface.CONCRETE ? BoardScene.Surface.ROCK : tile.surface();
        int count = 0;
        for (var candidate : surfaces) {
            if (votes[candidate.ordinal()] > count) {
                surface = candidate;
                count = votes[candidate.ordinal()];
            }
        }
        return new Material(BoardRoad.Kind.NONE, surface, null);
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

    /** A carriageway (a road or a ground rail) on {@code next} meets the deck: it gets a half grade and a solid ramp. */
    static boolean road(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return next != null && next.wayExits() != 0
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

    /** Connected decks and road approaches each own half the grade; bare banks keep the authored deck height. */
    static float edgeElevation(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        float level = tile.elevation() + feature(tile).elevation();
        if (connected(tile, next, direction)) { return (level + next.elevation() + feature(next).elevation()) / 2; }
        return road(tile, next, direction) ? (level + next.elevation()) / 2 : level;
    }
}
