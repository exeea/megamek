/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import megamek.common.board.Coords;

/** World-aligned crop rows and clustered reeds, with six shared instanced batches and bounded root preparation. */
final class GpuBiomeVegetation implements Disposable {
    static final class Kind extends FloatAttribute {
        static final long TYPE = register("boardBiomeVegetation");
        static final long LOD = register("boardBiomeVegetationLod");
        Kind(boolean crop) { super(TYPE, crop ? 1 : 2); }
        @Override
        public Kind copy() { return new Kind(value == 1); }
    }

    private static final float[] DENSITY = { 1, .35f, .08f };
    // Cumulative coverage: fine plants replace medium plants, which replace the distant stems.
    // The same intervals drive conservative CPU submission and the per-root GPU cross-fade.
    private static final float[] START_PIXELS = { 240, 64, 12 }, FULL_PIXELS = { 480, 128, 36 };
    private static final int CACHE_SIZE = 384, STRIDE = 4;
    private static final long BUILD_NANOS = 2_000_000;

    /** CPU-only roots are also useful for verifying ground contact, shared rows and pool/road exclusion. */
    static final class Patch {
        final BoardVegetation.Key key;
        BoardTacticalGeometry.Surface surface;
        final List<BoardSurface.Face> ground;
        final Support support, water;
        final BoardScene.Biome kind;
        final FloatArray roots = new FloatArray();
        final BoardRoad road;
        final float across, along;
        final int minX, minY, maxX, maxY;
        int x, y;
        int tier = 2;
        float requested = 1, prepared;
        long generation;
        // Keep the rendered roots during a terrain LOD handoff, until this replacement is complete.
        Patch previous;

        Patch(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface, long generation) {
            key = BoardVegetation.key(scene, tile);
            this.surface = surface;
            ground = support(tile, surface);
            support = new Support(ground);
            water = tile.liquid().present() ? new Support(surface.water()) : null;
            this.generation = generation;
            kind = BoardBiome.plantKind(scene, tile);
            road = BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
            across = BoardBiome.ROW_METRES;
            along = kind == BoardScene.Biome.FIELD ? .63f : 1.15f;
            float cx = BoardGeometry.centerX(tile.coords()) / BoardRelief.metres(1);
            float cy = BoardGeometry.centerY(tile.coords()) / BoardRelief.metres(1);
            float u = cx * BoardBiome.ROW_X + cy * BoardBiome.ROW_Y;
            float v = -cx * BoardBiome.ROW_Y + cy * BoardBiome.ROW_X;
            minX = (int) Math.floor((u - 21) / across);
            maxX = (int) Math.ceil((u + 21) / across);
            x = minX;
            minY = (int) Math.floor((v - 21) / along);
            y = minY;
            maxY = (int) Math.ceil((v + 21) / along);
        }

        void prepare(BoardScene scene, BoardScene.Tile tile, long deadline) {
            prepare(scene, tile, 1, deadline);
        }

        void prepare(BoardScene scene, BoardScene.Tile tile, float density, long deadline) {
            requested = density;
            int checked = 0;
            float metre = BoardRelief.metres(1);
            // Prepare the distant subset first. Zooming in extends it; zooming out never prepares hidden roots.
            while (prepared < requested) {
                if ((checked++ & 7) == 0 && System.nanoTime() >= deadline) { break; }
                if (y > maxY) {
                    prepared = DENSITY[tier--];
                    x = minX; y = minY;
                    continue;
                }
                int ix = x++, iy = y;
                if (x > maxX) { x = minX; y++; }
                // Thin the same world-space lattice at every LOD, independently of wetland coverage and plant height.
                // Reject before sampling support geometry, so omitted roots also avoid preparation and buffer cost.
                if (BoardRelief.hash(ix + 379, iy - 827) >= (kind == BoardScene.Biome.FIELD ? .38f : .46f)) { continue; }
                float seed = BoardRelief.hash(ix, iy);
                if (seed >= DENSITY[tier] || (tier < 2 && seed < DENSITY[tier + 1])) { continue; }
                float u = (ix + (BoardRelief.hash(ix + 37, iy) - .5f)
                      * (kind == BoardScene.Biome.FIELD ? .12f : .7f)) * across;
                float v = (iy + (BoardRelief.hash(ix, iy + 17) - .5f) * .55f) * along;
                float px = (u * BoardBiome.ROW_X - v * BoardBiome.ROW_Y) * metre;
                float py = (u * BoardBiome.ROW_Y + v * BoardBiome.ROW_X) * metre;
                if (BoardGeometry.tile(scene, px, py) != tile) { continue; }
                // Water's tactical top includes the water plane. Roots instead follow the actual bank/bar mesh.
                float z = support.height(px, py);
                if (!Float.isFinite(z)) { continue; }
                float cover = BoardBiome.coverage(scene, kind, px, py, z);
                if (kind == BoardScene.Biome.FIELD ? cover < .60f : seed > .88f * BoardRelief.smooth(cover)) { continue; }
                if (water != null) {
                    if (z <= water.height(px, py) + .015f * metre) { continue; }
                }
                if (road != null && road.distance((px - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale(),
                      (py - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale()) < BoardRoad.SHOULDER + 1) { continue; }
                if (kind == BoardScene.Biome.MARSH) {
                    float wet = BoardBiome.wetness(px / metre, py / metre);
                    if (wet < .48f || wet > .80f) { continue; }
                } else if (seed > .96f) { continue; }
                roots.addAll(px, py, z - .018f * metre, seed);
            }
        }

        boolean busy() { return prepared < requested; }
    }

    /** Small CPU index of the published triangles, not a second height field. No per-root whole-mesh scan. */
    private static final class Support {
        private static final int SIDE = 8;
        final List<List<BoardSurface.Face>> cells = new ArrayList<>(SIDE * SIDE);
        final float minX, minY, scaleX, scaleY;

        Support(List<BoardSurface.Face> faces) {
            float left = Float.POSITIVE_INFINITY, bottom = left, right = Float.NEGATIVE_INFINITY, top = right;
            for (var face : faces) {
                left = Math.min(left, Math.min(face.a().x, Math.min(face.b().x, face.c().x)));
                bottom = Math.min(bottom, Math.min(face.a().y, Math.min(face.b().y, face.c().y)));
                right = Math.max(right, Math.max(face.a().x, Math.max(face.b().x, face.c().x)));
                top = Math.max(top, Math.max(face.a().y, Math.max(face.b().y, face.c().y)));
            }
            minX = left; minY = bottom;
            scaleX = SIDE / Math.max(right - left, .001f); scaleY = SIDE / Math.max(top - bottom, .001f);
            for (int i = 0; i < SIDE * SIDE; i++) { cells.add(new ArrayList<>()); }
            float tolerance = .001f * BoardGeometry.hexScale();
            for (var face : faces) {
                int x0 = column(Math.min(face.a().x, Math.min(face.b().x, face.c().x)) - tolerance);
                int x1 = column(Math.max(face.a().x, Math.max(face.b().x, face.c().x)) + tolerance);
                int y0 = row(Math.min(face.a().y, Math.min(face.b().y, face.c().y)) - tolerance);
                int y1 = row(Math.max(face.a().y, Math.max(face.b().y, face.c().y)) + tolerance);
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) { cells.get(y * SIDE + x).add(face); }
                }
            }
        }

        private int column(float x) { return Math.clamp((int) ((x - minX) * scaleX), 0, SIDE - 1); }
        private int row(float y) { return Math.clamp((int) ((y - minY) * scaleY), 0, SIDE - 1); }
        float height(float x, float y) {
            return BoardSurface.sampleHeight(cells.get(row(y) * SIDE + column(x)), x, y, Float.NaN);
        }
    }

    private static List<BoardSurface.Face> support(BoardScene.Tile tile, BoardTacticalGeometry.Surface surface) {
        return tile.liquid().present() ? surface.faces().stream().filter(face -> face.finish() == BoardSurface.Finish.TOP
              || face.finish() == BoardSurface.Finish.SHORE || face.finish() == BoardSurface.Finish.BED).toList() : surface.top();
    }

    private static final class Batch implements Disposable {
        final boolean crop;
        final int lod;
        final List<Patch> current = new ArrayList<>(), previous = new ArrayList<>();
        final IntArray sizes = new IntArray(), previousSizes = new IntArray();
        final FloatArray data = new FloatArray();
        GpuInstancedMesh mesh;
        ModelInstance instance;
        int capacity;
        long uploads;

        Batch(boolean crop, int lod) { this.crop = crop; this.lod = lod; }
        void begin() { current.clear(); sizes.clear(); }
        void add(Patch patch) { if (patch.roots.size > 0) { current.add(patch); sizes.add(patch.roots.size); } }

        ModelInstance upload(Texture texture) {
            if (current.isEmpty()) { previous.clear(); previousSizes.clear(); return null; }
            if (current.equals(previous) && sizes.equals(previousSizes)) { return data.size == 0 ? null : instance; }
            data.clear();
            for (Patch patch : current) {
                for (int i = 0; i < patch.roots.size; i += STRIDE) {
                    if (patch.roots.items[i + 3] < DENSITY[lod]) { data.addAll(patch.roots.items, i, STRIDE); }
                }
            }
            previous.clear(); previous.addAll(current);
            previousSizes.clear(); previousSizes.addAll(sizes);
            if (data.size == 0) { return null; }
            if (mesh == null) {
                Mesh template = template(crop, lod);
                try { mesh = new GpuInstancedMesh(template); } finally { template.dispose(); }
                var builder = new ModelBuilder();
                builder.begin();
                var material = new Material(ColorAttribute.createDiffuse(Color.WHITE), IntAttribute.createCullFace(GL20.GL_NONE),
                      new Kind(crop), new FloatAttribute(Kind.LOD, lod));
                if (texture != null) { material.set(TextureAttribute.createDiffuse(texture)); }
                builder.part(crop ? "crop" : "reeds", mesh, GL20.GL_TRIANGLES, material);
                builder.manage(mesh);
                instance = new ModelInstance(builder.end());
            }
            int count = data.size / STRIDE;
            if (count > capacity) {
                if (capacity > 0) { mesh.disableInstancedRendering(); }
                capacity = Math.max(count, Math.max(256, capacity * 2));
                mesh.enableInstancedRendering(false, capacity, new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverRoot"));
            }
            mesh.setInstanceData(data.items, 0, data.size);
            uploads++;
            return instance;
        }

        @Override
        public void dispose() {
            if (instance != null) { instance.model.dispose(); }
            mesh = null; instance = null; capacity = 0;
            begin(); previous.clear(); previousSizes.clear(); data.clear();
        }
    }

    private final Map<Coords, Patch> patches = new LinkedHashMap<>(64, .75f, true);
    private final Batch[] batches = { new Batch(true, 0), new Batch(true, 1), new Batch(true, 2),
          new Batch(false, 0), new Batch(false, 1), new Batch(false, 2) };
    private List<BoardScene.Tile> tiles;
    private int revision = -1, boardId = -1;
    private long generation;
    private boolean preparing;
    private Texture sedge;

    static String vertex(String source) {
        String plant = Gdx.files.classpath("megamek/client/ui/clientGUI/boardview/gpu/terrain-biome-vegetation.glsl").readString();
        for (int lod = 0; lod < DENSITY.length; lod++) {
            plant = plant.replace("@START" + lod + "@", Float.toString(START_PIXELS[lod]))
                  .replace("@FULL" + lod + "@", Float.toString(FULL_PIXELS[lod]));
        }
        return source.replace("void main() {", plant + "\nvoid main() {\nvec3 coverPosition, coverNormal; vec4 coverColor;\n"
                    + "biomePlant(a_position, a_normal, a_color, coverPosition, coverNormal, coverColor);\n")
              .replace("vec4 pos = u_worldTrans * vec4(a_position, 1.0);", "vec4 pos = vec4(coverPosition, 1.0);")
              .replace("vec3 normal = normalize(u_normalMatrix * a_normal);", "vec3 normal = coverNormal;")
              .replace("v_color = a_color;", "v_color = coverColor;");
    }

    List<ModelInstance> visible(BoardScene scene, Camera camera, List<BoardScene.Tile> candidates,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        if (revision != BoardGeometry.revision() || boardId != scene.boardId()) {
            dispose(); revision = BoardGeometry.revision(); boardId = scene.boardId();
        }
        if (tiles != scene.tiles()) { tiles = scene.tiles(); generation++; }
        for (Batch batch : batches) { batch.begin(); }
        Set<Coords> visible = new HashSet<>();
        preparing = false;
        long deadline = System.nanoTime() + BUILD_NANOS;
        for (var tile : candidates) {
            var kind = BoardBiome.plantKind(scene, tile);
            if (kind != BoardScene.Biome.FIELD && kind != BoardScene.Biome.MARSH) { continue; }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            float radius = BoardGeometry.width() * .8f;
            if (!camera.frustum.sphereInFrustum(center, radius)) { continue; }
            float nearPixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera,
                  new Vector3(center).mulAdd(camera.direction, -radius));
            if (nearPixels <= START_PIXELS[2]) { continue; }
            float farPixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, center.mulAdd(camera.direction, radius));
            float density = nearPixels > START_PIXELS[0] ? DENSITY[0]
                  : nearPixels > START_PIXELS[1] ? DENSITY[1] : DENSITY[2];
            visible.add(tile.coords());
            var surface = surfaces.apply(tile.coords());
            if (surface == null) { continue; }
            Patch patch = patches.get(tile.coords());
            if (patch == null || patch.surface != surface || patch.generation != generation) {
                if (patch == null && System.nanoTime() >= deadline) { preparing = true; continue; }
                var key = BoardVegetation.key(scene, tile);
                if (patch == null || !patch.key.equals(key) || !patch.ground.equals(support(tile, surface))) {
                    Patch previous = patch == null ? null : patch.previous == null ? patch : patch.previous;
                    boolean retain = previous != null && previous.kind == kind && (previous.key.equals(key)
                          || previous.ground.equals(support(tile, surface)));
                    // Mesh LOD replacements share the same frame budget as first-time preparation.
                    if (System.nanoTime() >= deadline) {
                        preparing = true;
                        if (retain) { add(previous, kind, nearPixels, farPixels); }
                        else { patches.remove(tile.coords()); }
                        continue;
                    }
                    patch = new Patch(scene, tile, surface, generation);
                    // Keep complete roots while an unchanged floor's cover boundary or its mesh LOD updates.
                    // Changed elevations discard old roots so plants cannot float over or through the new ground.
                    if (retain) { patch.previous = previous; }
                    patches.put(tile.coords(), patch);
                }
                patch.surface = surface;
                patch.generation = generation;
            }
            patch.prepare(scene, tile, density, deadline);
            preparing |= patch.busy();
            if (!patch.busy()) { patch.previous = null; }
            Patch rendered = patch.previous == null ? patch : patch.previous;
            add(rendered, kind, nearPixels, farPixels);
        }
        List<ModelInstance> result = new ArrayList<>();
        for (Batch batch : batches) {
            if (!batch.crop && !batch.current.isEmpty() && sedge == null) {
                sedge = sedgeTexture();
                sedge.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                sedge.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            }
            ModelInstance instance = batch.upload(batch.crop ? null : sedge);
            if (instance != null) { result.add(instance); }
        }
        var iterator = patches.entrySet().iterator();
        while (patches.size() > CACHE_SIZE && iterator.hasNext()) {
            if (!visible.contains(iterator.next().getKey())) { iterator.remove(); }
        }
        return result;
    }

    private void add(Patch patch, BoardScene.Biome kind, float nearPixels, float farPixels) {
        for (int lod = 0; lod < DENSITY.length; lod++) {
            if (nearPixels > START_PIXELS[lod] && (lod == 0 || farPixels < FULL_PIXELS[lod - 1])) {
                batches[(kind == BoardScene.Biome.FIELD ? 0 : 3) + lod].add(patch);
            }
        }
    }

    boolean busy() { return preparing; }
    long uploads() { long total = 0; for (Batch batch : batches) { total += batch.uploads; } return total; }

    /** One 512px cutout with mipmaps for the whole board; keep the full-resolution source as the editable asset. */
    private static Texture sedgeTexture() {
        var source = new Pixmap(Gdx.files.classpath("megamek/client/ui/clientGUI/boardview/gpu/marsh-sedge.png"));
        try {
            var pixels = new Pixmap(512, 512, Pixmap.Format.RGBA8888);
            try {
                pixels.setBlending(Pixmap.Blending.None);
                pixels.setFilter(Pixmap.Filter.BiLinear);
                pixels.drawPixmap(source, 0, 0, source.getWidth(), source.getHeight(), 0, 0, 512, 512);
                return new Texture(pixels, true);
            } finally { pixels.dispose(); }
        } finally { source.dispose(); }
    }

    /** Crops use solid leaves; marsh clumps use bent cutouts. Each tier reduces geometry and root density. */
    private static Mesh template(boolean crop, int lod) {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked
              | (crop ? 0 : VertexAttributes.Usage.TextureCoordinates), GL20.GL_TRIANGLES);
        if (!crop) {
            marsh(mesh, lod);
            return mesh.end();
        }
        Color leaf = new Color(.38f, .48f, .13f, 1), stem = new Color(.40f, .38f, .15f, 1);
        if (lod == 2) {
            // One tapered, camera-facing stalk at distance; the ground shader retains the cultivated rows.
            strip(mesh, new Vector3(), new Vector3(0, 0, 1.04f), .052f, .006f, -(float) Math.PI / 2, leaf);
            return mesh.end();
        }
        strip(mesh, new Vector3(), new Vector3(0, 0, 1), .011f, .004f, 0, stem);
        int leaves = lod == 0 ? 3 : 1;
        for (int j = 0; j < leaves; j++) {
            float turn = j * 2.399963f, reach = .28f + .12f * BoardRelief.hash(j, 3);
            Vector3 from = new Vector3(0, 0, .18f + j * .60f / leaves);
            Vector3 tip = new Vector3(from).add((float) Math.cos(turn) * reach, (float) Math.sin(turn) * reach, .10f);
            // A leaf is one tapered ribbon; the shared vertex shader supplies its wind motion.
            strip(mesh, from, tip, .085f, .002f, turn, leaf);
        }
        strip(mesh, new Vector3(0, 0, .85f), new Vector3(0, 0, 1.04f),
              .04f, .009f, 0, new Color(.57f, .45f, .19f, 1));
        return mesh.end();
    }

    /** Crossed, bent surfaces retain volume from above; the alpha-tested cutout supplies individual curling leaves. */
    private static void marsh(MeshPartBuilder mesh, int lod) {
        int cards = lod == 2 ? 1 : 2;
        int segments = lod == 0 ? 2 : 1;
        for (int card = 0; card < cards; card++) {
            float angle = card * (float) Math.PI / cards;
            Vector3 side = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0);
            Vector3 outward = new Vector3(-side.y, side.x, 0);
            for (int row = 0; row < segments; row++) {
                float low = row / (float) segments, high = (row + 1) / (float) segments;
                Vector3 a = new Vector3(outward).scl((float) Math.sin(low * Math.PI) * .28f).add(0, 0, low * 1.1f);
                Vector3 b = new Vector3(outward).scl((float) Math.sin(high * Math.PI) * .28f).add(0, 0, high * 1.1f);
                Vector3 normal = new Vector3(side).crs(new Vector3(b).sub(a)).nor();
                float bottom = .98f - low * .98f, top = .98f - high * .98f;
                mesh.rect(vertex(new Vector3(a).mulAdd(side, -.58f), normal, Color.WHITE).setUV(0, bottom),
                      vertex(new Vector3(a).mulAdd(side, .58f), normal, Color.WHITE).setUV(1, bottom),
                      vertex(new Vector3(b).mulAdd(side, .58f), normal, Color.WHITE).setUV(1, top),
                      vertex(new Vector3(b).mulAdd(side, -.58f), normal, Color.WHITE).setUV(0, top));
            }
        }
    }

    private static void strip(MeshPartBuilder mesh, Vector3 a, Vector3 b, float wa, float wb, float angle, Color color) {
        Vector3 side = new Vector3(-(float) Math.sin(angle), (float) Math.cos(angle), 0);
        Vector3 normal = new Vector3(side).crs(new Vector3(b).sub(a)).nor();
        mesh.rect(vertex(new Vector3(a).mulAdd(side, -wa), normal, color), vertex(new Vector3(a).mulAdd(side, wa), normal, color),
              vertex(new Vector3(b).mulAdd(side, wb), normal, color), vertex(new Vector3(b).mulAdd(side, -wb), normal, color));
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Vector3 normal, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setCol(color);
    }

    @Override
    public void dispose() {
        for (Batch batch : batches) { batch.dispose(); }
        if (sedge != null) { sedge.dispose(); sedge = null; }
        patches.clear(); tiles = null; preparing = false;
    }
}
