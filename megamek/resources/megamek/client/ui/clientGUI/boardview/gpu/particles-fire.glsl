// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
float flameNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    vec4 h = sin(vec4(dot(cell, vec2(127.1, 311.7)),
                     dot(cell + vec2(1.0, 0.0), vec2(127.1, 311.7)),
                     dot(cell + vec2(0.0, 1.0), vec2(127.1, 311.7)),
                     dot(cell + vec2(1.0, 1.0), vec2(127.1, 311.7)))) * 43758.5453;
    h = fract(h);
    return mix(mix(h.x, h.y, f.x), mix(h.z, h.w, f.x), f.y);
}

vec4 fireParticle() {
    float age = fract(v_effect.x);
    float seed = floor(v_effect.x) * 7.13;
    vec2 p = v_uv;
    p.x += 0.14 * sin(p.y * 5.0 - age * 11.0 + seed);
    vec2 flow = p * vec2(3.1, 2.4) + vec2(seed, seed - age * 5.0);
    float noise = flameNoise(flow) * 0.65 + flameNoise(flow * 2.1 + seed) * 0.35;
    float radius = length(p * vec2(1.0, 0.86));
    float edge = 1.0 - smoothstep(0.40, 0.98, radius + (0.5 - noise) * 0.8);
    edge *= 1.0 - smoothstep(0.80, 1.0, max(abs(v_uv.x), abs(v_uv.y)));
    float heat = clamp(0.92 - age * 0.32 - radius * 0.43 + (noise - 0.5) * 0.55, 0.0, 1.0);
    vec3 flame = mix(vec3(0.85, 0.035, 0.002), vec3(1.0, 0.34, 0.015), smoothstep(0.12, 0.55, heat));
    flame = mix(flame, vec3(1.0, 0.88, 0.28), smoothstep(0.48, 0.86, heat));
    float core = (1.0 - smoothstep(0.0, 0.35, age)) * (1.0 - smoothstep(0.0, 0.38, radius));
    return vec4(mix(flame, vec3(1.0, 0.97, 0.72), core), edge * v_effect.y);
}
