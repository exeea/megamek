/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** Owns copied geometry for a fixed spatial page; visibility selects index ranges without rebuilding meshes. */
final class GpuMeshPage implements Disposable {
    static final int CHUNKS_PER_PAGE = 4;
    record Range(Renderable source, Renderable combined, int offset, int size) { }

    final Array<Renderable> current = new Array<>();
    final Array<Renderable> visible = new Array<>();
    final Array<Renderable> cached = new Array<>();
    private final Array<Renderable> previous = new Array<>();
    private final Array<Range> ranges = new Array<>();
    private final Set<Renderable> visibleSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Array<Renderable> runs = new Array<>();
    private boolean prepared;
    int indices;

    void begin() {
        current.clear();
        visible.clear();
        visibleSet.clear();
        indices = 0;
    }

    void add(Renderable part, boolean shown) {
        current.add(part);
        indices += part.meshPart.size;
        if (shown) { visible.add(part); visibleSet.add(part); }
    }

    /** All sources are supplied, including hidden chunks. Only source replacement invalidates the cache. */
    void update() {
        boolean changed = current.size != previous.size;
        for (int i = 0; !changed && i < current.size; i++) { changed = current.get(i) != previous.get(i); }
        if (!changed) { return; }
        dispose();
        previous.addAll(current);
    }

    boolean needsBuild() {
        return !prepared && current.size > 1 && !visible.isEmpty();
    }

    void build() {
        GpuPropBatch.copy(current, cached, ranges);
        if (cached.size >= current.size) { dispose(); previous.addAll(current); }
        prepared = true;
    }

    void render(ModelBatch batch, Environment environment) {
        forEachVisible(part -> submit(batch, environment, part));
    }

    /** The exact same culled ranges can feed a depth pass without copying or rebuilding the page. */
    void forEachVisible(Consumer<Renderable> submit) {
        if (visible.isEmpty()) { return; }
        if (cached.isEmpty()) { visible.forEach(submit); return; }
        if (visible.size == current.size) { cached.forEach(submit); return; }

        // A partial page keeps only visible sources. Adjacent ranges of the same material/mesh form one draw.
        // Reuse the small renderable pool; no vertex or index buffers change during a pan.
        int used = 0;
        Renderable run = null;
        Renderable combined = null;
        for (Range range : ranges) {
            if (!visibleSet.contains(range.source())) { run = null; continue; }
            if (run != null && combined == range.combined()
                  && run.meshPart.offset + run.meshPart.size == range.offset()) {
                run.meshPart.size += range.size();
            } else {
                if (used == runs.size) { runs.add(new Renderable()); }
                run = runs.get(used++);
                run.set(combined = range.combined());
                run.meshPart.offset = range.offset();
                run.meshPart.size = range.size();
            }
        }
        for (int i = 0; i < used; i++) { submit.accept(runs.get(i)); }
    }

    private static void submit(ModelBatch batch, Environment environment, Renderable part) {
        part.shader = null;
        part.environment = environment;
        batch.render(part);
    }

    @Override
    public void dispose() {
        GpuPropBatch.disposeMeshes(cached);
        previous.clear();
        ranges.clear();
        runs.clear();
        prepared = false;
    }
}
