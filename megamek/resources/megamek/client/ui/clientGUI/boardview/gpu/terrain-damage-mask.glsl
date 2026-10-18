// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by terrain and grass. Directory texels name sparse, fixed-ground-size image layers.
uniform sampler2DArray u_groundDamage;
uniform vec2 u_groundDamageSize;
uniform vec3 u_groundDamageGrid;
// x: 1 while any scorch shows (a nuclear blast's or a hex's authored one); y: Ground Zero's heat, cooling after a blast
uniform vec2 u_groundScorch;

// The directory texel of an 8 m tile: R and G name its image layer, B holds a nuclear blast's scorch, A the hex's
// authored scorch (GpuGroundDamage.author).
vec4 groundDamageEntry(ivec2 cell) {
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(u_groundDamageGrid.yz)))) return vec4(0.0);
    int size = int(GROUND_DAMAGE_TILE);
    int address = cell.y * int(u_groundDamageGrid.y) + cell.x;
    return texelFetch(u_groundDamage, ivec3(address % size, (address / size) % size, address / (size * size)), 0);
}

// The scorch here, 0 to 1: x a nuclear blast's, y the hexes' authored one; bilinear between the tiles' centres, so its
// 8 m tiles never show.
vec2 groundScorches(vec2 world) {
    if (u_groundScorch.x <= 0.0) return vec2(0.0);
    vec2 position = vec2(world.x, -world.y) / u_groundDamageGrid.x - .5;
    ivec2 cell = ivec2(floor(position));
    vec2 f = position - vec2(cell);
    vec2 near = mix(groundDamageEntry(cell).ba, groundDamageEntry(cell + ivec2(1, 0)).ba, f.x);
    vec2 far = mix(groundDamageEntry(cell + ivec2(0, 1)).ba, groundDamageEntry(cell + ivec2(1, 1)).ba, f.x);
    return mix(near, far, f.y);
}

// The scorch that shows here: the stronger of a nuclear blast's and the authored one.
float groundScorchAt(vec2 world) {
    vec2 scorches = groundScorches(world);
    return max(scorches.x, scorches.y);
}

// Scorch on a surface (shadeSurface): soot over a nuclear blast's area, which the falling damage thins toward its edge,
// or over a hex scorched in the editor, and at full scorch the dark, glassy crust the heat fuses from the ground
// (trinitite).
void scorchGround(float scorch, inout vec3 albedo, inout float roughness) {
    float ash = smoothstep(0.0, .45, scorch);
    float luminance = dot(albedo, vec3(.2126, .7152, .0722));
    albedo = mix(albedo, vec3(luminance * .12) + vec3(.010, .009, .008), ash);
    float glass = smoothstep(.72, .92, scorch);
    albedo = mix(albedo, vec3(.018, .03, .024), glass);
    roughness = mix(roughness, mix(.95, .15, glass), max(ash * .6, glass));
}

float scorchHash(vec2 cell) { return fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453); }

// The glow of Ground Zero's fused crust while it cools (shadeSurface): bright veins along the cracks of the crust, a dim
// glow between them, both as hot as its scorch and the heat left since the blast.
float scorchGlow(vec2 world, float scorch, float heat) {
    float glow = heat * smoothstep(.8, 1.0, scorch);
    if (glow <= 0.0) return 0.0;
    // Cracks about 3 m apart, in metres from the 8 m tiles' world size.
    vec2 p = world / (u_groundDamageGrid.x * .4);
    vec2 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float noise = mix(mix(scorchHash(cell), scorchHash(cell + vec2(1.0, 0.0)), f.x),
          mix(scorchHash(cell + vec2(0.0, 1.0)), scorchHash(cell + vec2(1.0, 1.0)), f.x), f.y);
    float vein = 1.0 - abs(noise * 2.0 - 1.0);
    return glow * (.15 + .85 * pow(vein, 6.0));
}

vec4 groundDamageAt(vec2 world) {
    if (u_groundDamageSize.x <= 0.0) return vec4(0.0);
    vec2 position = vec2(world.x, -world.y) / u_groundDamageGrid.x;
#ifdef GROUND_DAMAGE_FRAGMENT
    // Different fragments can select different layers; never differentiate the wrapped tile coordinates.
    vec2 dx = dFdx(position) * GROUND_DAMAGE_INNER / GROUND_DAMAGE_TILE;
    vec2 dy = dFdy(position) * GROUND_DAMAGE_INNER / GROUND_DAMAGE_TILE;
#endif
    ivec2 bytes = ivec2(groundDamageEntry(ivec2(floor(position))).rg * 255.0 + .5);
    int layer = bytes.x + bytes.y * 256;
    if (layer == 0) return vec4(0.0);
    vec2 uv = (fract(position) * GROUND_DAMAGE_INNER + GROUND_DAMAGE_BORDER) / GROUND_DAMAGE_TILE;
#ifdef GROUND_DAMAGE_FRAGMENT
    return textureGrad(u_groundDamage, vec3(uv, float(layer)), dx, dy);
#else
    return textureLod(u_groundDamage, vec3(uv, float(layer)), 0.0);
#endif
}

float groundScarCover(vec4 damage) {
    // Opaque, dark cores clear the turf; lighter debris and low-strength singeing retain some blades.
    return smoothstep(.04, .95, damage.r - damage.g * .45);
}
