// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Rain motion and appearance. The shared particle stages supply wind, camera, fade and lighting.
#ifdef WEATHER_VERTEX
const float FALL_SPEED = 16.0;
const float SEED_OFFSET = 0.0;

vec2 particleDrift(vec3 seed) { return vec2(0.0); }

vec2 particleSize(float projectedSpeed) {
    return vec2(0.025 * u_level, max(0.12 * u_level, projectedSpeed * 0.028));
}
#else
vec4 particleAppearance(vec2 uv) {
    return vec4(0.62, 0.77, 0.91, particleDisc(uv) * 0.64);
}
#endif
