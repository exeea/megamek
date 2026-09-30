#version 330 core
layout(location = 0) out vec4 fragColor; // longest cascade: slope XY, height in metres, persistent foam
layout(location = 1) out vec4 chop;      // middle cascade, the same channels
layout(location = 2) out vec4 ripple;    // shortest cascade: slope XY, height in metres; too short to foam
layout(location = 3) out vec4 shape;     // longest cascade: choppy displacement XY and height in metres, compression
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Water's cascades after the transform: the displaced surface's slopes, where crests fold, and the foam they leave.
// OCEAN_FINISH
uniform sampler2D u_previous0; // last frame's result of the foaming cascades
uniform sampler2D u_previous1;
uniform vec3 u_texels;         // metres per texel of each cascade
uniform vec3 u_patches;        // metres each cascade repeats over
uniform vec3 u_choppiness;
uniform float u_delta;         // seconds since the last frame
uniform vec2 u_drift;          // metres the foam travels downwind this frame
uniform vec2 u_downwind;       // unit direction of the eased wind

struct Crest {
    vec4 shading;
    float compression;
    vec2 displacement;
};

Crest crest(int cascade, float texel, float choppiness) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec3 here = oceanSample(cascade, at).xyz; // height, displacement X, displacement Y
    // Fourth-order central differences hold each band's shortest waves, five texels long, to within 5 %.
    vec3 dx = (8.0 * (oceanSample(cascade, at + ivec2(1, 0)).xyz - oceanSample(cascade, at - ivec2(1, 0)).xyz)
          - (oceanSample(cascade, at + ivec2(2, 0)).xyz - oceanSample(cascade, at - ivec2(2, 0)).xyz)) / (12.0 * texel);
    vec3 dy = (8.0 * (oceanSample(cascade, at + ivec2(0, 1)).xyz - oceanSample(cascade, at - ivec2(0, 1)).xyz)
          - (oceanSample(cascade, at + ivec2(0, 2)).xyz - oceanSample(cascade, at - ivec2(0, 2)).xyz)) / (12.0 * texel);
    // Tangents of the displaced surface: each rest point moves by the choppy offset and rises by the height.
    vec3 alongX = vec3(1.0 + choppiness * dx.y, choppiness * dx.z, dx.x);
    vec3 alongY = vec3(choppiness * dy.y, 1.0 + choppiness * dy.z, dy.x);
    // Below zero the surface folds over itself: a breaking crest.
    float jacobian = alongX.x * alongY.y - alongX.y * alongY.x;
    vec3 normal = cross(alongX, alongY);
    return Crest(vec4(-normal.xy / max(normal.z, .3), here.x, 0.0), clamp(1.0 - jacobian, 0.0, 1.0),
          here.yz * choppiness);
}

// Foam drifts downwind and thins over several seconds after the crest has passed. It spreads mostly along the wind,
// drawing out the streaks a sea leaves behind its breakers; as the wind turns, new streaks follow it. Whitecaps seed
// on the steepest crests: a few percent of a gale's, almost none of a breeze's.
float foam(Crest crest, sampler2D previous, float extent) {
    vec2 uv = (gl_FragCoord.xy / float(u_size)) - u_drift / extent;
    vec2 along = u_downwind / float(u_size), across = vec2(-along.y, along.x) * .5;
    float old = textureLod(previous, uv, 0.0).a;
    float spread = (textureLod(previous, uv + along, 0.0).a + textureLod(previous, uv - along, 0.0).a) * .35
          + (textureLod(previous, uv + across, 0.0).a + textureLod(previous, uv - across, 0.0).a) * .15;
    float breaking = smoothstep(.22, .45, crest.compression);
    return max(breaking, mix(old, spread, min(u_delta * 1.4, .35)) * exp(-u_delta / 4.0));
}

void main() {
    Crest longest = crest(0, u_texels.x, u_choppiness.x);
    Crest middle = crest(1, u_texels.y, u_choppiness.y);
    Crest shortest = crest(2, u_texels.z, u_choppiness.z);
    fragColor = vec4(longest.shading.xyz, foam(longest, u_previous0, u_patches.x));
    chop = vec4(middle.shading.xyz, foam(middle, u_previous1, u_patches.y));
    ripple = vec4(shortest.shading.xyz, 0.0);
    shape = vec4(longest.displacement, longest.shading.z, longest.compression);
}
