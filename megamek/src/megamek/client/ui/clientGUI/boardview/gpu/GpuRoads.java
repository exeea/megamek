/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;

/** Road material masks splatted on existing terrain faces. Only the terrain owns the road's physical shape. */
final class GpuRoads {
    static final VertexAttributes VERTICES = new VertexAttributes(VertexAttribute.Position(), VertexAttribute.Normal(),
          VertexAttribute.ColorPacked(), VertexAttribute.TexCoords(0),
          new VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_roadMaskUV"));
    record MaskData(BoardScene.Pixels pixels, float x, float y, float width, float height) { }

    /** Renderer-owned exact sharing across chunks/workers; only live materials keep masks in memory. */
    static final class Masks {
        private final Map<MaskData, WeakReference<MaskData>> masks = new WeakHashMap<>();

        synchronized MaskData share(MaskData mask) {
            WeakReference<MaskData> reference = masks.get(mask);
            MaskData shared = reference == null ? null : reference.get();
            if (shared != null) { return shared; }
            masks.put(mask, new WeakReference<>(mask));
            return mask;
        }
    }

    /** Borrowed chunk-atlas region. Local mask UVs belong to vertices, so repeated roads share one material batch. */
    static final class Mask extends TextureAttribute {
        static final long TYPE = register("roadMask");
        static { TextureAttribute.Mask |= TYPE; }
        final MaskData data;

        Mask(TextureRegion region, MaskData data) {
            super(TYPE, region);
            this.data = data;
        }

        Mask withRegion(TextureRegion region) { return new Mask(region, data); }

        @Override
        public Mask copy() {
            var copy = new Mask(new TextureRegion(textureDescription.texture), data);
            copy.textureDescription.set(textureDescription);
            copy.offsetU = offsetU; copy.offsetV = offsetV; copy.scaleU = scaleU; copy.scaleV = scaleV;
            return copy;
        }
    }
    private static final class Surface extends FloatAttribute {
        static final long TYPE = register("roadSurface");
        Surface(float transition) { super(TYPE, transition); }
    }
    static final class Maps extends TextureAttribute {
        static final long TYPE = register("roadMaterialMaps");
        static { TextureAttribute.Mask |= TYPE; }
        Maps(Texture texture) { super(TYPE, texture); }
        @Override
        public Maps copy() { return new Maps(textureDescription.texture); }
    }
    static final class Soil extends FloatAttribute {
        static final long TYPE = register("roadSoil");
        Soil() { super(TYPE, 1); }
    }
    static final long TYPE = Surface.TYPE;
    /** Pavement and bridge decks share this small clearance above their supporting elevation. */
    static final float SURFACE_LIFT = .04f;
    static final float WHEEL_TINT = .92f;
    private static final float FEATHER = .45f;
    private static final float LOOSE_FEATHER = 3;

    record Fade(float outer, float inner, float end, boolean wheels, BoardRoad.Join join) {
        Fade(float outer, float inner, float end) { this(outer, inner, end, false, null); }
    }
    record Patch(Area shape, String texture, Color tint, float repeat, float lift, Fade fade) {
        boolean blended() { return fade != null; }
        float endFade() { return fade == null ? 0 : fade.end(); }
    }

    private GpuRoads() { }

    static String vertex(String source) {
        return source.replace("void main()", "#ifdef roadMaskFlag\nin vec2 a_roadMaskUV;\n"
                    + "out vec2 v_roadMaskUV;\n#endif\nvoid main()")
              .replace("void main() {", "void main() {\n#ifdef roadMaskFlag\nv_roadMaskUV = a_roadMaskUV;\n#endif\n");
    }

    static FloatAttribute attribute(Patch patch) {
        boolean transition = patch.fade() != null && patch.fade().join() != null;
        // Positive: material joins. Negative: loose surface (-1), or patchy wheel compaction (-2).
        float finish = patch.fade() != null && patch.fade().wheels() ? -2
              : patch.texture().equals("roads/dirt") || patch.texture().equals("roads/gravel") ? -1 : 0;
        return new Surface(transition ? patch.texture().equals("roads/dirt") ? 2 : 1 : finish);
    }

    private static String texture(BoardRoad.Kind kind) {
        return "roads/" + switch (kind) {
            case PAVED, ALLEY -> "asphalt";
            case GRAVEL -> "gravel";
            default -> "dirt";
        };
    }

    static List<Patch> patches(BoardScene.Tile tile, BoardRoad road) {
        var family = tile.surface();
        boolean sand = family == BoardScene.Surface.SAND;
        boolean snow = family == BoardScene.Surface.SNOW;
        boolean paved = tile.road() == BoardRoad.Kind.PAVED || tile.road() == BoardRoad.Kind.ALLEY;
        // The route's compacted soil continues across biome borders; only its loose verge takes the local cover.
        String texture = texture(tile.road());
        Color tint = Color.WHITE;
        float repeat = 1;
        float endFade = paved || Integer.bitCount(tile.roadExits()) > 1 ? 0 : tile.road() == BoardRoad.Kind.DIRT ? 17 : 7;
        List<Patch> patches = new ArrayList<>();
        float feather = paved ? FEATHER : LOOSE_FEATHER;
        Area core = road.footprint(-feather);
        Area tail = new Area(core);
        tail.intersect(road.endZone(endFade));
        core.subtract(tail);
        if (paved) {
            Area verge = road.footprint(BoardRoad.SHOULDER);
            verge.subtract(road.footprint(-FEATHER));
            patches.add(new Patch(verge, snow ? "snow" : sand ? "sand" : "roads/gravel", Color.WHITE,
                  snow ? 3 : sand ? 1.4f : 1, .025f, new Fade(BoardRoad.SHOULDER, -.2f, endFade)));
        }
        patches.add(new Patch(core, texture, tint, repeat, SURFACE_LIFT, null));
        if (!tail.isEmpty()) {
            patches.add(new Patch(tail, texture, tint, repeat, SURFACE_LIFT, new Fade(0, 0, endFade)));
        }
        float outer = paved ? 0 : BoardRoad.SHOULDER;
        Area edge = road.footprint(outer);
        edge.subtract(road.footprint(-feather));
        patches.add(new Patch(edge, texture, tint, repeat, .045f, new Fade(outer, -feather, endFade)));
        for (var join : road.joins()) {
            String next = texture(join.kind());
            if (texture.equals(next)) { continue; }
            boolean looseJoin = !paved && (join.kind() == BoardRoad.Kind.DIRT || join.kind() == BoardRoad.Kind.GRAVEL);
            float joinOuter = looseJoin ? BoardRoad.SHOULDER : 0;
            float joinFeather = looseJoin ? LOOSE_FEATHER : FEATHER;
            Area change = road.footprint(joinOuter);
            change.intersect(join.area());
            core.subtract(change);
            tail.subtract(change);
            edge.subtract(change);
            // Both tiles use the same layer order and world UVs: dirt over asphalt, gravel over either.
            // Replacing the base in this small region avoids two opposing blends with a seam at the hex edge.
            String base = texture.compareTo(next) < 0 ? texture : next;
            String cover = base.equals(texture) ? next : texture;
            Area baseCore = new Area(change);
            baseCore.intersect(road.footprint(-joinFeather));
            Area baseEdge = new Area(change);
            baseEdge.subtract(baseCore);
            patches.add(new Patch(baseCore, base, tint, repeat, SURFACE_LIFT, null));
            patches.add(new Patch(baseEdge, base, tint, repeat, .045f, new Fade(joinOuter, -joinFeather, 0)));
            patches.add(new Patch(baseCore, cover, tint, repeat, .075f, new Fade(0, 0, 0, false, join)));
            patches.add(new Patch(baseEdge, cover, tint, repeat, .075f, new Fade(joinOuter, -joinFeather, 0, false, join)));
        }
        if (tile.road() == BoardRoad.Kind.PAVED) {
            patches.add(new Patch(road.markings(tile.coords(), tile.roadExits()), "concrete",
                  new Color(1, .93f, .70f, 1), 1.5f, .065f, null));
        } else if (!paved) {
            Area tracks = road.tracks(tile.roadExits());
            Area worn = new Area(tracks);
            worn.intersect(road.endZone(6));
            tracks.subtract(worn);
            Color wornSoil = new Color(WHEEL_TINT, WHEEL_TINT, WHEEL_TINT, 1);
            patches.add(new Patch(tracks, texture, wornSoil, repeat, .065f, new Fade(0, 0, 0, true, null)));
            if (!worn.isEmpty()) {
                patches.add(new Patch(worn, texture, wornSoil, repeat, .065f, new Fade(0, 0, 6, true, null)));
            }
        }
        return patches;
    }

    /** Whole terrain triangles carry the splat. Road borders, paint and tyre wear do not split them. */
    static List<BoardTacticalGeometry.Triangle> drape(BoardScene.Tile tile, BoardSurface surface, Patch patch) {
        List<BoardTacticalGeometry.Triangle> result = new ArrayList<>();
        float scale = BoardGeometry.hexScale();
        float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        var bounds = patch.shape().getBounds2D();
        for (var face : surface.groundFaces()) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            float area = (face.b().x - face.a().x) * (face.c().y - face.a().y)
                  - (face.b().y - face.a().y) * (face.c().x - face.a().x);
            // Nearly vertical slivers can remain where terrain strips meet. They have no paintable footprint.
            if (area <= .0001f * scale * scale) { continue; }
            float minX = (Math.min(face.a().x, Math.min(face.b().x, face.c().x)) - cx) / scale;
            float maxX = (Math.max(face.a().x, Math.max(face.b().x, face.c().x)) - cx) / scale;
            float minY = (Math.min(face.a().y, Math.min(face.b().y, face.c().y)) - cy) / scale;
            float maxY = (Math.max(face.a().y, Math.max(face.b().y, face.c().y)) - cy) / scale;
            if (!bounds.intersects(minX, minY, maxX - minX, maxY - minY)) { continue; }
            result.add(new BoardTacticalGeometry.Triangle(new Vector3(face.a()).add(0, 0, patch.lift() * scale),
                  new Vector3(face.b()).add(0, 0, patch.lift() * scale), new Vector3(face.c()).add(0, 0, patch.lift() * scale), -1));
        }
        return result;
    }

    /** Shape/coverage data only: repeating albedo, normals and roughness keep their original full resolution. */
    static MaskData mask(BoardRoad road, Patch patch) {
        var bounds = patch.shape().getBounds2D();
        int x = (int) Math.floor(Math.max(-BoardGeometry.TILE_WIDTH / 2f, bounds.getMinX())) - 1;
        int y = (int) Math.floor(Math.max(-BoardGeometry.TILE_HEIGHT / 2f, bounds.getMinY())) - 1;
        int w = Math.max(1, (int) Math.ceil(Math.min(BoardGeometry.TILE_WIDTH / 2f, bounds.getMaxX())) - x + 1);
        int h = Math.max(1, (int) Math.ceil(Math.min(BoardGeometry.TILE_HEIGHT / 2f, bounds.getMaxY())) - y + 1);
        int density = 4;
        var image = new BufferedImage(w * density, h * density, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(density, density);
        graphics.translate(-x, -y);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fill(patch.shape());
        graphics.dispose();
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        for (int py = 0; py < image.getHeight(); py++) {
            for (int px = 0; px < image.getWidth(); px++) {
                int i = py * image.getWidth() + px;
                float alpha = (pixels[i] >>> 24) / 255f;
                Color tint = alpha == 0 ? patch.tint() : color(road, patch, x + (px + .5f) / density, y + (py + .5f) / density);
                pixels[i] = Math.round(alpha * tint.a * 255) << 24 | Math.round(tint.r * 255) << 16
                      | Math.round(tint.g * 255) << 8 | Math.round(tint.b * 255);
            }
        }
        image.setRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
        return new MaskData(new BoardScene.Pixels(image).compact(), x, y, w, h);
    }

    static void write(Supplier<MeshPartBuilder> meshes, List<BoardTacticalGeometry.Triangle> triangles,
          Patch patch, MaskData mask, BoardSurface surface) {
        Map<Vector3, Vector3> normals = new HashMap<>();
        for (var face : surface.groundFaces()) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (var p : List.of(face.a(), face.b(), face.c())) {
                normals.computeIfAbsent(normalKey(p, 0), unused -> new Vector3()).add(normal);
            }
        }
        for (var t : triangles) {
            float repeat = BoardRelief.detailMetres(patch.repeat());
            float lift = patch.lift() * BoardGeometry.hexScale();
            Vector3 fallback = new Vector3(t.b()).sub(t.a()).crs(new Vector3(t.c()).sub(t.a())).nor();
            MeshPartBuilder mesh = meshes.get();
            mesh.triangle(carrier(mesh, t.a(), surface.roadNormal(t.a(), normals.getOrDefault(normalKey(t.a(), lift), fallback)).nor(), repeat, mask, surface.tile),
                  carrier(mesh, t.b(), surface.roadNormal(t.b(), normals.getOrDefault(normalKey(t.b(), lift), fallback)).nor(), repeat, mask, surface.tile),
                  carrier(mesh, t.c(), surface.roadNormal(t.c(), normals.getOrDefault(normalKey(t.c(), lift), fallback)).nor(), repeat, mask, surface.tile));
        }
    }

    private static Vector3 normalKey(Vector3 p, float lift) {
        return new Vector3(p.x, p.y, Math.round((p.z - lift) / (.001f * BoardGeometry.hexScale())));
    }

    private static short carrier(MeshPartBuilder mesh, Vector3 p, Vector3 normal, float repeat, MaskData mask, BoardScene.Tile tile) {
        float scale = BoardGeometry.hexScale();
        float u = ((p.x - BoardGeometry.centerX(tile.coords())) / scale - mask.x()) / mask.width();
        float v = ((p.y - BoardGeometry.centerY(tile.coords())) / scale - mask.y()) / mask.height();
        return mesh.vertex(p.x, p.y, p.z, normal.x, normal.y, normal.z, Color.WHITE.toFloatBits(), p.x / repeat, -p.y / repeat, u, v);
    }

    static float coverage(BoardRoad road, Patch patch, float x, float y) {
        Fade fade = patch.fade();
        if (fade == null) { return 1; }
        float alpha = fade.outer() > fade.inner()
              ? Math.clamp((fade.outer() - road.distance(x, y)) / (fade.outer() - fade.inner()), 0, 1) : 1;
        if (fade.wheels()) { alpha *= road.wheelCoverage(x, y); }
        return fade.end() > 0 ? alpha * road.endCoverage(x, y, fade.end()) : alpha;
    }

    static float materialCoverage(Patch patch, float x, float y) {
        BoardRoad.Join join = patch.fade().join();
        float cover = join.coverage(x, y);
        return patch.texture().equals(texture(join.kind())) ? cover : 1 - cover;
    }

    private static Color color(BoardRoad road, Patch patch, float x, float y) {
        Color tint = new Color(patch.tint());
        if (patch.blended()) {
            tint.a = coverage(road, patch, x, y);
        }
        if (patch.fade() != null && patch.fade().join() != null) {
            // Transition masks carry along/across coordinates in RG, separately from edge coverage in A.
            // Their shader supplies white material tint. Neither coordinate can taper the road's footprint.
            tint.r = materialCoverage(patch, x, y);
            tint.g = Math.clamp(.5f + patch.fade().join().lateral(x, y) / (2 * BoardRoad.JOIN_REACH), 0, 1);
            tint.b = 0;
        } else if (patch.fade() != null && patch.fade().wheels()) {
            var direction = road.wheelDirection(x, y);
            // Road UVs point down in Y. RG carries the wear direction, not an albedo tint.
            tint.r = Math.round(128 + direction.x * 127) / 255f;
            tint.g = Math.round(128 - direction.y * 127) / 255f;
            tint.b = 0;
        }
        return tint;
    }
}
