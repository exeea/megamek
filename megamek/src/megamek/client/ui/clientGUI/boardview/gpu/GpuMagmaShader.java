/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/** Opaque solid/flowing magma materials; GpuAssets owns their repeating maps. */
final class GpuMagmaShader extends Attribute {
    static final long TYPE = register("boardMagmaSurface");
    static final int CRUST = 0, LAVA = 1, FALL = 2, BANK = 3;

    private final int mode;
    private final GpuWaterShader.Field field;

    private GpuMagmaShader(int mode, GpuWaterShader.Field field) {
        super(TYPE);
        this.mode = mode;
        this.field = mode == LAVA || mode == FALL ? field : null;
    }

    GpuMagmaShader withField(GpuWaterShader.Field field) { return new GpuMagmaShader(mode, field); }

    boolean flowing() { return mode == LAVA || mode == FALL; }

    /** Both programs share texture projection; each owns its relief, advection and heat treatment. */
    static String functions(boolean flowing) {
        return GpuShaderSource.read("terrain-magma.glsl").replace("// MAGMA_CONDITION",
              GpuShaderSource.read(flowing ? "magma-flow.glsl" : "magma-solid.glsl"));
    }

    @Override
    public GpuMagmaShader copy() { return withField(field); }

    @Override
    public int hashCode() { return 31 * (31 * super.hashCode() + mode) + System.identityHashCode(field); }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        GpuMagmaShader magma = (GpuMagmaShader) other;
        int order = Integer.compare(mode, magma.mode);
        return order != 0 ? order : Integer.compare(System.identityHashCode(field), System.identityHashCode(magma.field));
    }

    static Material material(GpuAssets assets, int mode, GpuWaterShader.Field field) {
        var maps = assets.magma(mode == LAVA || mode == FALL);
        if (maps == null) { return null; }
        return new Material("magma:" + mode, new GpuMagmaShader(mode, field),
              TextureAttribute.createDiffuse(maps.relief().color()),
              TextureAttribute.createNormal(maps.relief().normal()),
              // Reuse the standard texture slots: surface is height/roughness/AO/range, emissive is heat/flow/halo.
              TextureAttribute.createSpecular(maps.relief().surface()), TextureAttribute.createEmissive(maps.heat()),
              IntAttribute.createCullFace(GL20.GL_NONE));
    }

    static void register(DefaultShader shader) {
        shader.register("u_magmaMode", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                if (magma != null) { target.set(id, (float) magma.mode); }
            }
        });
        shader.register("u_magmaField", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                if (magma != null && magma.field != null) { target.set(id, magma.field.texture); }
            }
        });
        shader.register("u_magmaFieldMap", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                if (magma != null && magma.field != null) {
                    var field = magma.field;
                    target.set(id, field.scaleX, field.scaleY, field.offsetX, field.offsetY);
                } else { target.set(id, 0f, 0f, 0f, 0f); }
            }
        });
    }
}
