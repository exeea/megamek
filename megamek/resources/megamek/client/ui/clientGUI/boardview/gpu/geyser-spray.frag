#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 0) out vec4 fragColor;
in vec2 v_uv;
in vec2 v_effect;
uniform vec3 u_light; // The terrain's linear daylight, shaded before the shared atmosphere composite.

float hash(vec2 p) { return fract(sin(dot(mod(p, 40.0), vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 a = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(a), hash(a + vec2(1, 0)), f.x), mix(hash(a + vec2(0, 1)), hash(a + 1.0), f.x), f.y);
}
float turbulence(vec2 p) { return noise(p) * .57 + noise(p * 2.0) * .28 + noise(p * 4.0) * .15; }

void main() {
    float kind = floor(v_effect.x / 100.0), seed = floor(mod(v_effect.x, 100.0)), age = fract(v_effect.x);
    vec2 p = v_uv;
    float radius = length(p), alpha = 0.0;
    vec3 color = vec3(.88, .94, .96);
    if (kind < .5) {
        float wave = .80 + .025 * sin(atan(p.y, p.x) * 13.0 + age * 6.283);
        alpha = (1.0 - smoothstep(.014, .075, abs(radius - wave))) * (.5 + .5 * noise(p * 24.0));
    } else if (kind < 1.5) {
        float t = clamp(p.y, 0.0, 1.0);
        vec2 flow = vec2(p.x * 3.0 + seed * 7.0, t * 16.0 - age * 40.0);
        float grains = turbulence(flow), streaks = noise(flow * vec2(2.5, .4));
        float edge = abs(p.x + .10 * sin(t * 24.0 + age * 6.283 + seed));
        alpha = (1.0 - smoothstep(.18 + .22 * t, .63 + .32 * t, edge));
        alpha *= smoothstep(0.0, .06, t) * (1.0 - smoothstep(.68, 1.0, t));
        alpha *= .36 + .64 * smoothstep(.18, .70, grains * .65 + streaks * .35);
        color = mix(vec3(.55, .71, .76), vec3(.99), grains);
    } else if (kind < 2.5) {
        vec2 curl = vec2(sin(p.y * 4.0 + age * 5.0 + seed), cos(p.x * 3.0 - age * 3.0));
        float vapour = turbulence(p * 3.5 + curl * .45 + vec2(seed * 3.0, -age * 2.5));
        alpha = (1.0 - smoothstep(.24, 1.0, radius)) * smoothstep(.20, .75, vapour);
        color = mix(vec3(.69, .76, .78), vec3(.98), vapour);
    } else if (kind < 3.5) {
        float tail = clamp(p.y, 0.0, 1.0);
        alpha = (1.0 - smoothstep(.10, .85, abs(p.x))) * sin(tail * 3.14159);
        color = vec3(.94, .98, 1.0);
    } else {
        float time = age * 6.283;
        float a = sin(p.x * 19.0 + p.y * 13.0 - time * 2.0);
        float b = sin(p.x * 11.0 - p.y * 23.0 + time * 3.0);
        float glint = pow(max(0.0, a * .55 + b * .45), 8.0);
        color = mix(vec3(.045, .22, .25), vec3(.76, .89, .9), glint);
        alpha = (1.0 - smoothstep(.74, 1.0, radius)) * (.26 + .5 * glint);
    }
    alpha *= v_effect.y;
    if (alpha < .002) discard;
    fragColor = vec4(pow(max(color * u_light, vec3(0.0)), vec3(1.0 / 2.2)), alpha);
}
