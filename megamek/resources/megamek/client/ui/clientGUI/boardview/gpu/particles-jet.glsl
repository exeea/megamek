// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
vec4 jetParticle() {
    float taper = 0.85 * (1.0 - v_uv.y) + 0.04;
    float radius = abs(v_uv.x) / taper;
    float edge = 1.0 - smoothstep(0.05, 1.0, radius);
    float tip = 1.0 - smoothstep(0.55, 1.0, v_uv.y);
    float core = (1.0 - smoothstep(0.0, 0.4, radius)) * (1.0 - smoothstep(0.1, 0.65, v_uv.y));
    vec3 blue = mix(vec3(0.025, 0.22, 1.0), vec3(0.08, 0.72, 1.0), edge);
    return vec4(mix(blue, vec3(0.85, 0.96, 1.0), core), edge * tip * v_effect.y);
}
