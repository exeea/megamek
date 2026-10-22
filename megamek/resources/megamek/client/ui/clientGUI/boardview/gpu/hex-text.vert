#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec4 a_position;
in vec4 a_color;
in vec2 a_texCoord0;
in float a_contrast; // 1: adaptive fill; 2: white fill; negative: its ring; 0: authored color.
in vec3 a_hex; // Owning column, row and tile elevation; independent of glyph/roof position.
uniform mat4 u_projTrans;
out vec4 v_color;
out vec2 v_uv;
out vec2 v_board;
flat out float v_ink;
// OVERLAY_CONTRAST
void main() {
    v_color = a_color;
    v_ink = -1.0;
    if (a_contrast != 0.0 && (abs(a_contrast) > 1.5 || u_contrastEnabled != 0.0)) {
        float ink = abs(a_contrast) > 1.5 || u_contrastEnabled < 0.0 ? 1.0 : overlayInk(a_hex.xy);
        v_ink = a_contrast > 0.0 ? ink : 1.0 - ink;
    }
    v_color.a *= 255.0 / 254.0;
    v_uv = a_texCoord0;
    v_board = a_position.xy;
    gl_Position = u_projTrans * a_position;
}
