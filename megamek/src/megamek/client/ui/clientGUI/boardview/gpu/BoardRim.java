/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** CPU cliff-top lightness masks, shared by the bounded terrain workers. Original artwork stays immutable. */
final class BoardRim {
    static final float BLEND_OPACITY = 0.7f;
    static final float GROUND_UV_SCALE = 0.96f; // MUST NOT TOUCH!!! With 1.0f we have some black pixels in the textures around the borders!
    /** The board's own incline split: a drop of up to two levels is an incline, anything deeper a high incline. */
    private static final int INCLINE_LEVELS = 2;

    record Images(BoardScene.Pixels color, BoardScene.Pixels normal) { }
    private record Triangle(float ax, float ay, float bx, float by, float cx, float cy) {
        boolean contains(float x, float y) {
            return cross(bx - ax, by - ay, cx - ax, cy - ay) > 0.0001f
                  && cross(bx - ax, by - ay, x - ax, y - ay) >= -0.0001f
                  && cross(cx - bx, cy - by, x - bx, y - by) >= -0.0001f
                  && cross(ax - cx, ay - cy, x - cx, y - cy) >= -0.0001f;
        }
    }
    private record Patch(int edge, float from, float to, boolean high) { }
    private record Key(Images ground, BitSet coverage, List<Patch> patches) { }

    private final Map<Key, Images> cache = new ConcurrentHashMap<>();
    private final Set<Key> used = ConcurrentHashMap.newKeySet();

    Images material(BoardScene scene, BoardScene.Tile tile, float floor, GpuAssets assets) {
        return material(scene, tile, floor, assets.inclineMask(), assets.highInclineMask());
    }

    /** CPU-only composition; asset decoding and cache ownership stay with the caller. */
    Images material(BoardScene scene, BoardScene.Tile tile, float floor,
          BoardScene.Pixels incline, BoardScene.Pixels highIncline) {
        Images ground = new Images(tile.ground(), tile.normals());
        if (tile.liquid().present()) { return ground; }
        BoardSurface surface = new BoardSurface(scene, tile);
        List<BoardSurface.Side> sides = surface.sides(scene, floor);
        if (sides.isEmpty()) { return ground; }
        Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
        int width = Math.max(ground.color().width(), (int) BoardGeometry.TILE_WIDTH);
        int height = Math.max(ground.color().height(), (int) BoardGeometry.TILE_HEIGHT);
        BitSet coverage = new BitSet(width * height);
        for (BoardSurface.Face face : surface.faces) {
            if (face.finish() == BoardSurface.Finish.TOP) {
                cover(coverage, new Triangle(local(face.a().x, center.x), local(face.a().y, center.y),
                      local(face.b().x, center.x), local(face.b().y, center.y),
                      local(face.c().x, center.x), local(face.c().y, center.y)), width, height);
            }
        }
        List<Patch> patches = new ArrayList<>();
        for (BoardSurface.Side side : sides) {
            Vector3 a = BoardGeometry.corner(tile.coords(), tile.elevation(), side.edge());
            Vector3 b = BoardGeometry.corner(tile.coords(), tile.elevation(), side.edge() + 1);
            Vector3 along = new Vector3(b).sub(a).nor();
            // Match the original clipped mesh: full edges extend into the corners; road mouths clip each end.
            float from = side.a().epsilonEquals(a, 0.02f) ? Float.NEGATIVE_INFINITY
                  : quantize(new Vector3(side.a()).sub(a).dot(along) / BoardGeometry.hexScale());
            float to = side.b().epsilonEquals(b, 0.02f) ? Float.POSITIVE_INFINITY
                  : quantize(new Vector3(side.b()).sub(a).dot(along) / BoardGeometry.hexScale());
            patches.add(new Patch(side.edge(), from, to, highDrop(scene, tile, side)));
        }
        Key key = new Key(ground, coverage, List.copyOf(patches));
        used.add(key);
        return cache.computeIfAbsent(key,
              ignored -> compose(key, incline, highIncline));
    }

    /** The incline split per edge. A board-edge drop has no adjacent hex and is judged by its own depth instead. */
    private static boolean highDrop(BoardScene scene, BoardScene.Tile tile, BoardSurface.Side side) {
        int direction = BoardGeometry.edgeDirection(side.edge());
        BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
        if (neighbor != null) {
            return high(tile, direction, tile.groundLevel() - neighbor.groundLevel());
        }
        return high(tile, direction,
              Math.round(Math.max(side.a().z - side.lowA(), side.b().z - side.lowB()) / BoardGeometry.level()));
    }

    /**
     * Deeper than an incline, or a drop the map marks as a cliff side (its manual cliff-top exit): the high rim, and in
     * the Tactical View a rock side.
     */
    static boolean high(BoardScene.Tile tile, int direction, int drop) {
        return drop > INCLINE_LEVELS || (tile.cliffTopExits() & (1 << direction)) != 0;
    }

    /**
     * The Tactical View's plain hex: its whole art, with the rim along every edge above a lower neighbour. Liquid
     * keeps its art, as in {@link #material}.
     */
    BoardScene.Pixels column(BoardScene scene, BoardScene.Tile tile, BoardScene.Pixels art,
          BoardScene.Pixels incline, BoardScene.Pixels highIncline) {
        List<Patch> patches = new ArrayList<>();
        int ramps = BoardSurface.ramps(scene, tile);
        for (int edge = 0; edge < 6; edge++) {
            int direction = BoardGeometry.edgeDirection(edge);
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
            int drop = neighbor == null ? 0 : tile.elevation() - neighbor.elevation();
            if (drop <= 0) { continue; }
            boolean high = high(tile, direction, drop);
            if ((ramps & 1 << direction) == 0) {
                patches.add(new Patch(edge, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, high));
                continue;
            }
            // A road ramps through the middle of this edge: its mouth stays clear, as the libGDX board's did.
            float middle = BoardGeometry.corner(tile.coords(), 0, edge).dst(BoardGeometry.corner(tile.coords(), 0, edge + 1))
                  / BoardGeometry.hexScale() / 2;
            patches.add(new Patch(edge, Float.NEGATIVE_INFINITY, quantize(middle - BoardSurface.ROAD_MOUTH), high));
            patches.add(new Patch(edge, quantize(middle + BoardSurface.ROAD_MOUTH), Float.POSITIVE_INFINITY, high));
        }
        if (patches.isEmpty() || tile.liquid().present()) { return art; }
        int width = Math.max(art.width(), (int) BoardGeometry.TILE_WIDTH);
        int height = Math.max(art.height(), (int) BoardGeometry.TILE_HEIGHT);
        BitSet coverage = new BitSet(width * height);
        coverage.set(0, width * height);
        Key key = new Key(new Images(art, null), coverage, List.copyOf(patches));
        used.add(key);
        return cache.computeIfAbsent(key, ignored -> compose(key, incline, highIncline)).color();
    }

    /** End of one terrain snapshot update; keep only combinations used by that snapshot. */
    void retainUsed() {
        cache.keySet().retainAll(used);
        used.clear();
    }

    void clear() {
        cache.clear();
        used.clear();
    }

    private static float local(float value, float center) {
        return quantize((value - center) / BoardGeometry.hexScale());
    }

    private static float quantize(float value) {
        // A thousandth of an artwork pixel removes world-coordinate roundoff from otherwise identical keys.
        return Math.round(value * 1024) / 1024f;
    }

    private static float cross(float ax, float ay, float bx, float by) {
        return ax * by - ay * bx;
    }

    /** Cache the exact painted texels, not thousands of ramp triangles that produced the same footprint. */
    private static void cover(BitSet coverage, Triangle face, int width, int height) {
        float minX = Math.min(face.ax(), Math.min(face.bx(), face.cx()));
        float maxX = Math.max(face.ax(), Math.max(face.bx(), face.cx()));
        float minY = Math.min(face.ay(), Math.min(face.by(), face.cy()));
        float maxY = Math.max(face.ay(), Math.max(face.by(), face.cy()));
        int fromX = Math.max(0, (int) Math.floor((minX * GROUND_UV_SCALE / BoardGeometry.TILE_WIDTH + .5f) * width - .5f));
        int toX = Math.min(width - 1, (int) Math.ceil((maxX * GROUND_UV_SCALE / BoardGeometry.TILE_WIDTH + .5f) * width - .5f));
        int fromY = Math.max(0, (int) Math.floor((.5f - maxY * GROUND_UV_SCALE / BoardGeometry.TILE_HEIGHT) * height - .5f));
        int toY = Math.min(height - 1, (int) Math.ceil((.5f - minY * GROUND_UV_SCALE / BoardGeometry.TILE_HEIGHT) * height - .5f));
        for (int y = fromY; y <= toY; y++) {
            for (int x = fromX; x <= toX; x++) {
                int pixel = y * width + x;
                if (coverage.get(pixel)) { continue; }
                float px = ((x + .5f) / width - .5f) * BoardGeometry.TILE_WIDTH / GROUND_UV_SCALE;
                float py = (.5f - (y + .5f) / height) * BoardGeometry.TILE_HEIGHT / GROUND_UV_SCALE;
                if (face.contains(px, py)) { coverage.set(pixel); }
            }
        }
    }

    private static Images compose(Key key, BoardScene.Pixels incline, BoardScene.Pixels high) {
        BoardScene.Pixels ground = key.ground().color();
        int width = Math.max(ground.width(), (int) BoardGeometry.TILE_WIDTH);
        int height = Math.max(ground.height(), (int) BoardGeometry.TILE_HEIGHT);
        BufferedImage color = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        BoardScene.Pixels baseNormal = key.ground().normal();
        // Custom artwork can be smaller than a rim. Keep its existing normals aligned with the enlarged color slot.
        BufferedImage normal = baseNormal != null && (baseNormal.width() != width || baseNormal.height() != height)
              ? new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB) : null;
        float[] albedo = new float[4];
        Vector3[] corners = new Vector3[6];
        Vector3[] along = new Vector3[6];
        var origin = new Coords(0, 0);
        Vector3 center = BoardGeometry.center(origin, 0);
        for (int edge = 0; edge < 6; edge++) {
            // Local artwork coordinates are independent of board scale and absolute tile position.
            corners[edge] = BoardGeometry.corner(origin, 0, edge).sub(center).scl(1 / BoardGeometry.hexScale());
        }
        for (int edge = 0; edge < 6; edge++) {
            along[edge] = new Vector3(corners[(edge + 1) % 6]).sub(corners[edge]);
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = texel(ground, x, y, width, height, albedo);
                float red = rgba >>> 24, green = rgba >>> 16 & 255, blue = rgba >>> 8 & 255;
                float px = ((x + 0.5f) / width - 0.5f) * BoardGeometry.TILE_WIDTH / GROUND_UV_SCALE;
                float py = (0.5f - (y + 0.5f) / height) * BoardGeometry.TILE_HEIGHT / GROUND_UV_SCALE;
                if (key.coverage().get(y * width + x)) {
                    int coveredEdges = 0;
                    for (Patch patch : key.patches()) {
                        if ((coveredEdges & (1 << patch.edge())) != 0) { continue; }
                        Vector3 a = corners[patch.edge()], edge = along[patch.edge()];
                        float length = edge.len();
                        float tx = edge.x / length, ty = edge.y / length;
                        float distance = (px - a.x) * -ty + (py - a.y) * tx;
                        float position = (px - a.x) * tx + (py - a.y) * ty;
                        if (distance > 26 || position < patch.from() || position > patch.to()) { continue; }
                        coveredEdges |= 1 << patch.edge();
                        BoardScene.Pixels rim = patch.high() ? high : incline;
                        float u = 0.25f + position / (2 * length);
                        float v = 1 - distance / BoardGeometry.TILE_HEIGHT;
                        sample(rim, u, v, albedo);
                        float alpha = albedo[3] / 255f * BLEND_OPACITY;
                        if (alpha <= 0) { continue; }
                        // Match libGDX: use the original mask's gray around 128, weighted by its alpha and opacity.
                        float shade = 1 + alpha * (albedo[0] / 128f - 1);
                        red *= shade;
                        green *= shade;
                        blue *= shade;
                    }
                }
                color.setRGB(x, y, ((rgba & 255) << 24) | (channel(red) << 16) | (channel(green) << 8) | channel(blue));
                if (normal != null) {
                    int packed = texel(baseNormal, x, y, width, height, albedo);
                    normal.setRGB(x, y, (packed >>> 8) | ((packed & 255) << 24));
                }
            }
        }
        return new Images(new BoardScene.Pixels(color), normal == null ? baseNormal : new BoardScene.Pixels(normal));
    }

    private static int channel(float value) {
        return Math.clamp(Math.round(value), 0, 255);
    }

    private static int texel(BoardScene.Pixels pixels, int x, int y, int width, int height, float[] scratch) {
        if (pixels.width() == width && pixels.height() == height) { return pixels.rgba(y * width + x); }
        sample(pixels, (x + 0.5f) / width, (y + 0.5f) / height, scratch);
        return Math.round(scratch[0]) << 24 | Math.round(scratch[1]) << 16 | Math.round(scratch[2]) << 8 | Math.round(scratch[3]);
    }

    /** Match GL linear sampling, including the texel-centre convention and clamp-to-edge addressing. */
    private static void sample(BoardScene.Pixels pixels, float u, float v, float[] out) {
        float x = Math.clamp(u * pixels.width() - 0.5f, 0, pixels.width() - 1);
        float y = Math.clamp(v * pixels.height() - 0.5f, 0, pixels.height() - 1);
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, pixels.width() - 1), y1 = Math.min(y0 + 1, pixels.height() - 1);
        int a = pixels.rgba(y0 * pixels.width() + x0), b = pixels.rgba(y0 * pixels.width() + x1);
        int c = pixels.rgba(y1 * pixels.width() + x0), d = pixels.rgba(y1 * pixels.width() + x1);
        for (int channel = 0; channel < 4; channel++) {
            int shift = 24 - 8 * channel;
            float top = ((a >>> shift) & 255) + (((b >>> shift) & 255) - ((a >>> shift) & 255)) * (x - x0);
            float bottom = ((c >>> shift) & 255) + (((d >>> shift) & 255) - ((c >>> shift) & 255)) * (x - x0);
            out[channel] = top + (bottom - top) * (y - y0);
        }
    }
}
