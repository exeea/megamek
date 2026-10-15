#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// An illumination round's flare (a star shell), after the 2D board's flare: a white-hot point with a starburst of
// thin rays inside a soft rose halo, trailing a pale smoke wisp (its parachute is a 3D canopy, GpuFlareCanopies).
// Kind: 0 core, 1 halo, 2 ray (a streak from its base), 4 smoke puff. The smoke is alpha blended; the glows are
// additive.
in vec2 v_uv;
in vec2 v_effect;
// LIGHT_MODEL

void main() {
    float kind = v_effect.x;
    float alpha = v_effect.y;
    float radius = length(v_uv);
    if (kind > 3.5) {
        float puff = exp(-radius * radius * 3.0) * (1.0 - smoothstep(0.7, 1.0, radius));
        fragColor = vec4(toLinear(vec3(0.78, 0.77, 0.75)), puff * alpha);
    } else if (kind > 1.5) {
        float across = 1.0 - smoothstep(0.0, 1.0, abs(v_uv.x));
        float along = 1.0 - v_uv.y;
        fragColor = vec4(mix(toLinear(vec3(1.0, 0.75, 0.75)), toLinear(vec3(1.0)), along), across * across * along * alpha);
    } else if (kind > 0.5) {
        float halo = exp(-radius * radius * 3.0) * (1.0 - smoothstep(0.8, 1.0, radius));
        fragColor = vec4(toLinear(vec3(1.0, 0.45, 0.45)), halo * 0.55 * alpha);
    } else {
        float core = 1.0 - smoothstep(0.0, 0.35, radius);
        float glow = exp(-radius * radius * 6.0);
        fragColor = vec4(mix(toLinear(vec3(1.0, 0.9, 0.85)), toLinear(vec3(1.0)), core), (core + glow * 0.6) * alpha);
    }
}
