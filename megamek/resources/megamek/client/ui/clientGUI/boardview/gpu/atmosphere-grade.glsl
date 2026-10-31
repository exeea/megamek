// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// COLOR_SPACE
uniform float u_exposure;
uniform float u_lightning;
uniform vec3 u_tint;
uniform float u_saturation;
uniform float u_nightVision; // rod vision's share in dim light (nightVision): none by day, most at full night
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

// The authored UI palette includes its historical gamma-2.2 EV/shoulder response. Map that display target
// into the current scene's linear domain, undoing only our final shoulder so it is applied exactly once later.
// This never transforms scene samples: surfaces, particles, weather and their alpha stay scene-linear.
vec3 backdropRadiance(vec3 authored, float exposure) {
    vec3 target = toLinear(pow(shoulder(pow(max(authored, vec3(0.0)), vec3(2.2)) * exposure), vec3(1.0 / 2.2)));
    vec3 above = vec3(KNEE) - (1.0 - KNEE) * log(max((1.0 - target) / (1.0 - KNEE), vec3(1e-7)));
    return mix(target, above, step(vec3(KNEE), target));
}

// Night vision (the Purkinje shift): in dim light the eye's rods take over, which see no colour and favour blue-green.
// The night stays as bright as the light model makes it, for a readable board: a dim exposed pixel turns toward a cool
// blue-grey at its own luminance, while one with any bright channel (fires, lamps, flashes, coloured emitters) keeps
// the cones' colour.
vec3 nightVision(vec3 linear, float share) {
    linear = max(linear, vec3(0.0));
    float luminance = dot(linear, vec3(0.2126, 0.7152, 0.0722));
    share *= 1.0 - smoothstep(.05, .25, max(linear.r, max(linear.g, linear.b)));
    if (share <= 0.0) return linear;
    vec3 shifted = mix(linear, dot(linear, vec3(.04, .46, .50)) * vec3(.72, .90, 1.2), share);
    return shifted * (luminance / max(dot(shifted, vec3(0.2126, 0.7152, 0.0722)), 1e-6));
}

vec3 gradeScene(vec3 linear, float coverage) {
    linear *= u_exposure * (1.0 + u_lightning);
    // Sky colors already carry the time/cover palette; grade in proportion to scene coverage.
    vec3 graded = linear * u_tint;
    float luminance = dot(graded, vec3(0.2126, 0.7152, 0.0722));
    linear = mix(linear, mix(vec3(luminance), graded, u_saturation), coverage);
    return nightVision(linear, u_nightVision * coverage);
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
