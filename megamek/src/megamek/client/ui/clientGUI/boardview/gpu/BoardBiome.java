/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.board.Coords;

/** Bounded visual fields in world metres, shared by vegetation placement and the ground shader. */
final class BoardBiome {
    static final float EDGE_METRES = 2.2f;
    static final float ROW_METRES = 1.15f;
    static final float ROW_X = .9396926f, ROW_Y = .3420201f;

    private BoardBiome() { }

    static BoardScene.Biome kind(BoardScene.Tile tile) {
        return tile != null && tile.detailedGround() && !tile.frozen() && !tile.liquid().present()
              && tile.features().stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.BUILDING)
              ? tile.biome() : BoardScene.Biome.NONE;
    }

    static float row(float x, float y) { return (x * ROW_X + y * ROW_Y) / ROW_METRES; }

    /** Same integer hash/value noise as biomeNoise in terrain-biome.glsl; no tile-local seed or phase. */
    static float wetness(float x, float y) {
        float px = x + 1.3f * (BoardRelief.noise(x / 4 + 31, y / 4) - .5f);
        float py = y + 1.3f * (BoardRelief.noise(x / 4, y / 4 - 23) - .5f);
        return .55f * BoardRelief.noise(px / 5.7f, py / 5.7f)
              + .30f * BoardRelief.noise(px / 2 + 19, py / 2 - 7) + .15f * BoardRelief.noise(px / .65f, py / .65f);
    }

    /** All candidates, including ordinary land, compete. Adjacent matching tiles have no internal fade. */
    static float coverage(BoardScene scene, BoardScene.Biome kind, float x, float y, float z) {
        float width = BoardRelief.metres(EDGE_METRES);
        int col = (int) Math.floor(x / (BoardGeometry.width() * .75f));
        float sum = 0, covered = 0;
        for (int dx = -1; dx <= 1; dx++) {
            int cx = col + dx;
            int row = (int) Math.floor(-y / BoardGeometry.height() - (cx & 1) * .5f);
            for (int dy = -1; dy <= 1; dy++) {
                var coords = new Coords(cx, row + dy);
                float px = Math.abs(x - BoardGeometry.centerX(coords)), py = Math.abs(y - BoardGeometry.centerY(coords));
                float a = BoardGeometry.height() / 2, b = BoardGeometry.width() / 4;
                float distance = Math.max(py - a, (a * px + b * py - BoardGeometry.width() * BoardGeometry.height() / 4)
                      / (float) Math.sqrt(a * a + b * b));
                float w = 1 - BoardRelief.smooth((distance + width) / (2 * width));
                sum += w;
                var tile = scene.tile(coords);
                if (kind(tile) == kind && tile != null) {
                    float height = Math.abs(z - BoardGeometry.groundZ(tile)) / BoardRelief.metres(1);
                    covered += w * (1 - BoardRelief.smooth((height - .15f) / 1.1f));
                }
            }
        }
        return sum > 0 ? covered / sum : 0;
    }
}
