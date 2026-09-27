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
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
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

    private static final float[] DENSITY = { 1, .5f, .18f };
    // Cumulative coverage: fine plants replace medium plants, which replace the distant stems.
    // The same intervals drive conservative CPU submission and the per-root GPU cross-fade.
    private static final float[] START_PIXELS = { 240, 64, 12 }, FULL_PIXELS = { 480, 128, 36 };
    private static final int CACHE_SIZE = 384, STRIDE = 4;
    private static final long BUILD_NANOS = 2_000_000;

    /** CPU-only roots are also useful for verifying ground contact, shared rows and pool/road exclusion. */
    static final class Patch {
        final BoardSurface.Key key;
        final BoardTacticalGeometry.Surface surface;
        final BoardScene.Biome kind;
        final FloatArray roots = new FloatArray();
        final BoardRoad road;
        final float across, along;
        final int minX, maxX, maxY;
        int x, y;
        long generation;
        // Keep the rendered roots during a terrain LOD handoff, until this replacement is complete.
        Patch previous;

        Patch(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface, long generation) {
            key = BoardSurface.geometryKey(scene, tile);
            this.surface = surface;
            this.generation = generation;
            kind = BoardBiome.kind(tile);
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
            y = (int) Math.floor((v - 21) / along);
            maxY = (int) Math.ceil((v + 21) / along);
        }

        void prepare(BoardScene scene, BoardScene.Tile tile, long deadline) {
            int checked = 0;
            float metre = BoardRelief.metres(1);
            while (y <= maxY) {
                if ((checked++ & 31) == 0 && System.nanoTime() >= deadline) { break; }
                int ix = x++, iy = y;
                if (x > maxX) { x = minX; y++; }
                float seed = BoardRelief.hash(ix, iy);
                float u = (ix + (BoardRelief.hash(ix + 37, iy) - .5f)
                      * (kind == BoardScene.Biome.FIELD ? .12f : .7f)) * across;
                float v = (iy + (BoardRelief.hash(ix, iy + 17) - .5f) * .55f) * along;
                float px = (u * BoardBiome.ROW_X - v * BoardBiome.ROW_Y) * metre;
                float py = (u * BoardBiome.ROW_Y + v * BoardBiome.ROW_X) * metre;
                if (BoardGeometry.tile(scene, px, py) != tile) { continue; }
                float z = BoardSurface.sampleHeight(surface.top(), px, py, Float.NaN);
                if (!Float.isFinite(z) || BoardBiome.coverage(scene, kind, px, py, z) < .60f) { continue; }
                if (road != null && road.distance((px - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale(),
                      (py - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale()) < BoardRoad.SHOULDER + 1) { continue; }
                if (kind == BoardScene.Biome.MARSH) {
                    float wet = BoardBiome.wetness(px / metre, py / metre);
                    if (wet < .48f || wet > .80f || seed > .88f) { continue; }
                } else if (seed > .96f) { continue; }
                roots.addAll(px, py, z - .018f * metre, seed);
            }
        }

        boolean busy() { return y <= maxY; }
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

        ModelInstance upload() {
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
                builder.part(crop ? "crop" : "reeds", mesh, GL20.GL_TRIANGLES,
                      new Material(ColorAttribute.createDiffuse(Color.WHITE), IntAttribute.createCullFace(GL20.GL_NONE),
                            new Kind(crop), new FloatAttribute(Kind.LOD, lod)));
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
            var kind = BoardBiome.kind(tile);
            if (kind != BoardScene.Biome.FIELD && kind != BoardScene.Biome.MARSH) { continue; }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            float radius = BoardGeometry.width() * .8f;
            if (!camera.frustum.sphereInFrustum(center, radius)) { continue; }
            float nearPixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera,
                  new Vector3(center).mulAdd(camera.direction, -radius));
            if (nearPixels <= START_PIXELS[2]) { continue; }
            float farPixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, center.mulAdd(camera.direction, radius));
            visible.add(tile.coords());
            var surface = surfaces.apply(tile.coords());
            if (surface == null) { continue; }
            Patch patch = patches.get(tile.coords());
            if (patch == null || patch.surface != surface || patch.generation != generation) {
                if (patch == null && System.nanoTime() >= deadline) { preparing = true; continue; }
                if (patch == null || patch.surface != surface || !patch.key.equals(BoardSurface.geometryKey(scene, tile))) {
                    Patch previous = patch == null ? null : patch.previous == null ? patch : patch.previous;
                    patch = new Patch(scene, tile, surface, generation);
                    // Actual terrain edits must discard stale plants; a LOD-only surface change may retain them.
                    if (previous != null && previous.key.equals(patch.key)) { patch.previous = previous; }
                    patches.put(tile.coords(), patch);
                }
                patch.generation = generation;
            }
            patch.prepare(scene, tile, deadline);
            preparing |= patch.busy();
            if (!patch.busy()) { patch.previous = null; }
            Patch rendered = patch.previous == null ? patch : patch.previous;
            for (int lod = 0; lod < DENSITY.length; lod++) {
                if (nearPixels > START_PIXELS[lod] && (lod == 0 || farPixels < FULL_PIXELS[lod - 1])) {
                    batches[(kind == BoardScene.Biome.FIELD ? 0 : 3) + lod].add(rendered);
                }
            }
        }
        List<ModelInstance> result = new ArrayList<>();
        for (Batch batch : batches) {
            ModelInstance instance = batch.upload();
            if (instance != null) { result.add(instance); }
        }
        var iterator = patches.entrySet().iterator();
        while (patches.size() > CACHE_SIZE && iterator.hasNext()) {
            if (!visible.contains(iterator.next().getKey())) { iterator.remove(); }
        }
        return result;
    }

    boolean busy() { return preparing; }
    long uploads() { long total = 0; for (Batch batch : batches) { total += batch.uploads; } return total; }

    /** Solid leaf strips, not texture cards; lower tiers reduce both leaves and the number of plants. */
    private static Mesh template(boolean crop, int lod) {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked, GL20.GL_TRIANGLES);
        if (!crop) {
            marsh(mesh, lod);
            return mesh.end();
        }
        Color leaf = new Color(.29f, .37f, .095f, 1), stem = new Color(.40f, .38f, .15f, 1);
        int stems = crop ? 1 : new int[] { 7, 4, 2 }[lod];
        for (int i = 0; i < stems; i++) {
            float angle = i * 2.399963f;
            Vector3 base = crop ? new Vector3() : new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0).scl(.22f);
            float height = crop ? 1 : .63f + BoardRelief.hash(i, 12) * .37f;
            strip(mesh, base, new Vector3(base).add(0, 0, height), crop ? .007f : .009f, .004f, angle, stem);
            int leaves = crop ? new int[] { 8, 4, 2 }[lod] : lod == 0 ? 2 : 1;
            for (int j = 0; j < leaves; j++) {
                float turn = angle + j * 2.399963f;
                float reach = crop ? .18f + .08f * BoardRelief.hash(j, 3) : .13f;
                Vector3 from = new Vector3(base).add(0, 0, height * (.18f + j * .60f / leaves));
                Vector3 tip = new Vector3(from).add((float) Math.cos(turn) * reach, (float) Math.sin(turn) * reach, .04f);
                float width = crop ? .032f : .013f;
                if (lod == 0) {
                    Vector3 middle = new Vector3(from).lerp(tip, .5f).add(0, 0, .08f);
                    strip(mesh, from, middle, width * .35f, width, turn, leaf);
                    strip(mesh, middle, tip, width, .001f, turn, leaf);
                } else { strip(mesh, from, tip, width, .001f, turn, leaf); }
            }
            if (crop || i % 3 == 0) {
                strip(mesh, new Vector3(base).add(0, 0, height * .85f), new Vector3(base).add(0, 0, height * 1.04f),
                      crop ? .023f : .014f, crop ? .009f : .014f, angle, new Color(.57f, .45f, .19f, 1));
            }
            if (!crop) {
                Vector3 middle = new Vector3(base).add((float) Math.cos(angle) * .13f, (float) Math.sin(angle) * .13f, .32f);
                Vector3 tip = new Vector3(base).add((float) Math.cos(angle) * .34f, (float) Math.sin(angle) * .34f, .16f);
                strip(mesh, base, middle, .016f, .035f, angle, leaf);
                if (lod < 2) { strip(mesh, middle, tip, .035f, .001f, angle, leaf); }
            }
        }
        return mesh.end();
    }

    /** Dense basal sedge, bent dead leaves and a few emergent cattails share one opaque instanced mesh. */
    private static void marsh(MeshPartBuilder mesh, int lod) {
        int blades = new int[] { 42, 18, 7 }[lod];
        for (int i = 0; i < blades; i++) {
            float angle = i * 2.399963f;
            float seed = BoardRelief.hash(i, 43), length = .32f + .42f * BoardRelief.hash(i, 71);
            float radius = .06f + .18f * BoardRelief.hash(i, 91);
            Vector3 base = new Vector3((float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius, 0);
            Vector3 side = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0);
            Color color = new Color(.26f, .31f, .095f, .3f).lerp(new Color(.49f, .40f, .21f, .3f), seed * .75f);
            float width = .012f + .012f * seed;
            Vector3 middle = new Vector3(base).mulAdd(side, length * .32f).add(0, 0, length * .7f);
            Vector3 tip = new Vector3(base).mulAdd(side, length).add(0, 0, length * .22f);
            strip(mesh, base, middle, width * .7f, width, angle, color);
            if (lod < 2) {
                Vector3 shoulder = new Vector3(base).mulAdd(side, length * .7f).add(0, 0, length * .60f);
                strip(mesh, middle, shoulder, width, width * .6f, angle, color);
                strip(mesh, shoulder, tip, width * .6f, .001f, angle, color);
            } else { strip(mesh, middle, tip, width, .001f, angle, color); }
        }
        int stems = new int[] { 13, 6, 3 }[lod];
        for (int i = 0; i < stems; i++) {
            float angle = i * 2.399963f, seed = BoardRelief.hash(i, 127);
            float height = .7f + .5f * seed, radius = .08f + .30f * BoardRelief.hash(i, 17);
            Vector3 base = new Vector3((float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius, 0);
            Vector3 head = new Vector3(base).add((float) Math.cos(angle) * .08f, (float) Math.sin(angle) * .08f, height);
            Color green = new Color(.23f, .30f, .10f, .8f).lerp(new Color(.43f, .39f, .20f, .8f), seed);
            strip(mesh, base, head, .010f, .005f, angle, green);
            int leaves = lod == 0 ? 3 : 1;
            for (int j = 0; j < leaves; j++) {
                float turn = angle + j * 2.399963f;
                Vector3 from = new Vector3(base).add(0, 0, height * (.12f + j * .18f));
                Vector3 bend = new Vector3(from).add((float) Math.cos(turn) * .14f, (float) Math.sin(turn) * .14f, height * .42f);
                Vector3 tip = new Vector3(from).add((float) Math.cos(turn) * .34f, (float) Math.sin(turn) * .34f, height * .30f);
                strip(mesh, from, bend, .018f, .022f, turn, green);
                if (lod < 2) { strip(mesh, bend, tip, .022f, .001f, turn, green); }
            }
            if (i % 3 == 0) {
                Color headColor = new Color(.27f, .18f, .075f, .8f);
                for (int side = 0; side < 2; side++) {
                    strip(mesh, new Vector3(head).add(0, 0, -.17f), head, .026f, .017f, angle + side * 1.570796f, headColor);
                }
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
        patches.clear(); tiles = null; preparing = false;
    }
}
