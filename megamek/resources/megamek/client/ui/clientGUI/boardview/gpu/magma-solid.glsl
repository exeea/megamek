// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Solid crust and cooled banks: occluding relief, stable plates and deep red fissures.
float magmaRepeatMetres() { return 12.0; }
vec2 magmaRelief(vec2 uv, vec2 dx, vec2 dy, vec4 surface, vec2 parallax) {
    if (u_normalMaps > .5) {
        uv += parallax * .5;
        float layer = 1.0;
        float previous = 1.0 - magmaTexel(MAGMA_SURFACE, uv, dx, dy).r;
        for (int step = 0; step < 12; step++) {
            uv -= parallax / 12.0;
            layer -= 1.0 / 12.0;
            float gap = layer - magmaTexel(MAGMA_SURFACE, uv, dx, dy).r;
            if (gap <= 0.0) {
                uv += parallax / 12.0 * (-gap / max(previous - gap, .0001));
                break;
            }
            previous = gap;
        }
    } else {
        uv -= parallax * (surface.r - .5);
    }
    return uv;
}

Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 downhill, vec3 random) {
    return magmaSample(uv, dx, dy, eye);
}

mat3 magmaDomain(inout vec3 position) {
    // Bend the 12 m plate field within a few repeats, rather than translating a recognisable grid almost rigidly.
    // The bounded off-diagonal derivatives sum to less than one per row: the domain cannot fold over itself.
    // Keep its exact Jacobian so colour, parallax and normal relief still describe the same surface.
    vec3 phase = position.yzx * vec3(1.73, 1.57, 1.91) + position.zxy * .47 + vec3(.1, 1.3, 2.7);
    vec3 slope = .32 * cos(phase);
    position += .32 * sin(phase);
    return mat3(vec3(1.0, .47 * slope.y, 1.91 * slope.z),
          vec3(1.73 * slope.x, 1.0, .47 * slope.z), vec3(.47 * slope.x, 1.57 * slope.y, 1.0));
}

vec3 magmaHeatColor(float heat) {
    return mix(vec3(.55, .006, .0002), vec3(.95, .24, .004), heat * heat * heat);
}

vec3 magmaRadiance(Volcanic material, float bank, float strength) {
    return magmaEmission(material.heat * mix(.72, 1.0, bank), strength);
}
