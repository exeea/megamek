#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Low-atmosphere sky cells keep their continuous native sky artwork. SPACE has no rendered plane.
in vec3 v_cloudPosition;
uniform float u_backdropHexWidth;
layout(location = 0) out vec4 fragColor;

float backdropHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float backdropNoise(vec2 p) {
    vec2 cell = floor(p), t = fract(p);
    t = t * t * (3.0 - 2.0 * t);
    return mix(mix(backdropHash(cell), backdropHash(cell + vec2(1, 0)), t.x),
          mix(backdropHash(cell + vec2(0, 1)), backdropHash(cell + vec2(1, 1)), t.x), t.y);
}

void main() {
    vec2 board = v_cloudPosition.xy / max(u_backdropHexWidth, 0.001);
    float cloud = backdropNoise(board * 0.22) * 0.7 + backdropNoise(board * 0.61) * 0.3;
    vec3 sky = mix(vec3(0.16, 0.34, 0.59), vec3(0.7, 0.77, 0.83),
          smoothstep(0.48, 0.82, cloud) * 0.75);
    fragColor = vec4(sky, 1.0);
}
