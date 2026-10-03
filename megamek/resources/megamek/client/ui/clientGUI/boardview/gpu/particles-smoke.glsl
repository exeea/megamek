// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Display-encoded scene light on smoke; fire and jets are emissive.
uniform vec3 u_light;

vec4 smokeParticle() {
    float lobes = 0.08 * sin(v_uv.x * 12.0) * sin(v_uv.y * 9.0);
    float shape = 1.0 - smoothstep(0.04, 1.0, dot(v_uv, v_uv) + lobes);
    vec3 smoke = mix(vec3(0.39, 0.43, 0.48), vec3(0.72, 0.75, 0.78), 0.5 + v_uv.y * 0.4);
    return vec4(smoke * u_light, shape * v_effect.y);
}
