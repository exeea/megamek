// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The surface length one pixel covers, in metres. Set once per fragment in main().
float pixelMetres;

float hash(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }

// Darkening of a joint of half-width w at distance x (metres) from its centre line. A joint narrower than a pixel
// darkens it only by the share it covers, so distant joints neither vanish nor flicker.
float joint(float x, float w) {
    return (1.0 - smoothstep(w - .5 * pixelMetres, w + .5 * pixelMetres, x)) * min(1.0, 2.0 * w / pixelMetres);
}

// Cast concrete stands in courses one level tall with staggered joints. Each slab samples its own window of the
// map and takes its own tone and grime, so the map's repeat never shows. u runs along the face and z up it; h and d
// are the height above the wall's foot and the depth below its rim, all in metres. Returns the map coordinates (V down
// the face) and writes the slab's colour factor, joints included, to shade.
vec2 slab(float u, float z, float h, float d, float projection, out float shade) {
    float levelMetres = u_levelHeight / u_metre;
    float course = floor(z / levelMetres + .001);
    float up = z / levelMetres + .001 - course;
    float width = 4.8;
    float along = u / width + .5 * mod(course, 2.0) + projection * .37;
    vec3 id = vec3(floor(along), course, projection);
    vec2 window = vec2(hash(id), hash(id + 17.3));
    // Each slab's own tone, grime settling toward its foot and run-off stains hanging from its top edge.
    shade = mix(.9, 1.07, hash(id + 5.1)) * mix(.84, 1.0, smoothstep(0.0, .3, up));
    float runoff = smoothstep(.55, .85, texture(u_rainNoise, vec2(u / 3.1 + window.x * 7.0, z / 45.0)).r);
    shade *= 1.0 - .14 * runoff * smoothstep(.45, 1.0, up);
    float seam = joint(min(fract(along), 1.0 - fract(along)) * width, .03);
    seam = max(seam, joint(min(up, 1.0 - up) * levelMetres, .03) * smoothstep(.1, .3, d) * smoothstep(.1, .3, h));
    shade *= 1.0 - .55 * seam;
    return vec2(u, -z) / u_sculptTiles.w + window;
}

void concreteSlab(vec3 world, vec3 face, vec3 projection, float h, float d,
      inout vec3 albedo, inout vec3 normal, inout float occlusion, inout float cavity) {
    vec3 axes = pow(abs(projection), vec3(4.0));
    // Evaluate contact shading per fragment so it stays at the foot and rim of a large planar panel.
    float shelterDistance = (d - 2.2) / 1.6;
    float shelter = max(0.0, 1.0 - shelterDistance * shelterDistance);
    occlusion = (1.0 - .4 * exp(-h / 1.6)) * (1.0 - .25 * shelter * shelter);
    float along = clamp((axes.x / max(axes.x + axes.y, 1e-4) - .5) * 6.0 + .5, 0.0, 1.0);
    float shadeX, shadeY;
    vec2 sx = slab(world.y * sign(projection.x), world.z, h, d, 1.0, shadeX);
    vec2 sy = slab(-world.x * sign(projection.y), world.z, h, d, 0.0, shadeY);
    albedo = mix(mapTexel(u_sculptLayers.w, sy).rgb * shadeY,
          mapTexel(u_sculptLayers.w, sx).rgb * shadeX, along);
    if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
        normal = wallNormal(u_sculptLayers.w + 1.0, sx, sy, face, along);
        float pores = mix(mapTexel(u_sculptLayers.w + 1.0, sy).a, mapTexel(u_sculptLayers.w + 1.0, sx).a, along);
        cavity = pores * .5 + .5;
    }
}
