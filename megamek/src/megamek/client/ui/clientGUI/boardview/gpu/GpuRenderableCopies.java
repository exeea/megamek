/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;

/** Pooled copies of parts drawn in another material for a while (a frame, a fill), freed together; sources unchanged. */
final class GpuRenderableCopies {
    private final Array<Renderable> copies = new Array<>();
    private final Pool<Renderable> pool = new Pool<>() {
        @Override
        protected Renderable newObject() { return new Renderable(); }
    };

    /** A copy of {@code part} in {@code material}, valid until {@link #clear}. */
    Renderable of(Renderable part, Material material) {
        Renderable copy = pool.obtain().set(part);
        copy.material = material;
        copies.add(copy);
        return copy;
    }

    void clear() {
        pool.freeAll(copies);
        copies.clear();
    }
}
