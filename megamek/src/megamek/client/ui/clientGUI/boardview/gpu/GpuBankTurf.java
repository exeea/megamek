/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;

/** Six cutout turf crowns draped over the installed bank. Workers shape them; the grass chunk owns their GL mesh. */
final class GpuBankTurf implements Disposable {
    static final class Turf extends FloatAttribute {
        static final long TYPE = register("boardBankTurf");
        Turf() { super(TYPE, 1); }
        @Override
        public Turf copy() { return new Turf(); }
    }

    static final int STRIDE = 19;
    private static final float[] ROWS = { 0, .23f, .42f, .68f, 1 };
    private final List<FloatArray> current = new ArrayList<>(), previous = new ArrayList<>();
    private ModelInstance instance;

    /** Cosmetic foliage uses the finished surface, including corner blends and road grades, never another bank profile. */
    static FloatArray plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface) {
        if (!BoardGeometry.tuning().stepsBetweenTops() || !BoardSurfaceBlend.natural(tile)
              || tile.surface() != BoardScene.Surface.GRASS || BoardBiome.kind(tile) != BoardScene.Biome.NONE) { return null; }
        int edges = 0;
        for (int edge = 0; edge < 6; edge++) {
            int direction = BoardGeometry.edgeDirection(edge);
            var lower = scene.tile(tile.coords().translated(direction));
            if (lower != null && !lower.liquid().present() && tile.elevation() - lower.elevation() == 2
                  && (tile.cliffTopExits() & (1 << direction)) == 0) { edges |= 1 << edge; }
        }
        if (edges == 0) { return null; }
        var vertices = new FloatArray();
        List<BoardSurface.Face> faces = new ArrayList<>(surface.top());
        faces.addAll(surface.walls());
        var support = new GpuBiomeVegetation.Support(faces);
        var center = BoardGeometry.center(tile.coords(), tile.elevation());
        float m = BoardRelief.metres(1), level = BoardGeometry.level();
        var road = BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
        boolean boundary = BoardSurfaceBlend.boundary(scene, tile);
        for (int edge = 0; edge < 6; edge++) {
            if ((edges & (1 << edge)) == 0) { continue; }
            var a = BoardGeometry.corner(tile.coords(), 0, edge);
            var b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
            var along = b.cpy().sub(a).nor();
            var outward = new Vector3(along.y, -along.x, 0);
            if (outward.dot(a.cpy().sub(center)) < 0) { outward.scl(-1); }
            int count = Math.max(1, Math.round(a.dst(b) / (1.6f * m)));
            var random = new Random(tile.coords().getX() * 0x9E3779B97F4A7C15L
                  ^ tile.coords().getY() * 0xC2B2AE3D27D4EB4FL ^ edge * 7919L);
            int previousVariant = -1;
            for (int band = 0; band < 2; band++) {
                for (int i = 0; i < count; i++) {
                    float t = (i + .5f + (random.nextFloat() - .5f) * .9f) / count;
                    var point = a.cpy().lerp(b, t);
                    float irregular = BoardRelief.gradient(point.x / (4 * m), point.y / (4 * m));
                    float z = center.z - (band == 0 ? .075f + .025f * irregular : 1 + .16f * irregular) * level;
                    var root = contour(support, point, outward, z);
                    float width = m * (2 + random.nextFloat() * 1.6f);
                    float length = m * (1.15f + random.nextFloat() * 1.1f);
                    int variant = random.nextInt(5);
                    if (variant >= previousVariant && previousVariant >= 0) { variant++; }
                    boolean flip = random.nextBoolean();
                    float tone = .86f + random.nextFloat() * .14f;
                    float chance = random.nextFloat();
                    previousVariant = variant;
                    if (root == null || band == 1 && chance < .23f) { continue; }
                    var cover = boundary ? BoardSurfaceBlend.sampleCliff(scene, tile, root.x, root.y, root.z)
                          : BoardSurfaceBlend.solid(BoardScene.Surface.GRASS);
                    float grass = cover.grass();
                    if (chance > grass * BoardRelief.smooth((grass - .55f) / .35f)) { continue; }
                    if (road != null && road.distance((root.x - center.x) / BoardGeometry.hexScale(),
                          (root.y - center.y) / BoardGeometry.hexScale()) < BoardRoad.SHOULDER + 2) { continue; }
                    // Follow the local contour through rounded corners instead of lining every clump up with the hex edge.
                    float dx = support.height(root.x - m, root.y) - support.height(root.x + m, root.y);
                    float dy = support.height(root.x, root.y - m) - support.height(root.x, root.y + m);
                    var facing = new Vector3(dx, dy, 0);
                    if (!Float.isFinite(dx + dy) || facing.len2() < .001f || facing.dot(outward) <= 0) { facing.set(outward); }
                    facing.nor();
                    crown(vertices, support, root, new Vector3(-facing.y, facing.x, 0), facing,
                          width, length, variant, flip, tone, cover);
                }
            }
        }
        if (vertices.isEmpty()) { return null; }
        vertices.shrink();
        return vertices;
    }

    /** Intersect a horizontal cross-section with the actual finished triangles, in the outward direction. */
    private static Vector3 contour(GpuBiomeVegetation.Support support, Vector3 point, Vector3 outward, float z) {
        float reach = BoardGeometry.width() * .45f;
        float previous = Float.NaN, from = -reach;
        for (int step = 0; step <= 48; step++) {
            float at = -reach + 2 * reach * step / 48;
            var sample = point.cpy().mulAdd(outward, at);
            var face = support.face(sample.x, sample.y);
            float height = face == null ? Float.NaN : face.height(sample.x, sample.y);
            if (previous >= z && height < z) {
                float to = at;
                for (int iteration = 0; iteration < 12; iteration++) {
                    float middle = (from + to) * .5f;
                    sample.set(point).mulAdd(outward, middle);
                    face = support.face(sample.x, sample.y);
                    if (face == null) { return null; }
                    if (face.height(sample.x, sample.y) >= z) { from = middle; } else { to = middle; }
                }
                sample.set(point).mulAdd(outward, (from + to) * .5f);
                sample.z = z;
                return sample;
            }
            previous = height;
            from = at;
        }
        return null;
    }

    /** A rounded crown turns into a hanging fringe. Three columns follow local roughness across each clump. */
    private static void crown(FloatArray into, GpuBiomeVegetation.Support support, Vector3 root,
          Vector3 along, Vector3 outward, float width, float length, int variant, boolean flip, float tone, BoardSurfaceBlend.Cover cover) {
        var points = new Vector3[ROWS.length][3];
        float m = BoardRelief.metres(1);
        for (int row = 0; row < ROWS.length; row++) {
            float v = ROWS[row], fall = Math.max(0, (v - .3f) / .7f);
            float across = length * (v < .3f ? (v - .3f) * 1.7f : .85f * fall);
            for (int col = 0; col < 3; col++) {
                var p = root.cpy().mulAdd(along, (col - 1) * width * .5f).mulAdd(outward, across);
                var face = support.face(p.x, p.y);
                float ground = face == null ? root.z - length : face.height(p.x, p.y);
                // At a junction another cliff or a missing carrier can be a whole level away. A turf crown must
                // stay attached to one small patch, never stretch into a curtain between unrelated surfaces.
                if (ground > root.z + length * .45f || v <= .42f && (face == null || ground < root.z - length * .65f)) { return; }
                float crown = length * .16f * (float) Math.sin(Math.PI * Math.min(v / .5f, 1));
                p.z = v < .3f ? ground + crown : Math.max(ground, root.z - length * (.2f * fall + .7f * fall * fall)) + crown;
                p.z += .07f * m;
                points[row][col] = p;
            }
        }
        float color = new Color(tone, tone, tone, 1).toFloatBits();
        var normals = new Vector3[ROWS.length][3];
        for (int row = 0; row < ROWS.length; row++) {
            for (int col = 0; col < 3; col++) {
                var down = points[Math.min(row + 1, ROWS.length - 1)][col].cpy().sub(points[Math.max(row - 1, 0)][col]);
                var side = points[row][Math.min(col + 1, 2)].cpy().sub(points[row][Math.max(col - 1, 0)]);
                normals[row][col] = down.crs(side).nor();
                if (normals[row][col].z < 0) { normals[row][col].scl(-1); }
            }
        }
        for (int row = 0; row < ROWS.length - 1; row++) {
            for (int col = 0; col < 2; col++) {
                vertex(into, points[row][col], normals[row][col], color, col, row, variant, flip, cover);
                vertex(into, points[row + 1][col], normals[row + 1][col], color, col, row + 1, variant, flip, cover);
                vertex(into, points[row + 1][col + 1], normals[row + 1][col + 1], color, col + 1, row + 1, variant, flip, cover);
                vertex(into, points[row][col], normals[row][col], color, col, row, variant, flip, cover);
                vertex(into, points[row + 1][col + 1], normals[row + 1][col + 1], color, col + 1, row + 1, variant, flip, cover);
                vertex(into, points[row][col + 1], normals[row][col + 1], color, col + 1, row, variant, flip, cover);
            }
        }
    }

    private static void vertex(FloatArray into, Vector3 p, Vector3 n, float color, int col, int row,
          int variant, boolean flip, BoardSurfaceBlend.Cover cover) {
        into.addAll(p.x, p.y, p.z, n.x, n.y, n.z, color,
              (variant % 3 + (flip ? 1 - col * .5f : col * .5f)) / 3,
              // Keep the free end inside its atlas cell: fract(v * 2) must not wrap the tip's wind weight to zero.
              (variant / 3 + .001f + ROWS[row] * .998f) / 2);
        into.addAll(cover.grass(), cover.dirt(), cover.sand(), cover.rock(), cover.concrete(), cover.snow(), cover.lunar(), cover.fungus());
        into.addAll(cover.desert(), cover.mars());
    }

    static String vertex(String source) {
        String wind = GpuShaderSource.read("terrain-vegetation-wind.glsl");
        return source.replace("void main() {", wind + "\n" + GpuShaderSource.read("terrain-bank-turf.glsl") + "\nvoid main() {")
              .replace("vec4 pos = u_worldTrans * vec4(a_position, 1.0);", "vec4 pos = vec4(turfPosition(), 1.0);\n"
                    + "v_coverData = vec2(1.0, .75); v_coverRoot = a_position.xy / u_worldMetre;\n"
                    + "v_turfWeights = a_turfWeights; v_turfOthers = a_turfOthers; v_turfArid = a_turfArid;");
    }

    void begin() { current.clear(); }
    void add(FloatArray vertices) { if (vertices != null) { current.add(vertices); } }
    boolean isEmpty() { return current.isEmpty(); }

    ModelInstance upload(Texture texture) {
        boolean changed = current.size() != previous.size();
        for (int i = 0; !changed && i < current.size(); i++) {
            changed = current.get(i) != previous.get(i) && !current.get(i).equals(previous.get(i));
        }
        previous.clear(); previous.addAll(current);
        if (!changed) { return instance; }
        if (instance != null) { instance.model.dispose(); instance = null; }
        if (current.isEmpty()) { return null; }
        var vertices = new FloatArray();
        for (var data : current) { vertices.addAll(data); }
        var mesh = new Mesh(true, vertices.size / STRIDE, 0, VertexAttribute.Position(), VertexAttribute.Normal(),
              VertexAttribute.ColorPacked(), VertexAttribute.TexCoords(0),
              new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_turfWeights"),
              new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_turfOthers", 1),
              new VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_turfArid", 2));
        mesh.setVertices(vertices.items, 0, vertices.size);
        var builder = new ModelBuilder();
        builder.begin();
        builder.part("bank-turf", mesh, GL20.GL_TRIANGLES, 0, vertices.size / STRIDE, new Material(ColorAttribute.createDiffuse(Color.WHITE),
              IntAttribute.createCullFace(GL20.GL_NONE), TextureAttribute.createDiffuse(texture), new Turf()));
        builder.manage(mesh);
        instance = new ModelInstance(builder.end());
        return instance;
    }

    @Override
    public void dispose() {
        if (instance != null) { instance.model.dispose(); instance = null; }
        current.clear(); previous.clear();
    }
}
