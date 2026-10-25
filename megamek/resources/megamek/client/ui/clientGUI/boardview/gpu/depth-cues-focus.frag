#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The distance the view centres on (GpuDepthCues), into a single texel: the mean camera distance of the surfaces on a
// grid over the middle two fifths of the view, weighted towards its centre, less the orbit pivot's, eased from the
// previous frame's. Water stops a distance at its surface. With only sky there the previous focus stays.
in vec2 v_uv;
uniform sampler2D u_depth;
uniform sampler2D u_waterDepth;
uniform sampler2D u_previous; // R the previous focus
uniform float u_blend; // this frame's share; 1 starts afresh, without reading the previous focus
uniform float u_rangeOrigin;
// CAMERA_DEPTH

const int GRID = 16;

void main() {
    vec2 size = vec2(textureSize(u_depth, 0));
    vec2 sum = vec2(0.0);
    for (int y = 0; y < GRID; y++) {
        for (int x = 0; x < GRID; x++) {
            vec2 offset = (vec2(x, y) + .5) / float(GRID) - .5;
            ivec2 texel = ivec2((.5 + offset * .4) * size);
            float depth = min(texelFetch(u_depth, texel, 0).r, texelFetch(u_waterDepth, texel, 0).r);
            if (depth < 1.0) sum += vec2(cameraDepth(depth) - u_rangeOrigin, 1.0) * exp(-8.0 * dot(offset, offset));
        }
    }
    bool fresh = u_blend >= 1.0;
    float previous = fresh ? 0.0 : texelFetch(u_previous, ivec2(0), 0).r;
    float measured = sum.g > 1e-4 ? sum.r / sum.g : previous;
    fragColor = vec4(fresh ? measured : mix(previous, measured, u_blend), 1.0, 0.0, 1.0);
}
