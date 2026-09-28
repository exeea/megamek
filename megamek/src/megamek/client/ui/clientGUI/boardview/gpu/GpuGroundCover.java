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
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import megamek.common.board.Coords;

/** Persistent surface roots, rendered as curved GPU blades in at most two instanced draws. GL ownership stays here. */
final class GpuGroundCover implements Disposable {
    static final class Wind extends FloatAttribute {
        static final long TYPE = register("boardVegetationWind");
        Wind() { super(TYPE, 1); }
        @Override
        public Wind copy() { return new Wind(); }
    }

    private static final int CACHE_SIZE = 384;
    private static final int ROOTS_PER_HEX = 4096;
    private static final int STRIDE = 4;
    private static final float START_PIXELS = 120, FULL_PIXELS = 500;
    private static final long BUILD_NANOS = 2_000_000;
    private static final class Cover {
        final BoardVegetation.Key key;
        BoardTacticalGeometry.Surface surface;
        // Retain valid roots until a changed cover boundary or mesh LOD is fully prepared.
        Cover previous;
        final List<BoardSurface.Face> ground;
        final float[] areas;
        final float total;
        final FloatArray roots = new FloatArray();
        final Random random;
        final BoardRoad road;
        final boolean boundary;
        int samples;
        long generation;

        Cover(BoardScene scene, BoardScene.Tile tile, BoardVegetation.Key key, BoardTacticalGeometry.Surface surface, long generation) {
            this.key = key;
            this.surface = surface;
            this.generation = generation;
            ground = new ArrayList<>(surface.top().stream().filter(face -> face.finish() == BoardSurface.Finish.TOP).toList());
            if (BoardGeometry.tuning().stepsBetweenTops()) { ground.addAll(surface.slopes()); }
            ground.removeIf(face -> new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor().z <= .7f);
            areas = new float[ground.size()];
            float sum = 0;
            for (int i = 0; i < ground.size(); i++) {
                var face = ground.get(i);
                sum += Math.abs((face.b().x - face.a().x) * (face.c().y - face.a().y)
                      - (face.c().x - face.a().x) * (face.b().y - face.a().y));
                areas[i] = sum;
            }
            total = sum;
            random = new Random(tile.coords().getX() * 0x9E3779B97F4A7C15L ^ tile.coords().getY() * 0xC2B2AE3D27D4EB4FL);
            boundary = BoardSurfaceBlend.boundary(scene, tile);
            road = BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
        }

        /** Roots are an ordered, deterministic prefix: zooming extends it, never rearranges existing blades. */
        void prepare(BoardScene scene, BoardScene.Tile tile, int target, long deadline) {
            if (total <= 0) { samples = ROOTS_PER_HEX; return; }
            while (samples < target) {
                if ((samples & 31) == 0 && System.nanoTime() >= deadline) { break; }
                int rank = samples++;
                int index = Arrays.binarySearch(areas, random.nextFloat() * total);
                var face = ground.get(Math.min(ground.size() - 1, index < 0 ? -index - 1 : index));
                float a = random.nextFloat(), b = random.nextFloat();
                if (a + b > 1) { a = 1 - a; b = 1 - b; }
                float x = face.a().x + (face.b().x - face.a().x) * a + (face.c().x - face.a().x) * b;
                float y = face.a().y + (face.b().y - face.a().y) * a + (face.c().y - face.a().y) * b;
                float z = face.a().z + (face.b().z - face.a().z) * a + (face.c().z - face.a().z) * b;
                if (road != null && road.distance((x - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale(),
                      (y - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale()) < BoardRoad.SHOULDER + 1) { continue; }
                float grass = boundary ? BoardSurfaceBlend.sample(scene, tile, x, y, z).grass()
                      : tile.surface() == BoardScene.Surface.GRASS ? 1 : 0;
                if (random.nextFloat() <= grass * BoardRelief.smooth((grass - .55f) / .35f)) {
                    roots.addAll(x, y, z - BoardGeometry.width() * .001f, rank);
                }
            }
        }

        int count(int target) {
            int low = 0, high = roots.size / STRIDE;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (roots.items[middle * STRIDE + 3] < target) { low = middle + 1; } else { high = middle; }
            }
            return low;
        }
    }

    /** Two tiny blade templates share every hex. A stationary view uploads no instance data. */
    private static final class Batch implements Disposable {
        final int segments;
        final List<Cover> current = new ArrayList<>(), previous = new ArrayList<>();
        final IntArray counts = new IntArray(), previousCounts = new IntArray();
        final FloatArray data = new FloatArray();
        GpuInstancedMesh mesh;
        ModelInstance instance;
        int capacity;
        long uploads;

        Batch(int segments) { this.segments = segments; }

        void begin() { current.clear(); counts.clear(); }

        void add(Cover cover, int count) {
            if (count > 0) { current.add(cover); counts.add(count); }
        }

        ModelInstance upload() {
            if (current.isEmpty()) { previous.clear(); previousCounts.clear(); return null; }
            boolean changed = !counts.equals(previousCounts) || current.size() != previous.size();
            for (int i = 0; !changed && i < current.size(); i++) { changed = current.get(i) != previous.get(i); }
            if (!changed) { return instance; }
            data.clear();
            for (int i = 0; i < current.size(); i++) { data.addAll(current.get(i).roots.items, 0, counts.get(i) * STRIDE); }
            if (mesh == null) {
                int vertices = segments * 2 + 1;
                mesh = new GpuInstancedMesh(vertices, (segments * 2 - 1) * 3,
                      new VertexAttributes(VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked()));
                float[] points = new float[vertices * 7];
                for (int i = 0; i < vertices; i++) {
                    points[i * 7] = i == vertices - 1 ? 0 : i % 2 == 0 ? -1 : 1;
                    points[i * 7 + 1] = (i / 2) / (float) segments;
                    points[i * 7 + 5] = 1;
                    points[i * 7 + 6] = Color.WHITE.toFloatBits();
                }
                short[] indices = new short[(vertices - 2) * 3];
                for (int i = 0; i < vertices - 2; i++) {
                    indices[i * 3] = (short) (i % 2 == 0 ? i : i + 1);
                    indices[i * 3 + 1] = (short) (i % 2 == 0 ? i + 1 : i);
                    indices[i * 3 + 2] = (short) (i + 2);
                }
                mesh.setVertices(points); mesh.setIndices(indices);
                ModelBuilder builder = new ModelBuilder();
                builder.begin();
                builder.part("living-cover", mesh, GL20.GL_TRIANGLES,
                      new Material(ColorAttribute.createDiffuse(Color.WHITE), IntAttribute.createCullFace(GL20.GL_NONE), new Wind()));
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
            previous.clear(); previous.addAll(current);
            previousCounts.clear(); previousCounts.addAll(counts);
            uploads++;
            return instance;
        }

        @Override
        public void dispose() {
            if (instance != null) { instance.model.dispose(); }
            mesh = null; instance = null; capacity = 0;
            begin(); previous.clear(); previousCounts.clear(); data.clear();
        }
    }

    private final Map<Coords, Cover> models = new LinkedHashMap<>(64, .75f, true);
    private final Batch[] batches = { new Batch(2), new Batch(4) };
    private int revision = -1;
    private int boardId = -1;
    private List<BoardScene.Tile> tiles;
    private long generation;
    private boolean preparing;

    static String vertex(String source) {
        String meadow = "uniform sampler2D u_rainNoise;\n"
              + Gdx.files.classpath("megamek/client/ui/clientGUI/boardview/gpu/terrain-meadow.glsl").readString();
        String blade = Gdx.files.classpath("megamek/client/ui/clientGUI/boardview/gpu/terrain-grass.glsl").readString()
              .replace("@START_PIXELS@", Float.toString(START_PIXELS)).replace("@FULL_PIXELS@", Float.toString(FULL_PIXELS))
              .replace("@ROOTS_PER_HEX@", Float.toString(ROOTS_PER_HEX));
        return source.replace("void main() {", meadow + blade + "\nvoid main() {\nvec3 coverPosition, coverNormal; vec4 coverColor;\n"
                    + "grassBlade(a_position, coverPosition, coverNormal, coverColor);\n")
              .replace("vec4 pos = u_worldTrans * vec4(a_position, 1.0);", "vec4 pos = vec4(coverPosition, 1.0);")
              .replace("vec3 normal = normalize(u_normalMatrix * a_normal);", "vec3 normal = coverNormal;")
              .replace("v_color = a_color;", "v_color = coverColor;");
    }

    private static float density(float pixels) {
        float t = Math.clamp((pixels - START_PIXELS) / (FULL_PIXELS - START_PIXELS), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Orthographic detail has one scale everywhere; perspective detail still depends on each tile's depth. */
    static boolean visibleAtScale(Camera camera) {
        return camera.projection.val[Matrix4.M33] == 0
              || density(BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, camera.position)) > 0;
    }

    List<ModelInstance> visible(BoardScene scene, Camera camera, List<BoardScene.Tile> candidates,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        if (revision != BoardGeometry.revision() || boardId != scene.boardId()) {
            dispose();
            revision = BoardGeometry.revision();
            boardId = scene.boardId();
        }
        if (tiles != scene.tiles()) {
            tiles = scene.tiles();
            generation++;
        }
        List<ModelInstance> result = new ArrayList<>();
        preparing = false;
        for (Batch batch : batches) { batch.begin(); }
        Set<Coords> visible = new HashSet<>();
        long deadline = System.nanoTime() + BUILD_NANOS;
        Vector3 nearest = new Vector3();
        for (BoardScene.Tile tile : candidates) {
            if (!BoardSurfaceBlend.natural(tile) || BoardBiome.kind(tile) != BoardScene.Biome.NONE
                  || !grassNearby(scene, tile)) { continue; }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            float radius = BoardGeometry.width() * .75f;
            if (!camera.frustum.sphereInFrustum(center, radius)) { continue; }
            // Submit enough roots for the nearest edge; the shader evaluates density at each actual root.
            nearest.set(center).mulAdd(camera.direction, -radius);
            float pixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, nearest);
            int target = (int) Math.ceil(ROOTS_PER_HEX * density(pixels));
            if (target == 0) { continue; }
            visible.add(tile.coords());
            Cover cover = models.get(tile.coords());
            BoardTacticalGeometry.Surface surface = surfaces.apply(tile.coords());
            if (surface == null) { continue; }
            int detail = pixels >= 280 ? 1 : 0;
            if (cover == null || cover.generation != generation || cover.surface != surface) {
                var key = BoardVegetation.key(scene, tile);
                if (cover == null || !cover.key.equals(key) || !BoardVegetation.sameGround(cover.surface, surface, true)) {
                    Cover previous = cover == null ? null : cover.previous == null ? cover : cover.previous;
                    // Actual height changes cannot keep roots at the old elevation. Equal ground or a mesh LOD
                    // handoff can retain complete cover while the replacement reaches its requested density.
                    boolean retain = previous != null && (previous.key.equals(key)
                          || BoardVegetation.sameGround(previous.surface, surface, true));
                    if (System.nanoTime() >= deadline) {
                        preparing = true;
                        if (retain) { batches[detail].add(previous, previous.count(target)); }
                        else { models.remove(tile.coords()); }
                        continue;
                    }
                    cover = new Cover(scene, tile, key, surface, generation);
                    if (retain) { cover.previous = previous; }
                    models.put(tile.coords(), cover);
                }
                cover.surface = surface;
                cover.generation = generation;
            }
            cover.prepare(scene, tile, target, deadline);
            preparing |= cover.samples < target;
            if (cover.samples >= target) { cover.previous = null; }
            Cover displayed = cover.previous == null ? cover : cover.previous;
            batches[detail].add(displayed, displayed.count(target));
        }
        for (Batch batch : batches) {
            ModelInstance instance = batch.upload();
            if (instance != null) { result.add(instance); }
        }
        // No speculative builds: an overflowing guard band used to rebuild and evict patches every stationary frame.
        var iterator = models.entrySet().iterator();
        while (models.size() > CACHE_SIZE && iterator.hasNext()) {
            var entry = iterator.next();
            if (!visible.contains(entry.getKey())) { iterator.remove(); }
        }
        return result;
    }

    long uploads() { return batches[0].uploads + batches[1].uploads; }

    /** Whether the most recent visible request still has roots to prepare within later frame budgets. */
    boolean busy() { return preparing; }

    private static boolean grassNearby(BoardScene scene, BoardScene.Tile tile) {
        if (tile.surface() == BoardScene.Surface.GRASS) { return true; }
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.tile(tile.coords().translated(direction));
            if (BoardSurfaceBlend.natural(neighbor) && neighbor.surface() == BoardScene.Surface.GRASS
                  && Math.abs(neighbor.elevation() - tile.elevation()) <= 1) { return true; }
        }
        return false;
    }

    @Override
    public void dispose() {
        for (Batch batch : batches) { batch.dispose(); }
        models.clear();
        tiles = null;
        preparing = false;
    }
}
