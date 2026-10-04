// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared world-space texture projection and normal reconstruction.


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

// A map is a sampler2D of its own, or a layer of u_terrainLayers, the array that holds every sculpt map (one texture
// unit for all of them; macOS gives a shader stage only 16). The helpers below take either; only the fetch differs.
vec4 mapTexel(sampler2D map, vec2 uv) { return texture(map, uv); }
vec4 mapTexel(float layer, vec2 uv) { return texture(u_terrainLayers, vec3(uv, layer)); }
// The map's average colour: its last mip level.
vec4 mapAverage(sampler2D map, vec2 uv) { return texture(map, uv, 12.0); }
vec4 mapAverage(float layer, vec2 uv) { return texture(u_terrainLayers, vec3(uv, layer), 12.0); }

// A map on the ground plane at two repeats, mixed by a broad field, so no period shows. p is in metres with +V
// pointing to world -Y, as the maps are authored. From afar it settles to the map's average (its last mip level).
vec4 planar(sampler2D map, vec2 p, float tile, float mixer) {
    vec4 near = mix(mapTexel(map, p / tile), mapTexel(map, TURN * p / (tile * 2.37) + .31), mixer);
    return farDetail > 0.0 ? mix(near, mapAverage(map, p / tile), farDetail) : near;
}

vec4 planar(float map, vec2 p, float tile, float mixer) {
    vec4 near = mix(mapTexel(map, p / tile), mapTexel(map, TURN * p / (tile * 2.37) + .31), mixer);
    return farDetail > 0.0 ? mix(near, mapAverage(map, p / tile), farDetail) : near;
}

// The matching tangent-space normal (x along +U, y along +V) from the two repeats' texels. An enlarged sample has
// the same relief over a longer distance: rotate its gradient back and divide its slope by the enlargement.
vec4 planarNormal(vec4 near, vec4 far, float mixer) {
    vec3 a = near.rgb * 2.0 - 1.0, b = far.rgb * 2.0 - 1.0;
    b = normalize(vec3(b.xy * TURN / 2.37, b.z));
    vec4 result = vec4(mix(a, b, mixer), mix(near.a, far.a, mixer));
    return mix(result, vec4(0.0, 0.0, 1.0, result.a), farDetail);
}

vec4 planarNormal(sampler2D map, vec2 p, float tile, float mixer) {
    return planarNormal(mapTexel(map, p / tile), mapTexel(map, TURN * p / (tile * 2.37) + .31), mixer);
}

vec4 planarNormal(float map, vec2 p, float tile, float mixer) {
    return planarNormal(mapTexel(map, p / tile), mapTexel(map, TURN * p / (tile * 2.37) + .31), mixer);
}

// Tangent-space detail on a surface facing up: U is world +X, V is world -Y.
vec3 upNormal(vec3 detail, vec3 face) {
    return normalize(vec3(detail.x + face.x, face.y - detail.y, detail.z * face.z));
}

// A wall map's two vertical projections (U along the face, V down it), whiteout-blended with the face normal and
// expressed in world space. side weights the projection onto the YZ plane.
vec3 wallNormal(vec4 x, vec4 y, vec3 face, float side) {
    vec3 nx = x.rgb * 2.0 - 1.0, ny = y.rgb * 2.0 - 1.0;
    vec3 wx = vec3(nx.z * face.x, nx.x * sign(face.x) + face.y, face.z - nx.y);
    vec3 wy = vec3(-ny.x * sign(face.y) + face.x, ny.z * face.y, face.z - ny.y);
    return normalize(mix(wy, wx, side));
}

vec3 wallNormal(sampler2D map, vec2 uvx, vec2 uvy, vec3 face, float side) {
    return wallNormal(mapTexel(map, uvx), mapTexel(map, uvy), face, side);
}

vec3 wallNormal(float map, vec2 uvx, vec2 uvy, vec3 face, float side) {
    return wallNormal(mapTexel(map, uvx), mapTexel(map, uvy), face, side);
}

// Cliffs and rocks share the same world-space projections.
vec4 wallSample(vec3 world, vec3 face, out vec2 uvx, out vec2 uvy, out float side) {
    vec3 axes = pow(abs(face), vec3(4.0));
    uvx = vec2(world.y * sign(face.x), -world.z) / u_sculptTiles.z;
    uvy = vec2(-world.x * sign(face.y), -world.z) / u_sculptTiles.z + .37;
    vec4 x = mapTexel(u_sculptLayers.z, uvx), y = mapTexel(u_sculptLayers.z, uvy);
    side = clamp((axes.x / max(axes.x + axes.y, 1e-4) - .5) * 3.0 + (x.a - y.a) * 1.2 + .5, 0.0, 1.0);
    return mix(y, x, side);
}

// A ground or debris map on a sloping face: seen from above where the face lies back (lying 1), and from the side like
// the wall maps where it is steep, so it never stretches down the slope. dx, dy are the side coordinates in metres.
vec4 draped(sampler2D map, vec2 p, vec2 dx, vec2 dy, float side, float tile, float mixer, float lying) {
    if (lying >= 1.0) return planar(map, p, tile, mixer);
    vec4 steep = mix(mapTexel(map, dy / tile), mapTexel(map, dx / tile), side);
    if (lying <= 0.0) return steep;
    return mix(steep, planar(map, p, tile, mixer), lying);
}

vec4 draped(float map, vec2 p, vec2 dx, vec2 dy, float side, float tile, float mixer, float lying) {
    if (lying >= 1.0) return planar(map, p, tile, mixer);
    vec4 steep = mix(mapTexel(map, dy / tile), mapTexel(map, dx / tile), side);
    if (lying <= 0.0) return steep;
    return mix(steep, planar(map, p, tile, mixer), lying);
}

vec3 drapedNormal(sampler2D map, vec2 p, vec2 dx, vec2 dy, vec3 face, float side, float tile, float mixer, float lying) {
    vec3 steep = wallNormal(map, dx / tile, dy / tile, face, side);
    return normalize(mix(steep, upNormal(planarNormal(map, p, tile, mixer).rgb, face), lying));
}

vec3 drapedNormal(float map, vec2 p, vec2 dx, vec2 dy, vec3 face, float side, float tile, float mixer, float lying) {
    vec3 steep = wallNormal(map, dx / tile, dy / tile, face, side);
    return normalize(mix(steep, upNormal(planarNormal(map, p, tile, mixer).rgb, face), lying));
}
