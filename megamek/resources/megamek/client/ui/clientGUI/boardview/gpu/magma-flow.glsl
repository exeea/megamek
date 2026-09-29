// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Moving pools and falls: two-phase advection, convection and orange molten heat.
uniform float u_magmaTime;          // the board's shared animation clock
uniform sampler2D u_magmaOcean; // shared inverse FFT: RG slope, BA horizontal displacement in metres
uniform float u_magmaOceanScale;
uniform sampler2D u_magmaField; // existing liquid field: R bank distance, BA world XY current
uniform vec4 u_magmaFieldMap;
const float MAGMA_CYCLE = 8.0;

vec2 magmaRelief(vec2 uv, vec2 dx, vec2 dy, vec4 surface, vec2 parallax) {
    return uv - parallax * (surface.r - .5);
}

Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 downhill) {
    vec2 flow = textureGrad(u_emissiveTexture, uv, dx, dy).gb * 2.0 - 1.0;
    // Sampling moves uphill so material travels downhill; gentle source curls retain the cooling rafts.
    vec2 velocity = flow * .018 + downhill;
    float phase = fract(u_magmaTime / MAGMA_CYCLE);
    return magmaMix(magmaSample(uv, dx, dy, eye, phase, velocity),
          magmaSample(uv, dx, dy, eye, fract(phase + .5), velocity), abs(phase * 2.0 - 1.0));
}

vec3 magmaWeights(vec3 weights, vec3 heights) { return weights; }

vec3 magmaDrift(vec3 face, vec2 current, float bank) {
    vec3 uphill = (vec3(0.0, 0.0, 1.0) - face * face.z) * (.037 * MAGMA_CYCLE);
    // The existing BoardFlow field supplies approach/junction currents as well as the actual descent.
    // Convert hex widths/second to texture repeats per advection cycle (30 m / 12 m).
    vec3 drift = vec3(-current * (2.5 * MAGMA_CYCLE), 0.0);
    uphill += (drift - face * dot(drift, face)) * bank;
    return uphill;
}

vec3 magmaHeatColor(float heat) {
    float temperature = smoothstep(.18, .85, heat);
    return mix(vec3(.55, .006, .0002), vec3(.98, .48, .035), temperature * temperature * temperature);
}
