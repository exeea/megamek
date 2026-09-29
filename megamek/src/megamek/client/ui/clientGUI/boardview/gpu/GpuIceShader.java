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

    static Material ground(Material material, BoardScene scene, BoardScene.Tile tile) {
        boolean paved = tile.surface() == BoardScene.Surface.CONCRETE;
        return apply(material, tile.frozen() ? 1 : tile.blackIce() && paved ? 2 : fringe(scene, tile, paved));
    }

    static Material road(Material material, BoardScene scene, BoardScene.Tile tile) {
        return apply(material, tile.frozen() ? 1 : tile.blackIce() ? 2 : fringe(scene, tile, true));
    }

    /**
     * A dry tile beside ice at its level draws its side of their jagged shared border: 4 beside frozen land, 5 also
     * beside detected black ice, which only paved ground carries. The ice shader finds the border itself.
     */
    private static int fringe(BoardScene scene, BoardScene.Tile tile, boolean paved) {
        if (scene == null || tile.frozen() || tile.blackIce() || tile.liquid().present()) { return 0; }
        int mode = 0;
        for (int direction = 0; direction < 6; direction++) {
            var other = scene.tile(tile.coords().translated(direction));
            if (other == null || other.liquid().present() || other.elevation() != tile.elevation()) { continue; }
            if (other.frozen() || paved && other.blackIce()) { mode = paved ? 5 : 4; }
        }
        return mode;
    }

    /** Land ice on a surface that belongs to another tile, such as a frozen lake's bank below frozen land. */
    static Material land(Material material) { return apply(material, 1); }

    static Material lake(Material material) { return apply(material, 3); }

    static boolean floating(Material material) {
        var ice = material.get(GpuIceShader.class, TYPE);
        return ice != null && ice.value == 3;
    }

    private static Material apply(Material material, int mode) {
        if (mode == 0) { return material; }
        Material result = new Material(material);
        result.set(new GpuIceShader(mode));
        return result;
    }

    static String fragment(String source) {
        // Compose after shared lighting/cloud insertion. Only iced material variants compile the extra samplers.
        return source.replace("void main()", "void iceBaseSurface()") + "\n"
              + GpuShaderSource.read("terrain-ice.glsl")
              + "\nvoid main() {\n    iceBaseSurface();\n#ifdef iceFlag\n    fragColor.rgb = iceFinish(fragColor.rgb);\n#endif\n}\n";
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
        shader.register("u_iceLevel", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, BoardGeometry.level());
            }
        });
        shader.register("u_iceWaterline", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                // BoardGeometry.waterZ: open water lies one tile pixel below its hex's level.
                target.set(id, BoardGeometry.hexScale());
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
