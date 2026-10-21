// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// A distant procedural skybox shared by every transparent SPACE cell and the exterior of its board.
// A single slowly rotated sampling direction keeps every layer coherent; no fractal flicker or rapid swirling.
const float SPACE_DRIFT_SPEED = .005;
const float SPACE_DENSITY = 1.7;

float spaceHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float spaceNoise(vec2 p) {
    vec2 cell = floor(p), t = fract(p);
    t = t * t * (3.0 - 2.0 * t);
    return mix(mix(spaceHash(cell), spaceHash(cell + vec2(1, 0)), t.x),
          mix(spaceHash(cell + vec2(0, 1)), spaceHash(cell + vec2(1, 1)), t.x), t.y);
}

float spaceVolumeNoise(vec3 p) {
    vec3 cell = floor(p), t = fract(p);
    t = t * t * (3.0 - 2.0 * t);
    // Integer keys make shared lattice corners identical, avoiding seams from fractional dot-product rounding.
    float key = dot(cell, vec3(127, 311, 75));
    vec4 lower = fract(sin(key + vec4(0, 127, 311, 438)) * 43758.5453);
    vec4 upper = fract(sin(key + vec4(75, 202, 386, 513)) * 43758.5453);
    vec4 mixed = mix(lower, upper, t.z);
    return mix(mix(mixed.x, mixed.y, t.x), mix(mixed.z, mixed.w, t.x), t.y);
}

vec3 spaceDust(vec3 direction) {
    // A bounded multifractal on the sphere supplies depth and filament detail without a per-pixel ray march.
    // All layers use the same drifting direction; the volume never evolves independently or flickers.
    vec3 p = direction * 4.3 + vec3(21.7, 37.2, 11.5);
    vec3 warp = vec3(spaceVolumeNoise(p), spaceVolumeNoise(p + 17.1), spaceVolumeNoise(p - 13.4));
    p += (warp - .5) * 1.6;
    float cloud = 0.0, weight = .43, detail = 0.0, detailWeight = .25;
    for (int octave = 0; octave < 6; octave++) {
        float layer = spaceVolumeNoise(p);
        cloud += layer * weight;
        float ridge = 1.0 - abs(layer * 2.0 - 1.0);
        detail += ridge * ridge * ridge * detailWeight;
        p = p * 2.13 + vec3(11.3, 7.1, 19.7);
        weight *= .58;
        detailWeight *= .75;
    }
    float band = exp(-pow(dot(direction, normalize(vec3(.3, .7, .24))) / .43, 2.0));
    // Density expands the emitting dust and reveals finer filaments, instead of only scaling brightness.
    float emission = smoothstep(.31 - SPACE_DENSITY * .09, .81 - SPACE_DENSITY * .09, cloud);
    float wisps = smoothstep(.21 - SPACE_DENSITY * .05, .67 - SPACE_DENSITY * .05, detail);
    // Dark foreground lanes carve the glowing dust. A dim cool layer remains outside the warmer central band.
    float lanes = smoothstep(.25, .67, warp.z * .65 + warp.x * .35);
    vec3 cool = vec3(.055, .073, .11) * emission * (.3 + .7 * wisps);
    vec3 warm = vec3(.22, .145, .082) * emission * (.45 + .55 * wisps);
    vec3 color = mix(cool, warm, .25 + band * .65) * (.38 + .62 * band);
    color *= (.22 + .78 * lanes) * (.6 + .8 * wisps);
    color += vec3(.008, .009, .014) * (.4 + cloud);
    // Lift faint dust without letting its brightest filaments compete with units or labels.
    return sqrt(color) * .5 * min(SPACE_DENSITY, 1.0);
}

vec3 spaceStars(vec2 sky, float scale, float density, float strength) {
    vec2 p = sky * vec2(scale * 2.0, scale), cell = floor(p);
    cell.x = mod(cell.x, scale * 2.0);
    float seed = spaceHash(cell);
    vec2 at = .12 + .76 * vec2(spaceHash(cell + 17.3), spaceHash(cell + 81.7));
    // Undo the spherical map's local distortion: distant stars are tiny round points, including near frame edges.
    vec2 dx = dFdx(p), dy = dFdy(p);
    dx.x -= round(dx.x / (scale * 2.0)) * scale * 2.0;
    dy.x -= round(dy.x / (scale * 2.0)) * scale * 2.0;
    float determinant = dx.x * dy.y - dx.y * dy.x;
    vec2 offset = fract(p) - at;
    vec2 pixel = vec2(dy.y * offset.x - dy.x * offset.y, dx.x * offset.y - dx.y * offset.x)
          / (abs(determinant) > 1e-8 ? determinant : 1e-8);
    float distance = length(pixel), brightness = spaceHash(cell + 29.1);
    float radius = mix(.3, .85, pow(brightness, 4.0));
    float star = 1.0 - smoothstep(radius, radius + .7, distance);
    star += step(.985, brightness) * .12 * exp(-distance * distance * .35);
    star *= step(1.0 - density, seed);
    vec3 tint = mix(vec3(.58, .73, 1.0), vec3(1.0, .87, .68), spaceHash(cell + 37.4));
    return tint * star * mix(.12, 1.6, pow(brightness, 3.0)) * strength;
}

vec3 spaceGalaxy(vec3 direction, vec3 center, float size, float flattening, vec3 tint) {
    center = normalize(center);
    float forward = dot(direction, center);
    if (forward < .8) return vec3(0);
    vec3 right = normalize(cross(center, vec3(0, 0, 1)));
    vec3 up = cross(right, center);
    vec2 p = vec2(dot(direction, right), dot(direction, up)) / (size * (.75 + .25 * SPACE_DENSITY));
    float c = cos(center.x * 17.0), s = sin(center.x * 17.0);
    p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) * vec2(1, flattening);
    float r = length(p);
    if (r > 3.0) return vec3(0);
    float arms = pow(.5 + .5 * cos(atan(p.y, p.x) * 2.0 - r * 7.0), 3.0);
    float dust = spaceNoise(p * 14.0) * .65 + .35;
    return (tint * exp(-r * 2.8) * mix(.2, 1.0, arms) * dust
          + mix(tint, vec3(.65, .8, 1.0), .5) * exp(-r * 12.0)) * min(SPACE_DENSITY, 1.0);
}

vec3 spaceSky(vec3 direction) {
    if (SPACE_DENSITY <= 0.0) return vec3(0);
    // A fixed rotation keeps the normal board cameras away from the star map's poles.
    vec3 d = vec3(dot(direction, vec3(.8, .6, 0)),
          dot(direction, vec3(-.3, .4, .8660254)), dot(direction, vec3(.5196152, -.6928203, .5)));
    vec2 sky = vec2(atan(d.y, d.x) / (2.0 * PI) + .5, asin(clamp(d.z, -1.0, 1.0)) / PI + .5);
    vec3 color = spaceDust(direction);
    color += spaceGalaxy(direction, vec3(-.35, .4, -.85), .22, 2.8, vec3(.28, .35, .55));
    color += spaceGalaxy(direction, vec3(.55, -.45, -.7), .14, 1.3, vec3(.3, .22, .12));
    color += spaceGalaxy(direction, vec3(.5, .75, .35), .16, 3.8, vec3(.35, .22, .13));
    color += spaceGalaxy(direction, vec3(-.75, -.55, .4), .12, 1.6, vec3(.2, .3, .55));
    // Numerous distant, dim stars establish scale; the sparse nearer layer supplies occasional bright pinpoints.
    return color + spaceStars(sky, 270.0, .26 * SPACE_DENSITY, .3)
          + spaceStars(sky + 13.7, 113.0, .15 * SPACE_DENSITY, .75)
          + spaceStars(sky + 29.4, 37.0, .10 * SPACE_DENSITY, 1.0);
}

vec3 spaceLight(vec3 direction) {
    float distance = length(direction - u_spaceLight.xyz);
    float aa = max(fwidth(distance), .0003);
    float disk = 1.0 - smoothstep(.004, .004 + aa, distance);
    float halo = .2 * exp(-distance * 90.0);
    return u_spaceLightColor * u_spaceLight.w * (disk * 3.0 + halo);
}
