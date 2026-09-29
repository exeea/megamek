/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.ShortArray;

/**
 * Trees and modular building parts drawn with OpenGL instancing. Each model at each detail level is held once; a
 * placement adds only its place, turn and size, so the geometry in memory does not grow with the number of trees.
 * Every chunk's trees stay uploaded in one buffer per species and detail level, in board order; each pass draws the
 * ranges of the chunks it sees, so panning, zooming and a change of detail upload nothing. A chunk's trees upload
 * again only when the chunk is replaced with different trees. Building parts still gather per pass.
 */
final class GpuTreeInstances implements RenderableProvider, Disposable {
    /** Marks the materials of instanced renderables, so the shader providers pick the instanced shaders. */
    static final class Instanced extends Attribute {
        static final long TYPE = register("boardInstanced");

        Instanced() { super(TYPE); }

        @Override
        public Attribute copy() { return new Instanced(); }

        @Override
        public int compareTo(Attribute other) { return Long.compare(type, other.type); }
    }

    /** The draw passes that gather building parts; each keeps its own part buffers, as its parts differ. */
    enum Pass { COLOUR, DEPTH, SHADOW }

    /** Floats per tree: x, y, z and the horizontal scale; the cosine and sine of its turn, its vertical scale and 0. */
    static final int STRIDE = 8;
    private static final String MAIN = "void main() {";

    /** One model or module part: an instanced copy of its mesh, a renderable per part and the instance buffer. */
    private final class Batch implements Disposable {
        final GpuInstancedMesh mesh;
        final List<Renderable> parts = new ArrayList<>();
        final FloatArray data = new FloatArray();
        /** What the instance buffer holds. */
        final FloatArray uploaded = new FloatArray();
        int capacity = 64;

        Batch(Model model) {
            mesh = new GpuInstancedMesh(model.meshes.first());
            mesh.enableInstancedRendering(false, capacity, attributes());
            for (Node node : model.nodes) { collect(node); }
        }

        /** A shared building module part; only this indexed range needs an instanced vertex buffer. */
        Batch(Renderable source) {
            FloatArray vertices = new FloatArray();
            ShortArray indices = new ShortArray();
            GpuPropBatch.copyVertices(source.meshPart.mesh, source.meshPart.offset, source.meshPart.size, vertices, indices);
            // Parts share a GLB vertex buffer; compact the indexed range instead of copying unrelated variants.
            MeshBuilder builder = new MeshBuilder();
            builder.begin(source.meshPart.mesh.getVertexAttributes(), source.meshPart.primitiveType);
            builder.addMesh(vertices.items, indices.items, 0, indices.size);
            mesh = new GpuInstancedMesh(builder.getNumVertices(), builder.getNumIndices(), builder.getAttributes());
            builder.end(mesh);
            mesh.enableInstancedRendering(false, capacity, attributes());
            Renderable part = new Renderable();
            part.meshPart.set(source.meshPart);
            part.meshPart.mesh = mesh;
            part.meshPart.offset = 0;
            part.material = new Material(source.material);
            part.material.set(new Instanced());
            parts.add(part);
        }

        private void collect(Node node) {
            for (NodePart part : node.parts) {
                Renderable renderable = new Renderable();
                renderable.meshPart.set(part.meshPart);
                renderable.meshPart.mesh = mesh;
                renderable.material = new Material(part.material);
                renderable.material.set(new Instanced());
                parts.add(renderable);
            }
            for (Node child : node.getChildren()) { collect(child); }
        }

        void upload() {
            if (data.equals(uploaded)) { return; }
            upload(data);
            uploaded.clear();
            uploaded.addAll(data);
        }

        void upload(FloatArray instances) {
            int count = instances.size / STRIDE;
            if (count > capacity) {
                capacity = Math.max(count, capacity * 2);
                mesh.disableInstancedRendering();
                mesh.enableInstancedRendering(false, capacity, attributes());
            }
            mesh.setInstanceData(instances.items, 0, instances.size);
            uploads++;
        }

        @Override
        public void dispose() { mesh.dispose(); }
    }

    /**
     * libGDX tells a mesh's attributes apart by usage and unit, and adds the unit to the shader location, so the two
     * instance attributes differ by usage: the place and horizontal scale, and the turn and vertical scale.
     */
    private static VertexAttribute[] attributes() {
        return new VertexAttribute[] { new VertexAttribute(VertexAttributes.Usage.Position, 4, "a_instance0"),
              new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_instance1") };
    }

    /** One chunk's trees, grouped by species; each tree as {@link #STRIDE} floats. */
    static final class Stand {
        final Map<String, FloatArray> trees = new LinkedHashMap<>();

        /** A tree's transform is a translation, a turn about z and a scale that is equal along x and y. */
        void add(String species, Matrix4 transform) {
            append(trees.computeIfAbsent(species, key -> new FloatArray()), transform);
        }
    }

    /**
     * One species at one detail level: every chunk's trees of that species in one buffer, in board order, and the
     * ranges of the chunks a pass draws at this level.
     */
    private final class Species implements Disposable {
        final Batch batch;
        // Chunk arrays are replaced, never edited: identity, then contents, identify an unchanged chunk.
        final List<FloatArray> current = new ArrayList<>(), previous = new ArrayList<>();
        final IntArray drawn = new IntArray(), starts = new IntArray(), ranges = new IntArray();
        final FloatArray data = new FloatArray();
        boolean gathered;

        Species(Model model) { batch = new Batch(model); }

        void begin() { current.clear(); drawn.clear(); gathered = false; }

        void add(FloatArray trees, boolean visible) {
            current.add(trees);
            drawn.add(visible ? 1 : 0);
        }

        boolean upload() {
            boolean changed = current.size() != previous.size();
            for (int i = 0; !changed && i < current.size(); i++) {
                changed = current.get(i) != previous.get(i) && !current.get(i).equals(previous.get(i));
            }
            previous.clear(); previous.addAll(current);
            if (changed) {
                data.clear();
                starts.clear();
                for (FloatArray trees : current) {
                    starts.add(data.size / STRIDE);
                    data.addAll(trees);
                }
                starts.add(data.size / STRIDE);
                batch.upload(data);
            }
            // Chunks lie in board order, so a column of visible chunks is one contiguous range and one draw.
            ranges.clear();
            int count = 0;
            for (int i = 0; i < current.size(); i++) {
                int size = starts.get(i + 1) - starts.get(i);
                if (drawn.get(i) == 0 || size == 0) { continue; }
                if (ranges.size > 0 && ranges.get(ranges.size - 2) + ranges.get(ranges.size - 1) == starts.get(i)) {
                    ranges.incr(ranges.size - 1, size);
                } else {
                    ranges.add(starts.get(i), size);
                }
                count += size;
            }
            batch.mesh.drawRanges(ranges);
            return count > 0;
        }

        @Override
        public void dispose() { batch.dispose(); }
    }

    private static final FloatArray NO_TREES = new FloatArray(0);
    private final BiFunction<String, Integer, Model> models;
    private final Map<String, Species[]> species = new HashMap<>();
    private final List<Species> stands = new ArrayList<>();
    private record Part(Mesh mesh, int offset, int size, Material material) { }
    private final Map<Part, Batch[]> sharedParts = new HashMap<>();
    // Persistent chunk snapshots need no per-frame material/range keys; values do not retain their weak keys.
    private final Map<Renderable, Batch[]> sharedLookups = new WeakHashMap<>();
    private final List<Batch> gathered = new ArrayList<>();
    private Pass pass = Pass.COLOUR;
    private long uploads;

    /** models: loads a tree model, already marked for the foliage shader, by asset name. */
    GpuTreeInstances(BiFunction<String, Integer, Model> models) {
        this.models = models;
    }

    /** Starts gathering one pass's trees and building parts. */
    void begin(Pass next) {
        for (Batch batch : gathered) { batch.data.clear(); }
        gathered.clear();
        for (Species stand : stands) { stand.begin(); }
        stands.clear();
        pass = next;
    }

    /**
     * Adds a chunk's trees at its detail level, in board order, whether the pass sees the chunk or not: a chunk's
     * place in every species buffer stays fixed while it is unchanged, and only the visible chunks draw.
     */
    void add(Stand stand, int level, boolean visible) {
        for (Map.Entry<String, FloatArray> entry : stand.trees.entrySet()) {
            Species[] levels = species.computeIfAbsent(entry.getKey(), key -> new Species[TreeLod.LEVELS]);
            for (int lod = 0; lod < levels.length; lod++) {
                if (levels[lod] == null) {
                    if (!visible || lod != level) { continue; }
                    levels[lod] = new Species(models.apply(entry.getKey(), lod));
                }
                gather(levels[lod]);
                levels[lod].add(entry.getValue(), visible && lod == level);
            }
        }
        // Species this chunk lacks keep their board-order places too.
        for (Map.Entry<String, Species[]> entry : species.entrySet()) {
            if (stand.trees.containsKey(entry.getKey())) { continue; }
            for (Species other : entry.getValue()) {
                if (other == null) { continue; }
                gather(other);
                other.add(NO_TREES, false);
            }
        }
    }

    private void gather(Species level) {
        if (!level.gathered) { level.gathered = true; stands.add(level); }
    }

    /** Static building ranges borrow module meshes; placements add only transforms, using the same draw path as trees. */
    void add(Array<Renderable> parts) {
        for (Renderable source : parts) {
            Batch[] passes = sharedLookups.computeIfAbsent(source, value -> sharedParts.computeIfAbsent(
                  new Part(value.meshPart.mesh, value.meshPart.offset, value.meshPart.size, value.material),
                  ignored -> new Batch[Pass.values().length]));
            Batch batch = passes[pass.ordinal()];
            if (batch == null) { passes[pass.ordinal()] = batch = new Batch(source); }
            if (batch.data.isEmpty()) { gathered.add(batch); }
            append(batch.data, source.worldTransform);
        }
    }

    private static void append(FloatArray data, Matrix4 transform) {
        float[] m = transform.val;
        float horizontal = (float) Math.hypot(m[Matrix4.M00], m[Matrix4.M10]);
        data.addAll(m[Matrix4.M03], m[Matrix4.M13], m[Matrix4.M23], horizontal,
              m[Matrix4.M00] / horizontal, m[Matrix4.M10] / horizontal, m[Matrix4.M22], 0);
    }

    /** Evict buffers for retired building interiors/modules at the scene commit boundary. */
    void retainParts(Set<Mesh> live) {
        sharedLookups.clear();
        sharedParts.entrySet().removeIf(entry -> {
            if (live.contains(entry.getKey().mesh())) { return false; }
            for (Batch batch : entry.getValue()) {
                if (batch != null) { gathered.remove(batch); batch.dispose(); }
            }
            return true;
        });
    }

    /** Uploads what changed and supplies one renderable per part of every model this pass draws. */
    @Override
    public void getRenderables(Array<Renderable> renderables, Pool<Renderable> pool) {
        for (Species stand : stands) {
            if (stand.upload()) { parts(stand.batch, renderables); }
        }
        for (Batch batch : gathered) {
            batch.upload();
            parts(batch, renderables);
        }
    }

    private static void parts(Batch batch, Array<Renderable> renderables) {
        for (Renderable part : batch.parts) {
            // Each pass picks its own shader; a depth shader must never be suggested to the colour pass.
            part.shader = null;
            part.environment = null;
            renderables.add(part);
        }
    }

    /** Instance buffer uploads so far; unchanged chunks and unchanged building parts add none. */
    long uploads() { return uploads; }

    /** Bytes of the shared tree/building meshes and of their instance buffers. */
    long bytes() {
        long total = 0;
        List<Batch> all = new ArrayList<>();
        for (Species[] levels : species.values()) { for (Species level : levels) { if (level != null) { all.add(level.batch); } } }
        for (Batch[] passes : sharedParts.values()) { for (Batch batch : passes) { if (batch != null) { all.add(batch); } } }
        for (Batch batch : all) {
            total += (long) batch.mesh.getNumVertices() * batch.mesh.getVertexSize()
                  + (long) batch.mesh.getNumIndices() * Short.BYTES + (long) batch.capacity * STRIDE * Float.BYTES;
        }
        return total;
    }

    @Override
    public void dispose() {
        for (Species[] levels : species.values()) {
            for (Species level : levels) { if (level != null) { level.dispose(); } }
        }
        retainParts(Set.of());
        species.clear();
        stands.clear();
        gathered.clear();
    }

    static boolean instanced(Renderable renderable) {
        return renderable.material != null && renderable.material.has(Instanced.TYPE);
    }

    /** The scene vertex shader, placing each vertex by its tree's instance data instead of the world transform. */
    static String vertex(String source) {
        source = insert(source, MAIN, GpuShaderSource.read("tree-instances.glsl") + "\n" + MAIN);
        source = insert(source, "vec4 pos = u_worldTrans * vec4(a_position, 1.0);",
              "vec4 pos = vec4(instancePosition(a_position), 1.0);");
        return insert(source, "vec3 normal = normalize(u_normalMatrix * a_normal);", "vec3 normal = instanceNormal(a_normal);");
    }

    /**
     * A depth pass that also draws instanced trees: each of its shaders renders either instanced renderables or the
     * others, never both. Instanced trees use the plain configuration with the instanced vertex shader.
     */
    static DepthShaderProvider depthProvider(DepthShader.Config plain) {
        DepthShader.Config trees = new DepthShader.Config(depthVertex(plain.vertexShader != null ? plain.vertexShader
              : GpuGlsl.libGdx(DepthShader.getDefaultVertexShader(), true)), plain.fragmentShader);
        trees.defaultCullFace = plain.defaultCullFace;
        trees.defaultDepthFunc = plain.defaultDepthFunc;
        trees.depthBufferOnly = plain.depthBufferOnly;
        trees.defaultAlphaTest = plain.defaultAlphaTest;
        return new DepthShaderProvider(plain) {
            @Override
            protected Shader createShader(Renderable renderable) {
                boolean instanced = instanced(renderable);
                DepthShader.Config chosen = instanced ? trees : plain;
                return new DepthShader(renderable, chosen, GpuGlsl.compile("GPU shadow depth",
                      DepthShader.createPrefix(renderable, chosen),
                      chosen.vertexShader == null ? GpuGlsl.libGdx(DepthShader.getDefaultVertexShader(), true) : chosen.vertexShader,
                      chosen.fragmentShader == null ? GpuGlsl.libGdx(DepthShader.getDefaultFragmentShader(), false) : chosen.fragmentShader)) {
                    @Override
                    public boolean canRender(Renderable other) {
                        return instanced(other) == instanced && super.canRender(other);
                    }
                };
            }
        };
    }

    private static String depthVertex(String source) {
        source = insert(source, MAIN, GpuShaderSource.read("tree-instances.glsl") + "\nuniform mat4 u_projViewTrans;\n" + MAIN);
        return insert(source, "vec4 pos = u_projViewWorldTrans * vec4(a_position, 1.0);",
              "vec4 pos = u_projViewTrans * vec4(instancePosition(a_position), 1.0);");
    }

    private static String insert(String source, String anchor, String replacement) {
        int index = source.indexOf(anchor);
        if (index < 0 || source.indexOf(anchor, index + anchor.length()) >= 0) {
            throw new IllegalStateException("Incompatible tree instancing insertion point: " + anchor);
        }
        return source.substring(0, index) + replacement + source.substring(index + anchor.length());
    }
}
