#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Keeps the open sea's whitecaps on the share the wind calls for (GpuOcean.foamCover). Last frame's mean whiteness,
// the smallest level of the finish pass's whitecap mipmaps, moves the crest compression at which the swell's and
// the chop's foam seed, both together: up while too much of the sea shows white, down while too little. However steep
// the configured sea, its whitecaps stay where they are set, and the swell and chop keep their balance.
uniform sampler2D u_state;     // R, G: last frame's seeding compression of the swell and of the chop
uniform sampler2D u_whitecaps; // last frame's ripple result, mipmapped: A is how white the open sea shows there
uniform float u_cover;         // share of the open sea whitecaps show on at this wind
uniform float u_delta;         // seconds since the last frame
const float RATE = .5;         // seeding compression moved per second, per unit of cover error

void main() {
    vec2 seeding = texelFetch(u_state, ivec2(0), 0).rg;
    float cover = textureLod(u_whitecaps, vec2(.5), 16.0).a;
    fragColor = vec4(clamp(seeding + (cover - u_cover) * (RATE * u_delta), 0.0, 1.0), 0.0, 1.0);
}
