/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;

/** Unit paint, damage and imported surface lighting. The ModelBatch owns the provider and its shaders. */
final class GpuUnitShader extends DefaultShader {
    private static final String MAIN = "void main() {";

    private final int rotation = register("u_camoRotation");
    private final int camoEnabled = register("u_camoEnabled");
    private final int camoImageSize = register("u_camoImageSize");
    private final int paintTransform = register("u_paintTransform");
    private final int paintNormalMatrix = register("u_paintNormalMatrix");
    private final int markerTexture = register("u_markerTexture");
    private final int markerEnabled = register("u_markerEnabled");
    private final int damageTexture = register("u_damageTexture");
    private final int damageEnabled = register("u_damageEnabled");
    private final int damageOpacity = register("u_damageOpacity");
    private final int damageTransform = register("u_damageTransform");

    private GpuUnitShader(Renderable renderable, Config config) {
        super(renderable, config, GpuGlsl.compile("GPU unit material", GpuCloudShadow.prefix(renderable, config),
              config.vertexShader, config.fragmentShader));
        GpuCloudShadow.register(this);
        GpuLavaLighting.register(this);
        GpuModelMaterial.register(this);
        register("u_normalMaps", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable part, Attributes attributes) { target.set(id, 1f); }
        });
    }

    static DefaultShaderProvider provider() {
        return new DefaultShaderProvider(GpuCloudShadow.vertex(vertexSource(linearVertex(getDefaultVertexShader()))),
              GpuCloudShadow.fragment(fragmentSource(linearFragment(getDefaultFragmentShader())), false)) {
            @Override
            protected Shader createShader(Renderable renderable) {
                return new GpuUnitShader(renderable, config);
            }
        };
    }

    // These insertion points are the source contract with the pinned libGDX version.
    // Reuse upstream material/shadow plumbing, and fail explicitly if an upgrade changes this contract.
    static String vertexSource(String source) {
        String declarations = GpuShaderSource.read("unit-material.vert");
        source = replaceOnce(source, MAIN, declarations + "\n" + MAIN + "\n    unitMaterialCoordinates();\n", "vertex");
        return replaceOnce(source, "v_diffuseUV = u_diffuseUVTransform.xy + a_texCoord0 * u_diffuseUVTransform.zw;",
              "v_diffuseUV = unitDiffuseUV();", "vertex");
    }

    static String fragmentSource(String source) {
        String declarations = GpuShaderSource.read("unit-material.frag");
        source = replaceOnce(source, MAIN, declarations + "\n" + MAIN, "fragment");
        String emissive = "#if defined(emissiveTextureFlag) && defined(emissiveColorFlag)";
        return replaceOnce(source, emissive, "diffuse.rgb = unitOverlays(diffuse.rgb);\n" + emissive, "fragment");
    }

    /**
     * libGDX's vertex lighting on the board's one light model (light-model.glsl): the ambient term becomes the sky
     * above and the sunlit ground below, as on the terrain. Units, props, buildings and liquids share it. The sunlit
     * ground's share also goes to v_groundBounce, so a cloud's shadow can dim it (GpuCloudShadow.fragment).
     */
    static String linearVertex(String source) {
        source = GpuGlsl.libGdx(source, true);
        source = replaceOnce(source, "#if defined(diffuseTextureFlag) || defined(specularTextureFlag) || defined(emissiveTextureFlag)",
              "#if defined(diffuseTextureFlag) || defined(specularTextureFlag) || defined(emissiveTextureFlag) || defined(modelSurfaceFlag)",
              "vertex");
        source = replaceOnce(source, MAIN,
              "#ifdef modelSurfaceFlag\nout vec2 v_modelUV;\n#endif\n" + MAIN
                    + "\n#ifdef modelSurfaceFlag\nv_modelUV = a_texCoord0;\n#endif\n", "vertex");
        source = replaceOnce(source, MAIN, lightModel() + "\nout vec3 v_groundBounce;\n" + MAIN, "vertex");
        // A recolourable model's shared mesh takes its placement's colours before any lighting (RigidGlb.recolour).
        source = replaceOnce(source, MAIN, GpuShaderSource.read("model-colour-slots.glsl") + "\n" + MAIN, "vertex");
        String color = "v_color = a_color;";
        source = replaceOnce(source, color, color
              + "\n#ifdef colourSlotsFlag\nv_color.rgb = slotColour(a_color.rgb, a_colourSlot, u_slotColours);\n#endif\n", "vertex");
        String ambient = "#endif // sphericalHarmonicsFlag";
        return replaceOnce(source, ambient, ambient + "\n" + GpuShaderSource.read("linear-ambient.glsl") + "\n", "vertex");
    }

    /**
     * libGDX's lit fragment on the one light model: linear albedo times linear light, plus decoded emission, then
     * one display encode. This matches the terrain's radiance sum. Unlit draws are untouched.
     */
    static String linearFragment(String source) {
        source = GpuGlsl.libGdx(source, false);
        source = replaceOnce(source, MAIN, "// model-lighting-functions\n" + lightModel()
              + GpuShaderSource.read("lava-lighting.glsl") + GpuShaderSource.read("model-surface.glsl") + "\n" + MAIN, "fragment");
        String lit = "#if (!defined(lightingFlag))";
        source = replaceOnce(source, lit, GpuShaderSource.read("linear-material.glsl")
              + "\n#if defined(modelSurfaceFlag) && defined(lightingFlag)\n"
              + "fragColor.rgb = modelSurface(diffuse.rgb, emissive.rgb);\n#elif (!defined(lightingFlag))", "fragment");
        String fog = "#endif // end fogFlag";
        return replaceOnce(source, fog, fog + "\n" + GpuShaderSource.read("linear-output.glsl") + "\n", "fragment");
    }

    private static String lightModel() {
        return GpuShaderSource.read("light-model.glsl");
    }

    static String replaceOnce(String source, String anchor, String replacement, String stage) {
        int index = source.indexOf(anchor);
        if (index < 0 || source.indexOf(anchor, index + anchor.length()) >= 0) {
            throw new IllegalStateException("Incompatible libGDX unit " + stage
                  + " shader: expected exactly one insertion point: " + anchor);
        }
        return source.substring(0, index) + replacement + source.substring(index + anchor.length());
    }

    @Override
    public void render(Renderable part, Attributes attributes) {
        var paint = attributes.get(GpuUnitCamouflage.Paint.class, GpuUnitCamouflage.Paint.TYPE);
        var damage = attributes.get(UnitDamageDisplay.Overlay.class, UnitDamageDisplay.Overlay.TYPE);
        set(damageEnabled, damage == null ? 0f
              : part.material.id.endsWith(UnitDamageDisplay.WRECKED_SUFFIX) ? 2f : 1f);
        if (damage != null) {
            set(damageOpacity, damage.opacity);
            set(damageTexture, context.textureBinder.bind(damage.texture));
            set(damageTransform, damage.cos, damage.sin, damage.offsetU, damage.offsetV);
        }
        set(rotation, paint == null ? 1 : paint.cos, paint == null ? 0 : paint.sin);
        var diffuse = attributes.get(TextureAttribute.class, TextureAttribute.Diffuse);
        boolean camo = paint != null && diffuse != null;
        set(camoEnabled, camo ? 1f : 0f);
        if (paint != null) {
            set(paintTransform, paint.transform);
            set(paintNormalMatrix, paint.normalMatrix);
        }
        if (camo) {
            var texture = diffuse.textureDescription.texture;
            set(camoImageSize, (float) texture.getWidth(), (float) texture.getHeight());
        }
        boolean marker = paint != null && paint.marker != null;
        set(markerEnabled, marker ? 1f : 0f);
        if (marker) {
            set(markerTexture, context.textureBinder.bind(paint.marker));
        }
        super.render(part, attributes);
    }
}
