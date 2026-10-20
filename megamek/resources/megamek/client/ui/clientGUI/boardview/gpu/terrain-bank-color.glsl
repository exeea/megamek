// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The atlas supplies blade detail; the installed terrain supplies pigment.
#ifndef TERRAIN_LAYERS_DECLARED
#define TERRAIN_LAYERS_DECLARED
uniform sampler2DArray u_terrainLayers;
#endif
uniform vec2 u_turfMaterials[SURFACE_FAMILIES]; // ground color layer, metres per repeat, in BoardScene.Surface order
uniform float u_metre, u_levelHeight;
in vec4 v_turfWeights;
in vec4 v_turfOthers;
in vec4 v_turfArid;

vec3 turfColor(vec3 artwork, mat2 gradient, out float roughness) {
    vec3 world = v_cloudPosition / u_metre;
    vec3 fields = groundFields(u_rainNoise, world);
    vec2 p = vec2(world.x, -world.y);
    vec3 pigment = vec3(0.0);
    float total = 0.0;
    roughness = 0.0;
    for (int family = 0; family < SURFACE_FAMILIES; family++) {
        float weight = family < 4 ? v_turfWeights[family] : family < 8 ? v_turfOthers[family - 4] : v_turfArid[family - 8];
        if (weight <= .0001) continue;
        vec2 material = u_turfMaterials[family];
        // Same two world-space projections as the ground; a little filtering leaves fine blade detail to the atlas.
        vec2 nearUv = p / material.y, farUv = TURN * p / (material.y * 2.37) + .31;
        mat2 nearGradient = gradient * (2.0 / material.y);
        mat2 farGradient = TURN * gradient * (2.0 / (material.y * 2.37));
        vec3 base = mix(textureGrad(u_terrainLayers, vec3(nearUv, material.x * .5), nearGradient[0], nearGradient[1]).rgb,
              textureGrad(u_terrainLayers, vec3(farUv, material.x * .5), farGradient[0], farGradient[1]).rgb, fields.x);
        roughness += mix(authoredRoughness(material.x, nearUv, nearGradient),
              authoredRoughness(material.x, farUv, farGradient), fields.x) * weight;
        base = groundToneFor(float(family), base, world, fields.x, fields.y, fields.z, 0.0, 0.0);
        pigment += base * weight;
        total += weight;
    }
    pigment /= max(total, .0001);
    roughness /= max(total, .0001);
    // The generated atlas has pale blade highlights. Use these as bounded cavity/detail, never as a second
    // light source or another brightening pass on the terrain pigment.
    float detail = mix(.62, 1.08,
          smoothstep(toLinear(vec3(.14)).r, toLinear(vec3(.65)).r, dot(artwork, vec3(.2126, .7152, .0722))));
    return pigment * detail;
}
