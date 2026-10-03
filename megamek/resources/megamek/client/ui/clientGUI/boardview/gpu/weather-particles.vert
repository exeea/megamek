#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec3 a_position;
in vec2 a_texCoord0;
uniform mat4 u_projView;
uniform vec3 u_origin;
uniform vec3 u_extent;
uniform vec3 u_right;
uniform vec3 u_up;
uniform vec2 u_wind;
uniform float u_clock;
uniform float u_level;
out vec2 v_uv;
out float v_fade;

#define WEATHER_VERTEX
// WEATHER_CONDITION

void main() {
    float speed = FALL_SPEED * u_level;
    vec3 seed = fract(a_position + vec3(0.173, 0.371, 0.619) * SEED_OFFSET);
    // World-anchored motion within a wrapping, camera-bounded volume.
    vec3 center;
    center.xy = u_origin.xy + mod(seed.xy * u_extent.xy - u_origin.xy + u_wind * u_level * u_clock, u_extent.xy);
    float height = fract(seed.z - u_clock * speed / u_extent.z);
    center.z = u_origin.z + height * u_extent.z;
    vec3 velocity = vec3(u_wind * u_level, -speed);
    float fade = smoothstep(0.0, 0.12, height) * (1.0 - smoothstep(0.85, 1.0, height));
    center.xy += particleDrift(seed);
    vec2 projected = vec2(dot(velocity, u_right), dot(velocity, u_up));
    float projectedLength = length(projected);
    vec2 axis = projectedLength > 0.001 ? projected / projectedLength : vec2(0.0, -1.0);
    vec3 along = u_right * axis.x + u_up * axis.y;
    vec3 across = u_right * -axis.y + u_up * axis.x;
    vec2 size = particleSize(projectedLength);
    vec3 position = center + across * a_texCoord0.x * size.x + along * a_texCoord0.y * size.y;
    v_uv = a_texCoord0;
    v_fade = fade;
    gl_Position = u_projView * vec4(position, 1.0);
}
