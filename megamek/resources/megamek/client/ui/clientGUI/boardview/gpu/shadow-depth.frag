#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shadow-map depth only; camera depth must retain the actual surface position.

#if defined(diffuseTextureFlag) && defined(blendedFlag)
in vec2 v_texCoords0;
uniform sampler2D u_diffuseTexture;
uniform float u_alphaTest;
#endif

#ifdef PackedDepthFlag
in float v_depth;
// SHADOW_MAP_TEXELS: GpuTerrain's shadow pass defines MAP_TEXELS, the map's texels across.
#ifdef MAP_TEXELS
// The pass's view-projection (libGDX's camera uniform).
uniform mat4 u_projViewTrans;
#endif
#endif

void main() {
#ifdef PackedDepthFlag
    // Measured in shadow texels, this follows the actual face slope along the light, including vertical cliffs.
    // Half-texel PCF offsets plus bilinear comparisons reach at most 1.5 texels in each axis.
    // Evaluate derivatives before alpha discard so cutout edges retain valid neighbor values.
    // A depth that measures height across the casters' slab (GpuShadowLight.SlabCamera) also changes from texel to
    // texel at one distance from the sun (drift, per texel; surface-lighting.glsl's shadowDrift per map width), which
    // the receivers follow. Along each axis the bias covers the farthest texel a comparison reads, 1.5 texels out: 1.5
    // texels of the slope without the drift (bent), the bias depth along the light gives (a face grazed by the light),
    // or, where the drift adds to that slope (a face turned to the sun), the slope with it across a bilinear
    // comparison's texels, which share one reference, plus half a texel of bent. Depth along the view does not drift.
    vec2 drift = vec2(0.0);
#ifdef MAP_TEXELS
    if (u_projViewTrans[0][3] == 0.0 && u_projViewTrans[1][3] == 0.0 && u_projViewTrans[2][3] == 0.0) {
        vec3 across = vec3(u_projViewTrans[0][0], u_projViewTrans[1][0], u_projViewTrans[2][0]);
        vec3 up = vec3(u_projViewTrans[0][1], u_projViewTrans[1][1], u_projViewTrans[2][1]);
        vec3 row = vec3(u_projViewTrans[0][2], u_projViewTrans[1][2], u_projViewTrans[2][2]);
        drift = vec2(dot(row, across) / dot(across, across), dot(row, up) / dot(up, up)) / MAP_TEXELS;
    }
#endif
    vec2 change = vec2(dFdx(v_depth), dFdy(v_depth));
    vec2 bent = abs(change - drift);
    vec2 axes = max(abs(change) + .5 * bent, 1.5 * bent);
    float bias = axes.x + axes.y;
    float depth = min(1.0, v_depth + bias + 0.000001);
#endif
#if defined(diffuseTextureFlag) && defined(blendedFlag)
    if (texture(u_diffuseTexture, v_texCoords0).a < u_alphaTest) discard;
#endif
#ifdef PackedDepthFlag
    // PackedDepthFlag is libGDX's vertex-depth selector; the framebuffer stores only D24 now.
    gl_FragDepth = depth;
#endif
}
