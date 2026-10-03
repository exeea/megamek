#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Roads, loose verges, bridge decks and tunnel floors.
// DefaultShader owns the uniforms, material binding and directional shadow map.

in vec2 v_diffuseUV;
in vec3 v_normal;
in vec4 v_color;
uniform sampler2D u_diffuseTexture;
#if defined(normalTextureFlag) || defined(roadCoatFlag)
uniform float u_normalMaps;
#endif
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
#endif
uniform float u_groundResponse;
#ifdef roadMaskFlag
uniform sampler2D u_roadMask;
flat in vec4 v_roadMaskRegion;
in vec2 v_roadMaskUV;
#endif
uniform float u_worldMetre;
#ifdef roadMapsFlag
uniform sampler2D u_roadSurface;
uniform float u_roadTransition;
#endif
#if defined(roadMapsFlag) || defined(roadCoatFlag)
uniform vec4 u_roadProfile;
#endif
#ifdef roadCoatFlag
// Every coat of a chunk in one draw. Road maps: colour, normal and surface per material; sculpt maps: colour and
// normal per material. Each coat's own choice, finish and wet response come from its vertices (GpuRoads#coat).
uniform sampler2DArray u_roadMaps;
uniform sampler2DArray u_sculptMaps;
flat in vec4 v_roadCoat;
#endif

// ground-surface-functions
// Those shared functions are inserted just before main, after the helpers below.
vec3 groundNormal(vec2 uv, float strength);
#if defined(normalTextureFlag) || defined(roadCoatFlag)
vec3 mappedNormal(vec4 texel, float strength);
#endif

// A surface's maps and settings: from its material, or for a merged coat from its vertices.
struct RoadMaps {
    int maps;           // a merged coat's road material, or 3 plus its sculpt material
    float transition;   // positive: material joins; negative: loose, wheels, landings (GpuRoads#attribute)
    float response;     // wet response; negative excludes the surface from liquid water
    bool mapped;        // has a surface map: grain, roughness and occlusion
    bool soil;          // compact soil, which holds mud and shallow water
};

RoadMaps roadMaps() {
#ifdef roadCoatFlag
    vec4 coat = floor(v_roadCoat * 255.0 + .5);
    int maps = int(coat.r);
    return RoadMaps(maps, coat.g - 4.0, coat.b / 255.0, maps < 3, maps == 1);
#else
    RoadMaps m = RoadMaps(-1, 0.0, u_groundResponse, false, false);
#ifdef roadMapsFlag
    m.transition = u_roadTransition;
    m.mapped = true;
#endif
#ifdef roadSoilFlag
    m.soil = true;
#endif
    return m;
#endif
}

vec4 roadAlbedo(RoadMaps m, vec2 uv, mat2 gradient) {
#ifdef roadCoatFlag
    return m.mapped ? textureGrad(u_roadMaps, vec3(uv, float(m.maps * 3)), gradient[0], gradient[1])
          : textureGrad(u_sculptMaps, vec3(uv, float((m.maps - 3) * 2)), gradient[0], gradient[1]);
#else
    return textureGrad(u_diffuseTexture, uv, gradient[0], gradient[1]);
#endif
}

// Only a surface with maps has one; see RoadMaps.mapped.
vec4 roadSurface(RoadMaps m, vec2 uv, mat2 gradient) {
#ifdef roadCoatFlag
    return textureGrad(u_roadMaps, vec3(uv, float(m.maps * 3 + 2)), gradient[0], gradient[1]);
#elif defined(roadMapsFlag)
    return textureGrad(u_roadSurface, uv, gradient[0], gradient[1]);
#else
    return vec4(.5);
#endif
}

vec3 roadNormal(RoadMaps m, vec2 uv, mat2 gradient, float strength) {
#ifdef roadCoatFlag
    return mappedNormal(m.mapped ? textureGrad(u_roadMaps, vec3(uv, float(m.maps * 3 + 1)), gradient[0], gradient[1])
          : textureGrad(u_sculptMaps, vec3(uv, float((m.maps - 3) * 2 + 1)), gradient[0], gradient[1]), strength);
#elif defined(normalTextureFlag)
    return mappedNormal(textureGrad(u_normalTexture, uv, gradient[0], gradient[1]), strength);
#else
    return normalize(v_normal);
#endif
}

vec4 roadProfile() {
#if defined(roadMapsFlag) || defined(roadCoatFlag)
    return u_roadProfile;
#else
    return vec4(1.0);
#endif
}

void main() {
    vec2 uv = v_diffuseUV;
#ifdef bridgeDeckFlag
    // A rotated bridge shares the adjacent road's scale, orientation and texture phase.
    uv = vec2(v_cloudPosition.x, -v_cloudPosition.y) / u_worldMetre;
#endif
    mat2 gradient = mat2(dFdx(uv), dFdy(uv));
    vec3 worldDx = dFdx(v_cloudPosition / u_worldMetre), worldDy = dFdy(v_cloudPosition / u_worldMetre);
    RoadMaps m = roadMaps();
    vec4 roadColor = v_color;
#ifdef roadMaskFlag
    vec2 maskUV = v_roadMaskUV;
    if (any(lessThan(maskUV, vec2(0.0))) || any(greaterThan(maskUV, vec2(1.0)))) { discard; }
    roadColor = texture(u_roadMask, v_roadMaskRegion.xy + maskUV * v_roadMaskRegion.zw);
    if (roadColor.a < .002) { discard; }
#endif
    vec3 tint = roadColor.rgb;
    if (m.mapped && (m.transition > .5 || m.transition < -1.5)) { tint = vec3(1.0); }
    vec2 sampleUv = uv;
    vec4 properties = m.mapped ? roadSurface(m, uv, gradient) : vec4(.5);
#if defined(normalTextureFlag) || defined(roadCoatFlag)
    if (m.mapped && u_parallaxMapping > .5) {
        vec3 face = normalize(v_normal);
        vec3 travel = parallaxDirection(-viewDirection(), face) * (properties.a * .1);
        vec2 ray = parallaxProject(travel, worldDx, worldDy, gradient[0], gradient[1]);
        // Coverage/markings stay on their authored geometry. Only aligned material maps follow the hit.
#ifdef roadCoatFlag
        sampleUv = parallaxUv(u_roadMaps, float(m.maps * 3 + 2), vec4(1.0, 0.0, 0.0, 0.0),
              uv, gradient[0], gradient[1], ray);
#elif defined(roadMapsFlag)
        sampleUv = parallaxUv(u_roadSurface, 0.0, vec4(1.0, 0.0, 0.0, 0.0), uv, gradient[0], gradient[1], ray);
#endif
        properties = roadSurface(m, sampleUv, gradient);
        vec2 sunRay = parallaxProject(parallaxDirection(surfaceSunDirection(), face) * (properties.a * .1),
              worldDx, worldDy, gradient[0], gradient[1]);
#ifdef roadCoatFlag
        reliefVisibility = parallaxShadow(u_roadMaps, float(m.maps * 3 + 2), vec4(1.0, 0.0, 0.0, 0.0),
              sampleUv, gradient[0], gradient[1], sunRay, 1.0);
#elif defined(roadMapsFlag)
        reliefVisibility = parallaxShadow(u_roadSurface, 0.0, vec4(1.0, 0.0, 0.0, 0.0),
              sampleUv, gradient[0], gradient[1], sunRay, 1.0);
#endif
    }
#endif
    vec3 albedo = roadAlbedo(m, sampleUv, gradient).rgb * tint;
    float roadCoverage = 1.0;
    bool wheelWear = m.mapped && m.transition < -1.5 && m.transition > -2.5;
    // Reuse the shared, stable noise field. These masks describe use and loose material, not road width.
    float wearPatches = 0.0, loosePatches = 0.0, compaction = 0.0;
    if (m.mapped && abs(m.transition) > .5) {
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
            // Broad feathered swaths replace the old continuously dark, narrow "rails".
            albedo *= mix(.77, 1.32, loosePatches) * mix(.88, 1.12, clods);
            properties.g = mix(properties.g, .76, compaction);
            wearPatches *= mix(.4, 1.6, clods);
        }
    }
    if (m.mapped && m.transition > .5) {
        vec4 profile = roadProfile();
        float along = (roadColor.r - .5) * 2.0 * profile.x;
        float across = abs((roadColor.g - .5) * 2.0 * profile.x);
        float rut = 1.0 - smoothstep(profile.z * .5, profile.z, abs(across - profile.y));
        float grain = properties.r;
        float broad = roadSurface(m, uv * .21, gradient * .21).r;
        // A full-width construction edge, chipped only at a small scale.
        float seam = along + (broad - .5) * .8 + (grain - .5) * .6;
        float body = smoothstep(-.7, .5, seam);
        float carry = 1.0 - smoothstep(0.0, profile.x, -along);
        float dust = (.10 + .15 * broad) * carry * carry;
        if (m.transition > 1.5) {
            // Soil dragged by tyres continues onto asphalt at the same axle spacing; no central blotch.
            float tracked = rut * (.4 + .6 * grain) * pow(carry, 1.3);
            roadCoverage = max(body, dust + .8 * tracked);
            albedo *= mix(1.0, profile.w, rut * body);
        } else {
            // Crushed aggregate leaves discrete grains as well as a little mineral dust.
            float stones = smoothstep(.78 - .3 * carry, .90 - .3 * carry, grain) * carry;
            roadCoverage = max(body, dust + stones);
        }
        roadCoverage = clamp(roadCoverage, 0.0, 1.0);
    }
    float normalStrength = 1.0;
    if (wheelWear) { normalStrength = mix(1.2, .35, compaction); }
    vec3 normal = roadNormal(m, sampleUv, gradient, normalStrength);
    // Only exposed, upward-facing ground receives liquid water. No accumulation or terrain-rule changes.
    float wet = u_wetness * step(0.0, m.response) * smoothstep(0.2, 0.8, v_normal.z);
    float response = max(0.0, m.response);
    albedo *= 1.0 - wet * mix(0.175, 0.10, response); // This is the darkening of the terrain (the mix(min, max, ...))
    float soilBasins = 0.0;
    if (m.soil) {
        // Compact soil holds patchy mud and shallow water; the basins stay put as rainfall changes.
        soilBasins = texture(u_rainNoise, uv / 22.0).r * .7
              + texture(u_rainNoise, uv / 7.0 + .37).g * .3;
        float mud = wet * smoothstep(.24, .68, soilBasins);
        albedo *= 1.0 - .30 * mud;
        properties.g = mix(properties.g, .55, mud);
    }
    float puddle = groundPuddle(wet, response);
    if (wet * u_rainDetail > 0.0 && m.mapped) {
        // Microscopic depressions wet first, without moving the road or its supporting terrain.
        puddle = reliefPuddle(puddle, properties.r, wet);
        if (m.soil) {
            float basinWater = smoothstep(mix(.86, .51, wet), mix(.92, .59, wet), soilBasins);
            puddle = max(puddle, basinWater * wet * smoothstep(.98, .999, v_normal.z) * u_rainDetail);
        }
    }
    reliefVisibility = mix(reliefVisibility, 1.0, puddle);
    groundFilm(albedo, normal, puddle);
    albedo = m.mapped ? groundLighting(albedo, normal, wet, response, puddle, properties.g, mix(1.0, properties.b, .7))
          : groundLighting(albedo, normal, wet, response, puddle, -1.0, 1.0);
#ifdef roadFlag
    // The road's outer strip carries coverage. World-anchored grain breaks up its dusty verge.
    float grain = m.mapped ? properties.r : roadAlbedo(m, uv * 2.3, gradient * 2.3).r;
    float alpha = clamp(roadColor.a + (grain - .5) * min(roadColor.a, 1.0 - roadColor.a) * .7, 0.0, 1.0);
    if (m.mapped && m.transition < -2.5) {
        // Grounded bridge landings: individual asphalt chips/aggregate expose soil and grass. The broad
        // envelope is in the mask; material grain supplies the small fractured edge at every orientation.
        float chips = roadSurface(m, uv * .63 + .19, gradient * .63).r;
        float fragments = clamp((.56 * grain + .28 * chips + .16 * loosePatches - .2) / .6, 0.0, 1.0);
        float filterWidth = max(.025, fwidth(fragments));
        float broken = smoothstep(fragments - filterWidth, fragments + filterWidth, roadColor.a);
        broken *= smoothstep(0.0, .025, roadColor.a);
        float dust = m.transition < -3.5 ? .16 : .025;
        alpha = mix(broken, roadColor.a, dust);
    } else if (wheelWear) {
        alpha *= smoothstep(.16, .62, wearPatches) * mix(.35, 1.0, loosePatches);
    } else if (m.mapped && abs(m.transition) > .5) {
        // The wide loose margin has no opaque shoulder underneath it. Clumps give way to isolated grains
        // and exposed ground; the compacted core remains continuous and at the authored road width.
        float clumps = texture(u_rainNoise, uv / 32.0 + .19).r;
        float threshold = .05 + .8 * clumps + .12 * loosePatches;
        float broken = smoothstep(threshold - .12, threshold + .12, roadColor.a + (grain - .5) * .32);
        alpha = mix(roadColor.a, broken, 4.0 * roadColor.a * (1.0 - roadColor.a));
        alpha *= roadCoverage;
    }
    fragColor = vec4(albedo, alpha);
#else
    fragColor = vec4(albedo, 1.0);
#endif
}
