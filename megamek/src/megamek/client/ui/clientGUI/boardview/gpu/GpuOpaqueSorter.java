/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.utils.Array;

/** Group opaque shaders unless ground cover needs the original depth order; transparency keeps its depth ordering. */
final class GpuOpaqueSorter extends DefaultRenderableSorter {
    private boolean groupShaders = true;

    @Override
    public void sort(Camera camera, Array<Renderable> renderables) {
        groupShaders = true;
        for (Renderable renderable : renderables) {
            if (renderable.material.has(GpuGroundCover.Wind.TYPE)) {
                // Grass intersects the ground at depth-buffer precision. Keep its original tie winners,
                // using one ordering for the whole pass so the comparator remains transitive.
                groupShaders = false;
                break;
            }
        }
        super.sort(camera, renderables);
    }

    @Override
    public int compare(Renderable left, Renderable right) {
        var a = left.material.get(BlendingAttribute.class, BlendingAttribute.Type);
        var b = right.material.get(BlendingAttribute.class, BlendingAttribute.Type);
        if (groupShaders && (a == null || !a.blended) && (b == null || !b.blended)) {
            int shader = Integer.compare(System.identityHashCode(left.shader), System.identityHashCode(right.shader));
            if (shader != 0) { return shader; }
        }
        return super.compare(left, right);
    }
}
