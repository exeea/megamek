/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/** Procedural, unlit aerospace artwork. The terrain batch owns its shader; no texture or separate pass is allocated. */
final class GpuBackdrop extends FloatAttribute {
    static final long TYPE = register("boardBackdrop");

    private GpuBackdrop(float value) { super(TYPE, value); }

    static Material material(BoardBackdrop backdrop) {
        return new Material("backdrop-" + backdrop, new GpuBackdrop(backdrop.ordinal()),
              IntAttribute.createCullFace(GL20.GL_NONE));
    }

    static void register(DefaultShader shader) {
        shader.register("u_backdropHexWidth", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, BoardGeometry.width());
            }
        });
    }

    @Override
    public GpuBackdrop copy() { return new GpuBackdrop(value); }
}
