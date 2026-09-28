#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec3 v_normal;
in vec4 v_color;
in vec2 v_coverData;
in vec2 v_coverRoot;
#ifdef biomeVegetationFlag
in vec2 v_coverFade;
#ifdef diffuseTextureFlag
in vec2 v_diffuseUV;
uniform sampler2D u_diffuseTexture;
#endif
#endif

void main() {
#if defined(biomeVegetationFlag) && defined(diffuseTextureFlag)
    // Sample before any coverage discard: neighbouring fragments must agree on derivatives during an LOD fade.
    vec4 plant = texture(u_diffuseTexture, v_diffuseUV);
#endif
#ifdef biomeVegetationFlag
    // Complementary coverage keeps overlapping LODs opaque without doubling leaves or changing depth ownership.
    float coverage = fract(52.9829189 * fract(dot(floor(gl_FragCoord.xy), vec2(.06711056, .00583715))));
    if (coverage < v_coverFade.x || coverage >= v_coverFade.y) discard;
#endif
    // An upward-biased normal approximates the many sunlit leaves in a tuft without black card-like speckles.
    vec3 leaf = normalize(v_normal) * (gl_FrontFacing ? 1.0 : -1.0);
    vec3 normal = normalize(mix(vec3(0.0, 0.0, 1.0), leaf, .45));
    vec3 albedo = v_color.rgb * (1.0 - u_wetness * .16);
#if defined(biomeVegetationFlag) && defined(diffuseTextureFlag)
    // Opaque depth ownership avoids ordering halos between intersecting clumps; mipmaps filter distant leaves.
    if (plant.a < .32) discard;
    albedo *= plant.rgb;
#endif
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
    fragColor = vec4(albedo * terrainGrid(v_coverRoot * .2), 1.0);
}
