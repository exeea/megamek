// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Reference-authored lichen/tussocks, fungal colonies and barren mineral crust in the shared texture array.
// Preserve actual image pigments and internal detail instead of thresholding them into flat coloured spots.
uniform vec3 u_tundraLayers, u_tundraTiles; // earth, fungus, mineral; normal/AO at colour layer + 1

vec3 tundraFamily(float f) {
    if (abs(f - FUNGUS_FAMILY) < .5) return vec3(0.0, 1.0, 0.0);
    if (abs(f - MARS_FAMILY) < .5 || abs(f - LUNAR_FAMILY) < .5 || abs(f - VOLCANO_FAMILY) < .5) {
        return vec3(0.0, 0.0, 1.0);
    }
    return vec3(1.0, 0.0, 0.0);
}

void tundraSurface(vec3 world, vec3 face, float amount, inout vec3 color, inout vec3 normal,
      inout float cavity, inout float grass, inout vec3 bounce) {
    MaterialProjection projection = materialProjection(world, face);
    amount *= smoothstep(.08, .65, face.z);
    if (amount < .0001) return;
    vec3 kinds = tundraFamily(u_sculptFamily);
    float meadow = family(0.0) || family(TROPICAL_FAMILY) ? 1.0 : 0.0;
    float snow = family(5.0) ? 1.0 : 0.0;
    float concrete = family(4.0) ? 1.0 : 0.0;
#ifdef terrainBlendFlag
    vec4 weights = max(v_coverWeights, vec4(0.0));
    weights /= max(dot(weights, vec4(1.0)), .0001);
    kinds = vec3(0.0); meadow = 0.0; snow = 0.0; concrete = 0.0;
    for (int i = 0; i < 4; i++) {
        float f = u_coverFamilies[i];
        kinds += tundraFamily(f) * weights[i];
        meadow += (abs(f) < .5 || abs(f - TROPICAL_FAMILY) < .5 ? 1.0 : 0.0) * weights[i];
        snow += (abs(f - 5.0) < .5 ? 1.0 : 0.0) * weights[i];
        concrete += (abs(f - 4.0) < .5 ? 1.0 : 0.0) * weights[i];
    }
#endif
    // Keep source colonies intact over broad regions; colour and relief use the same slow translation field.
    float variation = biomeNoise(world.xy / 80.0 + 29.0);
    vec3 treated = vec3(0.0), mappedNormal = vec3(0.0);
    float mappedCavity = 0.0;
    for (int i = 0; i < 3; i++) {
        if (kinds[i] < .0001) continue;
        vec4 source = sampleMaterial(u_tundraLayers[i], u_tundraTiles[i], 1.0, projection,
              world, variation, 0.0, 0.0, false, true, 0.0, vec2(0.0));
        source.rgb = toDisplay(source.rgb);
        float light = dot(source.rgb, vec3(.2126, .7152, .0722));
        vec3 pigment = source.rgb;
        if (i == 0) {
            // Exposed local geology remains between mats on Rock/Dirt/Desert. Meadows replace green turf.
            float cover = max(meadow + snow, mix(.40, .96, smoothstep(.28, .55, light)));
            pigment = mix(color, source.rgb, clamp(cover, 0.0, 1.0));
            // Retain the source's pale lichen branches, leaving concrete visible through the warm soil/tussocks.
            // A soft colour key keeps the authored pigment and relief instead of painting flat noise spots.
            float lichen = smoothstep(.30, .48, light)
                  * (1.0 - smoothstep(.10, .23, source.r - source.b));
            amount *= mix(1.0, lichen, concrete);
            pigment = mix(pigment, source.rgb * vec3(.72, .82, .64), concrete);
        } else if (i == 1) {
            // Fungal mats retain their authored lilac/cyan/copper detail and the local plum ground.
            pigment = mix(color, source.rgb, .88);
        } else {
            float localLight = max(dot(color, vec3(.2126, .7152, .0722)), .15);
            vec3 mineralTint = clamp(color / localLight, vec3(.65), vec3(1.35));
            pigment = mix(color, source.rgb * mineralTint * 1.12, .75);
        }
        treated += pigment * kinds[i];
        if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
            vec4 relief = materialNormal(u_tundraLayers[i] + 1.0, u_tundraTiles[i], 1.0,
                  projection, face, variation, false, true);
            mappedNormal += relief.rgb * kinds[i];
            mappedCavity += relief.a * kinds[i];
        }
    }
    // The Snow theme retains quiet snow remnants, free of lichen speckling. Explicit SNOW wins at capture.
    float remnant = smoothstep(.55, .72, biomeNoise(world.xy / 8.0 + 29.0)) * snow;
    amount *= 1.0 - remnant;
    color = mix(color, treated, amount);
    if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
        normal = normalize(mix(normal, normalize(mappedNormal), amount));
        cavity = mix(cavity, mappedCavity, amount);
    }
    grass *= 1.0 - amount;
    bounce = mix(bounce, treated * .25, amount);
}
