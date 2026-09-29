#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Water: slopes, crest compression and persistent breaking foam.
// OCEAN_FINISH
uniform sampler2D u_previous; // last frame's result
uniform float u_texel;        // metres per texel
uniform float u_choppiness;
uniform float u_fade;         // foam lost this frame

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 here = oceanSample(texel);
    vec2 east = oceanSample(texel + ivec2(1, 0)).zw;
    vec2 west = oceanSample(texel - ivec2(1, 0)).zw;
    vec2 north = oceanSample(texel + ivec2(0, 1)).zw;
    vec2 south = oceanSample(texel - ivec2(0, 1)).zw;
    // Below zero the surface folds over itself: a breaking crest.
    float gain = 0.5 / u_texel * u_choppiness;
    float xx = (east.x - west.x) * gain, yy = (north.y - south.y) * gain;
    float xy = (north.x - south.x) * gain, yx = (east.y - west.y) * gain;
    float jacobian = (1.0 + xx) * (1.0 + yy) - xy * yx;
    float breaking = 1.0 - smoothstep(0.15, 0.7, jacobian);
    float foam = max(breaking, texelFetch(u_previous, texel, 0).a - u_fade);
    fragColor = vec4(here.xy, clamp(1.0 - jacobian, 0.0, 1.0), foam);
}
