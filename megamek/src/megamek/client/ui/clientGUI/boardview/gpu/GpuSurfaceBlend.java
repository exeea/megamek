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
          new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverWeights"));
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
                  palette.third() == palette.base() ? 0 : point.cover().weight(palette.third()));
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
        var points = polygon.stream().map(v -> new Point(v, cover.apply(v.position))).toList();
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
        // A long top fan must not stretch a four-metre boundary into a half-hex fade. Refine only affected faces.
        if (Integer.bitCount(mask) > 4 || Integer.bitCount(mask) > 1 && Math.max(ab, Math.max(bc, ca)) > spacing * spacing) {
            if (depth >= 12) { throw new IllegalStateException("Unbounded terrain cover palette at " + a.vertex().position); }
            if (bc > ab && bc >= ca) { appendSplit(groups, family, cover, b, c, a, spacing, depth); }
            else if (ca > ab) { appendSplit(groups, family, cover, c, a, b, spacing, depth); }
            else { appendSplit(groups, family, cover, a, b, c, spacing, depth); }
            return;
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

    private static void appendSplit(Map<Palette, List<Triangle>> groups, int family,
          Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Cover> cover,
          Point a, Point b, Point c, float spacing, int depth) {
        var v = new MeshPartBuilder.VertexInfo().set(a.vertex()).lerp(b.vertex(), .5f);
        v.normal.nor();
        var mid = new Point(v, cover.apply(v));
        append(groups, family, cover, a, mid, c, spacing, depth + 1);
        append(groups, family, cover, mid, b, c, spacing, depth + 1);
    }
}
