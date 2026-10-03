#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec2 v_uv;
uniform sampler2D u_source;
uniform vec2 u_step;

void main() {
    // Linear-filtered pairs implement a nine-texel Gaussian with five fetches per direction.
    vec3 radiance = texture(u_source, v_uv).rgb * 0.2270270270;
    radiance += texture(u_source, v_uv + u_step * 1.3846153846).rgb * 0.3162162162;
    radiance += texture(u_source, v_uv - u_step * 1.3846153846).rgb * 0.3162162162;
    radiance += texture(u_source, v_uv + u_step * 3.2307692308).rgb * 0.0702702703;
    radiance += texture(u_source, v_uv - u_step * 3.2307692308).rgb * 0.0702702703;
    fragColor = vec4(radiance, 1.0);
}
