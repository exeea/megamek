/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.Pool;

/**
 * Trees drawn with OpenGL instancing. Each tree model at each detail level is held once per draw pass; a tree adds
 * only its place, turn and size. Every pass gathers the trees of the chunks it draws and submits one draw per model
 * part, so the geometry in memory no longer grows with the number of trees and a change of detail rebuilds nothing.
 * A pass whose trees are the same as in its previous frame uploads nothing.
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

    /**
     * The draw passes that gather trees. Each keeps its own instance buffers: the passes of one frame see different
     * trees, and sharing a buffer would upload it again for every pass of every frame.
     */
    enum Pass { COLOUR, DEPTH, SHADOW }

    /** Floats per tree: x, y, z and the horizontal scale; the cosine and sine of its turn, its vertical scale and 0. */
    static final int STRIDE = 8;
    private static final String MAIN = "void main() {";
    /**
     * The instance attributes sit at the same fixed locations in every program. A tree mesh keeps one vertex array
     * object for the colour and depth shaders alike, and a location made per-instance by one of them must never carry
     * another shader's per-vertex data.
     */
    private static final String DECLARATIONS = """
          layout(location = 14) in vec4 a_instance0;
          layout(location = 15) in vec4 a_instance1;
          vec3 instanceTurn(vec3 v) {
              return vec3(a_instance1.x * v.x - a_instance1.y * v.y, a_instance1.y * v.x + a_instance1.x * v.y, v.z);
          }
          vec3 instancePosition(vec3 p) { return instanceTurn(p * vec3(a_instance0.w, a_instance0.w, a_instance1.z)) + a_instance0.xyz; }
          vec3 instanceNormal(vec3 n) { return normalize(instanceTurn(n / vec3(a_instance0.w, a_instance0.w, a_instance1.z))); }
          """;

    /** One tree model at one detail level in one pass: an instanced copy of its mesh and a renderable per part. */
    private final class Batch implements Disposable {
        final Mesh mesh;
        final List<Renderable> parts = new ArrayList<>();
        final FloatArray data = new FloatArray();
        /** What the instance buffer holds. */
        final FloatArray uploaded = new FloatArray();
        int capacity = 64;

        Batch(Model model) {
            mesh = new InstancedMesh(model.meshes.first());
            mesh.enableInstancedRendering(false, capacity, attributes());
            for (Node node : model.nodes) { collect(node); }
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
            int count = data.size / STRIDE;
            if (count > capacity) {
                capacity = Math.max(count, capacity * 2);
                mesh.disableInstancedRendering();
                mesh.enableInstancedRendering(false, capacity, attributes());
            }
            mesh.setInstanceData(data.items, 0, data.size);
            uploaded.clear();
            uploaded.addAll(data);
            uploads++;
        }

        @Override
        public void dispose() { mesh.dispose(); }
    }

    /**
     * A static copy of a tree model's mesh that unbinds in the order a core profile needs. libGDX 1.14.2 unbinds the
     * vertex array object first and then disables the instance attributes, which with no vertex array object bound is
     * GL_INVALID_OPERATION on every instanced draw; this mesh disables them while its own vertex array object is bound.
     */
    private static final class InstancedMesh extends Mesh {
        InstancedMesh(Mesh source) {
            super(true, source.getNumVertices(), source.getNumIndices(), source.getVertexAttributes());
            float[] vertexData = new float[source.getNumVertices() * source.getVertexSize() / Float.BYTES];
            source.getVertices(vertexData);
            setVertices(vertexData);
            short[] indexData = new short[source.getNumIndices()];
            source.getIndices(indexData);
            setIndices(indexData);
        }

        @Override
        public void unbind(ShaderProgram shader, int[] locations, int[] instanceLocations) {
            if (instances != null && instances.getNumInstances() > 0) { instances.unbind(shader, instanceLocations); }
            vertices.unbind(shader, locations);
            if (indices.getNumIndices() > 0) { indices.unbind(); }
        }
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
            float[] m = transform.val;
            float horizontal = (float) Math.hypot(m[Matrix4.M00], m[Matrix4.M10]);
            trees.computeIfAbsent(species, key -> new FloatArray()).addAll(m[Matrix4.M03], m[Matrix4.M13], m[Matrix4.M23],
                  horizontal, m[Matrix4.M00] / horizontal, m[Matrix4.M10] / horizontal, m[Matrix4.M22], 0);
        }
    }

    private final Function<String, Model> models;
    private final Map<String, Batch> batches = new HashMap<>();
    private final List<Batch> gathered = new ArrayList<>();
    private Pass pass = Pass.COLOUR;
    private long uploads;

    /** models: loads a tree model, already marked for the foliage shader, by asset name. */
    GpuTreeInstances(Function<String, Model> models) {
        this.models = models;
    }

    /** Starts gathering one pass's trees. */
    void begin(Pass next) {
        for (Batch batch : gathered) { batch.data.clear(); }
        gathered.clear();
        pass = next;
    }

    /** Adds a chunk's trees at its detail level. */
    void add(Stand stand, int level) {
        for (Map.Entry<String, FloatArray> entry : stand.trees.entrySet()) {
            String asset = TreeLod.asset(entry.getKey(), level);
            Batch batch = batches.computeIfAbsent(pass.ordinal() + asset, key -> new Batch(models.apply(asset)));
            if (batch.data.isEmpty()) { gathered.add(batch); }
            batch.data.addAll(entry.getValue());
        }
    }

    /** Uploads what this pass gathered and supplies one renderable per part of every model in use. */
    @Override
    public void getRenderables(Array<Renderable> renderables, Pool<Renderable> pool) {
        for (Batch batch : gathered) {
            batch.upload();
            for (Renderable part : batch.parts) {
                // Each pass picks its own shader; a depth shader must never be suggested to the colour pass.
                part.shader = null;
                part.environment = null;
                renderables.add(part);
            }
        }
    }

    /** Instance buffer uploads so far; a pass whose trees did not change adds none. */
    long uploads() { return uploads; }

    /** Bytes of the shared tree meshes and of their instance buffers. */
    long bytes() {
        long total = 0;
        for (Batch batch : batches.values()) {
            total += (long) batch.mesh.getNumVertices() * batch.mesh.getVertexSize()
                  + (long) batch.mesh.getNumIndices() * Short.BYTES + (long) batch.capacity * STRIDE * Float.BYTES;
        }
        return total;
    }

    @Override
    public void dispose() {
        batches.values().forEach(Batch::dispose);
        batches.clear();
        gathered.clear();
    }

    static boolean instanced(Renderable renderable) {
        return renderable.material != null && renderable.material.has(Instanced.TYPE);
    }

    /** The scene vertex shader, placing each vertex by its tree's instance data instead of the world transform. */
    static String vertex(String source) {
        source = insert(source, MAIN, DECLARATIONS + MAIN);
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
              : DepthShader.getDefaultVertexShader()), plain.fragmentShader);
        trees.defaultCullFace = plain.defaultCullFace;
        trees.defaultDepthFunc = plain.defaultDepthFunc;
        trees.depthBufferOnly = plain.depthBufferOnly;
        trees.defaultAlphaTest = plain.defaultAlphaTest;
        return new DepthShaderProvider(plain) {
            @Override
            protected Shader createShader(Renderable renderable) {
                boolean instanced = instanced(renderable);
                return new DepthShader(renderable, instanced ? trees : plain) {
                    @Override
                    public boolean canRender(Renderable other) {
                        return instanced(other) == instanced && super.canRender(other);
                    }
                };
            }
        };
    }

    private static String depthVertex(String source) {
        source = insert(source, MAIN, DECLARATIONS + "uniform mat4 u_projViewTrans;\n" + MAIN);
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
