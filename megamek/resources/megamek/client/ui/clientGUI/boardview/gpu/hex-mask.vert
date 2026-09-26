// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
attribute vec4 a_position;
attribute vec2 a_texCoord0;
uniform mat4 u_projTrans;
uniform sampler2D u_color;
uniform sampler2D u_parameters;
uniform vec4 u_board;
uniform float u_hexScale;
varying vec4 v_color;
varying vec2 v_local;
varying vec2 v_border;
void main() {
    vec2 uv = (a_texCoord0 + 0.5) / u_board.xy;
    vec4 parameters = texture2D(u_parameters, uv);
    v_color = texture2D(u_color, uv);
    v_border = parameters.xy;
    vec2 center = vec2((a_texCoord0.x * 0.75 + 0.5) * u_board.z,
          (a_texCoord0.y + mod(a_texCoord0.x, 2.0) * 0.5 + 0.5) * u_board.w);
    v_local = a_position.xy * vec2(1.0, -1.0) / u_hexScale - center;
    vec4 position = a_position;
    position.z += parameters.z;
    gl_Position = u_projTrans * position;
}
