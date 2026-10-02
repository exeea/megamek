#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec3 a_position;
in vec3 a_hex;
uniform mat4 u_projTrans;
uniform vec3 u_hexSize;
out vec2 v_board;
void main() {
    // Captured hex elevations also keep the overhead grid aligned with perspective projection.
    vec3 center = vec3(a_hex.x * .75 + .5, -(a_hex.y + mod(a_hex.x, 2.0) * .5 + .5), a_hex.z);
    vec3 position = (center + a_position) * u_hexSize;
    v_board = position.xy / u_hexSize.x;
    gl_Position = u_projTrans * vec4(position, 1.0);
}
