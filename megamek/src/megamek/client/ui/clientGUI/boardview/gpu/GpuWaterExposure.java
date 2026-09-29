/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.Coords;

/** Coarse directional wind fetch. Four linear sweeps, rebuilt only when water coverage or elevations change. */
final class GpuWaterExposure implements Disposable {
    private static final int LAND = Integer.MIN_VALUE, OUTSIDE = Integer.MAX_VALUE;
    private static final float FETCH_METRES = 240;
    private List<BoardScene.Tile> previous;
    private int[] levels;
    private Texture texture;
    private int width, height;
    final float[] mapping = new float[4];

    Texture texture() { return texture; }

    void update(BoardScene scene) {
        if (texture != null && (scene == null || previous == scene.tiles())) { return; }
        int w = scene == null ? 1 : scene.width() + 2, h = scene == null ? 1 : scene.height() * 2 + 4;
        int[] next = new int[w * h];
        Arrays.fill(next, OUTSIDE);
        if (scene != null) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int col = x - 1, row = Math.round((y - 1 - (col & 1)) * .5f);
                    BoardScene.Tile tile = scene.tile(new Coords(col, row));
                    if (tile == null) { continue; }
                    boolean open = tile.liquid().present() && !tile.liquid().molten() && !tile.frozen();
                    // The canonical rounded drop handles its own flow. Suppress wind swell smoothly before it.
                    for (int d = 0; open && d < 6; d++) {
                        var neighbor = scene.tile(tile.coords().translated(d));
                        if (neighbor != null && tile.liquid().connects(neighbor.liquid())
                              && neighbor.elevation() != tile.elevation()) { open = false; }
                    }
                    next[y * w + x] = open ? tile.elevation() : LAND;
                }
            }
        }
        previous = scene == null ? null : scene.tiles();
        if (texture != null && w == width && h == height && Arrays.equals(next, levels)) { return; }
        float stepX = BoardGeometry.width() * .75f, stepY = BoardGeometry.height() * .5f;
        byte[] fetch = fetch(next, w, h, stepX / BoardRelief.metres(1), stepY / BoardRelief.metres(1));
        var pixels = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        try {
            pixels.getPixels().put(fetch).flip();
            if (texture != null) { texture.dispose(); }
            texture = new Texture(pixels);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        } finally { pixels.dispose(); }
        // Texel centres are at each column centre, with two rows per hex (odd columns offset one row).
        float firstX = BoardGeometry.width() * .5f - stepX;
        float firstY = -BoardGeometry.height() * .5f + stepY;
        mapping[0] = 1 / (stepX * w); mapping[1] = -1 / (stepY * h);
        mapping[2] = (.5f - firstX / stepX) / w; mapping[3] = (.5f + firstY / stepY) / h;
        levels = next; width = w; height = h;
    }

    /** RG fetch from west/east, BA from south/north. The fixed board-edge water section stays undisplaced. */
    static byte[] fetch(int[] levels, int width, int height, float stepX, float stepY) {
        byte[] result = new byte[levels.length * 4];
        for (int channel = 0; channel < 4; channel++) {
            boolean reverse = channel == 1 || channel == 2;
            int stride = channel < 2 ? 1 : width;
            float step = channel < 2 ? stepX : stepY;
            float[] distance = new float[levels.length];
            for (int scan = 0; scan < levels.length; scan++) {
                int i = reverse ? levels.length - 1 - scan : scan;
                int from = i + (reverse ? stride : -stride);
                boolean edge = channel < 2 ? (reverse ? i % width == width - 1 : i % width == 0)
                      : from < 0 || from >= levels.length;
                if (levels[i] != OUTSIDE && levels[i] != LAND) {
                    distance[i] = edge ? 0 : levels[from] == levels[i]
                          ? Math.min(FETCH_METRES, distance[from] + step) : 0;
                }
                result[i * 4 + channel] = (byte) Math.round(distance[i] * 255 / FETCH_METRES);
            }
        }
        return result;
    }

    @Override
    public void dispose() { if (texture != null) { texture.dispose(); } texture = null; previous = null; levels = null; }
}
