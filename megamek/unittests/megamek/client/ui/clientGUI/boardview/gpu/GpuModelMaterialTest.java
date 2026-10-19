/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** LibGDX's cache uses material equality, not only sort order, when deciding which draws may merge. */
class GpuModelMaterialTest {
    @Test
    void slotUniformDecodesLivePackedColoursWithoutChangingTheirOwner() {
        var shader = mock(DefaultShader.class);
        GpuModelMaterial.register(shader);
        var setter = ArgumentCaptor.forClass(BaseShader.Setter.class);
        verify(shader).register(eq("u_slotColours[0]"), setter.capture());
        var target = mock(BaseShader.class);
        target.program = mock(ShaderProgram.class);
        when(target.loc(4)).thenReturn(19);
        var part = new Renderable();
        part.material = new Material();
        float[] packed = { 0x0080ff, -7, 0xffffff, 0xff00ff };
        part.userData = packed;
        float[] original = packed.clone();
        setter.getValue().set(target, 4, part, part.material);
        var values = ArgumentCaptor.forClass(float[].class);
        verify(target.program).setUniform3fv(eq(19), values.capture(), eq(0), eq(12));
        // Independent byte-code references, including odd packed integers above 2^23 and an own-colour sentinel.
        assertArrayEquals(new float[] { 0, .2158605f, 1, -1, -1, -1, 1, 1, 1, 1, 0, 1 }, values.getValue(), .0000001f);
        assertArrayEquals(original, packed, "Shared placement colours remain packed and unchanged");

        packed[0] = 0x0a8000;
        packed[1] = 0;
        clearInvocations(target.program);
        setter.getValue().set(target, 4, part, part.material);
        verify(target.program).setUniform3fv(eq(19), values.capture(), eq(0), eq(12));
        assertArrayEquals(new float[] { .00303527f, .2158605f, 0, 0, 0, 0, 1, 1, 1, 1, 0, 1 },
              values.getValue(), .0000001f, "Live changes and a real black replacement are uploaded on the next draw");

        part.userData = null;
        clearInvocations(target.program);
        setter.getValue().set(target, 4, part, part.material);
        verify(target.program).setUniform3fv(eq(19), values.capture(), eq(0), eq(12));
        assertArrayEquals(new float[] { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 }, values.getValue(),
              "Returning to the model's own colours must reset every replacement");
    }

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
