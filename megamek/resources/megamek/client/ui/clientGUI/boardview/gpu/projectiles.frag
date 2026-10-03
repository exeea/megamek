#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec2 v_uv;
in vec2 v_effect;
// Integer kind: tracer, energy trail, plasma glow, flare, spray, screen, physical contact, impact spark.
// Fractional phase and opacity come from the shared attack clock; no wall-clock animation.

void main() {
    float kind = floor(v_effect.x);
    float phase = fract(v_effect.x);
    vec3 color;
    float alpha;
    if (kind < 1.5 || kind > 6.5) {
        // A bright narrow core, soft halo and rounded ends replace the tapered solid sphere.
        float cap = sqrt(max(0.0, 1.0 - pow(v_uv.y * 2.0 - 1.0, 2.0)));
        float radius = abs(v_uv.x) / max(0.02, cap);
        float core = 1.0 - smoothstep(0.08, 0.35, radius);
        float halo = exp(-radius * radius * 5.0) * (1.0 - smoothstep(0.7, 1.0, radius));
        bool warm = kind < 0.5 || kind > 6.5;
        vec3 edge = warm ? vec3(1.0, 0.32, 0.025) : vec3(0.015, 0.5, 1.0);
        vec3 hot = warm ? vec3(1.0, 0.95, 0.65) : vec3(0.7, 0.98, 1.0);
        if (kind > 6.5) {
            core = 1.0 - smoothstep(0.12, 0.5, radius);
            edge = vec3(1.4, 0.4, 0.025);
            hot = mix(vec3(4.0, 3.6, 1.7), vec3(2.0, 0.7, 0.04), phase);
        }
        color = mix(edge, hot, core);
        alpha = max(core, halo * 0.5) * smoothstep(0.0, 0.08, v_uv.y)
              * (1.0 - smoothstep(0.92, 1.0, v_uv.y));
        if (kind > 6.5) { alpha *= mix(0.25, 1.0, v_uv.y); }
    } else {
        float radius = length(v_uv);
        float edge = 1.0 - smoothstep(0.8, 1.0, radius);
        float halo = exp(-radius * radius * 4.5) * edge;
        float core = 1.0 - smoothstep(0.06, 0.33, radius);
        if (kind < 2.5) {
            float roll = sin(v_uv.x * 13.0 + phase * 16.0) * sin(v_uv.y * 11.0 - phase * 13.0);
            float shell = pow(max(0.0, sin(radius * 28.0 - phase * 18.0 + roll * 2.0)), 6.0);
            shell *= smoothstep(0.2, 0.4, radius) * (1.0 - smoothstep(0.65, 0.9, radius));
            color = mix(vec3(0.015, 0.48, 1.0), vec3(0.82, 1.0, 1.0), max(core, shell * 0.6));
            alpha = max(core, halo * 0.3 + shell * 0.45);
        } else if (kind < 3.5) {
            float rays = exp(-min(abs(v_uv.x), abs(v_uv.y)) * 36.0) * halo;
            color = mix(vec3(1.0, 0.5, 0.1), vec3(1.0, 0.99, 0.88), max(core, rays));
            alpha = max(core, halo * 0.32 + rays * 0.48);
        } else if (kind < 5.5) {
            float billow = sin(v_uv.x * 9.0 + phase * 6.0) * sin(v_uv.y * 7.0 - phase * 4.0);
            alpha = (1.0 - smoothstep(0.25, 0.95, radius + billow * 0.13)) * edge;
            color = kind < 4.5 ? vec3(0.38, 0.69, 0.88) : vec3(0.64, 0.14, 0.85);
            alpha *= 0.6;
        } else {
            float angle = radius > 0.0001 ? atan(v_uv.y, v_uv.x) : 0.0;
            float sparks = pow(abs(sin(angle * 5.0 + phase * 2.0)), 14.0);
            color = mix(vec3(0.65, 0.42, 0.18), vec3(1.0, 0.96, 0.8), core);
            alpha = max(core, halo * (0.15 + sparks * 0.6));
        }
    }
    fragColor = vec4(color, alpha * v_effect.y);
}
