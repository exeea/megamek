/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import org.junit.jupiter.api.Test;

/** LibGDX's cache uses material equality, not only sort order, when deciding which draws may merge. */
class GpuModelMaterialTest {
    @Test
    void batchesOnlyMatchingSurfaceValuesAndTextureIdentities() {
        Texture first = mock(Texture.class), second = mock(Texture.class);
        when(first.getTextureObjectHandle()).thenReturn(10);
        when(second.getTextureObjectHandle()).thenReturn(11);
        var surface = new GpuModelMaterial(.4f, .8f, first);
        var material = new Material(surface);
        assertTrue(material.same(material.copy(), true), "Copies may share a draw and borrow the same texture");
        assertEquals(surface.hashCode(), surface.copy().hashCode());
        assertFalse(material.same(new Material(new GpuModelMaterial(.5f, .8f, first)), true));
        assertFalse(material.same(new Material(new GpuModelMaterial(.4f, 0, first)), true));
        assertFalse(material.same(new Material(new GpuModelMaterial(.4f, .8f, second)), true));
        assertFalse(material.same(new Material(new GpuModelMaterial(.4f, .8f, null)), true));
    }
}
