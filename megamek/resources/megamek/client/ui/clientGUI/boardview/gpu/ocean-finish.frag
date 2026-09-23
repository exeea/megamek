// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The ocean texture (GpuOcean): wave slopes along x and y in red and green; in blue, how tightly the choppy
// horizontal displacement squeezes the surface at a crest; in alpha, foam where a crest folds over, which lingers and
// fades after it has passed.
uniform sampler2D u_source;   // after the transform: slopes x and y, displacement x and y, each times the centring
uniform sampler2D u_previous; // last frame's result
uniform int u_size;
uniform float u_texel;        // metres per texel
uniform float u_choppiness;
uniform float u_fade;         // foam lost this frame

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int mask = u_size - 1;
    // A spectrum centred on zero frequency comes out with every other texel negated.
    float parity = ((texel.x + texel.y) & 1) == 0 ? 1.0 : -1.0;
    vec4 here = texelFetch(u_source, texel, 0) * parity;
    vec2 east = texelFetch(u_source, ivec2((texel.x + 1) & mask, texel.y), 0).zw * -parity;
    vec2 west = texelFetch(u_source, ivec2((texel.x - 1) & mask, texel.y), 0).zw * -parity;
    vec2 north = texelFetch(u_source, ivec2(texel.x, (texel.y + 1) & mask), 0).zw * -parity;
    vec2 south = texelFetch(u_source, ivec2(texel.x, (texel.y - 1) & mask), 0).zw * -parity;
    // Below zero the surface folds over itself: a breaking crest.
    float gain = 0.5 / u_texel * u_choppiness;
    float xx = (east.x - west.x) * gain, yy = (north.y - south.y) * gain;
    float xy = (north.x - south.x) * gain, yx = (east.y - west.y) * gain;
    float jacobian = (1.0 + xx) * (1.0 + yy) - xy * yx;
    float breaking = 1.0 - smoothstep(0.15, 0.7, jacobian);
    float foam = max(breaking, texelFetch(u_previous, texel, 0).a - u_fade);
    gl_FragColor = vec4(here.xy, clamp(1.0 - jacobian, 0.0, 1.0), foam);
}
