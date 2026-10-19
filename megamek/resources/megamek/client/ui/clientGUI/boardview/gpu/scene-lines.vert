// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#version 330 core
in vec3 a_position;
in vec4 a_color;
uniform mat4 u_projModelView;
out vec4 v_color;
// COLOR_SPACE

void main() {
    gl_Position = u_projModelView * vec4(a_position, 1.0);
    // ShapeRenderer packs authored colors into bytes; decode after unpacking, before interpolation.
    v_color = vec4(toLinear(a_color.rgb), a_color.a * (255.0 / 254.0));
}
