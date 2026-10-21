// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Reference-authored lichen/tussocks, fungal colonies and barren mineral crust in the shared texture array.
// Preserve actual image pigments and internal detail instead of thresholding them into flat coloured spots.
uniform vec4 u_tundraLayers, u_tundraTiles; // earth, fungus, mineral, earth variant; normal/AO at colour layer + 1

float tundraVariant(vec2 world) {
    // Both sources contain the same tundra mixture; local variation breaks repetition, not terrain identity.
    return smoothstep(.46, .54, biomeNoise(world / 16.0 + vec2(17.0, 43.0)));
}

vec3 tundraFamily(float f) {
    if (abs(f - FUNGUS_FAMILY) < .5) return vec3(0.0, 1.0, 0.0);
    if (abs(f - MARS_FAMILY) < .5 || abs(f - LUNAR_FAMILY) < .5 || abs(f - VOLCANO_FAMILY) < .5) {
        return vec3(0.0, 0.0, 1.0);
    }
    return vec3(1.0, 0.0, 0.0);
}

// Tundra mats: the top projection's two translated reads. Volcanic programs, whose natural material draws every face,
// add the steep sides with one read per projection.
vec4 tundraTexel(sampler2DArray maps, float layer, float tile, MaterialProjection p, float variation) {
    vec4 top = translatedTexel(maps, layer, p.top / tile, p.topGradient / tile, variation);
#ifdef volcanicFlag
    if (p.lying < 1.0) {
        vec4 steep = mix(materialTexel(maps, layer, p.y / tile + .37, p.yGradient / tile),
              materialTexel(maps, layer, p.x / tile, p.xGradient / tile), p.side);
        top = mix(steep, top, p.lying);
    }
#endif
    return top;
}

// A mat's authored finish from its colour's reads. Roughness, metallic and scattering fade out with the normal detail,
// to the map's means; emission keeps its map at every distance.
Finish tundraFinish(float layer, float tile, MaterialProjection p, float variation) {
    int material = int(layer * .5);
    vec4 surface = u_finishSurface[material], emission = u_finishEmission[material];
    Finish texel = meanFinish(material);
    if (surface.a >= 0.0 && terrainNormalDetail > 0.0) {
        vec3 mapped = tundraTexel(u_surfaceLayers, surface.a, tile, p, variation).rgb;
        texel = finishOf(mix(surface.rgb, mapped, terrainNormalDetail), texel.emission);
    }
    if (emission.a >= 0.0) {
        texel.emission += emission.rgb * tundraTexel(u_emissionLayers, emission.a, tile, p, variation).rgb;
    }
    return texel;
}

void tundraSurface(vec3 world, vec3 face, float amount, inout vec3 color, inout vec3 normal,
      inout float cavity, inout Finish finish, inout float grass, inout vec3 bounce) {
    MaterialProjection projection = materialProjection(world, face);
    amount *= smoothstep(.08, .65, face.z);
    if (amount < .0001) return;
    vec4 kinds = vec4(tundraFamily(sculptFamily), 0.0);
    float meadow = family(0.0) || family(TROPICAL_FAMILY) ? 1.0 : 0.0;
    float snow = family(5.0) ? 1.0 : 0.0;
    float concrete = family(4.0) ? 1.0 : 0.0;
#ifdef terrainBlendFlag
    vec4 weights = max(v_coverWeights, vec4(0.0));
    weights /= max(dot(weights, vec4(1.0)), .0001);
    kinds = vec4(0.0); meadow = 0.0; snow = 0.0; concrete = 0.0;
    for (int i = 0; i < 4; i++) {
        float f = coverFamilies[i];
        kinds.xyz += tundraFamily(f) * weights[i];
        meadow += (abs(f) < .5 || abs(f - TROPICAL_FAMILY) < .5 ? 1.0 : 0.0) * weights[i];
        snow += (abs(f - 5.0) < .5 ? 1.0 : 0.0) * weights[i];
        concrete += (abs(f - 4.0) < .5 ? 1.0 : 0.0) * weights[i];
    }
#endif
    if (kinds.x > .0001) {
        kinds.w = kinds.x * tundraVariant(world.xy);
        kinds.x -= kinds.w;
    }
    // Keep source colonies intact over broad regions; colour and relief use the same slow translation field.
    float variation = biomeNoise(world.xy / 80.0 + 29.0);
    vec3 treated = vec3(0.0), mappedNormal = vec3(0.0);
    float mappedCavity = 0.0;
    Finish mappedFinish = Finish(0.0, 0.0, 0.0, vec3(0.0));
    float concreteLichen = 0.0;
    for (int i = 0; i < 4; i++) {
        if (kinds[i] < .0001) continue;
        vec4 source = tundraTexel(u_terrainLayers, u_tundraLayers[i] * .5, u_tundraTiles[i], projection, variation);
        float light = dot(source.rgb, vec3(.2126, .7152, .0722));
        vec3 pigment = source.rgb;
        if (i == 0 || i == 3) {
            // Keep a little local geology, without turning fine pigment into another patch coverage mask.
            float cover = max(meadow + snow, .88);
            pigment = mix(color, source.rgb, clamp(cover, 0.0, 1.0));
            // Retain the source's pale lichen branches, leaving concrete visible through the warm soil/tussocks.
            // Relative linear chroma separates pale lichen from warm soil without encoding the sampled pigment.
            float warmth = (source.r - source.b) / max(source.r + source.b, .001);
            float lichen = smoothstep(toLinear(vec3(.30)).r, toLinear(vec3(.48)).r, light)
                  * (1.0 - smoothstep(.25, .55, warmth));
            concreteLichen += lichen * kinds[i];
            pigment = mix(pigment, source.rgb * toLinear(vec3(.72, .82, .64)), concrete);
        } else if (i == 1) {
            // Fungal mats retain their authored lilac/cyan/copper detail and the local plum ground.
            pigment = mix(color, source.rgb, .88);
        } else {
            float localLight = max(dot(color, vec3(.2126, .7152, .0722)), toLinear(vec3(.15)).r);
            vec3 mineralTint = clamp(color / localLight, vec3(.65), vec3(1.35));
            pigment = mix(color, source.rgb * mineralTint * 1.12, .75);
        }
        treated += pigment * kinds[i];
        mappedFinish = addFinish(mappedFinish,
              tundraFinish(u_tundraLayers[i], u_tundraTiles[i], projection, variation), kinds[i]);
        if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
            vec4 n = tundraTexel(u_terrainData, u_tundraLayers[i] * .5, u_tundraTiles[i], projection, variation);
            vec3 relief = upNormal(mix(n.rgb * 2.0 - 1.0, vec3(0.0, 0.0, 1.0), farDetail), face);
#ifdef volcanicFlag
            if (projection.lying < 1.0) {
                relief = normalize(mix(wallNormal(n, n, face, projection.side), relief, projection.lying));
            }
#endif
            mappedNormal += normalize(mix(face, relief, terrainNormalDetail)) * kinds[i];
            mappedCavity += mix(TERRAIN_DISTANT_CAVITY, mix(.55, 1.0, n.a), terrainNormalDetail) * kinds[i];
        }
    }
    amount *= mix(1.0, concreteLichen / max(kinds.x + kinds.w, .0001), concrete);
    // The Snow theme retains quiet snow remnants, free of lichen speckling. Explicit SNOW wins at capture.
    float remnant = smoothstep(.55, .72, biomeNoise(world.xy / 8.0 + 29.0)) * snow;
    amount *= 1.0 - remnant;
    color = mix(color, treated, amount);
    finish = mixFinish(finish, mappedFinish, amount);
    if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
        normal = normalize(mix(normal, normalize(mappedNormal), amount));
        cavity = mix(cavity, mappedCavity, amount);
    }
    grass *= 1.0 - amount;
    bounce = mix(bounce, treated * .25, amount);
}
