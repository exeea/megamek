// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One nearest-filtered texel per board hex (GpuBiomeSurface) and the world fields every lit surface can share with it.
// R biome, G aqueous palette + 1, B ice, A elevation + 64. Unused samplers cost nothing: programs drop them.
uniform sampler2D u_biomeHexes;
uniform vec2 u_biomeBoard;

// BoardRelief.hash/noise in GLSL: roots and wetland material evaluate the same world field.
float biomeHash(ivec2 p) {
    uint h = uint(p.x) * 374761393u + uint(p.y) * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return float((h ^ (h >> 16)) & 0x00ffffffu) / 16777216.0;
}
float biomeNoise(vec2 p) {
    ivec2 cell = ivec2(floor(p));
    vec2 t = fract(p); t = t * t * (3.0 - 2.0 * t);
    return mix(mix(biomeHash(cell), biomeHash(cell + ivec2(1, 0)), t.x),
          mix(biomeHash(cell + ivec2(0, 1)), biomeHash(cell + ivec2(1, 1)), t.x), t.y);
}

// BoardSurfaceBlend.sandExposure: sparse windows through windblown sand into the local substrate. This field
// also selects grass roots on the CPU, so living tufts occupy exposed turf instead of the uninterrupted drifts.
float sandExposure(vec2 metres) {
    float field = .75 * biomeNoise(metres / 6.0) + .25 * biomeNoise(metres / 1.7 + vec2(19.0, -7.0));
    return smoothstep(.60, .74, field);
}

// Signed distance in metres from a hex's centre offset to its outline; negative inside.
float biomeHexDistance(vec2 p) {
    p = abs(p);
    const float a = 30.0 * 72.0 / 84.0 * .5, b = 7.5;
    return max(p.y - a, (a * p.x + b * p.y - 15.0 * a) / sqrt(a*a + b*b));
}

// The centre, in metres, of the board hex at column x and row y.
vec2 boardHexCenter(int x, int y) {
    const float width = 30.0, height = 30.0 * 72.0 / 84.0;
    float parity = mod(float(x), 2.0);
    return vec2((3.0 * float(x) + 2.0) * width * .25, -(2.0 * float(y) + parity + 1.0) * height * .5);
}
