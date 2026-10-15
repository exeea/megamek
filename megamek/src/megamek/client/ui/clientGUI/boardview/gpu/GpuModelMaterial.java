/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.math.Matrix4;

/** Authored glTF surface parameters. The model library owns the optional linear G-roughness/B-metalness map. */
final class GpuModelMaterial extends Attribute {
    static final long TYPE = register("boardModelSurface");
    final float roughness;
    final float metallic;
    final Texture map;

    GpuModelMaterial(float roughness, float metallic, Texture map) {
        super(TYPE);
        this.roughness = roughness;
        this.metallic = metallic;
        this.map = map;
    }

    static void register(DefaultShader shader) {
        shader.register("u_modelSurface", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var surface = attributes.get(GpuModelMaterial.class, TYPE);
                target.set(id, surface.roughness, surface.metallic, surface.map == null ? 0f : 1f);
            }
        });
        shader.register("u_modelSurfaceMap", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var surface = attributes.get(GpuModelMaterial.class, TYPE);
                if (surface.map == null) { target.set(id, 0); }
                else { target.set(id, surface.map); }
            }
        });
        // A recolourable model's shared mesh drawn one by one (tilted or faded placements, hover copies, combat scars, the
        // side view): its placement's colours. Instanced batches carry them per instance instead (GpuTreeInstances).
        shader.register("u_slotColours[0]", new BaseShader.LocalSetter() {
            private final float[] linear = new float[12];

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                float[] colours = RigidGlb.slotColours(renderable.userData);
                for (int slot = 0; slot < 4; slot++) {
                    int at = slot * 3;
                    if (colours[slot] < 0) {
                        linear[at] = linear[at + 1] = linear[at + 2] = -1;
                    } else {
                        int rgb = (int) colours[slot];
                        linear[at] = GpuColor.linear((rgb >> 16 & 255) / 255f);
                        linear[at + 1] = GpuColor.linear((rgb >> 8 & 255) / 255f);
                        linear[at + 2] = GpuColor.linear((rgb & 255) / 255f);
                    }
                }
                target.program.setUniform3fv(target.loc(id), linear, 0, linear.length);
            }
        });
        shader.register("u_modelView", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var camera = target.camera;
                target.set(id, camera.direction.x, camera.direction.y, camera.direction.z,
                      camera.projection.val[Matrix4.M33] == 0 ? 1f : 0f);
            }
        });
    }

    @Override
    public Attribute copy() { return new GpuModelMaterial(roughness, metallic, map); }

    @Override
    protected boolean equals(Attribute other) { return compareTo(other) == 0; }

    @Override
    public int hashCode() {
        int result = 31 * super.hashCode() + Float.floatToIntBits(roughness);
        result = 31 * result + Float.floatToIntBits(metallic);
        return 31 * result + (map == null ? 0 : map.getTextureObjectHandle());
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        var surface = (GpuModelMaterial) other;
        int order = Float.compare(roughness, surface.roughness);
        if (order == 0) { order = Float.compare(metallic, surface.metallic); }
        if (order == 0) {
            order = Integer.compare(map == null ? 0 : map.getTextureObjectHandle(),
                  surface.map == null ? 0 : surface.map.getTextureObjectHandle());
        }
        return order;
    }
}
