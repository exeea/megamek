/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/** Tiny untextured details, baked into a single opaque mesh per terrain chunk. */
final class GpuScatter {
    private GpuScatter() { }

    /** Tiny stones use only the scatter kit's eight-triangle meshes. */
    static BoardRocks.Rock rock(BoardScene.Tile tile, BoardScene.Feature feature) {
        return BoardRocks.scatter(tile.surface(), Math.round(feature.rotation()), feature.asset().equals("scatter-slab"));
    }

    static float diameter(BoardScene.Feature feature) {
        return (float) Math.hypot(8 * feature.scale() * BoardGeometry.hexScale(),
              feature.height() * BoardGeometry.level());
    }

    static void build(MeshPartBuilder mesh, BoardScene.Tile tile, BoardSurface surface, BoardScene.Feature feature) {
        // On the hex's own ground, never over a receding rim or a transition's slope.
        float[] spot = surface.relief.settle(BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale(),
              BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale(), BoardRelief.metres(.3f));
        float x = spot[0], y = spot[1];
        Matrix4 transform = new Matrix4().setToTranslation(x, y, surface.height(x, y))
              .rotate(Vector3.Z, feature.rotation())
              .scale(feature.scale() * BoardGeometry.hexScale(), feature.scale() * BoardGeometry.hexScale(),
                    feature.height() * BoardGeometry.level());
        Color color = color(tile.surface(), feature.asset());
        float shade = .88f + .24f * feature.rotation() / 360;
        color.mul(shade, shade, shade, 1);
        BoardRocks.Rock shape;
        switch (feature.asset()) {
            case "scatter-grass", "scatter-dry-grass" -> {
                shape = BoardRocks.scatter("grass");
            }
            case "scatter-plant" -> {
                shape = BoardRocks.scatter("plant");
            }
            case "scatter-rock", "scatter-slab" -> {
                shape = rock(tile, feature);
                float height = feature.asset().equals("scatter-slab") ? .4f : 1;
                // Keep the entire open base below ground, with the same footprint and summit as before.
                transform.translate(0, 0, -.08f).scale(6, 4.8f, (height + .08f) / shape.height());
            }
            default -> throw new IllegalArgumentException("Unknown terrain scatter: " + feature.asset());
        }
        for (BoardRocks.Polygon polygon : shape.polygons()) {
            Vector3[] points = polygon.points();
            triangle(mesh, points[0].cpy().mul(transform), points[1].cpy().mul(transform),
                  points[2].cpy().mul(transform), color);
        }
    }

    private static Color color(BoardScene.Surface surface, String asset) {
        if (asset.equals("scatter-dry-grass")) {
            return new Color(.76f, .60f, .18f, 1);
        }
        if (asset.equals("scatter-grass") || asset.equals("scatter-plant")) {
            return surface == BoardScene.Surface.SAND ? new Color(.23f, .51f, .16f, 1)
                  : asset.equals("scatter-grass") ? new Color(.32f, .47f, .16f, 1) : new Color(.12f, .32f, .10f, 1);
        }
        return switch (surface) {
            case SAND -> new Color(.61f, .49f, .33f, 1);
            case DIRT -> new Color(.47f, .32f, .24f, 1);
            case SNOW -> new Color(.65f, .66f, .67f, 1);
            case ROCK -> new Color(.49f, .48f, .45f, 1);
            default -> new Color(.43f, .45f, .38f, 1);
        };
    }

    private static void triangle(MeshPartBuilder mesh, Vector3 a, Vector3 b, Vector3 c, Color color) {
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).nor();
        mesh.triangle(vertex(a, normal, color), vertex(b, normal, color), vertex(c, normal, color));
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Vector3 normal, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setCol(color).setUV(0, 0);
    }
}
