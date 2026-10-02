#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
#extension GL_EXT_frag_depth : require
precision highp float;
#define DEPTH gl_FragDepthEXT
#else
#define DEPTH gl_FragDepth
#endif
in vec4 v_color;
in vec2 v_uv;
in vec2 v_board;
uniform sampler2D u_texture;
uniform sampler2D u_depth;
uniform sampler2D u_units;
uniform vec2 u_unitOptions; // This frame has a borrowed unit capture; matching-depth tolerance.
uniform vec4 u_viewport;
uniform vec2 u_surface; // Captured label plane and its own tile's maximum decorative height.
uniform int u_overlay; // 0: ground relief only; 1: HEIGHT through its own hex; 2: top-view ground overlay.
// GROUND_LAYER
void main() {
    fragColor = v_color * texture(u_texture, v_uv);
    if (u_overlay == 2) {
        DEPTH = 0.0;
        return;
    }
    vec2 screen = (gl_FragCoord.xy - u_viewport.xy) / u_viewport.zw;
    float depth = texture(u_depth, screen).r;
    vec4 reconstructed = u_inverseView * vec4(screen * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 position = reconstructed.xyz / reconstructed.w;
    vec3 normal = cross(dFdx(position), dFdy(position));
    normal /= max(length(normal), 0.000001);
    // A cliff lies on a shared edge. Move inside the visible solid before deciding who owns it.
    vec2 inside = (position - normal * (min(u_groundBoard.z, u_groundBoard.w) * 0.002)).xy;
    vec2 occluder = boardHex(inside * vec2(1.0, -1.0) / u_groundBoard.zw);
    vec2 owner = boardHex(v_board * vec2(1.0, -1.0) / u_groundBoard.zw);
    bool ownHex = depth < 1.0 && distance(owner, occluder) < 0.1;
    float unit = u_unitOptions.x > 0.5 ? texture(u_units, screen).r : 1.0;
    bool onUnit = unit < 1.0 && behind(depth, unit, u_unitOptions.y) < 0.5;
    bool decoration = !onUnit && u_surface.y > 0.0 && ownHex
          && position.z >= u_surface.x - u_groundBoard.z * 0.001
          && position.z <= u_surface.x + u_surface.y;
    // HEIGHT can pierce any local content, including units, but other hexes still occlude it.
    DEPTH = (u_overlay == 1 && ownHex) || decoration ? min(gl_FragCoord.z, depth) : gl_FragCoord.z;
}
