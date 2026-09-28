#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec4 a_position;
in vec4 a_color;
in vec2 a_texCoord0;
uniform mat4 u_projTrans;
out vec4 v_color;
out vec2 v_uv;
out vec2 v_board;
void main() {
    v_color = a_color;
    v_color.a *= 255.0 / 254.0;
    v_uv = a_texCoord0;
    v_board = a_position.xy;
    gl_Position = u_projTrans * a_position;
}
