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
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.Pool;

/**
 * Combines the visible chunks' plain opaque props. Sources must be the chunks' persistent cached renderables:
 * geometry, transforms and materials stay fixed until a chunk replaces those renderables. Source meshes and
 * materials remain borrowed; this helper owns only the meshes of its spatial pages. Chunk culling happens before
 * add(). A visible-set change rebuilds only its affected pages, each containing at most sixteen terrain chunks.
 */
final class GpuPropBatch implements Disposable {
    static final int CHUNKS_PER_PAGE = 4;
    private static final float[] IDENTITY = new Matrix4().val;
    private final Map<Integer, Page> pages = new HashMap<>();
    private final Array<Renderable> source = new Array<>();
    private final Array<Renderable> separate = new Array<>();
    private long rebuilds;

    private static final class Page implements RenderableProvider {
        final Array<Renderable> current = new Array<>();
        final Array<Renderable> previous = new Array<>();
        final Array<Renderable> cached = new Array<>();

        @Override
        public void getRenderables(Array<Renderable> destination, Pool<Renderable> pool) {
            for (Renderable value : cached) { value.shader = null; value.environment = null; }
            destination.addAll(cached);
        }
    }

    void begin() {
        source.clear();
        separate.clear();
        for (Page page : pages.values()) { page.current.clear(); }
    }

    void add(RenderableProvider props, int pageId) {
        // Chunk.solidProps supplies persistent renderables and does not need a temporary renderable pool.
        source.clear();
        props.getRenderables(source, null);
        for (Renderable value : source) {
            if (eligible(value)) {
                pages.computeIfAbsent(pageId, key -> new Page()).current.add(value);
            } else {
                separate.add(value);
            }
        }
    }

    void render(ModelBatch batch, Environment environment) {
        for (Page page : pages.values()) {
            // Keep an invisible page's last meshes for a return visit; there is only one cache per board page.
            if (page.current.isEmpty()) { continue; }
            boolean changed = page.previous.size != page.current.size;
            for (int i = 0; !changed && i < page.current.size; i++) {
                changed = page.previous.get(i) != page.current.get(i);
            }
            if (changed) {
                disposeMeshes(page.cached);
                copy(page.current, page.cached);
                page.previous.clear();
                page.previous.addAll(page.current);
                rebuilds++;
            }
            batch.render(page, environment);
        }
        for (Renderable value : separate) {
            value.environment = environment;
            batch.render(value);
        }
    }

    private static boolean eligible(Renderable value) {
        return value.bones == null && !value.meshPart.mesh.isInstanced()
              && value.meshPart.primitiveType == GL20.GL_TRIANGLES && value.meshPart.size > 0
              && value.meshPart.mesh.getNumIndices() > 0 && Arrays.equals(value.worldTransform.val, IDENTITY)
              && value.material.getMask() == ColorAttribute.Diffuse;
    }

    /** Chunk caches already contain world-space vertices. Copy them without normalizing their normals again. */
    private static void copy(Array<Renderable> source, Array<Renderable> destination) {
        Array<Renderable> ordered = new Array<>(source);
        new ModelCache.Sorter().sort(null, ordered);
        MeshBuilder builder = null;
        VertexAttributes attributes = null;
        Material material = null;
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
                    Renderable combined = new Renderable();
                    combined.material = material = value.material;
                    builder.part("props", GL20.GL_TRIANGLES, combined.meshPart);
                    destination.add(combined);
                }
                builder.addMesh(value.meshPart);
            }
            if (builder != null) { builder.end(); }
        } catch (RuntimeException | Error failure) {
            disposeMeshes(destination);
            throw failure;
        }
    }

    /** Counts geometry uploads requested by visible-source changes, for native performance checks. */
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
        for (Page page : pages.values()) { disposeMeshes(page.cached); }
        pages.clear();
        source.clear();
        separate.clear();
    }
}
