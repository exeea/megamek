#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Imported models: units, props, buildings and their liquids. libGDX's vertex stage and material attributes supply the
// inputs; lit draws take the board's one light model (model-surface.glsl, shadeSurface), unlit draws their material
// colour. Scene radiance stays linear; GpuSceneRadiance stores it.
#ifdef normalFlag
in vec3 v_normal;
#endif
#ifdef colorFlag
in vec4 v_color;
#endif
#ifdef blendedFlag
in float v_opacity;
#ifdef alphaTestFlag
in float v_alphaTest;
#endif
#endif
#ifdef diffuseTextureFlag
in vec2 v_diffuseUV;
uniform sampler2D u_diffuseTexture;
#endif
#ifdef diffuseColorFlag
uniform vec4 u_diffuseColor;
#endif
#ifdef emissiveTextureFlag
in vec2 v_emissiveUV;
uniform sampler2D u_emissiveTexture;
#endif
#ifdef emissiveColorFlag
uniform vec4 u_emissiveColor;
#endif
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
#endif

void main() {
#ifdef lightingFlag
    // The shading frame takes its derivatives before any scar or alpha discard.
    vec3 modelView = -viewDirection();
    vec3 modelFace;
    vec3 modelShading = modelNormal(modelView, modelFace);
#endif
    // surface-frame-ready
#if defined(diffuseTextureFlag) && defined(diffuseColorFlag) && defined(colorFlag)
    vec4 diffuse = texture(u_diffuseTexture, v_diffuseUV) * u_diffuseColor * v_color;
#elif defined(diffuseTextureFlag) && defined(diffuseColorFlag)
    vec4 diffuse = texture(u_diffuseTexture, v_diffuseUV) * u_diffuseColor;
#elif defined(diffuseTextureFlag) && defined(colorFlag)
    vec4 diffuse = texture(u_diffuseTexture, v_diffuseUV) * v_color;
#elif defined(diffuseTextureFlag)
    vec4 diffuse = texture(u_diffuseTexture, v_diffuseUV);
#elif defined(diffuseColorFlag) && defined(colorFlag)
    vec4 diffuse = u_diffuseColor * v_color;
#elif defined(diffuseColorFlag)
    vec4 diffuse = u_diffuseColor;
#elif defined(colorFlag)
    vec4 diffuse = v_color;
#else
    vec4 diffuse = vec4(1.0);
#endif
    // unit-overlays
#if defined(emissiveTextureFlag) && defined(emissiveColorFlag)
    vec4 emissive = texture(u_emissiveTexture, v_emissiveUV) * u_emissiveColor;
#elif defined(emissiveTextureFlag)
    vec4 emissive = texture(u_emissiveTexture, v_emissiveUV);
#elif defined(emissiveColorFlag)
    vec4 emissive = u_emissiveColor;
#else
    vec4 emissive = vec4(0.0);
#endif
#ifdef lightingFlag
    fragColor.rgb = modelSurface(diffuse.rgb, emissive.rgb, modelShading, modelFace, modelView);
#else
    fragColor.rgb = diffuse.rgb + emissive.rgb;
#endif
#ifdef blendedFlag
    fragColor.a = diffuse.a * v_opacity;
#ifdef alphaTestFlag
    if (fragColor.a <= v_alphaTest) discard;
#endif
#else
    fragColor.a = 1.0;
#endif
    // Tactical draws after the scene composite may borrow a scene color texture or an imported material. Their samples
    // and factors are linear too, but their destination is already display encoded.
#if defined(displayOutputFlag) || (!defined(lightingFlag) && !defined(sceneUnlitFlag) && (defined(linearTextureFlag) || defined(linearColorsFlag)))
    fragColor.rgb = toDisplay(fragColor.rgb);
#endif
}
