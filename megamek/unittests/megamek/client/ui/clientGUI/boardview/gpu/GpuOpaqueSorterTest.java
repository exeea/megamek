/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.utils.Array;
import org.junit.jupiter.api.Test;

class GpuOpaqueSorterTest {
    @Test
    void coverUsesDefaultOrderingForTheWholePassAndGroupingResumesWithoutIt() {
        var camera = new OrthographicCamera();
        var sorter = new GpuOpaqueSorter();
        Shader a = mock(Shader.class), b = mock(Shader.class);
        Renderable nearA = part(a, 1), middleB = part(b, 2), middleA = part(a, 3);
        Renderable tiedB = part(b, 3), farB = part(b, 4);
        Renderable glassNear = part(a, 1.5f), glassFar = part(b, 5);
        glassNear.material.set(new BlendingAttribute(true, .5f));
        glassFar.material.set(new BlendingAttribute(true, .5f));
        var source = new Array<>(new Renderable[] { farB, glassNear, middleA, nearA, glassFar, middleB, tiedB });

        Array<Renderable> grouped = new Array<>(source);
        sorter.sort(camera, grouped);
        assertEquals(1, shaderChanges(grouped, 5), "An overview still groups its opaque shader programs");
        assertSame(glassFar, grouped.get(5));
        assertSame(glassNear, grouped.get(6));

        middleB.material.set(new GpuGroundCover.Wind());
        Array<Renderable> reference = new Array<>(source), covered = new Array<>(source);
        new DefaultRenderableSorter().sort(camera, reference);
        sorter.sort(camera, covered);
        for (int i = 0; i < reference.size; i++) {
            assertSame(reference.get(i), covered.get(i), "Cover preserves every part's order, including depth ties");
        }
        assertSame(middleA, covered.get(2));
        assertSame(tiedB, covered.get(3));

        middleB.material.remove(GpuGroundCover.Wind.TYPE);
        Array<Renderable> nextOverview = new Array<>(source);
        sorter.sort(camera, nextOverview);
        for (int i = 0; i < grouped.size; i++) {
            assertSame(grouped.get(i), nextOverview.get(i), "Cover in an earlier pass must not disable later grouping");
        }
    }

    private static Renderable part(Shader shader, float distance) {
        var part = new Renderable();
        part.material = new Material();
        part.shader = shader;
        part.worldTransform.setToTranslation(distance, 0, 0);
        return part;
    }

    private static int shaderChanges(Array<Renderable> parts, int count) {
        int changes = 0;
        for (int i = 1; i < count; i++) {
            if (parts.get(i - 1).shader != parts.get(i).shader) { changes++; }
        }
        return changes;
    }
}
