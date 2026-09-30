#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The ocean's spectrum at one instant (GpuOcean), for every cascade side by side. Each wave of the initial spectrum
// turns at the frequency deep water gives its length, so long swells outrun short chop. Water packs
// height + i*displacementX, then displacementY; lava retains slopeX + i*slopeY, then displacementX + i*displacementY.
// The same inverse transform handles both.
uniform sampler2D u_from;    // h0(k) in red and green, conj(h0(-k)) in blue and alpha: the sea a change of wind
uniform sampler2D u_to;      // left, and the one it is heading for
uniform float u_blend;       // eased progress from one to the other; every wave keeps its phase throughout
uniform float u_time;
uniform float u_loop;        // every frequency is a multiple of this, so the clock can wrap without a jump
uniform float u_gravity;     // scenario acceleration, metres per second squared
uniform vec3 u_patches;      // metres each cascade repeats over
uniform int u_size;          // texels along a side of one cascade
uniform bool u_water;

vec2 multiply(vec2 a, vec2 b) {
    return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int cascade = texel.x / u_size;
    float extent = cascade == 0 ? u_patches.x : cascade == 1 ? u_patches.y : u_patches.z;
    vec4 initial = mix(texelFetch(u_from, texel, 0), texelFetch(u_to, texel, 0), u_blend);
    vec2 k = 6.2831853 * (vec2(texel.x - cascade * u_size, texel.y) - 0.5 * float(u_size)) / extent;
    float magnitude = max(length(k), 1e-6);
    float phase = floor(sqrt(u_gravity * magnitude) / u_loop) * u_loop * u_time;
    // h0(k) turns clockwise, so its crests travel along k: the wind's direction, not against it.
    vec2 turn = vec2(cos(phase), sin(phase));
    vec2 height = multiply(initial.xy, vec2(turn.x, -turn.y)) + multiply(initial.zw, turn);
    vec2 rotated = vec2(-height.y, height.x);
    // Water's horizontal displacement is i*k/|k|*h: surface points gather toward each crest, sharpening it and
    // broadening the trough between. Lava keeps its original sense; its folds are not wind waves.
    vec2 shiftX = rotated * (k.x / magnitude), shiftY = rotated * (k.y / magnitude);
    // a + ib packs two fields whose spectra are Hermitian: after the transform, a is the real part and b the imaginary.
    if (u_water) {
        fragColor = vec4(height.x - shiftX.y, height.y + shiftX.x, shiftY);
    } else {
        vec2 slopeX = rotated * k.x, slopeY = rotated * k.y;
        fragColor = vec4(slopeX.x - slopeY.y, slopeX.y + slopeY.x, -shiftX.x + shiftY.y, -shiftX.y - shiftY.x);
    }
}
