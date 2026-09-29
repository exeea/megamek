// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Solid crust and cooled banks: occluding relief, stable plates and deep red fissures.
vec2 magmaRelief(vec2 uv, vec2 dx, vec2 dy, vec4 surface, vec2 parallax) {
    if (u_normalMaps > .5) {
        uv += parallax * .5;
        float layer = 1.0;
        float previous = 1.0 - textureGrad(u_specularTexture, uv, dx, dy).r;
        for (int step = 0; step < 12; step++) {
            uv -= parallax / 12.0;
            layer -= 1.0 / 12.0;
            float gap = layer - textureGrad(u_specularTexture, uv, dx, dy).r;
            if (gap <= 0.0) {
                uv += parallax / 12.0 * (-gap / max(previous - gap, .0001));
                break;
            }
            previous = gap;
        }
    } else {
        uv -= parallax * (surface.r - .5);
    }
    return uv;
}

Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 downhill) {
    return magmaSample(uv, dx, dy, eye, 0.0, vec2(0.0));
}

vec3 magmaWeights(vec3 weights, vec3 heights) {
    // Solid plates cover neighbouring molten gaps at patch transitions. Ordinary colour
    // blending superimposes red fissures over cold rock and erases the slab's broken edge.
    // A patch with zero coverage cannot win this comparison at a lattice boundary.
    vec3 height = heights * weights;
    weights *= max(height - (max(max(height.x, height.y), height.z) - .12), vec3(0.0));
    weights /= dot(weights, vec3(1.0));
    return weights;
}

vec3 magmaHeatColor(float heat) {
    return mix(vec3(.55, .006, .0002), vec3(.95, .24, .004), heat * heat * heat);
}
