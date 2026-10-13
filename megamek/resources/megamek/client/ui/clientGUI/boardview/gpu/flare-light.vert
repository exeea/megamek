#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// A box around an illumination round's lit hexes; the fragment shader lights the scene surfaces inside it.
in vec3 a_position;
uniform mat4 u_projView;
uniform vec3 u_boxCenter;
uniform vec3 u_boxHalf;
void main() {
    gl_Position = u_projView * vec4(u_boxCenter + a_position * u_boxHalf, 1.0);
    // Keep coverage when the far plane cuts the box; each fragment reads the scene's own depth.
    gl_Position.z = min(gl_Position.z, gl_Position.w);
}
