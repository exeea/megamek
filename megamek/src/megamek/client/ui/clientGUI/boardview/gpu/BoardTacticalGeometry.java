/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;

/** Tessellation and terrain clipping only; all tactical decisions are supplied by the client painters. */
final class BoardTacticalGeometry {
    private static final float WALL_CLEARANCE = 0.6f;

    record Triangle(Vector3 a, Vector3 b, Vector3 c, int argb) { }
    /** Only the finished triangles are retained; none of the terrain builder's scene or shoreline caches. */
    record Surface(List<BoardSurface.Face> top, List<BoardSurface.Face> slopes,
          List<BoardSurface.Face> faces, List<BoardSurface.Face> water, List<BoardSurface.Face> walls,
          List<BoardSurface.Side> waterfalls, float highestTop) {
        Surface(List<BoardSurface.Face> top, List<BoardSurface.Face> slopes, List<BoardSurface.Face> faces,
              List<BoardSurface.Face> water, List<BoardSurface.Face> walls, List<BoardSurface.Side> waterfalls) {
            this(top, slopes, faces, water, walls, waterfalls, highest(top));
        }

        /** Derived once with this immutable finished-surface snapshot; markers and border endpoints reuse it. */
        private static float highest(List<BoardSurface.Face> faces) {
            float z = Float.NEGATIVE_INFINITY;
            for (BoardSurface.Face face : faces) {
                z = Math.max(z, Math.max(face.a().z, Math.max(face.b().z, face.c().z)));
            }
            return z;
        }

        static Surface of(BoardSurface surface, BoardScene scene, float floor) {
            List<BoardSurface.Face> top = new ArrayList<>();
            for (BoardSurface.Face face : surface.faces) {
                if (surface.tile.frozen() ? face.finish() == BoardSurface.Finish.ICE
                      : face.finish() == BoardSurface.Finish.TOP || face.finish() == BoardSurface.Finish.SHORE) {
                    top.add(face);
                }
            }
            if (!surface.tile.frozen()) { top.addAll(surface.waterFaces); }
            List<BoardSurface.Face> walls = List.copyOf(surface.walls(scene, floor));
            return new Surface(List.copyOf(top), BoardGeometry.tuning().stepsBetweenTops() ? lying(walls) : List.of(),
                  List.copyOf(surface.faces), List.copyOf(surface.waterFaces), walls, List.copyOf(surface.waterfalls));
        }
    }
    private record Edge(float x1, float y1, float x2, float y2) {
        float x(float y) {
            return x1 + (x2 - x1) * (y - y1) / (y2 - y1);
        }
    }

    private BoardTacticalGeometry() { }

    /** Horizontal trapezoids preserve holes, dashed strokes, concave polygons and glyph counters. */
    static List<Triangle> flat(BoardTactical.Fill fill) {
        List<Edge> edges = new ArrayList<>();
        TreeSet<Float> levels = new TreeSet<>();
        PathIterator path = new Area(fill.shape()).getPathIterator(null, 0.25);
        float[] point = new float[6];
        float x = 0, y = 0, startX = 0, startY = 0;
        while (!path.isDone()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) {
                startX = x = point[0];
                startY = y = point[1];
            } else {
                float nextX = type == PathIterator.SEG_CLOSE ? startX : point[0];
                float nextY = type == PathIterator.SEG_CLOSE ? startY : point[1];
                if (y != nextY) {
                    edges.add(new Edge(x, y, nextX, nextY));
                    levels.add(y);
                    levels.add(nextY);
                }
                x = nextX;
                y = nextY;
            }
            path.next();
        }
        List<Triangle> result = new ArrayList<>();
        List<Float> rows = new ArrayList<>(levels);
        for (int row = 1; row < rows.size(); row++) {
            float bottom = rows.get(row - 1), top = rows.get(row), middle = (bottom + top) / 2;
            List<Edge> crossings = edges.stream()
                  .filter(edge -> middle > Math.min(edge.y1(), edge.y2()) && middle < Math.max(edge.y1(), edge.y2()))
                  .sorted(Comparator.comparingDouble(edge -> edge.x(middle))).toList();
            for (int i = 1; i < crossings.size(); i += 2) {
                Edge left = crossings.get(i - 1), right = crossings.get(i);
                Vector3 a = new Vector3(left.x(bottom), bottom, 0), b = new Vector3(right.x(bottom), bottom, 0);
                Vector3 c = new Vector3(right.x(top), top, 0), d = new Vector3(left.x(top), top, 0);
                add(result::add, a, b, c, fill.argb());
                add(result::add, a, c, d, fill.argb());
            }
        }
        return result;
    }

    static void drape(BoardScene scene, Consumer<Triangle> destination) {
        drape(scene, scene.tactical().fills(), destination, surfaces(scene));
    }

    static void drape(BoardScene scene, Consumer<Triangle> destination, Function<Coords, Surface> surfaces) {
        drape(scene, scene.tactical().fills(), destination, surfaces);
    }

    static Function<Coords, Surface> surfaces(BoardScene scene) {
        Map<Coords, Surface> cache = new HashMap<>();
        float floor = BoardGeometry.floor(scene);
        return coords -> cache.computeIfAbsent(coords,
              key -> Surface.of(new BoardSurface(scene, scene.tile(key)), scene, floor));
    }

    private static void drape(BoardScene scene, List<BoardTactical.Fill> fills, Consumer<Triangle> destination,
          Function<Coords, Surface> surfaces) {
        Clipper clipper = new Clipper();
        int layer = 0;
        for (BoardTactical.Fill fill : fills) {
            drape(scene, fill, layer++, destination, surfaces, clipper);
        }
    }

    /** A retained command uses its absolute painter position, including the original capped lift. */
    static void drape(BoardScene scene, BoardTactical.Fill fill, int layer, Consumer<Triangle> destination,
          Function<Coords, Surface> surfaces, Clipper clipper) {
        if (floating(scene, fill, destination, surfaces)) { return; }
        float lift = layerLift(layer);
        for (Triangle triangle : flat(fill)) {
            int firstX = Math.max(0, (int) Math.floor(minX(triangle) / (BoardGeometry.TILE_WIDTH * 0.75f)) - 1);
            int lastX = Math.min(scene.width() - 1, (int) Math.floor(maxX(triangle) / (BoardGeometry.TILE_WIDTH * 0.75f)));
            int firstY = Math.max(0, (int) Math.floor(minY(triangle) / BoardGeometry.TILE_HEIGHT) - 1);
            int lastY = Math.min(scene.height() - 1, (int) Math.floor(maxY(triangle) / BoardGeometry.TILE_HEIGHT));
            Triangle world = new Triangle(world(triangle.a()), world(triangle.b()), world(triangle.c()), fill.argb());
            clipper.prepare(world);
            for (int x = firstX; x <= lastX; x++) {
                for (int y = firstY; y <= lastY; y++) {
                    Coords coords = new Coords(x, y);
                    Surface surface = surfaces.apply(coords);
                    clipSurface(surface, lift, destination, clipper);
                    for (BoardSurface.Face face : surface.slopes()) {
                        clipper.clip(face, lift, destination);
                    }
                }
            }
        }
    }

    static Coords borderCoords(BoardScene scene, BoardTactical.HexBorder border) {
        BoardScene.Tile tile = BoardGeometry.tile(scene, border.anchor().x() * BoardGeometry.HEX_SCALE,
              -border.anchor().y() * BoardGeometry.HEX_SCALE);
        return tile == null ? null : tile.coords();
    }

    /** A single horizontal plane clears its owner's finished top, without sampling neighboring cliffs or lake beds. */
    static boolean floating(BoardScene scene, BoardTactical.Fill fill, Consumer<Triangle> destination,
          Function<Coords, Surface> surfaces) {
        BoardTactical.HexBorder border = fill.border();
        if (border == null || !border.floating()) { return false; }
        Coords coords = borderCoords(scene, border);
        if (coords == null) { return false; }
        float z = floatingZ(scene, coords, surfaces);
        for (Triangle triangle : flat(fill)) {
            Vector3 a = world(triangle.a()), b = world(triangle.b()), c = world(triangle.c());
            a.z = z; b.z = z; c.z = z;
            destination.accept(new Triangle(a, b, c, triangle.argb()));
        }
        return true;
    }

    static float floatingZ(BoardScene scene, Coords coords, Function<Coords, Surface> surfaces) {
        Surface surface = surfaces.apply(coords);
        float z = surface.top().isEmpty() ? BoardGeometry.surfaceZ(scene.tile(coords)) : surface.highestTop();
        return z + .5f + GpuBattleView.SELECTION_BOB_HEIGHT_OFFSET;
    }

    static float layerLift(int layer) {
        return (0.35f + Math.min(layer, 10000) * 0.0001f) * BoardGeometry.HEX_SCALE;
    }

    /** Cache both presentations once; the camera only selects which one to draw. */
    static void walls(BoardScene scene, boolean flat, Consumer<Triangle> destination,
          BiConsumer<BoardTactical.Wall, Triangle> outline) {
        walls(scene, flat, destination, outline, surfaces(scene));
    }

    static void walls(BoardScene scene, boolean flat, Consumer<Triangle> destination,
          BiConsumer<BoardTactical.Wall, Triangle> outline, Function<Coords, Surface> surfaces) {
        Clipper clipper = new Clipper();
        if (flat) {
            drape(scene, scene.tactical().flatWalls(), destination, surfaces);
        }
        for (BoardTactical.Wall wall : BoardRangeBorder.join(scene.tactical().walls())) {
            wall(scene, wall, flat, destination, outline, surfaces, clipper);
        }
    }

    static void wall(BoardScene scene, BoardTactical.Wall wall, boolean flat, Consumer<Triangle> destination,
          BiConsumer<BoardTactical.Wall, Triangle> outline, Function<Coords, Surface> surfaces, Clipper clipper) {
        if (scene.tile(wall.coords()) == null) { return; }
        if (!flat) {
            Vector3 a = wallPoint(scene, wall, wall.a(), false, surfaces), b = wallPoint(scene, wall, wall.b(), false, surfaces);
            Vector3 c = wallPoint(scene, wall, wall.b(), true, surfaces), d = wallPoint(scene, wall, wall.a(), true, surfaces);
            destination.accept(new Triangle(a, b, c, wall.argb()));
            destination.accept(new Triangle(a, c, d, wall.argb()));
            wallOutline(wall, d, c, triangle -> outline.accept(wall, triangle));
        } else {
            Surface surface = surfaces.apply(wall.coords());
            Vector3 a = new Vector3(wall.a().x() * BoardGeometry.HEX_SCALE, -wall.a().y() * BoardGeometry.HEX_SCALE, 0);
            Vector3 b = new Vector3(wall.b().x() * BoardGeometry.HEX_SCALE, -wall.b().y() * BoardGeometry.HEX_SCALE, 0);
            wallOutline(wall, a, b, triangle -> {
                clipper.prepare(triangle);
                clipSurface(surface, WALL_CLEARANCE * BoardGeometry.HEX_SCALE,
                      clipped -> outline.accept(wall, clipped), clipper);
            });
        }
    }

    private static void clipSurface(Surface surface, float lift, Consumer<Triangle> destination,
          Clipper clipper) {
        for (BoardSurface.Face face : surface.top()) {
            clipper.clip(face, lift, destination);
        }
    }

    /** A continuous ribbon: the original dash pattern is supplied by a scrolling texture. */
    private static void wallOutline(BoardTactical.Wall wall, Vector3 a, Vector3 b, Consumer<Triangle> destination) {
        if (wall.outline() == null) {
            return;
        }
        var stroke = wall.outline().stroke();
        Vector3 side = new Vector3(a.y - b.y, b.x - a.x, 0).nor()
              .scl(stroke.getLineWidth() * BoardGeometry.HEX_SCALE / 2);
        Vector3 first = new Vector3(a).sub(side), second = new Vector3(b).sub(side);
        Vector3 third = new Vector3(b).add(side), fourth = new Vector3(a).add(side);
        destination.accept(new Triangle(first, second, third, wall.outline().argb()));
        destination.accept(new Triangle(first, third, fourth, wall.outline().argb()));
    }

    private static Vector3 wallPoint(BoardScene scene, BoardTactical.Wall wall, BoardTactical.Point point, boolean top,
          Function<Coords, Surface> surfaces) {
        Vector3 world = new Vector3(point.x() * BoardGeometry.HEX_SCALE, -point.y() * BoardGeometry.HEX_SCALE, 0);
        // Only interior owners of adjoining panels contribute to the joint, never terrain outside the range.
        // Finished tops include sculpted crowns and banks; deep water and lakebeds cannot pull a border down.
        float z = Math.max(scene.tile(wall.coords()).elevation() * BoardGeometry.LEVEL,
              surfaces.apply(wall.coords()).highestTop());
        for (Coords coords : point.equals(wall.a()) ? wall.aNeighbors() : wall.bNeighbors()) {
            BoardScene.Tile neighbor = scene.tile(coords);
            if (neighbor != null) {
                float neighborZ = Math.max(neighbor.elevation() * BoardGeometry.LEVEL, surfaces.apply(coords).highestTop());
                z = top ? Math.max(z, neighborZ) : Math.min(z, neighborZ);
            }
        }
        world.z = z + (top ? wall.height() * BoardGeometry.LEVEL : 0) + WALL_CLEARANCE * BoardGeometry.HEX_SCALE;
        return world;
    }

    private static Vector3 world(Vector3 point) {
        return new Vector3(point.x * BoardGeometry.HEX_SCALE, -point.y * BoardGeometry.HEX_SCALE, 0);
    }

    private static float cross(Vector3 a, Vector3 b, Vector3 c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    /** The faces of a wall that lie back far enough to be walked and marked on: slopes and talus, not cliff faces. */
    static List<BoardSurface.Face> lying(List<BoardSurface.Face> walls) {
        List<BoardSurface.Face> result = new ArrayList<>();
        for (BoardSurface.Face face : walls) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            if (normal.z > .35f * normal.len()) { result.add(face); }
        }
        return result;
    }

    /** One geometry rebuild owns its scratch vertices; emitted triangles always own separate vertices. */
    static final class Clipper {
        // Two convex triangles intersect in at most six corners; spare entries also retain boundary duplicates.
        private final Vector3[] first = vertices(), second = vertices();
        private Triangle triangle;
        private float minimumX, maximumX, minimumY, maximumY;

        /** One world triangle is clipped against many faces; its bounds stay fixed throughout those queries. */
        void prepare(Triangle triangle) {
            this.triangle = triangle;
            minimumX = minX(triangle);
            maximumX = maxX(triangle);
            minimumY = minY(triangle);
            maximumY = maxY(triangle);
        }

        private static Vector3[] vertices() {
            Vector3[] result = new Vector3[9];
            for (int i = 0; i < result.length; i++) { result[i] = new Vector3(); }
            return result;
        }

        void clip(BoardSurface.Face face, float lift, Consumer<Triangle> destination) {
            if (maximumX < Math.min(face.a().x, Math.min(face.b().x, face.c().x))
                  || minimumX > Math.max(face.a().x, Math.max(face.b().x, face.c().x))
                  || maximumY < Math.min(face.a().y, Math.min(face.b().y, face.c().y))
                  || minimumY > Math.max(face.a().y, Math.max(face.b().y, face.c().y))) {
                return;
            }
            float area = cross(face.a(), face.b(), face.c());
            if (Math.abs(area) < 0.00001f) { return; }
            Vector3[] polygon = first, clipped = second;
            polygon[0].set(triangle.a());
            polygon[1].set(triangle.b());
            polygon[2].set(triangle.c());
            int count = 3;
            float sign = Math.signum(area);
            for (int edge = 0; edge < 3 && count > 0; edge++) {
                Vector3 a = edge == 0 ? face.a() : edge == 1 ? face.b() : face.c();
                Vector3 b = edge == 0 ? face.b() : edge == 1 ? face.c() : face.a();
                int clippedCount = 0;
                Vector3 previous = polygon[count - 1];
                float before = sign * cross(a, b, previous);
                for (int i = 0; i < count; i++) {
                    Vector3 point = polygon[i];
                    float after = sign * cross(a, b, point);
                    if ((before >= 0) != (after >= 0)) {
                        clipped[clippedCount++].set(previous).lerp(point, before / (before - after));
                    }
                    if (after >= 0) { clipped[clippedCount++].set(point); }
                    previous = point;
                    before = after;
                }
                Vector3[] swap = polygon;
                polygon = clipped;
                clipped = swap;
                count = clippedCount;
            }
            if (count < 3) { return; }
            for (int i = 0; i < count; i++) {
                Vector3 point = polygon[i];
                float b = cross(face.a(), point, face.c()) / area;
                float c = cross(face.a(), face.b(), point) / area;
                point.z = face.a().z + b * (face.b().z - face.a().z) + c * (face.c().z - face.a().z) + lift;
            }
            Vector3 start = new Vector3(polygon[0]), previous = new Vector3(polygon[1]);
            for (int i = 2; i < count; i++) {
                Vector3 point = new Vector3(polygon[i]);
                add(destination, start, previous, point, triangle.argb());
                previous = point;
            }
        }
    }

    private static void add(Consumer<Triangle> destination, Vector3 a, Vector3 b, Vector3 c, int argb) {
        if (Math.abs(cross(a, b, c)) > 0.00001f) {
            destination.accept(new Triangle(a, b, c, argb));
        }
    }

    private static float minX(Triangle triangle) { return Math.min(triangle.a().x, Math.min(triangle.b().x, triangle.c().x)); }
    private static float maxX(Triangle triangle) { return Math.max(triangle.a().x, Math.max(triangle.b().x, triangle.c().x)); }
    private static float minY(Triangle triangle) { return Math.min(triangle.a().y, Math.min(triangle.b().y, triangle.c().y)); }
    private static float maxY(Triangle triangle) { return Math.max(triangle.a().y, Math.max(triangle.b().y, triangle.c().y)); }
}
