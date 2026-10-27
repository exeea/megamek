/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** CPU cliff-top lightness masks on the Classic columns, shared by the bounded terrain workers. Art stays immutable. */
final class BoardRim {
    static final float BLEND_OPACITY = 0.7f;
    static final float GROUND_UV_SCALE = 0.96f; // MUST NOT TOUCH!!! With 1.0f we have some black pixels in the textures around the borders!
    /** The board's own incline split: a drop of up to two levels is an incline, anything deeper a high incline. */
    private static final int INCLINE_LEVELS = 2;

    private record Patch(int edge, float from, float to, boolean high) { }
    private record Key(BoardScene.Pixels art, List<Patch> patches) { }

    private final Map<Key, BoardScene.Pixels> cache = new ConcurrentHashMap<>();
    private final Set<Key> used = ConcurrentHashMap.newKeySet();

    /**
     * Deeper than an incline, or a drop the map marks as a cliff side (its manual cliff-top exit): the high rim, and in
     * the Tactical View a rock side.
     */
    static boolean high(BoardScene.Tile tile, int direction, int drop) {
        return drop > INCLINE_LEVELS || (tile.cliffTopExits() & (1 << direction)) != 0;
    }

    /**
     * The Classic column's plain hex: its whole art, with the rim along every edge above a lower neighbour. Liquid
     * keeps its art. Cliffs get no stroke here: the Tactical View's ground layer strokes them (GpuHexGrid), in both
     * graphics (b11-scope).
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
            float mouth = BoardSurface.roadMouth(tile, neighbor, direction);
            patches.add(new Patch(edge, Float.NEGATIVE_INFINITY, quantize(middle - mouth), high));
            patches.add(new Patch(edge, quantize(middle + mouth), Float.POSITIVE_INFINITY, high));
        }
        if (patches.isEmpty() || tile.liquid().present()) { return art; }
        Key key = new Key(art, List.copyOf(patches));
        used.add(key);
        return cache.computeIfAbsent(key, ignored -> compose(key, incline, highIncline));
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

    private static float quantize(float value) {
        // A thousandth of an artwork pixel removes world-coordinate roundoff from otherwise identical keys.
        return Math.round(value * 1024) / 1024f;
    }

    private static BoardScene.Pixels compose(Key key, BoardScene.Pixels incline, BoardScene.Pixels high) {
        BoardScene.Pixels art = key.art();
        int width = Math.max(art.width(), (int) Math.ceil(BoardGeometry.TILE_WIDTH));
        int height = Math.max(art.height(), (int) Math.ceil(BoardGeometry.TILE_HEIGHT));
        BufferedImage color = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
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
                int rgba = texel(art, x, y, width, height, albedo);
                float red = rgba >>> 24, green = rgba >>> 16 & 255, blue = rgba >>> 8 & 255;
                float px = ((x + 0.5f) / width - 0.5f) * BoardGeometry.TILE_WIDTH / GROUND_UV_SCALE;
                float py = (0.5f - (y + 0.5f) / height) * BoardGeometry.TILE_HEIGHT / GROUND_UV_SCALE;
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
                color.setRGB(x, y, ((rgba & 255) << 24) | (channel(red) << 16) | (channel(green) << 8) | channel(blue));
            }
        }
        return new BoardScene.Pixels(color);
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
