// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;
varying vec2 v_effect;
uniform vec3 u_light;
void main() {
    float radius = length(v_uv);
    float alpha;
    if (v_effect.x < 0.5) {
        alpha = (1.0 - smoothstep(0.04, 0.12, abs(radius - 0.85)));
        alpha *= 0.7 + 0.3 * sin(atan(v_uv.y, v_uv.x) * 23.0 + radius * 41.0);
    } else {
        alpha = 1.0 - smoothstep(0.25, 1.0, radius);
    }
    gl_FragColor = vec4(vec3(0.65, 0.83, 0.9) * u_light, alpha * v_effect.y);
}
