/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;

/** One nearest-filtered texel per board hex. GL ownership and publication stay on the terrain render thread. */
final class GpuBiomeSurface implements Disposable {
    private List<BoardScene.Tile> previous;
    private int[] pixels;
    private Texture texture;
    private int width, height;
    private boolean active;

    Texture texture() { return texture; }
    int width() { return active ? width : 0; }
    int height() { return active ? height : 0; }

    void update(BoardScene scene) {
        if (texture != null && (scene == null || previous == scene.tiles())) { return; }
        int w = scene == null ? 1 : scene.width(), h = scene == null ? 1 : scene.height();
        int[] next = new int[w * h];
        boolean any = false;
        int palettes = 0;
        if (scene != null) {
            for (var tile : scene.tiles()) {
                var kind = BoardBiome.kind(tile);
                // R is Biome, G is aqueous palette + 1 (zero excludes dry/frozen/molten tiles), A is elevation + 64.
                int color = kind.ordinal() << 24;
                if (tile.liquid().present() && !tile.liquid().molten() && !tile.frozen()) {
                    int palette = GpuWaterShader.palette(tile.liquid());
                    color |= (palette + 1) << 16;
                    palettes |= 1 << palette;
                }
                any |= kind != BoardScene.Biome.NONE;
                next[tile.coords().getY() * w + tile.coords().getX()] = color | Math.clamp(tile.elevation() + 64, 0, 255);
            }
        }
        previous = scene == null ? null : scene.tiles();
        active = any || Integer.bitCount(palettes) > 1;
        if (texture != null && w == width && h == height && Arrays.equals(next, pixels)) { return; }
        var map = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        try {
            map.setBlending(Pixmap.Blending.None);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) { map.drawPixel(x, y, next[y * w + x]); }
            }
            if (texture == null || w != width || h != height) {
                if (texture != null) { texture.dispose(); }
                texture = new Texture(map);
                texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            } else { texture.draw(map, 0, 0); }
            pixels = next; width = w; height = h;
        } finally { map.dispose(); }
    }

    @Override
    public void dispose() {
        if (texture != null) { texture.dispose(); }
        texture = null; previous = null; pixels = null; active = false;
    }
}
