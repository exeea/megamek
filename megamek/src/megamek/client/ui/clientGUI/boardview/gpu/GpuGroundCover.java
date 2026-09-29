/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

/**
 * Grass blades over finished terrain. Terrain workers plant each full-detail hex's roots with its chunk
 * ({@link #plant}); here every chunk keeps one persistent instance buffer of those roots and draws the prefix by
 * rank that each hex's projected size needs, so zooming and panning upload nothing. GL ownership stays here.
 */
final class GpuGroundCover implements Disposable {
    static final class Wind extends FloatAttribute {
        static final long TYPE = register("boardVegetationWind");
        Wind() { super(TYPE, 1); }
        @Override
        public Wind copy() { return new Wind(); }
    }

    private static final int ROOTS_PER_HEX = 4096;
    private static final int STRIDE = 4;
    private static final float START_PIXELS = 120, FULL_PIXELS = 500;
    /** Metres frozen land's jagged border reaches past its hex edge at most: the shard offset, lean and splinters. */
    private static final float ICE_BORDER_REACH = 5.2f;

    /** Whether a hex grows grass: natural grass ground, or its neighbour within a level of a grass hex. */
    static boolean grows(BoardScene scene, BoardScene.Tile tile) {
        if (!BoardSurfaceBlend.natural(tile) || BoardBiome.kind(tile) != BoardScene.Biome.NONE) { return false; }
        if (tile.surface() == BoardScene.Surface.GRASS) { return true; }
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.tile(tile.coords().translated(direction));
            if (BoardSurfaceBlend.natural(neighbor) && neighbor.surface() == BoardScene.Surface.GRASS
                  && Math.abs(neighbor.elevation() - tile.elevation()) <= 1) { return true; }
        }
        return false;
    }

    /**
     * Deterministic roots on the hex's finished top triangles, filtered by the existing surface and road rules, as
     * (x, y, z, rank) in rank order. Density selects a prefix by rank, so zooming never moves an existing blade.
     */
    static FloatArray plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface) {
        List<BoardSurface.Face> ground = new ArrayList<>(surface.top().stream()
              .filter(face -> face.finish() == BoardSurface.Finish.TOP).toList());
        if (BoardGeometry.tuning().stepsBetweenTops()) { ground.addAll(surface.slopes()); }
        ground.removeIf(face -> new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor().z <= .7f);
        float[] areas = new float[ground.size()];
        float total = 0;
        for (int i = 0; i < ground.size(); i++) {
            var face = ground.get(i);
            total += Math.abs((face.b().x - face.a().x) * (face.c().y - face.a().y)
                  - (face.c().x - face.a().x) * (face.b().y - face.a().y));
            areas[i] = total;
        }
        var roots = new FloatArray();
        if (total <= 0) { return roots; }
        var random = new Random(tile.coords().getX() * 0x9E3779B97F4A7C15L ^ tile.coords().getY() * 0xC2B2AE3D27D4EB4FL);
        boolean boundary = BoardSurfaceBlend.boundary(scene, tile);
        BoardRoad road = BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
        float sink = BoardGeometry.width() * .001f;
        // Frozen land's jagged border reaches across a shared edge (terrain-ice.glsl); no blade grows through it.
        List<Vector3[]> iceEdges = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            var neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            if (neighbor != null && neighbor.frozen() && !neighbor.liquid().present()
                  && neighbor.elevation() == tile.elevation()) {
                iceEdges.add(new Vector3[] { BoardGeometry.corner(tile.coords(), 0, edge),
                      BoardGeometry.corner(tile.coords(), 0, edge + 1) });
            }
        }
        float iceReach = BoardRelief.metres(ICE_BORDER_REACH);
        for (int rank = 0; rank < ROOTS_PER_HEX; rank++) {
            int index = Arrays.binarySearch(areas, random.nextFloat() * total);
            var face = ground.get(Math.min(ground.size() - 1, index < 0 ? -index - 1 : index));
            float a = random.nextFloat(), b = random.nextFloat();
            if (a + b > 1) { a = 1 - a; b = 1 - b; }
            float x = face.a().x + (face.b().x - face.a().x) * a + (face.c().x - face.a().x) * b;
            float y = face.a().y + (face.b().y - face.a().y) * a + (face.c().y - face.a().y) * b;
            float z = face.a().z + (face.b().z - face.a().z) * a + (face.c().z - face.a().z) * b;
            if (road != null && road.distance((x - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale(),
                  (y - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale()) < BoardRoad.SHOULDER + 1) { continue; }
            boolean iced = false;
            for (Vector3[] edge : iceEdges) {
                float ex = edge[1].x - edge[0].x, ey = edge[1].y - edge[0].y;
                float t = Math.clamp(((x - edge[0].x) * ex + (y - edge[0].y) * ey) / (ex * ex + ey * ey), 0, 1);
                iced |= Math.hypot(x - edge[0].x - t * ex, y - edge[0].y - t * ey) < iceReach;
            }
            if (iced) { continue; }
            float grass = boundary ? BoardSurfaceBlend.sample(scene, tile, x, y, z).grass()
                  : tile.surface() == BoardScene.Surface.GRASS ? 1 : 0;
            if (random.nextFloat() <= grass * BoardRelief.smooth((grass - .55f) / .35f)) {
                roots.addAll(x, y, z - sink, rank);
            }
        }
        roots.shrink();
        return roots;
    }

    /** One blade template's instanced draw for a chunk. Its buffer persists while the chunk's roots are unchanged. */
    private static final class Batch implements Disposable {
        final int segments;
        // Roots are replaced, never edited in place: identity, then contents, identify unchanged hexes.
        final List<FloatArray> current = new ArrayList<>(), previous = new ArrayList<>();
        final FloatArray data = new FloatArray();
        // Where each hex's roots start in the uploaded data, when every hex draws its own range.
        final IntArray starts = new IntArray(), ranges = new IntArray();
        GpuInstancedMesh mesh;
        ModelInstance instance;
        int capacity;
        long uploads;

        Batch(int segments) { this.segments = segments; }

        void begin() { current.clear(); }

        void add(FloatArray roots) { current.add(roots); }

        /** Upload changed roots, then draw the prefix by rank that each hex's target needs, one range per hex. */
        ModelInstance upload(IntArray targets) {
            if (current.isEmpty()) { previous.clear(); return null; }
            // A chunk prepared again on equal ground plants equal roots: those need no upload either. Remember the
            // new arrays either way, so an equal replant is compared once rather than every frame.
            boolean changed = current.size() != previous.size();
            for (int i = 0; !changed && i < current.size(); i++) {
                changed = current.get(i) != previous.get(i) && !current.get(i).equals(previous.get(i));
            }
            previous.clear(); previous.addAll(current);
            if (changed) {
                concatenate();
                if (data.size == 0) { return null; }
                if (mesh == null) { build(); }
                int count = data.size / STRIDE;
                if (count > capacity) {
                    if (capacity > 0) { mesh.disableInstancedRendering(); }
                    capacity = Math.max(count, Math.max(256, capacity * 2));
                    mesh.enableInstancedRendering(false, capacity, new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverRoot"));
                }
                mesh.setInstanceData(data.items, 0, data.size);
                uploads++;
            }
            if (data.size == 0) { return null; }
            int drawn = 0;
            ranges.clear();
            for (int i = 0; i < current.size(); i++) {
                int count = prefix(starts.get(i), starts.get(i + 1), targets.get(i));
                ranges.add(starts.get(i) / STRIDE, count);
                drawn += count;
            }
            mesh.drawRanges(ranges);
            return drawn == 0 ? null : instance;
        }

        /** Instances within the rank-ordered data range [from, to), in floats, whose rank is below the target. */
        private int prefix(int from, int to, int target) {
            int low = from / STRIDE, high = to / STRIDE;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (data.items[middle * STRIDE + 3] < target) { low = middle + 1; } else { high = middle; }
            }
            return low - from / STRIDE;
        }

        /** Hex after hex, each in rank order: every hex's prefix is one contiguous range. */
        private void concatenate() {
            data.clear();
            starts.clear();
            for (FloatArray roots : current) {
                starts.add(data.size);
                data.addAll(roots);
            }
            starts.add(data.size);
        }

        private void build() {
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

        @Override
        public void dispose() {
            if (instance != null) { instance.model.dispose(); }
            mesh = null; instance = null; capacity = 0;
            begin(); previous.clear(); data.clear(); starts.clear();
        }
    }

    /** One terrain chunk's grass: the three- and seven-triangle blade templates, one in use at a time. */
    private static final class Chunk implements Disposable {
        final Batch[] batches = { new Batch(2), new Batch(4) };
        // The chunk's planted hexes and, for each, the roots its projected size needs this frame (none out of view).
        final List<FloatArray> roots = new ArrayList<>();
        final IntArray targets = new IntArray();
        float nearPixels;
        long frame;
        // Blades place themselves from their roots; the transform only orders chunks nearest first for early depth.
        final Vector3 centre;

        Chunk(Coords chunk) {
            centre = BoardGeometry.center(new Coords(chunk.getX() * TerrainLod.CHUNK_SIZE + TerrainLod.CHUNK_SIZE / 2,
                  chunk.getY() * TerrainLod.CHUNK_SIZE + TerrainLod.CHUNK_SIZE / 2), 0);
        }

        void begin(long next) { frame = next; roots.clear(); targets.clear(); nearPixels = 0; }

        @Override
        public void dispose() { for (Batch batch : batches) { batch.dispose(); } }
    }

    private final Map<Coords, Chunk> chunks = new HashMap<>();
    private long frame, uploads;
    private int revision = -1, boardId = -1;
    /** Render-owned submission snapshot; installed roots still invalidate a stationary view. */
    private record View(float[] projectionView, float viewportPixels, List<BoardScene.Tile> candidates,
          Map<Coords, FloatArray> sources, List<ModelInstance> instances) { }
    private View view;

    static String vertex(String source) {
        String meadow = "uniform sampler2D u_rainNoise;\n"
              + GpuShaderSource.read("terrain-meadow.glsl");
        String wind = GpuShaderSource.read("terrain-vegetation-wind.glsl");
        String blade = "#define GRASS_START_PIXELS " + START_PIXELS + "\n#define GRASS_FULL_PIXELS " + FULL_PIXELS
              + "\n#define GRASS_ROOTS_PER_HEX " + ROOTS_PER_HEX + "\n" + GpuShaderSource.read("terrain-grass.glsl");
        // A root beyond its density leaves every vertex of its blade outside the clip volume.
        return source.replace("void main() {", wind + meadow + blade + "\nvoid main() {\nvec3 coverPosition, coverNormal; vec4 coverColor;\n"
                    + "if (!grassBlade(a_position, coverPosition, coverNormal, coverColor)) {\n"
                    + "gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }\n")
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

    /** The candidate tiles' installed grass, one bound buffer per chunk drawing the prefix each hex's size needs. */
    List<ModelInstance> visible(BoardScene scene, Camera camera, List<BoardScene.Tile> candidates,
          Function<Coords, BoardPlants> plants) {
        if (revision != BoardGeometry.revision() || boardId != scene.boardId()) {
            dispose();
            revision = BoardGeometry.revision();
            boardId = scene.boardId();
        }
        float viewportPixels = Gdx.graphics == null ? camera.viewportHeight
              : camera.viewportHeight * Gdx.graphics.getBackBufferHeight() / Math.max(1f, Gdx.graphics.getHeight());
        if (view != null && view.viewportPixels() == viewportPixels
              && Arrays.equals(view.projectionView(), camera.combined.val) && view.candidates().equals(candidates)) {
            boolean current = true;
            for (var entry : view.sources().entrySet()) {
                if (grass(plants.apply(entry.getKey())) != entry.getValue()) { current = false; break; }
            }
            if (current) { return view.instances(); }
        }
        frame++;
        Map<Coords, FloatArray> sources = new HashMap<>();
        Vector3 nearest = new Vector3();
        for (BoardScene.Tile tile : candidates) {
            if (!grows(scene, tile)) { continue; }
            Chunk chunk = chunks.computeIfAbsent(new Coords(tile.coords().getX() / TerrainLod.CHUNK_SIZE,
                  tile.coords().getY() / TerrainLod.CHUNK_SIZE), Chunk::new);
            if (chunk.frame != frame) { chunk.begin(frame); }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            float radius = BoardGeometry.width() * .75f;
            boolean inView = camera.frustum.sphereInFrustum(center, radius);
            // Every candidate stays in its chunk's buffer, in view or not, so panning uploads nothing. Hexes without
            // installed roots are remembered too: their chunk's installation must refresh a stationary view.
            FloatArray roots = grass(plants.apply(tile.coords()));
            if (inView) { sources.put(tile.coords(), roots); }
            if (roots == null) { continue; }
            // Enough roots for the hex's nearest edge; the shader evaluates density again at each actual root.
            nearest.set(center).mulAdd(camera.direction, -radius);
            float pixels = inView ? BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, nearest) : 0;
            chunk.roots.add(roots);
            chunk.targets.add((int) Math.ceil(ROOTS_PER_HEX * density(pixels)));
            chunk.nearPixels = Math.max(chunk.nearPixels, pixels);
        }
        List<ModelInstance> result = new ArrayList<>();
        for (var iterator = chunks.values().iterator(); iterator.hasNext(); ) {
            Chunk chunk = iterator.next();
            if (chunk.frame != frame) {
                chunk.dispose();
                iterator.remove();
                continue;
            }
            int detail = chunk.nearPixels >= 280 ? 1 : 0;
            for (int i = 0; i < chunk.batches.length; i++) {
                Batch batch = chunk.batches[i];
                // The idle template keeps its buffer for a return to its scale.
                batch.begin();
                if (i != detail || chunk.nearPixels <= START_PIXELS) { continue; }
                for (FloatArray roots : chunk.roots) { batch.add(roots); }
                long before = batch.uploads;
                ModelInstance instance = batch.upload(chunk.targets);
                uploads += batch.uploads - before;
                if (instance != null) {
                    instance.transform.setToTranslation(chunk.centre);
                    result.add(instance);
                }
            }
        }
        view = new View(camera.combined.val.clone(), viewportPixels, List.copyOf(candidates), sources, result);
        return result;
    }

    private static FloatArray grass(BoardPlants plants) { return plants == null ? null : plants.grass(); }

    long uploads() { return uploads; }

    @Override
    public void dispose() {
        for (Chunk chunk : chunks.values()) { chunk.dispose(); }
        chunks.clear();
        view = null;
    }
}
