// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// GpuTerrain emits sampler2D and sampler2DArray overloads from this one kernel. POM_SAMPLER/POM_UV are replaced
// at assembly, not runtime. Height is linear [0,1], white high; ray spans the complete height range in texture UV.
vec2 parallaxUv(POM_SAMPLER heightMap, float layer, vec4 channel, vec2 uv, vec2 dx, vec2 dy, vec2 ray) {
    if (u_parallaxMapping < .5) return uv;
    float pixels = parallaxPixels(ray, dx, dy);
    if (pixels <= .5) return uv;
    ray *= smoothstep(.5, 2.0, pixels);
    // Bound the work by the texture detail the pixel can resolve. Use the smaller footprint axis so grazing
    // anisotropic views cannot skip narrow ridges merely because the other axis covers many texels.
    vec2 size = vec2(textureSize(heightMap, 0).xy);
    float footprint = max(1.0, min(length(dx * size), length(dy * size)));
    int steps = int(clamp(ceil(length(ray * size) / footprint), 4.0, 24.0));
    float stride = 1.0 / float(steps);
    vec2 at = uv + ray * .5;
    float before = 1.0 - dot(textureGrad(heightMap, POM_UV, dx, dy), channel);
    if (before <= 0.0) return at;
    float depth = 0.0, after = before;
    // A dynamic bound avoids expanding 24 samples at every inlined terrain role/projection on desktop drivers.
    for (int i = 1; i <= steps; i++) {
        before = after;
        depth = float(i) / float(steps);
        at = uv + ray * (.5 - depth);
        after = 1.0 - depth - dot(textureGrad(heightMap, POM_UV, dx, dy), channel);
        if (after <= 0.0) break;
    }
    // Refine only the first bracket we actually crossed. A binary search over the whole ray can choose a hidden
    // intersection. Two local bisections plus interpolation avoid staircase edges with a small sampling budget.
    float low = max(0.0, depth - stride), high = depth;
    for (int i = 0; i < 2; i++) {
        float middle = (low + high) * .5;
        at = uv + ray * (.5 - middle);
        float gap = 1.0 - middle - dot(textureGrad(heightMap, POM_UV, dx, dy), channel);
        if (gap > 0.0) { low = middle; before = gap; }
        else { high = middle; after = gap; }
    }
    float hit = mix(low, high, clamp(before / max(before - after, 1e-6), 0.0, 1.0));
    return uv + ray * (.5 - hit);
}
