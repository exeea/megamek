/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.Coords;

/** One nearest-filtered texel per board hex. GL ownership and publication stay on the terrain render thread. */
final class GpuBiomeSurface implements Disposable {
    /** The biome bits of a texel; R bits 4..7 above them flag the stencil quarters. */
    private static final int BIOME = 0x0f000000;
    /**
     * In metres, as terrain-biome-mask.glsl: biome weights reach 14 m beyond a hex outline, a palette 5 m beyond it from
     * a position the noise moves up to 3 m along each axis. The margin covers float rounding at quarter edges.
     */
    private static final float COVER_REACH = 14, LIQUID_REACH = 5, LIQUID_WANDER = 3, MARGIN = .05f;
    private List<BoardScene.Tile> previous;
    private int[] pixels;
    private Texture texture;
    private int width, height;
    private boolean active, iced;

    Texture texture() { return texture; }
    int width() { return active ? width : 0; }
    int height() { return active ? height : 0; }
    /** The board size where any hex shows ice, else zero: ice reads the texture without enabling the biomes. */
    int iceWidth() { return iced ? width : 0; }
    int iceHeight() { return iced ? height : 0; }

    void update(BoardScene scene) {
        if (texture != null && (scene == null || previous == scene.tiles())) { return; }
        int w = scene == null ? 1 : scene.width(), h = scene == null ? 1 : scene.height();
        int[] next = new int[w * h];
        boolean any = false, anyIce = false;
        int palettes = 0;
        if (scene != null) {
            for (var tile : scene.tiles()) {
                var kind = BoardBiome.kind(tile);
                // R is Biome, plus the stencil's reached quarters (below); G is aqueous palette + 1 (zero excludes
                // dry/frozen/molten tiles), B is the ice shown (1 frozen land, 2 frozen water, 3 detected black ice),
                // A is elevation + 64.
                int color = kind.ordinal() << 24;
                if (tile.liquid().present() && !tile.liquid().molten() && !tile.frozen()) {
                    int palette = GpuWaterShader.palette(tile.liquid());
                    color |= (palette + 1) << 16;
                    palettes |= 1 << palette;
                }
                boolean ice = tile.frozen() || tile.blackIce();
                color |= (!tile.frozen() ? tile.blackIce() ? 3 : 0 : tile.liquid().present() ? 2 : 1) << 8;
                any |= kind != BoardScene.Biome.NONE;
                anyIce |= ice;
                next[tile.coords().getY() * w + tile.coords().getX()] = color | Math.clamp(tile.elevation() + 64, 0, 255);
            }
            // The nine-hex stencil (terrain-biome-mask.glsl) only changes a fragment that a biome hex, or liquids of two
            // palettes, can reach: water of a single palette already takes that palette. Only hexes within two rings
            // of either can be reached; R bits 4..7 of their texels flag the reached quarters of their stencil cells,
            // and every other fragment skips the stencil after reading its own hex's texel.
            boolean[] nearBiome = new boolean[w * h];
            int[] palettesNear = new int[w * h];
            for (var tile : scene.tiles()) {
                int color = next[tile.coords().getY() * w + tile.coords().getX()];
                boolean biome = (color & BIOME) != 0;
                int liquid = (color >>> 16) & 0xff;
                if (!biome && liquid == 0) { continue; }
                for (Coords near : tile.coords().allAtDistanceOrLess(2)) {
                    if (inside(near, w, h)) {
                        int index = near.getY() * w + near.getX();
                        nearBiome[index] |= biome;
                        if (liquid != 0) { palettesNear[index] |= 1 << liquid; }
                    }
                }
            }
            for (int index = 0; index < next.length; index++) {
                if (nearBiome[index] || Integer.bitCount(palettesNear[index]) > 1) {
                    next[index] |= quarters(next, w, h, new Coords(index % w, index / w)) << 28;
                }
            }
        }
        previous = scene == null ? null : scene.tiles();
        active = any || Integer.bitCount(palettes) > 1;
        iced = anyIce;
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

    /**
     * Bits 0..3: the quarters of the hex's stencil cell (left/right half of its column, upper/lower half of its row,
     * as terrain-biome-mask.glsl divides them) that a biome or two palettes reach. The hexes within two rings include
     * every hex of the stencil anywhere in the cell.
     */
    private static int quarters(int[] colors, int w, int h, Coords hex) {
        float width = BoardGeometry.width(), height = BoardGeometry.height(), metre = BoardRelief.metres(1);
        List<Coords> near = hex.allAtDistanceOrLess(2);
        int bits = 0;
        for (int quarter = 0; quarter < 4; quarter++) {
            float left = (hex.getX() + (quarter & 1) * .5f) * width * .75f, right = left + width * .375f;
            float top = -(hex.getY() + (quarter >> 1) * .5f + (hex.getX() & 1) * .5f) * height, bottom = top - height * .5f;
            boolean covered = false;
            int palettes = 0;
            for (Coords coords : near) {
                if (!inside(coords, w, h)) { continue; }
                int color = colors[coords.getY() * w + coords.getX()];
                float x = BoardGeometry.centerX(coords), y = BoardGeometry.centerY(coords);
                if ((color & BIOME) != 0 && distance(left, right, bottom, top, x, y, MARGIN * metre)
                      < (COVER_REACH + MARGIN) * metre) {
                    covered = true;
                    break;
                }
                int liquid = (color >>> 16) & 0xff;
                if (liquid != 0 && distance(left, right, bottom, top, x, y, (LIQUID_WANDER + MARGIN) * metre)
                      < (LIQUID_REACH + MARGIN) * metre) { palettes |= 1 << liquid; }
            }
            if (covered || Integer.bitCount(palettes) > 1) { bits |= 1 << quarter; }
        }
        return bits;
    }

    /**
     * The stencil's least distance from the outline of the hex centred at (x, y) to the rectangle grown by {@code grow}.
     * It grows with each absolute offset, so the rectangle's point nearest the centre on each axis gives it.
     */
    private static float distance(float left, float right, float bottom, float top, float x, float y, float grow) {
        return BoardBiome.hexDistance(Math.max(0, Math.max(left - grow - x, x - right - grow)),
              Math.max(0, Math.max(bottom - grow - y, y - top - grow)));
    }

    private static boolean inside(Coords coords, int w, int h) {
        return coords.getX() >= 0 && coords.getY() >= 0 && coords.getX() < w && coords.getY() < h;
    }

    @Override
    public void dispose() {
        if (texture != null) { texture.dispose(); }
        texture = null; previous = null; pixels = null; active = false; iced = false;
    }
}
