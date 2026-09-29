/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
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
import megamek.common.Configuration;
import megamek.common.board.Coords;

/** World-aligned crop rows and clustered reeds, with shared instanced batches and bounded root preparation. */
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
    // Scale ten stalks from a 3 m strip; lengths stay in world metres, independent of per-row height variation.
    static final float CROP_SCALE = 1.4f;
    static final float ROW_LENGTH = 3 * CROP_SCALE, ROW_HALF_WIDTH = .36f * CROP_SCALE;

    /** CPU-only roots are also useful for verifying ground contact, shared rows and pool/road exclusion. */
    static final class Patch {
        final BoardVegetation.Key key;
        BoardTacticalGeometry.Surface surface;
        final List<BoardSurface.Face> ground;
        final Support support, water;
        final BoardScene.Biome kind;
        final boolean uniform;
        final float levelZ;
        final FloatArray roots = new FloatArray();
        // Crop-only, parallel to roots: supported length, end-to-end rise, and the original strip's U interval.
        final FloatArray rowSpans = new FloatArray();
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
            // A strip contains a whole run of plants. Keep every crop row at every distance;
            // cheaper templates, rather than missing strips, provide its distance reduction.
            if (kind == BoardScene.Biome.FIELD) { tier = 0; }
            levelZ = BoardGeometry.groundZ(tile);
            uniform = key.sites().stream().allMatch(site -> site != null && site.biome() == kind
                  && site.elevation() == tile.elevation());
            road = BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
            across = BoardBiome.ROW_METRES;
            along = kind == BoardScene.Biome.FIELD ? ROW_LENGTH : 1.15f;
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
            requested = kind == BoardScene.Biome.FIELD ? 1 : density;
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
                // Plant alternate furrows: larger plants keep 2.3 m row spacing without shifting the terrain's ridges.
                if (kind == BoardScene.Biome.FIELD && (ix & 1) != 0) { continue; }
                if (kind != BoardScene.Biome.FIELD && BoardRelief.hash(ix + 379, iy - 827) >= .46f) { continue; }
                float seed = BoardRelief.hash(ix, iy);
                if (kind != BoardScene.Biome.FIELD
                      && (seed >= DENSITY[tier] || (tier < 2 && seed < DENSITY[tier + 1]))) { continue; }
                if (kind == BoardScene.Biome.FIELD) {
                    row(scene, tile, ix * across, (iy + .5f) * along, seed);
                    continue;
                }
                float u = (ix + (BoardRelief.hash(ix + 37, iy) - .5f) * .7f) * across;
                float v = (iy + (BoardRelief.hash(ix, iy + 17) - .5f) * .55f) * along;
                float px = (u * BoardBiome.ROW_X - v * BoardBiome.ROW_Y) * metre;
                float py = (u * BoardBiome.ROW_Y + v * BoardBiome.ROW_X) * metre;
                if (BoardGeometry.tile(scene, px, py) != tile) { continue; }
                // Water's tactical top includes the water plane. Roots instead follow the actual bank/bar mesh.
                float z = support.height(px, py);
                if (!Float.isFinite(z)) { continue; }
                // An interior plateau has unit coverage. Boundary and sloping samples still use the shared field.
                float cover = uniform && Math.abs(z - levelZ) <= .15f * metre ? 1
                      : BoardBiome.coverage(scene, kind, px, py, z);
                if (seed > .88f * BoardRelief.smooth(cover)) { continue; }
                if (water != null) {
                    if (z <= water.height(px, py) + .015f * metre) { continue; }
                }
                if (road != null && road.distance((px - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale(),
                      (py - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale()) < BoardRoad.SHOULDER + 1) { continue; }
                float wet = BoardBiome.wetness(px / metre, py / metre);
                if (wet < .48f || wet > .80f) { continue; }
                roots.addAll(px, py, z - .018f * metre, seed);
            }
        }

        boolean busy() { return prepared < requested; }

        /** Clip each global row segment to this hex, then split at unsupported ground and roads. */
        private void row(BoardScene scene, BoardScene.Tile tile, float u, float v, float seed) {
            float metre = BoardRelief.metres(1);
            float px = (u * BoardBiome.ROW_X - v * BoardBiome.ROW_Y) * metre;
            float py = (u * BoardBiome.ROW_Y + v * BoardBiome.ROW_X) * metre;
            float low = -ROW_LENGTH / 2, high = ROW_LENGTH / 2;
            for (int edge = 0; edge < 6; edge++) {
                var a = BoardGeometry.corner(tile.coords(), 0, edge);
                var b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
                float ex = b.x - a.x, ey = b.y - a.y;
                float distance = ex * (py - a.y) - ey * (px - a.x);
                float rate = (ex * BoardBiome.ROW_X + ey * BoardBiome.ROW_Y) * metre;
                if (Math.abs(rate) < .00001f) { if (distance < 0) { return; } }
                else if (rate > 0) { low = Math.max(low, -distance / rate); }
                else { high = Math.min(high, -distance / rate); }
            }
            if (high - low < .03f) { return; }
            // Sample more finely than a stalk interval; merge only while all samples fit the same ground plane.
            int steps = Math.max(1, (int) Math.ceil((high - low) / .15f));
            float[] heights = new float[2 * steps + 1];
            float interval = (high - low) / (heights.length - 1);
            for (int i = 0; i < heights.length; i++) {
                heights[i] = rowHeight(scene, tile, px, py, Math.clamp(low + interval * i, low + .0001f, high - .0001f));
            }
            int start = 0;
            while (start < heights.length - 2) {
                int end = start;
                for (int candidate = start + 2; candidate < heights.length; candidate += 2) {
                    boolean fits = Float.isFinite(heights[start]) && Float.isFinite(heights[candidate]);
                    for (int i = start + 1; fits && i < candidate; i++) {
                        float expected = heights[start] + (heights[candidate] - heights[start]) * (i - start) / (candidate - start);
                        fits = Float.isFinite(heights[i]) && Math.abs(heights[i] - expected) < .008f * metre;
                    }
                    if (!fits) { break; }
                    end = candidate;
                }
                if (end > start) {
                    span(px, py, low + interval * start, low + interval * end, heights[start], heights[end], seed);
                }
                start = Math.max(start + 2, end);
            }
        }

        private float rowHeight(BoardScene scene, BoardScene.Tile tile, float px, float py, float offset) {
            float metre = BoardRelief.metres(1);
            float x = px - offset * BoardBiome.ROW_Y * metre, y = py + offset * BoardBiome.ROW_X * metre;
            float z = support.height(x, y);
            if (!Float.isFinite(z)) { return Float.NaN; }
            if ((!uniform || Math.abs(z - levelZ) > .15f * metre)
                  && BoardBiome.coverage(scene, kind, x, y, z) < .60f) { return Float.NaN; }
            // Include the horizontal canopy's reach in the road clearance.
            if (road != null && road.distance((x - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale(),
                  (y - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale())
                  < BoardRoad.SHOULDER + 1 + ROW_HALF_WIDTH * metre / BoardGeometry.hexScale()) { return Float.NaN; }
            return z;
        }

        private void span(float px, float py, float start, float end, float startZ, float endZ, float seed) {
            float metre = BoardRelief.metres(1), middle = (start + end) / 2;
            float x = px - middle * BoardBiome.ROW_Y * metre, y = py + middle * BoardBiome.ROW_X * metre;
            roots.addAll(x, y, support.height(x, y) - .018f * metre, seed);
            rowSpans.addAll((end - start) * metre, endZ - startZ, .5f + start / ROW_LENGTH, .5f + end / ROW_LENGTH);
        }
    }

    /** Small CPU index of the published triangles, not a second height field. No per-root whole-mesh scan. */
    static final class Support {
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
        final int stride;
        final List<Patch> current = new ArrayList<>(), previous = new ArrayList<>();
        final IntArray sizes = new IntArray(), previousSizes = new IntArray();
        final FloatArray data = new FloatArray();
        GpuInstancedMesh mesh;
        ModelInstance instance;
        int capacity;
        long uploads;

        Batch(boolean crop, int lod) { this.crop = crop; this.lod = lod; stride = crop ? 8 : STRIDE; }
        void begin() { current.clear(); sizes.clear(); }
        void add(Patch patch) { if (patch.roots.size > 0) { current.add(patch); sizes.add(patch.roots.size); } }

        ModelInstance upload(Texture texture) {
            if (current.isEmpty()) { previous.clear(); previousSizes.clear(); return null; }
            if (current.equals(previous) && sizes.equals(previousSizes)) { return data.size == 0 ? null : instance; }
            data.clear();
            for (Patch patch : current) {
                for (int i = 0; i < patch.roots.size; i += STRIDE) {
                    if (crop || patch.roots.items[i + 3] < DENSITY[lod]) {
                        data.addAll(patch.roots.items, i, STRIDE);
                        if (crop) { data.addAll(patch.rowSpans.items, i, STRIDE); }
                    }
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
            int count = data.size / stride;
            if (count > capacity) {
                if (capacity > 0) { mesh.disableInstancedRendering(); }
                capacity = Math.max(count, Math.max(256, capacity * 2));
                var root = new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverRoot");
                // libGDX indexes instance attributes by usage/unit, separately from the mesh's vertex attributes.
                mesh.enableInstancedRendering(false, capacity, crop ? new VertexAttribute[] { root,
                      new VertexAttribute(VertexAttributes.Usage.Position, 4, "a_coverRow") } : new VertexAttribute[] { root });
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
    // Derived once per immutable terrain snapshot, including marsh fringe hexes.
    private final Map<Coords, BoardScene.Biome> kinds = new HashMap<>();
    private int revision = -1, boardId = -1;
    private long generation;
    private boolean preparing;
    private Texture crop, sedge;
    /** Render-owned submission snapshot; installed support identities still invalidate a stationary view. */
    private record View(float[] projectionView, float viewportPixels, List<BoardScene.Tile> candidates,
          Set<Coords> visible, List<ModelInstance> instances) { }
    private View view;

    static String vertex(String source) {
        String wind = GpuShaderSource.read("terrain-vegetation-wind.glsl");
        String plant = GpuShaderSource.read("terrain-biome-vegetation.glsl");
        for (int lod = 0; lod < DENSITY.length; lod++) {
            plant = plant.replace("@START" + lod + "@", Float.toString(START_PIXELS[lod]))
                  .replace("@FULL" + lod + "@", Float.toString(FULL_PIXELS[lod]));
        }
        plant = plant.replace("@ROW_X@", Float.toString(BoardBiome.ROW_X))
              .replace("@ROW_Y@", Float.toString(BoardBiome.ROW_Y))
              .replace("@ROW_METRES@", Float.toString(BoardBiome.ROW_METRES))
              .replace("@CROP_SCALE@", Float.toString(CROP_SCALE));
        return source.replace("void main() {", wind + plant + "\nvoid main() {\nvec3 coverPosition, coverNormal; vec4 coverColor;\n"
                    + "biomePlant(a_position, a_normal, a_color, coverPosition, coverNormal, coverColor);\n")
              .replace("v_diffuseUV = u_diffuseUVTransform.xy + a_texCoord0 * u_diffuseUVTransform.zw;",
                    "v_diffuseUV = u_diffuseUVTransform.xy + a_texCoord0 * u_diffuseUVTransform.zw;\n"
                          + "if (u_biomeKind < 1.5 && a_coverRow.x > 0.0) "
                          + "v_diffuseUV.x = mix(a_coverRow.z, a_coverRow.w, a_texCoord0.x);")
              .replace("vec4 pos = u_worldTrans * vec4(a_position, 1.0);", "vec4 pos = vec4(coverPosition, 1.0);")
              .replace("vec3 normal = normalize(u_normalMatrix * a_normal);", "vec3 normal = coverNormal;")
              .replace("v_color = a_color;", "v_color = coverColor;");
    }

    List<ModelInstance> visible(BoardScene scene, Camera camera, List<BoardScene.Tile> candidates,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        if (revision != BoardGeometry.revision() || boardId != scene.boardId()) {
            dispose(); revision = BoardGeometry.revision(); boardId = scene.boardId();
        }
        if (tiles != scene.tiles()) {
            tiles = scene.tiles(); generation++; view = null;
            kinds.clear();
            for (var tile : tiles) {
                var kind = BoardBiome.plantKind(scene, tile);
                if (kind == BoardScene.Biome.FIELD || kind == BoardScene.Biome.MARSH) { kinds.put(tile.coords(), kind); }
            }
        }
        float viewportPixels = Gdx.graphics == null ? camera.viewportHeight
              : camera.viewportHeight * Gdx.graphics.getBackBufferHeight() / Math.max(1f, Gdx.graphics.getHeight());
        if (!preparing && view != null && view.viewportPixels() == viewportPixels
              && Arrays.equals(view.projectionView(), camera.combined.val) && view.candidates().equals(candidates)) {
            boolean current = true;
            for (Coords coords : view.visible()) {
                Patch patch = patches.get(coords);
                if (patch == null || patch.surface != surfaces.apply(coords)) { current = false; break; }
            }
            if (current) { return view.instances(); }
        }
        for (Batch batch : batches) { batch.begin(); }
        Set<Coords> visible = new HashSet<>();
        preparing = false;
        long deadline = System.nanoTime() + BUILD_NANOS;
        for (var tile : candidates) {
            var kind = kinds.get(tile.coords());
            if (kind == null) { continue; }
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
            Patch patch = patches.get(tile.coords());
            // A cold support lookup can rebuild geometry; include it in the preparation budget.
            if (patch == null && System.nanoTime() >= deadline) { preparing = true; continue; }
            var surface = surfaces.apply(tile.coords());
            if (surface == null) { continue; }
            if (patch == null || patch.surface != surface || patch.generation != generation) {
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
            Texture texture = batch.crop ? crop : sedge;
            if (!batch.current.isEmpty() && texture == null) {
                texture = batch.crop ? rowTexture() : plantTexture("marsh-sedge");
                if (batch.crop) { crop = texture; } else { sedge = texture; }
            }
            ModelInstance instance = batch.upload(texture);
            if (instance != null) { result.add(instance); }
        }
        var iterator = patches.entrySet().iterator();
        while (patches.size() > CACHE_SIZE && iterator.hasNext()) {
            if (!visible.contains(iterator.next().getKey())) { iterator.remove(); }
        }
        view = new View(camera.combined.val.clone(), viewportPixels, List.copyOf(candidates), visible, result);
        return result;
    }

    private void add(Patch patch, BoardScene.Biome kind, float nearPixels, float farPixels) {
        if (kind == BoardScene.Biome.FIELD) {
            // The near and medium crop templates are identical. Use one full-row batch, then
            // cross-fade to continuous overhead strips when individual stalks become too small.
            if (nearPixels > START_PIXELS[1]) { batches[0].add(patch); }
            if (nearPixels > START_PIXELS[2] && farPixels < FULL_PIXELS[1]) { batches[2].add(patch); }
            return;
        }
        for (int lod = 0; lod < DENSITY.length; lod++) {
            if (nearPixels > START_PIXELS[lod] && (lod == 0 || farPixels < FULL_PIXELS[lod - 1])) {
                batches[(kind == BoardScene.Biome.FIELD ? 0 : 3) + lod].add(patch);
            }
        }
    }

    boolean busy() { return preparing; }
    long uploads() { long total = 0; for (Batch batch : batches) { total += batch.uploads; } return total; }

    /** Side and overhead artwork share one sampler and one instanced draw per tier. */
    private static Texture rowTexture() {
        var pixels = new Pixmap(512, 640, Pixmap.Format.RGBA8888);
        try {
            pixels.setBlending(Pixmap.Blending.None);
            pixels.setFilter(Pixmap.Filter.BiLinear);
            for (String name : List.of("crop-row-side", "crop-row-top")) {
                var source = new Pixmap(new FileHandle(new File(Configuration.dataDir(),
                      "models/board/textures/foliage/" + name + ".png")));
                try {
                    // Remove only empty source margins, so photographed root bases meet the card's ground edge.
                    int left = source.getWidth(), right = 0, top = source.getHeight(), bottom = 0;
                    for (int y = 0; y < source.getHeight(); y++) {
                        for (int x = 0; x < source.getWidth(); x++) {
                            if ((source.getPixel(x, y) & 255) < 32) { continue; }
                            left = Math.min(left, x); right = Math.max(right, x);
                            top = Math.min(top, y); bottom = Math.max(bottom, y);
                        }
                    }
                    boolean overhead = name.endsWith("top");
                    pixels.drawPixmap(source, left, top, right - left + 1, bottom - top + 1,
                          0, overhead ? 512 : 0, 512, overhead ? 128 : 512);
                } finally { source.dispose(); }
            }
            return filteredTexture(pixels);
        } finally { pixels.dispose(); }
    }

    private static Texture filteredTexture(Pixmap pixels) {
        var texture = new Texture(pixels, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return texture;
    }

    /** One shared 512px reed cutout; keep the full-resolution source as the editable asset. */
    private static Texture plantTexture(String name) {
        var source = new Pixmap(new FileHandle(new File(Configuration.dataDir(),
              "models/board/textures/foliage/" + name + ".png")));
        try {
            var pixels = new Pixmap(512, 512, Pixmap.Format.RGBA8888);
            try {
                pixels.setBlending(Pixmap.Blending.None);
                pixels.setFilter(Pixmap.Filter.BiLinear);
                pixels.drawPixmap(source, 0, 0, source.getWidth(), source.getHeight(), 0, 0, 512, 512);
                return filteredTexture(pixels);
            } finally { pixels.dispose(); }
        } finally { source.dispose(); }
    }

    /** Two crossed row faces and a canopy cover crops from every side; reeds retain their bent crossed cards. */
    private static Mesh template(boolean crop, int lod) {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked
              | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
        if (crop) {
            // The shader opens both row faces above their grounded centreline. Far strips retain only the canopy.
            if (lod < 2) {
                Vector3 normal = new Vector3(0, -1, 0);
                for (float side : new float[] { -ROW_HALF_WIDTH, ROW_HALF_WIDTH }) {
                    mesh.rect(vertex(new Vector3(-.5f, side, 0), normal, Color.WHITE).setUV(0, 511.5f / 640),
                          vertex(new Vector3(.5f, side, 0), normal, Color.WHITE).setUV(1, 511.5f / 640),
                          vertex(new Vector3(.5f, side, 1.04f), normal, Color.WHITE).setUV(1, .5f / 640),
                          vertex(new Vector3(-.5f, side, 1.04f), normal, Color.WHITE).setUV(0, .5f / 640));
                }
            }
            Vector3 normal = new Vector3(0, 0, 1);
            mesh.rect(vertex(new Vector3(-.5f, -ROW_HALF_WIDTH, .72f), normal, Color.WHITE).setUV(0, 512.5f / 640),
                  vertex(new Vector3(.5f, -ROW_HALF_WIDTH, .72f), normal, Color.WHITE).setUV(1, 512.5f / 640),
                  vertex(new Vector3(.5f, ROW_HALF_WIDTH, .72f), normal, Color.WHITE).setUV(1, 639.5f / 640),
                  vertex(new Vector3(-.5f, ROW_HALF_WIDTH, .72f), normal, Color.WHITE).setUV(0, 639.5f / 640));
            return mesh.end();
        }
        int cards = lod == 2 ? 1 : 2;
        int segments = lod == 0 ? 2 : 1;
        float height = 1.1f, width = .58f;
        for (int card = 0; card < cards; card++) {
            float angle = card * (float) Math.PI / cards;
            Vector3 side = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0);
            Vector3 outward = new Vector3(-side.y, side.x, 0);
            for (int row = 0; row < segments; row++) {
                float low = row / (float) segments, high = (row + 1) / (float) segments;
                Vector3 a = new Vector3(outward).scl((float) Math.sin(low * Math.PI) * .28f).add(0, 0, low * height);
                Vector3 b = new Vector3(outward).scl((float) Math.sin(high * Math.PI) * .28f).add(0, 0, high * height);
                Vector3 normal = new Vector3(side).crs(new Vector3(b).sub(a)).nor();
                float bottom = .98f - low * .98f, top = .98f - high * .98f;
                mesh.rect(vertex(new Vector3(a).mulAdd(side, -width), normal, Color.WHITE).setUV(0, bottom),
                      vertex(new Vector3(a).mulAdd(side, width), normal, Color.WHITE).setUV(1, bottom),
                      vertex(new Vector3(b).mulAdd(side, width), normal, Color.WHITE).setUV(1, top),
                      vertex(new Vector3(b).mulAdd(side, -width), normal, Color.WHITE).setUV(0, top));
            }
        }
        return mesh.end();
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Vector3 normal, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setCol(color);
    }

    @Override
    public void dispose() {
        for (Batch batch : batches) { batch.dispose(); }
        if (crop != null) { crop.dispose(); crop = null; }
        if (sedge != null) { sedge.dispose(); sedge = null; }
        patches.clear(); kinds.clear(); tiles = null; view = null; preparing = false;
    }
}
