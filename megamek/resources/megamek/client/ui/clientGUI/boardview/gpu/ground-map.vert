#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The ground map's vertex stage (GpuGroundMap): the installed terrain seen straight down. With groundFlag the ground
// itself, which says what grows on it (ground-map.frag); else what lies on it (ground-map-cover.frag).
in vec3 a_position;
uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;
#ifdef groundFlag
in vec3 a_normal;
// A blended triangle's share of each family of its palette (GpuSurfaceBlend); elsewhere the constant (1, 0, 0, 0).
in vec4 a_coverWeights;
out vec3 v_world;
out vec3 v_normal;
out vec4 v_weights;
#else
in vec2 a_texCoord0;
in vec2 a_roadMaskUV;
in vec4 a_roadMaskRegion;
// Where its mask's alpha lies: 0 nowhere (it covers whole), 1 in its image through u_maskUV (paint), 2 in its road
// mask's region.
uniform int u_mask;
uniform vec4 u_maskUV;
// World units it is raised by: paint and water lie clear of their ground, an object's footprint is lowered onto it.
uniform float u_lift;
out vec2 v_maskUV;
// A road mask's layer in the board's mask array (GpuRoadMaskAtlas), carried in its region's U offset.
flat out float v_maskLayer;
// Where in its mask a road's point lies: outside 0..1 the coat draws nothing (terrain-road.frag), nor does its cover.
out vec2 v_roadMaskUV;
#endif

void main() {
    vec4 world = u_worldTrans * vec4(a_position, 1.0);
#ifdef groundFlag
    v_world = world.xyz;
    v_normal = mat3(u_worldTrans) * a_normal;
    v_weights = a_coverWeights;
#else
    world.z += u_lift;
    v_maskLayer = u_mask == 2 ? floor(a_roadMaskRegion.x) : 0.0;
    v_roadMaskUV = a_roadMaskUV;
    // A road mask's place carries its tint in the integer parts (GpuRoadMaskAtlas.Place).
    v_maskUV = u_mask == 2 ? vec2(a_roadMaskRegion.x - v_maskLayer, fract(a_roadMaskRegion.y)) + a_roadMaskUV * fract(a_roadMaskRegion.zw)
          : u_maskUV.xy + a_texCoord0 * u_maskUV.zw;
#endif
    gl_Position = u_projViewTrans * world;
}
