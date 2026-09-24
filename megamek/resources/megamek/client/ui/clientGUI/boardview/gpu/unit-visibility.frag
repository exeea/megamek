// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
precision highp float;
#endif
varying vec2 v_uv;
uniform sampler2D u_sceneDepth;
uniform sampler2D u_unitDepth;
uniform sampler2D u_unitColors;
uniform vec2 u_step;
uniform float u_bias;
uniform float u_intensity;
uniform float u_levelHeight;
// GROUND_LAYER

float depthAt(sampler2D map, vec2 uv) {
    return texture2D(map, uv).r;
}

vec3 worldAt(vec2 uv, float depth) {
    vec4 p = u_inverseView * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

// 1 where the scene hides the unit. The ground's own relief, grass and scatter in the hex the unit stands in never do:
// the capture's alpha holds the height below which that hex's decoration stays, in quarter levels offset by 128 (0 for
// markers, which have no such exemption), so only what stands in another hex, or rises above it, hides a unit.
float hiddenAt(vec2 uv) {
    if (min(uv.x, uv.y) < 0.0 || max(uv.x, uv.y) > 1.0) return 0.0;
    float unit = depthAt(u_unitDepth, uv), scene = depthAt(u_sceneDepth, uv);
    if (unit >= 1.0 || behind(scene, unit, u_bias) < 0.5) return 0.0;
    vec3 occluder = worldAt(uv, scene), surface = worldAt(uv, unit);
    float groundTop = (texture2D(u_unitColors, uv).a * 255.0 - 128.0) * 0.25 * u_levelHeight;
    bool own = boardHex(occluder.xy * vec2(1.0, -1.0) / u_groundBoard.zw)
          == boardHex(surface.xy * vec2(1.0, -1.0) / u_groundBoard.zw);
    return own && occluder.z <= groundTop ? 0.0 : 1.0;
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
        gl_FragColor = vec4(texture2D(u_unitColors, colorUV).rgb,
              u_intensity * mix(0.24, 1.0, edge));
    } else if (farMax > 0.5) {
        // A narrow dark halo retains contrast against snow, water and bright terrain artwork.
        gl_FragColor = vec4(0.025, 0.055, 0.07, u_intensity * 0.65);
    } else {
        discard;
    }
}
