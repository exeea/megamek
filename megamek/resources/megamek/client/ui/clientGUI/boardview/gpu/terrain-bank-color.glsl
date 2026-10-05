// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The atlas supplies blade detail; the installed terrain supplies all pigment and elevation grading.
uniform sampler2DArray u_terrainLayers;
uniform vec2 u_turfMaterials[SURFACE_FAMILIES]; // ground color layer, metres per repeat, in BoardScene.Surface order
uniform float u_metre, u_levelHeight;
in vec4 v_turfWeights;
in vec4 v_turfOthers;
in vec4 v_turfArid;

vec3 turfColor(vec3 artwork) {
    vec3 world = v_cloudPosition / u_metre;
    vec3 fields = groundFields(u_rainNoise, world);
    vec2 p = vec2(world.x, -world.y);
    vec3 pigment = vec3(0.0);
    float total = 0.0;
    for (int family = 0; family < SURFACE_FAMILIES; family++) {
        float weight = family < 4 ? v_turfWeights[family] : family < 8 ? v_turfOthers[family - 4] : v_turfArid[family - 8];
        if (weight <= .0001) continue;
        vec2 material = u_turfMaterials[family];
        // Same two world-space projections as the ground; a little filtering leaves fine blade detail to the atlas.
        vec3 base = mix(texture(u_terrainLayers, vec3(p / material.y, material.x), 1.0).rgb,
              texture(u_terrainLayers, vec3(TURN * p / (material.y * 2.37) + .31, material.x), 1.0).rgb, fields.x);
        base = groundToneFor(float(family), base, world, fields.x, fields.y, fields.z, 0.0, 0.0);
        pigment += toLinear(base) * weight;
        total += weight;
    }
    pigment = levelGrade(toDisplay(pigment / max(total, .0001)), v_cloudPosition.z / u_levelHeight);
    // The generated atlas has pale blade highlights. Use these as bounded cavity/detail, never as a second
    // light source or another brightening pass on the already elevation-graded terrain pigment.
    float detail = mix(.62, 1.08, smoothstep(.14, .65, dot(artwork, vec3(.299, .587, .114))));
    return pigment * detail;
}
