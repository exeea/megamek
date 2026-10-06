/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelCache;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;

/**
 * Combines the visible chunks' opaque colour/texture props. Sources must be the chunks' persistent cached renderables:
 * geometry, transforms and materials stay fixed until a chunk replaces those renderables. Source meshes and
 * materials remain borrowed; this helper owns only the meshes of its spatial pages. Supply hidden chunks too:
 * camera movement selects cached index ranges, while replacement sources invalidate only their own page.
 */
final class GpuPropBatch implements Disposable {
    static final int CHUNKS_PER_PAGE = GpuMeshPage.CHUNKS_PER_PAGE;
    private static final float[] IDENTITY = new Matrix4().val;
    private final Map<Integer, GpuMeshPage> pages = new HashMap<>();
    private final Array<Renderable> source = new Array<>();
    private final Array<Renderable> separate = new Array<>();
    private boolean enabled = true;
    private long rebuilds;

    void setEnabled(boolean value) { enabled = value; }

    void begin() {
        source.clear();
        separate.clear();
        if (enabled) { pages.values().forEach(GpuMeshPage::begin); }
    }

    void add(RenderableProvider props, int pageId, boolean visible) {
        // Chunk caches supply persistent renderables and do not need a temporary renderable pool.
        source.clear();
        props.getRenderables(source, null);
        add(source, pageId, visible);
    }

    /** Cutaway caches change with the hovered floor; draw them without replacing a whole static page. */
    void addDynamic(RenderableProvider props) {
        props.getRenderables(separate, null);
    }

    void add(Array<Renderable> parts, int pageId, boolean visible) {
        for (Renderable value : parts) {
            if (enabled && eligible(value)) {
                pages.computeIfAbsent(pageId, key -> new GpuMeshPage()).add(value, visible);
            } else if (visible) {
                separate.add(value);
            }
        }
    }

    void render(ModelBatch batch, Environment environment) {
        boolean built = false;
        if (enabled) {
            for (GpuMeshPage page : pages.values()) {
                page.update();
                if (!built && page.needsBuild()) {
                    page.build();
                    rebuilds++;
                    built = true;
                }
                page.render(batch, environment);
            }
        }
        for (Renderable value : separate) {
            value.environment = environment;
            batch.render(value);
        }
    }

    private static boolean eligible(Renderable value) {
        long mask = value.material.getMask();
        return value.bones == null && !value.meshPart.mesh.isInstanced()
              && value.meshPart.primitiveType == GL20.GL_TRIANGLES && value.meshPart.size > 0
              && value.meshPart.mesh.getNumIndices() > 0 && Arrays.equals(value.worldTransform.val, IDENTITY)
              && (mask & ~(ColorAttribute.Diffuse | TextureAttribute.Diffuse)) == 0;
    }

    /** Chunk caches already contain world-space vertices. Copy them without normalizing their normals again. */
    static void copy(Array<Renderable> source, Array<Renderable> destination, Array<GpuMeshPage.Range> ranges) {
        Array<Renderable> ordered = new Array<>(source);
        new ModelCache.Sorter().sort(null, ordered);
        MeshBuilder builder = null;
        VertexAttributes attributes = null;
        Material material = null;
        Renderable combined = null;
        FloatArray points = new FloatArray();
        ShortArray indices = new ShortArray();
        try {
            for (Renderable value : ordered) {
                Mesh mesh = value.meshPart.mesh;
                // An indexed part cannot reference more distinct vertices than either of these counts.
                int vertices = Math.min(mesh.getNumVertices(), value.meshPart.size);
                if (builder == null || !attributes.equals(mesh.getVertexAttributes())
                      || builder.getNumVertices() + vertices > 65536) {
                    if (builder != null) { builder.end(); }
                    builder = new MeshBuilder();
                    builder.begin(attributes = mesh.getVertexAttributes(), GL20.GL_TRIANGLES);
                    material = null;
                }
                if (material == null || !material.same(value.material, true)) {
                    combined = new Renderable();
                    combined.material = material = value.material;
                    builder.part("props", GL20.GL_TRIANGLES, combined.meshPart);
                    destination.add(combined);
                }
                int offset = builder.getNumIndices();
                // LibGDX's addMesh(part) reads the entire source mesh for every small range. Copy only the
                // referenced vertex span, reusing scratch buffers instead of retaining whole chunk arrays.
                copyVertices(mesh, value.meshPart.offset, value.meshPart.size, points, indices);
                builder.addMesh(points.items, indices.items, 0, indices.size);
                ranges.add(new GpuMeshPage.Range(value, combined, offset, builder.getNumIndices() - offset));
            }
            if (builder != null) { builder.end(); }
        } catch (RuntimeException | Error failure) {
            disposeMeshes(destination);
            ranges.clear();
            throw failure;
        }
    }

    /** Read only an indexed range's CPU vertex span; neither operation reads back from the GPU. */
    static void copyVertices(Mesh mesh, int offset, int count, FloatArray points, ShortArray indices) {
        short[] selected = indices.setSize(count);
        mesh.getIndices(offset, count, selected, 0);
        int first = 65535, last = 0;
        for (int i = 0; i < count; i++) {
            int vertex = Short.toUnsignedInt(selected[i]);
            first = Math.min(first, vertex);
            last = Math.max(last, vertex);
        }
        int stride = mesh.getVertexSize() / Float.BYTES;
        int floats = (last - first + 1) * stride;
        mesh.getVertices(first * stride, floats, points.setSize(floats), 0);
        for (int i = 0; i < count; i++) { selected[i] = (short) (Short.toUnsignedInt(selected[i]) - first); }
    }

    /** Counts page builds caused by source replacement, never by visibility changes. */
    long rebuilds() { return rebuilds; }

    /** Transfers the temporary builder's tightly sized meshes to destination; source meshes remain borrowed. */
    static void cache(Array<Renderable> destination, Consumer<ModelCache> submit) {
        ModelCache.TightMeshPool meshes = new ModelCache.TightMeshPool();
        ModelCache builder = new ModelCache(new ModelCache.Sorter(), meshes);
        try {
            builder.begin();
            submit.accept(builder);
            builder.end();
            builder.getRenderables(destination, null);
        } catch (RuntimeException | Error failure) {
            meshes.dispose();
            destination.clear();
            throw failure;
        }
    }

    static void disposeMeshes(Array<Renderable> renderables) {
        Set<Mesh> meshes = new HashSet<>();
        for (Renderable value : renderables) {
            // An interrupted page builder may not have assigned the current mesh to its parts yet.
            if (value.meshPart.mesh != null) { meshes.add(value.meshPart.mesh); }
        }
        meshes.forEach(Mesh::dispose);
        renderables.clear();
    }

    @Override
    public void dispose() {
        pages.values().forEach(GpuMeshPage::dispose);
        pages.clear();
        source.clear();
        separate.clear();
    }
}
