// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Screen-space ambient occlusion of the opaque scene (GpuAmbientOcclusion): the share of the sky this pixel's surface
// sees, for the ambient term only. It is computed from the depth pre-pass at half resolution and upsampled here against
// the fragment's own depth, so a surface the pre-pass does not hold (a unit, a building, water) reads 1. Without
// ambientOcclusionFlag it is the constant 1, which the compiler folds away.
#ifdef ambientOcclusionFlag
// CAMERA_DEPTH
uniform sampler2D u_ambientOcclusion; // half resolution: R visibility, G camera distance of the surface it is for
uniform vec2 u_ambientOcclusionPixel; // world size of one scene pixel at camera distance d: x + y * d

float screenAmbientOcclusion() {
    float depth = cameraDepth(gl_FragCoord.z);
    // The neighbouring half-resolution samples lie up to 1.5 scene pixels away; grazing surfaces change depth faster.
    float tolerance = 16.0 * (u_ambientOcclusionPixel.x + u_ambientOcclusionPixel.y * depth);
    // Half-resolution texel i holds scene pixel 2i.
    vec2 position = gl_FragCoord.xy * .5 - .25;
    ivec2 base = ivec2(floor(position));
    vec2 fraction = position - vec2(base);
    ivec2 last = textureSize(u_ambientOcclusion, 0) - 1;
    float visibility = 0.0, total = 0.0;
    for (int corner = 0; corner < 4; corner++) {
        ivec2 offset = ivec2(corner & 1, corner >> 1);
        vec2 texel = texelFetch(u_ambientOcclusion, clamp(base + offset, ivec2(0), last), 0).rg;
        vec2 bilinear = mix(1.0 - fraction, fraction, vec2(offset));
        // A small floor lets a matching neighbour stand in where the nearest sample shows another surface.
        float weight = (bilinear.x * bilinear.y + .001) * max(0.0, 1.0 - abs(texel.g - depth) / tolerance);
        visibility += texel.r * weight;
        total += weight;
    }
    return total > .0001 ? visibility / total : 1.0;
}
#else
float screenAmbientOcclusion() { return 1.0; }
#endif
