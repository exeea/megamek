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
uniform float u_biomeKind;
uniform float u_biomeLod;
#endif
#endif

void main() {
#if defined(biomeVegetationFlag) && defined(diffuseTextureFlag)
    // Sample before any coverage discard: neighbouring fragments must agree on derivatives during an LOD fade.
    vec4 plant;
    vec2 dx = dFdx(v_diffuseUV), dy = dFdy(v_diffuseUV);
    if (u_biomeKind < 1.5 && u_biomeLod > 1.5) {
        // A distant canopy run repeats its strip artwork along the furrow; wrap after interpolation, filtering by the
        // unwrapped derivatives so the repeat seams do not select a coarse mip level. The branch is uniform per draw.
        // The distant texture stacks the rows' head band over their crowns; the template addresses the near atlas's
        // overhead band, v .8 to 1.
        float u = v_diffuseUV.x - max(0.0, ceil(v_diffuseUV.x) - 1.0);
        float across = clamp((v_diffuseUV.y - .8) * 5.0, 0.0, 1.0) * .5;
        dx.y *= 2.5; dy.y *= 2.5;
        plant = textureGrad(u_diffuseTexture, vec2(u, .5 + across), dx, dy);
        // Seen at an angle, near rows show the grain heads along their sides more than their crowns. Colour the canopy
        // from the head band in that proportion: the golden heads are what identify a cultivated field.
        float incline = viewDirection().z;
        plant.rgb = mix(plant.rgb, textureGrad(u_diffuseTexture, vec2(u, across), dx, dy).rgb,
              sqrt(max(0.0, 1.0 - incline * incline)));
        // The canopy stands in for a whole row of plants. Box-filtered mipmaps would thin its alpha below the cut-off
        // at distance and let the ground show through, so restore its coverage with the mip level.
        vec2 texels = vec2(textureSize(u_diffuseTexture, 0));
        vec2 fx = dx * texels, fy = dy * texels;
        plant.a *= 1.0 + .25 * max(0.0, .5 * log2(max(dot(fx, fx), dot(fy, fy))));
    } else {
        plant = texture(u_diffuseTexture, v_diffuseUV);
    }
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
    // Rain leaves a water film on the leaves: sunlit glints appear with the surface wetness. Dry plants stay matte and
    // skip the specular term.
    surfaceLighting(normal, .6 * u_wetness, ambient, direct, sheen);
    // Thin leaves transmit a little back light, while still receiving the world's shadows and cloud cover.
    ambient += skyLight(-normal, GROUND_ALBEDO) * .15;
    albedo *= ambient + direct;
    albedo += sheen * u_wetness;
    albedo = toDisplay(albedo);
#endif
    fragColor = vec4(albedo * terrainGrid(v_coverRoot * .2), 1.0);
}
