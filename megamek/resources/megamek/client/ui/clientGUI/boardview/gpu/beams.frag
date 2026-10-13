#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec2 v_uv;
in vec2 v_effect;
// Kind: 0 laser, 1 PPC beam/arc, 2 PPC charge/impact glow, 3 TAG designator line, 4 TAG designator spot. Alpha is
// driven by the shared attack clock.

void main() {
    if (v_effect.x > 3.5) {
        // The designator's spot: a small hot red point with a soft red bloom around it.
        float radius = length(v_uv);
        float core = 1.0 - smoothstep(0.05, 0.18, radius);
        float halo = exp(-radius * radius * 7.0) * (1.0 - smoothstep(0.7, 1.0, radius));
        vec3 color = mix(vec3(1.0, 0.02, 0.01), vec3(1.0, 0.55, 0.45), core);
        fragColor = vec4(color, max(core, halo * 0.75) * v_effect.y);
    } else if (v_effect.x > 2.5) {
        // The designator's line: thin, deep red and even, with none of a weapon laser's white-hot core.
        float edge = 1.0 - smoothstep(0.35, 1.0, abs(v_uv.x));
        float core = 1.0 - smoothstep(0.0, 0.3, abs(v_uv.x));
        fragColor = vec4(mix(vec3(0.85, 0.0, 0.0), vec3(1.0, 0.18, 0.12), core), edge * 0.9 * v_effect.y);
    } else if (v_effect.x > 1.5) {
        float radius = length(v_uv);
        float core = 1.0 - smoothstep(0.08, 0.32, radius);
        float halo = exp(-radius * radius * 5.0) * (1.0 - smoothstep(0.7, 1.0, radius));
        vec3 color = mix(vec3(0.08, 0.45, 1.0), vec3(0.92, 0.98, 1.0), core);
        fragColor = vec4(color, max(core, halo * 0.8) * v_effect.y);
    } else if (v_effect.x > 0.5) {
        float radius = abs(v_uv.x);
        float core = 1.0 - smoothstep(0.08, 0.28, radius);
        float halo = exp(-radius * radius * 6.0) * (1.0 - smoothstep(0.7, 1.0, radius));
        vec3 color = mix(vec3(0.06, 0.38, 1.0), vec3(0.92, 0.98, 1.0), core);
        fragColor = vec4(color, max(core, halo * 0.7) * v_effect.y);
    } else {
        float edge = 1.0 - smoothstep(0.25, 1.0, abs(v_uv.x));
        float core = 1.0 - smoothstep(0.0, 0.4, abs(v_uv.x));
        fragColor = vec4(mix(vec3(1.0, 0.015, 0.005), vec3(1.0, 0.65, 0.45), core), edge * v_effect.y);
    }
}
