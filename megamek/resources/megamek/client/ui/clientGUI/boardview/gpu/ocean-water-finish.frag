#version 330 core
layout(location = 0) out vec4 fragColor; // longest cascade: slope XY, height in metres, persistent foam
layout(location = 1) out vec4 chop;      // middle cascade, the same channels
layout(location = 2) out vec4 ripple;    // shortest cascade, the same channels
layout(location = 3) out vec4 shape;     // longest cascade: choppy displacement XY and height in metres, compression
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Water's cascades after the transform: the displaced surface's slopes, where crests fold, and the foam they leave.
// OCEAN_FINISH
uniform sampler2D u_previous0; // last frame's result of each cascade
uniform sampler2D u_previous1;
uniform sampler2D u_previous2;
uniform vec3 u_texels;         // metres per texel of each cascade
uniform vec3 u_patches;        // metres each cascade repeats over
uniform vec3 u_choppiness;
uniform float u_delta;         // seconds since the last frame
uniform vec2 u_drift;          // metres the foam travels downwind this frame

struct Crest {
    vec4 shading;
    float compression;
    vec2 displacement;
};

Crest crest(int cascade, float texel, float choppiness, float extent, sampler2D previous) {
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
    // Foam drifts downwind, spreads a little and thins over several seconds after the crest has passed.
    vec2 uv = (vec2(at) + .5) / float(u_size) - u_drift / extent;
    vec2 texelUV = vec2(1.0 / float(u_size), 0.0);
    float old = textureLod(previous, uv, 0.0).a;
    float spread = (textureLod(previous, uv + texelUV, 0.0).a + textureLod(previous, uv - texelUV, 0.0).a
          + textureLod(previous, uv + texelUV.yx, 0.0).a + textureLod(previous, uv - texelUV.yx, 0.0).a) * .25;
    // Whitecaps seed on the steepest percent or two of crests in a gale, and on almost none in a breeze.
    float breaking = 1.0 - smoothstep(.55, .78, jacobian);
    float foam = max(breaking, mix(old, spread, min(u_delta * 1.4, .35)) * exp(-u_delta / 4.0));
    return Crest(vec4(-normal.xy / max(normal.z, .3), here.x, foam), clamp(1.0 - jacobian, 0.0, 1.0),
          here.yz * choppiness);
}

void main() {
    Crest longest = crest(0, u_texels.x, u_choppiness.x, u_patches.x, u_previous0);
    Crest middle = crest(1, u_texels.y, u_choppiness.y, u_patches.y, u_previous1);
    Crest shortest = crest(2, u_texels.z, u_choppiness.z, u_patches.z, u_previous2);
    fragColor = longest.shading;
    chop = middle.shading;
    ripple = shortest.shading;
    shape = vec4(longest.displacement, longest.shading.z, longest.compression);
}
