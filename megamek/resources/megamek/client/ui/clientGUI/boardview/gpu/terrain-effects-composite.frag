#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
uniform sampler2D u_depth;
uniform sampler2D u_effect;
uniform vec2 u_size;
uniform vec4 u_viewport;
uniform mat4 u_inverseProjView;

vec3 position(vec2 uv) {
    vec4 p = u_inverseProjView * vec4(uv * 2.0 - 1.0, texture(u_depth, uv).r * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec2 uv = (gl_FragCoord.xy - u_viewport.xy) / u_viewport.zw;
    vec2 pixel = uv * u_size - 0.5, base = floor(pixel), f = fract(pixel);
    vec3 surface = position(uv);
    vec3 dx = dFdx(surface), dy = dFdy(surface);
    vec3 normal = normalize(cross(dx, dy) + vec3(0.000001));
    float tolerance = max(0.05, min(length(dx), length(dy)) * 2.0);
    vec4 result = vec4(0.0);
    float total = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec2 corner = vec2(float(x), float(y));
            vec2 sampleUv = (base + corner + 0.5) / u_size;
            vec2 blend = mix(vec2(1.0) - f, f, corner);
            // Compare to the local opaque plane, preserving sloping ground without leaking over unit silhouettes.
            float separation = abs(dot(position(sampleUv) - surface, normal));
            float weight = blend.x * blend.y * exp(-separation / tolerance);
            result += texture(u_effect, sampleUv) * weight;
            total += weight;
        }
    }
    fragColor = total > 0.0001 ? result / total : vec4(0.0);
}
