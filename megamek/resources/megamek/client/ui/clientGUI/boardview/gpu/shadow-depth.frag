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
#endif

void main() {
#ifdef PackedDepthFlag
    // Measured in shadow texels, this follows the actual face slope, including vertical cliffs.
    // Half-texel PCF offsets plus bilinear comparisons reach at most 1.5 texels in each axis.
    // Evaluate derivatives before alpha discard so cutout edges retain valid neighbor values.
    float depth = min(1.0, v_depth + 1.5 * fwidth(v_depth) + 0.000001);
#endif
#if defined(diffuseTextureFlag) && defined(blendedFlag)
    if (texture(u_diffuseTexture, v_texCoords0).a < u_alphaTest) discard;
#endif
#ifdef PackedDepthFlag
    // PackedDepthFlag is libGDX's vertex-depth selector; the framebuffer stores only D24 now.
    gl_FragDepth = depth;
#endif
}
