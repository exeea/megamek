// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
uniform float u_exposure;
uniform float u_lightning;
uniform vec3 u_tint;
uniform float u_saturation;

// One highlight shoulder for every surface and the sky, replacing the plain clip (the user's decision of 2026-09-24):
// the identity up to KNEE (display 202), so mid-tones and the tileset palette keep their authored values, then a
// smooth roll-off towards white per channel, so bright orange sand turns cream instead of clipping flat. The floating
// scene target preserves values above one: exposure can recover bright surface detail before this display conversion.
// BoardAtmosphere already supplies adapted light; the composite applies only user EV compensation and lightning.
const float KNEE = 0.6;
vec3 shoulder(vec3 c) {
    c = max(c, vec3(0.0));
    return min(c, vec3(KNEE)) + (1.0 - KNEE) * (1.0 - exp(-max(c - KNEE, vec3(0.0)) / (1.0 - KNEE)));
}

vec3 gradeScene(vec3 linear, float coverage) {
    linear *= u_exposure * (1.0 + u_lightning);
    // Sky colors already carry the time/cover palette; grade in proportion to scene coverage.
    vec3 graded = linear * u_tint;
    float luminance = dot(graded, vec3(0.2126, 0.7152, 0.0722));
    linear = mix(linear, mix(vec3(luminance), graded, u_saturation), coverage);
    return linear;
}

vec3 displayScene(vec3 linear) {
    vec3 color = pow(shoulder(linear), vec3(1.0 / 2.2));
    vec2 edge = (v_uv - 0.5) * 2.0;
    color *= 1.0 - 0.09 * dot(edge, edge) * 0.5;
    return color;
}
