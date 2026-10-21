/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Terrain-owned mineral GLB placements; no movement terrain or gameplay occupancy is invented. */
final class BoardMetalDeposits {
    static final int VARIANTS = 4;
    private static final float SIZE = 1.5f;
    private BoardMetalDeposits() { }

    /** Increasing content exposes more of the same deterministic mineral deposit, including level-zero markers. */
    static List<BoardScene.Feature> capture(Hex hex, Coords coords) {
        if (!hex.containsTerrain(Terrains.METAL_CONTENT)) { return List.of(); }
        int content = Math.max(0, hex.terrainLevel(Terrains.METAL_CONTENT));
        Random random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L ^ 0x4D4554414CL);
        List<BoardScene.Feature> result = new ArrayList<>();
        for (int index = 0; index < 3 + Math.min(content, 10) / 4; index++) {
            float scale = (7 + random.nextFloat() * 3) * SIZE, x = 0, y = 0;
            for (int attempt = 0; index > 0 && attempt < 24; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                float radius = (6 + random.nextFloat() * 9) * SIZE;
                x = (float) Math.cos(angle) * radius;
                y = (float) Math.sin(angle) * radius * .85f;
                boolean separate = true;
                for (var previous : result) {
                    if (Math.hypot(x - previous.x(), y - previous.y()) < (scale + previous.scale()) * .42f) {
                        separate = false;
                        break;
                    }
                }
                if (separate) { break; }
            }
            result.add(new BoardScene.Feature("minerals/deposit-" + random.nextInt(VARIANTS), x, y,
                  random.nextFloat() * 360, scale, (.15f + random.nextFloat() * .10f) * SIZE, 0, BoardScene.FeatureKind.METAL));
        }
        return List.copyOf(result);
    }

    /** One placement decision serves both the physical GLB and its softly blended mineral-rich ground. */
    static boolean accepts(BoardScene scene, BoardScene.Tile tile, BoardSurface surface, BoardScene.Feature feature) {
        float x = BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale();
        float y = BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale();
        float radius = feature.scale() * .72f * BoardGeometry.hexScale();
        return BoardRoad.distance(BoardRoad.nearbyWays(scene, tile), x, y)
              >= radius / BoardGeometry.hexScale() + BoardRoad.SHOULDER
              && !surface.relief.obstructed(new Vector3(x, y, surface.height(x, y)), radius,
                    feature.height() * BoardGeometry.level());
    }

}
