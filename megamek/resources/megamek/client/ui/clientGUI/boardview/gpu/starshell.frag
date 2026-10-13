#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// An illumination round's flare (a star shell), after the 2D board's flare: a white-hot point with a starburst of
// thin rays inside a soft rose halo, hanging under a small parachute and trailing a pale smoke wisp.
// Kind: 0 core, 1 halo, 2 ray (a streak from its base), 3 parachute canopy, 4 smoke puff. The canopy and smoke are
// alpha blended; the glows are additive.
in vec2 v_uv;
in vec2 v_effect;

void main() {
    float kind = v_effect.x;
    float alpha = v_effect.y;
    float radius = length(v_uv);
    if (kind > 3.5) {
        float puff = exp(-radius * radius * 3.0) * (1.0 - smoothstep(0.7, 1.0, radius));
        fragColor = vec4(vec3(0.78, 0.77, 0.75), puff * alpha);
    } else if (kind > 2.5) {
        // A dome above, its shroud lines gathering to the flare below; lit from underneath by the flare.
        vec2 dome = vec2(v_uv.x, (v_uv.y - 0.15) * 1.7);
        float canopy = (v_uv.y > 0.15) ? 1.0 - smoothstep(0.82, 0.9, length(dome)) : 0.0;
        float lines = 0.0;
        if (v_uv.y <= 0.15 && v_uv.y > -0.95) {
            float t = (v_uv.y + 0.95) / 1.1;
            for (int line = -2; line <= 2; line++) {
                float x = float(line) * 0.38 * t;
                lines = max(lines, 1.0 - smoothstep(0.015, 0.045, abs(v_uv.x - x)));
            }
        }
        float shade = mix(1.0, 0.8, clamp(v_uv.y, 0.0, 1.0));
        fragColor = vec4(vec3(0.86, 0.85, 0.82) * shade, max(canopy, lines * 0.6) * alpha);
    } else if (kind > 1.5) {
        float across = 1.0 - smoothstep(0.0, 1.0, abs(v_uv.x));
        float along = 1.0 - v_uv.y;
        fragColor = vec4(mix(vec3(1.0, 0.75, 0.75), vec3(1.0), along), across * across * along * alpha);
    } else if (kind > 0.5) {
        float halo = exp(-radius * radius * 3.0) * (1.0 - smoothstep(0.8, 1.0, radius));
        fragColor = vec4(vec3(1.0, 0.45, 0.45), halo * 0.55 * alpha);
    } else {
        float core = 1.0 - smoothstep(0.0, 0.35, radius);
        float glow = exp(-radius * radius * 6.0);
        fragColor = vec4(mix(vec3(1.0, 0.9, 0.85), vec3(1.0), core), (core + glow * 0.6) * alpha);
    }
}
