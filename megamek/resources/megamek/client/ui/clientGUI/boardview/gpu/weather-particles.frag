#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team.
// SPDX-License-Identifier: GPL-3.0-or-later


uniform vec3 u_light;

in vec2 v_uv;
in float v_fade;

float particleDisc(vec2 uv) {
    return 1.0 - smoothstep(0.15, 1.0, dot(uv, uv));
}

// WEATHER_CONDITION

void main() {
    vec4 particle = particleAppearance(v_uv);
    vec3 color = particle.rgb;
    fragColor = vec4(color * u_light, particle.a * v_fade);
}
