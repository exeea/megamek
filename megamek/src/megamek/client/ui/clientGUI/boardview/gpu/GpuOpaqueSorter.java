/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.IdentityHashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.utils.Array;

/** Group opaque shaders unless ground cover needs the original depth order; transparency keeps its depth ordering. */
final class GpuOpaqueSorter extends DefaultRenderableSorter {
    private boolean groupShaders = true;
    private final Map<Shader, Integer> shaderOrder = new IdentityHashMap<>();

    @Override
    public void sort(Camera camera, Array<Renderable> renderables) {
        groupShaders = true;
        shaderOrder.clear();
        for (Renderable renderable : renderables) {
            if (renderable.material.has(GpuGroundCover.Wind.TYPE)) {
                // Grass intersects the ground at depth-buffer precision. Keep its original tie winners,
                // using one ordering for the whole pass so the comparator remains transitive.
                groupShaders = false;
                break;
            }
            // Allocation identity used to reshuffle groups when programs were recreated, changing depth ties
            // and early depth rejection. Encounter order is stable for the same submitted scene.
            shaderOrder.computeIfAbsent(renderable.shader, ignored -> shaderOrder.size());
        }
        super.sort(camera, renderables);
    }

    @Override
    public int compare(Renderable left, Renderable right) {
        var a = left.material.get(BlendingAttribute.class, BlendingAttribute.Type);
        var b = right.material.get(BlendingAttribute.class, BlendingAttribute.Type);
        if (groupShaders && (a == null || !a.blended) && (b == null || !b.blended)) {
            int shader = Integer.compare(shaderOrder.get(left.shader), shaderOrder.get(right.shader));
            if (shader != 0) { return shader; }
        }
        return super.compare(left, right);
    }
}
