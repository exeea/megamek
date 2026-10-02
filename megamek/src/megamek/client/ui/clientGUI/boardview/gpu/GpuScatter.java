/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.nio.ByteOrder;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/** Tiny textured details, baked into a single opaque material per terrain chunk and shared prop page. */
final class GpuScatter {
    private static final int CELL = 256;
    private static final int BORDER = 32;
    private static final int WIDTH = 2 * CELL;
    private static final int HEIGHT = 3 * CELL;
    // The white fifth swatch preserves the bushes' authored bark and leaf colours in the same draw batch.
    private static final String[] MATERIALS = { "sculpt/granite", "sculpt/earth", "sculpt/sandstone", "foliage/fronds-palm" };

    private GpuScatter() { }

    /** Uploaded once and owned by GpuAssets; source height/coverage alpha never makes scatter transparent. */
    static Texture atlas(File root) {
        Pixmap atlas = new Pixmap(WIDTH, HEIGHT, Pixmap.Format.RGBA8888);
        try {
            atlas.setColor(Color.WHITE);
            atlas.fill();
            var pixels = atlas.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN);
            for (int index = 0; index < MATERIALS.length; index++) {
                Pixmap tile = new Pixmap(CELL - 2 * BORDER, CELL - 2 * BORDER, Pixmap.Format.RGBA8888);
                try {
                    FileHandle file = new FileHandle(new File(root, "textures/" + MATERIALS[index] + ".png"));
                    tile.setBlending(Pixmap.Blending.None);
                    if (file.exists()) {
                        Pixmap source = new Pixmap(file);
                        try {
                            tile.setFilter(Pixmap.Filter.BiLinear);
                            tile.drawPixmap(source, 0, 0, source.getWidth(), source.getHeight(),
                                  0, 0, tile.getWidth(), tile.getHeight());
                        } finally { source.dispose(); }
                    } else {
                        tile.setColor(index >= 3 ? Color.WHITE : Color.GRAY);
                        tile.fill();
                    }
                    for (int y = 0; y < CELL; y++) {
                        pixels.position(((index / 2 * CELL + y) * WIDTH + index % 2 * CELL) * Integer.BYTES);
                        for (int x = 0; x < CELL; x++) {
                            pixels.putInt(tile.getPixel(Math.clamp(x - BORDER, 0, tile.getWidth() - 1),
                                  Math.clamp(y - BORDER, 0, tile.getHeight() - 1)) | 255);
                        }
                    }
                } finally { tile.dispose(); }
            }
            Texture texture = new Texture(atlas, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            // The 32-pixel extruded border still occupies a full texel at the last sampled mip level.
            texture.bind();
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 5);
            return texture;
        } finally { atlas.dispose(); }
    }

    /** Tiny stones use only the scatter kit's eight-triangle meshes. */
    static BoardShape rock(BoardScene.Tile tile, BoardScene.Feature feature) {
        return BoardScatter.rock(tile.surface(), Math.round(feature.rotation()), feature.asset().equals("scatter-slab"));
    }

    static float diameter(BoardScene.Feature feature) {
        if (feature.asset().equals("scatter-plant")) {
            BoardShape shape = BoardScatter.plant(BoardScene.Surface.GRASS);
            return (float) Math.hypot(2 * BoardScatter.BUSH_RADIUS, shape.height())
                  * BoardScatter.bushScale(shape, feature.scale());
        }
        return (float) Math.hypot(8 * feature.scale() * BoardGeometry.hexScale(),
              feature.height() * BoardGeometry.level());
    }

    static void build(MeshPartBuilder mesh, BoardScene.Tile tile, BoardSurface surface, BoardScene.Feature feature) {
        if (!BoardScatter.allowed(tile)) { return; }
        BoardShape bush = feature.asset().equals("scatter-plant") ? BoardScatter.plant(tile.surface()) : null;
        float bushScale = bush == null ? 0 : BoardScatter.bushScale(bush, feature.scale());
        // On the hex's own ground, never over a receding rim or a transition's slope.
        float[] spot = surface.relief.settle(BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale(),
              BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale(),
              bush == null ? BoardRelief.metres(.3f) : BoardScatter.BUSH_RADIUS * bushScale);
        float x = spot[0], y = spot[1];
        Matrix4 transform = new Matrix4().setToTranslation(x, y, surface.height(x, y))
              .rotate(Vector3.Z, feature.rotation())
              .scale(feature.scale() * BoardGeometry.hexScale(), feature.scale() * BoardGeometry.hexScale(),
                    feature.height() * BoardGeometry.level());
        Color color = color(tile.surface(), feature.asset());
        float shade = .88f + .24f * feature.rotation() / 360;
        color.mul(shade, shade, shade, 1);
        int material = feature.asset().equals("scatter-rock") || feature.asset().equals("scatter-slab")
              ? switch (tile.surface()) { case DIRT -> 1; case SAND -> 2; default -> 0; } : 3;
        BoardShape shape;
        switch (feature.asset()) {
            case "scatter-grass", "scatter-dry-grass" -> {
                shape = BoardScatter.shape("grass");
            }
            case "scatter-plant" -> {
                shape = bush;
                transform.setToTranslation(x, y, surface.height(x, y) - .02f * bushScale)
                      .rotate(Vector3.Z, feature.rotation()).scale(bushScale, bushScale, bushScale);
                material = 4;
            }
            case "scatter-rock", "scatter-slab" -> {
                shape = rock(tile, feature);
                float height = feature.asset().equals("scatter-slab") ? .4f : 1;
                // Keep the entire open base below ground, with the same footprint and summit as before.
                transform.translate(0, 0, -.08f).scale(6, 4.8f, (height + .08f) / shape.height());
            }
            default -> throw new IllegalArgumentException("Unknown terrain scatter: " + feature.asset());
        }
        float radius = 0, low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (var polygon : shape.polygons()) {
            for (var point : polygon.points()) {
                Vector3 world = new Vector3(point).mul(transform);
                radius = Math.max(radius, (float) Math.hypot(world.x - x, world.y - y));
                low = Math.min(low, world.z);
                high = Math.max(high, world.z);
            }
        }
        Vector3 base = new Vector3(x, y, low);
        if (!surface.relief.visibleScatter(base, high - low)
              || surface.relief.obstructed(base, radius, high - low)) { return; }
        for (BoardShape.Polygon polygon : shape.polygons()) {
            Color tint = material == 4 ? new Color(polygon.color()).mul(shade, shade, shade, 1) : color;
            triangle(mesh, polygon, transform, tint, material, shape.height());
        }
    }

    private static Color color(BoardScene.Surface surface, String asset) {
        if (asset.equals("scatter-dry-grass")) {
            return new Color(.88f, .71f, .24f, 1);
        }
        if (asset.equals("scatter-grass")) {
            return surface == BoardScene.Surface.SAND ? new Color(.28f, .60f, .20f, 1)
                  : new Color(.39f, .56f, .20f, 1);
        }
        // The atlas supplies the stone's colour; retain only a light biome tint in the vertices.
        return switch (surface) {
            case SAND, DIRT -> new Color(.95f, .95f, .95f, 1);
            case SNOW -> new Color(.95f, .98f, 1, 1);
            case ROCK -> new Color(.95f, .94f, .91f, 1);
            default -> new Color(.90f, .95f, .83f, 1);
        };
    }

    private static void triangle(MeshPartBuilder mesh, BoardShape.Polygon polygon, Matrix4 transform, Color color,
          int material, float height) {
        Vector3[] points = polygon.points();
        Vector3 a = points[0].cpy().mul(transform), b = points[1].cpy().mul(transform), c = points[2].cpy().mul(transform);
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).nor();
        // Project in object space, so turning a stone also turns its grain. Each blade spans the foliage swatch.
        Vector3 face = polygon.normal();
        boolean top = material != 3 && Math.abs(face.z) >= Math.max(Math.abs(face.x), Math.abs(face.y));
        boolean alongY = !top && Math.abs(face.x) > Math.abs(face.y);
        float low = -.5f, span = 1;
        if (material == 3) {
            low = Float.POSITIVE_INFINITY;
            float high = Float.NEGATIVE_INFINITY;
            for (Vector3 point : points) {
                float u = alongY ? point.y : point.x;
                low = Math.min(low, u);
                high = Math.max(high, u);
            }
            span = Math.max(.0001f, high - low);
        }
        float interior = CELL - 2 * BORDER - 1;
        Vector3[] world = { a, b, c };
        MeshPartBuilder.VertexInfo[] vertices = new MeshPartBuilder.VertexInfo[3];
        for (int i = 0; i < points.length; i++) {
            Vector3 local = points[i];
            float u = ((alongY ? local.y : local.x) - low) / span;
            float v = top ? local.y + .5f : local.z / height;
            vertices[i] = new MeshPartBuilder.VertexInfo().setPos(world[i]).setNor(normal).setCol(color).setUV(
                  (material % 2 * CELL + BORDER + .5f + Math.clamp(u, 0, 1) * interior) / WIDTH,
                  (material / 2 * CELL + BORDER + .5f + Math.clamp(v, 0, 1) * interior) / HEIGHT);
        }
        mesh.triangle(vertices[0], vertices[1], vertices[2]);
    }
}
