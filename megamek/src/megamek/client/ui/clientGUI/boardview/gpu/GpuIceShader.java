/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/** One shared optical coat on existing surfaces; hidden black ice never receives this attribute. */
final class GpuIceShader extends FloatAttribute {
    static final long TYPE = register("boardIce");

    private GpuIceShader(float value) { super(TYPE, value); }
    @Override public GpuIceShader copy() { return new GpuIceShader(value); }

    static Material ground(Material material, BoardScene.Tile tile) {
        return apply(material, tile.frozen() ? 1 : tile.blackIce() && tile.surface() == BoardScene.Surface.CONCRETE ? 2 : 0);
    }

    static Material road(Material material, BoardScene.Tile tile) {
        return apply(material, tile.frozen() ? 1 : tile.blackIce() ? 2 : 0);
    }

    static Material lake(Material material) { return apply(material, 3); }

    private static Material apply(Material material, int mode) {
        if (mode == 0) { return material; }
        Material result = new Material(material);
        result.set(new GpuIceShader(mode));
        return result;
    }

    static String fragment(String source) {
        // Compose after shared lighting/cloud insertion. Only iced material variants compile the extra samplers.
        return source.replace("void main()", "void iceBaseSurface()") + "\n"
              + GpuShaderSource.read("terrain-ice.glsl");
    }

    static void register(DefaultShader shader, GpuAssets assets) {
        shader.register("u_iceMode", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var ice = attributes.get(GpuIceShader.class, TYPE);
                if (ice != null) { target.set(id, ice.value); }
            }
        });
        shader.register("u_iceMetre", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, BoardRelief.metres(1));
            }
        });
        String[] uniforms = {"u_iceColor", "u_iceNormal", "u_iceSurface"};
        for (int index = 0; index < uniforms.length; index++) {
            int map = index;
            shader.register(uniforms[index], new BaseShader.LocalSetter() {
                @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    var maps = assets.ice();
                    target.set(id, map == 0 ? maps.color() : map == 1 ? maps.normal() : maps.surface());
                }
            });
        }
    }
}
