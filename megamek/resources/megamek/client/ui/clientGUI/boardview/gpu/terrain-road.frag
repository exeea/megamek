#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Roads, loose verges, bridge decks and tunnel floors.
// DefaultShader owns the uniforms, material binding and directional shadow map.

in vec2 v_diffuseUV;
in vec3 v_normal;
in vec4 v_color;
uniform sampler2D u_diffuseTexture;
#if defined(normalTextureFlag) || defined(roadCoatFlag) || defined(roadMapsFlag)
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
// A road material's repeat at the width it is catalogued for (BoardRelief.detailMetres(1)).
uniform float u_worldMetre;
#ifdef bridgeDeckFlag
uniform float u_bridgeDeckCoverage; // GpuAssets.coverage of the deck's road texture
#endif
#ifdef roadMapsFlag
uniform sampler2D u_roadSurface;
uniform float u_roadTransition;
uniform float u_roadFinish; // the material's place in GpuRoads.MATERIALS, -1 for none
#endif
#if defined(roadMapsFlag) || defined(roadCoatFlag)
// Per road material, its heightmap parallax as Godot draws it: the depth along U and V in UV units (heightmap_scale /
// 100, negative where Godot flips the tangent or binormal); the deep parallax's minimum and maximum layers, 0 for one
// step.
uniform vec4 u_roadRelief[FINISH_COUNT - ROAD_FINISH];
#endif
#if defined(roadMapsFlag) || defined(roadCoatFlag)
uniform vec4 u_roadProfile;
#endif
#ifdef roadCoatFlag
// Every coat of a chunk in one draw. Road maps: colour, normal and surface per material; sculpt maps: colour and
// normal per material. Each coat's own choice, finish and wet response come from its vertices (GpuRoads#coat).
uniform sampler2DArray u_roadColors, u_roadData;
uniform sampler2DArray u_sculptColors, u_sculptData;
// The finish layers follow the sculpt normal/AO layers (finish-arrays.glsl).
#define FINISH_LAYERS u_sculptData
flat in vec4 v_roadCoat;
#endif

// ground-surface-functions
// Those shared functions are inserted just before main, after the helpers below.
vec3 groundNormal(vec2 uv, float strength);
#ifdef roadCoatFlag
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

vec4 roadAlbedo(RoadMaps m, vec2 uv) {
#ifdef roadCoatFlag
    return m.mapped ? texture(u_roadColors, vec3(uv, float(m.maps)))
          : texture(u_sculptColors, vec3(uv, float(m.maps - 3)));
#else
    return texture(u_diffuseTexture, uv);
#endif
}

// Only a surface with maps has one; see RoadMaps.mapped.
vec4 roadSurface(RoadMaps m, vec2 uv) {
#ifdef roadCoatFlag
    return texture(u_roadData, vec3(uv, float(m.maps * 2 + 1)));
#elif defined(roadMapsFlag)
    return texture(u_roadSurface, uv);
#else
    return vec4(.5);
#endif
}

vec3 roadNormal(RoadMaps m, vec2 uv, float strength) {
#ifdef roadCoatFlag
    return mappedNormal(m.mapped ? texture(u_roadData, vec3(uv, float(m.maps * 2)))
          : texture(u_sculptData, vec3(uv, float(m.maps - 3))), strength);
#else
    return groundNormal(uv, strength);
#endif
}

#if defined(roadMapsFlag) || defined(roadCoatFlag)
// The material's place in the board's finish arrays (finish-arrays.glsl); -1 for a road material they lack.
int roadFinish(RoadMaps m) {
#ifdef roadCoatFlag
    return m.mapped ? ROAD_FINISH + m.maps : m.maps - 3;
#else
    return u_roadFinish < -.5 ? -1 : ROAD_FINISH + int(u_roadFinish + .5);
#endif
}

// Godot's heightmap parallax over a mapped road material's relief (its surface map's R, white high): the view, in the
// UV frame, moves its colour, normal, surface and finish reads. Deep parallax marches from the maximum layers at grazing
// views to the minimum head-on, then interpolates the crossing.
// A mapped material's height (its surface map's R) with explicit gradients: the march below diverges per pixel.
float roadHeight(RoadMaps m, vec2 uv, mat2 gradient) {
#ifdef roadCoatFlag
    return textureGrad(u_roadData, vec3(uv, float(m.maps * 2 + 1)), gradient[0], gradient[1]).r;
#else
    return textureGrad(u_roadSurface, uv, gradient[0], gradient[1]).r;
#endif
}

// dx and dy are the surface position's derivatives; view points from it to the eye.
vec2 roadParallax(RoadMaps m, vec2 uv, mat2 gradient, vec3 face, vec3 dx, vec3 dy, vec3 view) {
    int material = roadFinish(m) - ROAD_FINISH;
    float determinant = gradient[0].x * gradient[1].y - gradient[0].y * gradient[1].x;
    // The Normal maps switch takes the parallax with the normal maps.
    if (u_normalMaps <= .5 || !m.mapped || material < 0 || abs(determinant) < 1e-12) return vec2(0.0);
    vec4 relief = u_roadRelief[material];
    vec3 tangent = (dx * gradient[1].y - dy * gradient[0].y) / determinant;
    vec3 bitangent = (dy * gradient[0].x - dx * gradient[1].x) / determinant;
    vec3 v = normalize(vec3(dot(view, normalize(tangent)), dot(view, normalize(bitangent)), dot(view, face)));
    vec2 reach = v.xy * relief.xy;
    // A relief shallower than half a pixel moves nothing visible: skip its reads.
    if (dot(reach, reach) < .25 * max(dot(gradient[0], gradient[0]), dot(gradient[1], gradient[1]))) return vec2(0.0);
    float depth = 1.0 - roadHeight(m, uv, gradient);
    if (relief.w < 1.0) return -reach * depth;
    float layers = mix(relief.w, relief.z, abs(v.z));
    vec2 delta = reach / layers;
    vec2 shift = vec2(0.0);
    float current = 0.0;
    for (int layer = 0; layer < 128 && current < depth; layer++) {
        shift -= delta;
        depth = 1.0 - roadHeight(m, uv + shift, gradient);
        current += 1.0 / layers;
    }
    float after = depth - current;
    float before = 1.0 - roadHeight(m, uv + shift + delta, gradient) - current + 1.0 / layers;
    return after - before < -1e-6 ? mix(shift, shift + delta, after / (after - before)) : shift;
}
#endif

vec4 roadProfile() {
#if defined(roadMapsFlag) || defined(roadCoatFlag)
    return u_roadProfile;
#else
    return vec4(1.0);
#endif
}

void main() {
    vec3 geometric = visibleFace(dFdx(v_cloudPosition), dFdy(v_cloudPosition), -viewDirection());
    // Before the mask's discard, like the face above.
    vec3 face = normalize(v_normal);
    surfaceVariance = normalVariance(dFdx(face), dFdy(face));
    vec2 uv = v_diffuseUV;
#ifdef bridgeDeckFlag
    // A rotated bridge shares the adjacent road's scale, orientation and texture phase, widened with its texture.
    uv = vec2(v_cloudPosition.x, -v_cloudPosition.y) / (u_worldMetre * u_bridgeDeckCoverage);
#endif
    mat2 roadGradient = mat2(dFdx(uv), dFdy(uv));
    // Use, mud and loose clumps are anchored to the ground: they keep their size in metres whatever ground a material's
    // texture covers. The material's own maps, magnified or not, take uv, so its grain keeps its size too.
    vec2 groundUV = vec2(v_cloudPosition.x, -v_cloudPosition.y) / u_worldMetre;
    RoadMaps m = roadMaps();
    // The material's own reads take its parallax; world-anchored patterns and its other grain scales do not.
    vec2 materialUV = uv;
#if defined(roadMapsFlag) || defined(roadCoatFlag)
    materialUV += roadParallax(m, uv, roadGradient, face, dFdx(v_cloudPosition), dFdy(v_cloudPosition), -viewDirection());
#endif
    vec4 roadColor = v_color;
#ifdef roadMaskFlag
    vec2 maskUV = v_roadMaskUV;
    roadColor = texture(u_roadMask, v_roadMaskRegion.xy + maskUV * v_roadMaskRegion.zw);
    // Keep neighboring lanes through material filtering, edge fwidth and shadow receiver derivatives.
    bool roadHidden = any(lessThan(maskUV, vec2(0.0))) || any(greaterThan(maskUV, vec2(1.0)))
          || roadColor.a < .002;
#endif
    vec3 tint = roadColor.rgb;
    if (m.mapped && (m.transition > .5 || m.transition < -1.5)) { tint = vec3(1.0); }
#ifdef roadMaskFlag
    // Ordinary masks repeat one authored tint at every texel, including transparent borders, so filtering cannot
    // change it. Their shared atlas also holds join coordinates and wheel directions, which must remain data.
    else { tint = toLinear(tint); }
#endif
    vec3 albedo = roadAlbedo(m, materialUV).rgb * tint;
    vec4 properties = m.mapped ? roadSurface(m, materialUV) : vec4(.5);
    float roadCoverage = 1.0;
    bool wheelWear = m.mapped && m.transition < -1.5 && m.transition > -2.5;
    // Reuse the shared, stable noise field. These masks describe use and loose material, not road width.
    float wearPatches = 0.0, loosePatches = 0.0, compaction = 0.0;
    if (m.mapped && abs(m.transition) > .5) {
        wearPatches = texture(u_rainNoise, groundUV / 18.0).g;
        loosePatches = texture(u_rainNoise, groundUV / 5.0 + .37).b;
        albedo *= mix(.95, 1.05, wearPatches);
        if (wheelWear) {
            vec2 heading = normalize((roadColor.rg * 255.0 - 128.0) / 127.0);
            vec2 scuff = vec2(dot(groundUV, heading), dot(groundUV, vec2(-heading.y, heading.x)))
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
        float broad = roadSurface(m, uv * .21).r;
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
    vec3 normal = roadNormal(m, materialUV, normalStrength);
    // Only exposed, upward-facing ground receives liquid water. No accumulation or terrain-rule changes.
    float wet = u_wetness * step(0.0, m.response) * smoothstep(0.2, 0.8, v_normal.z);
    float response = max(0.0, m.response);
    float absorption = groundAbsorption(wet, response);
    float soilBasins = 0.0, soilMud = 0.0;
    if (m.soil) {
        // Compact soil holds patchy mud and shallow water; the basins stay put as rainfall changes.
        soilBasins = texture(u_rainNoise, groundUV / 22.0).r * .7
              + texture(u_rainNoise, groundUV / 7.0 + .37).g * .3;
        float mud = wet * smoothstep(.24, .68, soilBasins);
        absorption = max(absorption, .7 * mud);
        properties.g = mix(properties.g, .55, mud);
        soilMud = mud;
    }
    float puddle = groundPuddle(wet, response);
    if (wet * u_rainDetail > 0.0 && m.mapped) {
        // Microscopic depressions wet first, without moving the road or its supporting terrain.
        puddle *= mix(1.0, 1.0 - smoothstep(.18, .78, properties.r), .35);
        if (m.soil) {
            float basinWater = smoothstep(mix(.86, .51, wet), mix(.92, .59, wet), soilBasins);
            puddle = max(puddle, basinWater * wet * smoothstep(.98, .999, v_normal.z) * u_rainDetail);
        }
    }
    albedo = wetAlbedo(albedo, absorption);
    groundFilm(normal, puddle);
    // The material's finish; a mapped material's roughness is its surface map's, with the wear and mud above.
    Finish finish = Finish(m.mapped ? properties.g : -1.0, 0.0, 0.0, vec3(0.0));
#if defined(roadMapsFlag) || defined(roadCoatFlag)
    int material = roadFinish(m);
    if (material >= 0) {
        Finish authored = finishTexel(material, materialUV, roadGradient);
        finish = Finish(m.mapped ? properties.g : authored.roughness, authored.metallic, authored.scatter, authored.emission);
        // Mud buries the soil's own finish.
        coverFinish(finish, soilMud);
    }
#endif
    albedo = groundLighting(albedo, normal, geometric, wet, response, puddle, finish,
          m.mapped ? mix(1.0, properties.b, .7) : 1.0);
#ifdef roadFlag
    // The road's outer strip carries coverage. World-anchored grain breaks up its dusty verge.
    float grain = m.mapped ? properties.r : roadAlbedo(m, uv * 2.3).r;
    float alpha = clamp(roadColor.a + (grain - .5) * min(roadColor.a, 1.0 - roadColor.a) * .7, 0.0, 1.0);
    if (m.mapped && m.transition < -2.5) {
        // Grounded bridge landings: individual asphalt chips/aggregate expose soil and grass. The broad
        // envelope is in the mask; material grain supplies the small fractured edge at every orientation.
        float chips = roadSurface(m, uv * .63 + .19).r;
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
        float clumps = texture(u_rainNoise, groundUV / 32.0 + .19).r;
        float threshold = .05 + .8 * clumps + .12 * loosePatches;
        float broken = smoothstep(threshold - .12, threshold + .12, roadColor.a + (grain - .5) * .32);
        alpha = mix(roadColor.a, broken, 4.0 * roadColor.a * (1.0 - roadColor.a));
        alpha *= roadCoverage;
    }
    fragColor = vec4(albedo, alpha);
#else
    fragColor = vec4(albedo, 1.0);
#endif
#ifdef roadMaskFlag
    if (roadHidden) discard;
#endif
}
