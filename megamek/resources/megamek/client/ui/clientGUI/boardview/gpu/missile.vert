#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec3 a_position;
in vec4 a_color;
uniform mat4 u_projView;
out vec4 v_color;
void main() { v_color = a_color; gl_Position = u_projView * vec4(a_position, 1.0); }
