/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.Coords;

/** Small persistent grass meshes. Travel retains nearby cover; zoom scales blades continuously into the ground. */
final class GpuGroundCover implements Disposable {
    static final class Wind extends FloatAttribute {
        static final long TYPE = register("boardVegetationWind");
        Wind() { super(TYPE, 1); }
        @Override
        public Wind copy() { return new Wind(); }
    }

    private static final int CACHE_SIZE = 384;
    private static final class Cover {
        final BoardSurface.Key key;
        final ModelInstance instance;
        long generation;
        Cover(BoardSurface.Key key, ModelInstance instance, long generation) {
            this.key = key;
            this.instance = instance;
            this.generation = generation;
        }
    }
    private final Map<Coords, Cover> models = new LinkedHashMap<>(64, .75f, true);
    private int revision = -1;
    private int boardId = -1;
    private List<BoardScene.Tile> tiles;
    /** The board's floor for the current tiles, which transition slopes are built against. */
    private float floor;
    private long generation;

    static String vertex(String source) {
        return source.replace("void main() {", "attribute vec2 a_texCoord0;\nuniform vec3 u_wind;\nuniform float u_rainTime;\n"
                    + "uniform float u_worldMetre;\nuniform float u_coverFade;\n"
                    + "varying vec2 v_coverData;\nvarying vec2 v_coverRoot;\nvoid main() {")
              .replace("gl_Position = u_projViewTrans * pos;", "float flex = a_texCoord0.y * a_texCoord0.y;\n"
                    + "v_coverData = a_texCoord0;\nv_coverRoot = a_position.xy / u_worldMetre;\n"
                    + "float gust = sin(dot(pos.xy, vec2(.73, .41)) / u_worldMetre - u_rainTime * 2.3);\n"
                    + "pos.xy += u_wind.xy * u_wind.z * flex * u_worldMetre * (.035 + .025 * gust) * u_coverFade;\n"
                    + "pos.z -= (1.0 - u_coverFade) * a_texCoord0.y * a_texCoord0.x;\n"
                    + "gl_Position = u_projViewTrans * pos;");
    }

    static float fade(Camera camera, Vector3 position) {
        float pixels = BoardGeometry.WIDTH * BoardCamera.pixelsPerUnit(camera, position);
        float t = Math.clamp((pixels - 95) / 105, 0, 1);
        return t * t * (3 - 2 * t);
    }

    List<ModelInstance> visible(BoardScene scene, Camera camera, List<BoardScene.Tile> candidates) {
        if (revision != BoardGeometry.revision() || boardId != scene.boardId()) {
            dispose();
            revision = BoardGeometry.revision();
            boardId = scene.boardId();
        }
        if (tiles != scene.tiles()) {
            tiles = scene.tiles();
            floor = BoardGeometry.floor(scene);
            generation++;
        }
        List<ModelInstance> result = new ArrayList<>();
        List<BoardScene.Tile> nearby = new ArrayList<>();
        Set<Coords> visible = new HashSet<>();
        for (BoardScene.Tile tile : candidates) {
            if (tile.surface() != BoardScene.Surface.GRASS || !tile.detailedGround() || tile.liquid().present()
                  || tile.roadExits() != 0) { continue; }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            if (fade(camera, center) <= 0) { continue; }
            if (camera.frustum.sphereInFrustum(center, BoardGeometry.WIDTH * .75f)) {
                Cover cover = cover(scene, tile);
                if (cover != null) { result.add(cover.instance); visible.add(tile.coords()); }
            } else if (camera.frustum.sphereInFrustum(center, BoardGeometry.WIDTH * 2)) {
                nearby.add(tile);
            }
        }
        // Prefetch only outside the viewport, under a small CPU budget. Visible grass never waits in a build queue.
        long deadline = System.nanoTime() + 2_000_000;
        for (BoardScene.Tile tile : nearby) {
            if (System.nanoTime() >= deadline) { break; }
            cover(scene, tile);
        }
        var iterator = models.entrySet().iterator();
        while (models.size() > CACHE_SIZE && iterator.hasNext()) {
            var entry = iterator.next();
            if (!visible.contains(entry.getKey())) { entry.getValue().instance.model.dispose(); iterator.remove(); }
        }
        return result;
    }

    private Cover cover(BoardScene scene, BoardScene.Tile tile) {
        Cover cover = models.get(tile.coords());
        if (cover != null && cover.generation == generation) { return cover; }
        BoardSurface.Key key = BoardSurface.geometryKey(scene, tile);
        if (key.near().getFirst().ramps() != 0) { return null; }
        if (cover == null || !cover.key.equals(key)) {
            if (cover != null) { cover.instance.model.dispose(); }
            cover = new Cover(key, build(scene, tile, floor), generation);
            models.put(tile.coords(), cover);
        }
        cover.generation = generation;
        return cover;
    }

    private static ModelInstance build(BoardScene scene, BoardScene.Tile tile, float floor) {
        BoardSurface surface = new BoardSurface(scene, tile);
        List<BoardSurface.Face> ground = new ArrayList<>(surface.faces.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.TOP).toList());
        if (BoardGeometry.tuning().stepsBetweenTops()) {
            // A step's slope is meadow too where it lies back far enough; the hex above it owns it.
            for (BoardSurface.Face face : BoardTacticalGeometry.lying(surface.walls(scene, floor))) {
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                if (normal.z > .7f) { ground.add(face); }
            }
        }
        // Area-weighted sampling puts roots exactly on the rendered triangles, without searching every face per blade.
        float[] areas = new float[ground.size()];
        float total = 0;
        for (int i = 0; i < ground.size(); i++) {
            var face = ground.get(i);
            total += Math.abs((face.b().x - face.a().x) * (face.c().y - face.a().y)
                  - (face.c().x - face.a().x) * (face.b().y - face.a().y));
            areas[i] = total;
        }
        Random random = new Random(tile.coords().getX() * 0x9E3779B97F4A7C15L ^ tile.coords().getY() * 0xC2B2AE3D27D4EB4FL);
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        var mesh = builder.part("living-cover", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position
                    | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates | VertexAttributes.Usage.ColorPacked,
              new Material(ColorAttribute.createDiffuse(Color.WHITE), IntAttribute.createCullFace(GL20.GL_NONE), new Wind()));
        for (int tuft = 0; tuft < 72; tuft++) {
            int index = Arrays.binarySearch(areas, random.nextFloat() * total);
            var face = ground.get(Math.min(ground.size() - 1, index < 0 ? -index - 1 : index));
            float a = random.nextFloat(), b = random.nextFloat();
            if (a + b > 1) { a = 1 - a; b = 1 - b; }
            Vector3 root = new Vector3(face.a()).mulAdd(new Vector3(face.b()).sub(face.a()), a)
                  .mulAdd(new Vector3(face.c()).sub(face.a()), b).add(0, 0, -BoardGeometry.WIDTH * .001f);
            float tone = random.nextFloat();
            // The meadow texture's own blade palette, so near blades read as part of the ground.
            Color color = new Color(.17f + tone * .16f, .29f + tone * .15f, .08f + tone * .08f, 1);
            for (int blade = 0; blade < 3; blade++) {
                float angle = random.nextFloat() * (float) Math.PI * 2;
                Vector3 along = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0);
                Vector3 width = new Vector3(-along.y, along.x, 0).scl(BoardGeometry.WIDTH * (.0045f + random.nextFloat() * .003f));
                float height = BoardGeometry.WIDTH * (.024f + random.nextFloat() * .024f);
                Vector3 bend = new Vector3(root).mulAdd(along, height * .20f).add(0, 0, height * .55f);
                Vector3 tip = new Vector3(root).mulAdd(along, height * .60f).add(0, 0, height);
                triangle(mesh, new Vector3(root).sub(width), new Vector3(root).add(width), bend, color, height, 0, 0, .55f);
                triangle(mesh, new Vector3(root).add(width), tip, bend, color, height, 0, 1, .55f);
            }
        }
        return new ModelInstance(builder.end());
    }

    private static void triangle(MeshPartBuilder mesh, Vector3 a, Vector3 b, Vector3 c, Color color,
          float height, float u, float v, float w) {
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).nor();
        mesh.triangle(vertex(a, normal, color, height, u), vertex(b, normal, color, height, v), vertex(c, normal, color, height, w));
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Vector3 normal, Color color, float height, float flex) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setCol(color).setUV(height, flex);
    }

    @Override
    public void dispose() {
        models.values().forEach(cover -> cover.instance.model.dispose());
        models.clear();
        tiles = null;
    }
}
