#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One axis of the depth-aware blur over the half-resolution occlusion (ambient-occlusion-horizon.frag). Five taps
// weighted .5, 1, 1, 1, .5 give each phase of the 4 by 4 pattern the same weight, so two passes remove it; a tap on
// another surface, by camera distance, drops out. The second pass also applies the strength.
layout(location = 0) out vec4 fragColor;
uniform sampler2D u_occlusion; // R visibility, G camera distance
uniform ivec2 u_direction;
uniform vec3 u_blur; // world size of one scene pixel at camera distance d: x + y * d; the strength exponent

void main() {
    ivec2 centre = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(u_occlusion, 0) - 1;
    vec2 middle = texelFetch(u_occlusion, centre, 0).rg;
    // A half-resolution texel spans two scene pixels.
    float texelSize = 2.0 * (u_blur.x + u_blur.y * middle.g);
    float sum = 0.0, total = 0.0;
    for (int index = -2; index <= 2; index++) {
        vec2 tap = texelFetch(u_occlusion, clamp(centre + u_direction * index, ivec2(0), last), 0).rg;
        // Sloped ground changes depth along the taps: the tolerance grows with the distance from the centre.
        float tolerance = texelSize * (2.0 + 8.0 * float(abs(index)));
        float weight = (abs(index) == 2 ? .5 : 1.0) * max(0.0, 1.0 - abs(tap.g - middle.g) / tolerance);
        sum += tap.r * weight;
        total += weight;
    }
    fragColor = vec4(pow(max(sum / total, 1e-4), u_blur.z), middle.g, 0.0, 0.0);
}
