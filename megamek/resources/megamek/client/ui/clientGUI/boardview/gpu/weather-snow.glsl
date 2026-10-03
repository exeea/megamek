// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Snow motion and appearance. The shared particle stages supply wind, camera, fade and lighting.
#ifdef WEATHER_VERTEX
const float FALL_SPEED = 2.4;
const float SEED_OFFSET = 1.0;

vec2 particleDrift(vec3 seed) {
    return vec2(sin(u_clock * 1.3 + seed.z * 30.0), cos(u_clock + seed.x * 30.0)) * u_level * 0.18;
}

vec2 particleSize(float projectedSpeed) { return vec2(0.055 * u_level); }
#else
vec4 particleAppearance(vec2 uv) {
    return vec4(0.94, 0.97, 1.0, particleDisc(uv) * 0.72);
}
#endif
