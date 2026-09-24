// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
precision mediump float;
#endif
varying vec3 v_normal;
varying vec4 v_color;
varying vec2 v_coverData;
varying vec2 v_coverRoot;

void main() {
    float coverage = meadowCover(v_coverRoot);
    if (coverage < .12) discard;
    // An upward-biased normal approximates the many sunlit leaves in a tuft without black card-like speckles.
    vec3 leaf = normalize(v_normal) * (gl_FrontFacing ? 1.0 : -1.0);
    vec3 normal = normalize(mix(vec3(0.0, 0.0, 1.0), leaf, .45));
    vec3 albedo = v_color.rgb * (1.0 - u_wetness * .16);
    albedo *= mix(.80, 1.06, v_coverData.y);
#ifdef lightingFlag
    albedo = toLinear(albedo);
    vec3 ambient, direct, sheen;
    surfaceLighting(normal, .05, ambient, direct, sheen);
    // Thin leaves transmit a little back light, while still receiving the world's shadows and cloud cover.
    ambient += skyLight(-normal, GROUND_ALBEDO) * .15;
    albedo *= ambient + direct;
    albedo = toDisplay(albedo);
#endif
    gl_FragColor = vec4(albedo * terrainGrid(v_coverRoot * .2), 1.0);
}
