#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team.
// SPDX-License-Identifier: GPL-3.0-or-later


uniform vec3 u_light;

in vec2 v_uv;
in float v_kind;
in float v_fade;

void main() {
    bool rain = v_kind < 0.5;
    bool snow = v_kind > 0.5 && v_kind < 1.5;

    float dist2 = dot(v_uv, v_uv);

    float shape = 1.0 - smoothstep(0.15, 1.0, dist2);

    float opacity = rain ? 0.64 : snow ? 0.72 : 0.65;

    vec3 color = rain
        ? vec3(0.62, 0.77, 0.91)
        : vec3(0.94, 0.97, 1.0);

    fragColor = vec4(color * u_light, shape * opacity * v_fade);
}
