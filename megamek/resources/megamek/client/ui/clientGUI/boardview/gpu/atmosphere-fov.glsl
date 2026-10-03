// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// FoV is a rules-derived mask applied to reconstructed world positions, never a repaint of terrain artwork.
uniform sampler2D u_fov;
uniform float u_fovEnabled;
uniform vec2 u_fovSize;
uniform vec2 u_fovHexSize;
uniform mat4 u_fovInverseView;
uniform vec2 u_fovOptions; // Distance-ring opacity, spotting tint.
uniform vec3 u_fovEffect; // Opacity, grayscale, fog-of-war for hexes outside visual LOS.
uniform vec3 u_sensorEffect; // Same settings for hexes outside visual and sensor coverage.
uniform float u_dimmedDesaturation;
uniform float u_fovEdge;

vec4 fovAt(vec2 hex) {
    if (hex.x < 0.0 || hex.y < 0.0 || hex.x >= u_fovSize.x || hex.y >= u_fovSize.y) return vec4(0.0);
    return texture(u_fov, (hex + 0.5) / u_fovSize);
}

float fovState(vec4 value) {
    return mod(floor(value.a * 255.0 + 0.5), 8.0);
}

vec3 fovEffect(vec4 mask) {
    // BLOCKED also includes ordinary LOS obstructions and previews without a selected sensor.
    return mask.a * 255.0 >= 15.5 ? u_sensorEffect : u_fovEffect;
}

float fovOpacity(vec4 mask) {
    if (fovState(mask) <= 2.5) return 0.0;
    vec3 effect = fovEffect(mask);
    return effect.z > 0.5 ? 1.0 - pow(1.0 - effect.x, 4.0) : effect.x;
}

vec3 fovPosition(vec2 uv, float depth) {
    vec4 point = u_fovInverseView * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / point.w;
}

vec3 fovNormal(vec3 position) {
    vec3 normal = cross(dFdx(position), dFdy(position));
    return normal / max(length(normal), 0.000001);
}

vec2 fovSurfaceHex(vec3 position, vec3 normal) {
    // At a cliff edge, sample just inside its solid side to avoid depth rounding into the adjacent hex.
    vec3 inside = position - normal * (min(u_fovHexSize.x, u_fovHexSize.y) * 0.002);
    return boardHex(vec2(inside.x, -inside.y) / u_fovHexSize);
}

float fovHeatTransmission(vec2 uv, float depth) {
    vec3 position = fovPosition(uv, depth);
    vec3 normal = fovNormal(position);
    // As in the composite, derive the surface normal before branching away background lanes.
    if (depth >= 1.0) return 1.0;
    return 1.0 - fovOpacity(fovAt(fovSurfaceHex(position, normal)));
}

float fovBorder(vec2 neighbor, float distance, vec4 mask) {
    float state = fovState(mask);
    vec4 otherMask = fovAt(neighbor);
    float other = fovState(otherMask);
    if (other < 0.5 || (state < 2.5) == (other < 2.5)) return 0.0;
    float opacity = fovEffect(state > 2.5 ? mask : otherMask).x;
    if (opacity <= 0.0) return 0.0;
    return 1.0 - smoothstep(u_fovEdge * 0.5, u_fovEdge * 2.0, distance);
}

vec3 fieldOfView(vec3 color, float depth) {
    vec3 position = fovPosition(v_uv, depth);
    vec3 normal = fovNormal(position);
    // Derivatives need the whole pixel quad, including background lanes at silhouettes.
    if (depth >= 1.0) return color;
    float horizontal = smoothstep(0.35, 0.80, abs(normal.z));
    vec2 point = vec2(position.x, -position.y) / u_fovHexSize;
    vec2 hex = fovSurfaceHex(position, normal);
    vec4 mask = fovAt(hex);
    float state = fovState(mask);
    if (state < 0.5) return color;
    vec2 p = point - boardHexCenter(hex);
    float north = 0.5 + p.y, south = 0.5 - p.y;
    float northEast = 0.5 - p.x + 0.5 * p.y, southEast = 0.5 - p.x - 0.5 * p.y;
    float northWest = 0.5 + p.x + 0.5 * p.y, southWest = 0.5 + p.x - 0.5 * p.y;
    float parity = mod(hex.x, 2.0);
    float boundary = fovBorder(hex + vec2(0, -1), north, mask);
    boundary = max(boundary, fovBorder(hex + vec2(1, parity - 1.0), northEast, mask));
    boundary = max(boundary, fovBorder(hex + vec2(1, parity), southEast, mask));
    boundary = max(boundary, fovBorder(hex + vec2(0, 1), south, mask));
    boundary = max(boundary, fovBorder(hex + vec2(-1, parity), southWest, mask));
    boundary = max(boundary, fovBorder(hex + vec2(-1, parity - 1.0), northWest, mask));
    vec3 effect = fovEffect(mask);
    if (state > 2.5) {
        bool sensor = state < 3.5;
        float amount = effect.x;
        float gray = dot(color, vec3(0.2126, 0.7152, 0.0722));
        float desaturate = amount > 0.0 ? (effect.z > 0.5 ? 0.85 : u_dimmedDesaturation) : 0.0;
        color = mix(color, vec3(gray), desaturate);
        vec3 shade = sensor ? vec3(0.10, 0.20, 0.25) : vec3(0.025, 0.035, 0.055);
        if (u_fovOptions.y > 0.5) shade.b += 0.10;
        amount = fovOpacity(mask);
        color = mix(color, shade, amount);
    } else if (state < 1.5 && mod(floor(mask.a * 255.0 + 0.5), 16.0) > 8.0) {
        color = mix(color, mask.rgb, u_fovOptions.x);
    }
    if (state > 1.5 && state < 2.5) {
        float edge = min(min(north, south), min(min(northEast, southEast), min(northWest, southWest)));
        color = mix(color, mask.rgb, (1.0 - smoothstep(0.015, 0.04, edge)) * 0.28 * horizontal);
    }
    // A contour appears only where visible hexes meet an enabled blocked/sensor effect.
    color = mix(color, vec3(0.40, 0.78, 0.84), boundary * 0.60 * horizontal);
    if (state > 2.5 && effect.y > 0.5) {
        // Desaturate last so sensor/spotting tints and the contour remain grayscale too.
        color = vec3(dot(color, vec3(0.2126, 0.7152, 0.0722)));
    }
    return color;
}
