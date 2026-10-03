#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One radix-2 stage of the ocean's inverse FFT (GpuOcean), along x or y, on two complex values per texel. Cascades
// lie side by side along x, so a horizontal stage stays inside its own cascade's block. The first stage reads its
// input in bit-reversed order; each later stage joins two transforms of half its length.
uniform sampler2D u_source;
uniform int u_stage;
uniform int u_vertical;
uniform int u_bits;

vec2 multiply(vec2 a, vec2 b) {
    return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

int reversed(int value) {
    int result = 0;
    for (int i = 0; i < 16; i++) {
        if (i >= u_bits) break;
        result = (result << 1) | (value & 1);
        value >>= 1;
    }
    return result;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int size = 1 << u_bits;
    int index = u_vertical == 1 ? texel.y : texel.x & (size - 1);
    int block = u_vertical == 1 ? 0 : texel.x - index;
    int span = 1 << u_stage;
    int offset = index % (2 * span);
    bool upper = offset >= span;
    int top = upper ? offset - span : offset;
    int a = index - offset + top, b = a + span;
    if (u_stage == 0) {
        a = reversed(a);
        b = reversed(b);
    }
    vec4 even = texelFetch(u_source, u_vertical == 1 ? ivec2(texel.x, a) : ivec2(block + a, texel.y), 0);
    vec4 odd = texelFetch(u_source, u_vertical == 1 ? ivec2(texel.x, b) : ivec2(block + b, texel.y), 0);
    float angle = 3.14159265 * float(top) / float(span);
    vec2 twiddle = vec2(cos(angle), sin(angle));
    vec4 turned = vec4(multiply(twiddle, odd.xy), multiply(twiddle, odd.zw));
    fragColor = upper ? even - turned : even + turned;
}
