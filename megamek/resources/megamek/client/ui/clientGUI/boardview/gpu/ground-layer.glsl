// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared camera/depth, height integration and periodic volume noise for fog and sand.
uniform mat4 u_inverseView;
// CAMERA_DEPTH
uniform vec3 u_boundsMin;
uniform vec3 u_boundsMax;
uniform sampler2D u_layerNoise;
uniform vec4 u_groundBoard; // Board width/height in hexes and world hex width/height.
uniform vec4 u_layerScreenBounds;

bool outsideGroundLayer() {
    return v_uv.x < u_layerScreenBounds.x || v_uv.y < u_layerScreenBounds.y
          || v_uv.x > u_layerScreenBounds.z || v_uv.y > u_layerScreenBounds.w;
}

vec2 boardHexCenter(vec2 hex) {
    return vec2(hex.x * 0.75 + 0.5, hex.y + mod(hex.x, 2.0) * 0.5 + 0.5);
}

vec2 boardHex(vec2 point) {
    float column = floor(point.x / 0.75) - 1.0;
    vec2 hex = vec2(column, floor(point.y - mod(column, 2.0) * 0.5));
    vec2 delta = abs(point - boardHexCenter(hex));
    if (delta.y <= 0.5 && delta.x + 0.5 * delta.y <= 0.5) return hex;
    column += 1.0;
    return vec2(column, floor(point.y - mod(column, 2.0) * 0.5));
}

vec3 world(float depth) {
    vec4 p = u_inverseView * vec4(v_uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

vec2 groundSegment(vec3 origin, vec3 surface, vec3 direction) {
    vec3 inverseRay = 1.0 / (direction + vec3(0.000001));
    vec3 a = (u_boundsMin - origin) * inverseRay;
    vec3 b = (u_boundsMax - origin) * inverseRay;
    vec3 nearBox = min(a, b), farBox = max(a, b);
    float start = max(0.0, max(nearBox.x, max(nearBox.y, nearBox.z)));
    float end = min(dot(surface - origin, direction), min(farBox.x, min(farBox.y, farBox.z)));
    return vec2(start, end);
}

vec2 belowHeight(vec2 segment, vec3 origin, vec3 direction, float top) {
    if (abs(direction.z) < 0.000001) return origin.z <= top ? segment : vec2(0.0);
    float crossing = (top - origin.z) / direction.z;
    return direction.z < 0.0 ? vec2(max(segment.x, crossing), segment.y)
          : vec2(segment.x, min(segment.y, crossing));
}

float heightIntegral(float z0, float z1, float distance, float height) {
    float difference = abs(z1 - z0);
    if (difference < 0.001 * distance) return distance * exp(-max(0.0, z1) / height);
    return distance * (height * abs(exp(-max(0.0, z1) / height) - exp(-max(0.0, z0) / height))
          + abs(min(z1, 0.0) - min(z0, 0.0))) / max(difference, 0.000001);
}

// The two channels encode adjacent Z slices, so trilinear volume noise costs one filtered texture lookup.
float groundNoise(vec3 point) {
    vec3 cell = floor(point), f = fract(point);
    f = f * f * (3.0 - 2.0 * f);
    vec2 uv = cell.xy + vec2(37.0, 17.0) * cell.z + f.xy;
    vec2 pair = texture2D(u_layerNoise, (uv + 0.5) / 256.0).rg;
    return mix(pair.r, pair.g, f.z);
}

float groundEdge(vec3 position, float width) {
    vec2 edge = min(position.xy - u_boundsMin.xy, u_boundsMax.xy - position.xy);
    vec2 fade = smoothstep(vec2(0.0), vec2(width), edge);
    return fade.x * fade.y;
}

// The board's display plinth is solid, not part of the atmospheric volume.
// Keep horizontal water beds eligible: their opaque depth lies below the water surface.
bool groundBaseSide(vec3 surface, float depth, float base, float tolerance) {
    vec3 normal = cross(dFdx(surface), dFdy(surface));
    float lengthSquared = dot(normal, normal);
    if (depth >= 1.0 || normal.z * normal.z >= lengthSquared * 0.25) return false;
    normal /= max(sqrt(lengthSquared), 0.000001);
    vec2 outside = surface.xy + normal.xy * min(u_groundBoard.z, u_groundBoard.w) * 0.004;
    vec2 hex = boardHex(outside * vec2(1.0, -1.0) / u_groundBoard.zw);
    return surface.z < base - tolerance || hex.x < 0.0 || hex.y < 0.0
          || hex.x >= u_groundBoard.x || hex.y >= u_groundBoard.y;
}
