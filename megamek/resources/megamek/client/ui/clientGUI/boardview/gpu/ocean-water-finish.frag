#version 330 core
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 waveShape;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Water MRT outputs: slope/height/foam and displacement/height/compression.
// OCEAN_FINISH
uniform sampler2D u_previous; // last frame's result
uniform float u_texel;        // metres per texel
uniform float u_choppiness;
uniform float u_delta;
uniform vec2 u_drift;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 here = oceanSample(texel);
    vec3 east = oceanSample(texel + ivec2(1, 0)).xyz;
    vec3 west = oceanSample(texel - ivec2(1, 0)).xyz;
    vec3 north = oceanSample(texel + ivec2(0, 1)).xyz;
    vec3 south = oceanSample(texel - ivec2(0, 1)).xyz;
    // Below zero the surface folds over itself: a breaking crest.
    float gain = 0.5 / u_texel * u_choppiness;
    float xx = (east.y - west.y) * gain, yy = (north.z - south.z) * gain;
    float xy = (north.y - south.y) * gain, yx = (east.z - west.z) * gain;
    float jacobian = (1.0 + xx) * (1.0 + yy) - xy * yx;
    vec2 slope = vec2(east.x - west.x, north.x - south.x) * (0.5 / u_texel);
    vec3 normal = cross(vec3(1.0 + xx, yx, slope.x), vec3(xy, 1.0 + yy, slope.y));
    vec2 uv = (vec2(texel) + .5) / float(u_size) - u_drift;
    vec2 stepUV = vec2(1.0 / float(u_size), 0.0);
    float old = textureLod(u_previous, uv, 0.0).a;
    float spread = (textureLod(u_previous, uv + stepUV, 0.0).a + textureLod(u_previous, uv - stepUV, 0.0).a
          + textureLod(u_previous, uv + stepUV.yx, 0.0).a + textureLod(u_previous, uv - stepUV.yx, 0.0).a) * .25;
    float breaking = 1.0 - smoothstep(.18, .55, jacobian);
    float foam = max(breaking, mix(old, spread, min(u_delta * 1.4, .35)) * exp(-u_delta / 3.0));
    fragColor = vec4(-normal.xy / max(normal.z, .3), here.x, foam);
    waveShape = vec4(here.yz * u_choppiness, here.x, clamp(1.0 - jacobian, 0.0, 1.0));
}
