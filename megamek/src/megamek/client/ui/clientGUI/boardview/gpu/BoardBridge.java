/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;

/** The authored deck shape and its presentation materials, derived from connected roads. */
final class BoardBridge {
    static final float NATURAL_HALF_WIDTH = 4;

    private BoardBridge() { }

    record Deck(BoardRoad.Kind kind, int exits, List<BoardRoad.Kind> neighbors, BoardScene.Surface surface) {
        BoardRoad road(Coords coords) { return BoardRoad.layout(coords, exits, kind, neighbors::get); }
        boolean natural() { return kind == BoardRoad.Kind.NONE; }
    }

    private record Material(BoardRoad.Kind kind, BoardScene.Surface surface) { }

    enum Part { TOP, RIM, SIDE, SOFFIT, STRUCTURE }

    record Facet(Vector3 a, Vector3 b, Vector3 c, Vector3 normal, Part part) { }

    /** Immutable CPU geometry, owned by the installed terrain chunk and shared by drawing and picking. */
    record Shape(BoardScene.Surface surface, float level, List<Facet> facets, BoundingBox bounds) {
        float hit(Ray ray) {
            if (!Intersector.intersectRayBoundsFast(ray, bounds)) { return Float.POSITIVE_INFINITY; }
            float distance = Float.POSITIVE_INFINITY;
            var hit = new Vector3();
            for (var face : facets) {
                if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)) {
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
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            neighbors.add(connected(tile, next, d) ? kind
                  : road(tile, next, d) ? next.road() : BoardRoad.Kind.NONE);
        }
        return new Deck(kind, bridge.bridgeExits(), List.copyOf(neighbors), material.surface());
    }

    /** The whole connected span inherits its best approach: asphalt, then gravel, then dirt. */
    static BoardRoad.Kind kind(BoardScene scene, BoardScene.Tile tile) {
        return material(scene, tile).kind();
    }

    private static Material material(BoardScene scene, BoardScene.Tile tile) {
        var pending = new ArrayDeque<BoardScene.Tile>();
        var visited = new HashSet<Coords>();
        var banks = new HashSet<Coords>();
        var surfaces = BoardScene.Surface.values();
        int[] votes = new int[surfaces.length];
        pending.add(tile);
        visited.add(tile.coords());
        BoardRoad.Kind found = BoardRoad.Kind.NONE;
        while (!pending.isEmpty()) {
            var current = pending.remove();
            for (int d = 0; d < 6; d++) {
                var next = scene.tile(current.coords().translated(d));
                if (connected(current, next, d)) {
                    if (visited.add(next.coords())) { pending.add(next); }
                } else if (road(current, next, d)) {
                    var kind = next.road() == BoardRoad.Kind.NONE ? BoardRoad.Kind.PAVED : next.road();
                    if (priority(kind) > priority(found)) { found = kind; }
                    if (found == BoardRoad.Kind.PAVED) { return new Material(found, BoardScene.Surface.ROCK); }
                } else if (bank(current, next, d) && next.surface() != BoardScene.Surface.CONCRETE && banks.add(next.coords())) {
                    votes[next.surface().ordinal()]++;
                }
            }
        }
        var surface = tile.surface() == BoardScene.Surface.CONCRETE ? BoardScene.Surface.ROCK : tile.surface();
        int count = 0;
        for (var candidate : surfaces) {
            if (votes[candidate.ordinal()] > count) {
                surface = candidate;
                count = votes[candidate.ordinal()];
            }
        }
        return new Material(found, found == BoardRoad.Kind.NONE ? surface : BoardScene.Surface.ROCK);
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
        var bridge = feature(tile);
        return bridge != null && next != null && !next.liquid().present() && feature(next) == null
              && (bridge.bridgeExits() & (1 << direction)) != 0
              && Math.abs(tile.elevation() + bridge.elevation() - next.elevation()) <= 1;
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
            // Both styles reserve the widest mouth. A distant road edit must not change the bank's ground mesh.
            float width = Math.max(BoardRelief.metres(NATURAL_HALF_WIDTH * 1.04f),
                  (BoardRoad.Kind.PAVED.halfWidth + BoardRoad.SHOULDER) * BoardGeometry.hexScale());
            float level = span ? (tile.elevation() + own.elevation()) * BoardGeometry.level() : center.z;
            result.add(new Approach(center.x, center.y, direction.x, direction.y, length, width, level));
        }
        return List.copyOf(result);
    }

    static boolean connected(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        var bridge = feature(tile);
        var other = feature(next);
        return bridge != null && other != null && (bridge.bridgeExits() & (1 << direction)) != 0
              && (other.bridgeExits() & (1 << ((direction + 3) % 6))) != 0
              && tile.elevation() + bridge.elevation() == next.elevation() + other.elevation();
    }
}
