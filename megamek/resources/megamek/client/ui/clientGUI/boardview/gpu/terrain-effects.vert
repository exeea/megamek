// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
attribute vec3 a_position;
attribute vec4 a_origin; // world base and horizontal radius
attribute vec4 a_shape; // height, flame, density, palette
attribute float a_lod;
uniform mat4 u_projView;
uniform vec3 u_wind;
uniform float u_spread;
varying vec4 v_origin;
varying vec4 v_shape;
varying float v_lod;
void main() {
    v_origin = a_origin;
    v_shape = a_shape;
    v_lod = a_lod;
    // A widening frustum bounds the smoke. The margin contains its curved wind trajectory:
    // max(z - z * (0.25 + 0.75 * z)) = 0.1875 for normalized height z in [0, 1].
    vec2 radius = vec2(a_origin.w * (1.0 + u_spread * a_position.z))
          + abs(u_wind.xy) * a_shape.x * 0.1875;
    vec3 p = vec3(a_position.xy * radius, a_position.z * a_shape.x);
    p.xy += u_wind.xy * p.z;
    gl_Position = u_projView * vec4(a_origin.xyz + p, 1.0);
    gl_Position.z = min(gl_Position.z, gl_Position.w);
}
