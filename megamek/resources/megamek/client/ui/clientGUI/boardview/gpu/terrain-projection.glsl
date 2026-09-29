// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared world-space texture projection and normal reconstruction.
// The second repeat of every map is 2.37 times larger and turned by 34 degrees.
const mat2 TURN = mat2(.8253, .5646, -.5646, .8253);


// ---- Material sampling -------------------------------------------------------------------------------------------

// Height-based blend: where both layers are wanted, the one whose relief stands higher shows.
float heightBlend(float lower, float upper, float want) {
    float a = lower + 1.0 - want, b = upper + want;
    float top = max(a, b) - .2;
    float wa = max(a - top, 0.0), wb = max(b - top, 0.0);
    return wb / (wa + wb);
}

// How far ground detail has faded towards its average colour: from about 18 cm per pixel, where a map's repeats
// would start to beat against each other, to 60 cm; a fifth of the detail remains. Set once per fragment in main().
float farDetail;

// A map on the ground plane at two repeats, mixed by a broad field, so no period shows. p is in metres with +V
// pointing to world -Y, as the maps are authored. From afar it settles to the map's average (its last mip level).
vec4 planar(sampler2D map, vec2 p, float tile, float mixer) {
    vec4 near = mix(texture(map, p / tile), texture(map, TURN * p / (tile * 2.37) + .31), mixer);
    return farDetail > 0.0 ? mix(near, texture(map, p / tile, 12.0), farDetail) : near;
}

// The matching tangent-space normal (x along +U, y along +V); the turned sample is turned back.
vec4 planarNormal(sampler2D map, vec2 p, float tile, float mixer) {
    vec4 near = texture(map, p / tile);
    vec4 far = texture(map, TURN * p / (tile * 2.37) + .31);
    vec3 a = near.rgb * 2.0 - 1.0, b = far.rgb * 2.0 - 1.0;
    b.xy = b.xy * TURN;
    vec4 result = vec4(mix(a, b, mixer), mix(near.a, far.a, mixer));
    return mix(result, vec4(0.0, 0.0, 1.0, result.a), farDetail);
}

// Tangent-space detail on a surface facing up: U is world +X, V is world -Y.
vec3 upNormal(vec3 detail, vec3 face) {
    return normalize(vec3(detail.x + face.x, face.y - detail.y, detail.z * face.z));
}

// A wall map's two vertical projections (U along the face, V down it), whiteout-blended with the face normal and
// expressed in world space. side weights the projection onto the YZ plane.
vec3 wallNormal(sampler2D map, vec2 uvx, vec2 uvy, vec3 face, float side) {
    vec3 nx = texture(map, uvx).rgb * 2.0 - 1.0, ny = texture(map, uvy).rgb * 2.0 - 1.0;
    vec3 wx = vec3(nx.z * face.x, nx.x * sign(face.x) + face.y, face.z - nx.y);
    vec3 wy = vec3(-ny.x * sign(face.y) + face.x, ny.z * face.y, face.z - ny.y);
    return normalize(mix(wy, wx, side));
}

// Cliffs and rocks share the same world-space projections.
vec4 wallSample(vec3 world, vec3 face, out vec2 uvx, out vec2 uvy, out float side) {
    vec3 axes = pow(abs(face), vec3(4.0));
    uvx = vec2(world.y * sign(face.x), -world.z) / u_sculptTiles.z;
    uvy = vec2(-world.x * sign(face.y), -world.z) / u_sculptTiles.z + .37;
    vec4 x = texture(u_wallColor, uvx), y = texture(u_wallColor, uvy);
    side = clamp((axes.x / max(axes.x + axes.y, 1e-4) - .5) * 3.0 + (x.a - y.a) * 1.2 + .5, 0.0, 1.0);
    return mix(y, x, side);
}

// A ground or debris map on a sloping face: seen from above where the face lies back (lying 1), and from the side like
// the wall maps where it is steep, so it never stretches down the slope. dx, dy are the side coordinates in metres.
vec4 draped(sampler2D map, vec2 p, vec2 dx, vec2 dy, float side, float tile, float mixer, float lying) {
    if (lying >= 1.0) return planar(map, p, tile, mixer);
    vec4 steep = mix(texture(map, dy / tile), texture(map, dx / tile), side);
    if (lying <= 0.0) return steep;
    return mix(steep, planar(map, p, tile, mixer), lying);
}

vec3 drapedNormal(sampler2D map, vec2 p, vec2 dx, vec2 dy, vec3 face, float side, float tile, float mixer, float lying) {
    vec3 steep = wallNormal(map, dx / tile, dy / tile, face, side);
    return normalize(mix(steep, upNormal(planarNormal(map, p, tile, mixer).rgb, face), lying));
}

