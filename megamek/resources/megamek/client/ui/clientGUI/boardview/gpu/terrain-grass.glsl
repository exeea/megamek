// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Injected into the lit vertex shader. Only roots/ranks are stored; blade shape and wind live on the GPU.
// Older running clients can reload this shader before their Java-side shared size constant is rebuilt.
#ifndef GRASS_MAX_HEIGHT
#define GRASS_MAX_HEIGHT .07
#endif
#ifndef SHRUB_LEAVES
#define SHRUB_LEAVES 12.0
#endif
layout(location = 14) in vec4 a_coverRoot;
uniform float u_coverPixels;
uniform float u_coverHexWidth;
out vec2 v_coverData;
out vec2 v_coverRoot;

float grassRandom(uint seed) {
    seed ^= seed >> 16;
    seed *= 0x7feb352du;
    seed ^= seed >> 15;
    seed *= 0x846ca68bu;
    seed ^= seed >> 16;
    return float(seed & 0x00ffffffu) / 16777216.0;
}

// Dwarf-shrub forms, as fractions of the shrub's radius: height, leaf length, leaf width and the leaves' rise on the
// crown (x to w); their rise at the rim and how far the leaves spread from the stem (x, y). A low mat of needles, a
// prostrate spread of round leaves, an upright twiggy bush of round leaves and an upright one of narrow leaves.
const vec4 SHRUB_FORM[4] = vec4[4](vec4(.35, .4, .22, .7), vec4(.3, .55, .5, .5), vec4(1.15, .45, .55, 1.8),
      vec4(1.3, .62, .28, 2.4));
const vec2 SHRUB_SPREAD[4] = vec2[4](vec2(.12, .8), vec2(.08, .85), vec2(.55, .5), vec2(.8, .4));

// A dwarf shrub of the autumn tundra (GpuGroundCover's shrub template, its leaf number + 1 in z): diamond leaves
// around the root, steeper on the crown than at the rim, of a form and size drawn per shrub, in its colony's colour.
void dwarfShrub(vec3 samplePoint, vec3 root, uint seed, float scar, float growth, out vec3 position,
      out vec3 normal, out vec4 color) {
    float leaf = samplePoint.z - 1.0, ring = (leaf + .5) / SHRUB_LEAVES;
    uint leafSeed = seed + uint(leaf) * 131u;
    vec2 metres = root.xy / u_worldMetre;
    int kind = int(grassRandom(seed + 13u) * 3.999);
    vec4 form = SHRUB_FORM[kind];
    vec2 spread = SHRUB_SPREAD[kind];
    // Mostly small shrubs, a few broad ones.
    float size = grassRandom(seed + 7u);
    float radius = u_coverHexWidth * mix(.006, .022, size * size) * mix(.75, 1.0, meadowCover(metres))
          * (1.0 - scar * .5) * growth;
    float height = radius * form.x * mix(.75, 1.2, grassRandom(seed + 11u));
    float angle = grassRandom(seed) * 6.2831853 + leaf * 2.3999632;
    vec2 outward = vec2(cos(angle), sin(angle));
    vec3 base = root + vec3(outward * radius * spread.y * sqrt(ring), height * (.1 + .75 * (1.0 - ring * ring)));
    // Woody sprays sway a little in the gust, their tips the most.
    vec3 along = normalize(vec3(outward, mix(form.w, spread.x, ring)));
    along = normalize(along + vec3(u_wind.xy, 0.0) * u_wind.z * (.25 + .35 * vegetationGust(root.xy)) * samplePoint.y);
    vec3 side = vec3(-outward.y, outward.x, 0.0);
    float leafLength = radius * form.y * mix(.8, 1.2, grassRandom(leafSeed + 3u));
    position = base + along * samplePoint.y * leafLength + side * samplePoint.x * leafLength * form.z;
    normal = normalize(cross(side, along));
    // Colonies metres across, each of crowberry, bearberry, dwarf birch or willow.
    float colony = clamp((mix(textureLod(u_rainNoise, metres / 192.0 + .61, 0.0).r, grassRandom(seed + 71u), .5) - .5)
          * 2.2 + .5, 0.0, 1.0);
    vec3 crowberry = toLinear(vec3(.22, .25, .11)), bearberry = toLinear(vec3(.46, .14, .07));
    vec3 birch = toLinear(vec3(.62, .30, .09)), willow = toLinear(vec3(.60, .50, .18));
    color = vec4(mix(mix(mix(crowberry, bearberry, smoothstep(.25, .35, colony)), birch, smoothstep(.55, .65, colony)),
          willow, smoothstep(.8, .88, colony)), 1.0);
    color.rgb *= mix(.8, 1.1, grassRandom(leafSeed + 5u));
    color.rgb = mix(color.rgb, toLinear(vec3(.18, .14, .08)), scar * .75);
    v_coverData = vec2(height, clamp((position.z - root.z) / max(height, 1e-4), 0.0, 1.0));
    v_coverRoot = metres;
}

// False when this root is beyond the density its projected size allows: the caller skips the rest of the program.
// A chunk's buffer is drawn as a prefix by rank for its nearest hex; farther hexes need fewer of those roots. A
// shrub's root carries its rank + .5 (GpuGroundCover.plant).
bool grassBlade(vec3 samplePoint, out vec3 position, out vec3 normal, out vec4 color) {
    vec3 root = a_coverRoot.xyz;
    float rank = floor(a_coverRoot.w);
    float pixels = u_coverPixels / max(.001, abs((u_projViewTrans * vec4(root, 1.0)).w));
    float density = smoothstep(GRASS_START_PIXELS, GRASS_FULL_PIXELS, pixels) * GRASS_ROOTS_PER_HEX;
    if (density <= rank) { return false; }
    uint seed = floatBitsToUint(root.x) ^ (floatBitsToUint(root.y) * 1664525u) ^ uint(rank);
    // Reject the whole rooted blade, not fragments of a swaying tip. Buffers remain unchanged after an impact.
    float scar = max(groundScarCover(groundDamageAt(root.xy)), scorchedGrass(root.xy));
    if (scar > grassRandom(seed + 83u)) { return false; }
    // Fractional growth of the last plant keeps density transitions continuous, without shading invisible ones.
    float growth = clamp(density - rank, 0.0, 1.0);
    if (samplePoint.z > .5) {
        dwarfShrub(samplePoint, root, seed, scar, growth, position, normal, color);
        return true;
    }
    float angle = grassRandom(seed) * 6.2831853;
    float variation = grassRandom(seed + 23u);
    vec2 direction = vec2(cos(angle), sin(angle));
    vec3 side = vec3(-direction.y, direction.x, 0.0);
    float meadow = meadowCover(root.xy / u_worldMetre);
    float height = u_coverHexWidth * mix(.025, GRASS_MAX_HEIGHT, variation) * mix(.55, 1.0, meadow);
    height *= 1.0 - scar * .5;
    float width = u_coverHexWidth * mix(.0015, .0028, grassRandom(seed + 37u)) * mix(.75, 1.0, meadow);
    float t = samplePoint.y;
    float gust = vegetationGust(root.xy);
    vec3 rest = vec3(direction * mix(.25, .65, grassRandom(seed + 51u)), 1.0);
    float alongWind = dot(rest.xy, u_wind.xy);
    vec3 acrossWind = rest - vec3(u_wind.xy * alongWind, 0.0);
    float acrossLength = length(acrossWind);
    // Equal strength steps turn the blade through equal angles, retaining its length and both endpoint poses.
    // Scaling displacement before normalizing used up most of the visible bend near zero strength.
    float windAngle = mix(atan(alongWind, acrossLength), atan(alongWind + 12.5 + 9.0 * gust, acrossLength), u_wind.z);
    vec3 tip = height * (acrossWind * (cos(windAngle) / acrossLength) + vec3(u_wind.xy * sin(windAngle), 0.0));
    vec3 control = vec3(0.0, 0.0, height * .65);
    vec3 curve = 2.0 * (1.0 - t) * t * control + t * t * tip;
    vec3 tangent = 2.0 * (1.0 - t) * control + 2.0 * t * (tip - control);
    position = root + curve + side * samplePoint.x * width * (1.0 - t) * growth;
    normal = normalize(cross(side, tangent));
    float tone = grassRandom(seed + 71u);
    color = vec4(mix(toLinear(vec3(.24, .30, .10)), toLinear(vec3(.44, .48, .21)), tone), 1.0);
    color.rgb = mix(color.rgb, toLinear(vec3(.18, .14, .08)), scar * .75);
    v_coverData = vec2(height, t);
    v_coverRoot = root.xy / u_worldMetre;
    return true;
}
