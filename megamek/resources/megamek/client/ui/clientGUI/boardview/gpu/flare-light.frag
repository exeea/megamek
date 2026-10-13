#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// An illumination round's pool of light. It lights exactly the hexes the rules do (every hex within its radius, by
// hex count), fully, and fades over a short strip just outside them so it reads as light rather than a stencil.
// Blended as dst * (1 + src): the scene's own colours are brightened, not washed out. GpuStarShells mirrors the hex
// arithmetic below in Java, and its tests hold that to the game's own hex distance.
layout(location = 0) out vec4 fragColor;
uniform sampler2D u_depth;
uniform mat4 u_inverseProjView;
uniform vec4 u_viewport;
// Hex width and height in world units.
uniform vec2 u_hexSize;
// The flare's hex (column, row) and its radius in hexes.
uniform vec3 u_flareHex;
// Where the flare hangs, over the ground, for the gentle brightening toward it.
uniform vec2 u_flareCenter;
// The light's colour and strength.
uniform vec4 u_light;

vec2 hexCenter(ivec2 hex) {
    return vec2(float(hex.x) * u_hexSize.x * 0.75 + u_hexSize.x * 0.5,
          -(float(hex.y) * u_hexSize.y + float(hex.x & 1) * u_hexSize.y * 0.5 + u_hexSize.y * 0.5));
}

// The hex a point lies in: the nearest hex centre, as the cells of a hex grid are exactly its centres' nearest regions.
ivec2 hexAt(vec2 p) {
    ivec2 guess = ivec2(int(floor(p.x / (u_hexSize.x * 0.75))), int(floor(-p.y / u_hexSize.y)));
    ivec2 best = guess;
    float nearest = 1e30;
    for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
            ivec2 candidate = guess + ivec2(dx, dy);
            vec2 offset = p - hexCenter(candidate);
            float distance = dot(offset, offset);
            if (distance < nearest) {
                nearest = distance;
                best = candidate;
            }
        }
    }
    return best;
}

// Odd columns sit half a hex lower (HexTile.centerY), so these are "odd-q" offset coordinates.
ivec3 cube(ivec2 hex) {
    int z = hex.y - (hex.x - (hex.x & 1)) / 2;
    return ivec3(hex.x, -hex.x - z, z);
}

int hexDistance(ivec2 a, ivec2 b) {
    ivec3 d = abs(cube(a) - cube(b));
    return max(d.x, max(d.y, d.z));
}

void main() {
    vec2 uv = (gl_FragCoord.xy - u_viewport.xy) / u_viewport.zw;
    float depth = texture(u_depth, uv).r;
    if (depth >= 1.0) discard;
    vec4 surface = u_inverseProjView * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 p = surface.xyz / surface.w;
    ivec2 cell = hexAt(p.xy);
    ivec2 flare = ivec2(u_flareHex.xy);
    int radius = int(u_flareHex.z);
    int distance = hexDistance(cell, flare);
    float light = 0.0;
    if (distance <= radius) {
        light = 1.0;
    } else if (distance == radius + 1) {
        // Just outside: fade with the distance from the edge shared with the nearest lit neighbour.
        vec2 center = hexCenter(cell);
        float fromEdge = 1e30;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                ivec2 neighbour = cell + ivec2(dx, dy);
                if (hexDistance(neighbour, cell) != 1 || hexDistance(neighbour, flare) > radius) continue;
                vec2 other = hexCenter(neighbour);
                vec2 toward = normalize(other - center);
                fromEdge = min(fromEdge, dot((center + other) * 0.5 - p.xy, toward));
            }
        }
        light = 1.0 - smoothstep(0.0, u_hexSize.x * 0.2, max(fromEdge, 0.0));
    }
    if (light <= 0.0) discard;
    float reach = u_hexSize.y * (float(radius) + 0.5);
    float toward = 1.0 - 0.2 * clamp(length(p.xy - u_flareCenter) / reach, 0.0, 1.0);
    fragColor = vec4(u_light.rgb * u_light.a * light * toward, 1.0);
}
