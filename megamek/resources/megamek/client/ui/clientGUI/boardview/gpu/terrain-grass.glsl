// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Injected into the lit vertex shader. Only roots/ranks are stored; blade shape and wind live on the GPU.
layout(location = 14) in vec4 a_coverRoot;
uniform vec3 u_wind;
uniform float u_rainTime;
uniform float u_worldMetre;
uniform float u_coverPixels;
uniform float u_coverHexWidth;
varying vec2 v_coverData;
varying vec2 v_coverRoot;

float grassRandom(uint seed) {
    seed ^= seed >> 16;
    seed *= 0x7feb352du;
    seed ^= seed >> 15;
    seed *= 0x846ca68bu;
    seed ^= seed >> 16;
    return float(seed & 0x00ffffffu) / 16777216.0;
}

void grassBlade(vec3 samplePoint, out vec3 position, out vec3 normal, out vec4 color) {
    vec3 root = a_coverRoot.xyz;
    uint seed = floatBitsToUint(root.x) ^ (floatBitsToUint(root.y) * 1664525u) ^ uint(a_coverRoot.w);
    float angle = grassRandom(seed) * 6.2831853;
    float variation = grassRandom(seed + 23u);
    vec2 direction = vec2(cos(angle), sin(angle));
    vec3 side = vec3(-direction.y, direction.x, 0.0);
    float meadow = meadowCover(root.xy / u_worldMetre);
    float height = u_coverHexWidth * mix(.018, .043, variation) * mix(.55, 1.0, meadow);
    float width = u_coverHexWidth * mix(.0015, .0028, grassRandom(seed + 37u)) * mix(.75, 1.0, meadow);
    float pixels = u_coverPixels / max(.001, abs((u_projViewTrans * vec4(root, 1.0)).w));
    float density = smoothstep(@START_PIXELS@, @FULL_PIXELS@, pixels) * @ROOTS_PER_HEX@;
    // Fractional growth of the last blade keeps density transitions continuous, without shading invisible blades.
    float growth = clamp(density - a_coverRoot.w, 0.0, 1.0);
    float t = samplePoint.y;
    float gust = sin(dot(root.xy / u_worldMetre, vec2(.11, .07)) - u_rainTime * 1.7);
    vec2 bend = direction * height * mix(.25, .65, grassRandom(seed + 51u));
    bend += u_wind.xy * u_wind.z * height * (.25 + .18 * gust);
    vec3 tip = vec3(bend, height);
    tip *= height / max(height, length(tip));
    vec3 control = vec3(0.0, 0.0, height * .65);
    vec3 curve = 2.0 * (1.0 - t) * t * control + t * t * tip;
    vec3 tangent = 2.0 * (1.0 - t) * control + 2.0 * t * (tip - control);
    position = root + curve + side * samplePoint.x * width * (1.0 - t) * growth;
    normal = normalize(cross(side, tangent));
    float tone = grassRandom(seed + 71u);
    color = vec4(mix(vec3(.24, .30, .10), vec3(.44, .48, .21), tone), 1.0);
    v_coverData = vec2(height, t);
    v_coverRoot = root.xy / u_worldMetre;
}
