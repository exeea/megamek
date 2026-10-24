/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.util.List;

import com.badlogic.gdx.graphics.Color;

/** A soft soil coat under the accepted mineral clusters, carried by the existing terrain triangles and material pass. */
final class GpuMineralGround {
    record Paint(GpuRoads.Patch patch, GpuRoads.MaskData mask, List<BoardTacticalGeometry.Triangle> triangles) { }

    private GpuMineralGround() { }

    /** The soil and retained vegetation snapshot use the rendered models' placement decision. */
    static List<BoardScene.Feature> accepted(BoardScene scene, BoardScene.Tile tile, BoardSurface surface) {
        if (surface == null || tile.backdrop().present() || tile.liquid().present()) { return List.of(); }
        return tile.features().stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.METAL
                    && BoardMetalDeposits.accepts(scene, tile, surface, feature)).toList();
    }

    static Paint prepare(BoardScene scene, BoardScene.Tile tile, BoardSurface surface) {
        List<BoardScene.Feature> deposits = accepted(scene, tile, surface);
        if (deposits.isEmpty()) { return null; }
        Area shape = new Area();
        for (var feature : deposits) {
            float radius = feature.scale() * .7f;
            shape.add(new Area(new Ellipse2D.Float(feature.x() - radius, feature.y() - radius, radius * 2, radius * 2)));
        }
        // A halo on other ground: earth's grain at 6 m, finer than earth ground's 20 m.
        var patch = new GpuRoads.Patch(shape, "biome-soil", new Color(.88f, .75f, .62f, 1),
              .3f, .02f, new GpuRoads.Fade(0, 0, 0));
        var triangles = GpuRoads.drape(tile, surface, patch);
        if (triangles.isEmpty()) { return null; }
        var mask = GpuRoads.mask(patch, (x, y, scratch) ->
              scratch.set(patch.tint()).mul(1, 1, 1, coverage(deposits, x, y)));
        return new Paint(patch, mask, triangles);
    }

    /** Local model coordinates; overlapping deposits form one soil patch rather than stacking darker coats. */
    static float coverage(List<BoardScene.Feature> deposits, float x, float y) {
        float result = 0;
        for (var feature : deposits) {
            float angle = (float) Math.toRadians(feature.rotation()), c = (float) Math.cos(angle), s = (float) Math.sin(angle);
            float dx = x - feature.x(), dy = y - feature.y();
            float along = dx * c + dy * s, across = (-dx * s + dy * c) / .82f;
            float radius = feature.scale() * .65f;
            float distance = (float) Math.sqrt(along * along + across * across) / radius;
            float grain = BoardRelief.noise(x * .43f + feature.x(), y * .43f + feature.y()) - .5f;
            float edge = 1 - BoardRelief.smooth((distance + grain * .12f - .48f) / .52f);
            result = Math.max(result, edge * .9f);
        }
        return result;
    }
}
