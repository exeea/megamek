#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec2 v_uv;
uniform sampler2D u_sceneDepth;
uniform sampler2D u_buildingDepth;
uniform sampler2D u_unitDepth;
uniform sampler2D u_unitColors;
uniform sampler2D u_effectOpacity;
uniform vec2 u_effectSize;
uniform vec2 u_step;
uniform float u_bias;
uniform float u_intensity;
uniform float u_levelHeight;
// GROUND_LAYER

float depthAt(sampler2D map, vec2 uv) {
    return texture(map, uv).r;
}

vec3 worldAt(vec2 uv, float depth) {
    vec4 p = u_inverseView * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

float effectHiddenAt(vec2 uv, float unit) {
    if (u_effectSize.x < 1.0 || texture(u_effectOpacity, uv).a < 0.5) return 0.0;
    // Use a sample that actually ended on this surface. A half-resolution ray beside a thin limb may hit smoky
    // background instead; its opacity must not outline the foreground limb.
    vec2 base = (floor(uv * u_effectSize - 0.5) + 0.5) / u_effectSize;
    vec2 texel = 1.0 / u_effectSize;
    float tolerance = max(u_bias, length(worldAt(uv + texel, unit) - worldAt(uv, unit)) * 2.0);
    float nearest = tolerance;
    vec2 sampleUV = vec2(-1.0);
    float distance = cameraDepth(unit);
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec2 candidate = base + vec2(float(x), float(y)) * texel;
            float separation = abs(cameraDepth(depthAt(u_sceneDepth, candidate)) - distance);
            if (separation < nearest) { nearest = separation; sampleUV = candidate; }
        }
    }
    return sampleUV.x < 0.0 ? 0.0 : step(0.6, texture(u_effectOpacity, sampleUV).a);
}

// How far the unit's own surface runs in camera depth across one pixel. The capture draws the unit apart from the
// scene, and a steep face (a vent slat seen edge on) can rasterize a fraction of a pixel differently in the two: a
// scene depth within that span is the unit itself, not something standing before it.
float surfaceSpan(vec2 uv, float unit) {
    vec2 texel = 1.0 / vec2(textureSize(u_unitDepth, 0));
    float here = cameraDepth(unit), span = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 offset = texel * (i < 2 ? vec2(float(i * 2 - 1), 0.0) : vec2(0.0, float(i * 2 - 5)));
        float next = depthAt(u_unitDepth, uv + offset);
        if (next < 1.0) span = max(span, abs(cameraDepth(next) - here));
    }
    return span;
}

// 1 where the scene hides the unit. The ground's own relief, grass and scatter in the hex the unit stands in never do:
// the capture's alpha holds the height below which that hex's decoration stays, in quarter levels offset by 128 (0 for
// markers, which have no such exemption), so only what stands in another hex, or rises above it, hides a unit.
float hiddenAt(vec2 uv) {
    if (min(uv.x, uv.y) < 0.0 || max(uv.x, uv.y) > 1.0) return 0.0;
    // Faded buildings remain occluders here without changing the scene depth used by fog and floor labels.
    float unit = depthAt(u_unitDepth, uv), scene = min(depthAt(u_sceneDepth, uv), depthAt(u_buildingDepth, uv));
    if (unit >= 1.0) return 0.0;
    // The cheap test first: only an apparent occluder pays for the unit's own depth span.
    if (behind(scene, unit, u_bias) < 0.5 || behind(scene, unit, max(u_bias, surfaceSpan(uv, unit))) < 0.5) {
        return effectHiddenAt(uv, unit);
    }
    vec3 occluder = worldAt(uv, scene), surface = worldAt(uv, unit);
    float groundTop = (texture(u_unitColors, uv).a * 255.0 - 128.0) * 0.25 * u_levelHeight;
    bool own = boardHex(occluder.xy * vec2(1.0, -1.0) / u_groundBoard.zw)
          == boardHex(surface.xy * vec2(1.0, -1.0) / u_groundBoard.zw);
    return own && occluder.z <= groundTop ? effectHiddenAt(uv, unit) : 1.0;
}

void main() {
    float unit = depthAt(u_unitDepth, v_uv);
    float hidden = hiddenAt(v_uv);
    // Neither fill nor halo may repaint the normally visible part of a unit.
    if (unit < 1.0 && hidden < 0.5) discard;

    float nearMax = hidden;
    float nearMin = hidden;
    float farMax = hidden;
    vec2 colorUV = v_uv;
    float nearest = hidden > 0.5 ? 0.0 : 10.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0) continue;
            vec2 offset = vec2(float(x), float(y)) * u_step;
            float nearby = hiddenAt(v_uv + offset);
            float distance = float(x * x + y * y);
            if (nearby > 0.5 && distance < nearest) {
                nearest = distance;
                colorUV = v_uv + offset;
            }
            nearMax = max(nearMax, nearby);
            nearMin = min(nearMin, nearby);
            farMax = max(farMax, hiddenAt(v_uv + offset * 2.0));
        }
    }
    float edge = nearMax - nearMin;
    if (nearMax > 0.5) {
        // Both the edge and faint interior follow the unit's team/player color.
        fragColor = vec4(texture(u_unitColors, colorUV).rgb,
              u_intensity * mix(0.24, 1.0, edge));
    } else if (farMax > 0.5) {
        // A narrow dark halo retains contrast against snow, water and bright terrain artwork.
        fragColor = vec4(0.025, 0.055, 0.07, u_intensity * 0.65);
    } else {
        discard;
    }
}
