// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
attribute vec3 a_position;
attribute vec4 a_color;
uniform mat4 u_projView;
varying vec4 v_color;
void main() { v_color = a_color; gl_Position = u_projView * vec4(a_position, 1.0); }
