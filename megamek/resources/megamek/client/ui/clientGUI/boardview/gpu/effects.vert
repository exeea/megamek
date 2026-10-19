#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared quad geometry for particle and beam programs.
in vec3 a_position;
in vec2 a_texCoord0;
in vec2 a_texCoord1;
in vec4 a_color;
uniform mat4 u_projView;
out vec2 v_uv;
out vec2 v_effect;
out vec4 v_color;
void main() {
    v_uv = a_texCoord0;
    v_effect = a_texCoord1;
    v_color = a_color;
    gl_Position = u_projView * vec4(a_position, 1.0);
}
