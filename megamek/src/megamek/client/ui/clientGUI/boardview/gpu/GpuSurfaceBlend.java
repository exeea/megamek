/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.badlogic.gdx.math.Vector3;

/** Extra data only on material boundaries. Palettes are shared by a chunk, never unique to individual hexes. */
final class GpuSurfaceBlend extends Attribute {
    static final long TYPE = register("boardSurfaceBlend");
    static final VertexAttributes VERTICES = new VertexAttributes(VertexAttribute.Position(), VertexAttribute.Normal(),
          VertexAttribute.ColorPacked(), VertexAttribute.TexCoords(0),
          new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverWeights"),
          new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_coverInterpolation", 1));
    final TextureDescriptor<TextureArray> texture;
    final float[] families, responses;
    final float[][] tiles, layers;

    GpuSurfaceBlend(TextureArray texture, float[] families, float[] responses, float[][] tiles, float[][] layers) {
        super(TYPE);
        this.texture = new TextureDescriptor<>(texture);
        this.families = families.clone();
        this.responses = responses.clone();
        this.tiles = Arrays.stream(tiles).map(float[]::clone).toArray(float[][]::new);
        this.layers = Arrays.stream(layers).map(float[]::clone).toArray(float[][]::new);
    }

    @Override
    public GpuSurfaceBlend copy() {
        return new GpuSurfaceBlend(texture.texture, families, responses, tiles, layers);
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        var blend = (GpuSurfaceBlend) other;
        int result = texture.compareTo(blend.texture);
        if (result == 0) { result = Arrays.compare(families, blend.families); }
        if (result == 0) { result = Arrays.compare(responses, blend.responses); }
        for (int i = 0; i < tiles.length && result == 0; i++) {
            result = Arrays.compare(tiles[i], blend.tiles[i]);
            if (result == 0) { result = Arrays.compare(layers[i], blend.layers[i]); }
        }
        return result;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(super.hashCode(), texture, Arrays.hashCode(families), Arrays.hashCode(responses),
              Arrays.deepHashCode(tiles), Arrays.deepHashCode(layers));
    }

    static String vertex(String source) {
        return source.replace("void main() {",
              GpuShaderSource.read("terrain-blend.vert") + "\nvoid main() {\nterrainBlendWeights();\n");
    }

    record Palette(int base, int first, int second, int third) {
        Palette(int base, int first, int second) { this(base, first, second, base); }

        Palette(BoardScene.Surface base, BoardScene.Surface first, BoardScene.Surface second) {
            this(base.ordinal(), first.ordinal(), second.ordinal());
        }

        boolean volcanic() {
            return base >= BoardSurfaceBlend.CRUST || first >= BoardSurfaceBlend.CRUST
                  || second >= BoardSurfaceBlend.CRUST || third >= BoardSurfaceBlend.CRUST;
        }
    }

    record Point(MeshPartBuilder.VertexInfo vertex, BoardSurfaceBlend.Cover cover) { }

    record Triangle(Point a, Point b, Point c) {
        void write(MeshPartBuilder mesh, Palette palette) {
            mesh.triangle(write(mesh, a, palette), write(mesh, b, palette), write(mesh, c, palette));
        }

        private static short write(MeshPartBuilder mesh, Point point, Palette palette) {
            var v = point.vertex();
            return mesh.vertex(v.position.x, v.position.y, v.position.z, v.normal.x, v.normal.y, v.normal.z,
                  v.color.toFloatBits(), v.uv.x, v.uv.y, point.cover().weight(palette.base()),
                  palette.first() == palette.base() ? 0 : point.cover().weight(palette.first()),
                  palette.second() == palette.base() ? 0 : point.cover().weight(palette.second()),
                  palette.third() == palette.base() ? 0 : point.cover().weight(palette.third()), point.cover().interpolation());
        }
    }

    /** Subdivision changes only interpolation, never the support surface or its silhouette. */
    static Map<Palette, List<Triangle>> prepare(BoardScene scene, BoardScene.Tile tile, List<BoardSurface.Face> faces,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices) {
        return prepare(scene, tile, faces, vertices, spacing(TerrainLod.FULL));
    }

    /** Broad cover changes span several metres; texture height and noise provide the fine contact detail. */
    static float spacing(TerrainLod detail) {
        return BoardRelief.metres(detail == TerrainLod.DISTANT ? 8 : detail == TerrainLod.COARSE ? 4 : 3);
    }

    static Map<Palette, List<Triangle>> prepare(BoardScene scene, BoardScene.Tile tile, List<BoardSurface.Face> faces,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices, float spacing) {
        return prepare(BoardSurfaceBlend.family(tile), faces, vertices,
              v -> v.color.b > .375f && v.color.b < .625f
                    ? BoardSurfaceBlend.sampleCliff(scene, tile, v.position.x, v.position.y, v.position.z)
                    : BoardSurfaceBlend.sample(scene, tile, v.position.x, v.position.y, v.position.z), spacing);
    }

    private static Map<Palette, List<Triangle>> prepare(int family, List<BoardSurface.Face> faces,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices, Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Cover> cover,
          float spacing) {
        Map<Palette, List<Triangle>> groups = new LinkedHashMap<>();
        // Coincident cliff/top vertices deliberately carry different packed roles and UV meanings.
        Map<Vector3, Point> points = new IdentityHashMap<>();
        Function<Vector3, Point> point = p -> points.computeIfAbsent(p, key -> {
            var v = vertices.apply(key);
            return new Point(v, cover.apply(v));
        });
        for (var face : faces) {
            append(groups, family, cover, point.apply(face.a()), point.apply(face.b()), point.apply(face.c()), spacing, 0);
        }
        return groups;
    }

    /** Water coverage may split a bank first; retain those vertices and their dry/submerged shading data. */
    static void appendPolygon(Map<Palette, List<Triangle>> groups, BoardScene.Surface family,
          List<MeshPartBuilder.VertexInfo> polygon, Function<Vector3, BoardSurfaceBlend.Cover> cover, float spacing) {
        appendPolygon(groups, family.ordinal(), polygon, cover, spacing);
    }

    static void appendPolygon(Map<Palette, List<Triangle>> groups, int family,
          List<MeshPartBuilder.VertexInfo> polygon, Function<Vector3, BoardSurfaceBlend.Cover> cover, float spacing) {
        List<Point> points = new ArrayList<>(polygon.size());
        for (var vertex : polygon) { points.add(new Point(vertex, cover.apply(vertex.position))); }
        for (int i = 1; i + 1 < points.size(); i++) {
            var a = points.getFirst().vertex().position;
            var b = points.get(i).vertex().position;
            var c = points.get(i + 1).vertex().position;
            if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() <= 1e-8f) { continue; }
            append(groups, family, v -> cover.apply(v.position), points.getFirst(), points.get(i), points.get(i + 1), spacing, 0);
        }
    }

    private static void append(Map<Palette, List<Triangle>> groups, int family,
          Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Cover> cover,
          Point a, Point b, Point c, float spacing, int depth) {
        int mask = a.cover().mask() | b.cover().mask() | c.cover().mask();
        float ab = a.vertex().position.dst2(b.vertex().position), bc = b.vertex().position.dst2(c.vertex().position);
        float ca = c.vertex().position.dst2(a.vertex().position);
        boolean crowded = Integer.bitCount(mask) > 4;
        boolean mixedPoint = Integer.bitCount(a.cover().mask()) > 4 || Integer.bitCount(b.cover().mask()) > 4
              || Integer.bitCount(c.cover().mask()) > 4;
        // A long top fan must not stretch a four-metre boundary into a half-hex fade. Linear cover needs no
        // extra geometry; probe its edges and interior before subdividing a mixed triangle.
        float longest = Math.max(ab, Math.max(bc, ca));
        if (depth < 12 && (crowded || Integer.bitCount(mask) > 1 && longest > spacing * spacing)
              && !(crowded && mixedPoint && longest <= spacing * spacing)) {
            boolean split;
            if (bc > ab && bc >= ca) { split = appendSplit(groups, family, cover, b, c, a, spacing, depth, mask); }
            else if (ca > ab) { split = appendSplit(groups, family, cover, c, a, b, spacing, depth, mask); }
            else { split = appendSplit(groups, family, cover, a, b, c, spacing, depth, mask); }
            if (split) { return; }
        }
        // Authored mixtures can put more than four families at one point; subdivision cannot separate those.
        // Keep the strongest four on this already small carrier instead of growing an unbounded mesh.
        while (Integer.bitCount(mask) > 4) {
            int weakest = -1;
            float least = Float.POSITIVE_INFINITY;
            for (int candidate = 0; candidate < BoardSurfaceBlend.FAMILIES; candidate++) {
                if ((mask & (1 << candidate)) == 0) { continue; }
                float weight = a.cover().weight(candidate) + b.cover().weight(candidate) + c.cover().weight(candidate);
                if (weight < least) { weakest = candidate; least = weight; }
            }
            mask &= ~(1 << weakest);
        }
        // A slope can extend beyond its source footprint. Its absent family must not consume a palette slot.
        int base = (mask & (1 << family)) != 0 ? family : Integer.numberOfTrailingZeros(mask);
        var first = base;
        var second = base;
        var third = base;
        for (int candidate = 0; candidate < BoardSurfaceBlend.FAMILIES; candidate++) {
            if (candidate != base && (mask & (1 << candidate)) != 0) {
                if (first == base) { first = candidate; }
                else if (second == base) { second = candidate; }
                else { third = candidate; }
            }
        }
        groups.computeIfAbsent(new Palette(base, first, second, third), key -> new ArrayList<>()).add(new Triangle(a, b, c));
    }

    private static boolean appendSplit(Map<Palette, List<Triangle>> groups, int family,
          Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Cover> cover,
          Point a, Point b, Point c, float spacing, int depth, int mask) {
        var mid = between(cover, a, b, .5f);
        if (Integer.bitCount(mask) <= 4 && accurate(mid, a, b, c, .5f, .5f, 0)
              && accurate(between(cover, b, c, .5f), a, b, c, 0, .5f, .5f)
              && accurate(between(cover, c, a, .5f), a, b, c, .5f, 0, .5f)
              && accurate(between(cover, mid, c, 1 / 3f), a, b, c, 1 / 3f, 1 / 3f, 1 / 3f)) {
            return false;
        }
        append(groups, family, cover, a, mid, c, spacing, depth + 1);
        append(groups, family, cover, mid, b, c, spacing, depth + 1);
        return true;
    }

    private static Point between(Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Cover> cover,
          Point a, Point b, float amount) {
        var v = new MeshPartBuilder.VertexInfo().set(a.vertex()).lerp(b.vertex(), amount);
        v.normal.nor();
        return new Point(v, cover.apply(v));
    }

    private static boolean accurate(Point sample, Point a, Point b, Point c, float wa, float wb, float wc) {
        float interpolation = a.cover().interpolation() * wa + b.cover().interpolation() * wb + c.cover().interpolation() * wc;
        if (Math.abs(sample.cover().interpolation() - interpolation) > .04f) { return false; }
        for (int family = 0; family < BoardSurfaceBlend.FAMILIES; family++) {
            float expected = a.cover().weight(family) * wa + b.cover().weight(family) * wb + c.cover().weight(family) * wc;
            if (Math.abs(sample.cover().weight(family) - expected) > .04f) { return false; }
        }
        return true;
    }
}
