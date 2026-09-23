// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The ocean's spectrum at one instant (GpuOcean). Each wave of the initial spectrum turns at the frequency deep water
// gives its length, so long swells outrun short chop. Out come two complex fields that the inverse FFT turns into
// four real ones: the slopes along x and y in red and green, the horizontal displacement along x and y in blue and
// alpha.
uniform sampler2D u_initial; // h0(k) in red and green, conj(h0(-k)) in blue and alpha
uniform float u_time;
uniform float u_loop;        // every frequency is a multiple of this, so the clock can wrap without a jump
uniform float u_patch;       // metres the waves repeat over
uniform int u_size;

vec2 multiply(vec2 a, vec2 b) {
    return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 initial = texelFetch(u_initial, texel, 0);
    vec2 k = 6.2831853 * (vec2(texel) - 0.5 * float(u_size)) / u_patch;
    float magnitude = max(length(k), 1e-6);
    float phase = floor(sqrt(9.81 * magnitude) / u_loop) * u_loop * u_time;
    vec2 turn = vec2(cos(phase), sin(phase));
    vec2 height = multiply(initial.xy, turn) + multiply(initial.zw, vec2(turn.x, -turn.y));
    vec2 rotated = vec2(-height.y, height.x);
    vec2 slopeX = rotated * k.x, slopeY = rotated * k.y;
    vec2 shiftX = -rotated * (k.x / magnitude), shiftY = -rotated * (k.y / magnitude);
    // a + ib packs two fields whose spectra are Hermitian: after the transform, a is the real part and b the imaginary.
    gl_FragColor = vec4(slopeX.x - slopeY.y, slopeX.y + slopeY.x, shiftX.x - shiftY.y, shiftX.y + shiftY.x);
}
