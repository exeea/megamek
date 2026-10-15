// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// COLOR_SPACE
uniform float u_exposure;
uniform float u_lightning;
uniform vec3 u_tint;
uniform float u_saturation;
// User display adjustments. Zero offsets also keep isolated previews neutral without additional bindings.
uniform vec3 u_whiteBalanceShift;
uniform vec2 u_colorAdjust; // Saturation - 1, contrast - 1.

// One highlight shoulder for every surface and the sky, replacing the plain clip (the user's decision of 2026-09-24):
// the identity up to KNEE (display 202), so mid-tones and the tileset palette keep their authored values, then a
// smooth roll-off towards white per channel, so bright orange sand turns cream instead of clipping flat. The floating
// scene target preserves values above one: exposure can recover bright surface detail before this display conversion.
// Sources remain canonical; the composite applies shared adaptation, user EV compensation and lightning.
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
    // Apply to the complete image, including sky, weather and emissive heat, after the scenario's night grade.
    linear += linear * u_whiteBalanceShift;
    float luminance = dot(linear, vec3(0.2126, 0.7152, 0.0722));
    linear += (linear - vec3(luminance)) * u_colorAdjust.x;
    vec3 color = toDisplay(shoulder(linear));
    color = clamp(color + (color - 0.5) * u_colorAdjust.y, 0.0, 1.0);
    return color;
}
