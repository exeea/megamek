// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One optical model for the water surface and everything seen through it. The bed keeps the hue a column of water
// passes; the surface removes the rest of what the column absorbs and adds its in-scattered light.
uniform sampler2D u_waterDetail; // RG ripple slope, B foam noise, A caustic network; every channel tiles
// Depth in levels is encoded over this range, in the bed's vertex color and in the surface field alike.
const float WATER_DEPTH_RANGE = 4.0;
// Light crosses the column down to the bed and back up to the board camera: optical path per level of depth.
const float WATER_PATH = 1.7;
// The surface never hides more than this share of what lies below it, so units stay readable in deep water. Beds
// take the rest of their column's absorption themselves, which keeps the look of the water physical.
const float WATER_MAX_OPACITY = 0.5;

// Absorption per level of optical path. Red goes first, which turns shallow water over pale gravel turquoise.
vec3 waterAbsorption(float palette) {
    if (palette < 0.5) return vec3(1.9, 0.5, 0.33);
    if (palette < 1.5) return vec3(0.75, 1.25, 2.4);   // Mars: rust silt keeps red
    if (palette < 2.5) return vec3(0.9, 2.2, 2.0);     // volcanic: iron-red
    return vec3(2.4, 0.7, 3.2);                          // hazardous: chemical green
}

// Color of light scattered back out of a deep column lit by white light: clear water turns a deep navy.
vec3 waterScatter(float palette) {
    if (palette < 0.5) return vec3(0.012, 0.13, 0.26);
    if (palette < 1.5) return vec3(0.46, 0.26, 0.10);
    if (palette < 2.5) return vec3(0.30, 0.10, 0.10);
    return vec3(0.14, 0.40, 0.05);
}

// Shallow water over a pale bed glows with the bed's light scattered back through it: clear water turquoise.
vec3 waterShallows(float palette) {
    if (palette < 0.5) return vec3(0.08, 0.66, 0.62);
    if (palette < 1.5) return vec3(0.66, 0.44, 0.20);
    if (palette < 2.5) return vec3(0.50, 0.22, 0.17);
    return vec3(0.30, 0.62, 0.10);
}

// Even ankle-deep water reads as water: a shallow floor of optical depth, faded in from the water line itself.
vec3 waterTransmission(float palette, float levels) {
    float optical = max(levels, 0.0) + 0.16 * smoothstep(0.0, 0.05, levels);
    return exp(-waterAbsorption(palette) * (optical * WATER_PATH));
}

// Two drifting copies of one network; their minimum is a sharp web of focused light that keeps re-forming.
float waterCaustics(vec2 position) {
    vec2 drift = vec2(0.021, 0.013) * u_rainTime;
    float first = texture2D(u_waterDetail, position * 1.35 + drift).a;
    float second = texture2D(u_waterDetail, position * 1.1 - drift.yx * 1.3 + 0.41).a;
    return min(first, second);
}

// What a submerged surface keeps of its own color: the hue its column passes, less the brightness the surface above
// removes, which is all of it up to WATER_MAX_OPACITY.
vec3 waterBedTint(float palette, float levels) {
    vec3 kept = waterTransmission(palette, levels);
    return kept / max(max(max(kept.r, kept.g), kept.b), 1.0 - WATER_MAX_OPACITY);
}

// Light on a submerged surface: the water scatters daylight in every direction, so below the surface orientation and
// shadows soften with depth toward the surface's own colour under that scattered light.
vec3 submergedLight(vec3 lit, vec3 pigment, vec3 scattered, float levels) {
    return mix(lit, pigment * scattered, smoothstep(0.0, 0.8, levels) * 0.75);
}

// Extra direct light focused onto submerged ground: none at the water line, strongest in the shallows.
float waterBedCaustics(vec2 position, float levels) {
    return waterCaustics(position) * 2.6 * exp(-levels * 1.4) * smoothstep(0.0, 0.05, levels);
}
