#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// GpuElectronicWarfare: the terrain carriers of GpuHexSurface, each triangle tagged with its owner hex.
in vec4 a_position;
in vec2 a_texCoord0;
uniform mat4 u_projTrans;
uniform vec4 u_board;
uniform float u_hexScale;
flat out ivec2 v_cell;
out vec2 v_local;
void main() {
    v_cell = ivec2(a_texCoord0 + 0.5);
    vec2 center = vec2((a_texCoord0.x * 0.75 + 0.5) * u_board.z,
          (a_texCoord0.y + mod(a_texCoord0.x, 2.0) * 0.5 + 0.5) * u_board.w);
    // Board pixels from the owner's centre, y down, as the hex-mask pass measures them.
    v_local = a_position.xy * vec2(1.0, -1.0) / u_hexScale - center;
    gl_Position = u_projTrans * a_position;
}
