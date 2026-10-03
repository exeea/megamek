// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Light travels UP from the visible height toward the top of the height field. Explicit gradients share the POM mip.
float parallaxShadow(POM_SAMPLER heightMap, float layer, vec4 channel, vec2 uv, vec2 dx, vec2 dy,
      vec2 lightRay, float strength) {
    if (u_parallaxMapping < .5 || strength <= .001) return 1.0;
    float pixels = parallaxPixels(lightRay, dx, dy);
    if (pixels <= .75) return 1.0;
    vec2 at = uv;
    float height = dot(textureGrad(heightMap, POM_UV, dx, dy), channel);
    float remaining = 1.0 - height;
    if (remaining <= .02) return 1.0;
    int steps = int(clamp(ceil(pixels * remaining), 4.0, 10.0));
    float visibility = 1.0;
    for (int i = 1; i <= steps; i++) {
        float fraction = float(i) / float(steps);
        float rise = remaining * fraction * fraction;
        at = uv + lightRay * rise;
        float blocker = dot(textureGrad(heightMap, POM_UV, dx, dy), channel) - height - rise;
        // A small height bias rejects quantization/acne; a widening penumbra softens more distant blockers.
        visibility = min(visibility, 1.0 - smoothstep(.012, .035 + rise * .12, blocker));
        if (visibility <= .01) break;
    }
    return mix(1.0, visibility, clamp(strength, 0.0, 1.0) * smoothstep(.75, 2.5, pixels));
}
