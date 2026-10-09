// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by terrain and grass. Directory texels name sparse, fixed-ground-size image layers.
uniform sampler2DArray u_groundDamage;
uniform vec2 u_groundDamageSize;
uniform vec3 u_groundDamageGrid;

#ifdef surfaceScarFlag
in vec3 v_scarUV;
vec4 surfaceScarMask;
vec4 surfaceScar() {
    vec2 uv = (v_scarUV.xy * GROUND_DAMAGE_INNER + GROUND_DAMAGE_BORDER) / GROUND_DAMAGE_TILE;
    return textureGrad(u_groundDamage, vec3(uv, v_scarUV.z), dFdx(uv), dFdy(uv));
}
#endif

vec4 groundDamageAt(vec2 world) {
    if (u_groundDamageSize.x <= 0.0) return vec4(0.0);
    vec2 position = vec2(world.x, -world.y) / u_groundDamageGrid.x;
#ifdef GROUND_DAMAGE_FRAGMENT
    // Different fragments can select different layers; never differentiate the wrapped tile coordinates.
    vec2 dx = dFdx(position) * GROUND_DAMAGE_INNER / GROUND_DAMAGE_TILE;
    vec2 dy = dFdy(position) * GROUND_DAMAGE_INNER / GROUND_DAMAGE_TILE;
#endif
    ivec2 cell = ivec2(floor(position));
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(u_groundDamageGrid.yz)))) return vec4(0.0);
    int size = int(GROUND_DAMAGE_TILE);
    int address = cell.y * int(u_groundDamageGrid.y) + cell.x;
    ivec3 entry = ivec3(address % size, (address / size) % size, address / (size * size));
    ivec2 bytes = ivec2(texelFetch(u_groundDamage, entry, 0).rg * 255.0 + .5);
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
