// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Authored perceptual roughness is raw scalar data, independent of normal-map detail. Every sculpt material has its
// own: a map layer, or one value where it is uniform.
uniform sampler2DArray u_roughnessLayers;
uniform vec2 u_sculptRoughness[SCULPT_MATERIAL_COUNT]; // the uniform value or the map's mean; map layer, negative if uniform

float authoredRoughness(float colorLayer, vec2 uv, mat2 gradient) {
    vec2 authored = u_sculptRoughness[int(colorLayer * .5)];
    if (authored.y < 0.0) return authored.x;
    return textureGrad(u_roughnessLayers, vec3(uv, authored.y), gradient[0], gradient[1]).r;
}
