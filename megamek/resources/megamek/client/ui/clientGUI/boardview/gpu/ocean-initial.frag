#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Water's initial spectrum for a wind (GpuOcean), every cascade side by side: h0(k) and conj(h0(-k)) per texel. A
// fetch-limited JONSWAP sea (Hasselmann et al. 1973) spread about the wind by Donelan-Banner's sech², plus a low,
// narrow swell from a steady quarter to the wind so calm open water keeps breathing. Each cascade owns one band of
// wave numbers; neighbours cross-fade in power over an octave, so the sum is the full spectrum once. Amplitudes
// carry the wave-number cell area: the inverse transform yields heights in metres directly. With a blend of zero or
// more the same program instead freezes a transition part-way, so a new wind takes over from the sea as it looks.
uniform sampler2D u_gauss;  // RG: two independent standard normal deviates per texel, fixed for the session
uniform sampler2D u_from;   // blending: the spectra a transition runs between
uniform sampler2D u_to;
uniform float u_blend;      // 0..1 mixes from and to; negative generates the wind's spectrum
uniform int u_size;         // texels along a side of one cascade
uniform vec3 u_patches;     // metres each cascade repeats over
uniform vec2 u_directions;  // radians the wind sea and the swell travel toward
uniform vec4 u_sea;         // wind sea's JONSWAP peak (rad/s) and alpha, then the swell's
uniform float u_storm;      // art-directed amplitude of a storm sea

const float GRAVITY = 9.81;
const float PI = 3.14159265;
const float HANDOVER = 4.0; // a cascade hands over at this many of the next cascade's own harmonics

float jonswap(float omega, float peak, float alpha) {
    float sigma = omega <= peak ? .07 : .09, offset = (omega - peak) / (sigma * peak);
    return alpha * GRAVITY * GRAVITY / pow(omega, 5.0) * exp(-1.25 * pow(peak / omega, 4.0))
          * pow(3.3, exp(-.5 * offset * offset));
}

// Narrowest at the spectral peak, broadening for shorter and longer waves.
float donelanBanner(float ratio, float theta) {
    float beta = ratio < .95 ? 2.61 * pow(max(ratio, .56), 1.3) : ratio < 1.6 ? 2.28 * pow(ratio, -1.3)
          : pow(10.0, -.4 + .8393 * exp(-.567 * log(ratio * ratio)));
    float sech = 1.0 / cosh(beta * theta);
    return beta / (2.0 * tanh(beta * PI)) * sech * sech;
}

// A swell's narrow cos^2s(θ/2) spreading, s = 24: Γ(s+1) / (2√π Γ(s+½)) = 1.389 normalises it.
float swellSpread(float theta) {
    float c = cos(theta * .5);
    return 1.389 * pow(c * c, 24.0);
}

// The angle from a direction, wrapped into (-π, π].
float relative(float theta, float direction) {
    return mod(theta - direction + PI, 2.0 * PI) - PI;
}

// 0 below 0.7 k, 1 above 1.4 k, smooth in log wave number: a power-complementary cross-fade.
float handover(float k, float boundary) {
    float t = clamp(log(k / (.7 * boundary)) / log(2.0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// h0 at one texel of a cascade: the texel's deviates scaled by the square root of the variance its wave carries.
vec2 wave(int cascade, ivec2 index) {
    // The Nyquist rows have no conjugate partner; leaving them empty keeps the result real.
    if (index.x == 0 || index.y == 0) return vec2(0.0);
    float extent = cascade == 0 ? u_patches.x : cascade == 1 ? u_patches.y : u_patches.z;
    float cell = 2.0 * PI / extent;
    vec2 k = cell * vec2(index - ivec2(u_size / 2));
    float magnitude = length(k);
    if (magnitude < 1e-6) return vec2(0.0);
    float share = (cascade == 0 ? 1.0 : handover(magnitude, HANDOVER * cell))
          * (cascade == 2 ? 1.0 : 1.0 - handover(magnitude, HANDOVER * 2.0 * PI
                / (cascade == 0 ? u_patches.y : u_patches.z)));
    float omega = sqrt(GRAVITY * magnitude), theta = atan(k.y, k.x);
    // S(ω)·D(θ)·dω/dk / k: variance per unit wave-number area, times the cell's area.
    float sea = jonswap(omega, u_sea.x, u_sea.y) * donelanBanner(omega / u_sea.x, relative(theta, u_directions.x))
          + jonswap(omega, u_sea.z, u_sea.w) * swellSpread(relative(theta, u_directions.y));
    // Capillary ripples below a few centimetres stay in the static detail map.
    float variance = sea * (GRAVITY / (2.0 * omega)) / magnitude * cell * cell * share
          * exp(-magnitude * magnitude * .0004);
    // h0(k) and the mirrored conj(h0(-k)) both reach this wave vector: each carries half its variance.
    ivec2 texel = ivec2(cascade * u_size + index.x, index.y);
    return texelFetch(u_gauss, texel, 0).rg * (sqrt(variance * .25) * u_storm);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    if (u_blend >= 0.0) {
        fragColor = mix(texelFetch(u_from, texel, 0), texelFetch(u_to, texel, 0), u_blend);
        return;
    }
    int cascade = texel.x / u_size;
    ivec2 index = ivec2(texel.x - cascade * u_size, texel.y);
    ivec2 mirror = (ivec2(u_size) - index) % u_size;
    vec2 opposite = wave(cascade, mirror);
    fragColor = vec4(wave(cascade, index), opposite.x, -opposite.y);
}
