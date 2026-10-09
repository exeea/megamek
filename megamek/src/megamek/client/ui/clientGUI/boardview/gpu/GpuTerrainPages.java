/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/**
 * Small ranges shared by nearby chunks: opaque terrain, and authored paint, whose equal numbered draws
 * ({@link GpuDecalOrder#layer}) merge. Large meshes keep their original storage. Partial pages select
 * visible ranges in their existing buffers, preserving chunk culling. Camera movement never rebuilds a page.
 * Only replacement source ranges invalidate it. Perspective passes retain the original projected-detail uniforms.
 */
final class GpuTerrainPages implements Disposable {
    static final int CHUNKS_PER_PAGE = GpuMeshPage.CHUNKS_PER_PAGE;
    private static final int MAX_PART_INDICES = 8192;
    private static final int MAX_PAGE_INDICES = 65536;
    private final Predicate<Renderable> eligible;
    private final boolean perspective;
    private final Map<Integer, GpuMeshPage> pages = new HashMap<>();
    private final Array<Renderable> separate = new Array<>();
    private boolean enabled = true, active;
    private long rebuilds;

    GpuTerrainPages(Predicate<Renderable> eligible) { this(eligible, false); }
    GpuTerrainPages(Predicate<Renderable> eligible, boolean perspective) { this.eligible = eligible; this.perspective = perspective; }

    void setEnabled(boolean value) { enabled = value; }

    void begin(Camera camera) {
        active = enabled && (perspective || camera.projection.val[Matrix4.M33] != 0);
        separate.clear();
        if (active) { pages.values().forEach(GpuMeshPage::begin); }
    }

    /** Supply every chunk, including invisible ones, so visibility cannot change a page's geometry. */
    void add(Array<Renderable> parts, int pageId, boolean visible) {
        if (!active && !visible) { return; }
        GpuMeshPage page = active ? pages.computeIfAbsent(pageId, ignored -> new GpuMeshPage()) : null;
        for (Renderable part : parts) {
            part.shader = null;
            part.environment = null;
            // An empty part (a paint draw whose decal found no receiving face here) draws nothing and copies nothing.
            if (part.meshPart.size == 0) { continue; }
            if (active && part.meshPart.mesh.getNumIndices() > 0 && part.meshPart.size % 3 == 0
                  && part.meshPart.size <= MAX_PART_INDICES
                  && page.indices + part.meshPart.size <= MAX_PAGE_INDICES && eligible.test(part)) {
                page.add(part, visible);
            } else if (visible) {
                separate.add(part);
            }
        }
    }

    void render(ModelBatch batch, Environment environment) {
        if (active) {
            // Copy/upload at most one bounded page per frame; originals draw while other pages await preparation.
            boolean built = false;
            for (GpuMeshPage page : pages.values()) {
                page.update();
                if (!built && page.needsBuild()) {
                    page.build();
                    built = true;
                    rebuilds++;
                }
                page.render(batch, environment);
            }
        }
        submit(batch, environment, separate);
    }

    private static void submit(ModelBatch batch, Environment environment, Array<Renderable> parts) {
        for (Renderable part : parts) {
            part.shader = null;
            part.environment = environment;
            batch.render(part);
        }
    }

    long rebuilds() { return rebuilds; }

    @Override
    public void dispose() {
        pages.values().forEach(GpuMeshPage::dispose);
        pages.clear();
        separate.clear();
    }
}
