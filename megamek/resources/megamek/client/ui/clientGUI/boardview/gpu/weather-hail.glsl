// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Hail motion and appearance. The shared particle stages supply wind, camera, fade and lighting.
#ifdef WEATHER_VERTEX
const float FALL_SPEED = 10.0;
const float SEED_OFFSET = 2.0;

vec2 particleDrift(vec3 seed) { return vec2(0.0); }

vec2 particleSize(float projectedSpeed) { return vec2(0.065 * u_level); }
#else
vec4 particleAppearance(vec2 uv) {
    return vec4(0.94, 0.97, 1.0, particleDisc(uv) * 0.65);
}
#endif
