// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Authored perceptual roughness is raw scalar data, independent of normal-map detail.
uniform sampler2DArray u_roughnessLayers;
uniform vec2 u_sculptRoughness[SCULPT_MATERIAL_COUNT]; // scalar, map layer; negative means absent

bool hasAuthoredRoughness(float colorLayer) {
    return any(greaterThanEqual(u_sculptRoughness[int(colorLayer * .5)], vec2(0.0)));
}

float authoredRoughness(float colorLayer, vec2 uv, mat2 gradient, float fallback) {
    vec2 authored = u_sculptRoughness[int(colorLayer * .5)];
    if (authored.y >= 0.0) {
        return textureGrad(u_roughnessLayers, vec3(uv, authored.y), gradient[0], gradient[1]).r;
    }
    return authored.x >= 0.0 ? authored.x : fallback;
}
