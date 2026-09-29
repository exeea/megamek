// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by geometry and optics. Local depth and room damp waves in shallows, narrow passages and at banks.
uniform sampler2D u_waterExposure;
uniform vec4 u_waterExposureMap;
// A weaker long swell crosses the main spectrum, breaking its short tile repetition. Geometry and normals use
// the same transform and weights; this is a visual superposition, not a second simulated ocean.
const mat2 WATER_SWELL_TURN = mat2(.8, .6, -.6, .8);
const float WATER_SWELL_SCALE = .43;
const float WATER_SWELL_WEIGHT = .38;
float waterWaveEnergy(vec2 world, vec4 field) {
    float shore = (field.r * 2.0 - 1.0) * 0.4;
    float depth = field.g * 4.0;
    vec4 fetch = textureLod(u_waterExposure, world * u_waterExposureMap.xy + u_waterExposureMap.zw, 0.0);
    vec2 wind = dot(u_wind.xy, u_wind.xy) > .0001 ? normalize(u_wind.xy) : vec2(.8, .6);
    vec4 direction = max(vec4(wind.x, -wind.x, wind.y, -wind.y), vec4(0.0));
    direction /= max(dot(direction, vec4(1.0)), .001);
    float upwind = dot(fetch, direction);
    float room = min(min(fetch.x, fetch.y), min(fetch.z, fetch.w));
    return smoothstep(0.02, 0.22, shore) * smoothstep(0.04, 1.1, depth)
          * smoothstep(0.0, .08, room) * mix(.2, 1.0, smoothstep(.025, .65, upwind));
}
