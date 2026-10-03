// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Cosmetic relief only. One trace serves terrain, magma and roads; support, depth and cast shadows stay geometric.
// Live GPU Tuning checkbox: one uniform controls every material without replacing shaders or geometry.
uniform float u_parallaxMapping;

// Tangential travel per metre of relief along the actual surface normal. Project this into each texture domain,
// including its rotation/scale, instead of treating a sloping surface as a horizontal plane.
vec3 parallaxDirection(vec3 eye, vec3 face) {
    float facing = dot(eye, face);
    return (eye - face * facing) / max(facing, .18) * smoothstep(.03, .18, facing);
}

// UV travel expressed in screen pixels, including anisotropy. Derivatives are supplied from outside material
// branches: taking implicit derivatives during the march or after an early exit gives undefined mip selection.
float parallaxPixels(vec2 ray, vec2 dx, vec2 dy) {
    float determinant = dx.x * dy.y - dx.y * dy.x;
    if (abs(determinant) < 1e-12) return 0.0;
    return length(vec2(ray.x * dy.y - ray.y * dy.x, dx.x * ray.y - dx.y * ray.x) / determinant);
}

// Project a physical displacement into authored UVs without assuming an unrotated, level road/bridge.
vec2 parallaxProject(vec3 travel, vec3 dx, vec3 dy, vec2 uvDx, vec2 uvDy) {
    vec3 n = cross(dx, dy);
    float area = dot(n, n);
    if (area < 1e-16) return vec2(0.0);
    return (uvDx * dot(travel, cross(dy, n)) + uvDy * dot(travel, cross(n, dx))) / area;
}
