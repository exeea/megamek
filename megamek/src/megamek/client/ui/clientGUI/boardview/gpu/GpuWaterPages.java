/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** Stable spatial batches of water, with one world-aligned field atlas per page. No uploads during camera pans. */
final class GpuWaterPages implements Disposable {
    static final int CHUNKS_PER_PAGE = GpuMeshPage.CHUNKS_PER_PAGE;
    private final Map<Integer, Page> pages = new HashMap<>();
    private final Array<Renderable> separate = new Array<>();
    private final Array<Renderable> previousSeparate = new Array<>();
    private long rebuilds;
    private long atlasUploads;
    private long maxBuildNanos;
    private boolean enabled = true;
    private final Array<Renderable> depthParts = new Array<>();
    private int depthCount;
    private final Map<Material, Material> depthMaterials = new IdentityHashMap<>();

    void setEnabled(boolean value) { enabled = value; }

    private static final class Page implements Disposable {
        final Array<Renderable> sources = new Array<>(), previous = new Array<>();
        final Array<Boolean> visibility = new Array<>();
        final Array<Renderable> copies = new Array<>();
        final GpuMeshPage mesh = new GpuMeshPage();
        GpuWaterShader.Field atlas;
        List<GpuWaterShader.Field.Prepared> images = List.of();
        int stableFrames;

        boolean build() {
            var fields = new IdentityHashMap<GpuWaterShader.Field, Boolean>();
            for (Renderable source : sources) { fields.put(water(source).batchField(), Boolean.TRUE); }
            // Traversal order is stable, including when source padding overlaps at a chunk boundary.
            var nextImages = new java.util.ArrayList<GpuWaterShader.Field.Prepared>();
            for (Renderable source : sources) {
                var field = water(source).batchField();
                if (fields.remove(field) != null) { nextImages.add(field.image); }
            }
            boolean changed = atlas == null || images.size() != nextImages.size();
            for (int i = 0; !changed && i < images.size(); i++) { changed = !images.get(i).samePixels(nextImages.get(i)); }
            if (changed) {
                disposeAtlas();
                atlas = GpuWaterShader.Field.atlas(nextImages).upload(false);
            }
            images = nextImages;
            for (Renderable source : sources) {
                Renderable copy = new Renderable().set(source);
                copy.material = new Material(source.material);
                copy.material.set(water(source).withField(atlas));
                copies.add(copy);
            }
            return changed;
        }

        void invalidateMesh() {
            mesh.dispose();
            copies.clear();
            stableFrames = 0;
        }

        private void disposeAtlas() {
            if (atlas != null) { atlas.dispose(); atlas = null; }
            images = List.of();
        }

        @Override
        public void dispose() { invalidateMesh(); disposeAtlas(); }
    }

    void begin() {
        separate.clear();
        for (Page page : pages.values()) { page.sources.clear(); page.visibility.clear(); }
    }

    void add(Array<Renderable> parts, int pageId, boolean shown, boolean waterVisible) {
        for (Renderable part : parts) {
            GpuWaterShader water = water(part);
            // Lake ice shares this pass, but only liquid water disappears at zero gravity.
            if (!waterVisible && water != null) { continue; }
            if (enabled && water != null && water.batchField() != null && !part.material.has(GpuLiquidShader.Frame.TYPE)) {
                Page page = pages.computeIfAbsent(pageId, ignored -> new Page());
                page.sources.add(part);
                page.visibility.add(shown);
            } else if (shown) { separate.add(part); }
        }
    }

    void prepare() {
        boolean separateChanged = separate.size != previousSeparate.size;
        for (int i = 0; !separateChanged && i < separate.size; i++) { separateChanged = separate.get(i) != previousSeparate.get(i); }
        if (separateChanged) {
            depthMaterials.clear(); depthParts.clear(); previousSeparate.clear(); previousSeparate.addAll(separate);
        }
        if (!enabled) { return; }
        boolean built = false;
        for (Page page : pages.values()) {
            long buildStarted = 0;
            boolean changed = page.sources.size != page.previous.size;
            for (int i = 0; !changed && i < page.sources.size; i++) { changed = page.sources.get(i) != page.previous.get(i); }
            if (changed) {
                page.invalidateMesh(); page.previous.clear(); page.previous.addAll(page.sources);
                if (page.sources.size <= 1) { page.disposeAtlas(); }
                depthMaterials.clear();
                depthParts.clear();
            } else {
                page.stableFrames++;
            }
            // Coalesce consecutive chunk installations. Geometry LOD usually leaves canonical field pixels intact.
            if (!built && page.copies.isEmpty() && page.stableFrames >= 2 && page.sources.size > 1 && page.visibility.contains(true, false)) {
                buildStarted = System.nanoTime();
                if (page.build()) { atlasUploads++; }
                built = true; rebuilds++;
                depthMaterials.clear(); depthParts.clear();
            }
            page.mesh.begin();
            for (int i = 0; i < page.sources.size; i++) {
                page.mesh.add(page.copies.isEmpty() ? page.sources.get(i) : page.copies.get(i), page.visibility.get(i));
            }
            page.mesh.update();
            if (!page.copies.isEmpty() && page.mesh.needsBuild()) { page.mesh.build(); }
            if (buildStarted != 0) { maxBuildNanos = Math.max(maxBuildNanos, System.nanoTime() - buildStarted); }
        }
    }

    void render(ModelBatch batch, Environment environment, boolean depth) {
        depthCount = 0;
        if (enabled) {
            for (Page page : pages.values()) {
                page.mesh.forEachVisible(part -> submit(batch, environment, part, depth));
            }
        }
        for (Renderable part : separate) { submit(batch, environment, part, depth); }
    }

    private void submit(ModelBatch batch, Environment environment, Renderable part, boolean depth) {
        if (depth) {
            GpuWaterShader water = water(part);
            if (water == null || water.mode != GpuWaterShader.Mode.SURFACE) { return; }
            if (depthCount == depthParts.size) { depthParts.add(new Renderable()); }
            var copy = depthParts.get(depthCount++).set(part);
            copy.material = depthMaterials.computeIfAbsent(part.material, original -> {
                Material material = new Material(original);
                material.set(water.depth());
                material.remove(BlendingAttribute.Type);
                material.set(new DepthTestAttribute(GL20.GL_LEQUAL, true));
                return material;
            });
            part = copy;
        }
        part.shader = null; part.environment = environment; batch.render(part);
    }

    private static GpuWaterShader water(Renderable part) { return part.material.get(GpuWaterShader.class, GpuWaterShader.TYPE); }
    long rebuilds() { return rebuilds; }
    long atlasUploads() { return atlasUploads; }
    long maxBuildNanos() { return maxBuildNanos; }

    long atlasBytes() {
        long bytes = 0;
        for (Page page : pages.values()) {
            if (page.atlas != null) { bytes += (long) page.atlas.texture.getWidth() * page.atlas.texture.getHeight() * 4; }
        }
        return bytes;
    }

    @Override
    public void dispose() {
        pages.values().forEach(Page::dispose); pages.clear(); separate.clear();
        depthParts.clear(); depthMaterials.clear(); previousSeparate.clear();
    }
}
