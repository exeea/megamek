// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Solid crust and cooled banks: occluding relief, stable plates and deep red fissures.
// Retain the solid fissures' existing emission; the thermal calibration applies to liquid only.
const vec3 MAGMA_CRACK_RADIANCE = vec3(.25, .022, .0015);
float magmaRepeatMetres() { return 12.0; }
vec2 magmaRelief(vec2 uv, vec2 dx, vec2 dy, vec4 surface, vec2 parallax) {
    // Measure the relief ray in screen pixels with the projection's full Jacobian. A grazing, anisotropic
    // footprint must not lose its resolved direction merely because its other direction is strongly minified.
    float layers = 12.0;
    // The derivative matrix's Frobenius norm bounds its largest stretch. Beyond this cheap bound the ray
    // spans at least six screen pixels, so it certainly needs the full budget; skip inversion on close crust.
    if (dot(parallax, parallax) < 36.0 * (dot(dx, dx) + dot(dy, dy))) {
        float determinant = abs(dx.x * dy.y - dx.y * dy.x);
        vec2 projected = vec2(parallax.x * dy.y - parallax.y * dy.x, dx.x * parallax.y - dx.y * parallax.x);
        float pixels = length(projected) / max(determinant, 1e-12);
        // Below half a pixel, a single height offset carries the complete aligned material. Where relief is
        // visible, march no farther than half a screen pixel per layer, up to the existing twelve-layer budget.
        if (pixels <= .5) return uv - parallax * (surface.r - .5);
        layers = clamp(ceil(pixels * 2.0), 2.0, 12.0);
    }
    vec2 stride = parallax / layers;
    uv += parallax * .5;
    float layer = 1.0;
    float previous = 1.0 - magmaTexel(MAGMA_SURFACE, uv, dx, dy).r;
    for (int step = 0; step < 12; step++) {
        uv -= stride;
        layer -= 1.0 / layers;
        float gap = layer - magmaTexel(MAGMA_SURFACE, uv, dx, dy).r;
        if (gap <= 0.0) {
            uv += stride * (-gap / max(previous - gap, .0001));
            break;
        }
        previous = gap;
    }
    return uv;
}

Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 downhill, vec3 random) {
    return magmaSample(uv, dx, dy, eye, true);
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
