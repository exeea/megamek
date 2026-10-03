// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Smooth aperiodic coordinates keep one height field without multiplying the march's texture reads.
// Gentle oblique shears avoid stretching stones into the long curling bands produced by strong axis-aligned
// shears. Their maximum local stretch is below 1.26 and their determinant is one: they cannot fold the UVs.
// Return the analytic derivative so
// colour, filtering, view/light rays and normals share the same mapping, independently of the detail toggles.
float materialPhase(float cell) {
    uint h = uint(int(cell));
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return float(h >> 8u) / 16777215.0;
}

vec2 materialNoise(float x) {
    float cell = floor(x), t = x - cell;
    float a = materialPhase(cell), b = materialPhase(cell + 1.0);
    float fade = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    float slope = 30.0 * t * t * (t - 1.0) * (t - 1.0);
    return vec2(mix(a, b, fade), (b - a) * slope);
}

mat2 materialWarp(inout vec2 uv, float layer, bool wall) {
    // Vary the sampled window slowly without turning each pebble or clump into a wave.
    // Ground uses oblique directions so the remaining variation does not follow texture rows and columns.
    mat2 axes = wall ? mat2(1.0) : mat2(.8, .6, -.6, .8);
    vec2 local = transpose(axes) * uv;
    vec2 horizontal = materialNoise(local.y * .11 + layer * 5.37 + .27);
    local.x += horizontal.x - .5;
    float a = .11 * horizontal.y;
    // Keep geological beds nearly level while varying the windows sampled down a cliff face.
    float vertical = wall ? .12 : 1.0;
    vec2 along = materialNoise(local.x * .13 + layer * 7.13 + 17.3);
    local.y += vertical * (along.x - .5);
    float b = vertical * .13 * along.y;
    uv = axes * local;
    return axes * mat2(1.0, b, a, 1.0 + a * b) * transpose(axes);
}

vec3 materialWarpNormal(vec3 normal, mat2 basis) {
    return normalize(vec3(transpose(basis) * normal.xy, normal.z));
}
