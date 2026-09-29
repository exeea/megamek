#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Roads, loose verges, bridge decks and tunnel floors.
// DefaultShader owns the uniforms, material binding and directional shadow map.

in vec2 v_diffuseUV;
in vec3 v_normal;
in vec4 v_color;
uniform sampler2D u_diffuseTexture;
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
uniform float u_normalMaps;
#endif
uniform float u_groundResponse;
#ifdef roadMaskFlag
uniform sampler2D u_roadMask;
uniform vec4 u_roadMaskRegion;
in vec2 v_roadMaskUV;
#endif
#ifdef bridgeDeckFlag
uniform float u_worldMetre;
#endif
#ifdef roadMapsFlag
uniform sampler2D u_roadSurface;
uniform float u_roadTransition;
uniform vec4 u_roadProfile;
#endif

// ground-surface-functions

void main() {
    vec4 roadColor = v_color;
#ifdef roadMaskFlag
    vec2 maskUV = v_roadMaskUV;
    if (any(lessThan(maskUV, vec2(0.0))) || any(greaterThan(maskUV, vec2(1.0)))) { discard; }
    roadColor = texture(u_roadMask, u_roadMaskRegion.xy + maskUV * u_roadMaskRegion.zw);
    if (roadColor.a < .002) { discard; }
#endif
    vec2 uv = v_diffuseUV;
#ifdef bridgeDeckFlag
    // A rotated bridge shares the adjacent road's scale, orientation and texture phase.
    uv = vec2(v_cloudPosition.x, -v_cloudPosition.y) / u_worldMetre;
#endif
    vec3 tint = roadColor.rgb;
#ifdef roadMapsFlag
    if (u_roadTransition > .5 || u_roadTransition < -1.5) { tint = vec3(1.0); }
#endif
    vec3 albedo = texture(u_diffuseTexture, uv).rgb * tint;
#ifdef roadMapsFlag
    vec4 properties = texture(u_roadSurface, uv);
    float roadCoverage = 1.0;
    bool wheelWear = u_roadTransition < -1.5 && u_roadTransition > -2.5;
    // Reuse the shared, stable noise field. These masks describe use and loose material, not road width.
    float wearPatches = 0.0, loosePatches = 0.0, compaction = 0.0;
    if (abs(u_roadTransition) > .5) {
        wearPatches = texture(u_rainNoise, uv / 18.0).g;
        loosePatches = texture(u_rainNoise, uv / 5.0 + .37).b;
        albedo *= mix(.95, 1.05, wearPatches);
        if (wheelWear) {
            vec2 heading = normalize((roadColor.rg * 255.0 - 128.0) / 127.0);
            vec2 scuff = vec2(dot(uv, heading), dot(uv, vec2(-heading.y, heading.x)))
                  * vec2(1.0 / 36.0, 1.0 / 3.0);
            // Averaging both directions gives neighbouring hexes identical wear even when their paths run
            // opposite ways. The pair's geometry fixes axle spacing; this only varies compaction within it.
            loosePatches = .5 * (texture(u_rainNoise, scuff).b + texture(u_rainNoise, -scuff).b);
            vec2 broken = scuff * vec2(3.5, 2.2);
            float clods = .5 * (texture(u_rainNoise, broken).g + texture(u_rainNoise, -broken).g);
            compaction = smoothstep(.28, .68, loosePatches) * smoothstep(.18, .6, wearPatches);
            // Repeated vehicle passes leave intermittent compaction, sometimes dusty, sometimes darker.
            // Broad feathered swaths replace the old continuously dark, narrow 'rails'.
            albedo *= mix(.77, 1.32, loosePatches) * mix(.88, 1.12, clods);
            properties.g = mix(properties.g, .76, compaction);
            wearPatches *= mix(.4, 1.6, clods);
        }
    }
    if (u_roadTransition > .5) {
        float along = (roadColor.r - .5) * 2.0 * u_roadProfile.x;
        float across = abs((roadColor.g - .5) * 2.0 * u_roadProfile.x);
        float rut = 1.0 - smoothstep(u_roadProfile.z * .5, u_roadProfile.z, abs(across - u_roadProfile.y));
        float grain = properties.r;
        float broad = texture(u_roadSurface, uv * .21).r;
        // A full-width construction edge, chipped only at a small scale.
        float seam = along + (broad - .5) * .8 + (grain - .5) * .6;
        float body = smoothstep(-.7, .5, seam);
        float carry = 1.0 - smoothstep(0.0, u_roadProfile.x, -along);
        float dust = (.10 + .15 * broad) * carry * carry;
        if (u_roadTransition > 1.5) {
            // Soil dragged by tyres continues onto asphalt at the same axle spacing; no central blotch.
            float tracked = rut * (.4 + .6 * grain) * pow(carry, 1.3);
            roadCoverage = max(body, dust + .8 * tracked);
            albedo *= mix(1.0, u_roadProfile.w, rut * body);
        } else {
            // Crushed aggregate leaves discrete grains as well as a little mineral dust.
            float stones = smoothstep(.78 - .3 * carry, .90 - .3 * carry, grain) * carry;
            roadCoverage = max(body, dust + stones);
        }
        roadCoverage = clamp(roadCoverage, 0.0, 1.0);
    }
#endif
    float normalStrength = 1.0;
#ifdef roadMapsFlag
    if (wheelWear) { normalStrength = mix(1.2, .35, compaction); }
#endif
    vec3 normal = groundNormal(uv, normalStrength);
    // Only exposed, upward-facing ground receives liquid water. No accumulation or terrain-rule changes.
    float wet = u_wetness * step(0.0, u_groundResponse) * smoothstep(0.2, 0.8, v_normal.z);
    float response = max(0.0, u_groundResponse);
    albedo *= 1.0 - wet * mix(0.175, 0.10, response); // This is the darkening of the terrain (the mix(min, max, ...))
#ifdef roadSoilFlag
    // Compact soil holds patchy mud and shallow water; the basins stay put as rainfall changes.
    float soilBasins = texture(u_rainNoise, uv / 22.0).r * .7
          + texture(u_rainNoise, uv / 7.0 + .37).g * .3;
    float mud = wet * smoothstep(.24, .68, soilBasins);
    albedo *= 1.0 - .30 * mud;
    properties.g = mix(properties.g, .55, mud);
#endif
    float puddle = groundPuddle(wet, response);
    if (wet * u_rainDetail > 0.0) {
#ifdef roadMapsFlag
        // Microscopic depressions wet first, without moving the road or its supporting terrain.
        puddle *= mix(1.0, 1.0 - smoothstep(.18, .78, properties.r), .35);
#ifdef roadSoilFlag
        float basinWater = smoothstep(mix(.86, .51, wet), mix(.92, .59, wet), soilBasins);
        puddle = max(puddle, basinWater * wet * smoothstep(.98, .999, v_normal.z) * u_rainDetail);
#endif
#endif
    }
    groundFilm(albedo, normal, puddle);
#ifdef roadMapsFlag
    albedo = groundLighting(albedo, normal, wet, response, puddle, properties.g, mix(1.0, properties.b, .7));
#else
    albedo = groundLighting(albedo, normal, wet, response, puddle, -1.0, 1.0);
#endif
#ifdef roadFlag
    // The road's outer strip carries coverage. World-anchored grain breaks up its dusty verge.
#ifdef roadMapsFlag
    float grain = properties.r;
#else
    float grain = texture(u_diffuseTexture, uv * 2.3).r;
#endif
    float alpha = clamp(roadColor.a + (grain - .5) * min(roadColor.a, 1.0 - roadColor.a) * .7, 0.0, 1.0);
#ifdef roadMapsFlag
    if (u_roadTransition < -2.5) {
        // Grounded bridge landings: individual asphalt chips/aggregate expose soil and grass. The broad
        // envelope is in the mask; material grain supplies the small fractured edge at every orientation.
        float chips = texture(u_roadSurface, uv * .63 + .19).r;
        float fragments = clamp((.56 * grain + .28 * chips + .16 * loosePatches - .2) / .6, 0.0, 1.0);
        float filterWidth = max(.025, fwidth(fragments));
        float broken = smoothstep(fragments - filterWidth, fragments + filterWidth, roadColor.a);
        broken *= smoothstep(0.0, .025, roadColor.a);
        float dust = u_roadTransition < -3.5 ? .16 : .025;
        alpha = mix(broken, roadColor.a, dust);
    } else if (wheelWear) {
        alpha *= smoothstep(.16, .62, wearPatches) * mix(.35, 1.0, loosePatches);
    } else if (abs(u_roadTransition) > .5) {
        // The wide loose margin has no opaque shoulder underneath it. Clumps give way to isolated grains
        // and exposed ground; the compacted core remains continuous and at the authored road width.
        float clumps = texture(u_rainNoise, uv / 32.0 + .19).r;
        float threshold = .05 + .8 * clumps + .12 * loosePatches;
        float broken = smoothstep(threshold - .12, threshold + .12, roadColor.a + (grain - .5) * .32);
        alpha = mix(roadColor.a, broken, 4.0 * roadColor.a * (1.0 - roadColor.a));
        alpha *= roadCoverage;
    }
#endif
    fragColor = vec4(albedo, alpha);
#else
    fragColor = vec4(albedo, 1.0);
#endif
}
