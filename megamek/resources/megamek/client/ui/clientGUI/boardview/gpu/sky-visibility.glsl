// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// How much of the sky a point above the board sees past the board's own relief (GpuSkyVisibility): a volume over the
// board, read a cell out along the surface's normal, so a wall reads the air in front of it rather than the rock behind.
// Each cell holds the sky seen towards each quarter of the compass; a surface weighs the quarters it faces, so a wall
// does not count the sky it hides itself, and level ground takes all four alike. A strength of 0 leaves the sky light
// whole. Without skyVisibilityFlag it is the constant 1, which folds away.
#ifdef skyVisibilityFlag
uniform sampler3D u_skyVisibility; // RGBA: towards north-east, north-west, south-west, south-east
uniform vec3 u_skyVisibilityOrigin; // the volume's lowest corner, world units
uniform vec4 u_skyVisibilityScale; // xyz 1 / the volume's size in world units, w one cell's width
uniform float u_skyVisibilityStrength;

float skyVisibility(vec3 position, vec3 normal) {
    if (u_skyVisibilityStrength <= 0.0) return 1.0;
    vec3 uvw = (position + normal * u_skyVisibilityScale.w - u_skyVisibilityOrigin) * u_skyVisibilityScale.xyz;
    vec4 quarters = textureLod(u_skyVisibility, uvw, 0.0);
    vec4 facing = max(vec4(0.0), vec4(normal.x + normal.y, normal.y - normal.x, -normal.x - normal.y,
          normal.x - normal.y));
    float total = dot(facing, vec4(1.0));
    vec4 weights = total > 1e-4 ? mix(facing / total, vec4(.25), clamp(normal.z, 0.0, 1.0)) : vec4(.25);
    return mix(1.0, dot(quarters, weights), u_skyVisibilityStrength);
}
#else
float skyVisibility(vec3 position, vec3 normal) { return 1.0; }
#endif
