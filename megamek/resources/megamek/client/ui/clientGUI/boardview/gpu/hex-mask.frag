// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
precision highp float;
#endif
uniform vec4 u_board;
varying vec4 v_color;
varying vec2 v_local;
varying vec2 v_border;
void main() {
    if (v_color.a <= 0.0) { discard; }
    // Whole-section tints cover the owning hex's terrain, including cliffs that extend outside its footprint.
    // Leave a margin: perspective interpolation can round a constant width slightly below its stored value.
    if (v_border.y > u_board.z * 0.5) { gl_FragColor = v_color; return; }
    // HexDrawUtilities builds a regular hex, then scales Y to the board's 84 x 72 pixel lattice.
    float apothem = u_board.z * 0.4330127018922193;
    vec2 p = abs(vec2(v_local.x, v_local.y * apothem / (u_board.w * 0.5)));
    float inset = apothem - max(p.y, p.x * 0.8660254037844386 + p.y * 0.5);
    if (inset < v_border.x || inset >= v_border.x + v_border.y) { discard; }
    gl_FragColor = v_color;
}
