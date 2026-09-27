// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
attribute vec3 a_position;
uniform mat4 u_projView;
uniform vec4 u_centerRadius;
uniform vec3 u_wind;
uniform float u_stretch;
void main() {
    // A reusable unit cube provides coverage; the ray-marched density defines the visible shape.
    vec3 local = a_position * u_centerRadius.w;
    local.xy += u_wind.xy * dot(local.xy, u_wind.xy) * u_stretch;
    vec3 world = u_centerRadius.xyz + local;
    gl_Position = u_projView * vec4(world, 1.0);
    // Keep coverage when the camera's far plane cuts the cube. The fragment shader clips the actual ray.
    gl_Position.z = min(gl_Position.z, gl_Position.w);
}
