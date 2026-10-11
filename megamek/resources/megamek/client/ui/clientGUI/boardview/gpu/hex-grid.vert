#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One hexagon per tile (GpuHexGrid): the grid, or the Tactical View's ground layer with its style and arc codes.
in vec3 a_position;
in vec3 a_hex;
in vec4 a_style;
in vec4 a_overlay;
uniform mat4 u_projTrans;
uniform vec3 u_hexSize;
uniform int u_ground;
uniform vec2 u_planes;     // the ground layer's land plane, and the hex scale (one world unit of the art)
out vec2 v_board;
out vec2 v_local;
flat out ivec3 v_style;
flat out vec4 v_overlay;
flat out float v_level;
void main() {
    // Captured hex elevations also keep the overhead grid aligned with perspective projection.
    vec3 center = vec3(a_hex.x * .75 + .5, -(a_hex.y + mod(a_hex.x, 2.0) * .5 + .5), a_hex.z);
    vec3 position = (center + a_position) * u_hexSize;
    v_style = ivec3(a_style.xyz + .5);
    if (u_ground == 1) {
        // A liquid's marks lie a hair under its surface (BoardGeometry.waterZ), so a deck over it covers them; every
        // other mark lies on the plane above the board.
        bool liquid = (v_style.z & 8) != 0;
        position.z = liquid ? a_hex.z * u_hexSize.z - u_planes.y * 1.05 : u_planes.x;
    }
    v_board = position.xy / u_hexSize.x;
    v_local = a_position.xy * vec2(84.0, 72.0);
    v_overlay = a_overlay;
    v_level = a_hex.z;
    gl_Position = u_projTrans * vec4(position, 1.0);
}
